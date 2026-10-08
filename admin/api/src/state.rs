//! Shared process state: the database pool, the encryption key, and the sign-in limiter.

use std::sync::Arc;

use sqlx::PgPool;

use crate::limit::RateLimiter;
use crate::password;

#[derive(Clone)]
pub struct AppState {
    pool: Option<PgPool>,
    key: Option<[u8; 32]>,
    limiter: Arc<RateLimiter>,
}

impl AppState {
    pub fn disconnected() -> Self {
        Self {
            pool: None,
            key: None,
            limiter: Arc::new(RateLimiter::new()),
        }
    }

    pub fn connected(pool: PgPool, key: [u8; 32]) -> Self {
        password::warm_dummy();
        Self {
            pool: Some(pool),
            key: Some(key),
            limiter: Arc::new(RateLimiter::new()),
        }
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
