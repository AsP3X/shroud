//! Shared Axum application state.

use std::sync::Arc;

use axum::http::HeaderMap;
use sqlx::PgPool;

use crate::config::IceServer;
use crate::link_relay::RelayPolicy;
use crate::metrics::Metrics;
use crate::push::PushService;
use crate::rate_limit::{self, RateLimiter};
use crate::realtime::RealtimeHub;
use crate::routes::link_relay::LinkRelay;

/// State injected into every API handler.
#[derive(Clone)]
pub struct AppState {
    /// Shared Postgres pool for metadata queries (no message plaintext).
    pub pool: PgPool,
    /// Optional Nebular base URL; `None` uses local media volume only.
    pub nebular_url: Option<String>,
    /// Object storage bucket name for encrypted media.
    pub media_bucket: String,
    /// When true and Nebular is configured, prefer Nebular for media reads (multi-replica).
    pub media_prefer_nebular: bool,
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
    /// Honor `X-Forwarded-For` / `X-Real-IP` only when behind a trusted proxy.
    pub trust_forwarded_headers: bool,
    /// Process metrics for `/metrics`.
    pub metrics: Arc<Metrics>,
    /// Link-preview relay: target policy and each account's open pipes.
    pub link_relay: Arc<LinkRelay>,
    /// Most emoji one person may leave on one message; clients read it from `GET /config`.
    pub reactions_max_per_user: u32,
}

impl AppState {
    /// Client IP for rate-limit keys (respects [`Self::trust_forwarded_headers`]).
    pub fn client_ip(&self, headers: &HeaderMap) -> String {
        rate_limit::client_ip(headers, self.trust_forwarded_headers)
    }

    /// Builds state for integration tests (rate limits off, trust forwarded headers for XFF tests).
    ///
    /// Human: Keeps test setup identical across suites so new fields are not forgotten.
    /// Agent: CALLS RateLimiter::disabled; trust_forwarded_headers=true for proxy-header tests.
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
            media_prefer_nebular: false,
            realtime,
            push,
            ice_servers: vec![],
            reactions_max_per_user: 5,
            rate_limiter,
            redis_required: false,
            http_client: reqwest::Client::new(),
            trust_forwarded_headers: true,
            metrics: Arc::new(Metrics::new()),
            link_relay: Arc::new(LinkRelay::new(RelayPolicy::production())),
        }
    }

    /// Test state whose link relay may reach a local listener (see `RelayPolicy::for_tests`).
    pub fn with_link_relay_policy(mut self, policy: RelayPolicy) -> Self {
        self.link_relay = Arc::new(LinkRelay::new(policy));
        self
    }
}
