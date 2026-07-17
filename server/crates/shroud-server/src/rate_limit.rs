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
    pub const MEDIA_PRESIGN_USER: (u64, Duration) = (60, Duration::from_secs(60));
    pub const WS_CONNECT_IP: (u64, Duration) = (30, Duration::from_secs(60));
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
                    return Err(AppError::rate_limited());
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
        return Err(AppError::rate_limited());
    }
    Ok(())
}

/// Best-effort client IP for limit keys (proxy-aware).
///
/// Prefer first `X-Forwarded-For` hop, then `X-Real-IP`, else `"unknown"`.
pub fn client_ip(headers: &HeaderMap) -> String {
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

    "unknown".into()
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
            AppError::Api { status, code, .. } => {
                assert_eq!(status, axum::http::StatusCode::TOO_MANY_REQUESTS);
                assert_eq!(code, "RATE_LIMITED");
            }
            other => panic!("expected RATE_LIMITED, got {other:?}"),
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
    fn client_ip_prefers_forwarded_for() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "1.2.3.4, 10.0.0.1".parse().unwrap());
        headers.insert("x-real-ip", "9.9.9.9".parse().unwrap());
        assert_eq!(client_ip(&headers), "1.2.3.4");
    }

    #[test]
    fn client_ip_falls_back_to_real_ip() {
        let mut headers = HeaderMap::new();
        headers.insert("x-real-ip", "8.8.8.8".parse().unwrap());
        assert_eq!(client_ip(&headers), "8.8.8.8");
    }

    #[test]
    fn client_ip_unknown_when_missing() {
        assert_eq!(client_ip(&HeaderMap::new()), "unknown");
    }
}
