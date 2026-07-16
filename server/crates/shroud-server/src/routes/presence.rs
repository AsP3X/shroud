//! Online / last-seen presence (accepted contacts only).

use axum::{
    Json,
    extract::{Path, State},
};
use chrono::{DateTime, Utc};
use serde::Serialize;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::routes::contacts::{are_contacts, list_contact_user_ids};
use crate::state::AppState;

#[derive(Debug, Serialize)]
pub struct PresenceResponse {
    pub user_id: Uuid,
    pub online: bool,
    /// Max `devices.last_seen_at` for the user; null if never seen.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_seen_at: Option<DateTime<Utc>>,
}

/// `GET /presence/:user_id` — contacts only.
pub async fn get_presence(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(user_id): Path<Uuid>,
) -> Result<Json<PresenceResponse>, AppError> {
    if user_id == auth.user_id {
        let last_seen_at = max_last_seen(&state.pool, user_id).await?;
        let online = state.realtime.is_user_online(user_id).await;
        return Ok(Json(PresenceResponse {
            user_id,
            online,
            last_seen_at,
        }));
    }

    if !are_contacts(&state.pool, auth.user_id, user_id).await? {
        return Err(AppError::forbidden(
            "Presence is only visible to accepted contacts.",
        ));
    }

    let last_seen_at = max_last_seen(&state.pool, user_id).await?;
    let online = state.realtime.is_user_online(user_id).await;

    tracing::debug!(
        viewer = %auth.user_id,
        target = %user_id,
        online,
        "presence.get"
    );

    Ok(Json(PresenceResponse {
        user_id,
        online,
        last_seen_at,
    }))
}

/// Max device last_seen for a user.
pub(crate) async fn max_last_seen(
    pool: &sqlx::PgPool,
    user_id: Uuid,
) -> Result<Option<DateTime<Utc>>, AppError> {
    sqlx::query_scalar(
        r#"
        SELECT MAX(last_seen_at) FROM devices WHERE user_id = $1
        "#,
    )
    .bind(user_id)
    .fetch_one(pool)
    .await
    .map_err(|err| AppError::Internal(format!("last_seen lookup failed: {err}")))
}

/// Touch device last_seen (WS connect / disconnect / activity).
pub(crate) async fn touch_device_last_seen(
    pool: &sqlx::PgPool,
    device_id: Uuid,
) -> Result<DateTime<Utc>, AppError> {
    let now = Utc::now();
    sqlx::query(
        r#"
        UPDATE devices SET last_seen_at = $1 WHERE id = $2
        "#,
    )
    .bind(now)
    .bind(device_id)
    .execute(pool)
    .await
    .map_err(|err| AppError::Internal(format!("touch last_seen failed: {err}")))?;
    Ok(now)
}

/// Fan-out `presence.update` to accepted contacts (online sockets only via hub).
pub(crate) async fn notify_presence_to_contacts(
    state: &AppState,
    user_id: Uuid,
    online: bool,
    last_seen_at: Option<DateTime<Utc>>,
) {
    let contacts = match list_contact_user_ids(&state.pool, user_id).await {
        Ok(ids) => ids,
        Err(err) => {
            tracing::warn!(error = %err, %user_id, "presence notify: list contacts failed");
            return;
        }
    };
    if contacts.is_empty() {
        return;
    }

    let event = serde_json::json!({
        "type": "presence.update",
        "user_id": user_id,
        "online": online,
        "last_seen_at": last_seen_at,
    });
    let Ok(payload) = serde_json::to_string(&event) else {
        return;
    };

    state
        .realtime
        .publish_to_users(contacts, None, &payload)
        .await;
}
