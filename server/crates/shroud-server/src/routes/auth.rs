//! Registration, login, logout, me, password change, and account deletion.

use axum::{
    Json,
    extract::State,
    http::{HeaderMap, StatusCode},
};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::auth::{
    MAX_DEVICES_PER_USER, decode_username_hash, generate_share_code, hash_password,
    issue_session_token, parse_username_hash, verify_password,
};
use crate::error::{AppError, LimitDevice};
use crate::rate_limit::budgets;
use crate::state::AppState;
use crate::username_kdf::UsernameKdfParams;

/// Register / login success body (token shown once).
#[derive(Debug, Serialize)]
pub struct AuthSessionResponse {
    pub token: String,
    pub user: UserDto,
    pub device: DeviceDto,
}

#[derive(Debug, Serialize)]
pub struct UserDto {
    pub id: Uuid,
    pub share_code: String,
}

#[derive(Debug, Serialize)]
pub struct DeviceDto {
    pub id: Uuid,
    /// The device's name, sealed by the account's own devices (Base64). The server cannot read
    /// it; absent until a client of this account has named the device.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sealed_name: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct MeResponse {
    pub user: UserDto,
    pub device: DeviceDto,
}

/// Older builds also send a plaintext `device_name`; it is ignored and never stored (device names
/// are sealed, see `routes::devices::put_device_name`).
#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    /// Standard Base64 of the 32-byte username digest. The name is not sent.
    pub username_hash: String,
    pub password: String,
}

/// A plaintext `device_name` from older builds is ignored, as for [`RegisterRequest`].
#[derive(Debug, Deserialize)]
pub struct LoginRequest {
    /// Standard Base64 of the 32-byte username digest. The name is not sent.
    pub username_hash: String,
    /// SHA-256 of the same name, sent until this device has seen the account move to
    /// [`username_hash`]. Absent, or JSON null, when the account is already migrated.
    #[serde(default)]
    pub legacy_username_hash: Option<String>,
    pub password: String,
    pub device_id: Option<Uuid>,
    /// The device to sign out when every slot is signed in: one of the `devices` of the
    /// `DEVICE_LIMIT` answer (`oldest_device` unless the user picked another), sent once the user
    /// agreed. Ignored while a slot is free.
    pub replace_device_id: Option<Uuid>,
}

#[derive(Debug, Deserialize)]
pub struct PasswordChangeRequest {
    pub current_password: String,
    pub new_password: String,
}

#[derive(Debug, FromRow)]
struct UserAuthRow {
    id: Uuid,
    password_hash: String,
    share_code: String,
}

/// `GET /auth/username-kdf` — the public salt and cost of the username lookup hash.
///
/// No session: a client needs this before it can log in. The salt is not a secret.
pub async fn username_kdf(State(state): State<AppState>) -> Json<UsernameKdfParams> {
    Json(state.username_kdf.public_params())
}

/// `POST /auth/register` — create user, first device, session.
pub async fn register(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<RegisterRequest>,
) -> Result<(StatusCode, Json<AuthSessionResponse>), AppError> {
    let ip = state.client_ip(&headers);
    state
        .rate_limiter
        .check_budget("auth_ip", &ip, budgets::AUTH_IP)
        .await?;

    let username_hash = decode_username_hash(&body.username_hash)?;
    // Argon2id of a reserved name under this server's salt. The list is warmed at boot;
    // a cold call still runs off the runtime so a worker is not blocked for a few seconds.
    let kdf = state.username_kdf.clone();
    tokio::task::spawn_blocking(move || kdf.reject_reserved(&username_hash))
        .await
        .map_err(|err| AppError::Internal(format!("reserved username check failed: {err}")))??;
    state
        .rate_limiter
        .check_budget("auth_user", &body.username_hash, budgets::AUTH_USERNAME)
        .await?;

    let password_hash = hash_password(&body.password)?;

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let user_id = Uuid::new_v4();
    let mut share_code = generate_share_code();
    let mut inserted = false;
    for _ in 0..12 {
        let insert = sqlx::query(
            r#"
            INSERT INTO users (id, username_hash, password_hash, share_code)
            VALUES ($1, $2, $3, $4)
            "#,
        )
        .bind(user_id)
        .bind(username_hash.as_slice())
        .bind(&password_hash)
        .bind(&share_code)
        .execute(&mut *tx)
        .await;

        match insert {
            Ok(_) => {
                inserted = true;
                break;
            }
            Err(sqlx::Error::Database(db_err))
                if db_err.constraint() == Some("users_username_hash_uidx") =>
            {
                return Err(AppError::username_taken());
            }
            Err(sqlx::Error::Database(db_err))
                if db_err.constraint() == Some("users_share_code_uidx") =>
            {
                share_code = generate_share_code();
                continue;
            }
            Err(err) => {
                return Err(AppError::Internal(format!("insert user failed: {err}")));
            }
        }
    }
    if !inserted {
        return Err(AppError::Internal(
            "could not allocate a unique share code".into(),
        ));
    }

    let device_id = Uuid::new_v4();
    sqlx::query(
        r#"
        INSERT INTO devices (id, user_id, last_seen_at)
        VALUES ($1, $2, now())
        "#,
    )
    .bind(device_id)
    .bind(user_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("insert device failed: {err}")))?;

    let (token, token_hash) = issue_session_token()?;
    create_session(&mut tx, device_id, &token_hash).await?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit register failed: {err}")))?;

    tracing::debug!(
        user_id = %user_id,
        device_id = %device_id,
        "auth.register ok"
    );

    Ok((
        StatusCode::CREATED,
        Json(AuthSessionResponse {
            token,
            user: UserDto {
                id: user_id,
                share_code,
            },
            device: DeviceDto {
                id: device_id,
                sealed_name: None,
            },
        }),
    ))
}

