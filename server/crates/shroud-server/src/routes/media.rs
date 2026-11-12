//! Encrypted media: metadata registration + API-proxied blob put/get.
//!
//! Clients always upload/download via the Shroud API (`/media/{id}/content`): phones never
//! reach the media store, and the store never sees a client's address or token. Blobs live in
//! [`crate::media_store`].

use std::collections::{HashMap, HashSet};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex, OnceLock, PoisonError};

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

use crate::media_store::{BlobSource, LONGEST_PUBLISH, MediaStore, MediaStoreError};
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
pub(crate) const ORPHAN_GC_INTERVAL_SECS: u64 = 15 * 60;
/// Uploads one account may stream at once (an album of photos goes up a few at a time).
pub const MAX_UPLOADS_PER_USER: usize = 8;
/// An upload that sends nothing for this long is dropped, with its scratch file.
const UPLOAD_IDLE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(60);
/// A `publishing_at` marker older than this, in seconds, was left by a process that died
/// mid-publish: no publish runs longer than [`LONGEST_PUBLISH`], so it counts as unset.
pub(crate) const PUBLISH_MARKER_TTL_SECS: i64 = LONGEST_PUBLISH.as_secs() as i64;

/// The uploads being streamed, per account. Each media object takes one at a time: parallel
/// PUTs to one id would each spool their own copy of up to 2 GiB.
#[derive(Default)]
pub struct UploadSlots {
    active: Mutex<HashMap<Uuid, HashSet<Uuid>>>,
}

impl UploadSlots {
    /// Claims the slot of `media_id`, released when the guard drops however the upload ends.
    fn acquire(self: &Arc<Self>, user_id: Uuid, media_id: Uuid) -> Result<UploadSlot, AppError> {
        let mut active = self.active.lock().unwrap_or_else(PoisonError::into_inner);
        let uploads = active.entry(user_id).or_default();
        if uploads.contains(&media_id) {
            return Err(upload_in_progress());
        }
        if uploads.len() >= MAX_UPLOADS_PER_USER {
            return Err(AppError::rate_limited_after(5));
        }
        uploads.insert(media_id);
        Ok(UploadSlot {
            slots: Arc::clone(self),
            user_id,
            media_id,
        })
    }
}

struct UploadSlot {
    slots: Arc<UploadSlots>,
    user_id: Uuid,
    media_id: Uuid,
}

impl Drop for UploadSlot {
    fn drop(&mut self) {
        let mut active = self
            .slots
            .active
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(uploads) = active.get_mut(&self.user_id) {
            uploads.remove(&self.media_id);
            if uploads.is_empty() {
                active.remove(&self.user_id);
            }
        }
    }
}

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

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin media upload failed: {err}")))?;
    if state.media_quota_bytes > 0 {
        // One registration per account at a time weighs the quota, so parallel ones can't pass it.
        // Agent: bind text, not Uuid. `contacts::lock_pair` runs this same SQL with a text bind and
        // sqlx caches the statement per connection by its SQL alone, so a Uuid bind would send the
        // id's raw bytes as text and fail as invalid UTF-8.
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))")
            .bind(auth.user_id.to_string())
            .execute(&mut *tx)
            .await
            .map_err(|err| AppError::Internal(format!("media quota lock failed: {err}")))?;
        let used: i64 = sqlx::query_scalar(
            "SELECT COALESCE(SUM(size_bytes), 0)::bigint FROM media_objects WHERE uploader_user_id = $1",
        )
        .bind(auth.user_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("media quota sum failed: {err}")))?;
        if used.saturating_add(body.size_bytes) as u64 > state.media_quota_bytes {
            return Err(AppError::media_quota_exceeded());
        }
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
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("insert media object failed: {err}")))?;
    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit media upload failed: {err}")))?;

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

    let slot = state.uploads.acquire(auth.user_id, media_id)?;

    // A body that stops arriving ends the upload, so a stalled client can't hold its slot and
    // scratch file for good (nginx has its own body timeout; the API may be reached without it).
    let stream = futures_util::stream::unfold(body.into_data_stream(), |mut body| async move {
        match tokio::time::timeout(UPLOAD_IDLE_TIMEOUT, body.next()).await {
            Ok(Some(chunk)) => Some((
                chunk.map_err(|err| std::io::Error::other(err.to_string())),
                body,
            )),
            Ok(None) => None,
            Err(_) => Some((
                Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "upload stalled",
                )),
                body,
            )),
        }
    });
    // Human: The upload runs in a task of its own. A client that goes away while its blob is
    // being published would otherwise cancel this request between setting the publishing marker
    // and clearing it, and the media could not be linked or uploaded again until it went stale.
    // Agent: tokio::spawn(store_upload); the task owns the upload slot, freed when the work ends.
    let len = tokio::spawn(store_upload(state.clone(), media, stream, slot))
        .await
        .map_err(|err| AppError::Internal(format!("media upload task failed: {err}")))??;
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

