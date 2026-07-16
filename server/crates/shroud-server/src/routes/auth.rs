//! Registration, login, logout, me, and password change.

use axum::{Json, extract::State, http::StatusCode};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::auth::{
    MAX_DEVICES_PER_USER, generate_share_code, hash_password, issue_session_token,
    normalize_username, verify_password,
};
use crate::error::AppError;
use crate::state::AppState;

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
    pub username: String,
    pub share_code: String,
}

#[derive(Debug, Serialize)]
pub struct DeviceDto {
    pub id: Uuid,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct MeResponse {
    pub user: UserDto,
    pub device: DeviceDto,
}

#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    pub username: String,
    pub password: String,
    pub device_name: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct LoginRequest {
    pub username: String,
    pub password: String,
    pub device_name: Option<String>,
    pub device_id: Option<Uuid>,
}

#[derive(Debug, Deserialize)]
pub struct PasswordChangeRequest {
    pub current_password: String,
    pub new_password: String,
}

#[derive(Debug, FromRow)]
struct UserAuthRow {
    id: Uuid,
    username: String,
    password_hash: String,
    share_code: String,
}

/// `POST /auth/register` — create user, first device, session.
pub async fn register(
    State(state): State<AppState>,
    Json(body): Json<RegisterRequest>,
) -> Result<(StatusCode, Json<AuthSessionResponse>), AppError> {
    let username = normalize_username(&body.username)?;
    let password_hash = hash_password(&body.password)?;
    let device_name = normalize_optional_name(body.device_name);

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
            INSERT INTO users (id, username, password_hash, share_code)
            VALUES ($1, $2, $3, $4)
            "#,
        )
        .bind(user_id)
        .bind(&username)
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
                if db_err.constraint() == Some("users_username_key") =>
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
        INSERT INTO devices (id, user_id, name, last_seen_at)
        VALUES ($1, $2, $3, now())
        "#,
    )
    .bind(device_id)
    .bind(user_id)
    .bind(&device_name)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("insert device failed: {err}")))?;

    let (token, token_hash) = issue_session_token()?;
    create_session(&mut tx, device_id, &token_hash).await?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit register failed: {err}")))?;

    tracing::info!(
        user_id = %user_id,
        username = %username,
        device_id = %device_id,
        "auth.register ok"
    );

    Ok((
        StatusCode::CREATED,
        Json(AuthSessionResponse {
            token,
            user: UserDto {
                id: user_id,
                username,
                share_code,
            },
            device: DeviceDto {
                id: device_id,
                name: device_name,
            },
        }),
    ))
}

/// `POST /auth/login` — verify password, reuse or create device, issue session.
pub async fn login(
    State(state): State<AppState>,
    Json(body): Json<LoginRequest>,
) -> Result<Json<AuthSessionResponse>, AppError> {
    let username =
        normalize_username(&body.username).map_err(|_| AppError::invalid_credentials())?;
    let device_name = normalize_optional_name(body.device_name);

    let user = sqlx::query_as::<_, UserAuthRow>(
        r#"
        SELECT id, username, password_hash, share_code FROM users WHERE username = $1
        "#,
    )
    .bind(&username)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("login user lookup failed: {err}")))?;

    let user = match user {
        Some(row) => row,
        None => {
            // Human: Avoid free user-enumeration via early return path only; still run a verify-shaped delay.
            let dummy = hash_password("invalid-login-padding-xx").unwrap_or_default();
            let _ = verify_password(&body.password, &dummy);
            return Err(AppError::invalid_credentials());
        }
    };

    if !verify_password(&body.password, &user.password_hash)? {
        return Err(AppError::invalid_credentials());
    }

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let device_id =
        resolve_login_device(&mut tx, user.id, body.device_id, device_name.as_deref()).await?;

    // Human: One live token per device — revoke any prior active sessions on this device.
    sqlx::query(
        r#"
        UPDATE sessions
        SET revoked_at = now()
        WHERE device_id = $1 AND revoked_at IS NULL
        "#,
    )
    .bind(device_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("revoke prior sessions failed: {err}")))?;

    let (token, token_hash) = issue_session_token()?;
    create_session(&mut tx, device_id, &token_hash).await?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit login failed: {err}")))?;

    let name: Option<String> = sqlx::query_scalar(r#"SELECT name FROM devices WHERE id = $1"#)
        .bind(device_id)
        .fetch_one(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("load device name failed: {err}")))?;

    tracing::info!(
        user_id = %user.id,
        username = %user.username,
        device_id = %device_id,
        "auth.login ok"
    );

    Ok(Json(AuthSessionResponse {
        token,
        user: UserDto {
            id: user.id,
            username: user.username,
            share_code: user.share_code,
        },
        device: DeviceDto {
            id: device_id,
            name,
        },
    }))
}

/// `POST /auth/logout` — revoke current session only.
pub async fn logout(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<StatusCode, AppError> {
    sqlx::query(
        r#"
        UPDATE sessions SET revoked_at = now() WHERE id = $1 AND revoked_at IS NULL
        "#,
    )
    .bind(auth.session_id)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("logout failed: {err}")))?;

    tracing::info!(
        user_id = %auth.user_id,
        device_id = %auth.device_id,
        session_id = %auth.session_id,
        "auth.logout ok"
    );

    Ok(StatusCode::NO_CONTENT)
}

