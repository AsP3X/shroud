//! Media blobs in a local directory: one-node deployments, development, and the pre-Nebular
//! volume that [`super::MediaStore::spawn_legacy_migration`] empties.

use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

use axum::body::Bytes;
use futures_util::{Stream, StreamExt};
use tokio::io::AsyncWriteExt;
use tokio_util::io::ReaderStream;

use super::{MediaBlob, MediaStoreError};

/// Scratch files, on the same filesystem as the blobs so a rename publishes them in one step.
const SCRATCH_DIR: &str = ".tmp";
/// Scratch files older than this belonged to a write that crashed.
const STALE_SCRATCH_AFTER: Duration = Duration::from_secs(60 * 60);

#[derive(Debug, Clone)]
pub struct LocalStore {
    root: PathBuf,
}

impl LocalStore {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Writes `body` under `key`. The bytes go to an fsynced scratch file that is then renamed
    /// over the blob, so neither a reader nor a crash ever sees half an object.
    pub async fn put(&self, key: &str, body: Bytes) -> Result<(), MediaStoreError> {
        self.put_stream(
            key,
            futures_util::stream::iter([Ok::<_, std::io::Error>(body)]),
            u64::MAX,
            None,
        )
        .await
        .map(|_| ())
    }

    /// Streams `body` to `key`, refusing a body over `max_bytes` or different from `expected`.
    /// Nothing is published when either check fails.
    pub async fn put_stream<S, E>(
        &self,
        key: &str,
        body: S,
        max_bytes: u64,
        expected: Option<u64>,
    ) -> Result<u64, MediaStoreError>
    where
        S: Stream<Item = Result<Bytes, E>> + Send,
        E: std::fmt::Display,
    {
        let path = self.path_for(key)?;
        let parent = path.parent().ok_or(MediaStoreError::InvalidKey)?;
        let scratch_dir = self.root.join(SCRATCH_DIR);
        tokio::fs::create_dir_all(parent).await.map_err(io_error)?;
        tokio::fs::create_dir_all(&scratch_dir)
            .await
            .map_err(io_error)?;

        let scratch = scratch_dir.join(format!("{}.part", uuid::Uuid::new_v4()));
        let written = write_capped(&scratch, body, max_bytes).await;
        let byte_count = match written {
            Ok(count) => count,
            Err(err) => {
                let _ = tokio::fs::remove_file(&scratch).await;
                return Err(err);
            }
        };
        // An empty body is not a stored object. Refuse it before the rename publishes it.
        if byte_count == 0 {
            let _ = tokio::fs::remove_file(&scratch).await;
            return Err(MediaStoreError::TooLarge);
        }
        if let Some(expected) = expected
            && byte_count != expected
        {
            let _ = tokio::fs::remove_file(&scratch).await;
            return Err(MediaStoreError::SizeMismatch {
                actual: byte_count,
                expected,
            });
        }
        if let Err(err) = async {
            let file = tokio::fs::File::open(&scratch).await?;
            file.sync_all().await?;
            drop(file);
            tokio::fs::rename(&scratch, &path).await?;
            sync_dir(parent).await
        }
        .await
        {
            let _ = tokio::fs::remove_file(&scratch).await;
            return Err(io_error(err));
        }
        Ok(byte_count)
    }

    pub async fn get(&self, key: &str) -> Result<MediaBlob, MediaStoreError> {
        let path = self.path_for(key)?;
        let file = match tokio::fs::File::open(&path).await {
            Ok(file) => file,
            Err(err) if err.kind() == ErrorKind::NotFound => return Err(MediaStoreError::NotFound),
            Err(err) => return Err(io_error(err)),
        };
        let size = file.metadata().await.map_err(io_error)?.len();
        Ok(MediaBlob {
            size,
            body: ReaderStream::new(file).boxed(),
        })
    }

    /// Removes `key`; a blob that is already gone counts as removed.
    pub async fn delete(&self, key: &str) -> Result<(), MediaStoreError> {
        let path = self.path_for(key)?;
        match tokio::fs::remove_file(&path).await {
            Ok(()) => Ok(()),
            Err(err) if err.kind() == ErrorKind::NotFound => Ok(()),
            Err(err) => Err(io_error(err)),
        }
    }

    /// The whole blob, for callers that already know it is small. Large objects go through
    /// [`Self::put_stream`].
    pub async fn read(&self, key: &str) -> Result<Bytes, MediaStoreError> {
        let path = self.path_for(key)?;
        match tokio::fs::read(&path).await {
            Ok(bytes) => Ok(Bytes::from(bytes)),
            Err(err) if err.kind() == ErrorKind::NotFound => Err(MediaStoreError::NotFound),
            Err(err) => Err(io_error(err)),
        }
    }

    /// The directory exists and takes writes.
    pub async fn check(&self) -> Result<(), MediaStoreError> {
        tokio::fs::create_dir_all(self.root.join(SCRATCH_DIR))
            .await
            .map_err(io_error)
    }

    /// Deletes scratch files that writes interrupted by a crash left behind.
    pub async fn remove_stale_scratch(&self) -> usize {
        let Ok(mut entries) = tokio::fs::read_dir(self.root.join(SCRATCH_DIR)).await else {
            return 0;
        };
        let mut removed = 0;
        while let Ok(Some(entry)) = entries.next_entry().await {
            let stale = entry
                .metadata()
                .await
                .and_then(|meta| meta.modified())
                .ok()
                .and_then(|modified| SystemTime::now().duration_since(modified).ok())
                .is_some_and(|age| age > STALE_SCRATCH_AFTER);
            if stale && tokio::fs::remove_file(entry.path()).await.is_ok() {
                removed += 1;
            }
        }
        removed
    }

