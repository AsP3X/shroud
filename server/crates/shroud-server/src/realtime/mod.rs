//! In-process WebSocket fan-out (single API instance).

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use tokio::sync::{RwLock, mpsc};
use uuid::Uuid;

/// Per-device outbound event channel (JSON text frames).
type DeviceTx = mpsc::UnboundedSender<String>;

/// Shared connection hub keyed by device (and indexed by user).
#[derive(Debug, Default)]
pub struct RealtimeHub {
    by_device: RwLock<HashMap<Uuid, DeviceTx>>,
    devices_by_user: RwLock<HashMap<Uuid, HashSet<Uuid>>>,
}

impl RealtimeHub {
    pub fn new() -> Self {
        Self::default()
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
    }

    /// Sends a JSON text event to specific devices if they are online.
    pub async fn publish_to_devices(
        &self,
        device_ids: impl IntoIterator<Item = Uuid>,
        payload: &str,
    ) {
        let by_device = self.by_device.read().await;
        for device_id in device_ids {
            if let Some(tx) = by_device.get(&device_id) {
                let _ = tx.send(payload.to_string());
            }
        }
    }

    /// Sends to all online devices of the given users, optionally skipping one device.
    pub async fn publish_to_users(
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
}
