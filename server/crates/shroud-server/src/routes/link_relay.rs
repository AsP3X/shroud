//! `GET /link-relay` — the WebSocket byte pipe behind the web client's link previews.
//!
//! Human: The browser builds a preview the way the iPhone does — it fetches the page itself —
//! but it can only reach other websites through this pipe, and it runs TLS end to end
//! (rustls in WebAssembly), so the relay sees encrypted bytes and the host name, never the
//! link's path or the page. Protocol:
//! 1. Within 10 s the client sends one text frame:
//!    `{"type":"connect","token":"<session token>","host":"example.com"}`.
//! 2. The server answers `{"type":"connected"}` and from then on binary frames carry TLS bytes
//!    both ways — or `{"type":"error","error":{"code","message"}}` and closes.
//!
//! Agent: AUTH via first frame (`ids_for_token`); RATE LIMITS per IP (upgrade) and per user
//! (connect); at most `MAX_CONCURRENT_PER_USER` open pipes per account; byte caps both ways;
//! idle 15 s / lifetime 45 s. NEVER logs the host or any payload byte.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use axum::extract::State;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::http::{HeaderMap, StatusCode};
use axum::response::IntoResponse;
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::{Instant, timeout, timeout_at};
use tokio_util::bytes::BytesMut;
use uuid::Uuid;

use crate::auth::session::ids_for_token;
use crate::error::{AppError, ErrorBody};
use crate::link_relay::{self, RelayPolicy, RelayRejection};
use crate::rate_limit::budgets;
use crate::state::AppState;

/// Time allowed for the `connect` frame.
const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
/// A pipe with no bytes either way for this long is closed.
const IDLE_TIMEOUT: Duration = Duration::from_secs(15);
/// Hard end of every pipe.
const MAX_LIFETIME: Duration = Duration::from_secs(45);
/// Browser → website: a TLS handshake and one small GET request.
const MAX_UPSTREAM_BYTES: usize = 64 * 1024;
/// Website → browser: a certificate chain plus a page head (≤ 512 KB) or an image (≤ 5 MB).
const MAX_DOWNSTREAM_BYTES: usize = 8 * 1024 * 1024;
/// Largest binary frame sent to the browser.
const READ_CHUNK: usize = 16 * 1024;
/// Pipes one account may hold open at once (a preview needs the page, then its image).
pub const MAX_CONCURRENT_PER_USER: u32 = 6;

/// Relay-wide state: where pipes may go, and how many each account has open.
#[derive(Debug)]
pub struct LinkRelay {
    policy: RelayPolicy,
    active: Mutex<HashMap<Uuid, u32>>,
}

impl LinkRelay {
    pub fn new(policy: RelayPolicy) -> Self {
        Self {
            policy,
            active: Mutex::new(HashMap::new()),
        }
    }

    /// Claims one of the account's pipe slots; `None` when all are in use.
    fn acquire(self: &Arc<Self>, user_id: Uuid) -> Option<PipeSlot> {
        let mut active = self.active.lock().unwrap_or_else(PoisonError::into_inner);
        let count = active.entry(user_id).or_insert(0);
        if *count >= MAX_CONCURRENT_PER_USER {
            return None;
        }
        *count += 1;
        Some(PipeSlot {
            relay: Arc::clone(self),
            user_id,
        })
    }
}

/// An open pipe's slot; releases itself when the pipe ends, however it ends.
struct PipeSlot {
    relay: Arc<LinkRelay>,
    user_id: Uuid,
}

impl Drop for PipeSlot {
    fn drop(&mut self) {
        let mut active = self
            .relay
            .active
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(count) = active.get_mut(&self.user_id) {
            *count = count.saturating_sub(1);
            if *count == 0 {
                active.remove(&self.user_id);
            }
        }
    }
}

#[derive(Debug, Deserialize)]
struct ConnectFrame {
    r#type: String,
    token: Option<String>,
    host: Option<String>,
}

/// `GET /link-relay` — upgrade to the relay WebSocket.
pub async fn link_relay_upgrade(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, AppError> {
    let ip = state.client_ip(&headers);
    state
        .rate_limiter
        .check_budget("link_relay_ip", &ip, budgets::LINK_RELAY_IP)
        .await?;
    Ok(ws.on_upgrade(move |socket| relay_socket(socket, state)))
}

async fn relay_socket(mut socket: WebSocket, state: AppState) {
    let opened = match timeout(HELLO_TIMEOUT, open_target(&mut socket, &state)).await {
        Ok(Ok(opened)) => opened,
        Ok(Err(err)) => {
            refuse(&mut socket, &err).await;
            return;
        }
        Err(_) => {
            refuse(&mut socket, &AppError::unauthorized()).await;
            return;
        }
    };
    let (stream, _slot) = opened;
    if socket
        .send(Message::Text(CONNECTED_FRAME.into()))
        .await
        .is_err()
    {
        return;
    }
    let outcome = pipe(socket, stream).await;
    tracing::debug!(?outcome, "link_relay.closed");
}

/// Reads the `connect` frame, authenticates it, and opens the TCP connection.
async fn open_target(
    socket: &mut WebSocket,
    state: &AppState,
) -> Result<(TcpStream, PipeSlot), AppError> {
    let frame = loop {
        match socket.recv().await {
            Some(Ok(Message::Text(text))) => {
                break serde_json::from_str::<ConnectFrame>(&text)
                    .map_err(|_| AppError::validation("Invalid relay request."))?;
            }
            Some(Ok(Message::Ping(_) | Message::Pong(_))) => {}
            _ => return Err(AppError::unauthorized()),
        }
    };
    if frame.r#type != "connect" {
        return Err(AppError::validation("First message must be type connect."));
    }
    let token = frame.token.ok_or_else(AppError::unauthorized)?;
    let user_id = ids_for_token(&state.pool, &token).await?.user_id;
    state
        .rate_limiter
        .check_budget(
            "link_relay_user",
            &user_id.to_string(),
            budgets::LINK_RELAY_USER,
        )
        .await?;
    let slot = state
        .link_relay
        .acquire(user_id)
        .ok_or_else(AppError::rate_limited)?;
    let host = frame
        .host
        .ok_or_else(|| AppError::validation("That link cannot be previewed."))?;
    let stream = link_relay::connect(state.link_relay.policy, &host)
        .await
        .map_err(rejection_error)?;
    Ok((stream, slot))
}

