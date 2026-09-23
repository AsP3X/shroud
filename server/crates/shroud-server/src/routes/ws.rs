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

use crate::auth::session::ids_for_token;
use crate::error::AppError;
use crate::rate_limit::budgets;
use crate::routes::contacts::are_contacts;
use crate::routes::presence::{max_last_seen, notify_presence_to_contacts, touch_device_last_seen};
use crate::state::AppState;

const AUTH_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Deserialize)]
struct ClientMessage {
    r#type: String,
    token: Option<String>,
    peer_user_id: Option<Uuid>,
    is_typing: Option<bool>,
    is_recording: Option<bool>,
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

    let (user_id, device_id) = match auth {
        Ok(Ok(ids)) => ids,
        Ok(Err(err)) => {
            tracing::warn!(error = %err, "ws.auth failed");
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

    let mut rx = match state.realtime.subscribe(user_id, device_id).await {
        Ok(rx) => rx,
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

    // Human: Refresh Redis online TTL while the socket is alive so crashes expire cleanly.
    // Agent: CALLS realtime.refresh_online every 30s; TTL is ONLINE_TTL_SECS (90).
    let mut online_heartbeat = tokio::time::interval(Duration::from_secs(30));
    online_heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    online_heartbeat.tick().await;

    loop {
        tokio::select! {
            outbound = rx.recv() => {
                match outbound {
                    Some(payload) => {
                        if sink.send(Message::Text(payload.into())).await.is_err() {
                            break;
                        }
                    }
                    None => break,
                }
            }
            _ = online_heartbeat.tick() => {
                state.realtime.refresh_online(user_id, device_id).await;
            }
            inbound = stream.next() => {
                match inbound {
                    Some(Ok(Message::Close(_))) | None => break,
                    Some(Ok(Message::Ping(data))) => {
                        let _ = sink.send(Message::Pong(data)).await;
                    }
                    Some(Ok(Message::Text(text))) => {
                        handle_client_text(&state, user_id, device_id, &text).await;
                    }
                    Some(Ok(Message::Binary(_))) | Some(Ok(Message::Pong(_))) => {}
                    Some(Err(_)) => break,
                }
            }
        }
    }

    state.realtime.unsubscribe(user_id, device_id).await;

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

async fn handle_client_text(state: &AppState, user_id: Uuid, device_id: Uuid, text: &str) {
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
        other => {
            tracing::debug!(%user_id, r#type = other, "ws.client unknown type");
        }
    }
}

/// Relays an ephemeral activity frame (typing, recording) to the peer's devices only.
/// Contacts required; never echoed to the sender; never stored.
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

async fn authenticate_text(
    state: &AppState,
    text: &str,
) -> Result<(uuid::Uuid, uuid::Uuid), AppError> {
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