/// Streams `body` to the store as `media`'s blob and publishes it; returns its size.
async fn store_upload<S>(
    state: AppState,
    media: MediaRow,
    body: S,
    _slot: UploadSlot,
) -> Result<u64, AppError>
where
    S: futures_util::Stream<Item = std::io::Result<axum::body::Bytes>> + Send,
{
    let media_id = media.id;
    // Human: put_content's checks ran when the upload started, which can be hours before its end.
    // Right before the blob is published the row is checked again and marked as publishing, in a
    // statement of its own. A send that links the media, and a delete, refuse a row with a live
    // marker, so a linked blob can't be replaced and a deleted row can't get a blob nobody will
    // collect. No connection or row lock is held while the bytes go to the store: a publish can
    // take an hour, and holding one per upload let a few slow publishes take the whole pool.
    // Agent: DB UPDATE media_objects SET publishing_at (mark_publishing) -> store publish ->
    // finish_publish clears it; `marked` carries the marker's value out of the closure.
    let marked = OnceLock::new();
    let publish_check = || async {
        let at = mark_publishing(&state.pool, media_id).await?;
        // Set once: the store calls this check at most once.
        let _ = marked.set(at);
        Ok(())
    };
    let published = state
        .media
        .put_stream(
            &media.bucket,
            &media.object_key,
            body,
            MAX_MEDIA_BYTES as u64,
            media.size_bytes.map(|n| n as u64),
            publish_check,
        )
        .await;
    if let Some(&marked_at) = marked.get() {
        finish_publish(&state, &media, marked_at, published.is_ok()).await?;
    }
    match published {
        Ok((0, ())) => Err(AppError::validation(format!(
            "body size must be between 1 and {MAX_MEDIA_BYTES} bytes."
        ))),
        Ok((len, ())) => Ok(len),
        // Linked, deleted or being published by another request while the bytes arrived.
        Err(MediaStoreError::Withdrawn) => Err(withdrawn_error(&state, media_id).await),
        Err(MediaStoreError::TooLarge) => Err(AppError::validation(format!(
            "body size must be between 1 and {MAX_MEDIA_BYTES} bytes."
        ))),
        Err(MediaStoreError::SizeMismatch { actual, expected }) => Err(AppError::validation(
            format!("body size {actual} does not match declared size_bytes {expected}."),
        )),
        Err(other) => Err(store_error(&state.metrics, media_id, "put", other)),
    }
}

/// The answer for a busy media object: another `PUT` streams to it or publishes it.
pub(crate) fn upload_in_progress() -> AppError {
    AppError::conflict(
        "UPLOAD_IN_PROGRESS",
        "This media is already being uploaded.",
    )
}

/// Marks `media_id` as publishing, right before its blob goes to the store.
///
/// Human: Refused (`Withdrawn`) when the row is gone, linked, or carries another upload's live
/// marker. One statement, so the row lock lasts only as long as the update.
/// Agent: DB UPDATE media_objects SET publishing_at = now() WHERE unlinked AND marker unset or
/// stale; RETURNS the marker value, which `finish_publish` matches to clear only its own.
async fn mark_publishing(pool: &PgPool, media_id: Uuid) -> Result<DateTime<Utc>, MediaStoreError> {
    sqlx::query_scalar(
        r#"
        UPDATE media_objects
        SET publishing_at = now()
        WHERE id = $1
          AND message_id IS NULL
          AND (publishing_at IS NULL OR publishing_at <= now() - $2 * interval '1 second')
        RETURNING publishing_at
        "#,
    )
    .bind(media_id)
    .bind(PUBLISH_MARKER_TTL_SECS)
    .fetch_optional(pool)
    .await
    .map_err(|err| MediaStoreError::Unavailable(format!("media publish check: {err}")))?
    .ok_or(MediaStoreError::Withdrawn)
}