/// Client-safe error for a refused target. The host is deliberately left out.
fn rejection_error(rejection: RelayRejection) -> AppError {
    match rejection {
        RelayRejection::InvalidHost => AppError::validation("That link cannot be previewed."),
        RelayRejection::NotPublic => AppError::forbidden("That link cannot be previewed."),
        RelayRejection::Unresolvable | RelayRejection::Unreachable => AppError::Api {
            status: StatusCode::BAD_GATEWAY,
            code: "LINK_UNREACHABLE",
            message: "The website could not be reached.".into(),
        },
    }
}

/// Sent once the website accepted the connection; binary frames follow.
const CONNECTED_FRAME: &str = r#"{"type":"connected"}"#;

/// `{"type":"error","error":{"code","message"}}` — the API's error envelope on this socket.
#[derive(Serialize)]
struct ErrorFrame {
    r#type: &'static str,
    #[serde(flatten)]
    body: ErrorBody,
}

async fn refuse(socket: &mut WebSocket, err: &AppError) {
    let frame = ErrorFrame {
        r#type: "error",
        body: err.body(),
    };
    if let Ok(text) = serde_json::to_string(&frame) {
        let _ = socket.send(Message::Text(text.into())).await;
    }
    let _ = socket.close().await;
}

/// How a pipe ended (debug logs only — no host, no bytes).
#[derive(Debug)]
enum PipeEnd {
    BrowserClosed,
    WebsiteClosed,
    Idle,
    Expired,
    UpstreamCap,
    DownstreamCap,
    SendFailed,
}

/// Moves bytes between the WebSocket and the website until either side stops.
///
/// Human: The relay never parses what it carries — it is TLS to the browser's peer.
/// Agent: Binary frames → TCP; TCP reads (≤ 16 KiB) → binary frames. Text frames after
/// `connected` are ignored. Ends on close, error, idle, lifetime, or a byte cap.
async fn pipe(socket: WebSocket, stream: TcpStream) -> PipeEnd {
    let (mut sink, mut frames) = socket.split();
    let (mut from_site, mut to_site) = stream.into_split();
    let deadline = Instant::now() + MAX_LIFETIME;
    let mut sent_up = 0usize;
    let mut sent_down = 0usize;
    let mut buffer = BytesMut::with_capacity(READ_CHUNK);

    let end = loop {
        let idle_deadline = (Instant::now() + IDLE_TIMEOUT).min(deadline);
        let event = timeout_at(idle_deadline, async {
            tokio::select! {
                read = from_site.read_buf(&mut buffer) => Event::Site(read),
                frame = frames.next() => Event::Browser(frame),
            }
        })
        .await;
        let Ok(event) = event else {
            break if Instant::now() >= deadline {
                PipeEnd::Expired
            } else {
                PipeEnd::Idle
            };
        };
        match event {
            Event::Site(Ok(0) | Err(_)) => break PipeEnd::WebsiteClosed,
            Event::Site(Ok(read)) => {
                sent_down += read;
                if sent_down > MAX_DOWNSTREAM_BYTES {
                    break PipeEnd::DownstreamCap;
                }
                let chunk = buffer.split().freeze();
                if sink.send(Message::Binary(chunk)).await.is_err() {
                    break PipeEnd::SendFailed;
                }
                buffer.reserve(READ_CHUNK);
            }
            Event::Browser(Some(Ok(Message::Binary(data)))) => {
                sent_up += data.len();
                if sent_up > MAX_UPSTREAM_BYTES {
                    break PipeEnd::UpstreamCap;
                }
                if to_site.write_all(&data).await.is_err() {
                    break PipeEnd::WebsiteClosed;
                }
            }
            Event::Browser(Some(Ok(Message::Ping(data)))) => {
                if sink.send(Message::Pong(data)).await.is_err() {
                    break PipeEnd::SendFailed;
                }
            }
            Event::Browser(Some(Ok(Message::Text(_) | Message::Pong(_)))) => {}
            Event::Browser(Some(Ok(Message::Close(_)) | Err(_)) | None) => {
                break PipeEnd::BrowserClosed;
            }
        }
    };
    let _ = to_site.shutdown().await;
    let _ = sink.close().await;
    end
}

enum Event {
    Site(std::io::Result<usize>),
    Browser(Option<Result<Message, axum::Error>>),
}
