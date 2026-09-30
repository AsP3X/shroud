//! Linked device list, naming and removal.

use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::rate_limit::budgets;
use crate::state::AppState;

/// Smallest sealed name: a 12-byte nonce and a 16-byte tag around an empty plaintext.
const MIN_SEALED_NAME_BYTES: usize = 28;
/// Clients pad names to a fixed size well under this (see docs/architecture.md).
const MAX_SEALED_NAME_BYTES: usize = 512;

#[derive(Debug, Serialize)]
pub struct DevicesResponse {
    pub devices: Vec<DeviceListItem>,
}

#[derive(Debug, Serialize)]
pub struct DeviceListItem {
    pub id: Uuid,
    /// Sealed by the account's devices (Base64); the server cannot read it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sealed_name: Option<String>,
    pub created_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_seen_at: Option<DateTime<Utc>>,
    pub is_current: bool,
}

#[derive(Debug, FromRow)]
struct DeviceRow {
    id: Uuid,
    sealed_name: Option<Vec<u8>>,
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
        SELECT id, sealed_name, created_at, last_seen_at
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
            sealed_name: row.sealed_name.map(|bytes| BASE64.encode(bytes)),
            created_at: row.created_at,
            last_seen_at: row.last_seen_at,
            is_current: row.id == auth.device_id,
        })
        .collect();

    Ok(Json(DevicesResponse { devices }))
}

#[derive(Debug, Deserialize)]
pub struct PutDeviceNameRequest {
    /// Base64 of the sealed name; the server stores the bytes and never opens them.
    pub sealed_name: String,
}

/// `PUT /devices/:id/name` — store a device's sealed name.
///
/// Human: Names like "Niklas's iPhone" say who owns the account, so the server only ever holds
/// them sealed under a key from the encryption phrase. Any of the account's devices may name any
/// other (a phone can rename a browser); the seal binds the name to the device id, so a stored
/// name cannot be moved to another device.
/// Agent: UPDATE devices SET sealed_name for an own, non-revoked device; 404 otherwise.
pub async fn put_device_name(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(device_id): Path<Uuid>,
    Json(body): Json<PutDeviceNameRequest>,
) -> Result<StatusCode, AppError> {
    state
        .rate_limiter
        .check_budget(
            "device_name_user",
            &auth.user_id.to_string(),
            budgets::DEVICE_NAME_USER,
        )
        .await?;

    let sealed = BASE64
        .decode(body.sealed_name.trim().as_bytes())
        .map_err(|_| AppError::validation("sealed_name must be valid standard Base64."))?;
    if !(MIN_SEALED_NAME_BYTES..=MAX_SEALED_NAME_BYTES).contains(&sealed.len()) {
        return Err(AppError::validation(format!(
            "sealed_name must decode to {MIN_SEALED_NAME_BYTES}–{MAX_SEALED_NAME_BYTES} bytes."
        )));
    }

    let updated = sqlx::query(
        r#"
        UPDATE devices SET sealed_name = $1
        WHERE id = $2 AND user_id = $3 AND revoked_at IS NULL
        "#,
    )
    .bind(&sealed)
    .bind(device_id)
    .bind(auth.user_id)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("store device name failed: {err}")))?;

    if updated.rows_affected() == 0 {
        return Err(AppError::not_found("Device not found."));
    }
    Ok(StatusCode::NO_CONTENT)
}

/// `DELETE /devices/:id` — sign the device out for good and forget its keys.
///
/// Human: Messages, uploads and calls reference their sending device (`ON DELETE CASCADE`), so
/// deleting the row erased everything it ever sent, for both participants. The row stays,
/// marked `revoked_at`: it leaves the device list, delivery fan-out, key bundles and the cap,
/// and a login presenting its id gets a fresh device instead.
/// Agent: UPDATE sessions + devices SET revoked_at; DELETE keys/push/PIN guard and undelivered
/// delivery rows for the device; CLOSES its open WebSocket and SENDS it a wake push after
/// commit; 404 for foreign or already-removed ids.
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

    let revoked = revoke_device(&mut tx, device_id).await?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit delete device failed: {err}")))?;

    // A removed (say, stolen) device must stop receiving the account's messages now, not
    // whenever its socket happens to drop. Its socket is told DEVICE_REMOVED, and a device
    // without one is woken by push: either way it wipes the account's data at once.
    state
        .realtime
        .close_sessions(auth.user_id, &revoked.sessions)
        .await;
    state
        .push
        .wake_removed_devices(revoked.wake.into_iter().collect())
        .await;

    Ok(StatusCode::NO_CONTENT)
}

/// What the caller of [`revoke_device`] finishes once its transaction commits.
pub(crate) struct RevokedDevice {
    /// Sessions whose open sockets must close.
    pub sessions: Vec<Uuid>,
    /// Where the device was pushed to, for the push that makes it wipe itself.
    pub wake: Option<crate::push::RemovedDeviceWake>,
}

/// Signs a device out for good: sessions, keys, push token, PIN guard, sealed name and
/// undelivered delivery rows go, and the row is marked `revoked_at`. Also used by
/// `DELETE /auth/account`. Returns the revoked sessions, whose sockets the caller closes once it
/// commits, and the push registration read just before it was deleted.
///
/// Agent: UPDATE sessions/devices SET revoked_at, sealed_name = NULL; DELETE key tables,
/// push_tokens, device_pin_guards and undelivered message_deliveries for the device; RETURNS
/// session ids + push wake target.
pub(crate) async fn revoke_device(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    device_id: Uuid,
) -> Result<RevokedDevice, AppError> {
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

    let wake = crate::push::RemovedDeviceWake::load(tx, device_id)
        .await
        .map_err(|err| AppError::Internal(format!("load removed device push failed: {err}")))?;

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

    // The row outlives the device only because messages point at it; its name has no reader.
    sqlx::query(r#"UPDATE devices SET revoked_at = now(), sealed_name = NULL WHERE id = $1"#)
        .bind(device_id)
        .execute(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("revoke device failed: {err}")))?;

    Ok(RevokedDevice {
        sessions: revoked_sessions,
        wake,
    })
}
