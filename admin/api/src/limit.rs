//! In-process sign-in failures: 10 per 15 minutes per operator name.
//! One console container is the whole deployment, so a shared store is not required.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

const WINDOW: Duration = Duration::from_secs(15 * 60);
const MAX_FAILURES: usize = 10;
const MAX_KEYS: usize = 4096;

#[derive(Default)]
pub struct RateLimiter {
    inner: Mutex<HashMap<String, Vec<Instant>>>,
}

impl RateLimiter {
    pub fn new() -> Self {
        Self::default()
    }

    /// Seconds until another attempt is allowed, when this key is already at the cap.
    pub fn retry_after(&self, key: &str) -> Option<u64> {
        let mut map = self.lock();
        prune(&mut map, key);
        if let Some(hits) = map.get(key)
            && hits.len() >= MAX_FAILURES
        {
            return Some(wait(hits));
        }
        if map.len() >= MAX_KEYS && !map.contains_key(key) {
            return Some(WINDOW.as_secs());
        }
        None
    }

    pub fn record_failure(&self, key: &str) {
        let mut map = self.lock();
        if map.len() >= MAX_KEYS && !map.contains_key(key) {
            return;
        }
        prune(&mut map, key);
        map.entry(key.to_owned()).or_default().push(Instant::now());
    }

    pub fn clear(&self, key: &str) {
        self.lock().remove(key);
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, Vec<Instant>>> {
        self.inner
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
    }
}

fn prune(map: &mut HashMap<String, Vec<Instant>>, key: &str) {
    let Some(hits) = map.get_mut(key) else {
        return;
    };
    hits.retain(|at| at.elapsed() < WINDOW);
    if hits.is_empty() {
        map.remove(key);
    }
}

fn wait(hits: &[Instant]) -> u64 {
    let oldest = hits.iter().copied().min().unwrap_or_else(Instant::now);
    WINDOW.saturating_sub(oldest.elapsed()).as_secs().max(1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_eleventh_failure_waits() {
        let limiter = RateLimiter::new();
        assert!(limiter.retry_after("operator").is_none());
        for _ in 0..MAX_FAILURES {
            limiter.record_failure("operator");
        }
        let wait = limiter.retry_after("operator").unwrap();
        assert!(wait <= WINDOW.as_secs() && wait >= 1);
        limiter.clear("operator");
        assert!(limiter.retry_after("operator").is_none());
    }
}
