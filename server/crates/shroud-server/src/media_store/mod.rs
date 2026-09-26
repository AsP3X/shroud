//! Where encrypted media blobs live.
//!
//! Clients never reach the store: every byte passes through `/media/{id}/content`, which
//! decides who may write or read it. Blobs are ciphertext, so a store only ever holds opaque
//! bytes and their sizes.
//!
//! - **Nebular** (`NEBULAR_URL` set): Nebular OS over its S3 API, signed with SigV4. Every API
//!   replica sees the same blobs. The local volume earlier releases wrote to (`MEDIA_DATA_DIR`)
//!   stays readable while [`MediaStore::spawn_legacy_migration`] moves its blobs into Nebular.
//! - **Local** (no `NEBULAR_URL`): files under `MEDIA_DATA_DIR`, for one node or development.

mod local;
mod nebular;
pub mod sigv4;

use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::Ordering;

use axum::body::Bytes;
use futures_util::StreamExt;
use futures_util::stream::BoxStream;
use sqlx::PgPool;
use uuid::Uuid;

pub use local::LocalStore;
pub use nebular::{NebularConfig, NebularStore};

use crate::metrics::Metrics;

/// Rows read per page while moving the legacy volume into Nebular.
const MIGRATION_PAGE: i64 = 200;

/// A blob's bytes as they stream out of the store.
pub struct MediaBlob {
    pub size: u64,
    pub body: BoxStream<'static, std::io::Result<Bytes>>,
}

/// Which store served a read.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BlobSource {
    Primary,
    /// The local volume a Nebular deployment is moving out of.
    Legacy,
}

#[derive(Debug, thiserror::Error)]
pub enum MediaStoreError {
    #[error("media blob not found")]
    NotFound,
    #[error("media object key is not a valid store path")]
    InvalidKey,
    #[error("media blob exceeds the size limit")]
    TooLarge,
    #[error("media blob is {actual} bytes, declared {expected}")]
    SizeMismatch { actual: u64, expected: u64 },
    /// The store couldn't be reached or refused the request. The detail is for logs only.
    #[error("{0}")]
    Unavailable(String),
}

enum Backend {
    Local(LocalStore),
    Nebular(NebularStore),
}

pub struct MediaStore {
    backend: Backend,
    /// Nebular deployments only: the local volume blobs were written to before.
    legacy: Option<LocalStore>,
    /// Bucket new uploads go to (the local backend has no buckets).
    bucket: String,
}

impl MediaStore {
    /// Blobs in files under `root`.
    pub fn local(root: impl Into<PathBuf>, bucket: impl Into<String>) -> Self {
        Self {
            backend: Backend::Local(LocalStore::new(root)),
            legacy: None,
            bucket: bucket.into(),
        }
    }

    /// Blobs in Nebular. `legacy_root` is the local volume earlier releases used; it is read
    /// while its blobs are moved over. New uploads spool into its `.tmp` directory (the same
    /// scratch a local store uses) so a 2 GiB object is not written onto the container's own disk.
    pub fn nebular(
        config: NebularConfig,
        bucket: impl Into<String>,
        legacy_root: Option<PathBuf>,
    ) -> Result<Self, String> {
        Ok(Self {
            backend: Backend::Nebular(NebularStore::new(config)?),
            legacy: legacy_root.map(LocalStore::new),
            bucket: bucket.into(),
        })
    }

