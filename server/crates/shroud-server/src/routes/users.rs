//! Public user card lookup (id, username, share code) and share-code rotation.

use axum::{
    Json,
    extract::{Path, State},
    http::HeaderMap,
};
use serde::Serialize;
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::auth::{
    generate_share_code, is_valid_share_code_format, normalize_share_code, normalize_username,
};
use crate::error::AppError;
use crate::rate_limit::budgets;
use crate::routes::contacts::{are_contacts, pending_exists};
use crate::state::AppState;

async fn limit_user_lookup(state: &AppState, headers: &HeaderMap) -> Result<(), AppError> {
    let ip = state.client_ip(headers);
    state
        .rate_limiter
        .check_budget("user_lookup_ip", &ip, budgets::USER_LOOKUP_IP)
        .await
}

#[derive(Debug, Serialize, FromRow)]
pub struct UserCard {
    pub id: Uuid,
    pub username: String,
    pub share_code: String,
}

/// `GET /users/:user_id` — minimal public profile by UUID.
pub async fn get_user(
    State(state): State<AppState>,
    headers: HeaderMap,
    _auth: AuthContext,
    Path(user_id): Path<Uuid>,
) -> Result<Json<UserCard>, AppError> {
    limit_user_lookup(&state, &headers).await?;

    // A deleted account keeps its row (migration 021) but has no card to show.
    let row = sqlx::query_as::<_, UserCard>(
        r#"SELECT id, username, share_code FROM users WHERE id = $1 AND deleted_at IS NULL"#,
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("user lookup failed: {err}")))?
    .ok_or_else(|| AppError::not_found("User not found."))?;

    Ok(Json(row))
}

/// `GET /users/by-username/:username` — lookup by username (case-insensitive).
///
/// An account with `discoverable_by_username` off is "not found" — the same answer as a name
/// nobody has — except to itself, its contacts, and anyone it has a pending request with.
pub async fn get_user_by_username(
    State(state): State<AppState>,
    headers: HeaderMap,
    auth: AuthContext,
    Path(username): Path<String>,
) -> Result<Json<UserCard>, AppError> {
    limit_user_lookup(&state, &headers).await?;

    let username =
        normalize_username(&username).map_err(|_| AppError::not_found("User not found."))?;

    #[derive(FromRow)]
    struct Row {
        id: Uuid,
        username: String,
        share_code: String,
        discoverable_by_username: bool,
    }

    let row = sqlx::query_as::<_, Row>(
        r#"
        SELECT id, username, share_code, discoverable_by_username
        FROM users WHERE username = $1
        "#,
    )
    .bind(&username)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("user lookup by username failed: {err}")))?
    .ok_or_else(|| AppError::not_found("User not found."))?;

    if !row.discoverable_by_username
        && row.id != auth.user_id
        && !knows_already(&state.pool, auth.user_id, row.id).await?
    {
        return Err(AppError::not_found("User not found."));
    }

    Ok(Json(UserCard {
        id: row.id,
        username: row.username,
        share_code: row.share_code,
    }))
}

/// Contacts, or a pending request either way: they have each other's id already.
async fn knows_already(pool: &sqlx::PgPool, a: Uuid, b: Uuid) -> Result<bool, AppError> {
    Ok(are_contacts(pool, a, b).await?
        || pending_exists(pool, a, b).await?
        || pending_exists(pool, b, a).await?)
}

#[derive(Debug, Serialize)]
pub struct ShareCodeResponse {
    pub share_code: String,
}

/// `POST /users/me/share-code` — a new share code; the old one stops resolving at once.
///
/// Human: For a QR code or link that reached the wrong people. Anyone who already looked the
/// old code up has the account's id and can still send a request, like any contact of a contact
/// who was given it; blocking is the answer to that.
pub async fn rotate_share_code(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<Json<ShareCodeResponse>, AppError> {
    state
        .rate_limiter
        .check_budget(
            "share_code_rotate_user",
            &auth.user_id.to_string(),
            budgets::SHARE_CODE_ROTATE_USER,
        )
        .await?;

    for _ in 0..12 {
        let code = generate_share_code();
        let updated = sqlx::query_scalar::<_, String>(
            r#"UPDATE users SET share_code = $2 WHERE id = $1 AND deleted_at IS NULL RETURNING share_code"#,
        )
        .bind(auth.user_id)
        .bind(&code)
        .fetch_optional(&state.pool)
        .await;
        match updated {
            Ok(Some(share_code)) => {
                tracing::info!(user_id = %auth.user_id, "users.share_code rotated");
                return Ok(Json(ShareCodeResponse { share_code }));
            }
            Ok(None) => return Err(AppError::not_found("User not found.")),
            Err(sqlx::Error::Database(db_err))
                if db_err.constraint() == Some("users_share_code_uidx") =>
            {
                continue;
            }
            Err(err) => {
                return Err(AppError::Internal(format!(
                    "rotate share code failed: {err}"
                )));
            }
        }
    }
    Err(AppError::Internal(
        "could not allocate a unique share code".into(),
    ))
}

/// `GET /users/by-code/:code` — lookup by short share code (QR / deep link).
pub async fn get_user_by_share_code(
    State(state): State<AppState>,
    headers: HeaderMap,
    _auth: AuthContext,
    Path(code): Path<String>,
) -> Result<Json<UserCard>, AppError> {
    limit_user_lookup(&state, &headers).await?;

    let code = normalize_share_code(&code);
    if !is_valid_share_code_format(&code) {
        return Err(AppError::not_found("User not found."));
    }

    let row = sqlx::query_as::<_, UserCard>(
        r#"SELECT id, username, share_code FROM users WHERE share_code = $1"#,
    )
    .bind(&code)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("user lookup by share code failed: {err}")))?
    .ok_or_else(|| AppError::not_found("User not found."))?;

    Ok(Json(row))
}
