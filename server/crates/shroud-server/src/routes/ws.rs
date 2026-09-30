//! WebSocket endpoint with first-message session auth, typing, recording, and presence.

use std::time::Duration;

use axum::extract::State;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::http::HeaderMap;
use axum::response::IntoResponse;
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::json;
use tokio::time::timeout;
use uuid::Uuid;

use crate::auth::session::{AuthIds, SessionState, ids_for_token, session_state};
use crate::error::AppError;
use crate::rate_limit::budgets;
use crate::realtime::Subscription;
use crate::routes::contacts::are_contacts;
use crate::routes::presence::{max_last_seen, notify_presence_to_contacts, touch_device_last_seen};
use crate::routes::privacy::{Visibility, both_allow};
use crate::state::AppState;

const AUTH_TIMEOUT: Duration = Duration::from_secs(10);
/// Time allowed to tell a revoked socket why it is being closed.
const REVOKED_CLOSE_TIMEOUT: Duration = Duration::from_secs(5);
/// How often the server pings; browsers and URLSession answer pings on their own.
const PING_INTERVAL: Duration = Duration::from_secs(30);
/// A socket that sent nothing (not even a pong) this long belongs to a device that slept or
/// lost its network. It is closed, so the device counts as offline and gets pushes again.
const IDLE_TIMEOUT: Duration = Duration::from_secs(75);
/// A write the peer does not take within this is a dead socket: a full send buffer would
/// otherwise block the loop, idle close included, until TCP gave up many minutes later.
const SEND_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Deserialize)]
struct ClientMessage {
    r#type: String,
    token: Option<String>,
    peer_user_id: Option<Uuid>,
    is_typing: Option<bool>,
    is_recording: Option<bool>,
    /// `focus`: this app is in front (`true`) or has left (`false`).
    focused: Option<bool>,
}

