//! WebSocket fan-out: in-process hub, optional Redis multi-replica pub/sub.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

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
const ONLINE_KEY_PREFIX: &str = "shroud:online:";
/// Redis online hash entries older than this are treated as stale (crash without unsubscribe).
pub const ONLINE_TTL_SECS: i64 = 90;
/// Bounded outbound queue per device — drops events when full (slow-client backpressure).
const OUTBOUND_QUEUE_CAP: usize = 256;
/// Max simultaneous WebSocket connections per user on this replica.
pub const MAX_WS_PER_USER: usize = 5;

fn online_key(user_id: Uuid) -> String {
    format!("{ONLINE_KEY_PREFIX}{user_id}")
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
}

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
        let (tx, rx) = mpsc::channel(OUTBOUND_QUEUE_CAP);
        let id = self.next_connection_id.fetch_add(1, Ordering::Relaxed);
        {
            let mut connections = self.connections.write().await;
            if let Some(set) = connections.devices_by_user.get(&user_id)
                && set.len() >= MAX_WS_PER_USER
                && !set.contains(&device_id)
            {
                return Err("too many websocket connections for this user");
            }
            connections
                .by_device
                .insert(device_id, Connection { id, session_id, tx });
            connections
                .devices_by_user
                .entry(user_id)
                .or_default()
                .insert(device_id);
        }
        self.mark_online(user_id, device_id).await;
        Ok(Subscription { id, events: rx })
    }

    /// Removes a device connection on disconnect.
    ///
    /// Human: Only a socket that still holds its device's entry clears it. After a reconnect
    /// the entry is the newer socket's, and after a revocation it is already gone; clearing
    /// it unconditionally cut the newer socket off.
    /// Agent: NO-OP unless the device's entry is `connection_id`.
    pub async fn unsubscribe(&self, user_id: Uuid, device_id: Uuid, connection_id: u64) {
        {
            let mut connections = self.connections.write().await;
            let current = connections
                .by_device
                .get(&device_id)
                .is_some_and(|connection| connection.id == connection_id);
            if !current {
                return;
            }
            connections.remove(user_id, device_id);
        }
        self.mark_offline(user_id, device_id).await;
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
        let closed: Vec<Uuid> = {
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
            for device_id in &devices {
                connections.remove(user_id, *device_id);
            }
            devices
        };
        for device_id in closed {
            tracing::info!(%user_id, %device_id, "realtime.session_closed");
            self.mark_offline(user_id, device_id).await;
        }
    }

    /// True if the user has at least one online WebSocket (local or Redis online hash).
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
            .is_some_and(|devices| !devices.is_empty())
    }

    /// Refreshes this device's Redis online heartbeat (call from WS loop).
    pub async fn refresh_online(&self, user_id: Uuid, device_id: Uuid) {
        self.mark_online(user_id, device_id).await;
    }

    async fn mark_online(&self, user_id: Uuid, device_id: Uuid) {
        if let Some(mut conn) = self.redis.read().await.clone() {
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
        }
    }

    async fn mark_offline(&self, user_id: Uuid, device_id: Uuid) {
        if let Some(mut conn) = self.redis.read().await.clone() {
            let key = online_key(user_id);
            if let Err(err) = conn.hdel::<_, _, ()>(&key, device_id.to_string()).await {
                tracing::warn!(error = %err, "redis online hdel failed");
            }
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
        hub.publish_local_to_users([envelope.user_id], envelope.except_device_id, &event_text)
            .await;
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
}
