//! WebSocket fan-out: in-process hub, optional Redis multi-replica pub/sub.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use redis::AsyncCommands;
use redis::aio::ConnectionManager;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::{RwLock, mpsc, watch};
use uuid::Uuid;

/// Per-device outbound event channel (JSON text frames).
type DeviceTx = mpsc::Sender<String>;

const USER_CHANNEL_PREFIX: &str = "shroud:user:";
/// Sessions revoked on one replica, so every replica closes the sockets they opened.
const REVOKED_SESSIONS_CHANNEL: &str = "shroud:sessions:revoked";
/// A ringing call's `call.ring` event, per callee (see [`RealtimeHub::remember_ring`]).
const RING_KEY_PREFIX: &str = "shroud:ring:";
const ONLINE_KEY_PREFIX: &str = "shroud:online:";
/// Whether a connected device is in the foreground (`{connection_id}:1` or `:0`).
/// Missing means "in front": older clients never say, and they show their own notices.
const FOCUS_KEY_PREFIX: &str = "shroud:focus:";
/// Redis online hash entries older than this are treated as stale (crash without unsubscribe).
pub const ONLINE_TTL_SECS: i64 = 90;
/// Bounded outbound queue per device — drops events when full (slow-client backpressure).
const OUTBOUND_QUEUE_CAP: usize = 256;
/// Max simultaneous WebSocket connections per user on this replica.
pub const MAX_WS_PER_USER: usize = 5;
/// Longest a Redis call may take before the hub carries on with this replica's sockets only.
///
/// Human: The connection manager never gives up on its own: with Redis gone, a call waited
/// for good, and the send, presence update or revocation that made it hung with it.
const REDIS_TIMEOUT: Duration = Duration::from_millis(500);
/// After a Redis failure the hub works on this replica alone this long before Redis is tried
/// again, so an outage costs one slow call rather than one per message.
const REDIS_BACKOFF: Duration = Duration::from_secs(5);

fn online_key(user_id: Uuid) -> String {
    format!("{ONLINE_KEY_PREFIX}{user_id}")
}

fn focus_key(user_id: Uuid) -> String {
    format!("{FOCUS_KEY_PREFIX}{user_id}")
}

fn unix_now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// A device's live socket on this replica.
struct Connection {
    /// Tells this socket apart from a later one from the same device.
    id: u64,
    /// The session the socket authenticated with.
    session_id: Uuid,
    tx: DeviceTx,
    /// The app is in front and shows its own notices. A client says otherwise with `focus`;
    /// until it does, a live socket counts as in front (that is what older apps do) — unless
    /// it is a background socket.
    focused: bool,
    /// A background socket (Android's opt-in background connection): it gets every event, but
    /// while its app is not in front it counts neither as online nor as in front.
    background: bool,
    /// What this socket last wrote to the Redis online hash. Its cleanup deletes the entry
    /// only while it still holds that: a newer socket of the device, on another replica, may
    /// have written its own since.
    online_ts: Option<i64>,
}

impl Connection {
    /// Whether this socket makes its user online (presence, `last_seen`). A background socket
    /// does only while its app is in front.
    fn counts_online(&self) -> bool {
        !self.background || self.focused
    }
}

/// How a socket counts until its app says otherwise (`focus`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SocketMode {
    /// An app on screen, or one that never says: online, and in front.
    Foreground,
    /// The auth frame said `"background": true` (Android's background connection, a
    /// foreground service that keeps the socket open for messages and calls): it receives
    /// every event, but its user is not online through it and its pushes are not skipped,
    /// until its app sends `focus:true`.
    Background,
}

/// What a `focus` frame changed about whether its socket makes the user online.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OnlineChange {
    Unchanged,
    /// A background socket's app came to the front.
    CameOnline,
    /// It left again (or a socket declared itself a background socket on leaving).
    WentOffline,
}

/// HDEL the field only while it holds the value the caller wrote.
const DELETE_IF_UNCHANGED: &str = r#"
if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then
    return redis.call('HDEL', KEYS[1], ARGV[1])
end
return 0
"#;

/// HDEL a focus field only while it still belongs to this socket (`{connection_id}:`).
const DELETE_FOCUS_IF_OURS: &str = r#"
local v = redis.call('HGET', KEYS[1], ARGV[1])
if v and string.sub(v, 1, string.len(ARGV[2])) == ARGV[2] then
    return redis.call('HDEL', KEYS[1], ARGV[1])
end
return 0
"#;

/// This replica's sockets, keyed by device and indexed by user.
///
/// Human: One lock guards both maps, so a socket's entry and its user index change together.
#[derive(Default)]
struct Connections {
    by_device: HashMap<Uuid, Connection>,
    devices_by_user: HashMap<Uuid, HashSet<Uuid>>,
}

impl Connections {
    fn remove(&mut self, user_id: Uuid, device_id: Uuid) {
        self.by_device.remove(&device_id);
        if let Some(set) = self.devices_by_user.get_mut(&user_id) {
            set.remove(&device_id);
            if set.is_empty() {
                self.devices_by_user.remove(&user_id);
            }
        }
    }
}

/// A registered socket: its events, and the id to hand back to [`RealtimeHub::unsubscribe`].
#[derive(Debug)]
pub struct Subscription {
    pub id: u64,
    pub events: mpsc::Receiver<String>,
}

/// Shared connection hub keyed by device (and indexed by user).
#[derive(Default)]
pub struct RealtimeHub {
    connections: RwLock<Connections>,
    next_connection_id: AtomicU64,
    /// When set, cross-instance fan-out uses Redis pub/sub.
    redis: Arc<RedisSlot>,
    /// Rings kept while their calls ring, by callee — in Redis instead when it is set.
    rings: RwLock<HashMap<Uuid, PendingRing>>,
}

/// The hub's Redis connection, shared with the task that replaces it when it goes silent.
#[derive(Default)]
struct RedisSlot {
    manager: RwLock<Option<ConnectionManager>>,
    /// Opens the replacement (see [`RealtimeHub::set_redis_client`]).
    client: std::sync::Mutex<Option<redis::Client>>,
    /// Until when Redis is skipped after it failed or didn't answer (see [`REDIS_BACKOFF`]).
    down_until: std::sync::Mutex<Option<Instant>>,
    /// A replacement is being opened.
    replacing: AtomicBool,
    /// Each replacement, for whoever shares the connection (see
    /// [`RealtimeHub::redis_replacements`]).
    replaced: watch::Sender<Option<ConnectionManager>>,
}

impl RedisSlot {
    fn back_off(&self) {
        if let Ok(mut until) = self.down_until.lock() {
            *until = Some(Instant::now() + REDIS_BACKOFF);
        }
    }

    /// Opens a fresh connection manager in the background and swaps it in, unless one is
    /// already being opened or there is no client to open it with.
    ///
    /// Human: A connection whose peer vanished without a reset (a host off the network, a
    /// dropped NAT entry) never errors: the manager only reconnects on an I/O error, and TCP
    /// keepalive, at the OS defaults redis-rs leaves it on, takes hours to notice. Until then
    /// every call timed out and other replicas heard nothing from this one.
    /// Agent: Ends the backoff once the new manager is in, so the next call uses it at once,
    /// and announces it on `replaced`. The attempt is bounded by the manager config's retries
    /// and connection timeout.
    fn replace(self: &Arc<Self>) {
        let Some(client) = self.client.lock().ok().and_then(|client| client.clone()) else {
            return;
        };
        if self.replacing.swap(true, Ordering::AcqRel) {
            return;
        }
        let slot = self.clone();
        tokio::spawn(async move {
            match ConnectionManager::new_with_config(client, crate::redis_manager_config()).await {
                Ok(manager) => {
                    *slot.manager.write().await = Some(manager.clone());
                    if let Ok(mut until) = slot.down_until.lock() {
                        *until = None;
                    }
                    slot.replaced.send_replace(Some(manager));
                    tracing::info!("realtime redis connection replaced after it went silent");
                }
                Err(err) => tracing::warn!(
                    error = %err,
                    "realtime redis replacement failed; trying again on the next silent call"
                ),
            }
            slot.replacing.store(false, Ordering::Release);
        });
    }
}

