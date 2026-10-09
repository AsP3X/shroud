//! Encrypted media: metadata registration + API-proxied blob put/get.
//!
//! Clients always upload/download via the Shroud API (`/media/{id}/content`): phones never
//! reach the media store, and the store never sees a client's address or token. Blobs live in
//! [`crate::media_store`].

use std::sync::Arc;
use std::sync::atomic::Ordering;

use axum::{
    Json,
    body::Body,
    extract::{Path as AxumPath, State},
    http::{HeaderValue, StatusCode, header},
    response::Response,
};
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{FromRow, PgPool};
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use futures_util::StreamExt;

use crate::media_store::{BlobSource, MediaStore, MediaStoreError};
use crate::metrics::Metrics;
use crate::rate_limit::budgets;
use crate::state::AppState;

/// Largest encrypted media object: 2 GiB. A half-hour 1080p video and a large
/// photo both fit. The upload is written to disk as it arrives, not held in RAM.
pub const MAX_MEDIA_BYTES: i64 = 2 * 1024 * 1024 * 1024;
/// Presign TTL (15 minutes) — kept for response compatibility.
const PRESIGN_TTL_MINUTES: i64 = 15;
/// Unlinked media older than this is eligible for orphan GC.
pub const ORPHAN_TTL_MINUTES: i64 = 60;
/// How often the background GC task runs.
const ORPHAN_GC_INTERVAL_SECS: u64 = 15 * 60;

#[derive(Debug, Deserialize)]
pub struct CreateUploadRequest {
    pub size_bytes: i64,
    pub content_type: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct CreateUploadResponse {
    pub media_object_id: Uuid,
    /// Relative API path clients should PUT encrypted bytes to (with Bearer token).
    pub upload_url: String,
    pub object_key: String,
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, Serialize)]
pub struct DownloadResponse {
    /// Relative API path clients should GET encrypted bytes from (with Bearer token).
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
    size_bytes: Option<i64>,
}