    pub(crate) fn path_for(&self, key: &str) -> Result<PathBuf, MediaStoreError> {
        validate_key(key)?;
        Ok(self.root.join(key))
    }
}

/// Keys are relative `/`-separated paths the server made itself; anything that could step out
/// of the store's directory, or into its scratch directory, is refused.
pub(crate) fn validate_key(key: &str) -> Result<(), MediaStoreError> {
    let valid = !key.is_empty()
        && key.len() <= 512
        && !key.contains(['\\', '\0'])
        && key
            .split('/')
            .all(|segment| !segment.is_empty() && !segment.starts_with('.'));
    if valid {
        Ok(())
    } else {
        Err(MediaStoreError::InvalidKey)
    }
}

/// Writes `body` to `path`, stopping once `max_bytes` would be exceeded.
async fn write_capped<S, E>(path: &Path, body: S, max_bytes: u64) -> Result<u64, MediaStoreError>
where
    S: Stream<Item = Result<Bytes, E>> + Send,
    E: std::fmt::Display,
{
    let mut file = tokio::fs::File::create(path).await.map_err(io_error)?;
    let mut body = std::pin::pin!(body);
    let mut written: u64 = 0;
    while let Some(chunk) = body.next().await {
        let chunk = chunk.map_err(|err| MediaStoreError::Unavailable(err.to_string()))?;
        let next = written.saturating_add(chunk.len() as u64);
        if next > max_bytes {
            return Err(MediaStoreError::TooLarge);
        }
        file.write_all(&chunk).await.map_err(io_error)?;
        written = next;
    }
    file.sync_all().await.map_err(io_error)?;
    Ok(written)
}

fn io_error(err: std::io::Error) -> MediaStoreError {
    MediaStoreError::Unavailable(format!("local media store: {err}"))
}

/// Makes a rename in `dir` durable.
#[cfg(unix)]
async fn sync_dir(dir: &Path) -> std::io::Result<()> {
    tokio::fs::File::open(dir).await?.sync_all().await
}

#[cfg(not(unix))]
async fn sync_dir(_dir: &Path) -> std::io::Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_store() -> LocalStore {
        LocalStore::new(std::env::temp_dir().join(format!("shroud-local-{}", uuid::Uuid::new_v4())))
    }

    async fn collect(blob: MediaBlob) -> Vec<u8> {
        let mut out = Vec::new();
        let mut body = blob.body;
        while let Some(chunk) = body.next().await {
            out.extend_from_slice(&chunk.expect("chunk"));
        }
        out
    }

    #[tokio::test]
    async fn put_stream_does_not_publish_an_empty_body() {
        let store = temp_store();
        let err = store
            .put_stream(
                "media/ab/empty",
                futures_util::stream::iter(Vec::<Result<Bytes, std::io::Error>>::new()),
                100,
                None,
            )
            .await;
        assert!(matches!(err, Err(MediaStoreError::TooLarge)));
        assert!(matches!(
            store.get("media/ab/empty").await,
            Err(MediaStoreError::NotFound)
        ));
    }

    #[tokio::test]
    async fn put_stream_rejects_a_body_over_the_cap_without_publishing() {
        let store = temp_store();
        let err = store
            .put_stream(
                "media/ab/big",
                futures_util::stream::iter([
                    Ok::<_, std::io::Error>(Bytes::from(vec![1; 8])),
                    Ok(Bytes::from(vec![2; 8])),
                ]),
                10,
                None,
            )
            .await;
        assert!(matches!(err, Err(MediaStoreError::TooLarge)));
        assert!(matches!(
            store.get("media/ab/big").await,
            Err(MediaStoreError::NotFound)
        ));
    }

    #[tokio::test]
    async fn put_get_delete_round_trip() {
        let store = temp_store();
        store
            .put("media/ab/blob", Bytes::from_static(b"ciphertext"))
            .await
            .expect("put");
        let blob = store.get("media/ab/blob").await.expect("get");
        assert_eq!(blob.size, 10);
        assert_eq!(collect(blob).await, b"ciphertext");

        store
            .put("media/ab/blob", Bytes::from_static(b"replaced"))
            .await
            .expect("overwrite");
        assert_eq!(
            collect(store.get("media/ab/blob").await.expect("get")).await,
            b"replaced"
        );

        store.delete("media/ab/blob").await.expect("delete");
        assert!(matches!(
            store.get("media/ab/blob").await,
            Err(MediaStoreError::NotFound)
        ));
        store.delete("media/ab/blob").await.expect("delete again");
        // Nothing is left in the scratch directory after the writes.
        let mut scratch = tokio::fs::read_dir(store.root().join(SCRATCH_DIR))
            .await
            .expect("scratch dir");
        assert!(scratch.next_entry().await.expect("read").is_none());
        let _ = tokio::fs::remove_dir_all(store.root()).await;
    }

    #[test]
    fn keys_cannot_leave_the_store() {
        for key in [
            "",
            "/etc/passwd",
            "../outside",
            "media/../../outside",
            "media//double",
            ".tmp/x.part",
            "media/.hidden",
            "back\\slash",
            "nul\0byte",
        ] {
            assert!(validate_key(key).is_err(), "{key:?} should be refused");
        }
        for key in [
            "media/0f/0f6d1c2e-aaaa-4bbb-8ccc-123456789abc",
            "4b6f2d0e-1111-4222-8333-444455556666/0f6d1c2e-aaaa-4bbb-8ccc-123456789abc",
        ] {
            assert!(validate_key(key).is_ok(), "{key:?} should be accepted");
        }
    }
}