    /// The store integration tests run against: Nebular when `SHROUD_TEST_NEBULAR_URL`,
    /// `SHROUD_TEST_NEBULAR_ACCESS_KEY_ID` and `SHROUD_TEST_NEBULAR_SECRET_ACCESS_KEY` are set
    /// (bucket from `SHROUD_TEST_NEBULAR_BUCKET`, default `shroud-media`), else a fresh
    /// directory under the system temp dir.
    pub fn for_integration_tests() -> Self {
        let scratch = std::env::temp_dir().join(format!("shroud-media-test-{}", Uuid::new_v4()));
        let env = |name: &str| std::env::var(name).ok().filter(|v| !v.trim().is_empty());
        let bucket = env("SHROUD_TEST_NEBULAR_BUCKET").unwrap_or_else(|| "shroud-media".into());
        match (
            env("SHROUD_TEST_NEBULAR_URL"),
            env("SHROUD_TEST_NEBULAR_ACCESS_KEY_ID"),
            env("SHROUD_TEST_NEBULAR_SECRET_ACCESS_KEY"),
        ) {
            (Some(url), Some(access_key_id), Some(secret_access_key)) => Self::nebular(
                NebularConfig {
                    url,
                    access_key_id,
                    secret_access_key,
                    region: "us-east-1".into(),
                },
                bucket,
                Some(scratch.join("legacy")),
            )
            .expect("SHROUD_TEST_NEBULAR_* describe a usable Nebular"),
            _ => Self::local(scratch, bucket),
        }
    }

