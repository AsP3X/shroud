//! Per-user privacy switches that other users' actions are checked against.
//!
//! Human: These are consent flags, not secrets — they change what a *peer* is allowed to
//! do to this account's data, so they have to live server-side rather than in app storage.
//! Agent: DB `users.allow_peer_chat_delete`; READ by conversations::delete_conversation.

use axum::{Json, extract::State};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::state::AppState;

#[derive(Debug, Serialize, FromRow)]
pub struct PrivacySettings {
    /// When true, a contact's "delete chat for both" also clears this user's copy.
    /// When false the peer's own messages are tombstoned here instead, and this
    /// user's messages survive (see `conversations::delete_conversation`).
    pub allow_peer_chat_delete: bool,
}

#[derive(Debug, Deserialize)]
pub struct UpdatePrivacySettings {
    /// Absent fields are left unchanged.
    pub allow_peer_chat_delete: Option<bool>,
}

/// `GET /privacy/settings`
pub async fn get_settings(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<Json<PrivacySettings>, AppError> {
    let settings = load_settings(&state.pool, auth.user_id).await?;
    Ok(Json(settings))
}

/// `PUT /privacy/settings` — partial update; returns the settings after the write.
pub async fn put_settings(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<UpdatePrivacySettings>,
) -> Result<Json<PrivacySettings>, AppError> {
    let Some(allow) = body.allow_peer_chat_delete else {
        // Nothing to change — report current state rather than failing the call.
        return Ok(Json(load_settings(&state.pool, auth.user_id).await?));
    };

    let settings = sqlx::query_as::<_, PrivacySettings>(
        r#"
        UPDATE users
        SET allow_peer_chat_delete = $1
        WHERE id = $2
        RETURNING allow_peer_chat_delete
        "#,
    )
    .bind(allow)
    .bind(auth.user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("update privacy settings failed: {err}")))?
    .ok_or_else(|| AppError::not_found("User not found."))?;

    tracing::info!(
        user_id = %auth.user_id,
        allow_peer_chat_delete = settings.allow_peer_chat_delete,
        "privacy.settings updated"
    );

    Ok(Json(settings))
}

async fn load_settings(
    pool: &sqlx::PgPool,
    user_id: uuid::Uuid,
) -> Result<PrivacySettings, AppError> {
    sqlx::query_as::<_, PrivacySettings>(
        r#"SELECT allow_peer_chat_delete FROM users WHERE id = $1"#,
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("load privacy settings failed: {err}")))?
    .ok_or_else(|| AppError::not_found("User not found."))
}
