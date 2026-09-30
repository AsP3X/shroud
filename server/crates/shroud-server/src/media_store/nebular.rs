//! Media blobs in Nebular OS through its S3-compatible API, signed with AWS Signature V4.
//!
//! Nebular runs with one access key for this API: role `editor`, limited to the media bucket by
//! `NOS_BUCKET_POLICY`, and nothing else (no JWT secret, no presigned URLs). Every request signs
//! its body's SHA-256, which Nebular checks while the upload streams, before committing it.

use std::time::Duration;

use std::path::Path;

use axum::body::Bytes;
use futures_util::{Stream, StreamExt};
use reqwest::{Method, StatusCode, Url};
use tokio_util::io::ReaderStream;

use super::sigv4::{self, Credentials};
use super::{MediaBlob, MediaStoreError};

/// Readiness probes HEAD this key. It never exists, so a 404 shows Nebular answers and accepts
/// the credentials for the bucket.
const PROBE_KEY: &str = "health/readiness-probe";
/// Tries per request: transport errors and 429 / 5xx are retried (every call is idempotent).
const MAX_ATTEMPTS: u32 = 3;
/// Longest `Retry-After` honoured before a retry (Nebular's upload budget says 1 s).
const MAX_RETRY_AFTER: Duration = Duration::from_secs(2);
/// The whole upload, body included. A 2 GiB object on a slow link needs the full hour.
///
/// This is a total deadline, not an idle timer. Reqwest's `read_timeout` is also a single
/// deadline until response headers and does not reset while the body is still being written,
/// so the client must not set one shorter than this or a moving upload is aborted early.
/// A download that goes quiet is cut off separately, per chunk.
const PUT_TIMEOUT: Duration = Duration::from_secs(60 * 60);
/// Response headers for everything else. A download's body then streams at the client's pace.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
/// A download body that sends nothing for this long is stalled.
const BODY_IDLE: Duration = Duration::from_secs(60);

/// What `send` uploads. A file is re-opened on each retry; a buffer is cloned.
enum Upload {
    Empty,
    Buffered(Bytes),
    File { path: std::path::PathBuf, len: u64 },
}

/// Where Nebular is and the access key it gave this API.
#[derive(Clone)]
pub struct NebularConfig {
    pub url: String,
    pub access_key_id: String,
    pub secret_access_key: String,
    pub region: String,
}

impl std::fmt::Debug for NebularConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NebularConfig")
            .field("url", &self.url)
            .field("access_key_id", &self.access_key_id)
            .field("secret_access_key", &"[REDACTED]")
            .field("region", &self.region)
            .finish()
    }
}

#[derive(Debug, Clone)]
pub struct NebularStore {
    client: reqwest::Client,
    endpoint: Url,
    /// `Host` as reqwest sends it; the signature covers it.
    host: String,
    creds: Credentials,
}

impl NebularStore {
    pub fn new(config: NebularConfig) -> Result<Self, String> {
        let endpoint = Url::parse(config.url.trim())
            .map_err(|err| format!("NEBULAR_URL is not a valid URL: {err}"))?;
        if !matches!(endpoint.scheme(), "http" | "https") {
            return Err("NEBULAR_URL must start with http:// or https://".into());
        }
        if endpoint.path() != "/"
            || endpoint.query().is_some()
            || endpoint.fragment().is_some()
            || !endpoint.username().is_empty()
            || endpoint.password().is_some()
        {
            return Err("NEBULAR_URL must be a bare origin such as http://nebular:9000".into());
        }
        let host_name = endpoint
            .host_str()
            .ok_or("NEBULAR_URL has no host")?
            .to_string();
        let host = match endpoint.port() {
            Some(port) => format!("{host_name}:{port}"),
            None => host_name,
        };

        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            // Nebular closes keep-alive connections idle for NOS_HEADER_READ_TIMEOUT_SECS (75 s);
            // retiring them sooner means a request never lands on one as it closes.
            .pool_idle_timeout(Duration::from_secs(30))
            .tcp_keepalive(Duration::from_secs(30))
            .build()
            .map_err(|err| format!("Nebular HTTP client could not be built: {err}"))?;

        Ok(Self {
            client,
            endpoint,
            host,
            creds: Credentials {
                access_key_id: config.access_key_id,
                secret_access_key: config.secret_access_key,
                region: config.region,
            },
        })
    }

