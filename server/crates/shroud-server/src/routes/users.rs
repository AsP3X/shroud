//! Public user card lookup (id, username, share code).

use axum::{
    Json,
    extract::{Path, State},
};
use serde::Serialize;
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::auth::{is_valid_share_code_format, normalize_share_code, normalize_username};
use crate::error::AppError;
use crate::state::AppState;

#[derive(Debug, Serialize, FromRow)]
pub struct UserCard {
    pub id: Uuid,
    pub username: String,
    pub share_code: String,
}

/// `GET /users/:user_id` — minimal public profile by UUID.
pub async fn get_user(
    State(state): State<AppState>,
    _auth: AuthContext,
    Path(user_id): Path<Uuid>,
) -> Result<Json<UserCard>, AppError> {
    let row = sqlx::query_as::<_, UserCard>(
        r#"SELECT id, username, share_code FROM users WHERE id = $1"#,
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("user lookup failed: {err}")))?
    .ok_or_else(|| AppError::not_found("User not found."))?;

    Ok(Json(row))
}

/// `GET /users/by-username/:username` — lookup by username (case-insensitive).
pub async fn get_user_by_username(
    State(state): State<AppState>,
    _auth: AuthContext,
    Path(username): Path<String>,
) -> Result<Json<UserCard>, AppError> {
    let username = normalize_username(&username).map_err(|_| {
        AppError::not_found("User not found.")
    })?;

    let row = sqlx::query_as::<_, UserCard>(
        r#"SELECT id, username, share_code FROM users WHERE username = $1"#,
    )
    .bind(&username)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("user lookup by username failed: {err}")))?
    .ok_or_else(|| AppError::not_found("User not found."))?;

    Ok(Json(row))
}

/// `GET /users/by-code/:code` — lookup by short share code (QR / deep link).
pub async fn get_user_by_share_code(
    State(state): State<AppState>,
    _auth: AuthContext,
    Path(code): Path<String>,
) -> Result<Json<UserCard>, AppError> {
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
