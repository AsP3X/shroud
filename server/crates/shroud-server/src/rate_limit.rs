//! Fixed-window rate limits for abuse-prone API surfaces.
//!
//! Budgets follow `docs/server-plan.md` (`rl:{scope}:{id}` keys).
//! Uses Redis when configured; otherwise an in-process window (single-instance).
//! Existing integration tests use [`RateLimiter::disabled`] so concurrent suites
//! are not flaky; unit tests and production use an enabled limiter.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::http::HeaderMap;
use redis::AsyncCommands;
use redis::aio::ConnectionManager;
use tokio::sync::RwLock;

use crate::error::AppError;

/// Documented starting budgets (server-plan § Rate limits).
pub mod budgets {
    use std::time::Duration;

    pub const AUTH_IP: (u64, Duration) = (10, Duration::from_secs(60));
    pub const AUTH_USERNAME: (u64, Duration) = (5, Duration::from_secs(60));
    pub const USER_LOOKUP_IP: (u64, Duration) = (30, Duration::from_secs(60));
    pub const KEYS_USER: (u64, Duration) = (60, Duration::from_secs(60));
    pub const KEYS_IP: (u64, Duration) = (120, Duration::from_secs(60));
    pub const CONTACT_REQUEST_USER: (u64, Duration) = (10, Duration::from_secs(3600));
    /// New share codes per account: each one breaks every QR code and link handed out before.
    pub const SHARE_CODE_ROTATE_USER: (u64, Duration) = (10, Duration::from_secs(3600));
    pub const MEDIA_PRESIGN_USER: (u64, Duration) = (60, Duration::from_secs(60));
    pub const WS_CONNECT_IP: (u64, Duration) = (30, Duration::from_secs(60));
    /// Message send / delivery hot path.
    pub const MESSAGE_SEND_USER: (u64, Duration) = (120, Duration::from_secs(60));
    /// Reaction set / replace / remove. Separate from sends so tapping through reactions
    /// never eats the message budget.
    pub const REACTION_USER: (u64, Duration) = (120, Duration::from_secs(60));
    /// Placing calls.
    pub const CALL_USER: (u64, Duration) = (30, Duration::from_secs(60));
    /// A call's signals and heartbeats: an offer, an answer, batches of ICE candidates, and a
    /// heartbeat every 10 s from each device, with room for ICE restarts.
    pub const CALL_SIGNAL_USER: (u64, Duration) = (600, Duration::from_secs(60));
    /// Password change and account delete (expensive / sensitive).
    pub const AUTH_SENSITIVE_USER: (u64, Duration) = (5, Duration::from_secs(3600));
    /// Link-preview relay upgrades per client IP (a preview is a page plus its image,
    /// and redirects add one each).
    pub const LINK_RELAY_IP: (u64, Duration) = (120, Duration::from_secs(60));
    /// Link-preview relay connections per account.
    pub const LINK_RELAY_USER: (u64, Duration) = (60, Duration::from_secs(60));
    /// PIN-guard unlocks per client IP. The guard's own counter is the real limit; this keeps
    /// one address from probing many guards.
    pub const PIN_GUARD_IP: (u64, Duration) = (20, Duration::from_secs(60));
    /// Locked clients asking whether their device was removed (`POST /auth/session-status`):
    /// each open tab asks about twice a minute.
    pub const SESSION_STATUS_IP: (u64, Duration) = (60, Duration::from_secs(60));
    /// PIN changes per account.
    pub const PIN_GUARD_USER: (u64, Duration) = (10, Duration::from_secs(3600));
    /// Device renames per account: each unlock re-seals at most one name, renames are by hand.
    pub const DEVICE_NAME_USER: (u64, Duration) = (60, Duration::from_secs(3600));
    /// Test notifications per device: a settings button, not a way to spam a relay.
    pub const PUSH_TEST_DEVICE: (u64, Duration) = (6, Duration::from_secs(60));
}

struct MemoryWindow {
    count: u64,
    reset_at: Instant,
}