/// `POST /auth/login` — verify password, reuse or create device, issue session.
pub async fn login(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<LoginRequest>,
) -> Result<Json<AuthSessionResponse>, AppError> {
    let ip = state.client_ip(&headers);
    state
        .rate_limiter
        .check_budget("auth_ip", &ip, budgets::AUTH_IP)
        .await?;

    let username_hash =
        parse_username_hash(&body.username_hash).map_err(|_| AppError::invalid_credentials())?;
    // A missing or unreadable legacy digest is not a second guess. The budget key is the
    // digest the client claims now, never the old SHA-256.
    let legacy_hash = body
        .legacy_username_hash
        .as_deref()
        .and_then(|raw| parse_username_hash(raw).ok())
        .filter(|hash| hash != &username_hash);
    // Count failed and successful attempts so password guessing burns the budget.
    state
        .rate_limiter
        .check_budget("auth_user", &body.username_hash, budgets::AUTH_USERNAME)
        .await?;

    let primary = user_by_username_hash(&state.pool, username_hash.as_slice()).await?;
    let (user, migrate_from) = match primary {
        Some(row) => (row, None),
        None => match legacy_hash {
            Some(legacy) => match user_by_username_hash(&state.pool, legacy.as_slice()).await? {
                Some(row) => (row, Some(legacy)),
                None => {
                    delay_unknown_user(&body.password);
                    return Err(AppError::invalid_credentials());
                }
            },
            None => {
                delay_unknown_user(&body.password);
                return Err(AppError::invalid_credentials());
            }
        },
    };

    if !verify_password(&body.password, &user.password_hash)? {
        return Err(AppError::invalid_credentials());
    }

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    // Move a SHA-256 row onto the slow digest only after the password checks out, and inside
    // this transaction, so a device-limit refusal rolls the move back.
    let mut migrated = false;
    if let Some(legacy) = migrate_from {
        let updated = sqlx::query(
            r#"
            UPDATE users SET username_hash = $1 WHERE id = $2 AND username_hash = $3
            "#,
        )
        .bind(username_hash.as_slice())
        .bind(user.id)
        .bind(legacy.as_slice())
        .execute(&mut *tx)
        .await;
        match updated {
            Ok(done) => migrated = done.rows_affected() > 0,
            Err(sqlx::Error::Database(db_err))
                if db_err.constraint() == Some("users_username_hash_uidx") =>
            {
                return Err(AppError::Internal(
                    "username hash migration collided with an existing account".into(),
                ));
            }
            Err(err) => {
                return Err(AppError::Internal(format!(
                    "username hash migration failed: {err}"
                )));
            }
        }
    }

    let LoginDevice {
        id: device_id,
        replaced,
    } = resolve_login_device(&mut tx, user.id, body.device_id, body.replace_device_id).await?;

    // Human: One live token per device — revoke any prior active sessions on this device.
    let revoked_sessions: Vec<Uuid> = sqlx::query_scalar(
        r#"
        UPDATE sessions
        SET revoked_at = now()
        WHERE device_id = $1 AND revoked_at IS NULL
        RETURNING id
        "#,
    )
    .bind(device_id)
    .fetch_all(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("revoke prior sessions failed: {err}")))?;

    let (token, token_hash) = issue_session_token()?;
    create_session(&mut tx, device_id, &token_hash).await?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit login failed: {err}")))?;

    // The old token's socket goes with it; the client reconnects with the new one.
    state
        .realtime
        .close_sessions(user.id, &revoked_sessions)
        .await;

    // The device signed out to make room learns it now, as when it is removed from the list:
    // its socket is told DEVICE_REMOVED, and without one a push wakes it to wipe itself.
    let replaced_device = match replaced {
        Some(replaced) => {
            state
                .realtime
                .close_sessions(user.id, &replaced.revoked.sessions)
                .await;
            state
                .push
                .wake_removed_devices(replaced.revoked.wake.into_iter().collect(), false)
                .await;
            Some(replaced.id)
        }
        None => None,
    };

    // A device signing in again keeps the name its account sealed for it; a reclaimed one has none.
    let sealed_name: Option<Vec<u8>> =
        sqlx::query_scalar(r#"SELECT sealed_name FROM devices WHERE id = $1"#)
            .bind(device_id)
            .fetch_one(&state.pool)
            .await
            .map_err(|err| AppError::Internal(format!("load device name failed: {err}")))?;

    tracing::debug!(
        user_id = %user.id,
        device_id = %device_id,
        replaced_device_id = ?replaced_device,
        migrated,
        "auth.login ok"
    );

    Ok(Json(AuthSessionResponse {
        token,
        user: UserDto {
            id: user.id,
            share_code: user.share_code,
        },
        device: DeviceDto {
            id: device_id,
            sealed_name: sealed_name.map(|bytes| BASE64.encode(bytes)),
        },
    }))
}