/// A `call.ring` event kept for the callee's devices that connect while it rings.
struct PendingRing {
    payload: String,
    until: Instant,
}

impl std::fmt::Debug for RealtimeHub {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RealtimeHub")
            .field("redis_enabled", &"<async>")
            .finish_non_exhaustive()
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct RedisFanout {
    user_id: Uuid,
    except_device_id: Option<Uuid>,
    /// Set for an event meant for one device (a call's signals). Replicas from before it
    /// ignore the field and deliver to all the user's devices, which the apps filter.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    only_device_id: Option<Uuid>,
    event: Value,
}

#[derive(Debug, Serialize, Deserialize)]
struct RedisRevokedSessions {
    user_id: Uuid,
    session_ids: Vec<Uuid>,
}

impl RealtimeHub {
    pub fn new() -> Self {
        Self::default()
    }

    /// Attaches a Redis connection manager for multi-replica publish.
    pub async fn set_redis(&self, manager: ConnectionManager) {
        *self.redis.manager.write().await = Some(manager);
    }

    /// The client the Redis connection manager was opened with, so the hub can open a fresh
    /// one when its connection stops answering.
    pub fn set_redis_client(&self, client: redis::Client) {
        if let Ok(mut slot) = self.redis.client.lock() {
            *slot = Some(client);
        }
    }

    /// The connection managers the hub swaps in for one that went silent, so whatever was
    /// handed the same connection (the rate limiter) can move with it.
    ///
    /// Human: Clones of a connection manager share its connection, so a silent connection
    /// stalls every holder alike. The hub notices through its own traffic and the readiness
    /// PING the healthcheck sends every 10 s, and the others tell it
    /// ([`Self::report_silent_redis`]).
    pub fn redis_replacements(&self) -> watch::Receiver<Option<ConnectionManager>> {
        self.redis.replaced.subscribe()
    }

    /// Someone sharing the hub's Redis connection (the rate limiter) got no answer on it: the
    /// hub opens a fresh one, as after a silent call of its own.
    ///
    /// Agent: Doesn't back the hub off. If Redis was only slow, its fan-out carries on, and the
    /// replacement costs one new connection.
    pub fn report_silent_redis(&self) {
        self.redis.replace();
    }

    /// True when a Redis connection manager is attached.
    pub async fn has_redis(&self) -> bool {
        self.redis.manager.read().await.is_some()
    }

    /// PING Redis when configured. Errors if missing, the command fails or Redis doesn't
    /// answer within [`REDIS_TIMEOUT`].
    ///
    /// Agent: Asks Redis even while the hub backs off from it; a failure starts the backoff,
    /// and no answer replaces the connection.
    pub async fn ping_redis(&self) -> Result<(), String> {
        let mut conn = self
            .redis
            .manager
            .read()
            .await
            .clone()
            .ok_or_else(|| "redis not connected".to_string())?;
        let ping = redis::cmd("PING");
        let answer = tokio::time::timeout(REDIS_TIMEOUT, ping.query_async::<String>(&mut conn));
        let (error, silent) = match answer.await {
            Ok(Ok(_)) => return Ok(()),
            Ok(Err(err)) => (err.to_string(), false),
            Err(_) => (
                format!("no answer in {} ms", REDIS_TIMEOUT.as_millis()),
                true,
            ),
        };
        self.redis.back_off();
        if silent {
            self.redis.replace();
        }
        Err(error)
    }

    /// The Redis connection, unless there is none or it failed within [`REDIS_BACKOFF`].
    async fn redis(&self) -> Option<ConnectionManager> {
        let skipping = self
            .redis
            .down_until
            .lock()
            .ok()
            .and_then(|until| *until)
            .is_some_and(|until| Instant::now() < until);
        if skipping {
            return None;
        }
        self.redis.manager.read().await.clone()
    }

    /// Runs one Redis call, bounded by [`REDIS_TIMEOUT`].
    ///
    /// Human: `None` when there is no Redis, the hub is backing off from it, or the call failed
    /// or didn't answer. Callers then do what a single replica without Redis does: deliver to
    /// and count the sockets it holds itself.
    /// Agent: A failure starts [`REDIS_BACKOFF`]; no answer also replaces the connection
    /// ([`RedisSlot::replace`]). An error doesn't: the manager reconnects on I/O errors itself.
    /// LOGS `op` only: keys and channels hold user and device ids.
    async fn redis_call<T, F, Fut>(&self, op: &'static str, call: F) -> Option<T>
    where
        F: FnOnce(ConnectionManager) -> Fut,
        Fut: Future<Output = redis::RedisResult<T>>,
    {
        let conn = self.redis().await?;
        let (error, silent) = match tokio::time::timeout(REDIS_TIMEOUT, call(conn)).await {
            Ok(Ok(value)) => return Some(value),
            Ok(Err(err)) => (err.to_string(), false),
            Err(_) => (
                format!("no answer in {} ms", REDIS_TIMEOUT.as_millis()),
                true,
            ),
        };
        tracing::warn!(
            error,
            op,
            "realtime redis failed; carrying on with this replica's sockets"
        );
        // Backed off first: a replacement that lands ends the backoff.
        self.redis.back_off();
        if silent {
            self.redis.replace();
        }
        None
    }

    /// Registers a device connection; returns the receiver for WS write loop.
    ///
    /// Human: Caps concurrent sockets per user and uses a bounded queue so slow clients
    /// cannot grow memory without bound. A device's new socket replaces its old one, whose
    /// loop ends when its sender drops.
    /// Agent: RETURNS Err when local connections for user >= MAX_WS_PER_USER (unless reconnect).
    pub async fn subscribe(
        self: &Arc<Self>,
        user_id: Uuid,
        device_id: Uuid,
        session_id: Uuid,
    ) -> Result<Subscription, &'static str> {
        self.subscribe_with(user_id, device_id, session_id, SocketMode::Foreground)
            .await
    }

    /// [`Self::subscribe`] for a socket that starts as `mode` says.
    ///
    /// Human: A background socket counts against the per-user cap like any other, and gets
    /// every event; it just leaves no trace in presence (no online entry, no focus in front).
    pub async fn subscribe_with(
        self: &Arc<Self>,
        user_id: Uuid,
        device_id: Uuid,
        session_id: Uuid,
        mode: SocketMode,
    ) -> Result<Subscription, &'static str> {
        let (tx, rx) = mpsc::channel(OUTBOUND_QUEUE_CAP);
        let id = self.next_connection_id.fetch_add(1, Ordering::Relaxed);
        let background = mode == SocketMode::Background;
        let replaced_online_ts = {
            let mut connections = self.connections.write().await;
            if let Some(set) = connections.devices_by_user.get(&user_id)
                && set.len() >= MAX_WS_PER_USER
                && !set.contains(&device_id)
            {
                return Err("too many websocket connections for this user");
            }
            let replaced = connections.by_device.insert(
                device_id,
                Connection {
                    id,
                    session_id,
                    tx,
                    focused: !background,
                    background,
                    online_ts: None,
                },
            );
            connections
                .devices_by_user
                .entry(user_id)
                .or_default()
                .insert(device_id);
            replaced.and_then(|connection| connection.online_ts)
        };
        if background {
            // The device's previous socket may have left it online in Redis: no longer.
            self.mark_offline(user_id, device_id, replaced_online_ts)
                .await;
            self.write_focus(user_id, device_id, id, false).await;
        } else {
            self.mark_online(user_id, device_id, id).await;
        }
        Ok(Subscription { id, events: rx })
    }