/// `POST /media/uploads` — create media row; client then PUTs to `/media/{id}/content`.
pub async fn create_upload(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<CreateUploadRequest>,
) -> Result<(StatusCode, Json<CreateUploadResponse>), AppError> {
    state
        .rate_limiter
        .check_budget(
            "media_presign_user",
            &auth.user_id.to_string(),
            budgets::MEDIA_PRESIGN_USER,
        )
        .await?;

    if body.size_bytes < 1 || body.size_bytes > MAX_MEDIA_BYTES {
        return Err(AppError::validation(format!(
            "size_bytes must be between 1 and {MAX_MEDIA_BYTES}."
        )));
    }

    let media_id = Uuid::new_v4();
    let object_key = MediaStore::object_key(media_id);
    let bucket = state.media.bucket().to_string();
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

    // Client-relative path — iOS resolves against the configured API base URL.
    let upload_url = format!("media/{media_id}/content");

    tracing::debug!(
        user_id = %auth.user_id,
        media_object_id = %media_id,
        size_bytes = body.size_bytes,
        bucket = %bucket,
        object_key = %object_key,
        "media.upload_create ok"
    );

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

/// `PUT /media/:id/content` — store encrypted blob (uploader only, before message link).
pub async fn put_content(
    State(state): State<AppState>,
    auth: AuthContext,
    AxumPath(media_id): AxumPath<Uuid>,
    body: Body,
) -> Result<StatusCode, AppError> {
    let media = load_media(&state, media_id).await?;
    if media.uploader_user_id != auth.user_id {
        return Err(AppError::forbidden(
            "Only the uploader can write media content.",
        ));
    }
    if media.message_id.is_some() {
        return Err(AppError::already_exists(
            "Media is already linked to a message and cannot be overwritten.",
        ));
    }

    let stream = body
        .into_data_stream()
        .map(|chunk| chunk.map_err(|err| std::io::Error::other(err.to_string())));
    let len = match state
        .media
        .put_stream(
            &media.bucket,
            &media.object_key,
            stream,
            MAX_MEDIA_BYTES as u64,
            media.size_bytes.map(|n| n as u64),
        )
        .await
    {
        Ok(0) => {
            return Err(AppError::validation(format!(
                "body size must be between 1 and {MAX_MEDIA_BYTES} bytes."
            )));
        }
        Ok(len) => len,
        Err(err) => {
            return Err(match err {
                MediaStoreError::TooLarge => AppError::validation(format!(
                    "body size must be between 1 and {MAX_MEDIA_BYTES} bytes."
                )),
                MediaStoreError::SizeMismatch { actual, expected } => AppError::validation(
                    format!("body size {actual} does not match declared size_bytes {expected}."),
                ),
                other => store_error(&state.metrics, media_id, "put", other),
            });
        }
    };
    state
        .metrics
        .media_puts_total
        .fetch_add(1, Ordering::Relaxed);

    tracing::debug!(
        user_id = %auth.user_id,
        media_object_id = %media_id,
        bytes = len,
        "media.content_put ok"
    );
    Ok(StatusCode::NO_CONTENT)
}

/// `GET /media/:id/content` — stream encrypted blob when authorized.
pub async fn get_content(
    State(state): State<AppState>,
    auth: AuthContext,
    AxumPath(media_id): AxumPath<Uuid>,
) -> Result<Response, AppError> {
    let media = load_media(&state, media_id).await?;
    authorize_download(&state, auth.user_id, &media).await?;

    let (blob, source) = state
        .media
        .get(&media.bucket, &media.object_key)
        .await
        .map_err(|err| store_error(&state.metrics, media_id, "get", err))?;
    if source == BlobSource::Legacy {
        state
            .metrics
            .media_legacy_reads_total
            .fetch_add(1, Ordering::Relaxed);
    }
    state
        .metrics
        .media_gets_total
        .fetch_add(1, Ordering::Relaxed);
    tracing::debug!(
        user_id = %auth.user_id,
        media_object_id = %media_id,
        bytes = blob.size,
        source = ?source,
        "media.content_get ok"
    );

    // Streamed at the client's pace: the blob is not buffered whole.
    let mut response = Response::new(Body::from_stream(blob.body));
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/octet-stream"),
    );
    headers.insert(header::CONTENT_LENGTH, HeaderValue::from(blob.size));
    // Ciphertext is private to this user; no shared cache or proxy should keep a copy.
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("private, no-store"),
    );
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    Ok(response)
}

/// `POST /media/:id/download` — returns API-relative download path (Bearer required).
pub async fn create_download(
    State(state): State<AppState>,
    auth: AuthContext,
    AxumPath(media_id): AxumPath<Uuid>,
) -> Result<Json<DownloadResponse>, AppError> {
    let media = load_media(&state, media_id).await?;
    authorize_download(&state, auth.user_id, &media).await?;

    let expires_at = Utc::now() + Duration::minutes(PRESIGN_TTL_MINUTES);
    let download_url = format!("media/{media_id}/content");

    tracing::debug!(
        user_id = %auth.user_id,
        media_object_id = %media.id,
        "media.download_presign ok"
    );

    Ok(Json(DownloadResponse {
        download_url,
        expires_at,
    }))
}

async fn load_media(state: &AppState, media_id: Uuid) -> Result<MediaRow, AppError> {
    sqlx::query_as::<_, MediaRow>(
        r#"
        SELECT id, uploader_user_id, bucket, object_key, message_id, size_bytes
        FROM media_objects
        WHERE id = $1
        "#,
    )
    .bind(media_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("load media failed: {err}")))?
    .ok_or_else(|| AppError::not_found("Media not found."))
}

#[derive(Debug, FromRow)]
struct MediaAccessRow {
    in_conversation: bool,
    deleted_everyone: bool,
    hidden: bool,
}