/// Shared rate limiter (cheap to clone via [`Arc`]).
#[derive(Clone)]
pub struct RateLimiter {
    inner: Arc<RateLimiterInner>,
}

struct RateLimiterInner {
    /// When false, every check succeeds (test apps).
    enabled: bool,
    redis: RwLock<Option<ConnectionManager>>,
    memory: RwLock<HashMap<String, MemoryWindow>>,
}

impl RateLimiter {
    /// Enabled limiter: Redis when attached, else in-process windows.
    pub fn new() -> Self {
        Self {
            inner: Arc::new(RateLimiterInner {
                enabled: true,
                redis: RwLock::new(None),
                memory: RwLock::new(HashMap::new()),
            }),
        }
    }

    /// No-op limiter for integration tests that hammer routes without Redis.
    pub fn disabled() -> Self {
        Self {
            inner: Arc::new(RateLimiterInner {
                enabled: false,
                redis: RwLock::new(None),
                memory: RwLock::new(HashMap::new()),
            }),
        }
    }

    /// Attach a Redis connection manager for multi-replica counting.
    pub async fn set_redis(&self, manager: ConnectionManager) {
        *self.inner.redis.write().await = Some(manager);
    }

    /// Increments the counter for `scope`+`id` and errors if over `limit` in `window`.
    pub async fn check(
        &self,
        scope: &str,
        id: &str,
        limit: u64,
        window: Duration,
    ) -> Result<(), AppError> {
        if !self.inner.enabled {
            return Ok(());
        }

        let key = format!("rl:{scope}:{id}");
        let window_secs = window.as_secs().max(1);

        if let Some(mut conn) = self.inner.redis.read().await.clone() {
            match redis_check(&mut conn, &key, limit, window_secs).await {
                Ok(allowed) => {
                    if allowed {
                        return Ok(());
                    }
                    return Err(AppError::rate_limited_after(window_secs));
                }
                Err(err) => {
                    // Human: Degrade to in-process windows — do not fail open under Redis outage.
                    // Agent: FALLBACK memory_check on Redis error; still enforces per-process budgets.
                    tracing::warn!(
                        error = %err,
                        %key,
                        "rate limit redis failed; falling back to in-process window"
                    );
                }
            }
        }

        memory_check(&self.inner.memory, &key, limit, window).await
    }

    /// Convenience: check with a `(limit, window)` budget tuple.
    pub async fn check_budget(
        &self,
        scope: &str,
        id: &str,
        budget: (u64, Duration),
    ) -> Result<(), AppError> {
        self.check(scope, id, budget.0, budget.1).await
    }
}

impl Default for RateLimiter {
    fn default() -> Self {
        Self::new()
    }
}

async fn redis_check(
    conn: &mut ConnectionManager,
    key: &str,
    limit: u64,
    window_secs: u64,
) -> Result<bool, redis::RedisError> {
    // INCR then EXPIRE on first hit — fixed window.
    let count: u64 = conn.incr(key, 1u64).await?;
    if count == 1 {
        let _: () = conn.expire(key, window_secs as i64).await?;
    }
    Ok(count <= limit)
}

async fn memory_check(
    memory: &RwLock<HashMap<String, MemoryWindow>>,
    key: &str,
    limit: u64,
    window: Duration,
) -> Result<(), AppError> {
    let now = Instant::now();
    let mut map = memory.write().await;
    let entry = map.entry(key.to_string()).or_insert_with(|| MemoryWindow {
        count: 0,
        reset_at: now + window,
    });

    if now >= entry.reset_at {
        entry.count = 0;
        entry.reset_at = now + window;
    }

    entry.count = entry.count.saturating_add(1);
    if entry.count > limit {
        return Err(AppError::rate_limited_after(window.as_secs().max(1)));
    }
    Ok(())
}

/// Request header carrying the TCP peer address. Set by [`peer_ip_middleware`] from the
/// connection itself; whatever a client sends under this name is dropped first.
pub const PEER_IP_HEADER: &str = "x-shroud-peer-ip";

