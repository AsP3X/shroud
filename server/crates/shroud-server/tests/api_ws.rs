//! WebSocket auth smoke test.
//!
//! Human: Requires Postgres — set `DATABASE_URL` (see `server/.env.example`).
//! Agent: Binds ephemeral listener; tokio-tungstenite auth handshake; shuts down to free pool.

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use shroud_server::routes;
use sqlx::postgres::PgPoolOptions;
use tokio_tungstenite::tungstenite::Message;
use uuid::Uuid;

async fn test_pool() -> Option<sqlx::PgPool> {
    let database_url = std::env::var("DATABASE_URL").ok()?;
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .ok()?;
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .ok()?;
    Some(pool)
}

#[tokio::test]
async fn websocket_auth_ok_with_valid_token() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping websocket_auth_ok_with_valid_token: DATABASE_URL unavailable");
        return;
    };

    let state = shroud_server::state::AppState::for_integration_tests(pool);
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state);

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let addr = listener.local_addr().expect("addr");

    // Human: Shut down the test server so the pool is dropped before other suites run.
    // Agent: oneshot cancel → graceful_shutdown; awaits join before test returns.
    let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();
    let server = tokio::spawn(async move {
        axum::serve(listener, app)
            .with_graceful_shutdown(async {
                let _ = shutdown_rx.await;
            })
            .await
            .expect("serve");
    });

    tokio::time::sleep(Duration::from_millis(50)).await;

    let id = &Uuid::new_v4().simple().to_string()[..12];
    let username = format!("w_{id}");
    let password = "correct-horse-battery";
    let client = reqwest::Client::new();
    let register = client
        .post(format!("http://{addr}/api/v1/auth/register"))
        .json(&json!({
            "username": username,
            "password": password,
            "device_name": "WS Test"
        }))
        .send()
        .await
        .expect("register");
    assert_eq!(register.status(), reqwest::StatusCode::CREATED);
    let body: Value = register.json().await.expect("json");
    let token = body["token"].as_str().unwrap().to_string();
    let user_id = body["user"]["id"].as_str().unwrap().to_string();
    let device_id = body["device"]["id"].as_str().unwrap().to_string();

    let url = format!("ws://{addr}/api/v1/ws");
    let (mut socket, _) = tokio_tungstenite::connect_async(&url)
        .await
        .expect("ws connect");

    socket
        .send(Message::Text(
            json!({ "type": "auth", "token": token }).to_string().into(),
        ))
        .await
        .expect("send auth");

    let reply = tokio::time::timeout(Duration::from_secs(5), socket.next())
        .await
        .expect("auth reply timeout")
        .expect("stream ended")
        .expect("ws error");
    let Message::Text(text) = reply else {
        panic!("expected text frame, got {reply:?}");
    };
    let event: Value = serde_json::from_str(&text).expect("json");
    assert_eq!(event["type"], "auth.ok");
    assert_eq!(event["user_id"], user_id);
    assert_eq!(event["device_id"], device_id);

    let _ = socket.close(None).await;
    let _ = shutdown_tx.send(());
    let _ = server.await;
}

// MARK: typing relay

/// Serves the app on an ephemeral port; send on the returned channel to shut it down.
async fn spawn_app(
    pool: sqlx::PgPool,
) -> (
    std::net::SocketAddr,
    tokio::sync::oneshot::Sender<()>,
    tokio::task::JoinHandle<()>,
) {
    let state = shroud_server::state::AppState::for_integration_tests(pool);
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let addr = listener.local_addr().expect("addr");
    let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();
    let server = tokio::spawn(async move {
        axum::serve(listener, app)
            .with_graceful_shutdown(async {
                let _ = shutdown_rx.await;
            })
            .await
            .expect("serve");
    });
    tokio::time::sleep(Duration::from_millis(50)).await;
    (addr, shutdown_tx, server)
}