    /// Removes a device connection on disconnect.
    ///
    /// Human: Only a socket that still holds its device's entry clears it. After a reconnect
    /// the entry is the newer socket's, and after a revocation it is already gone; clearing
    /// it unconditionally cut the newer socket off.
    /// Agent: NO-OP unless the device's entry is `connection_id`.
    pub async fn unsubscribe(&self, user_id: Uuid, device_id: Uuid, connection_id: u64) {
        let online_ts = {
            let mut connections = self.connections.write().await;
            let Some(connection) = connections
                .by_device
                .get(&device_id)
                .filter(|connection| connection.id == connection_id)
            else {
                return;
            };
            let online_ts = connection.online_ts;
            connections.remove(user_id, device_id);
            online_ts
        };
        self.mark_offline(user_id, device_id, online_ts).await;
        self.clear_focus(user_id, device_id, connection_id).await;
    }

    /// The app on this socket came to the front, or left it.
    ///
    /// Human: A phone the OS just suspended, and a browser tab that is open but not in front,
    /// still have a socket. Pushes are skipped for a device that is actually in front; these
    /// are not, so a call and a message still reach them.
    /// Agent: NO-OP unless `connection_id` still owns the device. Also written to Redis so
    /// another replica's push decision sees it. RETURNS whether a background socket's user
    /// came online or went offline through it (the caller announces presence).
    pub async fn set_focus(
        &self,
        user_id: Uuid,
        device_id: Uuid,
        connection_id: u64,
        focused: bool,
    ) -> OnlineChange {
        self.update_focus(user_id, device_id, connection_id, focused, None)
            .await
    }

    /// [`Self::set_focus`], and with `background` set, whether the socket is a background
    /// socket from now on (`{"type":"focus","focused":false,"background":true}`: a socket the
    /// app keeps open for its background connection when it leaves the screen).
    ///
    /// Human: A background socket in front counts like any socket; once its app leaves again
    /// it is back to background accounting — offline, not in front.
    /// Agent: Redis online entry written when it comes online, cleared when it goes offline.
    pub async fn update_focus(
        &self,
        user_id: Uuid,
        device_id: Uuid,
        connection_id: u64,
        focused: bool,
        background: Option<bool>,
    ) -> OnlineChange {
        let (change, cleared_online_ts) = {
            let mut connections = self.connections.write().await;
            if !connections
                .devices_by_user
                .get(&user_id)
                .is_some_and(|devices| devices.contains(&device_id))
            {
                return OnlineChange::Unchanged;
            }
            let Some(connection) = connections.by_device.get_mut(&device_id) else {
                return OnlineChange::Unchanged;
            };
            if connection.id != connection_id {
                return OnlineChange::Unchanged;
            }
            let was_online = connection.counts_online();
            connection.focused = focused;
            if let Some(background) = background {
                connection.background = background;
            }
            match (was_online, connection.counts_online()) {
                (false, true) => (OnlineChange::CameOnline, None),
                (true, false) => (OnlineChange::WentOffline, connection.online_ts.take()),
                _ => (OnlineChange::Unchanged, None),
            }
        };
        match change {
            // Writes the online entry and the focus.
            OnlineChange::CameOnline => self.mark_online(user_id, device_id, connection_id).await,
            OnlineChange::WentOffline => {
                self.mark_offline(user_id, device_id, cleared_online_ts)
                    .await;
                self.write_focus(user_id, device_id, connection_id, focused)
                    .await;
            }
            OnlineChange::Unchanged => {
                self.write_focus(user_id, device_id, connection_id, focused)
                    .await;
            }
        }
        change
    }

    /// Closes the sockets opened with any of `session_ids`, here and on every other replica.
    /// Call it once the revocation has committed.
    ///
    /// Human: A socket authenticates only once, so without this a revoked session's socket
    /// kept receiving the account's messages, typing and call signaling until the device
    /// disconnected by itself. Dropping a socket's sender ends its loop in `routes::ws`,
    /// which then tells the client and closes.
    /// Agent: CALLS close_local_sessions; PUBLISHES shroud:sessions:revoked when Redis is set.
    pub async fn close_sessions(&self, user_id: Uuid, session_ids: &[Uuid]) {
        if session_ids.is_empty() {
            return;
        }
        self.close_local_sessions(user_id, session_ids).await;

        if self.redis().await.is_none() {
            return;
        }
        let message = RedisRevokedSessions {
            user_id,
            session_ids: session_ids.to_vec(),
        };
        let body = match serde_json::to_string(&message) {
            Ok(s) => s,
            Err(err) => {
                tracing::warn!(error = %err, "redis revoked sessions serialize failed");
                return;
            }
        };
        // Another replica keeps a revoked socket only while Redis is down, and it can't be
        // told any other way.
        self.redis_call("revoked sessions publish", move |mut conn| async move {
            conn.publish::<_, _, ()>(REVOKED_SESSIONS_CHANNEL, body)
                .await
        })
        .await;
    }

    /// Drops this replica's sockets for `session_ids`.
    async fn close_local_sessions(&self, user_id: Uuid, session_ids: &[Uuid]) {
        let closed: Vec<(Uuid, Option<i64>, Option<u64>)> = {
            let mut connections = self.connections.write().await;
            let devices: Vec<Uuid> = connections
                .devices_by_user
                .get(&user_id)
                .into_iter()
                .flatten()
                .copied()
                .filter(|device_id| {
                    connections
                        .by_device
                        .get(device_id)
                        .is_some_and(|connection| session_ids.contains(&connection.session_id))
                })
                .collect();
            devices
                .into_iter()
                .map(|device_id| {
                    let connection = connections.by_device.get(&device_id);
                    let online_ts = connection.and_then(|connection| connection.online_ts);
                    let connection_id = connection.map(|connection| connection.id);
                    connections.remove(user_id, device_id);
                    (device_id, online_ts, connection_id)
                })
                .collect()
        };
        for (device_id, online_ts, connection_id) in closed {
            tracing::debug!(%user_id, %device_id, "realtime.session_closed");
            self.mark_offline(user_id, device_id, online_ts).await;
            if let Some(connection_id) = connection_id {
                self.clear_focus(user_id, device_id, connection_id).await;
            }
        }
    }

    /// True if the user has at least one online WebSocket (local or Redis online hash). A
    /// background socket whose app is not in front does not count.
    pub async fn is_user_online(&self, user_id: Uuid) -> bool {
        let online_elsewhere = self
            .redis_call("online count", move |mut conn| async move {
                redis_online_count(&mut conn, user_id).await
            })
            .await
            .is_some_and(|n| n > 0);
        if online_elsewhere {
            return true;
        }
        let connections = self.connections.read().await;
        connections
            .devices_by_user
            .get(&user_id)
            .is_some_and(|devices| {
                devices.iter().any(|device_id| {
                    connections
                        .by_device
                        .get(device_id)
                        .is_some_and(Connection::counts_online)
                })
            })
    }

    /// True when this device is connected and its app is in front, so it shows its own notices.
    ///
    /// Human: Push decisions are per device. An app the user is looking at needs no push. One
    /// that is signed in but backgrounded or unfocused does, even while its socket lingers.
    /// A socket that never says (an older app) counts as in front. A device with no socket
    /// does not, nor does one with only a background socket while its app is not in front.
    pub async fn is_device_foreground(&self, user_id: Uuid, device_id: Uuid) -> bool {
        {
            let connections = self.connections.read().await;
            if connections
                .devices_by_user
                .get(&user_id)
                .is_some_and(|devices| devices.contains(&device_id))
                && let Some(connection) = connections.by_device.get(&device_id)
            {
                return connection.focused;
            }
        }
        if !self.is_device_online(user_id, device_id).await {
            return false;
        }
        // Online on another replica. An explicit "away" is a push; no record means the app
        // is in front (or Redis missed the write — a duplicate notice is worse than a delay).
        self.redis_focus(user_id, device_id).await.unwrap_or(true)
    }