/// `GET /ws` — upgrade to WebSocket.
pub async fn ws_upgrade(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, AppError> {
    let ip = state.client_ip(&headers);
    state
        .rate_limiter
        .check_budget("ws_ip", &ip, budgets::WS_CONNECT_IP)
        .await?;
    Ok(ws.on_upgrade(move |socket| handle_socket(socket, state)))
}

async fn handle_socket(socket: WebSocket, state: AppState) {
    let (mut sink, mut stream) = socket.split();

    // Human: Require auth within 10s so unauthenticated connections cannot idle.
    let auth = timeout(AUTH_TIMEOUT, async {
        while let Some(Ok(msg)) = stream.next().await {
            match msg {
                Message::Text(text) => {
                    return authenticate_text(&state, &text).await;
                }
                Message::Close(_) => return Err(AppError::unauthorized()),
                _ => {}
            }
        }
        Err(AppError::unauthorized())
    })
    .await;

    let AuthIds {
        user_id,
        device_id,
        session_id,
    } = match auth {
        Ok(Ok(ids)) => ids,
        Ok(Err(err)) => {
            tracing::warn!(error = %err, "ws.auth failed");
            // A removed device is told so, and wipes itself; anything else stays a plain
            // UNAUTHORIZED, as clients have always seen it.
            let removed = AppError::device_removed();
            let error = if err.code() == removed.code() {
                removed.body().error
            } else {
                AppError::unauthorized().body().error
            };
            let _ = sink
                .send(Message::Text(
                    json!({ "type": "auth.error", "error": error })
                        .to_string()
                        .into(),
                ))
                .await;
            let _ = sink.close().await;
            return;
        }
        Err(_) => {
            tracing::warn!("ws.auth timeout");
            let _ = sink
                .send(Message::Text(
                    json!({
                        "type": "auth.error",
                        "error": { "code": "UNAUTHORIZED", "message": "Authentication required." }
                    })
                    .to_string()
                    .into(),
                ))
                .await;
            let _ = sink.close().await;
            return;
        }
    };

    tracing::info!(%user_id, %device_id, "ws.connected");

    let ok = json!({
        "type": "auth.ok",
        "user_id": user_id,
        "device_id": device_id,
    });
    if sink
        .send(Message::Text(ok.to_string().into()))
        .await
        .is_err()
    {
        tracing::warn!(%user_id, %device_id, "ws.auth.ok send failed");
        return;
    }

    let Subscription {
        id: connection_id,
        events: mut rx,
    } = match state
        .realtime
        .subscribe(user_id, device_id, session_id)
        .await
    {
        Ok(subscription) => subscription,
        Err(reason) => {
            tracing::warn!(%user_id, %device_id, %reason, "ws.subscribe rejected");
            let _ = sink
                .send(Message::Text(
                    json!({
                        "type": "auth.error",
                        "error": {
                            "code": "RATE_LIMITED",
                            "message": "Too many WebSocket connections for this account."
                        }
                    })
                    .to_string()
                    .into(),
                ))
                .await;
            let _ = sink.close().await;
            return;
        }
    };

    // Touch last_seen and announce online to contacts.
    if let Ok(last_seen) = touch_device_last_seen(&state.pool, device_id).await {
        notify_presence_to_contacts(&state, user_id, true, Some(last_seen)).await;
    }

    // Human: A call still ringing for this user reaches a device that connects now — its user
    // tapped "Incoming call" — so the app opens on the ringing call. A device that was
    // connected already has it; the apps ignore a ring they know.
    if let Some(ring) = crate::routes::calls::ring_to_replay(&state, user_id).await {
        let _ = timeout(SEND_TIMEOUT, sink.send(Message::Text(ring.into()))).await;
    }

    // Human: Refresh Redis online TTL while the socket is alive so crashes expire cleanly, and
    // re-check the session: a revocation this replica never heard about still closes it. The
    // first tick fires at once, because a revocation that committed between the token lookup
    // and subscribe found no socket to close.
    // Agent: CALLS session_state + refresh_online now, then every 30s; TTL is ONLINE_TTL_SECS.
    let mut online_heartbeat = tokio::time::interval(Duration::from_secs(30));
    online_heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    // Human: A phone the OS suspended keeps its TCP connection open for a long time, and the
    // server saw it online all that while — no push. Liveness is now what the device answers.
    // Agent: Ping every PING_INTERVAL; close IDLE_TIMEOUT after the last frame that arrived.
    let mut ping = tokio::time::interval(PING_INTERVAL);
    ping.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    ping.tick().await;
    let idle = tokio::time::sleep(IDLE_TIMEOUT);
    tokio::pin!(idle);

    // Set when the session ended while the socket was open: signed out, or removed.
    let mut ended: Option<SessionState> = None;
    loop {
        tokio::select! {
            outbound = rx.recv() => {
                match outbound {
                    Some(payload) => {
                        let sent = timeout(SEND_TIMEOUT, sink.send(Message::Text(payload.into()))).await;
                        if !matches!(sent, Ok(Ok(()))) {
                            break;
                        }
                    }
                    // The hub let go of this socket: its session was revoked, or the device
                    // opened a newer socket.
                    None => {
                        ended = session_state(&state.pool, session_id)
                            .await
                            .ok()
                            .filter(|state| *state != SessionState::Live);
                        break;
                    }
                }
            }
            _ = online_heartbeat.tick() => {
                match session_state(&state.pool, session_id).await {
                    Ok(SessionState::Live) => {}
                    Ok(over) => {
                        ended = Some(over);
                        break;
                    }
                    Err(err) => tracing::warn!(error = %err, %device_id, "ws.session check failed"),
                }
                state
                    .realtime
                    .refresh_online(user_id, device_id, connection_id)
                    .await;
            }
            _ = ping.tick() => {
                let sent = timeout(SEND_TIMEOUT, sink.send(Message::Ping(Vec::new().into()))).await;
                if !matches!(sent, Ok(Ok(()))) {
                    break;
                }
            }
            _ = &mut idle => {
                tracing::info!(%user_id, %device_id, "ws.idle_timeout");
                break;
            }
            inbound = stream.next() => {
                idle.as_mut().reset(tokio::time::Instant::now() + IDLE_TIMEOUT);
                match inbound {
                    Some(Ok(Message::Close(_))) | None => break,
                    Some(Ok(Message::Ping(data))) => {
                        if timeout(SEND_TIMEOUT, sink.send(Message::Pong(data))).await.is_err() {
                            break;
                        }
                    }
                    Some(Ok(Message::Text(text))) => {
                        handle_client_text(&state, user_id, device_id, connection_id, &text).await;
                    }
                    Some(Ok(Message::Binary(_))) | Some(Ok(Message::Pong(_))) => {}
                    Some(Err(_)) => break,
                }
            }
        }
    }

    if let Some(over) = ended {
        tracing::info!(%user_id, %device_id, ?over, "ws.session_revoked");
        // Clients take auth.error as final: they stop reconnecting with the dead token, and
        // the web client signs out. DEVICE_REMOVED also makes the iPhone wipe itself now.
        let error = if over == SessionState::Removed {
            json!(AppError::device_removed().body().error)
        } else {
            json!({ "code": "UNAUTHORIZED", "message": "This session was signed out." })
        };
        let _ = timeout(REVOKED_CLOSE_TIMEOUT, async {
            let _ = sink
                .send(Message::Text(
                    json!({ "type": "auth.error", "error": error })
                        .to_string()
                        .into(),
                ))
                .await;
            let _ = sink.close().await;
        })
        .await;
    }

    state
        .realtime
        .unsubscribe(user_id, device_id, connection_id)
        .await;

    // Update last_seen; only announce offline if no remaining sessions for this user.
    let last_seen = touch_device_last_seen(&state.pool, device_id).await.ok();
    let still_online = state.realtime.is_user_online(user_id).await;
    if !still_online {
        let last = match last_seen {
            Some(ts) => Some(ts),
            None => max_last_seen(&state.pool, user_id).await.ok().flatten(),
        };
        notify_presence_to_contacts(&state, user_id, false, last).await;
    }

    tracing::info!(%user_id, %device_id, "ws.disconnected");
}

async fn handle_client_text(
    state: &AppState,
    user_id: Uuid,
    device_id: Uuid,
    connection_id: u64,
    text: &str,
) {
    let parsed: ClientMessage = match serde_json::from_str(text) {
        Ok(m) => m,
        Err(_) => {
            tracing::debug!(%user_id, "ws.client invalid json");
            return;
        }
    };

    match parsed.r#type.as_str() {
        "typing" => {
            let Some(peer_user_id) = parsed.peer_user_id else {
                tracing::debug!(%user_id, "ws.typing missing peer_user_id");
                return;
            };
            relay_contact_activity(
                state,
                user_id,
                device_id,
                peer_user_id,
                "typing",
                json!({
                    "type": "typing",
                    "is_typing": parsed.is_typing.unwrap_or(true),
                }),
            )
            .await;
        }
        "recording" => {
            let Some(peer_user_id) = parsed.peer_user_id else {
                tracing::debug!(%user_id, "ws.recording missing peer_user_id");
                return;
            };
            relay_contact_activity(
                state,
                user_id,
                device_id,
                peer_user_id,
                "recording",
                json!({
                    "type": "recording",
                    "is_recording": parsed.is_recording.unwrap_or(true),
                }),
            )
            .await;
        }
        "auth" => {
            // Already authenticated; ignore duplicate auth frames.
        }
        "focus" => {
            let Some(focused) = parsed.focused else {
                tracing::debug!(%user_id, "ws.focus missing focused");
                return;
            };
            state
                .realtime
                .set_focus(user_id, device_id, connection_id, focused)
                .await;
        }
        other => {
            tracing::debug!(%user_id, r#type = other, "ws.client unknown type");
        }
    }
}

/// Relays an ephemeral activity frame (typing, recording) to the peer's devices only.
/// Contacts required, and both must allow typing indicators (`users.send_typing`); never
/// echoed to the sender; never stored.
async fn relay_contact_activity(
    state: &AppState,
    user_id: Uuid,
    device_id: Uuid,
    peer_user_id: Uuid,
    kind: &str,
    mut event: serde_json::Value,
) {
    if peer_user_id == user_id {
        return;
    }

    match are_contacts(&state.pool, user_id, peer_user_id).await {
        Ok(true) => {}
        Ok(false) => {
            tracing::debug!(%user_id, %peer_user_id, kind, "ws.activity not contacts");
            return;
        }
        Err(err) => {
            tracing::warn!(error = %err, kind, "ws.activity contacts check failed");
            return;
        }
    }
    match both_allow(&state.pool, user_id, peer_user_id, Visibility::Typing).await {
        Ok(true) => {}
        Ok(false) => return,
        Err(err) => {
            tracing::warn!(error = %err, kind, "ws.activity privacy check failed");
            return;
        }
    }

    event["user_id"] = json!(user_id);
    event["device_id"] = json!(device_id);
    event["peer_user_id"] = json!(peer_user_id);
    if let Ok(payload) = serde_json::to_string(&event) {
        // Only the peer needs the indicator; exclude our own devices by not listing self.
        state
            .realtime
            .publish_to_users([peer_user_id], None, &payload)
            .await;
    }
}

async fn authenticate_text(state: &AppState, text: &str) -> Result<AuthIds, AppError> {
    let parsed: ClientMessage = serde_json::from_str(text)
        .map_err(|_| AppError::validation("Invalid WebSocket auth message."))?;
    if parsed.r#type != "auth" {
        return Err(AppError::validation("First message must be type auth."));
    }
    let token = parsed
        .token
        .filter(|value| !value.is_empty())
        .ok_or_else(AppError::unauthorized)?;
    ids_for_token(&state.pool, &token).await
}
