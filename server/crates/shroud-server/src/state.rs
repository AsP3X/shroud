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
}