    /// True if this device has a live WebSocket here or (with Redis) on another replica.
    ///
    /// Human: Presence is per device: a connected app counts as online, whether or not it is
    /// the one in front — except over a background socket, which counts only while its app is
    /// in front.
    pub async fn is_device_online(&self, user_id: Uuid, device_id: Uuid) -> bool {
        {
            let connections = self.connections.read().await;
            if connections
                .devices_by_user
                .get(&user_id)
                .is_some_and(|devices| devices.contains(&device_id))
                && let Some(connection) = connections.by_device.get(&device_id)
            {
                return connection.counts_online();
            }
        }
        self.redis_call("device online hget", move |mut conn| async move {
            conn.hget::<_, _, Option<i64>>(online_key(user_id), device_id.to_string())
                .await
        })
        .await
        .flatten()
        .is_some_and(|ts| ts >= unix_now_secs() - ONLINE_TTL_SECS)
    }

    /// Keeps a ringing call's `call.ring` event for `ttl_secs`.
    ///
    /// Human: The event goes out once, to the callee's connected devices. A phone that was
    /// asleep shows "Incoming call"; when its user taps it the app connects, and gets the ring
    /// then (`routes::ws`), so it opens on the ringing call instead of a chat.
    /// Agent: Redis `shroud:ring:{callee}` SET EX when configured (any replica may see the
    /// device connect), else in memory. The reader checks the call still rings.
    pub async fn remember_ring(&self, callee: Uuid, payload: &str, ttl_secs: u64) {
        let value = payload.to_string();
        let kept_in_redis = self
            .redis_call("ring set", move |mut conn| async move {
                conn.set_ex::<_, _, ()>(format!("{RING_KEY_PREFIX}{callee}"), value, ttl_secs)
                    .await
            })
            .await
            .is_some();
        if kept_in_redis {
            return;
        }
        let now = Instant::now();
        let mut rings = self.rings.write().await;
        rings.retain(|_, ring| ring.until > now);
        rings.insert(
            callee,
            PendingRing {
                payload: payload.to_string(),
                until: now + Duration::from_secs(ttl_secs),
            },
        );
    }

    /// The `call.ring` kept for `callee`, if one is (its call may have stopped ringing since).
    pub async fn pending_ring(&self, callee: Uuid) -> Option<String> {
        let kept_in_redis = self
            .redis_call("ring get", move |mut conn| async move {
                conn.get::<_, Option<String>>(format!("{RING_KEY_PREFIX}{callee}"))
                    .await
            })
            .await
            .flatten();
        if kept_in_redis.is_some() {
            return kept_in_redis;
        }
        let rings = self.rings.read().await;
        rings
            .get(&callee)
            .filter(|ring| ring.until > Instant::now())
            .map(|ring| ring.payload.clone())
    }

    /// Refreshes this socket's Redis online heartbeat (call from WS loop). A background
    /// socket whose app is not in front writes none.
    pub async fn refresh_online(&self, user_id: Uuid, device_id: Uuid, connection_id: u64) {
        self.mark_online(user_id, device_id, connection_id).await;
    }

    async fn mark_online(&self, user_id: Uuid, device_id: Uuid, connection_id: u64) {
        let focused = {
            let connections = self.connections.read().await;
            let Some(connection) = connections.by_device.get(&device_id) else {
                return;
            };
            if connection.id != connection_id || !connection.counts_online() {
                return;
            }
            connection.focused
        };
        let key = online_key(user_id);
        let now = unix_now_secs();
        // Human: HASH field = device_id, value = unix ts; EXPIRE bounds crash orphans.
        // Agent: HSET + EXPIRE ONLINE_TTL_SECS; pruned on is_user_online read. Without Redis
        // (or while it is down) the socket stays online on this replica only; the next
        // refresh_online writes the entry again.
        let hset_key = key.clone();
        let written = self
            .redis_call("online hset", move |mut conn| async move {
                conn.hset::<_, _, _, ()>(hset_key, device_id.to_string(), now)
                    .await
            })
            .await;
        if written.is_none() {
            return;
        }
        self.redis_call("online expire", move |mut conn| async move {
            conn.expire::<_, ()>(key, ONLINE_TTL_SECS).await
        })
        .await;
        if let Some(connection) = self.connections.write().await.by_device.get_mut(&device_id)
            && connection.id == connection_id
        {
            connection.online_ts = Some(now);
        }
        self.write_focus(user_id, device_id, connection_id, focused)
            .await;
    }

    async fn write_focus(&self, user_id: Uuid, device_id: Uuid, connection_id: u64, focused: bool) {
        let key = focus_key(user_id);
        let value = format!("{connection_id}:{}", if focused { "1" } else { "0" });
        let hset_key = key.clone();
        let written = self
            .redis_call("focus hset", move |mut conn| async move {
                conn.hset::<_, _, _, ()>(hset_key, device_id.to_string(), value)
                    .await
            })
            .await;
        if written.is_none() {
            return;
        }
        self.redis_call("focus expire", move |mut conn| async move {
            conn.expire::<_, ()>(key, ONLINE_TTL_SECS).await
        })
        .await;
    }

    async fn clear_focus(&self, user_id: Uuid, device_id: Uuid, connection_id: u64) {
        self.redis_call("focus hdel", move |mut conn| async move {
            redis::Script::new(DELETE_FOCUS_IF_OURS)
                .key(focus_key(user_id))
                .arg(device_id.to_string())
                .arg(format!("{connection_id}:"))
                .invoke_async::<i64>(&mut conn)
                .await
        })
        .await;
    }

    /// `Some` when this replica can read an explicit focus flag for the device.
    async fn redis_focus(&self, user_id: Uuid, device_id: Uuid) -> Option<bool> {
        let value = self
            .redis_call("focus hget", move |mut conn| async move {
                conn.hget::<_, _, Option<String>>(focus_key(user_id), device_id.to_string())
                    .await
            })
            .await?;
        match value.as_deref().and_then(|value| value.rsplit(':').next()) {
            Some("1") => Some(true),
            Some("0") => Some(false),
            _ => None,
        }
    }

    /// Clears the device's Redis online entry, unless a newer socket of it wrote one since.
    async fn mark_offline(&self, user_id: Uuid, device_id: Uuid, online_ts: Option<i64>) {
        let Some(online_ts) = online_ts else {
            return;
        };
        self.redis_call("online hdel", move |mut conn| async move {
            redis::Script::new(DELETE_IF_UNCHANGED)
                .key(online_key(user_id))
                .arg(device_id.to_string())
                .arg(online_ts)
                .invoke_async::<i64>(&mut conn)
                .await
        })
        .await;
    }

    /// Delivers a JSON text event to local sockets for the given users.
    pub async fn publish_local_to_users(
        &self,
        user_ids: impl IntoIterator<Item = Uuid>,
        except_device: Option<Uuid>,
        payload: &str,
    ) {
        let connections = self.connections.read().await;
        for user_id in user_ids {
            if let Some(devices) = connections.devices_by_user.get(&user_id) {
                for device_id in devices {
                    if except_device == Some(*device_id) {
                        continue;
                    }
                    if let Some(connection) = connections.by_device.get(device_id) {
                        // Human: Never block HTTP handlers on slow WS consumers — drop when full.
                        // Agent: try_send; Full → warn+drop; Closed → ignore.
                        match connection.tx.try_send(payload.to_string()) {
                            Ok(()) => {}
                            Err(mpsc::error::TrySendError::Full(_)) => {
                                tracing::warn!(
                                    %device_id,
                                    "realtime outbound queue full; dropping event"
                                );
                            }
                            Err(mpsc::error::TrySendError::Closed(_)) => {}
                        }
                    }
                }
            }
        }
    }

