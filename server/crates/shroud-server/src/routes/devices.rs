//! Linked device list and removal.

use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use chrono::{DateTime, Utc};
use serde::Serialize;
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::state::AppState;

#[derive(Debug, Serialize)]
pub struct DevicesResponse {
    pub devices: Vec<DeviceListItem>,
}

#[derive(Debug, Serialize)]
pub struct DeviceListItem {
    pub id: Uuid,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub created_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_seen_at: Option<DateTime<Utc>>,
    pub is_current: bool,
}

#[derive(Debug, FromRow)]
struct DeviceRow {
    id: Uuid,
    name: Option<String>,
    created_at: DateTime<Utc>,
    last_seen_at: Option<DateTime<Utc>>,
}

/// `GET /devices` — list devices for the authenticated user.
pub async fn list_devices(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<Json<DevicesResponse>, AppError> {
    let rows = sqlx::query_as::<_, DeviceRow>(
        r#"
        SELECT id, name, created_at, last_seen_at
        FROM devices
        WHERE user_id = $1 AND revoked_at IS NULL
        ORDER BY created_at ASC
        "#,
    )
    .bind(auth.user_id)
    .fetch_all(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("list devices failed: {err}")))?;

    let devices = rows
        .into_iter()
        .map(|row| DeviceListItem {
            id: row.id,
            name: row.name,
            created_at: row.created_at,
            last_seen_at: row.last_seen_at,
            is_current: row.id == auth.device_id,
        })
        .collect();

    Ok(Json(DevicesResponse { devices }))
}

/// `DELETE /devices/:id` — sign the device out for good and forget its keys.
///
/// Human: Messages, uploads and calls reference their sending device (`ON DELETE CASCADE`), so
/// deleting the row erased everything it ever sent, for both participants. The row stays,
/// marked `revoked_at`: it leaves the device list, delivery fan-out, key bundles and the cap,
/// and a login presenting its id gets a fresh device instead.
/// Agent: UPDATE sessions + devices SET revoked_at; DELETE keys/push/PIN guard and undelivered
/// delivery rows for the device; CLOSES its open WebSocket after commit; 404 for foreign or
/// already-removed ids.
pub async fn delete_device(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(device_id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let owned: Option<Uuid> = sqlx::query_scalar(
        r#"
        SELECT id FROM devices WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL
        FOR UPDATE
        "#,
    )
    .bind(device_id)
    .bind(auth.user_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("device lookup failed: {err}")))?;

    if owned.is_none() {
        return Err(AppError::not_found("Device not found."));
    }

    let revoked_sessions = revoke_device(&mut tx, device_id).await?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit delete device failed: {err}")))?;

    // A removed (say, stolen) device must stop receiving the account's messages now, not
    // whenever its socket happens to drop.
    state
        .realtime
        .close_sessions(auth.user_id, &revoked_sessions)
        .await;

    Ok(StatusCode::NO_CONTENT)
}

/// Signs a device out for good: sessions, keys, push token, PIN guard and undelivered delivery
/// rows go, and the row is marked `revoked_at`. Also used by `DELETE /auth/account`. Returns
/// the revoked sessions, whose sockets the caller closes once it commits.
///
/// Agent: UPDATE sessions/devices SET revoked_at; DELETE key tables, push_tokens,
/// device_pin_guards and undelivered message_deliveries for the device; RETURNS session ids.
pub(crate) async fn revoke_device(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    device_id: Uuid,
) -> Result<Vec<Uuid>, AppError> {
    let revoked_sessions: Vec<Uuid> = sqlx::query_scalar(
        r#"
        UPDATE sessions SET revoked_at = now()
        WHERE device_id = $1 AND revoked_at IS NULL
        RETURNING id
        "#,
    )
    .bind(device_id)
    .fetch_all(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("revoke device sessions failed: {err}")))?;

    crate::routes::auth::purge_device_secrets(tx, device_id).await?;

    // Nobody will ever fetch these; delivered rows stay so sent messages keep their ticks.
    sqlx::query(
        r#"
        DELETE FROM message_deliveries WHERE device_id = $1 AND delivered_at IS NULL
        "#,
    )
    .bind(device_id)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("drop pending deliveries failed: {err}")))?;

    sqlx::query(r#"UPDATE devices SET revoked_at = now() WHERE id = $1"#)
        .bind(device_id)
        .execute(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("revoke device failed: {err}")))?;

    Ok(revoked_sessions)
}
