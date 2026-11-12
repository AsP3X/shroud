//! Fixed-window rate limits for abuse-prone API surfaces.
//!
//! Budgets follow `docs/server-plan.md` (`rl:{scope}:{id}` keys).
//! Uses Redis when configured; otherwise an in-process window (single-instance).
//! Existing integration tests use [`RateLimiter::disabled`] so concurrent suites
//! are not flaky; unit tests and production use an enabled limiter.

use std::collections::HashMap;
use std::convert::Infallible;
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::{Arc, PoisonError};
use std::time::{Duration, Instant};

use axum::extract::{ConnectInfo, FromRequestParts};
use axum::http::HeaderMap;
use axum::http::request::Parts;
use redis::AsyncCommands;
use redis::aio::ConnectionManager;
use tokio::sync::RwLock;

use crate::error::AppError;
use crate::state::AppState;

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
    /// Whole-chat deletes (`DELETE /conversations/{peer}`): one chat at a time, by hand. Each
    /// one publishes to every device of the caller and, for `everyone`, of the peer.
    pub const CONVERSATION_DELETE_USER: (u64, Duration) = (30, Duration::from_secs(60));
    /// Placing calls.
    pub const CALL_USER: (u64, Duration) = (30, Duration::from_secs(60));
    /// A call's signals and heartbeats: an offer, an answer, batches of ICE candidates, and a
    /// heartbeat every 10 s from each device, with room for ICE restarts.
    pub const CALL_SIGNAL_USER: (u64, Duration) = (600, Duration::from_secs(60));
    /// `GET /calls/ice-servers`: a device asks once per call it places or answers, and each
    /// answer mints a TURN login. Twice the call budget.
    pub const ICE_SERVERS_USER: (u64, Duration) = (60, Duration::from_secs(60));
    /// Password change and account delete (expensive / sensitive).
    pub const AUTH_SENSITIVE_USER: (u64, Duration) = (5, Duration::from_secs(3600));
    /// Device removals, each with a password check: removing every other device at the
    /// limit takes four, which leaves room for a few typos.
    pub const DEVICE_REMOVE_USER: (u64, Duration) = (10, Duration::from_secs(3600));
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

