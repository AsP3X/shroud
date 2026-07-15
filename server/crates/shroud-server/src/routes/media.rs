//! Encrypted media upload/download authorization (presigned or stub URLs).

use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::state::AppState;

/// Maximum encrypted object size (25 MiB).
pub const MAX_MEDIA_BYTES: i64 = 25 * 1024 * 1024;
/// Presign TTL (15 minutes).
const PRESIGN_TTL_MINUTES: i64 = 15;

#[derive(Debug, Deserialize)]
pub struct CreateUploadRequest {
    pub size_bytes: i64,
    pub content_type: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct CreateUploadResponse {
    pub media_object_id: Uuid,
    pub upload_url: String,
    pub object_key: String,
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, Serialize)]
pub struct DownloadResponse {
    pub download_url: String,
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, FromRow)]
struct MediaRow {
    id: Uuid,
    uploader_user_id: Uuid,
    bucket: String,
    object_key: String,
    message_id: Option<Uuid>,
}

/// `POST /media/uploads` — create media row + short-lived upload URL.
pub async fn create_upload(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<CreateUploadRequest>,
) -> Result<(StatusCode, Json<CreateUploadResponse>), AppError> {
    if body.size_bytes < 1 || body.size_bytes > MAX_MEDIA_BYTES {
        return Err(AppError::validation(format!(
            "size_bytes must be between 1 and {MAX_MEDIA_BYTES}."
        )));
    }

    let media_id = Uuid::new_v4();
    let object_key = format!("{}/{}", auth.user_id, media_id);
    let bucket = state.media_bucket.clone();
    let expires_at = Utc::now() + Duration::minutes(PRESIGN_TTL_MINUTES);
    let content_type = body
        .content_type
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty());

    sqlx::query(
        r#"
        INSERT INTO media_objects (
            id, uploader_user_id, uploader_device_id, bucket, object_key,
            size_bytes, content_type, created_at
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7, now())
        "#,
    )
    .bind(media_id)
    .bind(auth.user_id)
    .bind(auth.device_id)
    .bind(&bucket)
    .bind(&object_key)
    .bind(body.size_bytes)
    .bind(&content_type)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("insert media object failed: {err}")))?;

    let upload_url = presign_url(&state, "upload", &bucket, &object_key, media_id, expires_at);

    Ok((
        StatusCode::CREATED,
        Json(CreateUploadResponse {
            media_object_id: media_id,
            upload_url,
            object_key,
            expires_at,
        }),
    ))
}

/// `POST /media/:id/download` — short-lived download URL if authorized.
pub async fn create_download(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(media_id): Path<Uuid>,
) -> Result<Json<DownloadResponse>, AppError> {
    let media = sqlx::query_as::<_, MediaRow>(
        r#"
        SELECT id, uploader_user_id, bucket, object_key, message_id
        FROM media_objects
        WHERE id = $1
        "#,
    )
    .bind(media_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("load media failed: {err}")))?
    .ok_or_else(|| AppError::not_found("Media not found."))?;

    authorize_download(&state, auth.user_id, &media).await?;

    let expires_at = Utc::now() + Duration::minutes(PRESIGN_TTL_MINUTES);
    let download_url = presign_url(
        &state,
        "download",
        &media.bucket,
        &media.object_key,
        media.id,
        expires_at,
    );

    Ok(Json(DownloadResponse {
        download_url,
        expires_at,
    }))
}

async fn authorize_download(
    state: &AppState,
    user_id: Uuid,
    media: &MediaRow,
) -> Result<(), AppError> {
    if media.message_id.is_none() {
        if media.uploader_user_id == user_id {
            return Ok(());
        }
        return Err(AppError::forbidden(
            "Only the uploader can download unlinked media.",
        ));
    }

    let message_id = media.message_id.expect("checked");
    // Human: Linked media is available to either conversation participant.
    let allowed: bool = sqlx::query_scalar(
        r#"
        SELECT EXISTS(
            SELECT 1
            FROM messages m
            INNER JOIN conversations c ON c.id = m.conversation_id
            WHERE m.id = $1
              AND (c.user_a_id = $2 OR c.user_b_id = $2)
        )
        "#,
    )
    .bind(message_id)
    .bind(user_id)
    .fetch_one(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("media ACL check failed: {err}")))?;

    if allowed {
        Ok(())
    } else {
        Err(AppError::forbidden(
            "You are not allowed to download this media.",
        ))
    }
}

/// Builds a Nebular or stub presigned-style URL (bytes never stored on API).
fn presign_url(
    state: &AppState,
    kind: &str,
    bucket: &str,
    object_key: &str,
    media_id: Uuid,
    expires_at: DateTime<Utc>,
) -> String {
    if let Some(base) = &state.nebular_url {
        let base = base.trim_end_matches('/');
        // Human: Real Nebular signing can replace this path once wired; shape is S3-like.
        format!(
            "{base}/{bucket}/{object_key}?shroud_media_id={media_id}&expires={}",
            expires_at.timestamp()
        )
    } else {
        format!(
            "stub://{bucket}/{object_key}?op={kind}&media_id={media_id}&expires={}",
            expires_at.timestamp()
        )
    }
}
