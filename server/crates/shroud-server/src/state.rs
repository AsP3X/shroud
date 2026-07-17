//! Shared Axum application state.

use std::sync::Arc;

use sqlx::PgPool;

use crate::config::IceServer;
use crate::push::PushService;
use crate::rate_limit::RateLimiter;
use crate::realtime::RealtimeHub;

/// State injected into every API handler.
#[derive(Clone)]
pub struct AppState {
    /// Shared Postgres pool for metadata queries (no message plaintext).
    pub pool: PgPool,
    /// Optional Nebular base URL; `None` enables stub media presigns.
    pub nebular_url: Option<String>,
    /// Object storage bucket name for encrypted media.
    pub media_bucket: String,
    /// In-process WebSocket fan-out hub (single instance).
    pub realtime: Arc<RealtimeHub>,
    /// APNs data-push dispatcher (no-ops send when credentials missing).
    pub push: PushService,
    /// STUN/TURN servers advertised to clients for WebRTC.
    pub ice_servers: Vec<IceServer>,
    /// Abuse budgets (Redis when configured, else in-process).
    pub rate_limiter: RateLimiter,
    /// When true, readiness requires a live Redis connection (`REDIS_URL` was set).
    pub redis_required: bool,
    /// Shared HTTP client for Nebular (and other outbound) calls.
    pub http_client: reqwest::Client,
}

impl AppState {
    /// Builds state for integration tests (rate limits off, no Nebular/APNs).
    ///
    /// Human: Keeps test setup identical across suites so new fields are not forgotten.
    /// Agent: CALLS RateLimiter::disabled; WRITES AppState with reqwest::Client::new().
    pub fn for_integration_tests(pool: PgPool) -> Self {
        Self::for_integration_tests_with_limiter(pool, RateLimiter::disabled())
    }

    /// Like [`Self::for_integration_tests`] but with a custom rate limiter (e.g. enabled).
    pub fn for_integration_tests_with_limiter(pool: PgPool, rate_limiter: RateLimiter) -> Self {
        let realtime = Arc::new(RealtimeHub::new());
        let push = PushService::new(pool.clone(), realtime.clone(), None);
        Self {
            pool,
            nebular_url: None,
            media_bucket: "shroud-media".into(),
            realtime,
            push,
            ice_servers: vec![],
            rate_limiter,
            redis_required: false,
            http_client: reqwest::Client::new(),
        }
    }
}