async fn authorize_download(
    state: &AppState,
    user_id: Uuid,
    media: &MediaRow,
) -> Result<(), AppError> {
    let Some(message_id) = media.message_id else {
        // Unlinked (compose / abandoned upload): only the uploader.
        if media.uploader_user_id == user_id {
            return Ok(());
        }
        return Err(AppError::forbidden(
            "Only the uploader can download unlinked media.",
        ));
    };

    // Linked: conversation participant, not deleted-for-everyone, not hidden for caller.
    let access = sqlx::query_as::<_, MediaAccessRow>(
        r#"
        SELECT
            (c.user_a_id = $2 OR c.user_b_id = $2) AS in_conversation,
            (m.deleted_for_everyone_at IS NOT NULL) AS deleted_everyone,
            EXISTS(
                SELECT 1 FROM message_hides h
                WHERE h.message_id = m.id AND h.user_id = $2
            ) AS hidden
        FROM messages m
        INNER JOIN conversations c ON c.id = m.conversation_id
        WHERE m.id = $1
        "#,
    )
    .bind(message_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("media ACL check failed: {err}")))?
    .ok_or_else(|| AppError::forbidden("You are not allowed to download this media."))?;

    if !access.in_conversation {
        return Err(AppError::forbidden(
            "You are not allowed to download this media.",
        ));
    }
    if access.deleted_everyone {
        return Err(AppError::forbidden("This media was deleted for everyone."));
    }
    if access.hidden {
        return Err(AppError::forbidden(
            "This media is hidden for your account.",
        ));
    }
    Ok(())
}

/// Blobs attached to these messages, from either pointer (`media_objects.message_id` or
/// `messages.media_object_id`).
pub async fn media_ids_for_messages(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    message_ids: &[Uuid],
) -> Result<Vec<Uuid>, AppError> {
    if message_ids.is_empty() {
        return Ok(Vec::new());
    }
    sqlx::query_scalar(
        r#"
        SELECT id FROM media_objects WHERE message_id = ANY($1)
        UNION
        SELECT media_object_id FROM messages
        WHERE id = ANY($1) AND media_object_id IS NOT NULL
        "#,
    )
    .bind(message_ids)
    .fetch_all(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("list media for messages failed: {err}")))
}

/// Deletes media rows and their blobs now (delete for everyone, Saved Messages, account deletion).
///
/// Caller must already have unlinked `messages.media_object_id` / `media_objects.message_id`
/// so FK order does not block the DELETE. Safe to call with an empty slice. A blob the store
/// can't delete right now keeps its (unlinked) row, and the orphan GC tries again.
pub async fn purge_media_ids(state: &AppState, media_ids: &[Uuid]) -> Result<u64, AppError> {
    if media_ids.is_empty() {
        return Ok(0);
    }

    let rows = sqlx::query_as::<_, MediaRow>(
        r#"
        SELECT id, uploader_user_id, bucket, object_key, message_id, size_bytes
        FROM media_objects
        WHERE id = ANY($1)
        "#,
    )
    .bind(media_ids)
    .fetch_all(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("load media for purge failed: {err}")))?;

    let mut purged = 0_u64;
    for media in rows {
        if !delete_blob(&state.media, &state.metrics, &media).await {
            continue;
        }
        let result = sqlx::query(r#"DELETE FROM media_objects WHERE id = $1"#)
            .bind(media.id)
            .execute(&state.pool)
            .await
            .map_err(|err| AppError::Internal(format!("delete media row failed: {err}")))?;
        if result.rows_affected() > 0 {
            purged += 1;
        }
    }
    Ok(purged)
}

