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
        WHERE user_id = $1
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

/// `DELETE /devices/:id` — revoke sessions and remove the device row.
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
        SELECT id FROM devices WHERE id = $1 AND user_id = $2
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

    sqlx::query(
        r#"
        UPDATE sessions SET revoked_at = now()
        WHERE device_id = $1 AND revoked_at IS NULL
        "#,
    )
    .bind(device_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("revoke device sessions failed: {err}")))?;

    sqlx::query(r#"DELETE FROM devices WHERE id = $1"#)
        .bind(device_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("delete device failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit delete device failed: {err}")))?;

    Ok(StatusCode::NO_CONTENT)
}