    pub fn endpoint(&self) -> &Url {
        &self.endpoint
    }

    pub async fn put(&self, bucket: &str, key: &str, body: Bytes) -> Result<(), MediaStoreError> {
        let payload = sigv4::sha256_hex(&body);
        let response = self
            .send(Method::PUT, bucket, key, Upload::Buffered(body), &payload)
            .await?;
        if response.status().is_success() {
            Ok(())
        } else {
            Err(refused(response, "upload").await)
        }
    }

    /// Uploads a file already on disk. The caller hashed it; retries re-read the file.
    pub async fn put_file(
        &self,
        bucket: &str,
        key: &str,
        path: &Path,
        len: u64,
        payload_sha256: &str,
    ) -> Result<(), MediaStoreError> {
        let response = self
            .send(
                Method::PUT,
                bucket,
                key,
                Upload::File {
                    path: path.to_path_buf(),
                    len,
                },
                payload_sha256,
            )
            .await?;
        if response.status().is_success() {
            Ok(())
        } else {
            Err(refused(response, "upload").await)
        }
    }

    pub async fn get(&self, bucket: &str, key: &str) -> Result<MediaBlob, MediaStoreError> {
        let response = self
            .send(
                Method::GET,
                bucket,
                key,
                Upload::Empty,
                sigv4::EMPTY_PAYLOAD_SHA256,
            )
            .await?;
        match response.status() {
            StatusCode::OK => {
                let size = response.content_length().ok_or_else(|| {
                    MediaStoreError::Unavailable("Nebular sent a download without a length".into())
                })?;
                let body = idle_limited(response.bytes_stream(), BODY_IDLE).boxed();
                Ok(MediaBlob { size, body })
            }
            StatusCode::NOT_FOUND => Err(MediaStoreError::NotFound),
            _ => Err(refused(response, "download").await),
        }
    }

    /// Removes `key`. Nebular deletes at once (`NOS_SOFT_DELETE_TTL_SECS=0`); a key that is
    /// already gone counts as removed.
    pub async fn delete(&self, bucket: &str, key: &str) -> Result<(), MediaStoreError> {
        let response = self
            .send(
                Method::DELETE,
                bucket,
                key,
                Upload::Empty,
                sigv4::EMPTY_PAYLOAD_SHA256,
            )
            .await?;
        match response.status() {
            status if status.is_success() => Ok(()),
            StatusCode::NOT_FOUND => Ok(()),
            _ => Err(refused(response, "delete").await),
        }
    }

    /// Nebular answers and accepts this API's access key for `bucket`.
    pub async fn check(&self, bucket: &str) -> Result<(), MediaStoreError> {
        let response = self
            .send(
                Method::HEAD,
                bucket,
                PROBE_KEY,
                Upload::Empty,
                sigv4::EMPTY_PAYLOAD_SHA256,
            )
            .await?;
        match response.status() {
            StatusCode::OK | StatusCode::NOT_FOUND => Ok(()),
            _ => Err(refused(response, "readiness probe").await),
        }
    }

