//! In-process sign-in limits. One console container is the whole deployment, so a shared store is
//! not required.
//!
//! - [`RateLimiter`]: 10 failures per 15 minutes per client address and operator name.
//! - [`NameCeiling`]: past a ceiling of failures with the right password for one operator name in
//!   an hour, from every address together, each further such attempt waits its turn (security
//!   audit A3).

use std::collections::HashMap;
use std::convert::Infallible;
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use axum::extract::{ConnectInfo, FromRequestParts};
use axum::http::request::Parts;

const WINDOW: Duration = Duration::from_secs(15 * 60);
const MAX_FAILURES: usize = 10;
const MAX_KEYS: usize = 4096;

/// Defaults for `ADMIN_SIGN_IN_CEILING` and `ADMIN_SIGN_IN_DELAY_SECS`.
pub const NAME_CEILING: usize = 50;
pub const NAME_DELAY: Duration = Duration::from_secs(30);
const NAME_WINDOW: Duration = Duration::from_secs(60 * 60);

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

/// Human: The per-address budget alone lets someone who knows an operator's password guess
/// authenticator codes from as many addresses as they have. Once one name has failed `ceiling`
/// times within an hour, from anywhere, its attempts start one at a time, `delay` apart: about
/// 120 an hour at 30 s, so the 330 000 guesses a code takes on average need months, not hours.
/// Only attempts that already proved the password (or a session's step-up) are counted or made
/// to wait. Someone who knows just the name can neither fill the ceiling nor take the name's
/// turns, so they cannot keep the operator out (that stays the address budget's job).
/// Agent: In memory only, keyed by [`crate::auth`]'s normalised name of a real operator, so the
/// table never holds more names than there are operators; never logged. An attempt is counted
/// when it starts (so concurrent requests can't overshoot the ceiling) and taken back if it signs
/// in.
pub struct NameCeiling {
    ceiling: usize,
    delay: Duration,
    inner: Mutex<HashMap<String, NameAttempts>>,
}

#[derive(Default)]
struct NameAttempts {
    /// Attempts started within the window that did not sign in.
    started: Vec<Instant>,
    /// Past the ceiling: the earliest the next attempt may start.
    next: Option<Instant>,
    /// This crossing is already in the audit log.
    reported: bool,
}

/// One started attempt, handed back to [`NameCeiling::signed_in`] when it succeeds.
#[derive(Clone, Copy)]
pub struct Attempt(Instant);

impl NameCeiling {
    pub fn new(ceiling: usize, delay: Duration) -> Self {
        Self {
            ceiling: ceiling.max(1),
            delay,
            inner: Mutex::default(),
        }
    }

    /// Failures for one name in an hour before its attempts wait their turn.
    pub fn ceiling(&self) -> usize {
        self.ceiling
    }

    /// Starts an attempt for `name`, or returns the seconds until one may start.
    pub fn begin(&self, name: &str) -> Result<Attempt, u64> {
        let now = Instant::now();
        let mut map = self.lock();
        prune_name(&mut map, name, self.ceiling);
        let entry = map.entry(name.to_owned()).or_default();
        if entry.started.len() >= self.ceiling {
            if let Some(next) = entry.next.filter(|next| *next > now) {
                return Err(seconds_until(next, now));
            }
            entry.next = Some(now + self.delay);
        }
        entry.started.push(now);
        Ok(Attempt(now))
    }

    /// A correct sign-in is not a failure. The name's other failures stay, so signing in does not
    /// hand whoever is guessing a fresh ceiling.
    pub fn signed_in(&self, name: &str, attempt: Attempt) {
        let mut map = self.lock();
        if let Some(entry) = map.get_mut(name)
            && let Some(index) = entry.started.iter().position(|at| *at == attempt.0)
        {
            entry.started.remove(index);
        }
    }

