//! Public user card lookup.

use axum::{
    Json,
    extract::{Path, State},
};
use serde::Serialize;
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::state::AppState;

#[derive(Debug, Serialize, FromRow)]
pub struct UserCard {
    pub id: Uuid,
    pub username: String,
}

/// `GET /users/:user_id` — minimal public profile for share links.
pub async fn get_user(
    State(state): State<AppState>,
    _auth: AuthContext,
    Path(user_id): Path<Uuid>,
) -> Result<Json<UserCard>, AppError> {
    let row = sqlx::query_as::<_, UserCard>(r#"SELECT id, username FROM users WHERE id = $1"#)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("user lookup failed: {err}")))?
        .ok_or_else(|| AppError::not_found("User not found."))?;

    Ok(Json(row))
}
