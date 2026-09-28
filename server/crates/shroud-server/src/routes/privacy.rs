//! Per-user privacy switches that other users' actions are checked against.
//!
//! Human: These are consent flags, not secrets — they change what a *peer* is allowed to
//! do to or learn about this account, so they have to live server-side rather than in app
//! storage. The visibility switches (read receipts, typing, presence) work both ways: whoever
//! hides theirs also stops seeing everyone else's.
//! Agent: DB `users.allow_peer_chat_delete`, `send_read_receipts`, `send_typing`,
//! `share_presence`, `discoverable_by_username`. READ by conversations::delete_conversation,
//! messages (receipts), ws::relay_contact_activity (typing), presence and users (lookup).

use axum::{Json, extract::State};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::routes::presence;
use crate::state::AppState;

#[derive(Debug, Serialize, FromRow)]
pub struct PrivacySettings {
    /// When true, a contact's "delete chat for both" also clears this user's copy.
    /// When false the peer's own messages are tombstoned here instead, and this
    /// user's messages survive (see `conversations::delete_conversation`).
    pub allow_peer_chat_delete: bool,
    /// Contacts learn when this user read their messages, and this user sees theirs.
    pub send_read_receipts: bool,
    /// Typing and voice-recording indicators go out, and come in.
    pub send_typing: bool,
    /// Contacts see "online" / "last seen", and this user sees theirs.
    pub share_presence: bool,
    /// People who only know the username can find this account (`GET /users/by-username`).
    pub discoverable_by_username: bool,
}

#[derive(Debug, Deserialize)]
pub struct UpdatePrivacySettings {
    /// Absent fields are left unchanged.
    pub allow_peer_chat_delete: Option<bool>,
    pub send_read_receipts: Option<bool>,
    pub send_typing: Option<bool>,
    pub share_presence: Option<bool>,
    pub discoverable_by_username: Option<bool>,
}

/// A switch that only has an effect when both people in a chat leave it on.
#[derive(Debug, Clone, Copy)]
pub(crate) enum Visibility {
    ReadReceipts,
    Typing,
    Presence,
}

impl Visibility {
    fn column(self) -> &'static str {
        match self {
            Visibility::ReadReceipts => "send_read_receipts",
            Visibility::Typing => "send_typing",
            Visibility::Presence => "share_presence",
        }
    }
}

/// Whether `a` and `b` both allow `what`. False when either user is missing.
pub(crate) async fn both_allow(
    pool: &sqlx::PgPool,
    a: Uuid,
    b: Uuid,
    what: Visibility,
) -> Result<bool, AppError> {
    let ids: &[Uuid] = if a == b { &[a] } else { &[a, b] };
    Ok(allowing(pool, ids, what).await?.len() == ids.len())
}

/// Of `user_ids`, the ones that allow `what` themselves.
pub(crate) async fn allowing(
    pool: &sqlx::PgPool,
    user_ids: &[Uuid],
    what: Visibility,
) -> Result<Vec<Uuid>, AppError> {
    if user_ids.is_empty() {
        return Ok(Vec::new());
    }
    let sql = format!(
        "SELECT id FROM users WHERE id = ANY($1::uuid[]) AND {col}",
        col = what.column()
    );
    sqlx::query_scalar::<_, Uuid>(&sql)
        .bind(user_ids)
        .fetch_all(pool)
        .await
        .map_err(|err| AppError::Internal(format!("privacy filter failed: {err}")))
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
    let before = load_settings(&state.pool, auth.user_id).await?;

    let settings = sqlx::query_as::<_, PrivacySettings>(
        r#"
        UPDATE users
        SET allow_peer_chat_delete = COALESCE($2, allow_peer_chat_delete),
            send_read_receipts = COALESCE($3, send_read_receipts),
            send_typing = COALESCE($4, send_typing),
            share_presence = COALESCE($5, share_presence),
            discoverable_by_username = COALESCE($6, discoverable_by_username)
        WHERE id = $1
        RETURNING allow_peer_chat_delete, send_read_receipts, send_typing, share_presence,
                  discoverable_by_username
        "#,
    )
    .bind(auth.user_id)
    .bind(body.allow_peer_chat_delete)
    .bind(body.send_read_receipts)
    .bind(body.send_typing)
    .bind(body.share_presence)
    .bind(body.discoverable_by_username)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("update privacy settings failed: {err}")))?
    .ok_or_else(|| AppError::not_found("User not found."))?;

    tracing::info!(
        user_id = %auth.user_id,
        allow_peer_chat_delete = settings.allow_peer_chat_delete,
        send_read_receipts = settings.send_read_receipts,
        send_typing = settings.send_typing,
        share_presence = settings.share_presence,
        discoverable_by_username = settings.discoverable_by_username,
        "privacy.settings updated"
    );

    // Contacts' apps show whatever presence they last heard, so a change has to reach them now:
    // hiding clears "online" / "last seen" there, sharing again shows the current state.
    if before.share_presence != settings.share_presence {
        presence::announce_presence_setting(&state, auth.user_id, settings.share_presence).await;
    }

    Ok(Json(settings))
}

async fn load_settings(
    pool: &sqlx::PgPool,
    user_id: uuid::Uuid,
) -> Result<PrivacySettings, AppError> {
    sqlx::query_as::<_, PrivacySettings>(
        r#"
        SELECT allow_peer_chat_delete, send_read_receipts, send_typing, share_presence,
               discoverable_by_username
        FROM users WHERE id = $1
        "#,
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("load privacy settings failed: {err}")))?
    .ok_or_else(|| AppError::not_found("User not found."))
}
