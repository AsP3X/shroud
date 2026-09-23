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

/// None without `DATABASE_URL` (the tests skip). Set but unusable fails loudly instead.
async fn test_pool() -> Option<sqlx::PgPool> {
    let database_url = std::env::var("DATABASE_URL").ok()?;
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("connect to DATABASE_URL");
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .expect("apply migrations (recreate a throwaway database whose migrations changed)");
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

/// Human: Recording a voice note uses the same relay as typing (`web/src/typing.ts`
/// `createRecordingSender`, iOS `MessagingController.setRecording`): the sender's frame
/// reaches the peer's devices as `recording` with `user_id` = sender, never echoes to the
/// sender, and never reaches a non-contact.
#[tokio::test]
async fn websocket_recording_relays_to_contact_only() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping websocket_recording_relays_to_contact_only: DATABASE_URL unavailable");
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

    for is_recording in [true, false] {
        a.send(Message::Text(
            json!({ "type": "recording", "peer_user_id": user_b, "is_recording": is_recording })
                .to_string()
                .into(),
        ))
        .await
        .expect("send recording");
        let event = next_of_type(&mut b, "recording", Duration::from_secs(5))
            .await
            .expect("peer gets recording");
        assert_eq!(event["user_id"], user_a);
        assert_eq!(event["peer_user_id"], user_b);
        assert_eq!(event["is_recording"], is_recording);
    }

    assert!(
        next_of_type(&mut a, "recording", Duration::from_millis(300))
            .await
            .is_none()
    );

    c.send(Message::Text(
        json!({ "type": "recording", "peer_user_id": user_b, "is_recording": true })
            .to_string()
            .into(),
    ))
    .await
    .expect("send stranger recording");
    assert!(
        next_of_type(&mut b, "recording", Duration::from_millis(500))
            .await
            .is_none()
    );

    for mut socket in [a, b, c] {
        let _ = socket.close(None).await;
    }
    let _ = shutdown_tx.send(());
    let _ = server.await;
}

// MARK: reactions

/// Signs `username` in again: a second device with its own token.
async fn login(client: &reqwest::Client, addr: std::net::SocketAddr, username: &str) -> String {
    let response = client
        .post(format!("http://{addr}/api/v1/auth/login"))
        .json(&json!({ "username": username, "password": "correct-horse-battery" }))
        .send()
        .await
        .expect("login");
    assert!(
        response.status().is_success(),
        "login: {}",
        response.status()
    );
    let body: Value = response.json().await.expect("json");
    body["token"].as_str().unwrap().to_string()
}

async fn username_of(client: &reqwest::Client, addr: std::net::SocketAddr, token: &str) -> String {
    let body: Value = client
        .get(format!("http://{addr}/api/v1/auth/me"))
        .bearer_auth(token)
        .send()
        .await
        .expect("me")
        .json()
        .await
        .expect("json");
    body["username"]
        .as_str()
        .or_else(|| body["user"]["username"].as_str())
        .expect("username")
        .to_string()
}