async fn user_by_username_hash(
    pool: &sqlx::PgPool,
    hash: &[u8],
) -> Result<Option<UserAuthRow>, AppError> {
    sqlx::query_as::<_, UserAuthRow>(
        r#"
        SELECT id, password_hash, share_code FROM users WHERE username_hash = $1
        "#,
    )
    .bind(hash)
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("login user lookup failed: {err}")))
}

/// Same password work as a real miss, so a wrong name is not obviously faster.
fn delay_unknown_user(password: &str) {
    let dummy = hash_password("invalid-login-padding-xx").unwrap_or_default();
    let _ = verify_password(password, &dummy);
}

/// `POST /auth/logout` — revoke the current session and forget how this device is pushed to.
///
/// Human: Pushes are selected per account, not per live session, so a token left behind kept
/// ringing a logged-out phone with the account's messages and calls. The device row stays
/// (a logout is not a removal; see `routes::devices::delete_device`), and the next login
/// re-registers its token.
pub async fn logout(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<StatusCode, AppError> {
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    sqlx::query(
        r#"
        UPDATE sessions SET revoked_at = now() WHERE id = $1 AND revoked_at IS NULL
        "#,
    )
    .bind(auth.session_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("logout failed: {err}")))?;

    clear_signed_out_device(&mut tx, auth.device_id).await?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit logout failed: {err}")))?;

    state
        .realtime
        .close_sessions(auth.user_id, &[auth.session_id])
        .await;

    tracing::debug!(
        user_id = %auth.user_id,
        device_id = %auth.device_id,
        session_id = %auth.session_id,
        "auth.logout ok"
    );

    Ok(StatusCode::NO_CONTENT)
}

/// Drops how a signed-out device is reached. The device row stays, so the next login can
/// use it again. Logout does this for one device; signing every device out does it for each.
async fn clear_signed_out_device(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    device_id: Uuid,
) -> Result<(), AppError> {
    // Every way this device was reached by push, and what it asked to be pushed.
    for table in [
        "push_tokens",
        "web_push_subscriptions",
        "device_notification_settings",
    ] {
        sqlx::query(&format!("DELETE FROM {table} WHERE device_id = $1"))
            .bind(device_id)
            .execute(&mut **tx)
            .await
            .map_err(|err| AppError::Internal(format!("logout {table} delete failed: {err}")))?;
    }

    // The browser that logs out wipes its vault; its PIN must not unlock anything afterwards.
    sqlx::query(r#"DELETE FROM device_pin_guards WHERE device_id = $1"#)
        .bind(device_id)
        .execute(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("logout pin guard delete failed: {err}")))?;
    Ok(())
}

/// What signing an account out everywhere found.
pub(crate) enum SignOut {
    /// No users row.
    Missing,
    /// The account is already a placeholder.
    Deleted,
    /// No live session. Push registrations of the remaining devices are still cleared.
    Idle,
    /// Live sessions that were revoked.
    SignedOut(usize),
}

