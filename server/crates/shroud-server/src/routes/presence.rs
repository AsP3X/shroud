//! Online / last-seen presence (accepted contacts only, and only while both share it).

use axum::{
    Json,
    extract::{Path, State},
};
use chrono::{DateTime, Utc};
use serde::Serialize;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::routes::contacts::{are_contacts, is_blocked_either_way, list_contact_user_ids};
use crate::routes::privacy::{Visibility, allowing, both_allow};
use crate::state::AppState;

#[derive(Debug, Serialize)]
pub struct PresenceResponse {
    pub user_id: Uuid,
    pub online: bool,
    /// Max `devices.last_seen_at` for the user; null if never seen.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_seen_at: Option<DateTime<Utc>>,
}

/// `GET /presence/:user_id` — contacts only.
pub async fn get_presence(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(user_id): Path<Uuid>,
) -> Result<Json<PresenceResponse>, AppError> {
    if user_id == auth.user_id {
        let last_seen_at = max_last_seen(&state.pool, user_id).await?;
        let online = state.realtime.is_user_online(user_id).await;
        return Ok(Json(PresenceResponse {
            user_id,
            online,
            last_seen_at,
        }));
    }

    if !are_contacts(&state.pool, auth.user_id, user_id).await?
        || is_blocked_either_way(&state.pool, auth.user_id, user_id).await?
    {
        return Err(AppError::forbidden(
            "Presence is only visible to accepted contacts.",
        ));
    }

    // Hidden looks exactly like a contact who is offline and was never seen: the viewer can't
    // tell "hides it from me" apart from "hides it from everyone" or "stopped sharing myself".
    if !both_allow(&state.pool, auth.user_id, user_id, Visibility::Presence).await? {
        return Ok(Json(PresenceResponse {
            user_id,
            online: false,
            last_seen_at: None,
        }));
    }

    let last_seen_at = max_last_seen(&state.pool, user_id).await?;
    let online = state.realtime.is_user_online(user_id).await;

    tracing::debug!(
        viewer = %auth.user_id,
        target = %user_id,
        online,
        "presence.get"
    );

    Ok(Json(PresenceResponse {
        user_id,
        online,
        last_seen_at,
    }))
}

/// Max device last_seen for a user.
pub(crate) async fn max_last_seen(
    pool: &sqlx::PgPool,
    user_id: Uuid,
) -> Result<Option<DateTime<Utc>>, AppError> {
    sqlx::query_scalar(
        r#"
        SELECT MAX(last_seen_at) FROM devices WHERE user_id = $1
        "#,
    )
    .bind(user_id)
    .fetch_one(pool)
    .await
    .map_err(|err| AppError::Internal(format!("last_seen lookup failed: {err}")))
}

/// Touch device last_seen (WS connect / disconnect / activity).
pub(crate) async fn touch_device_last_seen(
    pool: &sqlx::PgPool,
    device_id: Uuid,
) -> Result<DateTime<Utc>, AppError> {
    let now = Utc::now();
    sqlx::query(
        r#"
        UPDATE devices SET last_seen_at = $1 WHERE id = $2
        "#,
    )
    .bind(now)
    .bind(device_id)
    .execute(pool)
    .await
    .map_err(|err| AppError::Internal(format!("touch last_seen failed: {err}")))?;
    Ok(now)
}

/// Fan-out `presence.update` to accepted contacts (online sockets only via hub) — nobody at all
/// when `user_id` hides presence, and only the contacts who share theirs otherwise.
pub(crate) async fn notify_presence_to_contacts(
    state: &AppState,
    user_id: Uuid,
    online: bool,
    last_seen_at: Option<DateTime<Utc>>,
) {
    let recipients = match presence_audience(state, user_id).await {
        Ok(Some(ids)) => ids,
        Ok(None) => return,
        Err(err) => {
            tracing::warn!(error = %err, %user_id, "presence notify: audience lookup failed");
            return;
        }
    };
    publish_presence(state, recipients, user_id, online, last_seen_at).await;
}

/// Tells contacts right away that `user_id` started or stopped sharing presence.
///
/// Stopping reaches every contact, so none keeps showing a stale "online"; starting again
/// reaches only the contacts who share theirs (the rest would be told nothing anyway).
pub(crate) async fn announce_presence_setting(state: &AppState, user_id: Uuid, sharing: bool) {
    if sharing {
        let online = state.realtime.is_user_online(user_id).await;
        let last_seen_at = max_last_seen(&state.pool, user_id).await.ok().flatten();
        notify_presence_to_contacts(state, user_id, online, last_seen_at).await;
        return;
    }
    match list_contact_user_ids(&state.pool, user_id).await {
        Ok(contacts) => publish_presence(state, contacts, user_id, false, None).await,
        Err(err) => tracing::warn!(error = %err, %user_id, "presence hide: list contacts failed"),
    }
}

/// Contacts allowed to hear `user_id`'s presence; None when the user hides it.
async fn presence_audience(state: &AppState, user_id: Uuid) -> Result<Option<Vec<Uuid>>, AppError> {
    if allowing(&state.pool, &[user_id], Visibility::Presence)
        .await?
        .is_empty()
    {
        return Ok(None);
    }
    let contacts = list_contact_user_ids(&state.pool, user_id).await?;
    Ok(Some(
        allowing(&state.pool, &contacts, Visibility::Presence).await?,
    ))
}