/// Registers a fresh user; returns (token, user id).
async fn register(client: &reqwest::Client, addr: std::net::SocketAddr) -> (String, String) {
    let id = &Uuid::new_v4().simple().to_string()[..12];
    let response = client
        .post(format!("http://{addr}/api/v1/auth/register"))
        .json(&json!({ "username": format!("ty_{id}"), "password": "correct-horse-battery" }))
        .send()
        .await
        .expect("register");
    assert_eq!(response.status(), reqwest::StatusCode::CREATED);
    let body: Value = response.json().await.expect("json");
    (
        body["token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
    )
}

/// Mutual requests connect the pair, as in the contacts tests.
async fn become_contacts(
    client: &reqwest::Client,
    addr: std::net::SocketAddr,
    (token_a, user_a): (&str, &str),
    (token_b, user_b): (&str, &str),
) {
    for (token, peer) in [(token_a, user_b), (token_b, user_a)] {
        let response = client
            .post(format!("http://{addr}/api/v1/contacts/requests"))
            .bearer_auth(token)
            .json(&json!({ "user_id": peer }))
            .send()
            .await
            .expect("contact request");
        assert!(
            response.status().is_success(),
            "contact request: {}",
            response.status()
        );
    }
}

type Socket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn connect_authed(addr: std::net::SocketAddr, token: &str) -> Socket {
    let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{addr}/api/v1/ws"))
        .await
        .expect("ws connect");
    socket
        .send(Message::Text(
            json!({ "type": "auth", "token": token }).to_string().into(),
        ))
        .await
        .expect("send auth");
    let ok = next_of_type(&mut socket, "auth.ok", Duration::from_secs(5)).await;
    assert!(ok.is_some(), "no auth.ok");
    socket
}

/// The next event of `kind`, skipping others (presence and the like); None on timeout.
async fn next_of_type(socket: &mut Socket, kind: &str, wait: Duration) -> Option<Value> {
    let deadline = tokio::time::Instant::now() + wait;
    loop {
        let frame = tokio::time::timeout_at(deadline, socket.next())
            .await
            .ok()??
            .ok()?;
        let Message::Text(text) = frame else { continue };
        let event: Value = serde_json::from_str(&text).ok()?;
        if event["type"] == kind {
            return Some(event);
        }
    }
}

/// Human: The web client and iOS both speak this contract (web/src/typing.ts, iOS
/// `MessagingController`): the sender's frame reaches the peer's devices as `typing` with
/// `user_id` = sender, never echoes to the sender, and never reaches a non-contact.
#[tokio::test]
async fn websocket_typing_relays_to_contact_only() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping websocket_typing_relays_to_contact_only: DATABASE_URL unavailable");
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();

    let (token_a, user_a) = register(&client, addr).await;
    let (token_b, user_b) = register(&client, addr).await;
    let (token_c, _user_c) = register(&client, addr).await;
    become_contacts(&client, addr, (&token_a, &user_a), (&token_b, &user_b)).await;

    let mut a = connect_authed(addr, &token_a).await;
    let mut b = connect_authed(addr, &token_b).await;
    let mut c = connect_authed(addr, &token_c).await;

    // Exactly what both clients send.
    for is_typing in [true, false] {
        a.send(Message::Text(
            json!({ "type": "typing", "peer_user_id": user_b, "is_typing": is_typing })
                .to_string()
                .into(),
        ))
        .await
        .expect("send typing");
        let event = next_of_type(&mut b, "typing", Duration::from_secs(5))
            .await
            .expect("peer gets typing");
        assert_eq!(event["user_id"], user_a);
        assert_eq!(event["peer_user_id"], user_b);
        assert_eq!(event["is_typing"], is_typing);
    }

    // Not echoed to the sender's own socket.
    assert!(
        next_of_type(&mut a, "typing", Duration::from_millis(300))
            .await
            .is_none()
    );

    // A stranger's typing never reaches B.
    c.send(Message::Text(
        json!({ "type": "typing", "peer_user_id": user_b, "is_typing": true })
            .to_string()
            .into(),
    ))
    .await
    .expect("send stranger typing");
    assert!(
        next_of_type(&mut b, "typing", Duration::from_millis(500))
            .await
            .is_none()
    );

    for mut socket in [a, b, c] {
        let _ = socket.close(None).await;
    }
    let _ = shutdown_tx.send(());
    let _ = server.await;
}