/// Human: `message.reaction` goes to the reactor's other devices and to the other participant
/// while they can still see the message (catch-up hides it from them otherwise), carrying the
/// message's sender and whether an emoji was added; `reactions.seen` only to the marker's other
/// devices. Contract shared with web/src/screens/AppShell.tsx and iOS `MessagingController`.
#[tokio::test]
async fn websocket_reaction_events_reach_who_can_see_the_message() {
    let Some(pool) = test_pool().await else {
        eprintln!(
            "skipping websocket_reaction_events_reach_who_can_see_the_message: no DATABASE_URL"
        );
        return;
    };
    let (addr, shutdown, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let (token_a, user_a) = register(&client, addr).await;
    let (token_b, user_b) = register(&client, addr).await;
    become_contacts(&client, addr, (&token_a, &user_a), (&token_b, &user_b)).await;
    let token_a2 = login(&client, addr, &username_of(&client, addr, &token_a).await).await;
    let token_b2 = login(&client, addr, &username_of(&client, addr, &token_b).await).await;

    let mut a1 = connect_authed(addr, &token_a).await;
    let mut a2 = connect_authed(addr, &token_a2).await;
    let mut b1 = connect_authed(addr, &token_b).await;
    let mut b2 = connect_authed(addr, &token_b2).await;

    let sent: Value = client
        .post(format!("http://{addr}/api/v1/messages"))
        .bearer_auth(&token_a)
        .json(&json!({
            "peer_user_id": user_b,
            "client_message_id": Uuid::new_v4(),
            "content_type": "text",
            "ciphertext": "c2VhbGVk",
        }))
        .send()
        .await
        .expect("send")
        .json()
        .await
        .expect("json");
    let message = sent["id"].as_str().unwrap().to_string();
    let react = |token: &str, blob: &str, base: i64, added: bool| {
        client
            .put(format!("http://{addr}/api/v1/messages/{message}/reaction"))
            .bearer_auth(token.to_string())
            .json(&json!({ "ciphertext": blob, "base_seq": base, "added": added }))
            .send()
    };

    // B (device 1) reacts: A's devices and B's device 2 hear it, device 1 does not.
    let first: Value = react(&token_b, "aGVhcnQ=", 0, true)
        .await
        .expect("react")
        .json()
        .await
        .expect("json");
    let short = Duration::from_millis(400);
    for socket in [&mut a1, &mut a2, &mut b2] {
        let event = next_of_type(socket, "message.reaction", Duration::from_secs(5))
            .await
            .expect("message.reaction");
        assert_eq!(event["message_sender_id"], user_a.as_str());
        assert_eq!(event["added"], true);
        assert_eq!(event["reaction"]["seq"], first["seq"]);
        assert_eq!(event["reaction"]["user_id"], user_b.as_str());
    }
    assert!(
        next_of_type(&mut b1, "message.reaction", short)
            .await
            .is_none()
    );

    // A marks it seen on device 1: device 2 hears it, device 1 and B do not.
    let seen = client
        .post(format!(
            "http://{addr}/api/v1/conversations/{user_b}/reactions/seen"
        ))
        .bearer_auth(&token_a)
        .json(&json!({ "up_to_seq": first["seq"] }))
        .send()
        .await
        .expect("seen");
    assert!(seen.status().is_success());
    let event = next_of_type(&mut a2, "reactions.seen", Duration::from_secs(5))
        .await
        .expect("reactions.seen");
    assert_eq!(event["peer_user_id"], user_b.as_str());
    assert_eq!(event["seen_seq"], first["seq"]);
    assert!(
        next_of_type(&mut a1, "reactions.seen", short)
            .await
            .is_none()
    );
    assert!(
        next_of_type(&mut b2, "reactions.seen", short)
            .await
            .is_none()
    );

    // Taking one back reaches everyone, as not added.
    let second: Value = react(&token_b, "ZmlyZQ==", first["seq"].as_i64().unwrap(), false)
        .await
        .expect("react")
        .json()
        .await
        .expect("json");
    for socket in [&mut a1, &mut a2, &mut b2] {
        let event = next_of_type(socket, "message.reaction", Duration::from_secs(5))
            .await
            .expect("message.reaction");
        assert_eq!(event["added"], false);
        assert_eq!(event["reaction"]["seq"], second["seq"]);
    }

    // A deletes the message for themselves: B's next change reaches only B's other device.
    let hidden = client
        .delete(format!("http://{addr}/api/v1/messages/{message}?scope=me"))
        .bearer_auth(&token_a)
        .send()
        .await
        .expect("hide");
    assert!(hidden.status().is_success());
    let third = react(&token_b, "dGh1bWJz", second["seq"].as_i64().unwrap(), true)
        .await
        .expect("react");
    assert!(third.status().is_success());
    let third: Value = third.json().await.expect("json");
    let event = next_of_type(&mut b2, "message.reaction", Duration::from_secs(5))
        .await
        .expect("message.reaction");
    assert_eq!(event["reaction"]["seq"], third["seq"]);
    assert!(
        next_of_type(&mut a1, "message.reaction", short)
            .await
            .is_none()
    );
    assert!(
        next_of_type(&mut a2, "message.reaction", short)
            .await
            .is_none()
    );

    let _ = shutdown.send(());
    let _ = server.await;
}
