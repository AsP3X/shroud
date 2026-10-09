//! Fixed-window rate limits for abuse-prone API surfaces.
//!
//! Budgets follow `docs/server-plan.md` (`rl:{scope}:{id}` keys).
//! Uses Redis when configured; otherwise an in-process window (single-instance).
//! Existing integration tests use [`RateLimiter::disabled`] so concurrent suites
//! are not flaky; unit tests and production use an enabled limiter.

use std::collections::HashMap;
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

/// In-process windows, and when finished ones are next dropped. A key is an IP, a user id or a
/// username hash, so it must not outlive its window by more than one sweep.
struct MemoryState {
    windows: HashMap<String, MemoryWindow>,
    next_sweep: Instant,
}

/// How often finished windows are dropped from [`MemoryState`].
const MEMORY_SWEEP_EVERY: Duration = Duration::from_secs(60);

impl MemoryState {
    fn new() -> Self {
        Self {
            windows: HashMap::new(),
            next_sweep: Instant::now() + MEMORY_SWEEP_EVERY,
        }
    }
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
    memory: RwLock<MemoryState>,
    /// Until when Redis is skipped after it failed or didn't answer (see [`REDIS_BACKOFF`]).
    redis_down_until: std::sync::Mutex<Option<Instant>>,
    /// Told when Redis didn't answer (see [`RateLimiter::on_silent_redis`]).
    on_silent_redis: std::sync::Mutex<Option<SilentRedisReport>>,
}

type SilentRedisReport = Arc<dyn Fn() + Send + Sync>;

/// Longest a Redis call may take before the in-process window counts instead.
///
/// Human: The connection manager never gives up on its own: with Redis gone, a call waited
/// for good and every rate-limited route hung. The console's rate-limit check found it.
const REDIS_TIMEOUT: Duration = Duration::from_millis(500);
/// After a Redis failure, counting stays in-process this long before Redis is tried again, so
/// an outage costs one slow request rather than a slow request each.
const REDIS_BACKOFF: Duration = Duration::from_secs(5);

impl RateLimiter {
    /// Enabled limiter: Redis when attached, else in-process windows.
    pub fn new() -> Self {
        Self {
            inner: Arc::new(RateLimiterInner {
                enabled: true,
                redis: RwLock::new(None),
                memory: RwLock::new(MemoryState::new()),
                redis_down_until: std::sync::Mutex::new(None),
                on_silent_redis: std::sync::Mutex::new(None),
            }),
        }
    }

    /// No-op limiter for integration tests that hammer routes without Redis.
    pub fn disabled() -> Self {
        Self {
            inner: Arc::new(RateLimiterInner {
                enabled: false,
                redis: RwLock::new(None),
                memory: RwLock::new(MemoryState::new()),
                redis_down_until: std::sync::Mutex::new(None),
                on_silent_redis: std::sync::Mutex::new(None),
            }),
        }
    }

    /// Attach a Redis connection manager for multi-replica counting.
    ///
    /// Agent: Ends [`REDIS_BACKOFF`]: a manager handed in while counting is in-process is a
    /// fresh connection (the realtime hub's replacement for a silent one), so it is tried at
    /// once.
    pub async fn set_redis(&self, manager: ConnectionManager) {
        *self.inner.redis.write().await = Some(manager);
        if let Ok(mut until) = self.inner.redis_down_until.lock() {
            *until = None;
        }
    }

    /// Called when a check gets no answer from Redis, so the connection can be replaced.
    ///
    /// Human: The limiter's connection is the realtime hub's (`lib.rs`), and the hub opens a
    /// fresh one when it goes silent. Without this the hub noticed only through its own calls,
    /// and a limiter that was the only one using Redis stayed in-process meanwhile.
    /// Agent: Not called on an error: the connection manager reconnects on I/O errors itself.
    pub fn on_silent_redis(&self, report: impl Fn() + Send + Sync + 'static) {
        if let Ok(mut slot) = self.inner.on_silent_redis.lock() {
            *slot = Some(Arc::new(report));
        }
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

        if let Some(mut conn) = self.redis().await {
            let answer = tokio::time::timeout(
                REDIS_TIMEOUT,
                redis_check(&mut conn, &key, limit, window_secs),
            )
            .await;
            match answer {
                Ok(Ok(allowed)) => {
                    if allowed {
                        return Ok(());
                    }
                    return Err(AppError::rate_limited_after(window_secs));
                }
                failed => {
                    // Human: Degrade to in-process windows — do not fail open under Redis outage.
                    // Agent: FALLBACK memory_check on Redis error or timeout; still enforces
                    // per-process budgets, and skips Redis for REDIS_BACKOFF.
                    // The key holds an IP, a user id or a username hash: log the scope only.
                    let silent = failed.is_err();
                    let error = match failed {
                        Ok(Err(err)) => err.to_string(),
                        _ => format!("no answer in {} ms", REDIS_TIMEOUT.as_millis()),
                    };
                    tracing::warn!(
                        error,
                        scope,
                        "rate limit redis failed; falling back to in-process window"
                    );
                    self.redis_failed();
                    if silent {
                        self.report_silent_redis();
                    }
                }
            }
        }

        memory_check(&self.inner.memory, &key, limit, window).await
    }

