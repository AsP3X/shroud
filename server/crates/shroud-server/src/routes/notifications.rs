//! Notification settings (per device) and chat mutes (per account).
//!
//! Human: Settings belong to the device that makes the noise — a laptop and a phone can want
//! different things. A mute belongs to the chat and follows its owner to every device, like
//! Telegram's. The server needs both because it decides which devices get a push.
//! Agent: DB device_notification_settings, chat_mutes; PUBLISHES `conversation.mute` to the
//! caller's other devices; DISPATCHES a badge sync (muting changes what the badge counts).

use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::push::{NotificationSettings, PushEvent};
use crate::state::AppState;

/// Longest timed mute; anything longer is "until I unmute".
const MAX_MUTE_SECS: i64 = 366 * 24 * 60 * 60;
const MIN_MUTE_SECS: i64 = 60;

/// `PUT /notifications/settings` body: only the fields a client names change.
#[derive(Debug, Default, Deserialize)]
pub struct SettingsPatch {
    pub enabled: Option<bool>,
    pub show_sender: Option<bool>,
    pub reactions: Option<bool>,
    pub contact_requests: Option<bool>,
    pub sound: Option<String>,
    pub badge: Option<bool>,
    pub badge_includes_muted: Option<bool>,
}

/// `GET /notifications/settings` — this device's settings (defaults until it saves some).
pub async fn get_settings(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<Json<NotificationSettings>, AppError> {
    Ok(Json(load_settings(&state.pool, auth.device_id).await?))
}

/// `PUT /notifications/settings` — change this device's settings; returns what is stored.
///
/// Human: One statement per save, each field kept unless named: two saves from the device at
/// once (a toggle while the app syncs its settings) cannot undo each other's fields.
pub async fn put_settings(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(patch): Json<SettingsPatch>,
) -> Result<Json<NotificationSettings>, AppError> {
    let sound = match patch.sound {
        Some(value) => {
            let value = value.trim().to_ascii_lowercase();
            if !valid_sound(&value) {
                return Err(AppError::validation(
                    "sound must be 1–32 characters of a–z, 0–9, '-' or '_'.",
                ));
            }
            Some(value)
        }
        None => None,
    };
    let defaults = NotificationSettings::default();
    let settings = sqlx::query_as::<_, NotificationSettings>(
        r#"
        INSERT INTO device_notification_settings (
            device_id, enabled, show_sender, reactions, contact_requests, sound, badge,
            badge_includes_muted, updated_at
        )
        VALUES (
            $1, COALESCE($2, $9), COALESCE($3, $10), COALESCE($4, $11), COALESCE($5, $12),
            COALESCE($6, $13), COALESCE($7, $14), COALESCE($8, $15), now()
        )
        ON CONFLICT (device_id) DO UPDATE SET
            enabled = COALESCE($2, device_notification_settings.enabled),
            show_sender = COALESCE($3, device_notification_settings.show_sender),
            reactions = COALESCE($4, device_notification_settings.reactions),
            contact_requests = COALESCE($5, device_notification_settings.contact_requests),
            sound = COALESCE($6, device_notification_settings.sound),
            badge = COALESCE($7, device_notification_settings.badge),
            badge_includes_muted = COALESCE($8, device_notification_settings.badge_includes_muted),
            updated_at = now()
        RETURNING enabled, show_sender, reactions, contact_requests, sound, badge,
                  badge_includes_muted
        "#,
    )
    .bind(auth.device_id)
    .bind(patch.enabled)
    .bind(patch.show_sender)
    .bind(patch.reactions)
    .bind(patch.contact_requests)
    .bind(sound)
    .bind(patch.badge)
    .bind(patch.badge_includes_muted)
    .bind(defaults.enabled)
    .bind(defaults.show_sender)
    .bind(defaults.reactions)
    .bind(defaults.contact_requests)
    .bind(&defaults.sound)
    .bind(defaults.badge)
    .bind(defaults.badge_includes_muted)
    .fetch_one(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("save notification settings failed: {err}")))?;

    tracing::debug!(
        user_id = %auth.user_id,
        device_id = %auth.device_id,
        enabled = settings.enabled,
        "notifications.settings saved"
    );
    Ok(Json(settings))
}

pub(crate) async fn load_settings(
    pool: &sqlx::PgPool,
    device_id: Uuid,
) -> Result<NotificationSettings, AppError> {
    let stored = sqlx::query_as::<_, NotificationSettings>(
        r#"
        SELECT enabled, show_sender, reactions, contact_requests, sound, badge,
               badge_includes_muted
        FROM device_notification_settings
        WHERE device_id = $1
        "#,
    )
    .bind(device_id)
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("load notification settings failed: {err}")))?;
    Ok(stored.unwrap_or_default())
}