    /// Delivers a JSON text event to one device's local socket, if it has one here.
    fn publish_local_to_device(connections: &Connections, device_id: Uuid, payload: &str) {
        let Some(connection) = connections.by_device.get(&device_id) else {
            return;
        };
        match connection.tx.try_send(payload.to_string()) {
            Ok(()) => {}
            Err(mpsc::error::TrySendError::Full(_)) => {
                tracing::warn!(%device_id, "realtime outbound queue full; dropping event");
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {}
        }
    }

    /// Sends an event to one device of `user_id` only, on whichever replica holds its socket.
    ///
    /// Human: A call's signals are for the one device in the call; the user's other devices
    /// have no business with them.
    pub async fn publish_to_device(&self, user_id: Uuid, device_id: Uuid, payload: &str) {
        {
            let connections = self.connections.read().await;
            if connections
                .devices_by_user
                .get(&user_id)
                .is_some_and(|devices| devices.contains(&device_id))
            {
                Self::publish_local_to_device(&connections, device_id, payload);
            }
        }

        if self.redis().await.is_none() {
            return;
        }
        let event: Value = match serde_json::from_str(payload) {
            Ok(value) => value,
            Err(_) => Value::String(payload.to_string()),
        };
        let envelope = RedisFanout {
            user_id,
            except_device_id: None,
            only_device_id: Some(device_id),
            event,
        };
        let body = match serde_json::to_string(&envelope) {
            Ok(s) => s,
            Err(err) => {
                tracing::warn!(error = %err, "redis fanout serialize failed");
                return;
            }
        };
        let channel = format!("{USER_CHANNEL_PREFIX}{user_id}");
        self.redis_call("device publish", move |mut conn| async move {
            conn.publish::<_, _, ()>(channel, body).await
        })
        .await;
    }

    /// Fan-out entry point used by HTTP handlers.
    ///
    /// Always delivers to local sockets first (this process). When Redis is configured,
    /// also publishes so other replicas can deliver. Clients de-dupe by message id.
    ///
    /// Previously Redis-only mode silently dropped events when the subscriber lagged
    /// or no local sockets were registered yet after a publish-only path.
    pub async fn publish_to_users(
        &self,
        user_ids: impl IntoIterator<Item = Uuid>,
        except_device: Option<Uuid>,
        payload: &str,
    ) {
        let users: Vec<Uuid> = user_ids.into_iter().collect();

        // Count local targets for diagnostics (helps spot "send ok but nobody online").
        let local_targets = {
            let connections = self.connections.read().await;
            users
                .iter()
                .filter_map(|uid| connections.devices_by_user.get(uid).map(|set| set.len()))
                .sum::<usize>()
        };
        if local_targets == 0 {
            tracing::debug!(
                users = ?users,
                except_device = ?except_device,
                "realtime.publish: no local websocket subscribers"
            );
        }

        self.publish_local_to_users(users.clone(), except_device, payload)
            .await;

        if self.redis().await.is_none() {
            return;
        }

        let event: Value = match serde_json::from_str(payload) {
            Ok(value) => value,
            Err(_) => Value::String(payload.to_string()),
        };

        for user_id in users {
            let envelope = RedisFanout {
                user_id,
                except_device_id: except_device,
                only_device_id: None,
                event: event.clone(),
            };
            let body = match serde_json::to_string(&envelope) {
                Ok(s) => s,
                Err(err) => {
                    tracing::warn!(error = %err, "redis fanout serialize failed");
                    continue;
                }
            };
            let channel = format!("{USER_CHANNEL_PREFIX}{user_id}");
            // After a failure the rest are skipped at once (REDIS_BACKOFF).
            self.redis_call("users publish", move |mut conn| async move {
                conn.publish::<_, _, ()>(channel, body).await
            })
            .await;
        }
    }
}

/// Counts fresh Redis online devices for `user_id`, pruning stale hash fields.
async fn redis_online_count(
    conn: &mut ConnectionManager,
    user_id: Uuid,
) -> Result<usize, redis::RedisError> {
    let key = online_key(user_id);
    let entries: HashMap<String, i64> = conn.hgetall(&key).await?;
    if entries.is_empty() {
        return Ok(0);
    }

    let cutoff = unix_now_secs() - ONLINE_TTL_SECS;
    let mut fresh = 0usize;
    for (device_id, ts) in entries {
        if ts >= cutoff {
            fresh += 1;
        } else if let Err(err) = conn.hdel::<_, _, ()>(&key, &device_id).await {
            tracing::warn!(error = %err, "redis online prune hdel failed");
        }
    }
    Ok(fresh)
}

/// How often the subscriber checks that its connection still answers.
const SUBSCRIBER_PROBE_EVERY: Duration = Duration::from_secs(10);
/// How long the subscriber waits for Redis to answer: connecting, subscribing, a probe.
const SUBSCRIBER_TIMEOUT: Duration = Duration::from_secs(2);

/// Spawns a background task that pattern-subscribes and fans out to the local hub.
///
/// Agent: CALLS redis PSUBSCRIBE shroud:user:*, delivered via publish_local_to_users, and
/// SUBSCRIBE shroud:sessions:revoked, applied via close_local_sessions.
pub fn spawn_redis_subscriber(hub: Arc<RealtimeHub>, redis_url: String) {
    tokio::spawn(async move {
        loop {
            if let Err(err) = run_subscriber(hub.clone(), &redis_url, SUBSCRIBER_PROBE_EVERY).await
            {
                tracing::error!(error = %err, "redis subscriber stopped; reconnecting in 2s");
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            }
        }
    });
}

/// `call`, or an I/O error once it hasn't finished within [`SUBSCRIBER_TIMEOUT`].
async fn subscriber_step<T>(
    what: &'static str,
    call: impl Future<Output = redis::RedisResult<T>>,
) -> redis::RedisResult<T> {
    tokio::time::timeout(SUBSCRIBER_TIMEOUT, call)
        .await
        .unwrap_or_else(|_| {
            Err(redis::RedisError::from((
                redis::ErrorKind::IoError,
                "redis subscriber got no answer",
                what.to_string(),
            )))
        })
}

/// Listens until the connection ends or stops answering.
///
/// Human: A connection whose peer vanished without a reset never ends on its own, and a
/// replica on one heard nothing from the others while looking healthy. Every `probe_every`
/// it subscribes again to a channel it already has, which changes nothing but must be
/// answered.
/// Agent: RETURNS Err when connecting, subscribing or a probe takes over SUBSCRIBER_TIMEOUT;
/// spawn_redis_subscriber then reconnects.
async fn run_subscriber(
    hub: Arc<RealtimeHub>,
    redis_url: &str,
    probe_every: Duration,
) -> Result<(), redis::RedisError> {
    use futures_util::StreamExt;

    let client = redis::Client::open(redis_url)?;
    let pubsub = subscriber_step("connect", client.get_async_pubsub()).await?;
    let (mut sink, mut stream) = pubsub.split();
    subscriber_step(
        "psubscribe",
        sink.psubscribe(format!("{USER_CHANNEL_PREFIX}*")),
    )
    .await?;
    subscriber_step("subscribe", sink.subscribe(REVOKED_SESSIONS_CHANNEL)).await?;
    tracing::info!(
        "redis realtime subscriber listening on shroud:user:* and {REVOKED_SESSIONS_CHANNEL}"
    );

    let mut probe =
        tokio::time::interval_at(tokio::time::Instant::now() + probe_every, probe_every);
    probe.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! {
            msg = stream.next() => {
                let Some(msg) = msg else {
                    break;
                };
                deliver_redis_message(&hub, msg).await;
            }
            _ = probe.tick() => {
                subscriber_step("probe", sink.subscribe(REVOKED_SESSIONS_CHANNEL)).await?;
            }
        }
    }

    Err(redis::RedisError::from((
        redis::ErrorKind::IoError,
        "redis pubsub stream ended",
    )))
}

