//! Shared Axum application state.

use std::sync::Arc;

use axum::http::HeaderMap;
use sqlx::PgPool;

use crate::client_version::ClientVersions;
use crate::config::IceServer;
use crate::link_relay::RelayPolicy;
use crate::media_store::MediaStore;
use crate::metrics::Metrics;
use crate::push::PushService;
use crate::rate_limit::{self, RateLimiter};
use crate::realtime::RealtimeHub;
use crate::routes::link_relay::LinkRelay;
use crate::turn::TurnConfig;
use crate::username_kdf::UsernameKdf;

/// State injected into every API handler.
#[derive(Clone)]
pub struct AppState {
    /// Shared Postgres pool for metadata queries (no message plaintext).
    pub pool: PgPool,
    /// Encrypted media blobs: Nebular OS, or a local directory.
    pub media: Arc<MediaStore>,
    /// In-process WebSocket fan-out hub (single instance).
    pub realtime: Arc<RealtimeHub>,
    /// Push dispatcher: APNs (sends only with credentials) and Web Push.
    pub push: PushService,
    /// STUN/TURN servers advertised to clients for WebRTC.
    pub ice_servers: Vec<IceServer>,
    /// TURN whose per-user logins `GET /calls/ice-servers` mints.
    pub turn: Option<TurnConfig>,
    /// Abuse budgets (Redis when configured, else in-process).
    pub rate_limiter: RateLimiter,
    /// When true, readiness requires a live Redis connection (`REDIS_URL` was set).
    pub redis_required: bool,
    /// Honor `X-Forwarded-For` / `X-Real-IP` only when behind a trusted proxy.
    pub trust_forwarded_headers: bool,
    /// Process metrics for `/operator/metrics`.
    pub metrics: Arc<Metrics>,
    /// Link-preview relay: target policy and each account's open pipes.
    pub link_relay: Arc<LinkRelay>,
    /// Most emoji one person may leave on one message; clients read it from `GET /config`.
    pub reactions_max_per_user: u32,
    /// Released app versions; clients read them from `GET /client-version`.
    pub client_versions: Arc<ClientVersions>,
    /// Username lookup hash. Clients read the salt from `GET /auth/username-kdf`.
    pub username_kdf: UsernameKdf,
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
        // Pushes are recorded (`state.push.recorded()`), never sent.
        let push = PushService::recording(pool.clone(), realtime.clone());
        Self {
            pool,
            media: Arc::new(MediaStore::for_integration_tests()),
            realtime,
            push,
            ice_servers: vec![],
            turn: None,
            reactions_max_per_user: 5,
            client_versions: Arc::new(ClientVersions::default()),
            username_kdf: UsernameKdf::for_tests(),
            rate_limiter,
            redis_required: false,
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
