//! WebSocket fan-out: in-process hub, optional Redis multi-replica pub/sub.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use redis::AsyncCommands;
use redis::aio::ConnectionManager;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::{RwLock, mpsc};
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
    redis: RwLock<Option<ConnectionManager>>,
    /// Rings kept while their calls ring, by callee — in Redis instead when it is set.
    rings: RwLock<HashMap<Uuid, PendingRing>>,
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
        *self.redis.write().await = Some(manager);
    }

    /// True when a Redis connection manager is attached.
    pub async fn has_redis(&self) -> bool {
        self.redis.read().await.is_some()
    }

    /// PING Redis when configured. Errors if missing or the command fails.
    pub async fn ping_redis(&self) -> Result<(), String> {
        let mut conn = self
            .redis
            .read()
            .await
            .clone()
            .ok_or_else(|| "redis not connected".to_string())?;
        redis::cmd("PING")
            .query_async::<String>(&mut conn)
            .await
            .map_err(|err| err.to_string())?;
        Ok(())
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

        let redis = self.redis.read().await.clone();
        let Some(mut conn) = redis else {
            return;
        };
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
        if let Err(err) = conn
            .publish::<_, _, ()>(REVOKED_SESSIONS_CHANNEL, body)
            .await
        {
            tracing::warn!(error = %err, %user_id, "redis revoked sessions publish failed");
        }
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
            tracing::info!(%user_id, %device_id, "realtime.session_closed");
            self.mark_offline(user_id, device_id, online_ts).await;
            if let Some(connection_id) = connection_id {
                self.clear_focus(user_id, device_id, connection_id).await;
            }
        }
    }

    /// True if the user has at least one online WebSocket (local or Redis online hash). A
    /// background socket whose app is not in front does not count.
    pub async fn is_user_online(&self, user_id: Uuid) -> bool {
        if let Some(mut conn) = self.redis.read().await.clone() {
            match redis_online_count(&mut conn, user_id).await {
                Ok(n) if n > 0 => return true,
                Ok(_) => {}
                Err(err) => tracing::warn!(error = %err, "redis online check failed"),
            }
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
        if let Some(mut conn) = self.redis.read().await.clone() {
            match conn
                .hget::<_, _, Option<i64>>(online_key(user_id), device_id.to_string())
                .await
            {
                Ok(Some(ts)) => return ts >= unix_now_secs() - ONLINE_TTL_SECS,
                Ok(None) => {}
                Err(err) => tracing::warn!(error = %err, "redis device online check failed"),
            }
        }
        false
    }

    /// Keeps a ringing call's `call.ring` event for `ttl_secs`.
    ///
    /// Human: The event goes out once, to the callee's connected devices. A phone that was
    /// asleep shows "Incoming call"; when its user taps it the app connects, and gets the ring
    /// then (`routes::ws`), so it opens on the ringing call instead of a chat.
    /// Agent: Redis `shroud:ring:{callee}` SET EX when configured (any replica may see the
    /// device connect), else in memory. The reader checks the call still rings.
    pub async fn remember_ring(&self, callee: Uuid, payload: &str, ttl_secs: u64) {
        if let Some(mut conn) = self.redis.read().await.clone() {
            match conn
                .set_ex::<_, _, ()>(format!("{RING_KEY_PREFIX}{callee}"), payload, ttl_secs)
                .await
            {
                Ok(()) => return,
                Err(err) => tracing::warn!(error = %err, "redis ring set failed"),
            }
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
        if let Some(mut conn) = self.redis.read().await.clone() {
            match conn
                .get::<_, Option<String>>(format!("{RING_KEY_PREFIX}{callee}"))
                .await
            {
                Ok(Some(payload)) => return Some(payload),
                Ok(None) => {}
                Err(err) => tracing::warn!(error = %err, "redis ring get failed"),
            }
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
        let Some(mut conn) = self.redis.read().await.clone() else {
            return;
        };
        let key = online_key(user_id);
        let now = unix_now_secs();
        // Human: HASH field = device_id, value = unix ts; EXPIRE bounds crash orphans.
        // Agent: HSET + EXPIRE ONLINE_TTL_SECS; pruned on is_user_online read.
        if let Err(err) = conn
            .hset::<_, _, _, ()>(&key, device_id.to_string(), now)
            .await
        {
            tracing::warn!(error = %err, "redis online hset failed");
            return;
        }
        if let Err(err) = conn.expire::<_, ()>(&key, ONLINE_TTL_SECS).await {
            tracing::warn!(error = %err, "redis online expire failed");
        }
        if let Some(connection) = self.connections.write().await.by_device.get_mut(&device_id)
            && connection.id == connection_id
        {
            connection.online_ts = Some(now);
        }
        self.write_focus(user_id, device_id, connection_id, focused)
            .await;
    }

    async fn write_focus(&self, user_id: Uuid, device_id: Uuid, connection_id: u64, focused: bool) {
        let Some(mut conn) = self.redis.read().await.clone() else {
            return;
        };
        let key = focus_key(user_id);
        let value = format!("{connection_id}:{}", if focused { "1" } else { "0" });
        if let Err(err) = conn
            .hset::<_, _, _, ()>(&key, device_id.to_string(), value)
            .await
        {
            tracing::warn!(error = %err, "redis focus hset failed");
            return;
        }
        if let Err(err) = conn.expire::<_, ()>(&key, ONLINE_TTL_SECS).await {
            tracing::warn!(error = %err, "redis focus expire failed");
        }
    }

    async fn clear_focus(&self, user_id: Uuid, device_id: Uuid, connection_id: u64) {
        let Some(mut conn) = self.redis.read().await.clone() else {
            return;
        };
        if let Err(err) = redis::Script::new(DELETE_FOCUS_IF_OURS)
            .key(focus_key(user_id))
            .arg(device_id.to_string())
            .arg(format!("{connection_id}:"))
            .invoke_async::<i64>(&mut conn)
            .await
        {
            tracing::warn!(error = %err, "redis focus hdel failed");
        }
    }

    /// `Some` when this replica can read an explicit focus flag for the device.
    async fn redis_focus(&self, user_id: Uuid, device_id: Uuid) -> Option<bool> {
        let mut conn = self.redis.read().await.clone()?;
        let value: Option<String> = conn
            .hget(focus_key(user_id), device_id.to_string())
            .await
            .ok()?;
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
        if let Some(mut conn) = self.redis.read().await.clone()
            && let Err(err) = redis::Script::new(DELETE_IF_UNCHANGED)
                .key(online_key(user_id))
                .arg(device_id.to_string())
                .arg(online_ts)
                .invoke_async::<i64>(&mut conn)
                .await
        {
            tracing::warn!(error = %err, "redis online hdel failed");
        }
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

        let Some(mut conn) = self.redis.read().await.clone() else {
            return;
        };
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
        if let Err(err) = conn.publish::<_, _, ()>(&channel, body).await {
            tracing::warn!(error = %err, %user_id, "redis publish failed");
        }
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

        let redis = self.redis.read().await.clone();
        let Some(mut conn) = redis else {
            return;
        };

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
            if let Err(err) = conn.publish::<_, _, ()>(&channel, body).await {
                tracing::warn!(error = %err, %user_id, "redis publish failed");
            }
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
            tracing::warn!(error = %err, %device_id, "redis online prune hdel failed");
        }
    }
    Ok(fresh)
}

/// Spawns a background task that pattern-subscribes and fans out to the local hub.
///
/// Agent: CALLS redis PSUBSCRIBE shroud:user:*, delivered via publish_local_to_users, and
/// SUBSCRIBE shroud:sessions:revoked, applied via close_local_sessions.
pub fn spawn_redis_subscriber(hub: Arc<RealtimeHub>, redis_url: String) {
    tokio::spawn(async move {
        loop {
            if let Err(err) = run_subscriber(hub.clone(), &redis_url).await {
                tracing::error!(error = %err, "redis subscriber stopped; reconnecting in 2s");
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            }
        }
    });
}

async fn run_subscriber(hub: Arc<RealtimeHub>, redis_url: &str) -> Result<(), redis::RedisError> {
    let client = redis::Client::open(redis_url)?;
    let mut pubsub = client.get_async_pubsub().await?;
    pubsub.psubscribe(format!("{USER_CHANNEL_PREFIX}*")).await?;
    pubsub.subscribe(REVOKED_SESSIONS_CHANNEL).await?;
    tracing::info!(
        "redis realtime subscriber listening on shroud:user:* and {REVOKED_SESSIONS_CHANNEL}"
    );

    let mut stream = pubsub.on_message();
    use futures_util::StreamExt;
    while let Some(msg) = stream.next().await {
        let payload: String = match msg.get_payload() {
            Ok(p) => p,
            Err(err) => {
                tracing::warn!(error = %err, "redis message payload decode failed");
                continue;
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
            continue;
        }
        let envelope: RedisFanout = match serde_json::from_str(&payload) {
            Ok(e) => e,
            Err(err) => {
                tracing::warn!(error = %err, "redis fanout envelope invalid");
                continue;
            }
        };
        let event_text = match serde_json::to_string(&envelope.event) {
            Ok(s) => s,
            Err(err) => {
                tracing::warn!(error = %err, "redis event reserialize failed");
                continue;
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
                hub.publish_local_to_users(
                    [envelope.user_id],
                    envelope.except_device_id,
                    &event_text,
                )
                .await;
            }
        }
    }

    Err(redis::RedisError::from((
        redis::ErrorKind::IoError,
        "redis pubsub stream ended",
    )))
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

    #[test]
    fn fanout_envelopes_without_a_device_still_parse() {
        let old = r#"{"user_id":"0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b","except_device_id":null,"event":{"type":"x"}}"#;
        let envelope: RedisFanout = serde_json::from_str(old).expect("old envelope");
        assert!(envelope.only_device_id.is_none());
    }
}