    async fn send(
        &self,
        method: Method,
        bucket: &str,
        key: &str,
        body: Upload,
        payload_sha256: &str,
    ) -> Result<reqwest::Response, MediaStoreError> {
        let path = sigv4::encode_path(bucket, key);
        let mut url = self.endpoint.clone();
        url.set_path(&path);
        let wait = if method == Method::PUT {
            PUT_TIMEOUT
        } else {
            REQUEST_TIMEOUT
        };

        let mut attempt = 1;
        loop {
            // Signed per attempt: Nebular accepts a request only within 15 minutes of its date.
            let amz_date = chrono::Utc::now().format("%Y%m%dT%H%M%SZ").to_string();
            let signed = [
                ("host", self.host.as_str()),
                ("x-amz-content-sha256", payload_sha256),
                ("x-amz-date", amz_date.as_str()),
            ];
            let authorization = sigv4::authorization(
                &self.creds,
                &sigv4::Request {
                    method: method.as_str(),
                    canonical_uri: &path,
                    headers: &signed,
                    payload_sha256,
                },
                &amz_date,
            );
            let mut request = self
                .client
                .request(method.clone(), url.clone())
                .header("x-amz-content-sha256", payload_sha256)
                .header("x-amz-date", &amz_date)
                .header(reqwest::header::AUTHORIZATION, authorization);
            request = match &body {
                Upload::Empty => request,
                Upload::Buffered(bytes) => request.body(bytes.clone()),
                Upload::File { path, len } => {
                    let file = tokio::fs::File::open(path).await.map_err(|err| {
                        MediaStoreError::Unavailable(format!("open upload {path:?}: {err}"))
                    })?;
                    request
                        .header(reqwest::header::CONTENT_LENGTH, *len)
                        .body(reqwest::Body::wrap_stream(ReaderStream::new(file)))
                }
            };

            let failure = match tokio::time::timeout(wait, request.send()).await {
                Ok(Ok(response)) if is_retryable(response.status()) && attempt < MAX_ATTEMPTS => {
                    let delay = retry_after(&response).unwrap_or_else(|| backoff(attempt));
                    tracing::debug!(
                        status = %response.status(),
                        attempt,
                        %method,
                        "media.nebular retrying"
                    );
                    tokio::time::sleep(delay).await;
                    attempt += 1;
                    continue;
                }
                Ok(Ok(response)) => return Ok(response),
                Ok(Err(err)) => format!("{method} {path} failed: {err}"),
                Err(_) => format!("{method} {path} got no answer within {}s", wait.as_secs()),
            };
            if attempt >= MAX_ATTEMPTS {
                return Err(MediaStoreError::Unavailable(format!("Nebular {failure}")));
            }
            tracing::debug!(attempt, error = %failure, "media.nebular retrying");
            tokio::time::sleep(backoff(attempt)).await;
            attempt += 1;
        }
    }
}

/// Ends `stream` with a timeout error once a chunk takes longer than `idle`.
///
/// Reqwest's client `read_timeout` would also bound the upload, and it does not reset while
/// bytes are still going out, so downloads carry their own idle limit here instead.
fn idle_limited<S>(
    stream: S,
    idle: Duration,
) -> impl Stream<Item = Result<Bytes, std::io::Error>> + Send
where
    S: Stream<Item = Result<Bytes, reqwest::Error>> + Send + Unpin + 'static,
{
    futures_util::stream::unfold((stream, true), move |(mut stream, alive)| async move {
        if !alive {
            return None;
        }
        match tokio::time::timeout(idle, stream.next()).await {
            Ok(Some(Ok(chunk))) => Some((Ok(chunk), (stream, true))),
            Ok(Some(Err(err))) => Some((Err(std::io::Error::other(err)), (stream, false))),
            Ok(None) => None,
            Err(_elapsed) => Some((
                Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "media download stalled",
                )),
                (stream, false),
            )),
        }
    })
}

fn is_retryable(status: StatusCode) -> bool {
    matches!(
        status,
        StatusCode::REQUEST_TIMEOUT
            | StatusCode::TOO_MANY_REQUESTS
            | StatusCode::INTERNAL_SERVER_ERROR
            | StatusCode::BAD_GATEWAY
            | StatusCode::SERVICE_UNAVAILABLE
            | StatusCode::GATEWAY_TIMEOUT
    )
}

fn backoff(attempt: u32) -> Duration {
    Duration::from_millis(200 * 4_u64.pow(attempt.saturating_sub(1)))
}

fn retry_after(response: &reqwest::Response) -> Option<Duration> {
    let seconds: u64 = response
        .headers()
        .get(reqwest::header::RETRY_AFTER)?
        .to_str()
        .ok()?
        .trim()
        .parse()
        .ok()?;
    Some(Duration::from_secs(seconds).min(MAX_RETRY_AFTER))
}

