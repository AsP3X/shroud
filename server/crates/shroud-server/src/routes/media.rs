//! Encrypted media: metadata registration + API-proxied blob put/get.
//!
//! Clients always upload/download via the Shroud API (`/media/{id}/content`) so
//! phones never need to reach internal Docker hostnames like `nebular:9000`.

use std::path::{Path, PathBuf};

use axum::{
    Json,
    body::{Body, Bytes},
    extract::{Path as AxumPath, State},
    http::{StatusCode, header},
    response::{IntoResponse, Response},
};
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use tokio_util::io::ReaderStream;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::rate_limit::budgets;
use crate::state::AppState;

/// Maximum encrypted object size (25 MiB).
pub const MAX_MEDIA_BYTES: i64 = 25 * 1024 * 1024;
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

    // Client-relative path — iOS resolves against the configured API base URL.
    let upload_url = format!("media/{media_id}/content");

    tracing::info!(
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
    body: Bytes,
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

    let len = body.len() as i64;
    if !(1..=MAX_MEDIA_BYTES).contains(&len) {
        return Err(AppError::validation(format!(
            "body size must be between 1 and {MAX_MEDIA_BYTES} bytes."
        )));
    }
    if let Some(expected) = media.size_bytes {
        // Allow small variance? Keep strict: must match declared size.
        if expected != len {
            return Err(AppError::validation(format!(
                "body size {len} does not match declared size_bytes {expected}."
            )));
        }
    }

    write_blob(&state, &media, body.as_ref()).await?;
    state
        .metrics
        .media_puts_total
        .fetch_add(1, std::sync::atomic::Ordering::Relaxed);

    tracing::info!(
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

    // Multi-replica: when Nebular is primary, try shared object store first so any
    // API replica can serve blobs uploaded on another node.
    if state.media_prefer_nebular && state.nebular_url.is_some() {
        if let Ok(bytes) = read_blob_nebular(&state, &media).await {
            state
                .metrics
                .media_nebular_hits_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            state
                .metrics
                .media_gets_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            tracing::info!(
                user_id = %auth.user_id,
                media_object_id = %media_id,
                bytes = bytes.len(),
                "media.content_get ok (nebular primary)"
            );
            return Ok((
                StatusCode::OK,
                [(header::CONTENT_TYPE, "application/octet-stream")],
                bytes,
            )
                .into_response());
        }
    }

    let path = blob_path(&media);
    if Path::new(&path).exists() {
        // Human: Stream from disk so large ciphertext never fills process RAM.
        // Agent: READS local file via ReaderStream; never logs plaintext.
        let file = tokio::fs::File::open(&path)
            .await
            .map_err(|err| AppError::Internal(format!("open media blob failed: {err}")))?;
        let stream = ReaderStream::new(file);
        let body = Body::from_stream(stream);
        state
            .metrics
            .media_local_hits_total
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        state
            .metrics
            .media_gets_total
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        tracing::info!(
            user_id = %auth.user_id,
            media_object_id = %media_id,
            "media.content_get ok (stream)"
        );
        return Ok((
            StatusCode::OK,
            [(header::CONTENT_TYPE, "application/octet-stream")],
            body,
        )
            .into_response());
    }

    // Local miss — fall back to Nebular (may buffer into memory).
    let bytes = read_blob(&state, &media).await?;
    state
        .metrics
        .media_nebular_hits_total
        .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    state
        .metrics
        .media_gets_total
        .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    tracing::info!(
        user_id = %auth.user_id,
        media_object_id = %media_id,
        bytes = bytes.len(),
        "media.content_get ok (buffered fallback)"
    );

    Ok((
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/octet-stream")],
        bytes,
    )
        .into_response())
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

    tracing::info!(
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

/// Delete unlinked media older than [`ORPHAN_TTL_MINUTES`] (DB row + local blob).
///
/// Human: Stops abandoned uploads from filling disk indefinitely.
/// Agent: SELECT orphans; remove_file; DELETE WHERE message_id IS NULL.
pub async fn purge_orphan_media(pool: &sqlx::PgPool) -> Result<u64, AppError> {
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
    for media in orphans {
        let path = blob_path(&media);
        if Path::new(&path).exists()
            && let Err(err) = tokio::fs::remove_file(&path).await
        {
            tracing::warn!(
                error = %err,
                path = %path.display(),
                media_object_id = %media.id,
                "orphan media blob delete failed"
            );
        }

        let result = sqlx::query(
            r#"
            DELETE FROM media_objects
            WHERE id = $1 AND message_id IS NULL
            "#,
        )
        .bind(media.id)
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
pub fn spawn_orphan_gc(pool: sqlx::PgPool) {
    tokio::spawn(async move {
        let mut interval =
            tokio::time::interval(std::time::Duration::from_secs(ORPHAN_GC_INTERVAL_SECS));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        // Don't run immediately at boot — wait one interval so cold starts settle.
        interval.tick().await;
        loop {
            interval.tick().await;
            match purge_orphan_media(&pool).await {
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

fn media_data_dir() -> PathBuf {
    std::env::var("MEDIA_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("/data/shroud-media"))
}

fn blob_path(media: &MediaRow) -> PathBuf {
    media_data_dir().join(&media.object_key)
}

async fn write_blob(state: &AppState, media: &MediaRow, bytes: &[u8]) -> Result<(), AppError> {
    // Local volume (fast path for single-node) + Nebular mirror for multi-replica reads.
    let path = blob_path(media);
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|err| {
            AppError::Internal(format!(
                "create media dir failed at {}: {err} (check MEDIA_DATA_DIR permissions; container user needs write access)",
                parent.display()
            ))
        })?;
    }
    tokio::fs::write(&path, bytes).await.map_err(|err| {
        AppError::Internal(format!(
            "write media blob failed at {}: {err}",
            path.display()
        ))
    })?;

    // Mirror to Nebular for multi-replica reads. Local volume remains authoritative for
    // this write — a Nebular blip must not fail the client upload (photos would "not send").
    // Prefer-Nebular only affects *read* path (see get_content).
    if let Some(base) = &state.nebular_url {
        let base = base.trim_end_matches('/');
        let url = format!("{base}/{}/{}", media.bucket, media.object_key);
        match state
            .http_client
            .put(&url)
            .body(bytes.to_vec())
            .send()
            .await
        {
            Ok(resp) if resp.status().is_success() => {
                tracing::debug!(%url, "media mirrored to nebular");
            }
            Ok(resp) => {
                tracing::warn!(
                    status = %resp.status(),
                    %url,
                    prefer_nebular = state.media_prefer_nebular,
                    "nebular mirror put failed; local blob kept"
                );
            }
            Err(err) => {
                tracing::warn!(
                    error = %err,
                    %url,
                    prefer_nebular = state.media_prefer_nebular,
                    "nebular mirror put error; local blob kept"
                );
            }
        }
    }

    Ok(())
}

async fn read_blob_nebular(state: &AppState, media: &MediaRow) -> Result<Vec<u8>, AppError> {
    let Some(base) = &state.nebular_url else {
        return Err(AppError::not_found("Media content not found."));
    };
    let base = base.trim_end_matches('/');
    let url = format!("{base}/{}/{}", media.bucket, media.object_key);
    match state.http_client.get(&url).send().await {
        Ok(resp) if resp.status().is_success() => resp
            .bytes()
            .await
            .map(|b| b.to_vec())
            .map_err(|err| AppError::Internal(format!("nebular body read failed: {err}"))),
        Ok(resp) => Err(AppError::Internal(format!(
            "nebular get failed with status {}",
            resp.status()
        ))),
        Err(err) => Err(AppError::Internal(format!("nebular get error: {err}"))),
    }
}

async fn read_blob(state: &AppState, media: &MediaRow) -> Result<Vec<u8>, AppError> {
    let path = blob_path(media);
    if Path::new(&path).exists() {
        return tokio::fs::read(&path)
            .await
            .map_err(|err| AppError::Internal(format!("read media blob failed: {err}")));
    }

    // Fallback: try Nebular if local missing (other replica / legacy).
    match read_blob_nebular(state, media).await {
        Ok(bytes) => Ok(bytes),
        Err(_) => Err(AppError::not_found("Media content not found.")),
    }
}
