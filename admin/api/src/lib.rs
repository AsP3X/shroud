//! Admin console HTTP process.
//!
//! Serves `GET /healthz` and, when `admin/ui/dist/index.html` is present, the built UI
//! under `/admin/`. Hashed files under `/admin/assets/` are cached for a year.
//! `index.html` and client-side routes are `Cache-Control: no-store`.
//!
//! Every response carries a content security policy and `Referrer-Policy: no-referrer`.
//! Request bodies are capped at 16 KiB. Each request is logged with an id, the method,
//! the path and the status. The log line has no client address and no query string.
//!
//! This crate is its own Cargo workspace. Build it from `admin/api`.

use std::net::{IpAddr, SocketAddr};
use std::path::Path;

pub mod auth;
mod crypto;
pub mod db;
mod error;
mod limit;
mod operator_api;
mod operators;
mod overview;
mod pages;
mod password;
mod probe;
mod published;
mod state;
pub mod totp;
mod users;
mod writes;

pub use state::AppState;

use axum::Router;
use axum::extract::{DefaultBodyLimit, Request};
use axum::http::{HeaderName, HeaderValue, StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::get;
use tower_http::services::{ServeDir, ServeFile};
use tracing::Instrument;

const ASSET_CACHE: &str = "public, max-age=31536000, immutable";
const NO_STORE: &str = "no-store";
const BODY_LIMIT: usize = 16 * 1024;
/// Scripts, connections and fonts stay on this origin. The enrolment QR is a `data:` image.
/// Style attributes are allowed because the UI sets them; `default-src 'self'` alone blocks
/// those attributes.
const CONTENT_SECURITY_POLICY: &str =
    "default-src 'self'; img-src 'self' data:; style-src-attr 'unsafe-inline'";
const REFERRER_POLICY: &str = "no-referrer";

/// Process configuration. `HOST` defaults to `0.0.0.0`, `PORT` to `8082`,
/// and `ADMIN_DIST` to `admin/ui/dist` (relative to the working directory).
pub struct Config {
    pub host: IpAddr,
    pub port: u16,
    pub dist: std::path::PathBuf,
}

impl Config {
    /// Read `HOST`, `PORT`, and `ADMIN_DIST`. Empty variables use the defaults.
    pub fn from_env() -> Result<Self, StartupError> {
        let host = env_or("HOST", "0.0.0.0").parse()?;
        let port = env_or("PORT", "8082").parse()?;
        let dist = env_or("ADMIN_DIST", "admin/ui/dist").into();
        Ok(Self { host, port, dist })
    }
}

#[derive(Debug, thiserror::Error)]
pub enum StartupError {
    #[error("HOST must be an IP address")]
    Host(#[from] std::net::AddrParseError),
    #[error("PORT must be a number from 0 to 65535")]
    Port(#[from] std::num::ParseIntError),
    #[error("failed to bind {addr}")]
    Bind {
        addr: SocketAddr,
        #[source]
        source: std::io::Error,
    },
    #[error("admin console stopped")]
    Serve(#[source] std::io::Error),
    #[error("could not connect to the database or migrate it")]
    Database,
    #[error("{0}")]
    Config(&'static str),
}

/// Router for `/healthz`, `/api/admin`, and, when `dist/index.html` exists, the built UI at `/admin/`.
pub fn router(dist: Option<&Path>) -> Router {
    router_with(dist, AppState::disconnected())
}

pub fn router_with(dist: Option<&Path>, state: AppState) -> Router {
    let api = auth::routes()
        .merge(overview::routes())
        .merge(users::routes())
        .merge(pages::routes())
        .merge(writes::routes())
        .merge(operators::routes())
        .fallback(auth::unknown)
        .layer(middleware::from_fn(no_store))
        .with_state(state);
    let mut router = Router::new()
        .route("/healthz", get(healthz))
        .nest("/api/admin", api);
    if let Some(dist) = dist.filter(|path| path.join("index.html").is_file()) {
        let assets = Router::new()
            .fallback_service(ServeDir::new(dist.join("assets")))
            .layer(middleware::from_fn(cache_hashed_asset));
        // no-store covers the pages only. The asset nest is a sibling, so a hashed file
        // keeps its year-long cache.
        let pages = Router::new()
            .fallback_service(ServeDir::new(dist).fallback(ServeFile::new(dist.join("index.html"))))
            .layer(middleware::from_fn(no_store));
        let ui = Router::new()
            .nest("/assets", assets)
            .fallback_service(pages);
        // Axum's nest matches `/admin/` only when the prefix itself ends in a slash,
        // and matches `/admin` only when it does not. The console is a path on the
        // Shroud site. Both spellings land on `/admin/`.
        router = router
            .route("/", get(|| async { Redirect::permanent("/admin/") }))
            .route("/admin", get(|| async { Redirect::permanent("/admin/") }))
            .nest("/admin/", ui);
    }
    router
        .layer(DefaultBodyLimit::max(BODY_LIMIT))
        .layer(middleware::from_fn(cap_body))
        .layer(middleware::from_fn(harden))
}

/// Listen on `HOST`:`PORT` and serve until the process is stopped.
pub async fn run() -> Result<(), StartupError> {
    init_tracing();
    let config = Config::from_env()?;
    let addr = SocketAddr::from((config.host, config.port));
    let ui = config.dist.join("index.html").is_file();
    tracing::info!(%addr, ui, dist = %config.dist.display(), "admin console listening");
    let state = load_state().await?;
    let app = router_with(Some(&config.dist), state);
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .map_err(|source| StartupError::Bind { addr, source })?;
    axum::serve(listener, app)
        .await
        .map_err(StartupError::Serve)?;
    Ok(())
}

async fn load_state() -> Result<AppState, StartupError> {
    let url = nonempty_env("ADMIN_DATABASE_URL");
    let key_hex = nonempty_env("ADMIN_SECRET_KEY");
    match (url, key_hex) {
        (None, _) => Ok(AppState::disconnected()),
        (Some(_), None) => Err(StartupError::Config(
            "ADMIN_SECRET_KEY is required when ADMIN_DATABASE_URL is set",
        )),
        (Some(url), Some(key_hex)) => {
            let Some(key) = crypto::key_from_hex(&key_hex) else {
                return Err(StartupError::Config(
                    "ADMIN_SECRET_KEY must be 64 hex characters",
                ));
            };
            let pool = db::connect(&url).await.map_err(|err| {
                db::log_db("connect", &err);
                StartupError::Database
            })?;
            db::migrate(&pool).await.map_err(|err| {
                db::log_db("migrate", &err);
                StartupError::Database
            })?;
            tracing::info!("admin schema ready");
            Ok(AppState::connected(pool, key))
        }
    }
}

fn nonempty_env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

pub(crate) fn init_tracing() {
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
    // A second call (tests, or a restarted binary in-process) leaves the first subscriber.
    let _ = tracing_subscriber::fmt().with_env_filter(filter).try_init();
}

fn env_or(name: &str, default: &str) -> String {
    match std::env::var(name) {
        Ok(value) if !value.is_empty() => value,
        _ => default.to_owned(),
    }
}

async fn healthz() -> impl IntoResponse {
    (
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, "text/plain; charset=utf-8"),
            (header::CACHE_CONTROL, NO_STORE),
        ],
        "ok",
    )
}

/// Successful responses under `/admin/assets/` are content-addressed by the UI build.
async fn cache_hashed_asset(request: Request, next: Next) -> Response {
    let mut response = next.run(request).await;
    let value = if response.status().is_success() {
        ASSET_CACHE
    } else {
        NO_STORE
    };
    insert_cache_control(&mut response, value);
    response
}

async fn no_store(request: Request, next: Next) -> Response {
    let mut response = next.run(request).await;
    insert_cache_control(&mut response, NO_STORE);
    response
}

fn insert_cache_control(response: &mut Response, value: &'static str) {
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static(value));
}

/// Refuse a body over [`BODY_LIMIT`] before a handler maps the rejection to 400.
async fn cap_body(request: Request, next: Next) -> Response {
    if request
        .headers()
        .get(header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok())
        .is_some_and(|len| len > BODY_LIMIT as u64)
    {
        return payload_too_large();
    }
    let (parts, body) = request.into_parts();
    let bytes = match axum::body::to_bytes(body, BODY_LIMIT).await {
        Ok(bytes) => bytes,
        Err(err) if length_limit(&err) => return payload_too_large(),
        Err(_) => {
            tracing::error!("request body could not be read");
            return StatusCode::BAD_REQUEST.into_response();
        }
    };
    next.run(Request::from_parts(parts, axum::body::Body::from(bytes)))
        .await
}

fn payload_too_large() -> Response {
    (
        StatusCode::PAYLOAD_TOO_LARGE,
        [(header::CONTENT_TYPE, "text/plain; charset=utf-8")],
        "length limit exceeded",
    )
        .into_response()
}

fn length_limit(err: &impl std::error::Error) -> bool {
    let mut current = Some(err as &dyn std::error::Error);
    while let Some(err) = current {
        if err.to_string() == "length limit exceeded" {
            return true;
        }
        current = err.source();
    }
    false
}

/// Security headers, a request id, and one log line. The line is the method, the path
/// without the query, the status and the id. It does not include an address.
async fn harden(request: Request, next: Next) -> Response {
    let request_id = request_id(request.headers());
    let method = request.method().clone();
    let path = request.uri().path().to_owned();
    let span = tracing::info_span!(
        "http",
        request_id = %request_id,
        method = %method,
        path = %path,
    );
    async move {
        let mut response = next.run(request).await;
        let headers = response.headers_mut();
        headers.insert(
            header::CONTENT_SECURITY_POLICY,
            HeaderValue::from_static(CONTENT_SECURITY_POLICY),
        );
        headers.insert(
            header::REFERRER_POLICY,
            HeaderValue::from_static(REFERRER_POLICY),
        );
        headers.insert(
            HeaderName::from_static("x-request-id"),
            HeaderValue::from_str(&request_id).expect("request id is a token"),
        );
        tracing::info!(status = response.status().as_u16(), "request");
        response
    }
    .instrument(span)
    .await
}

/// Keep a caller's id only when it cannot break a log line. Anything else gets a new one.
fn request_id(headers: &axum::http::HeaderMap) -> String {
    if let Some(value) = headers
        .get(HeaderName::from_static("x-request-id"))
        .and_then(|value| value.to_str().ok())
        && is_request_id(value)
    {
        return value.to_owned();
    }
    crypto::random_hex(16)
}

fn is_request_id(value: &str) -> bool {
    (1..=64).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    use axum::body::Body;
    use axum::http::{Request, StatusCode, header};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    use super::{
        ASSET_CACHE, BODY_LIMIT, CONTENT_SECURITY_POLICY, NO_STORE, REFERRER_POLICY, router,
    };

    static TEMP_SEQ: AtomicU64 = AtomicU64::new(0);

    struct TempDir {
        path: PathBuf,
    }

    impl TempDir {
        fn new(name: &str) -> Self {
            let n = TEMP_SEQ.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!("shroud-admin-{name}-{n}"));
            std::fs::create_dir_all(path.join("assets")).unwrap();
            Self { path }
        }

        fn write(&self, rel: &str, bytes: &str) {
            let path = self.path.join(rel);
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent).unwrap();
            }
            std::fs::write(path, bytes).unwrap();
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.path);
        }
    }

    async fn call(dist: Option<&std::path::Path>, uri: &str) -> axum::response::Response {
        router(dist)
            .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
            .await
            .unwrap()
    }

    async fn body(response: axum::response::Response) -> String {
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        String::from_utf8(bytes.to_vec()).unwrap()
    }

    fn cache(response: &axum::response::Response) -> &str {
        response
            .headers()
            .get(header::CACHE_CONTROL)
            .and_then(|value| value.to_str().ok())
            .unwrap_or("")
    }

    #[tokio::test]
    async fn healthz_without_a_ui_build() {
        let response = call(None, "/healthz").await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(cache(&response), NO_STORE);
        assert_eq!(body(response).await, "ok");

        let missing = call(None, "/").await;
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn healthz_is_not_replaced_by_index_html() {
        let dir = TempDir::new("healthz");
        dir.write("index.html", "<p>ui</p>");
        let response = call(Some(&dir.path), "/healthz").await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(body(response).await, "ok");
    }

    #[tokio::test]
    async fn index_and_client_routes_are_not_stored() {
        let dir = TempDir::new("spa");
        dir.write("index.html", "<p>ui</p>");
        dir.write("favicon.svg", "<svg></svg>");

        let root = call(Some(&dir.path), "/").await;
        assert_eq!(root.status(), StatusCode::PERMANENT_REDIRECT);
        assert_eq!(
            root.headers()
                .get(header::LOCATION)
                .and_then(|v| v.to_str().ok()),
            Some("/admin/")
        );

        let bare = call(Some(&dir.path), "/admin").await;
        assert_eq!(bare.status(), StatusCode::PERMANENT_REDIRECT);
        assert_eq!(
            bare.headers()
                .get(header::LOCATION)
                .and_then(|v| v.to_str().ok()),
            Some("/admin/")
        );

        let index = call(Some(&dir.path), "/admin/").await;
        assert_eq!(index.status(), StatusCode::OK);
        assert_eq!(cache(&index), NO_STORE);
        assert_eq!(body(index).await, "<p>ui</p>");

        let route = call(Some(&dir.path), "/admin/users").await;
        assert_eq!(route.status(), StatusCode::OK);
        assert_eq!(cache(&route), NO_STORE);
        assert_eq!(body(route).await, "<p>ui</p>");

        let named = call(Some(&dir.path), "/admin/index.html").await;
        assert_eq!(named.status(), StatusCode::OK);
        assert_eq!(cache(&named), NO_STORE);

        let icon = call(Some(&dir.path), "/admin/favicon.svg").await;
        assert_eq!(icon.status(), StatusCode::OK);
        assert_eq!(cache(&icon), NO_STORE);
    }

    #[tokio::test]
    async fn hashed_assets_are_cached_for_a_year() {
        let dir = TempDir::new("assets");
        dir.write("index.html", "<p>ui</p>");
        dir.write("assets/app.js", "console.log(1)");

        let asset = call(Some(&dir.path), "/admin/assets/app.js").await;
        assert_eq!(asset.status(), StatusCode::OK);
        assert_eq!(cache(&asset), ASSET_CACHE);
        assert_eq!(body(asset).await, "console.log(1)");

        let missing = call(Some(&dir.path), "/admin/assets/missing.js").await;
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
        assert_eq!(cache(&missing), NO_STORE);
    }

    #[tokio::test]
    async fn a_dist_without_index_html_serves_only_healthz() {
        let dir = TempDir::new("no-index");
        dir.write("assets/app.js", "console.log(1)");
        let response = call(Some(&dir.path), "/admin/assets/app.js").await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        let health = call(Some(&dir.path), "/healthz").await;
        assert_eq!(health.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn admin_routes_answer_json_when_there_is_no_database() {
        let dir = TempDir::new("api");
        dir.write("index.html", "<p>ui</p>");
        let app = router(Some(&dir.path));

        let missing = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/admin/session")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(missing.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(cache(&missing), NO_STORE);
        assert_json(
            body(missing).await,
            include_str!("../fixtures/error.unauthenticated.json"),
        );

        let down = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/admin/session")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(
                        r#"{"operator":"operator","password":"correct-horse","totp":"123456"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(down.status(), StatusCode::BAD_GATEWAY);
        assert_eq!(cache(&down), NO_STORE);
        let text = body(down).await;
        assert_ne!(text, "<p>ui</p>");
        assert_json(
            text,
            include_str!("../fixtures/error.upstream-postgres.json"),
        );
    }

    fn assert_json(actual: String, fixture: &str) {
        let actual: serde_json::Value = serde_json::from_str(&actual).unwrap();
        let expected: serde_json::Value = serde_json::from_str(fixture).unwrap();
        assert_eq!(actual, expected);
    }

    fn assert_hardened(response: &axum::response::Response) {
        assert_eq!(
            response
                .headers()
                .get(header::CONTENT_SECURITY_POLICY)
                .and_then(|value| value.to_str().ok()),
            Some(CONTENT_SECURITY_POLICY)
        );
        assert_eq!(
            response
                .headers()
                .get(header::REFERRER_POLICY)
                .and_then(|value| value.to_str().ok()),
            Some(REFERRER_POLICY)
        );
        let id = response
            .headers()
            .get("x-request-id")
            .and_then(|value| value.to_str().ok())
            .unwrap();
        assert!(super::is_request_id(id), "{id}");
    }

    #[tokio::test]
    async fn responses_carry_the_content_security_policy_and_no_referrer() {
        let dir = TempDir::new("csp");
        dir.write("index.html", "<p>ui</p>");
        dir.write("assets/app.js", "console.log(1)");
        let app = router(Some(&dir.path));

        let health = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/healthz")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(health.status(), StatusCode::OK);
        assert_hardened(&health);

        let page = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/admin/users")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(page.status(), StatusCode::OK);
        assert_hardened(&page);

        let asset = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/admin/assets/app.js")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(asset.status(), StatusCode::OK);
        assert_hardened(&asset);

        let api = app
            .oneshot(
                Request::builder()
                    .uri("/api/admin/session")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(api.status(), StatusCode::UNAUTHORIZED);
        assert_hardened(&api);
    }

    #[tokio::test]
    async fn bodies_over_16_kib_are_rejected() {
        let app = router(None);
        let kept = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/admin/session")
                    .header(header::CONTENT_TYPE, "application/json")
                    .header(header::CONTENT_LENGTH, BODY_LIMIT.to_string())
                    .body(Body::from(vec![b'a'; BODY_LIMIT]))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_ne!(kept.status(), StatusCode::PAYLOAD_TOO_LARGE);
        assert_hardened(&kept);

        let declared = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/admin/session")
                    .header(header::CONTENT_TYPE, "application/json")
                    .header(header::CONTENT_LENGTH, (BODY_LIMIT + 1).to_string())
                    .body(Body::from("{}"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(declared.status(), StatusCode::PAYLOAD_TOO_LARGE);
        assert_hardened(&declared);

        let streamed = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/admin/session")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(vec![b'{'; BODY_LIMIT + 1]))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(streamed.status(), StatusCode::PAYLOAD_TOO_LARGE);
        assert_hardened(&streamed);
    }
}