/// Revokes every live session of an account and forgets how its devices are pushed to.
/// Device rows stay. Sockets close as a sign-out (`UNAUTHORIZED`), not a removal.
///
/// The same per-device cleanup as [`logout`]. A concurrent login waits on the user row.
pub(crate) async fn sign_out_user(state: &AppState, user_id: Uuid) -> Result<SignOut, AppError> {
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let deleted_at: Option<Option<DateTime<Utc>>> =
        sqlx::query_scalar(r#"SELECT deleted_at FROM users WHERE id = $1 FOR UPDATE"#)
            .bind(user_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(|err| AppError::Internal(format!("lock user for sign-out failed: {err}")))?;
    let Some(deleted_at) = deleted_at else {
        return Ok(SignOut::Missing);
    };
    if deleted_at.is_some() {
        return Ok(SignOut::Deleted);
    }

    let device_ids: Vec<Uuid> = sqlx::query_scalar(
        r#"SELECT id FROM devices WHERE user_id = $1 AND revoked_at IS NULL ORDER BY id"#,
    )
    .bind(user_id)
    .fetch_all(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("list devices for sign-out failed: {err}")))?;

    let sessions: Vec<Uuid> = if device_ids.is_empty() {
        Vec::new()
    } else {
        sqlx::query_scalar(
            r#"
            UPDATE sessions SET revoked_at = now()
            WHERE device_id = ANY($1) AND revoked_at IS NULL
            RETURNING id
            "#,
        )
        .bind(&device_ids)
        .fetch_all(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("revoke sessions failed: {err}")))?
    };
    for device_id in &device_ids {
        clear_signed_out_device(&mut tx, *device_id).await?;
    }

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit sign-out failed: {err}")))?;

    state.realtime.close_sessions(user_id, &sessions).await;
    if sessions.is_empty() {
        Ok(SignOut::Idle)
    } else {
        Ok(SignOut::SignedOut(sessions.len()))
    }
}

#[derive(Debug, Deserialize)]
pub struct SessionStatusRequest {
    /// Standard Base64 of SHA-256 over the session token (what the server stores).
    pub token_hash: String,
}

#[derive(Debug, Serialize)]
pub struct SessionStatusResponse {
    /// The client should wipe the account's data: its device was removed or its account
    /// deleted. Unknown and merely signed-out sessions answer false.
    pub removed: bool,
    /// `"account_deleted"` when the account is gone. Absent when only the device was removed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<&'static str>,
}

/// `POST /auth/session-status` — may a locked client keep its data?
///
/// Human: A locked browser keeps its session token sealed in its vault, so it cannot ask an
/// authenticated endpoint whether it still belongs to the account. It keeps the token's hash
/// readable instead: the hash is what the server stores, and presenting it authenticates
/// nothing. A removed browser learns it here while still on its lock screen and wipes itself.
/// The answer says nothing about the account, only about the session the hash belongs to.
/// Agent: UNAUTHENTICATED; RATE LIMITED per IP (`SESSION_STATUS_IP`); CALLS token_hash_removed.
pub async fn session_status(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<SessionStatusRequest>,
) -> Result<Json<SessionStatusResponse>, AppError> {
    let ip = state.client_ip(&headers);
    state
        .rate_limiter
        .check_budget("session_status_ip", &ip, budgets::SESSION_STATUS_IP)
        .await?;
    let token_hash = BASE64
        .decode(body.token_hash.trim().as_bytes())
        .ok()
        .filter(|bytes| bytes.len() == 32)
        .ok_or_else(|| AppError::validation("token_hash must be a Base64 SHA-256 digest."))?;
    let removal = crate::auth::session::token_hash_removed(&state.pool, &token_hash).await?;
    Ok(Json(SessionStatusResponse {
        removed: removal.removed(),
        reason: removal.reason(),
    }))
}

/// `GET /auth/me` — current user + device.
pub async fn me(auth: AuthContext) -> Result<Json<MeResponse>, AppError> {
    Ok(Json(MeResponse {
        user: UserDto {
            id: auth.user_id,
            share_code: auth.share_code,
        },
        device: DeviceDto {
            id: auth.device_id,
            sealed_name: auth.device_sealed_name.map(|bytes| BASE64.encode(bytes)),
        },
    }))
}

#[derive(Debug, Deserialize)]
pub struct DeleteAccountRequest {
    pub password: String,
}

/// `DELETE /auth/account` — delete the account after a password check.
///
/// Human: Every chat is deleted for both, exactly as `DELETE /conversations/{peer}?scope=everyone`
/// would: what the account sent becomes "Message deleted", a peer who allowed
/// `allow_peer_chat_delete` loses the chat, and everyone else keeps their own messages. The
/// users row stays as a placeholder (migration 021) because conversations, messages, uploads
/// and calls reference it with `ON DELETE CASCADE`; deleting it wiped the peer's side too.
/// Username and share code are released, the devices are revoked, and Saved Messages, uploads,
/// contacts, requests, blocks and calls go. No reaction of the account's, or on what it sent,
/// stays sealed.
/// Agent: one transaction: lock users row, revoke devices, tombstone messages, clear chats and
/// reactions, scrub users. Then, on a task of its own, close the account's sockets, purge media
/// blobs and PUBLISH conversation.deleted / contact.removed / call.ended to the peers. This
/// function waits for that task. Cancelling the wait does not cancel the task, so a caller that
/// hangs up still leaves the account fully deleted.
pub async fn delete_account(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<DeleteAccountRequest>,
) -> Result<StatusCode, AppError> {
    state
        .rate_limiter
        .check_budget(
            "auth_sensitive_user",
            &auth.user_id.to_string(),
            budgets::AUTH_SENSITIVE_USER,
        )
        .await?;

    let row: Option<(Option<DateTime<Utc>>, Option<String>)> = sqlx::query_as(
        r#"SELECT deleted_at, password_hash FROM users WHERE id = $1"#,
    )
    .bind(auth.user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("load user for delete failed: {err}")))?;
    // Another device deleted the account a moment ago: this one is removed with it, and the
    // answer says the account is gone. A missing row is only a removed device.
    let Some((deleted_at, password_hash)) = row else {
        return Err(AppError::device_removed());
    };
    if deleted_at.is_some() {
        return Err(AppError::account_deleted());
    }
    let Some(password_hash) = password_hash else {
        return Err(AppError::device_removed());
    };

    if !verify_password(&body.password, &password_hash)? {
        return Err(AppError::invalid_credentials());
    }

    match delete_user_account(&state, auth.user_id, Some(auth.device_id)).await? {
        AccountDelete::Done => Ok(StatusCode::NO_CONTENT),
        AccountDelete::Deleted => Err(AppError::account_deleted()),
        AccountDelete::Missing => Err(AppError::device_removed()),
    }
}