/// Records the connection's peer address in [`PEER_IP_HEADER`] for [`client_ip`].
///
/// Human: Handlers only see headers, so the socket address travels as one the client cannot set.
pub async fn peer_ip_middleware(
    mut request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    request.headers_mut().remove(PEER_IP_HEADER);
    let peer = request
        .extensions()
        .get::<axum::extract::ConnectInfo<SocketAddr>>()
        .map(|info| info.0.ip().to_canonical());
    if let Some(peer) = peer
        && let Ok(value) = axum::http::HeaderValue::from_str(&peer.to_string())
    {
        request.headers_mut().insert(PEER_IP_HEADER, value);
    }
    next.run(request).await
}

/// Client IP for limit keys.
///
/// Without a trusted proxy (`trust_forwarded` false) this is the TCP peer, and forwarding
/// headers are ignored so clients cannot pick their own key.
///
/// Behind proxies, every hop appends the address it saw to `X-Forwarded-For`, so only the
/// right-hand end of the list is trustworthy: anything left of the real client is whatever the
/// client sent. Walk the chain (`X-Forwarded-For`, then the peer) from the right past the
/// proxies — private, loopback and link-local addresses such as Docker networks — and key on the
/// first public address. A chain with no public address (a LAN deployment) falls back to its
/// left-most entry, then `X-Real-IP`.
pub fn client_ip(headers: &HeaderMap, trust_forwarded: bool) -> String {
    let peer = header_str(headers, PEER_IP_HEADER).and_then(parse_ip);
    if !trust_forwarded {
        return peer.map_or_else(|| "unknown".into(), |ip| ip.to_string());
    }

    let mut chain: Vec<IpAddr> = headers
        .get_all("x-forwarded-for")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .flat_map(|value| value.split(','))
        .filter_map(parse_ip)
        .collect();
    chain.extend(peer);

    if let Some(public) = chain.iter().rev().find(|ip| !is_internal(ip)) {
        return public.to_string();
    }
    if let Some(first) = chain
        .first()
        .filter(|_| headers.contains_key("x-forwarded-for"))
    {
        return first.to_string();
    }
    if let Some(real) = header_str(headers, "x-real-ip").and_then(parse_ip) {
        return real.to_string();
    }
    peer.map_or_else(|| "unknown".into(), |ip| ip.to_string())
}

fn header_str<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get(name).and_then(|value| value.to_str().ok())
}

fn parse_ip(raw: &str) -> Option<IpAddr> {
    let raw = raw.trim();
    raw.parse::<IpAddr>()
        .or_else(|_| raw.parse::<SocketAddr>().map(|addr| addr.ip()))
        .ok()
        .map(|ip| ip.to_canonical())
}

