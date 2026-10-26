//! In-process sign-in failures: 10 per 15 minutes per client address and operator name.
//! One console container is the whole deployment, so a shared store is not required.

use std::collections::HashMap;
use std::convert::Infallible;
use std::net::{IpAddr, SocketAddr};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use axum::extract::{ConnectInfo, FromRequestParts};
use axum::http::request::Parts;

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
        map.get(key)
            .filter(|hits| hits.len() >= MAX_FAILURES)
            .map(|hits| wait(hits))
    }

    pub fn record_failure(&self, key: &str) {
        let mut map = self.lock();
        prune(&mut map, key);
        if map.len() >= MAX_KEYS && !map.contains_key(key) {
            make_room(&mut map);
        }
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

/// A full table drops its finished windows, then the key failed longest ago. Refusing new keys
/// instead would let 4096 made-up names lock every operator out.
fn make_room(map: &mut HashMap<String, Vec<Instant>>) {
    map.retain(|_, hits| {
        hits.retain(|at| at.elapsed() < WINDOW);
        !hits.is_empty()
    });
    if map.len() < MAX_KEYS {
        return;
    }
    let stalest = map
        .iter()
        .min_by_key(|(_, hits)| hits.iter().max().copied())
        .map(|(key, _)| key.clone());
    if let Some(key) = stalest {
        map.remove(&key);
    }
}

fn wait(hits: &[Instant]) -> u64 {
    let oldest = hits.iter().copied().min().unwrap_or_else(Instant::now);
    WINDOW.saturating_sub(oldest.elapsed()).as_secs().max(1)
}

/// The address sign-in failures are counted against, so nobody elsewhere can lock an operator
/// out: nginx's `X-Real-IP` when the connection comes from a proxy on a loopback or private
/// address, else the peer itself. Held in memory for the limiter's window, never logged.
pub struct ClientAddr(pub String);

impl<S: Send + Sync> FromRequestParts<S> for ClientAddr {
    type Rejection = Infallible;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        let peer = ConnectInfo::<SocketAddr>::from_request_parts(parts, state)
            .await
            .ok()
            .map(|ConnectInfo(addr)| addr.ip().to_canonical());
        let forwarded = peer.filter(|ip| is_proxy_address(*ip)).and_then(|_| {
            parts
                .headers
                .get("x-real-ip")
                .and_then(|value| value.to_str().ok())
                .and_then(|value| value.trim().parse::<IpAddr>().ok())
        });
        Ok(Self(
            forwarded
                .or(peer)
                .map_or_else(|| "unknown".to_owned(), |ip| ip.to_string()),
        ))
    }
}

fn is_proxy_address(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback() || v4.is_private(),
        IpAddr::V6(v6) => v6.is_loopback() || v6.is_unique_local(),
    }
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

    #[test]
    fn a_full_table_still_counts_a_new_key() {
        let limiter = RateLimiter::new();
        for n in 0..MAX_KEYS {
            limiter.record_failure(&format!("203.0.113.9 made-up-{n}"));
        }
        assert!(limiter.retry_after("198.51.100.4 operator").is_none());
        for _ in 0..MAX_FAILURES {
            limiter.record_failure("198.51.100.4 operator");
        }
        assert!(limiter.retry_after("198.51.100.4 operator").is_some());
        assert!(limiter.lock().len() <= MAX_KEYS);
    }
}