    /// The Redis connection, unless there is none or it failed within [`REDIS_BACKOFF`].
    async fn redis(&self) -> Option<ConnectionManager> {
        let skipping = self
            .inner
            .redis_down_until
            .lock()
            .ok()
            .and_then(|until| *until)
            .is_some_and(|until| Instant::now() < until);
        if skipping {
            return None;
        }
        self.inner.redis.read().await.clone()
    }

    fn redis_failed(&self) {
        if let Ok(mut until) = self.inner.redis_down_until.lock() {
            *until = Some(Instant::now() + REDIS_BACKOFF);
        }
    }

    fn report_silent_redis(&self) {
        let report = self
            .inner
            .on_silent_redis
            .lock()
            .ok()
            .and_then(|slot| slot.clone());
        if let Some(report) = report {
            report();
        }
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
    memory: &RwLock<MemoryState>,
    key: &str,
    limit: u64,
    window: Duration,
) -> Result<(), AppError> {
    let now = Instant::now();
    let mut state = memory.write().await;
    if now >= state.next_sweep {
        state.windows.retain(|_, w| now < w.reset_at);
        state.next_sweep = now + MEMORY_SWEEP_EVERY;
    }
    let entry = state
        .windows
        .entry(key.to_string())
        .or_insert_with(|| MemoryWindow {
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

/// Best-effort client IP for limit keys (proxy-aware when trusted).
///
/// When `trust_forwarded` is true (API behind a trusted reverse proxy), prefer the
/// first `X-Forwarded-For` hop, then `X-Real-IP`. Otherwise ignore those headers
/// so clients cannot spoof rate-limit keys.
pub fn client_ip(headers: &HeaderMap, trust_forwarded: bool) -> String {
    if trust_forwarded {
        if let Some(xff) = headers
            .get("x-forwarded-for")
            .and_then(|value| value.to_str().ok())
            && let Some(first) = xff.split(',').next()
        {
            let trimmed = first.trim();
            if !trimmed.is_empty() {
                return trimmed.to_string();
            }
        }

        if let Some(real) = headers
            .get("x-real-ip")
            .and_then(|value| value.to_str().ok())
        {
            let trimmed = real.trim();
            if !trimmed.is_empty() {
                return trimmed.to_string();
            }
        }
    }

    "unknown".into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn finished_windows_are_dropped_at_the_next_sweep() {
        let memory = RwLock::new(MemoryState::new());
        memory_check(&memory, "rl:test:203.0.113.7", 5, Duration::from_millis(1))
            .await
            .expect("allowed");
        tokio::time::sleep(Duration::from_millis(5)).await;
        memory.write().await.next_sweep = Instant::now();
        memory_check(&memory, "rl:test:other", 5, Duration::from_secs(60))
            .await
            .expect("allowed");
        let state = memory.read().await;
        assert!(!state.windows.contains_key("rl:test:203.0.113.7"));
        assert!(state.windows.contains_key("rl:test:other"));
    }

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

    /// A Redis that answers the connection's setup and then goes silent, as one does when its
    /// host drops off the network: the limiter must fall back to its own window within
    /// [`REDIS_TIMEOUT`], then skip Redis for [`REDIS_BACKOFF`].
    #[tokio::test]
    async fn a_silent_redis_falls_back_instead_of_hanging() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind");
        let address = listener.local_addr().expect("address");
        tokio::spawn(async move {
            let Ok((mut socket, _)) = listener.accept().await else {
                return;
            };
            let mut buffer = vec![0u8; 4096];
            let mut silent = false;
            loop {
                let Ok(read) = socket.read(&mut buffer).await else {
                    return;
                };
                if read == 0 {
                    return;
                }
                let text = String::from_utf8_lossy(&buffer[..read]).to_ascii_uppercase();
                silent |= text.contains("INCR");
                if !silent {
                    let commands = text.lines().filter(|line| line.starts_with('*')).count();
                    let _ = socket
                        .write_all("+OK\r\n".repeat(commands.max(1)).as_bytes())
                        .await;
                }
            }
        });
        let client = redis::Client::open(format!("redis://{address}")).expect("client");
        let manager = ConnectionManager::new(client).await.expect("manager");
        let rl = RateLimiter::new();
        rl.set_redis(manager).await;

        let started = Instant::now();
        rl.check("test", "silent", 1, Duration::from_secs(60))
            .await
            .expect("first hit passes in-process");
        assert!(started.elapsed() < REDIS_TIMEOUT + Duration::from_millis(500));
        // Within the backoff Redis isn't waited on, and the in-process window still enforces.
        let started = Instant::now();
        assert!(
            rl.check("test", "silent", 1, Duration::from_secs(60))
                .await
                .is_err()
        );
        assert!(started.elapsed() < Duration::from_millis(100));
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
    fn client_ip_unknown_when_missing() {
        assert_eq!(client_ip(&HeaderMap::new(), true), "unknown");
    }
}