/// Hands one pub/sub message from another replica to this replica's sockets.
async fn deliver_redis_message(hub: &RealtimeHub, msg: redis::Msg) {
    let payload: String = match msg.get_payload() {
        Ok(p) => p,
        Err(err) => {
            tracing::warn!(error = %err, "redis message payload decode failed");
            return;
        }
    };
    if msg.get_channel_name() == REVOKED_SESSIONS_CHANNEL {
        match serde_json::from_str::<RedisRevokedSessions>(&payload) {
            Ok(revoked) => {
                hub.close_local_sessions(revoked.user_id, &revoked.session_ids)
                    .await;
            }
            Err(err) => tracing::warn!(error = %err, "redis revoked sessions invalid"),
        }
        return;
    }
    let envelope: RedisFanout = match serde_json::from_str(&payload) {
        Ok(e) => e,
        Err(err) => {
            tracing::warn!(error = %err, "redis fanout envelope invalid");
            return;
        }
    };
    let event_text = match serde_json::to_string(&envelope.event) {
        Ok(s) => s,
        Err(err) => {
            tracing::warn!(error = %err, "redis event reserialize failed");
            return;
        }
    };
    match envelope.only_device_id {
        Some(device_id) => {
            let connections = hub.connections.read().await;
            if connections
                .devices_by_user
                .get(&envelope.user_id)
                .is_some_and(|devices| devices.contains(&device_id))
            {
                RealtimeHub::publish_local_to_device(&connections, device_id, &event_text);
            }
        }
        None => {
            hub.publish_local_to_users([envelope.user_id], envelope.except_device_id, &event_text)
                .await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::mpsc::error::TryRecvError;

    #[tokio::test]
    async fn closing_a_session_ends_only_its_socket() {
        let hub = Arc::new(RealtimeHub::new());
        let user = Uuid::new_v4();
        let (phone, phone_session) = (Uuid::new_v4(), Uuid::new_v4());
        let (laptop, laptop_session) = (Uuid::new_v4(), Uuid::new_v4());
        let mut phone_socket = hub
            .subscribe(user, phone, phone_session)
            .await
            .expect("phone");
        let mut laptop_socket = hub
            .subscribe(user, laptop, laptop_session)
            .await
            .expect("laptop");

        hub.close_sessions(user, &[laptop_session]).await;
        hub.publish_to_users([user], None, "event").await;

        assert_eq!(
            laptop_socket.events.try_recv(),
            Err(TryRecvError::Disconnected)
        );
        assert_eq!(phone_socket.events.try_recv().as_deref(), Ok("event"));
        // The closed socket's own cleanup comes later and changes nothing.
        hub.unsubscribe(user, laptop, laptop_socket.id).await;
        assert!(hub.is_user_online(user).await);
    }

    #[tokio::test]
    async fn a_replaced_socket_leaves_its_successor_registered() {
        let hub = Arc::new(RealtimeHub::new());
        let (user, device, session) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let mut old = hub.subscribe(user, device, session).await.expect("old");
        let mut new = hub.subscribe(user, device, session).await.expect("new");

        assert_eq!(old.events.try_recv(), Err(TryRecvError::Disconnected));
        hub.unsubscribe(user, device, old.id).await;
        hub.publish_to_users([user], None, "event").await;

        assert_eq!(new.events.try_recv().as_deref(), Ok("event"));
        hub.unsubscribe(user, device, new.id).await;
        assert!(!hub.is_user_online(user).await);
    }

    #[tokio::test]
    async fn a_device_event_reaches_that_device_only() {
        let hub = Arc::new(RealtimeHub::new());
        let user = Uuid::new_v4();
        let (phone, laptop) = (Uuid::new_v4(), Uuid::new_v4());
        let mut phone_socket = hub
            .subscribe(user, phone, Uuid::new_v4())
            .await
            .expect("phone");
        let mut laptop_socket = hub
            .subscribe(user, laptop, Uuid::new_v4())
            .await
            .expect("laptop");

        hub.publish_to_device(user, laptop, "signal").await;
        // Another user's device id never matches.
        hub.publish_to_device(Uuid::new_v4(), phone, "stray").await;

        assert_eq!(laptop_socket.events.try_recv().as_deref(), Ok("signal"));
        assert_eq!(phone_socket.events.try_recv(), Err(TryRecvError::Empty));
    }

    #[tokio::test]
    async fn a_socket_that_left_the_foreground_is_not_in_front() {
        let hub = Arc::new(RealtimeHub::new());
        let (user, device) = (Uuid::new_v4(), Uuid::new_v4());
        let socket = hub
            .subscribe(user, device, Uuid::new_v4())
            .await
            .expect("socket");

        assert!(hub.is_device_foreground(user, device).await);
        // A stale socket cannot mark the live one away.
        hub.set_focus(user, device, socket.id + 1, false).await;
        assert!(hub.is_device_foreground(user, device).await);

        hub.set_focus(user, device, socket.id, false).await;
        assert!(!hub.is_device_foreground(user, device).await);
        hub.set_focus(user, device, socket.id, true).await;
        assert!(hub.is_device_foreground(user, device).await);

        hub.unsubscribe(user, device, socket.id).await;
        assert!(!hub.is_device_foreground(user, device).await);
        assert!(!hub.is_device_online(user, device).await);
    }

    #[tokio::test]
    async fn a_background_socket_counts_only_while_its_app_is_in_front() {
        let hub = Arc::new(RealtimeHub::new());
        let (user, phone) = (Uuid::new_v4(), Uuid::new_v4());
        let mut socket = hub
            .subscribe_with(user, phone, Uuid::new_v4(), SocketMode::Background)
            .await
            .expect("background socket");

        // It hears everything, but its user is not online through it, nor its app in front.
        hub.publish_to_users([user], None, "event").await;
        assert_eq!(socket.events.try_recv().as_deref(), Ok("event"));
        assert!(!hub.is_user_online(user).await);
        assert!(!hub.is_device_online(user, phone).await);
        assert!(!hub.is_device_foreground(user, phone).await);
        // Saying it is still away changes nothing.
        assert_eq!(
            hub.set_focus(user, phone, socket.id, false).await,
            OnlineChange::Unchanged
        );
        assert!(!hub.is_user_online(user).await);

        // The app comes to the front on the same socket: online, and in front.
        assert_eq!(
            hub.set_focus(user, phone, socket.id, true).await,
            OnlineChange::CameOnline
        );
        assert!(hub.is_user_online(user).await);
        assert!(hub.is_device_online(user, phone).await);
        assert!(hub.is_device_foreground(user, phone).await);
        assert_eq!(
            hub.set_focus(user, phone, socket.id, true).await,
            OnlineChange::Unchanged
        );

        // It leaves again: back to background accounting.
        assert_eq!(
            hub.set_focus(user, phone, socket.id, false).await,
            OnlineChange::WentOffline
        );
        assert!(!hub.is_user_online(user).await);
        assert!(!hub.is_device_foreground(user, phone).await);
        hub.publish_to_users([user], None, "later").await;
        assert_eq!(socket.events.try_recv().as_deref(), Ok("later"));

        // A stale socket id changes nothing.
        assert_eq!(
            hub.set_focus(user, phone, socket.id + 1, true).await,
            OnlineChange::Unchanged
        );
        assert!(!hub.is_user_online(user).await);
    }

    #[tokio::test]
    async fn a_background_socket_leaves_other_devices_online() {
        let hub = Arc::new(RealtimeHub::new());
        let (user, phone, laptop) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let _phone = hub
            .subscribe_with(user, phone, Uuid::new_v4(), SocketMode::Background)
            .await
            .expect("phone");
        let laptop_socket = hub
            .subscribe(user, laptop, Uuid::new_v4())
            .await
            .expect("laptop");
        assert!(hub.is_user_online(user).await);
        assert!(hub.is_device_online(user, laptop).await);
        assert!(!hub.is_device_online(user, phone).await);

        hub.unsubscribe(user, laptop, laptop_socket.id).await;
        assert!(!hub.is_user_online(user).await);
    }

    #[tokio::test]
    async fn a_socket_kept_for_the_background_connection_turns_background() {
        let hub = Arc::new(RealtimeHub::new());
        let (user, phone) = (Uuid::new_v4(), Uuid::new_v4());
        // Opened while the app was on screen, as every socket is.
        let socket = hub
            .subscribe(user, phone, Uuid::new_v4())
            .await
            .expect("socket");
        assert!(hub.is_user_online(user).await);

        // An ordinary socket that leaves the front stays online (a tab in the background).
        assert_eq!(
            hub.update_focus(user, phone, socket.id, false, None).await,
            OnlineChange::Unchanged
        );
        assert!(hub.is_user_online(user).await);
        assert!(!hub.is_device_foreground(user, phone).await);
        hub.set_focus(user, phone, socket.id, true).await;

        // The app leaves and keeps its socket for the background connection.
        assert_eq!(
            hub.update_focus(user, phone, socket.id, false, Some(true))
                .await,
            OnlineChange::WentOffline
        );
        assert!(!hub.is_user_online(user).await);
        assert!(!hub.is_device_foreground(user, phone).await);
        // Back in front, then away again without saying: still a background socket.
        assert_eq!(
            hub.update_focus(user, phone, socket.id, true, None).await,
            OnlineChange::CameOnline
        );
        assert_eq!(
            hub.update_focus(user, phone, socket.id, false, None).await,
            OnlineChange::WentOffline
        );
        // The background connection was switched off while the socket stays: ordinary again.
        assert_eq!(
            hub.update_focus(user, phone, socket.id, false, Some(false))
                .await,
            OnlineChange::CameOnline
        );
        assert!(hub.is_user_online(user).await);
    }

    #[tokio::test]
    async fn a_background_socket_replacing_an_online_one_takes_the_device_offline() {
        let hub = Arc::new(RealtimeHub::new());
        let (user, phone) = (Uuid::new_v4(), Uuid::new_v4());
        let old = hub
            .subscribe(user, phone, Uuid::new_v4())
            .await
            .expect("old");
        assert!(hub.is_user_online(user).await);
        let new = hub
            .subscribe_with(user, phone, Uuid::new_v4(), SocketMode::Background)
            .await
            .expect("new");
        assert!(!hub.is_user_online(user).await);
        // The old socket's cleanup leaves the new one registered.
        hub.unsubscribe(user, phone, old.id).await;
        hub.set_focus(user, phone, new.id, true).await;
        assert!(hub.is_user_online(user).await);
    }

    /// A stand-in Redis. Its first connection answers the connection's setup and every
    /// command up to the `nth` named `silent_at`, then goes silent, as one does when its host
    /// drops off the network without a reset. Later connections answer everything.
    struct FakeRedis {
        url: String,
        /// The names of the commands later connections answered.
        answered_later: Arc<std::sync::Mutex<Vec<String>>>,
    }

    impl FakeRedis {
        async fn start(silent_at: &'static str, nth: u64) -> Self {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
                .await
                .expect("bind");
            let url = format!("redis://{}", listener.local_addr().expect("address"));
            let answered_later = Arc::new(std::sync::Mutex::new(Vec::new()));
            let counter = answered_later.clone();
            tokio::spawn(async move {
                let mut first = true;
                while let Ok((socket, _)) = listener.accept().await {
                    let silent_from = first.then_some(nth);
                    first = false;
                    tokio::spawn(serve_fake_redis(
                        socket,
                        silent_at,
                        silent_from,
                        counter.clone(),
                    ));
                }
            });
            Self {
                url,
                answered_later,
            }
        }

        fn client(&self) -> redis::Client {
            redis::Client::open(self.url.as_str()).expect("client")
        }

        async fn manager(&self) -> ConnectionManager {
            ConnectionManager::new(self.client())
                .await
                .expect("manager")
        }

        /// How many `name` commands later connections answered.
        fn answered_later(&self, name: &str) -> usize {
            let answered = self.answered_later.lock().expect("answered");
            answered.iter().filter(|answered| *answered == name).count()
        }
    }

    async fn serve_fake_redis(
        mut socket: tokio::net::TcpStream,
        silent_at: &'static str,
        silent_from: Option<u64>,
        answered_later: Arc<std::sync::Mutex<Vec<String>>>,
    ) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let mut buffer = Vec::new();
        let mut chunk = [0u8; 4096];
        let mut seen = 0;
        let mut silent = false;
        loop {
            let read = match socket.read(&mut chunk).await {
                Ok(0) | Err(_) => return,
                Ok(read) => read,
            };
            buffer.extend_from_slice(&chunk[..read]);
            let mut out = String::new();
            while let Some((args, used)) = parse_resp_command(&buffer) {
                buffer.drain(..used);
                match silent_from {
                    Some(nth) => {
                        if args[0].eq_ignore_ascii_case(silent_at) {
                            seen += 1;
                            silent |= seen >= nth;
                        }
                    }
                    None => {
                        if let Ok(mut answered) = answered_later.lock() {
                            answered.push(args[0].to_ascii_uppercase());
                        }
                    }
                }
                if !silent {
                    out.push_str(&resp_reply(&args));
                }
            }
            if !out.is_empty() && socket.write_all(out.as_bytes()).await.is_err() {
                return;
            }
        }
    }

    /// One command from the front of `buffer` and the bytes it took, once all of it is there.
    fn parse_resp_command(buffer: &[u8]) -> Option<(Vec<String>, usize)> {
        fn line(buffer: &[u8], at: usize) -> Option<(&str, usize)> {
            let end = at
                + buffer
                    .get(at..)?
                    .windows(2)
                    .position(|pair| pair == b"\r\n")?;
            Some((std::str::from_utf8(&buffer[at..end]).ok()?, end + 2))
        }
        let (header, mut at) = line(buffer, 0)?;
        let count: usize = header.strip_prefix('*')?.parse().ok()?;
        let mut args = Vec::with_capacity(count);
        for _ in 0..count {
            let (length, start) = line(buffer, at)?;
            let length: usize = length.strip_prefix('$')?.parse().ok()?;
            let data = buffer.get(start..start + length + 2)?;
            args.push(String::from_utf8_lossy(&data[..length]).into_owned());
            at = start + length + 2;
        }
        Some((args, at))
    }

    /// What Redis answers `args` with (RESP2), as far as the hub needs.
    fn resp_reply(args: &[String]) -> String {
        let bulk = |text: &str| format!("${}\r\n{text}\r\n", text.len());
        let name = args[0].to_ascii_uppercase();
        match name.as_str() {
            "SUBSCRIBE" | "PSUBSCRIBE" => args[1..]
                .iter()
                .enumerate()
                .map(|(index, channel)| {
                    let kind = bulk(&name.to_ascii_lowercase());
                    format!("*3\r\n{kind}{}:{}\r\n", bulk(channel), index + 1)
                })
                .collect(),
            "PING" => "+PONG\r\n".into(),
            "GET" | "HGET" => "$-1\r\n".into(),
            "HGETALL" => "*0\r\n".into(),
            "INCR" | "INCRBY" => ":1\r\n".into(),
            "PUBLISH" | "HSET" | "HDEL" | "EXPIRE" | "EVALSHA" | "EVAL" => ":0\r\n".into(),
            _ => "+OK\r\n".into(),
        }
    }

    /// A send must reach this replica's sockets within [`REDIS_TIMEOUT`] of Redis going
    /// silent, and for [`REDIS_BACKOFF`] after it the hub doesn't wait on Redis at all.
    #[tokio::test]
    async fn a_silent_redis_falls_back_instead_of_hanging() {
        let hub = Arc::new(RealtimeHub::new());
        hub.set_redis(FakeRedis::start("PUBLISH", 1).await.manager().await)
            .await;
        let user = Uuid::new_v4();
        let (phone, phone_session) = (Uuid::new_v4(), Uuid::new_v4());
        let (laptop, laptop_session) = (Uuid::new_v4(), Uuid::new_v4());
        let mut phone_socket = hub
            .subscribe(user, phone, phone_session)
            .await
            .expect("phone");
        let mut laptop_socket = hub
            .subscribe(user, laptop, laptop_session)
            .await
            .expect("laptop");

        let started = Instant::now();
        hub.publish_to_users([user], None, "first").await;
        assert!(started.elapsed() < REDIS_TIMEOUT + Duration::from_millis(500));
        assert_eq!(phone_socket.events.try_recv().as_deref(), Ok("first"));
        assert_eq!(laptop_socket.events.try_recv().as_deref(), Ok("first"));

        // Within the backoff, sends, presence, rings and revocations stay on this replica.
        let started = Instant::now();
        hub.publish_to_users([user], None, "second").await;
        hub.publish_to_device(user, phone, "signal").await;
        assert!(hub.is_user_online(user).await);
        assert!(hub.is_device_foreground(user, phone).await);
        hub.remember_ring(user, "ring", 60).await;
        assert_eq!(hub.pending_ring(user).await.as_deref(), Some("ring"));
        hub.close_sessions(user, &[laptop_session]).await;
        hub.unsubscribe(user, phone, phone_socket.id).await;
        assert!(started.elapsed() < Duration::from_millis(100));
        assert_eq!(phone_socket.events.try_recv().as_deref(), Ok("second"));
        assert_eq!(phone_socket.events.try_recv().as_deref(), Ok("signal"));
        assert_eq!(laptop_socket.events.try_recv().as_deref(), Ok("second"));
        assert_eq!(
            laptop_socket.events.try_recv(),
            Err(TryRecvError::Disconnected)
        );
        assert!(!hub.is_user_online(user).await);

        // Readiness still asks Redis, and gives up on it too.
        let started = Instant::now();
        assert!(hub.ping_redis().await.is_err());
        assert!(started.elapsed() < REDIS_TIMEOUT + Duration::from_millis(500));
    }

    /// The presence write a new socket makes is bounded the same way.
    #[tokio::test]
    async fn a_silent_redis_does_not_hold_up_a_new_socket() {
        let hub = Arc::new(RealtimeHub::new());
        hub.set_redis(FakeRedis::start("HSET", 1).await.manager().await)
            .await;
        let (user, device) = (Uuid::new_v4(), Uuid::new_v4());

        let started = Instant::now();
        let mut socket = hub
            .subscribe(user, device, Uuid::new_v4())
            .await
            .expect("socket");
        assert!(started.elapsed() < REDIS_TIMEOUT + Duration::from_millis(500));
        let started = Instant::now();
        assert!(hub.is_user_online(user).await);
        hub.publish_to_users([user], None, "event").await;
        assert!(started.elapsed() < Duration::from_millis(100));
        assert_eq!(socket.events.try_recv().as_deref(), Ok("event"));
    }

    /// A connection that went silent is replaced rather than waited out: the hub is back on
    /// Redis well before [`REDIS_BACKOFF`] runs out.
    #[tokio::test]
    async fn a_silent_redis_connection_is_replaced() {
        let redis = FakeRedis::start("PUBLISH", 1).await;
        let hub = Arc::new(RealtimeHub::new());
        hub.set_redis(redis.manager().await).await;
        hub.set_redis_client(redis.client());
        let user = Uuid::new_v4();

        hub.publish_to_users([user], None, "unanswered").await;
        let started = Instant::now();
        while redis.answered_later("PUBLISH") == 0 {
            assert!(
                started.elapsed() < Duration::from_secs(2),
                "still not back on Redis"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
            hub.publish_to_users([user], None, "again").await;
        }
        assert!(hub.ping_redis().await.is_ok());
    }

    /// The rate limiter, handed the same connection, moves to the replacement with the hub.
    #[tokio::test]
    async fn the_rate_limiter_moves_with_a_replaced_connection() {
        let redis = FakeRedis::start("PUBLISH", 1).await;
        let manager = redis.manager().await;
        let hub = Arc::new(RealtimeHub::new());
        hub.set_redis(manager.clone()).await;
        hub.set_redis_client(redis.client());
        let limiter = crate::rate_limit::RateLimiter::new();
        limiter.set_redis(manager).await;
        crate::relay_redis_replacements(hub.redis_replacements(), limiter.clone());

        hub.publish_to_users([Uuid::new_v4()], None, "unanswered")
            .await;
        let started = Instant::now();
        while redis.answered_later("INCRBY") == 0 {
            assert!(
                started.elapsed() < Duration::from_secs(2),
                "the rate limiter kept the silent connection"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
            // On the silent connection the check may wait; this test only needs to see where
            // it goes.
            let check = limiter.check("test", "relay", 100, Duration::from_secs(60));
            let _ = tokio::time::timeout(Duration::from_millis(200), check).await;
        }
    }

    /// A rate-limit check that gets no answer has the hub replace the connection, so the
    /// limiter is back on Redis without the hub calling Redis at all, well before its backoff
    /// runs out.
    #[tokio::test]
    async fn a_silent_rate_limit_check_replaces_the_connection() {
        let redis = FakeRedis::start("INCRBY", 1).await;
        let manager = redis.manager().await;
        let hub = Arc::new(RealtimeHub::new());
        hub.set_redis(manager.clone()).await;
        hub.set_redis_client(redis.client());
        let limiter = crate::rate_limit::RateLimiter::new();
        limiter.set_redis(manager).await;
        crate::relay_redis_replacements(hub.redis_replacements(), limiter.clone());
        let reporter = hub.clone();
        limiter.on_silent_redis(move || reporter.report_silent_redis());

        let started = Instant::now();
        limiter
            .check("test", "silent", 100, Duration::from_secs(60))
            .await
            .expect("counted in-process");
        assert!(started.elapsed() < REDIS_TIMEOUT + Duration::from_millis(500));
        let started = Instant::now();
        while redis.answered_later("INCRBY") == 0 {
            assert!(
                started.elapsed() < Duration::from_secs(2),
                "the silent check replaced nothing"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
            limiter
                .check("test", "silent", 100, Duration::from_secs(60))
                .await
                .expect("counted");
        }
    }

    /// The subscriber gives up a connection that stops answering its probe, so the loop
    /// connects again instead of listening to nothing for good.
    #[tokio::test]
    async fn a_silent_subscriber_connection_is_given_up() {
        // Answers the SUBSCRIBE that starts the subscriber, not the first probe.
        let redis = FakeRedis::start("SUBSCRIBE", 2).await;
        let hub = Arc::new(RealtimeHub::new());
        let probe_every = Duration::from_millis(200);

        let started = Instant::now();
        let ended = run_subscriber(hub, &redis.url, probe_every).await;
        assert!(ended.is_err());
        assert!(started.elapsed() >= probe_every + SUBSCRIBER_TIMEOUT);
        assert!(started.elapsed() < probe_every + SUBSCRIBER_TIMEOUT + Duration::from_millis(500));
    }

    /// An answering connection passes its probes and keeps listening.
    #[tokio::test]
    async fn an_answering_subscriber_connection_is_kept() {
        let redis = FakeRedis::start("SUBSCRIBE", u64::MAX).await;
        let hub = Arc::new(RealtimeHub::new());

        let running = tokio::time::timeout(
            Duration::from_secs(1),
            run_subscriber(hub, &redis.url, Duration::from_millis(100)),
        )
        .await;
        assert!(running.is_err(), "subscriber ended: {running:?}");
    }

    #[test]
    fn fanout_envelopes_without_a_device_still_parse() {
        let old = r#"{"user_id":"0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b","except_device_id":null,"event":{"type":"x"}}"#;
        let envelope: RedisFanout = serde_json::from_str(old).expect("old envelope");
        assert!(envelope.only_device_id.is_none());
    }
}