/// Takes this upload's publishing marker off its row once the store is done with the blob.
///
/// Human: After a failed publish the marker only comes off, so the client can retry at once. After
/// a published one the row decides. Still unlinked is the normal case. A row that is gone was
/// deleted while the marker already counted as stale, so the blob just published is deleted
/// again; nothing else would ever collect it. A row linked meanwhile, or whose stale marker
/// another upload took over, keeps the blob and the request gets the conflict.
/// Agent: DB UPDATE media_objects SET publishing_at = NULL WHERE publishing_at = `marked_at`;
/// on no match DB SELECT EXISTS, and CALLS MediaStore::delete when the row is gone.
async fn finish_publish(
    state: &AppState,
    media: &MediaRow,
    marked_at: DateTime<Utc>,
    published: bool,
) -> Result<(), AppError> {
    let cleared: Result<Option<Option<Uuid>>, sqlx::Error> = sqlx::query_scalar(
        r#"
        UPDATE media_objects
        SET publishing_at = NULL
        WHERE id = $1 AND publishing_at = $2
        RETURNING message_id
        "#,
    )
    .bind(media.id)
    .bind(marked_at)
    .fetch_optional(&state.pool)
    .await;
    if !published {
        if let Err(err) = cleared {
            tracing::warn!(
                error = %err,
                media_object_id = %media.id,
                "media publishing marker not cleared after a failed publish; it goes stale on its own"
            );
        }
        return Ok(());
    }
    match cleared
        .map_err(|err| AppError::Internal(format!("clear media publishing marker failed: {err}")))?
    {
        Some(None) => Ok(()),
        Some(Some(_)) => Err(AppError::already_exists(
            "Media is already linked to a message and cannot be overwritten.",
        )),
        None => {
            let exists: bool =
                sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM media_objects WHERE id = $1)")
                    .bind(media.id)
                    .fetch_one(&state.pool)
                    .await
                    .map_err(|err| {
                        AppError::Internal(format!("media publish re-check failed: {err}"))
                    })?;
            if exists {
                return Err(withdrawn_error(state, media.id).await);
            }
            delete_blob(&state.media, &state.metrics, media).await;
            Err(AppError::not_found("Media not found."))
        }
    }
}

/// What an upload whose row said no around its publish answers: what the checks up front would
/// have said for a gone or linked row, else that another upload is publishing it.
async fn withdrawn_error(state: &AppState, media_id: Uuid) -> AppError {
    match load_media(state, media_id).await {
        Err(err) => err,
        Ok(media) if media.message_id.is_some() => AppError::already_exists(
            "Media is already linked to a message and cannot be overwritten.",
        ),
        Ok(_) => upload_in_progress(),
    }
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
///
/// Human: An upload still publishing its blob (live `publishing_at`) is left alone: deleting its
/// row now would leave the blob it is about to publish with no row. Once the publish ends it is
/// an unlinked row like any other, which the orphan GC collects. Each row is claimed before its
/// blob goes (see [`purge_row`]), so no publish can start in between.
/// Agent: DB SELECT media_objects skips a live marker (PUBLISH_MARKER_TTL_SECS); CALLS purge_row.
pub async fn purge_media_ids(state: &AppState, media_ids: &[Uuid]) -> Result<u64, AppError> {
    if media_ids.is_empty() {
        return Ok(0);
    }

    let rows = sqlx::query_as::<_, MediaRow>(
        r#"
        SELECT id, uploader_user_id, bucket, object_key, message_id, size_bytes
        FROM media_objects
        WHERE id = ANY($1)
          AND (publishing_at IS NULL OR publishing_at <= now() - $2 * interval '1 second')
        "#,
    )
    .bind(media_ids)
    .bind(PUBLISH_MARKER_TTL_SECS)
    .fetch_all(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("load media for purge failed: {err}")))?;

    let mut purged = 0_u64;
    for media in rows {
        if purge_row(&state.pool, &state.media, &state.metrics, &media, false).await? {
            purged += 1;
        }
    }
    Ok(purged)
}