/// What deleting an account found.
pub(crate) enum AccountDelete {
    /// Chats, devices and the sign-in are gone. The users row stays as a placeholder.
    Done,
    /// No users row.
    Missing,
    /// `deleted_at` was already set. Nothing else was changed.
    Deleted,
}

/// Deletes an account the way [`delete_account`] does after the password check.
///
/// `except_device` is the device that asked. It wipes itself from the response, so it is
/// not sent the wake push. The operator listener passes `None` and every device is woken.
/// A missing row, and an account that is already a placeholder, change nothing.
pub(crate) async fn delete_user_account(
    state: &AppState,
    user_id: Uuid,
    except_device: Option<Uuid>,
) -> Result<AccountDelete, AppError> {
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    // Human: NO KEY UPDATE, not UPDATE: a send racing this still takes its foreign-key lock
    // on the row, and a second delete waits here and then finds it gone.
    let deleted_at: Option<Option<DateTime<Utc>>> =
        sqlx::query_scalar(r#"SELECT deleted_at FROM users WHERE id = $1 FOR NO KEY UPDATE"#)
            .bind(user_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(|err| AppError::Internal(format!("lock user for delete failed: {err}")))?;
    let Some(deleted_at) = deleted_at else {
        return Ok(AccountDelete::Missing);
    };
    if deleted_at.is_some() {
        return Ok(AccountDelete::Deleted);
    }

    // Devices first: `send_message` holds its device row while it inserts, so a send racing
    // this either commits before the chats are tombstoned below (and gets one) or is refused.
    let device_ids: Vec<Uuid> = sqlx::query_scalar(
        r#"SELECT id FROM devices WHERE user_id = $1 AND revoked_at IS NULL ORDER BY id"#,
    )
    .bind(user_id)
    .fetch_all(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("list devices for delete failed: {err}")))?;
    let mut revoked_sessions: Vec<Uuid> = Vec::new();
    let mut wakes = Vec::new();
    for device_id in &device_ids {
        let revoked = crate::routes::devices::revoke_device(&mut tx, *device_id).await?;
        revoked_sessions.extend(revoked.sessions);
        // The device that asked wipes itself from the response. An operator delete has no
        // such device, so every one is woken.
        if Some(*device_id) != except_device {
            wakes.extend(revoked.wake);
        }
    }
    // Rows revoked earlier kept their names before removals cleared them.
    sqlx::query(r#"UPDATE devices SET sealed_name = NULL WHERE user_id = $1"#)
        .bind(user_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("scrub device names failed: {err}")))?;

    // After the device locks: a send that was already inserting has committed, and its
    // `created_at` is earlier than this. A watermark taken before those locks misses it, and
    // the peer who allowed the clear still sees the chat.
    let now = Utc::now();
    // Locks each chat's row before touching its messages, the order `DELETE /conversations` uses.
    let (chats, cleared_media) =
        crate::routes::conversations::delete_chats_for_both(&mut tx, user_id, now).await?;

    // Saved Messages has nobody to keep it for (cascades its messages, clears and hides, and
    // unlinks its media).
    sqlx::query(r#"DELETE FROM conversations WHERE user_a_id = $1 AND user_b_id = $1"#)
        .bind(user_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("delete saved messages failed: {err}")))?;

    // Two directed rows per contact, so each peer comes back twice.
    let mut contact_ids: Vec<Uuid> = sqlx::query_scalar(
        r#"
        DELETE FROM contacts WHERE user_id = $1 OR contact_user_id = $1
        RETURNING CASE WHEN user_id = $1 THEN contact_user_id ELSE user_id END
        "#,
    )
    .bind(user_id)
    .fetch_all(&mut *tx)
    .await
    .map_err(|err| {
        AppError::Internal(format!("delete contacts on account delete failed: {err}"))
    })?;
    contact_ids.sort_unstable();
    contact_ids.dedup();
    for (table, filter) in [
        ("contact_requests", "from_user_id = $1 OR to_user_id = $1"),
        ("blocks", "blocker_id = $1 OR blocked_id = $1"),
        ("message_hides", "user_id = $1"),
        ("reaction_reads", "user_id = $1"),
        ("conversation_reads", "user_id = $1"),
        ("chat_mutes", "user_id = $1 OR peer_user_id = $1"),
        ("contact_sealed_names", "owner_id = $1 OR peer_id = $1"),
        ("contact_name_books", "user_id = $1"),
    ] {
        sqlx::query(&format!("DELETE FROM {table} WHERE {filter}"))
            .bind(user_id)
            .execute(&mut *tx)
            .await
            .map_err(|err| AppError::Internal(format!("delete {table} failed: {err}")))?;
    }

    let ended_calls = crate::routes::calls::delete_calls_of_account(&mut tx, user_id, now).await?;

    // Everything this account uploaded, plus the other person's files in chats that were
    // cleared for both (those uploads are not this account's).
    let mut media_ids: Vec<Uuid> =
        sqlx::query_scalar(r#"SELECT id FROM media_objects WHERE uploader_user_id = $1"#)
            .bind(user_id)
            .fetch_all(&mut *tx)
            .await
            .map_err(|err| AppError::Internal(format!("list media for delete failed: {err}")))?;
    media_ids.extend(cleared_media);

    sqlx::query(
        r#"
        UPDATE users
        SET username_hash = NULL,
            share_code = NULL,
            password_hash = NULL,
            allow_peer_chat_delete = false,
            deleted_at = $2
        WHERE id = $1
        "#,
    )
    .bind(user_id)
    .bind(now)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("scrub user failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit account delete failed: {err}")))?;

    // Human: The sign-in is already gone. Sockets, the wipe push, every blob and the peers'
    // live updates still have to finish. The console stops waiting after 10s and hangs up;
    // that must not leave the blobs in the store or skip telling the other person. A blob the
    // store refuses stays unlinked, and the orphan GC retries that one.
    // Agent: spawned before the await, so cancelling this function drops the JoinHandle and
    // the task keeps running. Dropping a JoinHandle does not abort. purge_media_ids deletes
    // each blob and then its row; a store error leaves that row for the orphan GC.
    let state = state.clone();
    let devices_revoked = device_ids.len();
    let follow_up = tokio::spawn(async move {
        state
            .realtime
            .close_sessions(user_id, &revoked_sessions)
            .await;
        state
            .push
            .wake_removed_devices(wakes, true)
            .await;

        let media_purged = match crate::routes::media::purge_media_ids(&state, &media_ids).await {
            Ok(purged) => purged,
            Err(err) => {
                tracing::warn!(%user_id, error = %err, "auth.account_delete media purge failed");
                0
            }
        };

        let mut events: Vec<(Uuid, serde_json::Value)> = Vec::new();
        for chat in &chats {
            let event = crate::routes::conversations::conversation_deleted_event(
                Some(chat.conversation_id),
                user_id,
                chat.peer_user_id,
                "everyone",
                chat.cleared_for_peer,
            );
            events.push((chat.peer_user_id, event));
        }
        for contact_id in &contact_ids {
            let event = serde_json::json!({
                "type": "contact.removed",
                "user_id": user_id,
                "peer_user_id": contact_id,
            });
            events.push((*contact_id, event));
            // The contact rows are already gone, so closing the sockets cannot fan
            // presence out. Without this, a peer keeps the last "online" it heard
            // on the "Deleted account" row.
            events.push((
                *contact_id,
                serde_json::json!({
                    "type": "presence.update",
                    "user_id": user_id,
                    "online": false,
                    "last_seen_at": serde_json::Value::Null,
                }),
            ));
        }
        events.extend(ended_calls);
        for (peer_user_id, event) in events {
            if let Ok(payload) = serde_json::to_string(&event) {
                state
                    .realtime
                    .publish_to_users([peer_user_id], None, &payload)
                    .await;
            }
        }

        tracing::debug!(
            user_id = %user_id,
            chats = chats.len(),
            chats_cleared_for_peer = chats.iter().filter(|chat| chat.cleared_for_peer).count(),
            devices_revoked,
            media_purged,
            "auth.account_delete ok"
        );
    });
    if let Err(err) = follow_up.await {
        tracing::warn!(%user_id, error = %err, "auth.account_delete follow-up stopped");
    }

    Ok(AccountDelete::Done)
}