/// Where the limiter keeps its counts (see [`RateLimiter::store`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Store {
    Redis,
    /// `REDIS_URL` is set but Redis doesn't answer: each process counts on its own meanwhile.
    RedisDown,
    /// No Redis: counted in this process.
    Process,
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

    /// Where counts are kept, for the console's rate-limit check: `None` when the limiter is off.
    ///
    /// Agent: A PING that gets no answer is reported like a silent check
    /// ([`Self::on_silent_redis`]), so the console's check also gets the connection replaced.
    /// An error isn't: the connection manager reconnects on I/O errors itself.
    pub(crate) async fn store(&self) -> Option<Store> {
        if !self.inner.enabled {
            return None;
        }
        let Some(mut conn) = self.inner.redis.read().await.clone() else {
            return Some(Store::Process);
        };
        let ping = redis::cmd("PING");
        let answered =
            tokio::time::timeout(REDIS_TIMEOUT, ping.query_async::<String>(&mut conn)).await;
        Some(match answered {
            Ok(Ok(_)) => Store::Redis,
            failed => {
                self.redis_failed();
                if failed.is_err() {
                    self.report_silent_redis();
                }
                Store::RedisDown
            }
        })
    }

    /// Drops one counter at once, wherever it is kept: the rate-limit check's own keys.
    pub(crate) async fn forget(&self, scope: &str, id: &str) {
        let key = format!("rl:{scope}:{id}");
        if let Some(mut conn) = self.redis().await {
            let _ = tokio::time::timeout(REDIS_TIMEOUT, conn.del::<_, ()>(&key)).await;
        }
        self.inner.memory.write().await.windows.remove(&key);
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
/// Human: The forwarded headers count only when `trust.enabled` is on and the connection
/// itself comes from a proxy the deployment names (see [`ForwardedTrust`]). A private address
/// alone is no proxy: a published port can be reached from the LAN, and Docker Desktop, rootless
/// Docker and the IPv6 userland proxy hand every client to the API from the bridge's gateway
/// (security audit S3). So a client never names its own key. From such a proxy, `X-Real-IP` wins
/// (our nginx and NPM overwrite it), then the right-most `X-Forwarded-For` hop: the one the
/// proxy appended. Every hop left of it is whatever the client sent.
/// Agent: else the peer address; `"unknown"` only when the server runs without connect info.
/// An IPv6 client is keyed on its /64 (see [`budget_key`]).
pub fn client_ip(headers: &HeaderMap, peer: Option<IpAddr>, trust: &ForwardedTrust) -> String {
    let peer = peer.map(|ip| ip.to_canonical());
    let forwarded = if peer.is_some_and(|ip| trust.trusts(ip)) {
        forwarded_client(headers)
    } else {
        None
    };
    forwarded
        .or(peer)
        .map_or_else(|| "unknown".into(), budget_key)
}

/// Human: A provider hands one subscriber a whole IPv6 /64 and the host picks the low 64 bits
/// freely, so a /128 key would give one client 2^64 budgets. The /64 is the smallest block that
/// is one client; IPv4 stays the single address.
fn budget_key(ip: IpAddr) -> String {
    match ip {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => {
            let network = Ipv6Addr::from(u128::from(v6) & (u128::MAX << 64));
            format!("{network}/64")
        }
    }
}

/// Which peers may name the client in `X-Real-IP` / `X-Forwarded-For` (see [`client_ip`]).
#[derive(Clone, Debug, Default)]
pub struct ForwardedTrust {
    /// `TRUST_FORWARDED_HEADERS`. Off, no peer is a proxy.
    pub enabled: bool,
    /// The addresses and ranges in `TRUSTED_PROXIES`, and with `TRUST_PROXY_NETWORKS` the
    /// networks this container sits on.
    pub proxies: Vec<IpRange>,
    /// The host names in `TRUSTED_PROXIES`, such as the Compose service `web`.
    pub hosts: Vec<String>,
    /// The networks this container is on: where a name without a dot may resolve to.
    pub local_networks: Vec<IpRange>,
    /// What [`Self::resolve_hosts`] last found for `hosts`, shared by every clone.
    resolved: Arc<std::sync::RwLock<Vec<IpAddr>>>,
}

/// Human: A recreated container comes back on another address, and until the next lookup its
/// requests count as its own, so every client behind it shares one budget. Docker's DNS is
/// local, so a lookup this often costs nothing.
pub const PROXY_RESOLVE_INTERVAL: Duration = Duration::from_secs(2);

impl ForwardedTrust {
    pub fn new(enabled: bool, proxies: Vec<IpRange>, hosts: Vec<String>) -> Self {
        Self {
            enabled,
            proxies,
            hosts,
            local_networks: Vec::new(),
            resolved: Arc::default(),
        }
    }

    pub fn with_local_networks(mut self, networks: Vec<IpRange>) -> Self {
        self.local_networks = networks;
        self
    }

    /// Human: A name without a dot is a Compose service or container name, so it counts only
    /// on loopback or a network this container is on. While that container does not exist,
    /// Docker's DNS hands the name to the host's resolvers and their search domains, and a LAN
    /// host of the same name must not become a proxy. A dotted name (a CDN, a proxy on another
    /// host) counts wherever it points.
    fn accepts(&self, host: &str, address: IpAddr) -> bool {
        host.contains('.')
            || address.is_loopback()
            || self
                .local_networks
                .iter()
                .any(|network| network.contains(address))
    }

    fn trusts(&self, peer: IpAddr) -> bool {
        self.enabled
            && (self.proxies.iter().any(|range| range.contains(peer))
                || self
                    .resolved
                    .read()
                    .unwrap_or_else(PoisonError::into_inner)
                    .contains(&peer))
    }

    /// Looks `hosts` up again. A host that does not resolve (its container is stopped) is
    /// trusted on no address until it does; see [`Self::accepts`] for the answers kept.
    pub async fn resolve_hosts(&self) {
        let mut found = Vec::new();
        for host in &self.hosts {
            match tokio::net::lookup_host((host.as_str(), 0)).await {
                Ok(addresses) => found.extend(
                    addresses
                        .map(|address| address.ip().to_canonical())
                        .filter(|&address| self.accepts(host, address)),
                ),
                Err(err) => tracing::debug!(%host, %err, "trusted proxy did not resolve"),
            }
        }
        *self
            .resolved
            .write()
            .unwrap_or_else(PoisonError::into_inner) = found;
    }

    /// Resolves `hosts` now and then every [`PROXY_RESOLVE_INTERVAL`], for as long as the
    /// process runs. Nothing to do while forwarded headers are off or no host is named.
    pub fn spawn_host_resolver(&self) {
        if !self.enabled || self.hosts.is_empty() {
            return;
        }
        let trust = self.clone();
        tokio::spawn(async move {
            let mut every = tokio::time::interval(PROXY_RESOLVE_INTERVAL);
            loop {
                every.tick().await;
                trust.resolve_hosts().await;
            }
        });
    }
}

/// An address range in CIDR form (`203.0.113.0/24`, `2001:db8::/32`) or a single address.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct IpRange {
    network: IpAddr,
    prefix: u8,
}

