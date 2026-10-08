//! Shared process state: the database pool, the encryption key, and the sign-in limiter.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use sqlx::PgPool;

use crate::limit::RateLimiter;
use crate::password;

/// Table counts. Refreshed at most once a minute (plan rule R8).
#[derive(Clone, Copy)]
pub(crate) struct Counts {
    pub accounts: i64,
    pub accounts_7d: i64,
    pub accounts_deleted: i64,
    pub devices_active_30d: i64,
    pub web_push_subscriptions: i64,
}

struct CachedCounts {
    at: Instant,
    counts: Counts,
}

#[derive(Clone)]
pub struct AppState {
    pool: Option<PgPool>,
    key: Option<[u8; 32]>,
    limiter: Arc<RateLimiter>,
    counts: Arc<Mutex<Option<CachedCounts>>>,
}

impl AppState {
    pub fn disconnected() -> Self {
        Self {
            pool: None,
            key: None,
            limiter: Arc::new(RateLimiter::new()),
            counts: Arc::new(Mutex::new(None)),
        }
    }

    pub fn connected(pool: PgPool, key: [u8; 32]) -> Self {
        password::warm_dummy();
        Self {
            pool: Some(pool),
            key: Some(key),
            limiter: Arc::new(RateLimiter::new()),
            counts: Arc::new(Mutex::new(None)),
        }
    }

    /// Counts from the last minute, if a page view already loaded them.
    pub(crate) fn cached_counts(&self) -> Option<Counts> {
        let guard = self
            .counts
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        let cached = guard.as_ref()?;
        (cached.at.elapsed() < Duration::from_secs(60)).then_some(cached.counts)
    }

    pub(crate) fn store_counts(&self, counts: Counts) {
        let mut guard = self
            .counts
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        *guard = Some(CachedCounts {
            at: Instant::now(),
            counts,
        });
    }

    pub(crate) fn pool(&self) -> Option<&PgPool> {
        self.pool.as_ref()
    }

    pub(crate) fn key(&self) -> Option<&[u8; 32]> {
        self.key.as_ref()
    }

    pub(crate) fn limiter(&self) -> Arc<RateLimiter> {
        Arc::clone(&self.limiter)
    }
}