/// An error for a response Nebular refused, with its status and the start of its message.
///
/// The body read has the same idle limit as a download. Without it, a peer that sends
/// headers and then nothing holds this task open: the client no longer has a `read_timeout`,
/// because that deadline also fires while a long upload is still being written.
async fn refused(response: reqwest::Response, operation: &str) -> MediaStoreError {
    let status = response.status();
    let detail: String = match tokio::time::timeout(BODY_IDLE, response.text()).await {
        Ok(Ok(text)) => text.chars().take(300).collect(),
        Ok(Err(_)) | Err(_) => String::new(),
    };
    let hint = if matches!(status, StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN) {
        " (check NEBULAR_ACCESS_KEY_ID / NEBULAR_SECRET_ACCESS_KEY against Nebular's \
         NOS_S3_ACCESS_KEY / NOS_S3_SECRET_KEY and NOS_BUCKET_POLICY)"
    } else {
        ""
    };
    MediaStoreError::Unavailable(format!(
        "Nebular refused the {operation} with {status}{hint}: {}",
        detail.trim()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Bytes;

    fn config(url: &str) -> NebularConfig {
        NebularConfig {
            url: url.into(),
            access_key_id: "SHROUDTESTKEY".into(),
            secret_access_key: "0123456789abcdef0123456789abcdef".into(),
            region: "us-east-1".into(),
        }
    }

    #[test]
    fn host_matches_what_reqwest_sends() {
        let store = NebularStore::new(config("http://nebular:9000")).expect("store");
        assert_eq!(store.host, "nebular:9000");
        // Default ports are left out of the Host header, so the signature leaves them out too.
        let store = NebularStore::new(config("https://storage.example.com:443")).expect("store");
        assert_eq!(store.host, "storage.example.com");
        let store = NebularStore::new(config("http://[::1]:9000/")).expect("store");
        assert_eq!(store.host, "[::1]:9000");
    }

    #[test]
    fn only_bare_http_origins_are_accepted() {
        for url in [
            "nebular:9000",
            "ftp://nebular:9000",
            "http://nebular:9000/prefix",
            "http://nebular:9000/?x=1",
            "http://user:pass@nebular:9000",
        ] {
            assert!(
                NebularStore::new(config(url)).is_err(),
                "{url} should be refused"
            );
        }
    }

    #[test]
    fn debug_hides_the_secret() {
        let shown = format!("{:?}", config("http://nebular:9000"));
        assert!(!shown.contains("0123456789abcdef"));
        let store = NebularStore::new(config("http://nebular:9000")).expect("store");
        assert!(!format!("{store:?}").contains("0123456789abcdef"));
    }

    #[test]
    fn backoff_grows() {
        assert_eq!(backoff(1), Duration::from_millis(200));
        assert_eq!(backoff(2), Duration::from_millis(800));
    }

    #[tokio::test]
    async fn a_download_chunk_is_passed_through() {
        use futures_util::StreamExt;

        let stream =
            futures_util::stream::iter([Ok::<_, reqwest::Error>(Bytes::from_static(b"abc"))]);
        let mut limited = idle_limited(stream, Duration::from_secs(5)).boxed();
        let chunk = limited.next().await.expect("chunk").expect("ok");
        assert_eq!(&chunk[..], b"abc");
        assert!(limited.next().await.is_none());
    }

    #[tokio::test]
    async fn a_quiet_download_ends_instead_of_waiting() {
        use futures_util::StreamExt;

        let pending = futures_util::stream::pending::<Result<Bytes, reqwest::Error>>();
        let mut limited = idle_limited(pending, Duration::from_millis(40)).boxed();
        let started = std::time::Instant::now();
        let err = limited
            .next()
            .await
            .expect("stall yields one item")
            .expect_err("stall is an error");
        assert_eq!(err.kind(), std::io::ErrorKind::TimedOut);
        assert!(started.elapsed() < Duration::from_secs(5));
        assert!(
            limited.next().await.is_none(),
            "the stream ends after the stall"
        );
    }
}