impl IpRange {
    pub fn parse(value: &str) -> Option<Self> {
        let (address, prefix) = match value.trim().split_once('/') {
            Some((address, prefix)) => (address, Some(prefix.parse::<u8>().ok()?)),
            None => (value.trim(), None),
        };
        let network = address.parse::<IpAddr>().ok()?.to_canonical();
        let bits = if network.is_ipv4() { 32 } else { 128 };
        let prefix = prefix.unwrap_or(bits);
        (prefix <= bits).then_some(Self { network, prefix })
    }

    pub fn contains(&self, ip: IpAddr) -> bool {
        match (self.network, ip.to_canonical()) {
            (IpAddr::V4(network), IpAddr::V4(ip)) => {
                let mask = u32::MAX
                    .checked_shl(32 - u32::from(self.prefix))
                    .unwrap_or(0);
                u32::from(network) & mask == u32::from(ip) & mask
            }
            (IpAddr::V6(network), IpAddr::V6(ip)) => {
                let mask = u128::MAX
                    .checked_shl(128 - u32::from(self.prefix))
                    .unwrap_or(0);
                u128::from(network) & mask == u128::from(ip) & mask
            }
            _ => false,
        }
    }
}

/// The client address a trusted proxy put in the request headers.
fn forwarded_client(headers: &HeaderMap) -> Option<IpAddr> {
    let header = |name: &str| headers.get(name).and_then(|value| value.to_str().ok());
    header("x-real-ip").and_then(parse_ip).or_else(|| {
        header("x-forwarded-for")
            .and_then(|value| value.rsplit(',').next())
            .and_then(parse_ip)
    })
}

fn parse_ip(value: &str) -> Option<IpAddr> {
    value
        .trim()
        .parse::<IpAddr>()
        .ok()
        .map(|ip| ip.to_canonical())
}

/// The networks this host or container is attached to, from Linux's `/proc/net/route` and
/// `/proc/net/ipv6_route` (`TRUST_PROXY_NETWORKS`).
///
/// Human: Only the directly connected routes: no gateway, not the loopback interface, not
/// IPv6 link-local or multicast. Behind Nginx Proxy Manager the proxy's address is not known in
/// advance, but it is on `proxy-network`, one of these.
/// Agent: `/proc/net/route` prints each IPv4 address as the host-order value of its
/// network-order bytes, so `to_ne_bytes` gives the bytes back.
pub fn connected_networks(route: &str, ipv6_route: &str) -> Vec<IpRange> {
    let v4 = route.lines().skip(1).filter_map(|line| {
        let fields: Vec<&str> = line.split_whitespace().collect();
        let (iface, destination, gateway, mask) = (
            *fields.first()?,
            *fields.get(1)?,
            *fields.get(2)?,
            *fields.get(7)?,
        );
        let hex = |value: &str| u32::from_str_radix(value, 16).ok();
        let (destination, gateway, mask) = (hex(destination)?, hex(gateway)?, hex(mask)?);
        (iface != "lo" && gateway == 0 && destination != 0).then(|| IpRange {
            network: IpAddr::from(destination.to_ne_bytes()),
            prefix: mask.count_ones() as u8,
        })
    });
    let v6 = ipv6_route.lines().filter_map(|line| {
        let fields: Vec<&str> = line.split_whitespace().collect();
        let (destination, prefix, next_hop, iface) = (
            *fields.first()?,
            *fields.get(1)?,
            *fields.get(4)?,
            *fields.get(9)?,
        );
        let network = Ipv6Addr::from(u128::from_str_radix(destination, 16).ok()?);
        let prefix = u8::from_str_radix(prefix, 16).ok()?;
        let direct = u128::from_str_radix(next_hop, 16).ok()? == 0;
        let scoped = network.is_unicast_link_local() || network.is_multicast();
        (iface != "lo" && direct && !scoped && (1..128).contains(&prefix)).then_some(IpRange {
            network: IpAddr::V6(network),
            prefix,
        })
    });
    let mut networks: Vec<IpRange> = v4.chain(v6).collect();
    networks.dedup();
    networks
}