/// `POST /auth/password` — change password; revoke other sessions and close their sockets.
pub async fn change_password(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<PasswordChangeRequest>,
) -> Result<StatusCode, AppError> {
    state
        .rate_limiter
        .check_budget(
            "auth_sensitive_user",
            &auth.user_id.to_string(),
            budgets::AUTH_SENSITIVE_USER,
        )
        .await?;

    let password_hash: String =
        sqlx::query_scalar(r#"SELECT password_hash FROM users WHERE id = $1"#)
            .bind(auth.user_id)
            .fetch_one(&state.pool)
            .await
            .map_err(|err| AppError::Internal(format!("password change load failed: {err}")))?;

    if !verify_password(&body.current_password, &password_hash)? {
        return Err(AppError::invalid_credentials());
    }

    let new_hash = hash_password(&body.new_password)?;

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    sqlx::query(r#"UPDATE users SET password_hash = $1 WHERE id = $2"#)
        .bind(&new_hash)
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("password update failed: {err}")))?;

    // Human: Keep the session that performed the change; kill every other device session.
    let revoked_sessions: Vec<Uuid> = sqlx::query_scalar(
        r#"
        UPDATE sessions s
        SET revoked_at = now()
        FROM devices d
        WHERE s.device_id = d.id
          AND d.user_id = $1
          AND s.id <> $2
          AND s.revoked_at IS NULL
        RETURNING s.id
        "#,
    )
    .bind(auth.user_id)
    .bind(auth.session_id)
    .fetch_all(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("revoke other sessions failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit password change failed: {err}")))?;

    state
        .realtime
        .close_sessions(auth.user_id, &revoked_sessions)
        .await;

    Ok(StatusCode::NO_CONTENT)
}

