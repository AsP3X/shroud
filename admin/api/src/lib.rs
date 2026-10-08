//! Admin console HTTP process.
//!
//! Serves `GET /healthz` and, when `admin/ui/dist/index.html` is present, the built UI.
//! Hashed files under `/assets/` are cached for a year. `index.html` and client-side
//! routes are `Cache-Control: no-store`.
//!
//! This crate is its own Cargo workspace. Build it from `admin/api`. Rate-limit and
//! retention constants come in later as a path dependency, not by joining `server/`.

use std::net::{IpAddr, SocketAddr};
use std::path::Path;

use axum::Router;
use axum::extract::Request;
use axum::http::{HeaderValue, StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use tower_http::services::{ServeDir, ServeFile};

const ASSET_CACHE: &str = "public, max-age=31536000, immutable";
const NO_STORE: &str = "no-store";

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
}

/// Router for `/healthz` and, when `dist/index.html` exists, the built UI.
pub fn router(dist: Option<&Path>) -> Router {
    let mut router = Router::new().route("/healthz", get(healthz));
    if let Some(dist) = dist.filter(|path| path.join("index.html").is_file()) {
        let assets = Router::new()
            .fallback_service(ServeDir::new(dist.join("assets")))
            .layer(middleware::from_fn(cache_hashed_asset));
        let spa = Router::new()
            .fallback_service(ServeDir::new(dist).fallback(ServeFile::new(dist.join("index.html"))))
            .layer(middleware::from_fn(no_store));
        router = router.nest("/assets", assets).fallback_service(spa);
    }
    router
}

/// Listen on `HOST`:`PORT` and serve until the process is stopped.
pub async fn run() -> Result<(), StartupError> {
    init_tracing();
    let config = Config::from_env()?;
    let addr = SocketAddr::from((config.host, config.port));
    let ui = config.dist.join("index.html").is_file();
    tracing::info!(%addr, ui, dist = %config.dist.display(), "admin console listening");
    let app = router(Some(&config.dist));
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .map_err(|source| StartupError::Bind { addr, source })?;
    axum::serve(listener, app)
        .await
        .map_err(StartupError::Serve)?;
    Ok(())
}

fn init_tracing() {
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

/// Successful responses under `/assets/` are content-addressed by the UI build.
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

#[cfg(test)]
mod tests {
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    use axum::body::Body;
    use axum::http::{Request, StatusCode, header};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    use super::{ASSET_CACHE, NO_STORE, router};

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

        let index = call(Some(&dir.path), "/").await;
        assert_eq!(index.status(), StatusCode::OK);
        assert_eq!(cache(&index), NO_STORE);
        assert_eq!(body(index).await, "<p>ui</p>");

        let route = call(Some(&dir.path), "/users").await;
        assert_eq!(route.status(), StatusCode::OK);
        assert_eq!(cache(&route), NO_STORE);
        assert_eq!(body(route).await, "<p>ui</p>");

        let named = call(Some(&dir.path), "/index.html").await;
        assert_eq!(named.status(), StatusCode::OK);
        assert_eq!(cache(&named), NO_STORE);

        let icon = call(Some(&dir.path), "/favicon.svg").await;
        assert_eq!(icon.status(), StatusCode::OK);
        assert_eq!(cache(&icon), NO_STORE);
    }

    #[tokio::test]
    async fn hashed_assets_are_cached_for_a_year() {
        let dir = TempDir::new("assets");
        dir.write("index.html", "<p>ui</p>");
        dir.write("assets/app.js", "console.log(1)");

        let asset = call(Some(&dir.path), "/assets/app.js").await;
        assert_eq!(asset.status(), StatusCode::OK);
        assert_eq!(cache(&asset), ASSET_CACHE);
        assert_eq!(body(asset).await, "console.log(1)");

        let missing = call(Some(&dir.path), "/assets/missing.js").await;
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
        assert_eq!(cache(&missing), NO_STORE);
    }

    #[tokio::test]
    async fn a_dist_without_index_html_serves_only_healthz() {
        let dir = TempDir::new("no-index");
        dir.write("assets/app.js", "console.log(1)");
        let response = call(Some(&dir.path), "/assets/app.js").await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        let health = call(Some(&dir.path), "/healthz").await;
        assert_eq!(health.status(), StatusCode::OK);
    }
}