/// The client IP of a request, for its IP budgets (see [`client_ip`]).
pub struct ClientIp(pub String);

impl FromRequestParts<AppState> for ClientIp {
    type Rejection = Infallible;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let peer = ConnectInfo::<SocketAddr>::from_request_parts(parts, state)
            .await
            .ok()
            .map(|ConnectInfo(addr)| addr.ip());
        Ok(Self(client_ip(
            &parts.headers,
            peer,
            &state.forwarded_trust,
        )))
    }
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
        assert_eq!(rl.store().await, Some(Store::RedisDown));
    }

    /// A Redis that answers the connection's setup with OK and every PING with `ping`, or
    /// never when it is `None`.
    async fn redis_answering_ping(ping: Option<&'static str>) -> ConnectionManager {
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
            loop {
                let Ok(read) = socket.read(&mut buffer).await else {
                    return;
                };
                if read == 0 {
                    return;
                }
                let text = String::from_utf8_lossy(&buffer[..read]).to_ascii_uppercase();
                let reply = if text.contains("PING") {
                    let Some(ping) = ping else {
                        continue;
                    };
                    ping.to_string()
                } else {
                    let commands = text.lines().filter(|line| line.starts_with('*')).count();
                    "+OK\r\n".repeat(commands.max(1))
                };
                let _ = socket.write_all(reply.as_bytes()).await;
            }
        });
        let client = redis::Client::open(format!("redis://{address}")).expect("client");
        ConnectionManager::new(client).await.expect("manager")
    }

    /// The console's check reports a PING that gets no answer, so the connection is replaced,
    /// but not one Redis answered with an error.
    #[tokio::test]
    async fn store_reports_a_silent_redis_only() {
        for (ping, reported) in [(None, 1), (Some("-ERR busy\r\n"), 0)] {
            let rl = RateLimiter::new();
            rl.set_redis(redis_answering_ping(ping).await).await;
            let reports = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            let counter = reports.clone();
            rl.on_silent_redis(move || {
                counter.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            });

            assert_eq!(rl.store().await, Some(Store::RedisDown), "{ping:?}");
            assert_eq!(
                reports.load(std::sync::atomic::Ordering::SeqCst),
                reported,
                "{ping:?}"
            );
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

    const DOCKER_PROXY: Option<IpAddr> = Some(IpAddr::V4(std::net::Ipv4Addr::new(172, 18, 0, 5)));
    const PUBLIC_PEER: Option<IpAddr> = Some(IpAddr::V4(std::net::Ipv4Addr::new(198, 51, 100, 7)));
    /// The web container, named in `TRUSTED_PROXIES`.
    fn trusted() -> ForwardedTrust {
        ForwardedTrust::new(
            true,
            vec![IpRange::parse("172.18.0.5").unwrap()],
            Vec::new(),
        )
    }

    #[test]
    fn client_ip_prefers_real_ip_from_a_trusted_proxy() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "1.2.3.4, 10.0.0.1".parse().unwrap());
        headers.insert("x-real-ip", "9.9.9.9".parse().unwrap());
        assert_eq!(client_ip(&headers, DOCKER_PROXY, &trusted()), "9.9.9.9");
    }

    #[test]
    fn client_ip_takes_the_hop_the_proxy_appended() {
        // The client sent `1.2.3.4`; the proxy appended the address it saw.
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "1.2.3.4, 8.8.8.8".parse().unwrap());
        assert_eq!(client_ip(&headers, DOCKER_PROXY, &trusted()), "8.8.8.8");
    }

    #[test]
    fn client_ip_ignores_forwarded_when_untrusted() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "1.2.3.4".parse().unwrap());
        headers.insert("x-real-ip", "1.2.3.4".parse().unwrap());
        assert_eq!(
            client_ip(&headers, DOCKER_PROXY, &ForwardedTrust::default()),
            "172.18.0.5"
        );
    }

    #[test]
    fn client_ip_ignores_forwarded_from_a_public_peer() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "1.2.3.4".parse().unwrap());
        headers.insert("x-real-ip", "1.2.3.4".parse().unwrap());
        assert_eq!(client_ip(&headers, PUBLIC_PEER, &trusted()), "198.51.100.7");
    }

    #[test]
    fn client_ip_ignores_headers_that_are_not_addresses() {
        let mut headers = HeaderMap::new();
        headers.insert("x-real-ip", "not-an-ip".parse().unwrap());
        headers.insert("x-forwarded-for", "8.8.8.8, junk".parse().unwrap());
        assert_eq!(client_ip(&headers, DOCKER_PROXY, &trusted()), "172.18.0.5");
    }

    #[test]
    fn client_ip_reads_a_mapped_peer_as_ipv4() {
        let mapped = "::ffff:172.18.0.5".parse::<IpAddr>().ok();
        let mut headers = HeaderMap::new();
        headers.insert("x-real-ip", "8.8.8.8".parse().unwrap());
        assert_eq!(client_ip(&headers, mapped, &trusted()), "8.8.8.8");
        assert_eq!(
            client_ip(&HeaderMap::new(), mapped, &trusted()),
            "172.18.0.5"
        );
        let other = "::ffff:10.1.2.3".parse::<IpAddr>().ok();
        assert_eq!(client_ip(&headers, other, &trusted()), "10.1.2.3");
    }

    #[test]
    fn client_ip_keys_ipv6_on_its_64_prefix() {
        let key = |value: &str| client_ip(&HeaderMap::new(), value.parse().ok(), &trusted());
        assert_eq!(key("2001:db8::1"), key("2001:db8::2"));
        assert_ne!(key("2001:db8::1"), key("2001:db8:0:1::1"));
        let mut headers = HeaderMap::new();
        headers.insert("x-real-ip", "2001:db8::ffff".parse().unwrap());
        assert_eq!(
            client_ip(&headers, DOCKER_PROXY, &trusted()),
            key("2001:db8::1")
        );
        assert_eq!(key("::ffff:203.0.113.9"), "203.0.113.9");
    }

    #[test]
    fn client_ip_unknown_without_a_peer() {
        let mut headers = HeaderMap::new();
        headers.insert("x-real-ip", "8.8.8.8".parse().unwrap());
        assert_eq!(client_ip(&headers, None, &trusted()), "unknown");
    }

    #[test]
    fn client_ip_trusts_a_listed_public_proxy() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "1.2.3.4, 8.8.8.8".parse().unwrap());
        let listed = |range: &str, enabled: bool| {
            ForwardedTrust::new(enabled, vec![IpRange::parse(range).unwrap()], Vec::new())
        };
        let listed_on = listed("198.51.100.0/24", true);
        assert_eq!(client_ip(&headers, PUBLIC_PEER, &listed_on), "8.8.8.8");
        let other = listed("198.51.101.0/24", true);
        assert_eq!(client_ip(&headers, PUBLIC_PEER, &other), "198.51.100.7");
        let off = listed("198.51.100.0/24", false);
        assert_eq!(client_ip(&headers, PUBLIC_PEER, &off), "198.51.100.7");
    }

    /// Security audit S3: a LAN client, or Docker's gateway handing on a published port's
    /// traffic, is on a private address but is not the proxy.
    #[test]
    fn client_ip_ignores_forwarded_from_a_private_peer_that_is_not_a_proxy() {
        let mut headers = HeaderMap::new();
        headers.insert("x-real-ip", "9.9.9.9".parse().unwrap());
        headers.insert("x-forwarded-for", "1.2.3.4".parse().unwrap());
        for peer in [
            "192.168.1.20",
            "10.0.0.7",
            "172.18.0.1",
            "127.0.0.1",
            "fd00::2",
        ] {
            let ip = peer.parse::<IpAddr>().ok();
            assert_eq!(
                client_ip(&headers, ip, &trusted()),
                budget_key(ip.unwrap()),
                "{peer}"
            );
        }
    }

    #[tokio::test]
    async fn client_ip_trusts_a_named_proxy_once_it_resolves() {
        let mut headers = HeaderMap::new();
        headers.insert("x-real-ip", "9.9.9.9".parse().unwrap());
        let loopback = "127.0.0.1".parse::<IpAddr>().ok();
        let named = ForwardedTrust::new(true, Vec::new(), vec!["localhost".into()]);
        assert_eq!(client_ip(&headers, loopback, &named), "127.0.0.1");
        named.clone().resolve_hosts().await;
        assert_eq!(client_ip(&headers, loopback, &named), "9.9.9.9");
        let gone =
            ForwardedTrust::new(true, Vec::new(), vec!["shroud-no-such-host.invalid".into()]);
        gone.resolve_hosts().await;
        assert_eq!(client_ip(&headers, loopback, &gone), "127.0.0.1");
    }

    #[test]
    fn a_name_without_a_dot_counts_only_on_this_containers_networks() {
        let ip = |value: &str| value.parse::<IpAddr>().unwrap();
        let trust = ForwardedTrust::new(true, Vec::new(), vec!["web".into(), "cdn.example".into()])
            .with_local_networks(vec![IpRange::parse("172.19.0.0/16").unwrap()]);
        assert!(trust.accepts("web", ip("172.19.0.7")));
        assert!(trust.accepts("web", ip("127.0.0.1")));
        // A LAN host called `web` (or `web.<search domain>`) while the container is gone.
        assert!(!trust.accepts("web", ip("192.168.1.30")));
        assert!(!trust.accepts("web", ip("172.20.0.4")));
        assert!(trust.accepts("cdn.example", ip("192.168.1.30")));
        let outside_docker = ForwardedTrust::new(true, Vec::new(), vec!["web".into()]);
        assert!(!outside_docker.accepts("web", ip("172.19.0.7")));
    }

    #[test]
    fn connected_networks_are_the_direct_routes_only() {
        let route = "\
Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT
eth0\t00000000\t010012AC\t0003\t0\t0\t0\t00000000\t0\t0\t0
eth0\t000012AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0
eth1\t0000A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0
eth1\t0000000A\t0100A8C0\t0003\t0\t0\t0\t000000FF\t0\t0\t0
lo\t0000007F\t00000000\t0001\t0\t0\t0\t000000FF\t0\t0\t0
";
        let ipv6_route = "\
fd00000000000000000000000000ab00 78 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000001 00000000 00000001     eth0
fe800000000000000000000000000000 40 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000001 00000000 00000001     eth0
00000000000000000000000000000000 00 00000000000000000000000000000000 00 fd00000000000000000000000000ab01 00000400 00000001 00000000 00000003     eth0
fd00000000000000000000000000ab02 80 00000000000000000000000000000000 00 00000000000000000000000000000000 00000000 00000002 00000000 80200001       lo
ff000000000000000000000000000000 08 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000003 00000000 00000001     eth0
";
        let networks = connected_networks(route, ipv6_route);
        let expected = ["172.18.0.0/16", "192.168.0.0/24", "fd00::ab00/120"]
            .map(|range| IpRange::parse(range).unwrap());
        assert_eq!(networks, expected);
        assert!(connected_networks("", "").is_empty());
    }

    #[test]
    fn ip_ranges_parse_and_match() {
        let ip = |value: &str| value.parse::<IpAddr>().unwrap();
        let v4 = IpRange::parse("203.0.113.0/24").unwrap();
        assert!(v4.contains(ip("203.0.113.200")));
        assert!(v4.contains(ip("::ffff:203.0.113.9")));
        assert!(!v4.contains(ip("203.0.114.1")));
        assert!(!v4.contains(ip("2001:db8::1")));
        let one = IpRange::parse(" 192.0.2.7 ").unwrap();
        assert!(one.contains(ip("192.0.2.7")));
        assert!(!one.contains(ip("192.0.2.8")));
        let v6 = IpRange::parse("2001:db8::/32").unwrap();
        assert!(v6.contains(ip("2001:db8:ffff::1")));
        assert!(!v6.contains(ip("2001:db9::1")));
        assert!(IpRange::parse("0.0.0.0/0").unwrap().contains(ip("8.8.8.8")));
        for bad in [
            "",
            "203.0.113.0/33",
            "2001:db8::/129",
            "example.com",
            "10.0.0.0/x",
        ] {
            assert_eq!(IpRange::parse(bad), None, "{bad}");
        }
    }
}