async fn create_session(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    device_id: Uuid,
    token_hash: &[u8],
) -> Result<(), AppError> {
    let now: DateTime<Utc> = Utc::now();
    sqlx::query(
        r#"
        INSERT INTO sessions (device_id, token_hash, last_used_at)
        VALUES ($1, $2, $3)
        "#,
    )
    .bind(device_id)
    .bind(token_hash)
    .bind(now)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("insert session failed: {err}")))?;
    Ok(())
}

/// The device a login lands on, and the device it signed out to make room, if any.
struct LoginDevice {
    id: Uuid,
    replaced: Option<ReplacedDevice>,
}

struct ReplacedDevice {
    id: Uuid,
    revoked: crate::routes::devices::RevokedDevice,
}

async fn resolve_login_device(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
    requested_device_id: Option<Uuid>,
    replace_device_id: Option<Uuid>,
) -> Result<LoginDevice, AppError> {
    if let Some(device_id) = requested_device_id {
        let owned: Option<Uuid> = sqlx::query_scalar(
            r#"
            SELECT id FROM devices WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL
            "#,
        )
        .bind(device_id)
        .bind(user_id)
        .fetch_optional(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("device ownership check failed: {err}")))?;

        if let Some(id) = owned {
            sqlx::query(r#"UPDATE devices SET last_seen_at = now() WHERE id = $1"#)
                .bind(id)
                .execute(&mut **tx)
                .await
                .map_err(|err| AppError::Internal(format!("touch device failed: {err}")))?;
            return Ok(LoginDevice { id, replaced: None });
        }
        // Unknown, foreign or removed device_id → treat as new device (subject to cap).
    }

    // Human: Two logins at once would both count below the cap, or both pick the same idle
    // device (the second then revoking the first's session). The user row serializes them.
    sqlx::query(r#"SELECT id FROM users WHERE id = $1 FOR UPDATE"#)
        .bind(user_id)
        .execute(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("lock user failed: {err}")))?;

    let count: i64 = sqlx::query_scalar(
        r#"SELECT COUNT(*)::bigint FROM devices WHERE user_id = $1 AND revoked_at IS NULL"#,
    )
    .bind(user_id)
    .fetch_one(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("count devices failed: {err}")))?;

    if count >= MAX_DEVICES_PER_USER {
        // Human: A logout keeps the device row but the client forgets its id, so the next login
        // arrives without one. Hand it back the longest-idle device that nobody is signed in on;
        // refuse only when all are live. Removed devices are history, not spare slots.
        let idle: Option<Uuid> = sqlx::query_scalar(
            r#"
            SELECT d.id FROM devices d
            WHERE d.user_id = $1
              AND d.revoked_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM sessions s WHERE s.device_id = d.id AND s.revoked_at IS NULL
              )
            ORDER BY COALESCE(d.last_seen_at, d.created_at), d.created_at
            LIMIT 1
            "#,
        )
        .bind(user_id)
        .fetch_optional(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("find idle device failed: {err}")))?;

        if let Some(id) = idle {
            reset_reclaimed_device(tx, id).await?;
            return Ok(LoginDevice { id, replaced: None });
        }

        // Human: Every device is signed in. The login names the least recently active one (and
        // lists the rest, for the user to pick another) and signs out only the device the user
        // agreed to; when that one is gone already, they are asked again.
        // Clients ask only after the encryption phrase checked out against `identity_key`, so
        // nobody without their phrase loses a working device to a login they can't finish.
        // Signing out means removing: a device still holding its id would otherwise sign back
        // in onto the slot, so it wipes itself exactly as when removed from the Devices list.
        let replace = match replace_device_id {
            Some(id) => sqlx::query_scalar::<_, Uuid>(
                r#"SELECT id FROM devices WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL"#,
            )
            .bind(id)
            .bind(user_id)
            .fetch_optional(&mut **tx)
            .await
            .map_err(|err| AppError::Internal(format!("replaced device lookup failed: {err}")))?,
            None => None,
        };
        let Some(replace) = replace else {
            // Every device, least recently active first: the first is the one offered, and the
            // user may pick another. Names stay sealed; the phrase opens them on the client.
            let devices =
                sqlx::query_as::<_, (Uuid, Option<Vec<u8>>, DateTime<Utc>, Option<DateTime<Utc>>)>(
                    r#"
                SELECT id, sealed_name, created_at, last_seen_at FROM devices
                WHERE user_id = $1 AND revoked_at IS NULL
                ORDER BY COALESCE(last_seen_at, created_at), created_at
                "#,
                )
                .bind(user_id)
                .fetch_all(&mut **tx)
                .await
                .map_err(|err| AppError::Internal(format!("list devices at cap failed: {err}")))?
                .into_iter()
                .map(|(id, sealed_name, created_at, last_seen_at)| LimitDevice {
                    id,
                    sealed_name: sealed_name.map(|bytes| BASE64.encode(bytes)),
                    created_at,
                    last_seen_at,
                })
                .collect();
            // The same key `GET /keys/identity/:user_id` serves peers: the phrase derives it, so
            // the client can tell a wrong phrase before anything is signed out.
            let identity_key: Option<Vec<u8>> = sqlx::query_scalar(
                r#"
                SELECT ik.public_key
                FROM devices d
                INNER JOIN device_identity_keys ik ON ik.device_id = d.id
                WHERE d.user_id = $1 AND d.revoked_at IS NULL
                ORDER BY d.last_seen_at DESC NULLS LAST, d.created_at DESC
                LIMIT 1
                "#,
            )
            .bind(user_id)
            .fetch_optional(&mut **tx)
            .await
            .map_err(|err| AppError::Internal(format!("find identity key failed: {err}")))?;
            return Err(AppError::device_limit(
                devices,
                identity_key.map(|key| BASE64.encode(key)),
            ));
        };
        let revoked = crate::routes::devices::revoke_device(tx, replace).await?;
        let id = insert_login_device(tx, user_id).await?;
        return Ok(LoginDevice {
            id,
            replaced: Some(ReplacedDevice {
                id: replace,
                revoked,
            }),
        });
    }

    let id = insert_login_device(tx, user_id).await?;
    Ok(LoginDevice { id, replaced: None })
}