    /// `"nebular"` or `"local"`, for logs.
    pub fn backend_name(&self) -> &'static str {
        match self.backend {
            Backend::Local(_) => "local",
            Backend::Nebular(_) => "nebular",
        }
    }

    pub fn bucket(&self) -> &str {
        &self.bucket
    }

    /// Where a Nebular upload is written before it is signed and sent. The legacy media volume
    /// when this process has one; otherwise a directory under the system temp dir.
    fn spool_dir(&self) -> PathBuf {
        match &self.legacy {
            Some(legacy) => legacy.root().join(".tmp"),
            None => std::env::temp_dir().join("shroud-media-spool"),
        }
    }

    /// Key for a new upload. It carries only the media id (sharded by its first two hex digits
    /// so a local directory stays small), never who uploaded it.
    pub fn object_key(media_id: Uuid) -> String {
        let id = media_id.to_string();
        format!("media/{}/{id}", &id[..2])
    }

    /// Stores `body` under `bucket`/`key`, replacing what was there.
    pub async fn put(&self, bucket: &str, key: &str, body: Bytes) -> Result<(), MediaStoreError> {
        local::validate_key(key)?;
        match &self.backend {
            Backend::Local(store) => store.put(key, body).await,
            Backend::Nebular(store) => store.put(bucket, key, body).await,
        }
    }

    /// Streams an upload to the store. `expected`, when set, must match the bytes received.
    /// A body over `max_bytes` is refused and not published.
    pub async fn put_stream<S, E>(
        &self,
        bucket: &str,
        key: &str,
        body: S,
        max_bytes: u64,
        expected: Option<u64>,
    ) -> Result<u64, MediaStoreError>
    where
        S: futures_util::Stream<Item = Result<Bytes, E>> + Send,
        E: std::fmt::Display,
    {
        local::validate_key(key)?;
        match &self.backend {
            Backend::Local(store) => store.put_stream(key, body, max_bytes, expected).await,
            Backend::Nebular(store) => {
                let spooled = spool_upload(&self.spool_dir(), body, max_bytes, expected).await?;
                // An empty body is not an object. Refuse it before Nebular publishes it.
                if spooled.len == 0 {
                    let _ = tokio::fs::remove_file(&spooled.path).await;
                    return Err(MediaStoreError::TooLarge);
                }
                let result = store
                    .put_file(bucket, key, &spooled.path, spooled.len, &spooled.hash)
                    .await;
                let _ = tokio::fs::remove_file(&spooled.path).await;
                result.map(|()| spooled.len)
            }
        }
    }

    /// Streams a blob, from the legacy volume if it hasn't been moved yet.
    pub async fn get(
        &self,
        bucket: &str,
        key: &str,
    ) -> Result<(MediaBlob, BlobSource), MediaStoreError> {
        local::validate_key(key)?;
        match self.get_primary(bucket, key).await {
            Err(MediaStoreError::NotFound) => {}
            other => return other.map(|blob| (blob, BlobSource::Primary)),
        }
        let Some(legacy) = &self.legacy else {
            return Err(MediaStoreError::NotFound);
        };
        match legacy.get(key).await {
            Ok(blob) => Ok((blob, BlobSource::Legacy)),
            // Moved while this read was between the two stores.
            Err(MediaStoreError::NotFound) => self
                .get_primary(bucket, key)
                .await
                .map(|blob| (blob, BlobSource::Primary)),
            Err(err) => Err(err),
        }
    }

    /// Deletes a blob everywhere it may be. A blob that is already gone counts as deleted.
    pub async fn delete(&self, bucket: &str, key: &str) -> Result<(), MediaStoreError> {
        local::validate_key(key)?;
        match &self.backend {
            Backend::Local(store) => store.delete(key).await?,
            Backend::Nebular(store) => store.delete(bucket, key).await?,
        }
        if let Some(legacy) = &self.legacy {
            legacy.delete(key).await?;
        }
        Ok(())
    }

    /// The store answers and accepts writes (local) or this API's credentials (Nebular).
    pub async fn check(&self) -> Result<(), MediaStoreError> {
        match &self.backend {
            Backend::Local(store) => store.check().await,
            Backend::Nebular(store) => store.check(&self.bucket).await,
        }
    }

    /// Startup housekeeping: scratch files a crash left in a local store.
    pub async fn prepare(&self) {
        let stores = [
            match &self.backend {
                Backend::Local(store) => Some(store),
                Backend::Nebular(_) => None,
            },
            self.legacy.as_ref(),
        ];
        for store in stores.into_iter().flatten() {
            let removed = store.remove_stale_scratch().await;
            if removed > 0 {
                tracing::info!(
                    removed,
                    dir = %store.root().display(),
                    "media: removed scratch files of interrupted writes"
                );
            }
        }
    }

    /// Where logs should say blobs go (a URL or a directory).
    pub fn location(&self) -> String {
        match &self.backend {
            Backend::Local(store) => store.root().display().to_string(),
            Backend::Nebular(store) => format!("{}{}", store.endpoint(), self.bucket),
        }
    }

    /// Moves the legacy volume's blobs into Nebular in the background, then deletes them there.
    /// Blobs no row names any more (their media was deleted) are deleted without moving.
    /// Does nothing for a local store or when the volume holds nothing.
    pub fn spawn_legacy_migration(self: &Arc<Self>, pool: PgPool, metrics: Arc<Metrics>) {
        if self.legacy.is_none() || matches!(self.backend, Backend::Local(_)) {
            return;
        }
        let store = Arc::clone(self);
        tokio::spawn(async move {
            match store.migrate_legacy(&pool, &metrics).await {
                Ok(report) if report.is_empty() => {}
                Ok(report) => tracing::info!(
                    moved = report.moved,
                    removed_unreferenced = report.removed_unreferenced,
                    "media: legacy volume moved into Nebular"
                ),
                Err(err) => tracing::warn!(
                    error = %err,
                    "media: moving the legacy volume into Nebular stopped; the next start resumes"
                ),
            }
        });
    }

    async fn get_primary(&self, bucket: &str, key: &str) -> Result<MediaBlob, MediaStoreError> {
        match &self.backend {
            Backend::Local(store) => store.get(key).await,
            Backend::Nebular(store) => store.get(bucket, key).await,
        }
    }

    /// What [`Self::spawn_legacy_migration`] runs: one pass over the legacy volume.
    pub async fn migrate_legacy(
        &self,
        pool: &PgPool,
        metrics: &Metrics,
    ) -> Result<MigrationReport, MediaStoreError> {
        let mut report = MigrationReport::default();
        let Some(legacy) = &self.legacy else {
            return Ok(report);
        };
        if !legacy_volume_has_blobs(legacy).await {
            return Ok(report);
        }
        tracing::info!(
            dir = %legacy.root().display(),
            "media: moving blobs from the legacy volume into Nebular"
        );

        let mut after = Uuid::nil();
        loop {
            let rows: Vec<(Uuid, String, String)> = sqlx::query_as(
                r#"
                SELECT id, bucket, object_key FROM media_objects
                WHERE id > $1
                ORDER BY id
                LIMIT $2
                "#,
            )
            .bind(after)
            .bind(MIGRATION_PAGE)
            .fetch_all(pool)
            .await
            .map_err(db_error)?;
            let Some(last) = rows.last() else { break };
            after = last.0;

            for (id, bucket, key) in rows {
                if self
                    .move_legacy_blob(legacy, pool, id, &bucket, &key)
                    .await?
                {
                    report.moved += 1;
                    metrics.media_migrated_total.fetch_add(1, Ordering::Relaxed);
                }
            }
        }

        report.removed_unreferenced = remove_unreferenced_legacy_blobs(legacy, pool).await?;
        Ok(report)
    }

    /// Copies one blob into Nebular and deletes the local copy. `Ok(false)` when it isn't on
    /// the legacy volume.
    async fn move_legacy_blob(
        &self,
        legacy: &LocalStore,
        pool: &PgPool,
        id: Uuid,
        bucket: &str,
        key: &str,
    ) -> Result<bool, MediaStoreError> {
        let Backend::Nebular(nebular) = &self.backend else {
            return Ok(false);
        };
        let path = match legacy.path_for(key) {
            Ok(path) => path,
            Err(MediaStoreError::InvalidKey) => return Ok(false),
            Err(err) => return Err(err),
        };
        let meta = match tokio::fs::metadata(&path).await {
            Ok(meta) => meta,
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => return Ok(false),
            Err(err) => {
                return Err(MediaStoreError::Unavailable(format!(
                    "legacy blob metadata: {err}"
                )));
            }
        };
        let hash = hash_file(&path).await?;
        nebular
            .put_file(bucket, key, &path, meta.len(), &hash)
            .await?;
        // A purge between the read and the upload deleted the row and both copies before this
        // one landed; take the upload back so deleted media stays deleted.
        let still_referenced: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM media_objects WHERE id = $1)")
                .bind(id)
                .fetch_one(pool)
                .await
                .map_err(db_error)?;
        if !still_referenced {
            nebular.delete(bucket, key).await?;
        }
        legacy.delete(key).await?;
        Ok(true)
    }
}