fn valid_sound(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 32
        && value
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_')
}

/// A chat's mute as clients see it.
#[derive(Debug, Clone, Serialize)]
pub struct MuteState {
    /// When the mute ends; null while it lasts until turned off.
    pub until: Option<DateTime<Utc>>,
}

#[derive(Debug, Default, Deserialize)]
pub struct MuteRequest {
    /// How long, in seconds (1 minute to a year). Omitted or null: until unmuted.
    pub seconds: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct MuteResponse {
    pub peer_user_id: Uuid,
    pub mute: MuteState,
}

/// `PUT /conversations/{peer_user_id}/mute` — silence a chat on every device of the account.
pub async fn put_mute(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(peer_user_id): Path<Uuid>,
    body: Option<Json<MuteRequest>>,
) -> Result<Json<MuteResponse>, AppError> {
    let Json(body) = body.unwrap_or_default();
    ensure_mutable_peer(&state, &auth, peer_user_id).await?;
    let until = match body.seconds {
        None => None,
        Some(seconds) if (MIN_MUTE_SECS..=MAX_MUTE_SECS).contains(&seconds) => {
            Some(Utc::now() + Duration::seconds(seconds))
        }
        Some(_) => {
            return Err(AppError::validation(
                "seconds must be between 60 and 31622400, or null for no end.",
            ));
        }
    };

    sqlx::query(
        r#"
        INSERT INTO chat_mutes (user_id, peer_user_id, muted_until, updated_at)
        VALUES ($1, $2, $3, now())
        ON CONFLICT (user_id, peer_user_id) DO UPDATE SET
            muted_until = EXCLUDED.muted_until,
            updated_at = now()
        "#,
    )
    .bind(auth.user_id)
    .bind(peer_user_id)
    .bind(until)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("save mute failed: {err}")))?;

    let mute = MuteState { until };
    announce_mute(&state, &auth, peer_user_id, Some(&mute)).await;
    tracing::debug!(user_id = %auth.user_id, forever = until.is_none(), "notifications.mute set");
    Ok(Json(MuteResponse { peer_user_id, mute }))
}

/// `DELETE /conversations/{peer_user_id}/mute` — notifications for the chat come back.
pub async fn delete_mute(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(peer_user_id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    ensure_mutable_peer(&state, &auth, peer_user_id).await?;
    let removed = sqlx::query(r#"DELETE FROM chat_mutes WHERE user_id = $1 AND peer_user_id = $2"#)
        .bind(auth.user_id)
        .bind(peer_user_id)
        .execute(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("delete mute failed: {err}")))?
        .rows_affected();
    if removed > 0 {
        announce_mute(&state, &auth, peer_user_id, None).await;
    }
    Ok(StatusCode::NO_CONTENT)
}

async fn ensure_mutable_peer(
    state: &AppState,
    auth: &AuthContext,
    peer_user_id: Uuid,
) -> Result<(), AppError> {
    if peer_user_id == auth.user_id {
        return Err(AppError::validation("Saved Messages never notify."));
    }
    // A deleted account keeps its row, and its chat can stay muted like any other.
    let exists: bool = sqlx::query_scalar(r#"SELECT EXISTS(SELECT 1 FROM users WHERE id = $1)"#)
        .bind(peer_user_id)
        .fetch_one(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("load peer failed: {err}")))?;
    if !exists {
        return Err(AppError::not_found("User not found."));
    }
    Ok(())
}

/// Tells the caller's other devices, and lets the icon badge follow (muted chats drop out of
/// it unless a device counts them).
async fn announce_mute(
    state: &AppState,
    auth: &AuthContext,
    peer_user_id: Uuid,
    mute: Option<&MuteState>,
) {
    let event = serde_json::json!({
        "type": "conversation.mute",
        "peer_user_id": peer_user_id,
        "mute": mute,
    });
    if let Ok(payload) = serde_json::to_string(&event) {
        state
            .realtime
            .publish_to_users([auth.user_id], Some(auth.device_id), &payload)
            .await;
    }
    state
        .push
        .dispatch(PushEvent::BadgeSync {
            recipient: auth.user_id,
            reader_device: auth.device_id,
            conversation_id: None,
        })
        .await;
}

#[cfg(test)]
mod tests {
    use super::valid_sound;

    #[test]
    fn sound_names_are_file_safe() {
        assert!(valid_sound("default"));
        assert!(valid_sound("none"));
        assert!(valid_sound("glass-2"));
        assert!(!valid_sound(""));
        assert!(!valid_sound("../x"));
        assert!(!valid_sound("Chime"));
        assert!(!valid_sound(&"a".repeat(33)));
    }
}