/// Deletes one row's blob, then the row; `true` when both are gone.
///
/// Human: The row is claimed first by setting its publishing marker, in the same conditional
/// update an upload uses ([`mark_publishing`]). While the claim holds, no upload can start
/// publishing (it answers `UPLOAD_IN_PROGRESS`) and no send can link the row, so the blob deleted
/// here can't be replaced by one the row would then still name. A row an upload is publishing,
/// or (with `unlinked_only`) one linked since it was listed, is skipped. When the store can't
/// delete the blob, the claim comes off again and the row stays for the orphan GC.
/// Agent: DB UPDATE media_objects SET publishing_at = now() WHERE marker unset or stale
/// RETURNING it -> CALLS MediaStore::delete -> DB DELETE WHERE publishing_at = claim, or on store
/// failure DB UPDATE SET publishing_at = NULL WHERE publishing_at = claim.
async fn purge_row(
    pool: &PgPool,
    store: &MediaStore,
    metrics: &Metrics,
    row: &MediaRow,
    unlinked_only: bool,
) -> Result<bool, AppError> {
    let claim: Option<DateTime<Utc>> = sqlx::query_scalar(
        r#"
        UPDATE media_objects
        SET publishing_at = now()
        WHERE id = $1
          AND (NOT $3 OR message_id IS NULL)
          AND (publishing_at IS NULL OR publishing_at <= now() - $2 * interval '1 second')
        RETURNING publishing_at
        "#,
    )
    .bind(row.id)
    .bind(PUBLISH_MARKER_TTL_SECS)
    .bind(unlinked_only)
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("claim media row for delete failed: {err}")))?;
    let Some(claim) = claim else {
        return Ok(false);
    };

    if !delete_blob(store, metrics, row).await {
        if let Err(err) = sqlx::query(
            "UPDATE media_objects SET publishing_at = NULL WHERE id = $1 AND publishing_at = $2",
        )
        .bind(row.id)
        .bind(claim)
        .execute(pool)
        .await
        {
            tracing::warn!(
                error = %err,
                media_object_id = %row.id,
                "media delete claim not released; it goes stale on its own"
            );
        }
        return Ok(false);
    }
    let result = sqlx::query("DELETE FROM media_objects WHERE id = $1 AND publishing_at = $2")
        .bind(row.id)
        .bind(claim)
        .execute(pool)
        .await
        .map_err(|err| AppError::Internal(format!("delete media row failed: {err}")))?;
    Ok(result.rows_affected() > 0)
}

/// Deletes unlinked media older than [`ORPHAN_TTL_MINUTES`]: abandoned uploads, and blobs a
/// delete could not remove because the store was down. Delete for everyone removes its blobs
/// immediately; this pass is the retry.
///
/// The blob goes first and the row only once it is gone, so a store outage leaves the row for
/// the next pass rather than a blob nothing points at. An upload still publishing (live
/// `publishing_at`) waits for the next pass, and each row is claimed before its blob goes, as in
/// [`purge_media_ids`].
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
          AND (publishing_at IS NULL OR publishing_at <= now() - $2 * interval '1 second')
        "#,
    )
    .bind(cutoff)
    .bind(PUBLISH_MARKER_TTL_SECS)
    .fetch_all(pool)
    .await
    .map_err(|err| AppError::Internal(format!("list orphan media failed: {err}")))?;

    let mut purged = 0_u64;
    for orphan in orphans {
        // Only while still unlinked: a send may have linked it since the list was read.
        if purge_row(pool, media, metrics, &orphan, true).await? {
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
            media.sweep_scratch().await;
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
        MediaStoreError::Withdrawn => AppError::already_exists(
            "Media is already linked to a message and cannot be overwritten.",
        ),
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