/// One pass over the legacy volume.
#[derive(Debug, Default)]
pub struct MigrationReport {
    /// Blobs copied into Nebular and deleted locally.
    pub moved: u64,
    /// Local blobs no row named any more, deleted without copying.
    pub removed_unreferenced: u64,
}

impl MigrationReport {
    fn is_empty(&self) -> bool {
        self.moved == 0 && self.removed_unreferenced == 0
    }
}

/// Anything besides the scratch directory under the legacy root.
async fn legacy_volume_has_blobs(legacy: &LocalStore) -> bool {
    let Ok(mut entries) = tokio::fs::read_dir(legacy.root()).await else {
        return false;
    };
    while let Ok(Some(entry)) = entries.next_entry().await {
        if !entry.file_name().to_string_lossy().starts_with('.') {
            return true;
        }
    }
    false
}

/// Deletes legacy blobs no row names (a delete that removed the row but failed to remove the
/// file). Only paths shaped like the legacy layout, `{user uuid}/{media uuid}`, are touched,
/// and emptied user directories are removed.
async fn remove_unreferenced_legacy_blobs(
    legacy: &LocalStore,
    pool: &PgPool,
) -> Result<u64, MediaStoreError> {
    let mut removed = 0;
    let Ok(mut users) = tokio::fs::read_dir(legacy.root()).await else {
        return Ok(0);
    };
    while let Ok(Some(user_dir)) = users.next_entry().await {
        let user = user_dir.file_name().to_string_lossy().into_owned();
        if Uuid::parse_str(&user).is_err() || !user_dir.path().is_dir() {
            continue;
        }
        let Ok(mut blobs) = tokio::fs::read_dir(user_dir.path()).await else {
            continue;
        };
        while let Ok(Some(blob)) = blobs.next_entry().await {
            let media = blob.file_name().to_string_lossy().into_owned();
            if Uuid::parse_str(&media).is_err() {
                continue;
            }
            let key = format!("{user}/{media}");
            let referenced: bool = sqlx::query_scalar(
                "SELECT EXISTS(SELECT 1 FROM media_objects WHERE object_key = $1)",
            )
            .bind(&key)
            .fetch_one(pool)
            .await
            .map_err(db_error)?;
            if !referenced {
                legacy.delete(&key).await?;
                removed += 1;
            }
        }
        // Fails while the directory still holds something, which is fine.
        let _ = tokio::fs::remove_dir(user_dir.path()).await;
    }
    Ok(removed)
}