async fn insert_login_device(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
) -> Result<Uuid, AppError> {
    let device_id = Uuid::new_v4();
    sqlx::query(
        r#"
        INSERT INTO devices (id, user_id, last_seen_at)
        VALUES ($1, $2, now())
        "#,
    )
    .bind(device_id)
    .bind(user_id)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("insert login device failed: {err}")))?;

    Ok(device_id)
}

/// Human: The client that held this device is gone, and so are its private keys. Its published
/// pre-keys would have peers encrypt to keys nobody holds (the new holder's upload only
/// overwrites the key ids it reuses), its push token would ring someone else's phone, and its
/// PIN guard belongs to a vault that no longer exists. The next login publishes fresh keys.
/// Its sealed name described the old holder, so it goes too; the new one seals its own.
async fn reset_reclaimed_device(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    device_id: Uuid,
) -> Result<(), AppError> {
    purge_device_secrets(tx, device_id).await?;
    sqlx::query(r#"UPDATE devices SET sealed_name = NULL, last_seen_at = now() WHERE id = $1"#)
        .bind(device_id)
        .execute(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("reclaim device failed: {err}")))?;
    Ok(())
}

/// Deletes the key material, push registrations, notification settings and PIN guard a
/// device's client left on the server.
///
/// Agent: DELETE FROM key tables, push_tokens, web_push_subscriptions,
/// device_notification_settings, device_pin_guards WHERE device_id; the device row and
/// everything it sent stay.
pub(crate) async fn purge_device_secrets(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    device_id: Uuid,
) -> Result<(), AppError> {
    for table in [
        "device_identity_keys",
        "device_signed_prekeys",
        "device_one_time_prekeys",
        "push_tokens",
        "web_push_subscriptions",
        "device_notification_settings",
        "device_pin_guards",
    ] {
        sqlx::query(&format!("DELETE FROM {table} WHERE device_id = $1"))
            .bind(device_id)
            .execute(&mut **tx)
            .await
            .map_err(|err| AppError::Internal(format!("reset {table} failed: {err}")))?;
    }
    Ok(())
}