/// Deletes unlinked media older than [`ORPHAN_TTL_MINUTES`]: abandoned uploads, and blobs a
/// delete could not remove because the store was down. Delete for everyone removes its blobs
/// immediately; this pass is the retry.
///
/// The blob goes first and the row only once it is gone, so a store outage leaves the row for
/// the next pass rather than a blob nothing points at.
pub async fn purge_orphan_media(
    pool: &PgPool,
    media: &MediaStore,
    metrics: &Metrics,
) -> Result<u64, AppError> {
    let cutoff = Utc::now() - Duration::minutes(ORPHAN_TTL_MINUTES);
    let orphans = sqlx::query_as::<_, MediaRow>(
        r#"
        SELECT id, uploader_user_id, bucket, object_key, message_id, size_bytes
        FROM media_objects
        WHERE message_id IS NULL
          AND created_at < $1
        "#,
    )
    .bind(cutoff)
    .fetch_all(pool)
    .await
    .map_err(|err| AppError::Internal(format!("list orphan media failed: {err}")))?;

    let mut purged = 0_u64;
    for orphan in orphans {
        if !delete_blob(media, metrics, &orphan).await {
            continue;
        }
        let result = sqlx::query(
            r#"
            DELETE FROM media_objects
            WHERE id = $1 AND message_id IS NULL
            "#,
        )
        .bind(orphan.id)
        .execute(pool)
        .await
        .map_err(|err| AppError::Internal(format!("delete orphan media row failed: {err}")))?;

        if result.rows_affected() > 0 {
            purged += 1;
        }
    }

    Ok(purged)
}

/// Background loop: purge abandoned unlinked media on a fixed interval.
pub fn spawn_orphan_gc(pool: PgPool, media: Arc<MediaStore>, metrics: Arc<Metrics>) {
    tokio::spawn(async move {
        let mut interval =
            tokio::time::interval(std::time::Duration::from_secs(ORPHAN_GC_INTERVAL_SECS));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        // Don't run immediately at boot — wait one interval so cold starts settle.
        interval.tick().await;
        loop {
            interval.tick().await;
            match purge_orphan_media(&pool, &media, &metrics).await {
                Ok(0) => {
                    tracing::debug!("media.orphan_gc: nothing to purge");
                }
                Ok(n) => {
                    tracing::info!(purged = n, "media.orphan_gc ok");
                }
                Err(err) => {
                    tracing::warn!(error = %err, "media.orphan_gc failed");
                }
            }
        }
    });
}

/// Deletes one row's blob; `false` (logged) when the store couldn't.
async fn delete_blob(media: &MediaStore, metrics: &Metrics, row: &MediaRow) -> bool {
    match media.delete(&row.bucket, &row.object_key).await {
        Ok(()) => true,
        Err(err) => {
            metrics
                .media_store_errors_total
                .fetch_add(1, Ordering::Relaxed);
            tracing::warn!(
                error = %err,
                media_object_id = %row.id,
                "media blob delete failed; the row stays for the orphan GC to retry"
            );
            false
        }
    }
}

/// A client-facing error for a failed store call; the detail goes to the log.
fn store_error(
    metrics: &Metrics,
    media_id: Uuid,
    operation: &'static str,
    err: MediaStoreError,
) -> AppError {
    match err {
        MediaStoreError::NotFound => AppError::not_found("Media content not found."),
        MediaStoreError::TooLarge => AppError::validation(format!(
            "body size must be between 1 and {MAX_MEDIA_BYTES} bytes."
        )),
        MediaStoreError::SizeMismatch { actual, expected } => AppError::validation(format!(
            "body size {actual} does not match declared size_bytes {expected}."
        )),
        MediaStoreError::InvalidKey => {
            tracing::error!(
                media_object_id = %media_id,
                operation,
                "media row holds an object key the store refuses"
            );
            AppError::Internal(format!("invalid object key for media {media_id}"))
        }
        MediaStoreError::Unavailable(detail) => {
            metrics
                .media_store_errors_total
                .fetch_add(1, Ordering::Relaxed);
            tracing::error!(
                media_object_id = %media_id,
                operation,
                error = %detail,
                "media store call failed"
            );
            AppError::media_unavailable()
        }
    }
}