struct SpooledUpload {
    path: std::path::PathBuf,
    len: u64,
    hash: String,
}

/// Writes `body` to a temp file and hashes it, so Nebular can be signed without holding
/// the object in memory. The caller deletes `path`.
async fn spool_upload<S, E>(
    dir: &std::path::Path,
    body: S,
    max_bytes: u64,
    expected: Option<u64>,
) -> Result<SpooledUpload, MediaStoreError>
where
    S: futures_util::Stream<Item = Result<Bytes, E>> + Send,
    E: std::fmt::Display,
{
    use sha2::Digest;
    tokio::fs::create_dir_all(dir)
        .await
        .map_err(|err| MediaStoreError::Unavailable(format!("create upload spool: {err}")))?;
    let path = dir.join(format!("shroud-upload-{}.part", uuid::Uuid::new_v4()));
    let mut file = tokio::fs::File::create(&path)
        .await
        .map_err(|err| MediaStoreError::Unavailable(format!("create upload spool: {err}")))?;
    let mut hasher = sha2::Sha256::new();
    let mut body = std::pin::pin!(body);
    let mut written: u64 = 0;
    while let Some(chunk) = body.next().await {
        let chunk = match chunk {
            Ok(chunk) => chunk,
            Err(err) => {
                let _ = tokio::fs::remove_file(&path).await;
                return Err(MediaStoreError::Unavailable(err.to_string()));
            }
        };
        let next = written.saturating_add(chunk.len() as u64);
        if next > max_bytes {
            let _ = tokio::fs::remove_file(&path).await;
            return Err(MediaStoreError::TooLarge);
        }
        hasher.update(&chunk);
        if let Err(err) = tokio::io::AsyncWriteExt::write_all(&mut file, &chunk).await {
            let _ = tokio::fs::remove_file(&path).await;
            return Err(MediaStoreError::Unavailable(format!(
                "write upload spool: {err}"
            )));
        }
        written = next;
    }
    if let Err(err) = file.sync_all().await {
        let _ = tokio::fs::remove_file(&path).await;
        return Err(MediaStoreError::Unavailable(format!(
            "sync upload spool: {err}"
        )));
    }
    drop(file);
    if written == 0 {
        let _ = tokio::fs::remove_file(&path).await;
        return Err(MediaStoreError::TooLarge);
    }
    if let Some(expected) = expected
        && written != expected
    {
        let _ = tokio::fs::remove_file(&path).await;
        return Err(MediaStoreError::SizeMismatch {
            actual: written,
            expected,
        });
    }
    Ok(SpooledUpload {
        path,
        len: written,
        hash: hex_sha256(hasher),
    })
}