async fn publish_presence(
    state: &AppState,
    recipients: Vec<Uuid>,
    user_id: Uuid,
    online: bool,
    last_seen_at: Option<DateTime<Utc>>,
) {
    if recipients.is_empty() {
        return;
    }

    let event = serde_json::json!({
        "type": "presence.update",
        "user_id": user_id,
        "online": online,
        "last_seen_at": last_seen_at,
    });
    let Ok(payload) = serde_json::to_string(&event) else {
        return;
    };

    state
        .realtime
        .publish_to_users(recipients, None, &payload)
        .await;
}

/// Least time between two `presence.update` fan-outs for one user (security audit M5).
pub const PRESENCE_INTERVAL: Duration = Duration::from_secs(2);

/// Entries kept before old ones are swept.
const PRESENCE_SWEEP_AT: usize = 4096;

/// Spaces out each user's presence fan-out.
///
/// Human: A socket that flips `focus` as fast as its frame budget allows would otherwise send a
/// `presence.update` to every contact each time, filling their queues until their real events
/// are dropped. The first change in an interval is told at once; the rest are folded into one
/// update at the interval's end that tells the state as it is then, so contacts never keep a
/// stale "online". Per replica: each one spaces out the changes its own sockets make.
/// Agent: `admit` → Now (announce), Later(delay) (announce the current state after `delay`,
/// then `release`), or Folded (one is already scheduled).
#[derive(Debug)]
pub struct PresenceThrottle {
    interval: Duration,
    users: Mutex<HashMap<Uuid, ThrottleSlot>>,
}

#[derive(Debug)]
struct ThrottleSlot {
    last: Instant,
    scheduled: bool,
}

#[derive(Debug, PartialEq, Eq)]
pub enum PresenceTurn {
    Now,
    Later(Duration),
    Folded,
}

impl PresenceThrottle {
    pub fn new(interval: Duration) -> Self {
        Self {
            interval,
            users: Mutex::new(HashMap::new()),
        }
    }

    pub fn admit(&self, user_id: Uuid) -> PresenceTurn {
        let now = Instant::now();
        let mut users = self
            .users
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if users.len() >= PRESENCE_SWEEP_AT {
            let interval = self.interval;
            users.retain(|_, slot| slot.scheduled || now.duration_since(slot.last) < interval);
        }
        match users.get_mut(&user_id) {
            Some(slot) if slot.scheduled => PresenceTurn::Folded,
            Some(slot) if now.duration_since(slot.last) < self.interval => {
                slot.scheduled = true;
                PresenceTurn::Later(self.interval - now.duration_since(slot.last))
            }
            _ => {
                users.insert(
                    user_id,
                    ThrottleSlot {
                        last: now,
                        scheduled: false,
                    },
                );
                PresenceTurn::Now
            }
        }
    }

    /// The scheduled update is going out now; it starts the next interval.
    pub fn release(&self, user_id: Uuid) {
        let mut users = self
            .users
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        users.insert(
            user_id,
            ThrottleSlot {
                last: Instant::now(),
                scheduled: false,
            },
        );
    }
}

/// Tells contacts whether `user_id` is online, at most once per [`PRESENCE_INTERVAL`].
///
/// `online` and `last_seen_at` describe the change that asked; a change folded into a later
/// update is told as the state at that time instead.
pub(crate) async fn announce_presence(
    state: &AppState,
    user_id: Uuid,
    online: bool,
    last_seen_at: Option<DateTime<Utc>>,
) {
    match state.presence_throttle.admit(user_id) {
        PresenceTurn::Now => {
            notify_presence_to_contacts(state, user_id, online, last_seen_at).await
        }
        PresenceTurn::Later(delay) => {
            let state = state.clone();
            tokio::spawn(async move {
                tokio::time::sleep(delay).await;
                state.presence_throttle.release(user_id);
                let online = state.realtime.is_user_online(user_id).await;
                let last_seen_at = max_last_seen(&state.pool, user_id).await.ok().flatten();
                notify_presence_to_contacts(&state, user_id, online, last_seen_at).await;
            });
        }
        PresenceTurn::Folded => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn throttle_tells_the_first_change_and_folds_the_rest() {
        let throttle = PresenceThrottle::new(Duration::from_secs(60));
        let user = Uuid::new_v4();
        assert_eq!(throttle.admit(user), PresenceTurn::Now);
        assert!(
            matches!(throttle.admit(user), PresenceTurn::Later(delay) if delay <= Duration::from_secs(60))
        );
        assert_eq!(throttle.admit(user), PresenceTurn::Folded);
        assert_eq!(throttle.admit(Uuid::new_v4()), PresenceTurn::Now);
        throttle.release(user);
        assert!(matches!(throttle.admit(user), PresenceTurn::Later(_)));
    }

    #[test]
    fn throttle_tells_at_once_after_a_quiet_interval() {
        let throttle = PresenceThrottle::new(Duration::ZERO);
        let user = Uuid::new_v4();
        assert_eq!(throttle.admit(user), PresenceTurn::Now);
        assert_eq!(throttle.admit(user), PresenceTurn::Now);
    }
}