/// `GET /auth/me` — current user + device.
pub async fn me(auth: AuthContext) -> Result<Json<MeResponse>, AppError> {
    Ok(Json(MeResponse {
        user: UserDto {
            id: auth.user_id,
            username: auth.username,
            share_code: auth.share_code,
        },
        device: DeviceDto {
            id: auth.device_id,
            name: auth.device_name,
        },
    }))
}

#[derive(Debug, Deserialize)]
pub struct DeleteAccountRequest {
    pub password: String,
}

/// `DELETE /auth/account` — hard-delete account after password check.
pub async fn delete_account(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<DeleteAccountRequest>,
) -> Result<StatusCode, AppError> {
    let password_hash: String =
        sqlx::query_scalar(r#"SELECT password_hash FROM users WHERE id = $1"#)
            .bind(auth.user_id)
            .fetch_one(&state.pool)
            .await
            .map_err(|err| AppError::Internal(format!("load user for delete failed: {err}")))?;

    if !verify_password(&body.password, &password_hash)? {
        return Err(AppError::invalid_credentials());
    }

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    // Human: Tombstone sent messages so peers keep conversation history without ciphertext.
    sqlx::query(
        r#"
        UPDATE messages
        SET ciphertext = NULL,
            media_object_id = NULL,
            deleted_for_everyone_at = COALESCE(deleted_for_everyone_at, now())
        WHERE sender_user_id = $1
        "#,
    )
    .bind(auth.user_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("tombstone sent messages failed: {err}")))?;

    sqlx::query(
        r#"
        UPDATE media_objects mo
        SET message_id = NULL
        FROM messages m
        WHERE mo.message_id = m.id AND m.sender_user_id = $1
        "#,
    )
    .bind(auth.user_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("unlink media on account delete failed: {err}")))?;

    sqlx::query(r#"DELETE FROM users WHERE id = $1"#)
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("delete user failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit account delete failed: {err}")))?;

    tracing::info!(
        user_id = %auth.user_id,
        username = %auth.username,
        "auth.account_delete ok"
    );

    Ok(StatusCode::NO_CONTENT)
}

/// `POST /auth/password` — change password; revoke other sessions.
pub async fn change_password(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<PasswordChangeRequest>,
) -> Result<StatusCode, AppError> {
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
    sqlx::query(
        r#"
        UPDATE sessions s
        SET revoked_at = now()
        FROM devices d
        WHERE s.device_id = d.id
          AND d.user_id = $1
          AND s.id <> $2
          AND s.revoked_at IS NULL
        "#,
    )
    .bind(auth.user_id)
    .bind(auth.session_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("revoke other sessions failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit password change failed: {err}")))?;

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

async fn resolve_login_device(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
    requested_device_id: Option<Uuid>,
    device_name: Option<&str>,
) -> Result<Uuid, AppError> {
    if let Some(device_id) = requested_device_id {
        let owned: Option<Uuid> = sqlx::query_scalar(
            r#"
            SELECT id FROM devices WHERE id = $1 AND user_id = $2
            "#,
        )
        .bind(device_id)
        .bind(user_id)
        .fetch_optional(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("device ownership check failed: {err}")))?;

        if let Some(id) = owned {
            if let Some(name) = device_name {
                sqlx::query(r#"UPDATE devices SET name = $1, last_seen_at = now() WHERE id = $2"#)
                    .bind(name)
                    .bind(id)
                    .execute(&mut **tx)
                    .await
                    .map_err(|err| {
                        AppError::Internal(format!("update device name failed: {err}"))
                    })?;
            } else {
                sqlx::query(r#"UPDATE devices SET last_seen_at = now() WHERE id = $1"#)
                    .bind(id)
                    .execute(&mut **tx)
                    .await
                    .map_err(|err| AppError::Internal(format!("touch device failed: {err}")))?;
            }
            return Ok(id);
        }
        // Unknown or foreign device_id → treat as new device (subject to cap).
    }

    let count: i64 =
        sqlx::query_scalar(r#"SELECT COUNT(*)::bigint FROM devices WHERE user_id = $1"#)
            .bind(user_id)
            .fetch_one(&mut **tx)
            .await
            .map_err(|err| AppError::Internal(format!("count devices failed: {err}")))?;

    if count >= MAX_DEVICES_PER_USER {
        return Err(AppError::device_limit());
    }

    let device_id = Uuid::new_v4();
    sqlx::query(
        r#"
        INSERT INTO devices (id, user_id, name, last_seen_at)
        VALUES ($1, $2, $3, now())
        "#,
    )
    .bind(device_id)
    .bind(user_id)
    .bind(device_name)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("insert login device failed: {err}")))?;

    Ok(device_id)
}

fn normalize_optional_name(name: Option<String>) -> Option<String> {
    name.map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .map(|value| {
            if value.len() > 128 {
                value.chars().take(128).collect()
            } else {
                value
            }
        })
}