fn hex_sha256(hasher: sha2::Sha256) -> String {
    use sha2::Digest;
    hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

async fn hash_file(path: &std::path::Path) -> Result<String, MediaStoreError> {
    use sha2::Digest;
    use tokio::io::AsyncReadExt;
    let mut file = tokio::fs::File::open(path)
        .await
        .map_err(|err| MediaStoreError::Unavailable(format!("open legacy blob: {err}")))?;
    let mut hasher = sha2::Sha256::new();
    let mut buf = vec![0_u8; 64 * 1024];
    loop {
        let n = file
            .read(&mut buf)
            .await
            .map_err(|err| MediaStoreError::Unavailable(format!("read legacy blob: {err}")))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex_sha256(hasher))
}

fn db_error(err: sqlx::Error) -> MediaStoreError {
    MediaStoreError::Unavailable(format!("media migration database query failed: {err}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::StreamExt;

    async fn read_all(blob: MediaBlob) -> Vec<u8> {
        let mut out = Vec::new();
        let mut body = blob.body;
        while let Some(chunk) = body.next().await {
            out.extend_from_slice(&chunk.expect("chunk"));
        }
        out
    }

    fn temp_dir(label: &str) -> PathBuf {
        std::env::temp_dir().join(format!("shroud-{label}-{}", Uuid::new_v4()))
    }

    #[test]
    fn new_keys_hold_only_the_media_id() {
        let id = Uuid::parse_str("0f6d1c2e-aaaa-4bbb-8ccc-123456789abc").unwrap();
        assert_eq!(
            MediaStore::object_key(id),
            "media/0f/0f6d1c2e-aaaa-4bbb-8ccc-123456789abc"
        );
    }

    #[test]
    fn nebular_uploads_spool_on_the_legacy_volume() {
        let legacy = temp_dir("spool");
        let config = NebularConfig {
            url: "http://127.0.0.1:9".into(),
            access_key_id: "k".into(),
            secret_access_key: "s".into(),
            region: "us-east-1".into(),
        };
        let store = MediaStore::nebular(config.clone(), "shroud-media", Some(legacy.clone()))
            .expect("store");
        assert_eq!(store.spool_dir(), legacy.join(".tmp"));

        let nowhere = MediaStore::nebular(config, "shroud-media", None).expect("store");
        assert_eq!(
            nowhere.spool_dir(),
            std::env::temp_dir().join("shroud-media-spool")
        );
    }

    #[tokio::test]
    async fn reads_fall_back_to_the_legacy_volume_and_deletes_reach_both() {
        // A local primary stands in for Nebular: the fallback logic is the same.
        let legacy_root = temp_dir("legacy");
        let primary_root = temp_dir("primary");
        let store = MediaStore {
            backend: Backend::Local(LocalStore::new(&primary_root)),
            legacy: Some(LocalStore::new(&legacy_root)),
            bucket: "shroud-media".into(),
        };
        let old_key = "4b6f2d0e-1111-4222-8333-444455556666/0f6d1c2e-aaaa-4bbb-8ccc-123456789abc";
        LocalStore::new(&legacy_root)
            .put(old_key, Bytes::from_static(b"old blob"))
            .await
            .expect("seed legacy");

        let (blob, source) = store.get("shroud-media", old_key).await.expect("get");
        assert_eq!(source, BlobSource::Legacy);
        assert_eq!(read_all(blob).await, b"old blob");

        store
            .put(
                "shroud-media",
                "media/aa/new",
                Bytes::from_static(b"new blob"),
            )
            .await
            .expect("put");
        let (blob, source) = store
            .get("shroud-media", "media/aa/new")
            .await
            .expect("get");
        assert_eq!(source, BlobSource::Primary);
        assert_eq!(read_all(blob).await, b"new blob");

        store.delete("shroud-media", old_key).await.expect("delete");
        assert!(matches!(
            store.get("shroud-media", old_key).await,
            Err(MediaStoreError::NotFound)
        ));
        let _ = tokio::fs::remove_dir_all(&legacy_root).await;
        let _ = tokio::fs::remove_dir_all(&primary_root).await;
    }

    #[tokio::test]
    async fn invalid_keys_never_reach_a_store() {
        let store = MediaStore::local(temp_dir("keys"), "shroud-media");
        for key in ["../escape", "/abs", ".tmp/x"] {
            assert!(matches!(
                store
                    .put("shroud-media", key, Bytes::from_static(b"x"))
                    .await,
                Err(MediaStoreError::InvalidKey)
            ));
            assert!(matches!(
                store.get("shroud-media", key).await,
                Err(MediaStoreError::InvalidKey)
            ));
            assert!(matches!(
                store.delete("shroud-media", key).await,
                Err(MediaStoreError::InvalidKey)
            ));
        }
    }
}