    /// True once per crossing: the first refusal that finds the name at the ceiling since it was
    /// last below it.
    pub fn refused(&self, name: &str) -> bool {
        let mut map = self.lock();
        let Some(entry) = map.get_mut(name) else {
            return false;
        };
        let crossed = entry.started.len() >= self.ceiling && !entry.reported;
        entry.reported |= crossed;
        crossed
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, NameAttempts>> {
        self.inner
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
    }
}

impl Default for NameCeiling {
    fn default() -> Self {
        Self::new(NAME_CEILING, NAME_DELAY)
    }
}

/// Back under the ceiling, the name starts at once again and a later crossing is reported anew.
fn prune_name(map: &mut HashMap<String, NameAttempts>, name: &str, ceiling: usize) {
    let Some(entry) = map.get_mut(name) else {
        return;
    };
    entry.started.retain(|at| at.elapsed() < NAME_WINDOW);
    if entry.started.len() < ceiling {
        entry.next = None;
        entry.reported = false;
    }
    if entry.started.is_empty() {
        map.remove(name);
    }
}

fn seconds_until(next: Instant, now: Instant) -> u64 {
    let left = next.saturating_duration_since(now);
    (left.as_secs() + u64::from(left.subsec_nanos() > 0)).max(1)
}

/// The address sign-in failures are counted against, so nobody elsewhere can lock an operator
/// out: nginx's `X-Real-IP` when the connection comes from a proxy on a loopback or private
/// address, else the peer itself. An IPv6 client counts as its /64 (see [`budget_key`]). Held in
/// memory for the limiter's window, never logged.
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
                .map_or_else(|| "unknown".to_owned(), budget_key),
        ))
    }
}

/// Human: A provider hands one subscriber a whole IPv6 /64 and the host picks the low 64 bits
/// freely, so a /128 key would give one client 2^64 sign-in budgets. Same rule as the server's
/// `rate_limit::client_ip`. A mapped address (`::ffff:a.b.c.d`) is its IPv4 address.
fn budget_key(ip: IpAddr) -> String {
    match ip.to_canonical() {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => {
            let network = Ipv6Addr::from(u128::from(v6) & (u128::MAX << 64));
            format!("{network}/64")
        }
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

    async fn client_addr(peer: &str, real_ip: Option<&str>) -> String {
        let mut request = axum::http::Request::builder()
            .extension(ConnectInfo(SocketAddr::new(peer.parse().unwrap(), 40000)));
        if let Some(real_ip) = real_ip {
            request = request.header("x-real-ip", real_ip);
        }
        let (mut parts, ()) = request.body(()).unwrap().into_parts();
        let Ok(ClientAddr(addr)) = ClientAddr::from_request_parts(&mut parts, &()).await;
        addr
    }

    #[tokio::test]
    async fn ipv6_clients_are_keyed_on_their_64_prefix() {
        assert_eq!(
            client_addr("2001:db8::1", None).await,
            client_addr("2001:db8::2", None).await
        );
        assert_ne!(
            client_addr("2001:db8::1", None).await,
            client_addr("2001:db8:0:1::1", None).await
        );
        assert_eq!(
            client_addr("172.18.0.5", Some("2001:db8::ffff")).await,
            client_addr("2001:db8::1", None).await
        );
        assert_eq!(
            client_addr("172.18.0.5", Some("::ffff:203.0.113.9")).await,
            "203.0.113.9"
        );
        assert_eq!(client_addr("203.0.113.9", None).await, "203.0.113.9");
    }

    #[test]
    fn past_the_ceiling_a_name_waits_its_turn_and_a_correct_sign_in_still_gets_in() {
        let ceiling = NameCeiling::new(3, Duration::from_millis(200));
        let mut crossings = 0;
        for _ in 0..3 {
            ceiling.begin("ada").expect("under the ceiling");
            crossings += usize::from(ceiling.refused("ada"));
        }
        assert_eq!(crossings, 1, "the crossing is reported once");
        ceiling
            .begin("ada")
            .expect("the first turn past the ceiling");
        assert!(!ceiling.refused("ada"));
        let wait = ceiling.begin("ada").err().expect("the next attempt waits");
        assert_eq!(wait, 1);
        assert!(ceiling.begin("grace").is_ok(), "other names are not slowed");

        std::thread::sleep(Duration::from_millis(250));
        let turn = ceiling.begin("ada").expect("after the delay");
        ceiling.signed_in("ada", turn);
        assert_eq!(ceiling.lock()["ada"].started.len(), 4);
        assert!(
            ceiling.begin("ada").is_err(),
            "signing in does not reset the ceiling"
        );
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
