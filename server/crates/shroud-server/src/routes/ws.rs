//! WebSocket endpoint with first-message session auth.

use std::time::Duration;

use axum::extract::State;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::response::IntoResponse;
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::json;
use tokio::time::timeout;

use crate::auth::hash_token;
use crate::error::AppError;
use crate::state::AppState;

const AUTH_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Deserialize)]
struct ClientMessage {
    r#type: String,
    token: Option<String>,
}

/// `GET /ws` — upgrade to WebSocket.
pub async fn ws_upgrade(ws: WebSocketUpgrade, State(state): State<AppState>) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, state))
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

    let mut rx = state.realtime.subscribe(user_id, device_id).await;

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
            inbound = stream.next() => {
                match inbound {
                    Some(Ok(Message::Close(_))) | None => break,
                    Some(Ok(Message::Ping(data))) => {
                        let _ = sink.send(Message::Pong(data)).await;
                    }
                    Some(Ok(Message::Text(_))) | Some(Ok(Message::Binary(_))) | Some(Ok(Message::Pong(_))) => {
                        // Ignore client messages after auth (typing later).
                    }
                    Some(Err(_)) => break,
                }
            }
        }
    }

    state.realtime.unsubscribe(user_id, device_id).await;
    tracing::info!(%user_id, %device_id, "ws.disconnected");
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

    let token_hash = hash_token(&token);

    #[derive(sqlx::FromRow)]
    struct AuthIds {
        user_id: uuid::Uuid,
        device_id: uuid::Uuid,
    }

    let row = sqlx::query_as::<_, AuthIds>(
        r#"
        SELECT u.id AS user_id, d.id AS device_id
        FROM sessions s
        INNER JOIN devices d ON d.id = s.device_id
        INNER JOIN users u ON u.id = d.user_id
        WHERE s.token_hash = $1 AND s.revoked_at IS NULL
        "#,
    )
    .bind(token_hash.as_slice())
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("ws auth lookup failed: {err}")))?;

    row.map(|ids| (ids.user_id, ids.device_id))
        .ok_or_else(AppError::unauthorized)
}