/// Addresses a reverse proxy in front of the API would have: never a client on the internet.
fn is_internal(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            v4.is_private()
                || v4.is_loopback()
                || v4.is_link_local()
                || v4.is_unspecified()
                // 100.64.0.0/10, carrier-grade NAT and Tailscale.
                || (v4.octets()[0] == 100 && (v4.octets()[1] & 0xc0) == 64)
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()
                || v6.is_unspecified()
                || (v6.segments()[0] & 0xfe00) == 0xfc00
                || (v6.segments()[0] & 0xffc0) == 0xfe80
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn memory_allows_under_limit() {
        let rl = RateLimiter::new();
        for _ in 0..3 {
            rl.check("test", "a", 3, Duration::from_secs(60))
                .await
                .expect("allowed");
        }
    }

    #[tokio::test]
    async fn memory_blocks_over_limit() {
        let rl = RateLimiter::new();
        for _ in 0..3 {
            rl.check("test", "b", 3, Duration::from_secs(60))
                .await
                .expect("allowed");
        }
        let err = rl
            .check("test", "b", 3, Duration::from_secs(60))
            .await
            .expect_err("blocked");
        match err {
            AppError::RateLimited { retry_after_secs } => {
                assert_eq!(retry_after_secs, 60);
            }
            other => panic!("expected RateLimited, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn rate_limited_uses_window_for_retry_after() {
        let rl = RateLimiter::new();
        let window = Duration::from_secs(3600);
        for _ in 0..2 {
            rl.check("test", "hour", 2, window).await.expect("allowed");
        }
        let err = rl
            .check("test", "hour", 2, window)
            .await
            .expect_err("blocked");
        match err {
            AppError::RateLimited { retry_after_secs } => {
                assert_eq!(retry_after_secs, 3600);
            }
            other => panic!("expected RateLimited, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn disabled_never_blocks() {
        let rl = RateLimiter::disabled();
        for _ in 0..100 {
            rl.check("test", "c", 1, Duration::from_secs(60))
                .await
                .expect("disabled");
        }
    }

    #[tokio::test]
    async fn separate_keys_are_independent() {
        let rl = RateLimiter::new();
        rl.check("test", "x", 1, Duration::from_secs(60))
            .await
            .unwrap();
        rl.check("test", "y", 1, Duration::from_secs(60))
            .await
            .unwrap();
        assert!(
            rl.check("test", "x", 1, Duration::from_secs(60))
                .await
                .is_err()
        );
        assert!(
            rl.check("test", "y", 1, Duration::from_secs(60))
                .await
                .is_err()
        );
    }

    #[test]
    fn client_ip_prefers_forwarded_for_when_trusted() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "1.2.3.4, 10.0.0.1".parse().unwrap());
        headers.insert("x-real-ip", "9.9.9.9".parse().unwrap());
        assert_eq!(client_ip(&headers, true), "1.2.3.4");
    }

    #[test]
    fn client_ip_ignores_forwarded_when_untrusted() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "1.2.3.4".parse().unwrap());
        assert_eq!(client_ip(&headers, false), "unknown");
    }

    #[test]
    fn client_ip_falls_back_to_real_ip_when_trusted() {
        let mut headers = HeaderMap::new();
        headers.insert("x-real-ip", "8.8.8.8".parse().unwrap());
        assert_eq!(client_ip(&headers, true), "8.8.8.8");
    }

    #[test]
    fn client_ip_skips_entries_the_client_wrote() {
        // nginx appends the address it saw; the left-hand entry is the client's own claim.
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "6.6.6.6, 203.0.113.7".parse().unwrap());
        headers.insert(PEER_IP_HEADER, "172.18.0.3".parse().unwrap());
        assert_eq!(client_ip(&headers, true), "203.0.113.7");
    }

    #[test]
    fn client_ip_walks_past_every_proxy_hop() {
        // Client → Nginx Proxy Manager → web nginx → API, all proxies on Docker networks.
        let mut headers = HeaderMap::new();
        headers.insert(
            "x-forwarded-for",
            "10.9.9.9, 198.51.100.4, 172.20.0.2".parse().unwrap(),
        );
        headers.insert(PEER_IP_HEADER, "172.18.0.5".parse().unwrap());
        assert_eq!(client_ip(&headers, true), "198.51.100.4");
    }

    #[test]
    fn client_ip_uses_the_peer_without_forwarding() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "1.2.3.4".parse().unwrap());
        headers.insert(PEER_IP_HEADER, "203.0.113.9".parse().unwrap());
        assert_eq!(client_ip(&headers, false), "203.0.113.9");
        // A public peer is the client even when trusted: a forwarding header cannot replace it.
        assert_eq!(client_ip(&headers, true), "203.0.113.9");
    }

    #[test]
    fn client_ip_on_a_lan_keys_on_the_first_hop() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "192.168.1.20".parse().unwrap());
        headers.insert(PEER_IP_HEADER, "172.18.0.3".parse().unwrap());
        assert_eq!(client_ip(&headers, true), "192.168.1.20");
    }

    #[test]
    fn client_ip_reads_mapped_ipv6_peers_as_ipv4() {
        let mut headers = HeaderMap::new();
        headers.insert(PEER_IP_HEADER, "::ffff:203.0.113.9".parse().unwrap());
        assert_eq!(client_ip(&headers, false), "203.0.113.9");
    }

    #[test]
    fn client_ip_unknown_when_missing() {
        assert_eq!(client_ip(&HeaderMap::new(), true), "unknown");
    }
}
