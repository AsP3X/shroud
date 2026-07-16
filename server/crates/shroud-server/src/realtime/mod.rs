//! WebSocket fan-out: in-process hub, optional Redis multi-replica pub/sub.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use redis::AsyncCommands;
use redis::aio::ConnectionManager;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::{RwLock, mpsc};
use uuid::Uuid;

/// Per-device outbound event channel (JSON text frames).
type DeviceTx = mpsc::UnboundedSender<String>;

const USER_CHANNEL_PREFIX: &str = "shroud:user:";
const ONLINE_KEY_PREFIX: &str = "shroud:online:";

fn online_key(user_id: Uuid) -> String {
    format!("{ONLINE_KEY_PREFIX}{user_id}")
}

/// Shared connection hub keyed by device (and indexed by user).
#[derive(Default)]
pub struct RealtimeHub {
    by_device: RwLock<HashMap<Uuid, DeviceTx>>,
    devices_by_user: RwLock<HashMap<Uuid, HashSet<Uuid>>>,
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

impl RealtimeHub {
    pub fn new() -> Self {
        Self::default()
    }

    /// Attaches a Redis connection manager for multi-replica publish.
    pub async fn set_redis(&self, manager: ConnectionManager) {
        *self.redis.write().await = Some(manager);
    }

    /// Registers a device connection; returns the receiver for WS write loop.
    pub async fn subscribe(
        self: &Arc<Self>,
        user_id: Uuid,
        device_id: Uuid,
    ) -> mpsc::UnboundedReceiver<String> {
        let (tx, rx) = mpsc::unbounded_channel();
        {
            let mut by_device = self.by_device.write().await;
            by_device.insert(device_id, tx);
        }
        {
            let mut by_user = self.devices_by_user.write().await;
            by_user.entry(user_id).or_default().insert(device_id);
        }
        self.mark_online(user_id, device_id).await;
        rx
    }

    /// Removes a device connection (on disconnect or replace).
    pub async fn unsubscribe(&self, user_id: Uuid, device_id: Uuid) {
        {
            let mut by_device = self.by_device.write().await;
            by_device.remove(&device_id);
        }
        let mut by_user = self.devices_by_user.write().await;
        if let Some(set) = by_user.get_mut(&user_id) {
            set.remove(&device_id);
            if set.is_empty() {
                by_user.remove(&user_id);
            }
        }
        self.mark_offline(user_id, device_id).await;
    }

    /// True if the user has at least one online WebSocket (local or Redis online set).
    pub async fn is_user_online(&self, user_id: Uuid) -> bool {
        if let Some(mut conn) = self.redis.read().await.clone() {
            let key = online_key(user_id);
            match conn.scard::<_, u64>(&key).await {
                Ok(n) if n > 0 => return true,
                Ok(_) => {}
                Err(err) => tracing::warn!(error = %err, "redis online scard failed"),
            }
        }
        let by_user = self.devices_by_user.read().await;
        by_user
            .get(&user_id)
            .is_some_and(|devices| !devices.is_empty())
    }

    async fn mark_online(&self, user_id: Uuid, device_id: Uuid) {
        if let Some(mut conn) = self.redis.read().await.clone() {
            let key = online_key(user_id);
            if let Err(err) = conn.sadd::<_, _, ()>(&key, device_id.to_string()).await {
                tracing::warn!(error = %err, "redis online sadd failed");
            }
        }
    }

    async fn mark_offline(&self, user_id: Uuid, device_id: Uuid) {
        if let Some(mut conn) = self.redis.read().await.clone() {
            let key = online_key(user_id);
            if let Err(err) = conn.srem::<_, _, ()>(&key, device_id.to_string()).await {
                tracing::warn!(error = %err, "redis online srem failed");
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
        let by_user = self.devices_by_user.read().await;
        let by_device = self.by_device.read().await;
        for user_id in user_ids {
            if let Some(devices) = by_user.get(&user_id) {
                for device_id in devices {
                    if except_device == Some(*device_id) {
                        continue;
                    }
                    if let Some(tx) = by_device.get(device_id) {
                        let _ = tx.send(payload.to_string());
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
            let by_user = self.devices_by_user.read().await;
            users
                .iter()
                .filter_map(|uid| by_user.get(uid).map(|set| set.len()))
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

/// Spawns a background task that pattern-subscribes and fans out to the local hub.
///
/// Agent: CALLS redis PSUBSCRIBE shroud:user:*; delivers via publish_local_to_users.
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
    tracing::info!("redis realtime subscriber listening on shroud:user:*");

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
