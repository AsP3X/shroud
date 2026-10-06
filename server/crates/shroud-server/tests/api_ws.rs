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
            "username_hash": shroud_server::auth::username_hash_b64(username),
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
    spawn_state(shroud_server::state::AppState::for_integration_tests(pool)).await
}

/// Like [`spawn_app`], for a state the test wired itself (a Redis replica).
async fn spawn_state(
    state: shroud_server::state::AppState,
) -> (
    std::net::SocketAddr,
    tokio::sync::oneshot::Sender<()>,
    tokio::task::JoinHandle<()>,
) {
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

const PASSWORD: &str = "correct-horse-battery";

/// Registers a fresh user; returns (token, user id).
async fn register(client: &reqwest::Client, addr: std::net::SocketAddr) -> (String, String) {
    register_as(client, addr, &unique_username()).await
}

fn unique_username() -> String {
    format!("ty_{}", &Uuid::new_v4().simple().to_string()[..12])
}

/// Registers `username`; returns (token, user id).
async fn register_as(
    client: &reqwest::Client,
    addr: std::net::SocketAddr,
    username: &str,
) -> (String, String) {
    let response = client
        .post(format!("http://{addr}/api/v1/auth/register"))
        .json(&json!({ "username_hash": shroud_server::auth::username_hash_b64(username), "password": PASSWORD }))
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

// MARK: revocation

/// Signs `username` in; with `device_id`, on that device again. Returns (token, device id).
async fn log_in(
    client: &reqwest::Client,
    addr: std::net::SocketAddr,
    username: &str,
    device_id: Option<&str>,
) -> (String, String) {
    let response = client
        .post(format!("http://{addr}/api/v1/auth/login"))
        .json(&json!({ "username_hash": shroud_server::auth::username_hash_b64(username), "password": PASSWORD, "device_id": device_id }))
        .send()
        .await
        .expect("login");
    assert_eq!(response.status(), reqwest::StatusCode::OK);
    let body: Value = response.json().await.expect("json");
    (
        body["token"].as_str().unwrap().to_string(),
        body["device"]["id"].as_str().unwrap().to_string(),
    )
}

/// Sends a text message from `token`'s account to `peer_user_id`.
async fn send_text(
    client: &reqwest::Client,
    addr: std::net::SocketAddr,
    token: &str,
    peer_user_id: &str,
) {
    let response = client
        .post(format!("http://{addr}/api/v1/messages"))
        .bearer_auth(token)
        .json(&json!({
            "peer_user_id": peer_user_id,
            "client_message_id": Uuid::new_v4(),
            "content_type": "text",
            "ciphertext": "c2VhbGVk",
        }))
        .send()
        .await
        .expect("send message");
    assert_eq!(response.status(), reqwest::StatusCode::CREATED);
}

/// Alice on a phone and a laptop, each with an open socket, and Bob, one of her contacts.
struct TwoDevices {
    alice: String,
    alice_id: String,
    phone_token: String,
    phone: Socket,
    laptop_token: String,
    laptop_id: String,
    laptop: Socket,
    bob_token: String,
}

/// Sets up [`TwoDevices`] with requests to `api` and sockets on `ws` (one server, or two
/// replicas). Both sockets have already received a message from Bob.
async fn alice_on_two_devices(
    client: &reqwest::Client,
    api: std::net::SocketAddr,
    ws: std::net::SocketAddr,
) -> TwoDevices {
    let alice = unique_username();
    let (phone_token, alice_id) = register_as(client, api, &alice).await;
    let (laptop_token, laptop_id) = log_in(client, api, &alice, None).await;
    let (bob_token, bob_id) = register(client, api).await;
    become_contacts(
        client,
        api,
        (&phone_token, &alice_id),
        (&bob_token, &bob_id),
    )
    .await;

    let mut phone = connect_authed(ws, &phone_token).await;
    let mut laptop = connect_authed(ws, &laptop_token).await;
    send_text(client, api, &bob_token, &alice_id).await;
    for socket in [&mut phone, &mut laptop] {
        assert!(
            next_of_type(socket, "message.new", Duration::from_secs(5))
                .await
                .is_some(),
            "a linked device gets the message"
        );
    }
    TwoDevices {
        alice,
        alice_id,
        phone_token,
        phone,
        laptop_token,
        laptop_id,
        laptop,
        bob_token,
    }
}

/// Reads until the server closes the socket; returns the events it sent before that.
async fn events_until_closed(socket: &mut Socket) -> Vec<Value> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    let mut events: Vec<Value> = Vec::new();
    loop {
        match tokio::time::timeout_at(deadline, socket.next()).await {
            Err(_) => panic!("socket still open; it got {events:?}"),
            Ok(Some(Ok(Message::Text(text)))) => {
                events.push(serde_json::from_str(&text).expect("json"));
            }
            Ok(Some(Ok(Message::Close(_))) | Some(Err(_)) | None) => return events,
            Ok(Some(Ok(_))) => {}
        }
    }
}

/// The socket is told its session ended (`code`: `UNAUTHORIZED` for a sign-out,
/// `DEVICE_REMOVED` when the device must wipe itself) and is closed, with no message reaching
/// it first.
async fn assert_closed_with(socket: &mut Socket, code: &str) {
    let events = events_until_closed(socket).await;
    assert!(
        !events.iter().any(|event| event["type"] == "message.new"),
        "revoked socket got {events:?}"
    );
    let last = events.last().expect("a final frame");
    assert_eq!(last["type"], "auth.error");
    assert_eq!(last["error"]["code"], code);
}

async fn assert_signed_out(socket: &mut Socket) {
    assert_closed_with(socket, "UNAUTHORIZED").await;
}

async fn assert_removed(socket: &mut Socket) {
    assert_closed_with(socket, "DEVICE_REMOVED").await;
}

/// Human: Settings → Devices promises a removed device "is signed out right away and stops
/// receiving messages". Sockets authenticate once, so a removed (say, stolen) device kept
/// every new message, typing and call frame until it disconnected by itself.
#[tokio::test]
async fn removing_a_device_closes_its_socket() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping removing_a_device_closes_its_socket: DATABASE_URL unavailable");
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let mut two = alice_on_two_devices(&client, addr, addr).await;

    let removed = client
        .delete(format!("http://{addr}/api/v1/devices/{}", two.laptop_id))
        .bearer_auth(&two.phone_token)
        .send()
        .await
        .expect("remove device");
    assert_eq!(removed.status(), reqwest::StatusCode::NO_CONTENT);
    send_text(&client, addr, &two.bob_token, &two.alice_id).await;

    assert_removed(&mut two.laptop).await;
    assert!(
        next_of_type(&mut two.phone, "message.new", Duration::from_secs(5))
            .await
            .is_some()
    );

    // Reconnecting with its token is refused with the same reason, so the device wipes
    // itself even when it missed the close.
    let (mut again, _) = tokio_tungstenite::connect_async(format!("ws://{addr}/api/v1/ws"))
        .await
        .expect("ws connect");
    again
        .send(Message::Text(
            json!({ "type": "auth", "token": two.laptop_token })
                .to_string()
                .into(),
        ))
        .await
        .expect("send auth");
    assert_removed(&mut again).await;

    let _ = two.phone.close(None).await;
    let _ = shutdown_tx.send(());
    let _ = server.await;
}

/// Human: A login that the user let sign out a device to make room removes that device the
/// same way the Devices list does: its socket closes with DEVICE_REMOVED right away.
#[tokio::test]
async fn a_login_replacing_a_device_closes_its_socket() {
    let Some(pool) = test_pool().await else {
        eprintln!(
            "skipping a_login_replacing_a_device_closes_its_socket: DATABASE_URL unavailable"
        );
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let mut two = alice_on_two_devices(&client, addr, addr).await;
    for _ in 2..shroud_server::auth::MAX_DEVICES_PER_USER {
        log_in(&client, addr, &two.alice, None).await;
    }

    let replaced = client
        .post(format!("http://{addr}/api/v1/auth/login"))
        .json(&json!({
            "username_hash": shroud_server::auth::username_hash_b64(&two.alice),
            "password": PASSWORD,
            "replace_device_id": two.laptop_id,
        }))
        .send()
        .await
        .expect("login");
    assert_eq!(replaced.status(), reqwest::StatusCode::OK);
    send_text(&client, addr, &two.bob_token, &two.alice_id).await;

    assert_removed(&mut two.laptop).await;
    assert!(
        next_of_type(&mut two.phone, "message.new", Duration::from_secs(5))
            .await
            .is_some()
    );

    let _ = two.phone.close(None).await;
    let _ = shutdown_tx.send(());
    let _ = server.await;
}

/// Human: Changing the password signs every other session out; their sockets close with
/// them, and the device that changed it keeps its own.
#[tokio::test]
async fn password_change_closes_the_other_devices_sockets() {
    let Some(pool) = test_pool().await else {
        eprintln!(
            "skipping password_change_closes_the_other_devices_sockets: DATABASE_URL unavailable"
        );
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let mut two = alice_on_two_devices(&client, addr, addr).await;

    let changed = client
        .post(format!("http://{addr}/api/v1/auth/password"))
        .bearer_auth(&two.phone_token)
        .json(&json!({ "current_password": PASSWORD, "new_password": "battery-staple-horse" }))
        .send()
        .await
        .expect("change password");
    assert_eq!(changed.status(), reqwest::StatusCode::NO_CONTENT);
    send_text(&client, addr, &two.bob_token, &two.alice_id).await;

    assert_signed_out(&mut two.laptop).await;
    assert!(
        next_of_type(&mut two.phone, "message.new", Duration::from_secs(5))
            .await
            .is_some()
    );

    let _ = two.phone.close(None).await;
    let _ = shutdown_tx.send(());
    let _ = server.await;
}

/// Human: Logging out ends the session's socket along with its token.
#[tokio::test]
async fn logout_closes_the_socket() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping logout_closes_the_socket: DATABASE_URL unavailable");
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let mut two = alice_on_two_devices(&client, addr, addr).await;

    let logged_out = client
        .post(format!("http://{addr}/api/v1/auth/logout"))
        .bearer_auth(&two.laptop_token)
        .send()
        .await
        .expect("logout");
    assert_eq!(logged_out.status(), reqwest::StatusCode::NO_CONTENT);
    send_text(&client, addr, &two.bob_token, &two.alice_id).await;

    assert_signed_out(&mut two.laptop).await;
    assert!(
        next_of_type(&mut two.phone, "message.new", Duration::from_secs(5))
            .await
            .is_some()
    );

    let _ = two.phone.close(None).await;
    let _ = shutdown_tx.send(());
    let _ = server.await;
}

/// Human: A new login on a device replaces its token. The socket the old token opened
/// closes; a socket on the new token works.
#[tokio::test]
async fn new_login_on_a_device_closes_its_old_socket() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping new_login_on_a_device_closes_its_old_socket: DATABASE_URL unavailable");
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let mut two = alice_on_two_devices(&client, addr, addr).await;

    let (new_token, device_id) = log_in(&client, addr, &two.alice, Some(&two.laptop_id)).await;
    assert_eq!(device_id, two.laptop_id);
    send_text(&client, addr, &two.bob_token, &two.alice_id).await;

    assert_signed_out(&mut two.laptop).await;
    let mut laptop = connect_authed(addr, &new_token).await;
    send_text(&client, addr, &two.bob_token, &two.alice_id).await;
    assert!(
        next_of_type(&mut laptop, "message.new", Duration::from_secs(5))
            .await
            .is_some()
    );

    for mut socket in [two.phone, laptop] {
        let _ = socket.close(None).await;
    }
    let _ = shutdown_tx.send(());
    let _ = server.await;
}

/// Human: Deleting the account revokes every one of its devices, so every socket it had open
/// closes, the deleting device's own included.
#[tokio::test]
async fn deleting_the_account_closes_its_sockets() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping deleting_the_account_closes_its_sockets: DATABASE_URL unavailable");
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let mut two = alice_on_two_devices(&client, addr, addr).await;

    let deleted = client
        .delete(format!("http://{addr}/api/v1/auth/account"))
        .bearer_auth(&two.phone_token)
        .json(&json!({ "password": PASSWORD }))
        .send()
        .await
        .expect("delete account");
    assert_eq!(deleted.status(), reqwest::StatusCode::NO_CONTENT);

    assert_removed(&mut two.laptop).await;
    assert_removed(&mut two.phone).await;

    let _ = shutdown_tx.send(());
    let _ = server.await;
}

/// Human: A client that reconnects before the server notices its old socket (a network
/// change) must keep the new one. The replaced socket is closed without `auth.error`, which
/// would sign the web client out, and its cleanup must not unregister its successor.
#[tokio::test]
async fn reconnecting_device_keeps_its_new_socket() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping reconnecting_device_keeps_its_new_socket: DATABASE_URL unavailable");
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let mut two = alice_on_two_devices(&client, addr, addr).await;

    let mut reconnected = connect_authed(addr, &two.laptop_token).await;
    let replaced = events_until_closed(&mut two.laptop).await;
    assert!(
        replaced.iter().all(|event| event["type"] != "auth.error"),
        "replaced socket got {replaced:?}"
    );
    send_text(&client, addr, &two.bob_token, &two.alice_id).await;
    assert!(
        next_of_type(&mut reconnected, "message.new", Duration::from_secs(5))
            .await
            .is_some()
    );

    for mut socket in [two.phone, reconnected] {
        let _ = socket.close(None).await;
    }
    let _ = shutdown_tx.send(());
    let _ = server.await;
}

/// A replica's state wired to Redis the way `shroud_server::run` wires it.
async fn redis_replica(pool: sqlx::PgPool, redis_url: &str) -> shroud_server::state::AppState {
    let state = shroud_server::state::AppState::for_integration_tests(pool);
    let client = redis::Client::open(redis_url).expect("redis url");
    let manager = redis::aio::ConnectionManager::new(client)
        .await
        .expect("redis connect");
    state.realtime.set_redis(manager).await;
    shroud_server::realtime::spawn_redis_subscriber(state.realtime.clone(), redis_url.to_string());
    state
}

/// Human: Behind a load balancer, the request that removes a device and that device's
/// socket can land on different replicas. The revocation crosses over Redis, so the socket
/// closes straight away rather than at its next 30 s session re-check. Also needs
/// `REDIS_URL` (a throwaway instance: the test publishes on the live channel names).
#[tokio::test]
async fn removing_a_device_closes_its_socket_via_redis() {
    let Some(pool) = test_pool().await else {
        eprintln!(
            "skipping removing_a_device_closes_its_socket_via_redis: DATABASE_URL unavailable"
        );
        return;
    };
    let Ok(redis_url) = std::env::var("REDIS_URL") else {
        eprintln!("skipping removing_a_device_closes_its_socket_via_redis: REDIS_URL unset");
        return;
    };
    let (api, api_shutdown, api_server) =
        spawn_state(redis_replica(pool.clone(), &redis_url).await).await;
    let (ws, ws_shutdown, ws_server) = spawn_state(redis_replica(pool, &redis_url).await).await;

    // Both replicas' subscribers are listening before anything is published.
    let mut redis = redis::Client::open(redis_url.as_str())
        .expect("redis url")
        .get_multiplexed_async_connection()
        .await
        .expect("redis connect");
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    loop {
        let (_, listeners): (String, i64) = redis::cmd("PUBSUB")
            .arg("NUMSUB")
            .arg("shroud:sessions:revoked")
            .query_async(&mut redis)
            .await
            .expect("pubsub numsub");
        if listeners >= 2 {
            break;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "subscribers not listening"
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }

    let client = reqwest::Client::new();
    let mut two = alice_on_two_devices(&client, api, ws).await;

    let removed = client
        .delete(format!("http://{api}/api/v1/devices/{}", two.laptop_id))
        .bearer_auth(&two.phone_token)
        .send()
        .await
        .expect("remove device");
    assert_eq!(removed.status(), reqwest::StatusCode::NO_CONTENT);
    send_text(&client, api, &two.bob_token, &two.alice_id).await;

    assert_removed(&mut two.laptop).await;
    assert!(
        next_of_type(&mut two.phone, "message.new", Duration::from_secs(5))
            .await
            .is_some()
    );

    let _ = two.phone.close(None).await;
    for (shutdown, server) in [(api_shutdown, api_server), (ws_shutdown, ws_server)] {
        let _ = shutdown.send(());
        let _ = server.await;
    }
}

// MARK: reactions

/// Signs `username` in again: a second device with its own token.
async fn login(client: &reqwest::Client, addr: std::net::SocketAddr, username: &str) -> String {
    let response = client
        .post(format!("http://{addr}/api/v1/auth/login"))
        .json(&json!({ "username_hash": shroud_server::auth::username_hash_b64(username), "password": "correct-horse-battery" }))
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
    let name_a = unique_username();
    let name_b = unique_username();
    let (token_a, user_a) = register_as(&client, addr, &name_a).await;
    let (token_b, user_b) = register_as(&client, addr, &name_b).await;
    become_contacts(&client, addr, (&token_a, &user_a), (&token_b, &user_b)).await;
    let token_a2 = login(&client, addr, &name_a).await;
    let token_b2 = login(&client, addr, &name_b).await;

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

// MARK: background sockets (Android's background connection)

/// Opens a socket with `auth` as its first frame; returns it with its `auth.ok`.
async fn connect_with(addr: std::net::SocketAddr, auth: Value) -> (Socket, Value) {
    let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{addr}/api/v1/ws"))
        .await
        .expect("ws connect");
    socket
        .send(Message::Text(auth.to_string().into()))
        .await
        .expect("send auth");
    let ok = next_of_type(&mut socket, "auth.ok", Duration::from_secs(5))
        .await
        .expect("auth.ok");
    (socket, ok)
}

/// A socket the way the Android app's background connection opens it.
async fn connect_background(addr: std::net::SocketAddr, token: &str) -> (Socket, Value) {
    connect_with(
        addr,
        json!({ "type": "auth", "token": token, "background": true }),
    )
    .await
}

async fn send_frame(socket: &mut Socket, frame: Value) {
    socket
        .send(Message::Text(frame.to_string().into()))
        .await
        .expect("send frame");
}

/// `GET /presence/{user}` as `viewer`: (online, last_seen_at).
async fn presence_of(
    client: &reqwest::Client,
    addr: std::net::SocketAddr,
    viewer: &str,
    user: &str,
) -> (bool, Value) {
    let response = client
        .get(format!("http://{addr}/api/v1/presence/{user}"))
        .bearer_auth(viewer)
        .send()
        .await
        .expect("presence");
    assert_eq!(response.status(), reqwest::StatusCode::OK);
    let body: Value = response.json().await.expect("json");
    (
        body["online"].as_bool().unwrap(),
        body.get("last_seen_at").cloned().unwrap_or(Value::Null),
    )
}

/// The next `presence.update` about `user`, if one comes within `wait`.
async fn presence_update(socket: &mut Socket, user: &str, wait: Duration) -> Option<Value> {
    let deadline = tokio::time::Instant::now() + wait;
    loop {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        let event = next_of_type(socket, "presence.update", left).await?;
        if event["user_id"] == user {
            return Some(event);
        }
    }
}

/// Human: The Android app's opt-in background connection keeps a socket open while the app
/// is not on screen. Its user must not look online to their contacts all day, nor its
/// `last_seen` move, yet it must get every event. Once the app is on screen the same socket
/// counts like any other; when it leaves, it is back to background accounting.
#[tokio::test]
async fn a_background_socket_is_not_online_until_its_app_is_in_front() {
    let Some(pool) = test_pool().await else {
        eprintln!(
            "skipping a_background_socket_is_not_online_until_its_app_is_in_front: DATABASE_URL unavailable"
        );
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let (alice_token, alice_id) = register(&client, addr).await;
    let (bob_token, bob_id) = register(&client, addr).await;
    become_contacts(
        &client,
        addr,
        (&alice_token, &alice_id),
        (&bob_token, &bob_id),
    )
    .await;
    let mut bob = connect_authed(addr, &bob_token).await;
    let short = Duration::from_millis(500);
    let (online, never_seen) = presence_of(&client, addr, &bob_token, &alice_id).await;
    assert!(!online);

    let (mut alice, ok) = connect_background(addr, &alice_token).await;
    assert_eq!(ok["user_id"], alice_id);
    assert!(
        presence_update(&mut bob, &alice_id, short).await.is_none(),
        "a background socket is not announced"
    );
    assert_eq!(
        presence_of(&client, addr, &bob_token, &alice_id).await,
        (false, never_seen.clone()),
        "neither online nor seen"
    );
    // It gets every event all the same.
    send_text(&client, addr, &bob_token, &alice_id).await;
    assert!(
        next_of_type(&mut alice, "message.new", Duration::from_secs(5))
            .await
            .is_some()
    );

    // The app comes to the front on the same socket: online, and seen now.
    send_frame(&mut alice, json!({ "type": "focus", "focused": true })).await;
    let update = presence_update(&mut bob, &alice_id, Duration::from_secs(5))
        .await
        .expect("online once in front");
    assert_eq!(update["online"], true);
    let (online, seen) = presence_of(&client, addr, &bob_token, &alice_id).await;
    assert!(online);
    assert!(seen.is_string() && seen != never_seen, "{seen}");

    // It leaves again: offline, while the socket stays open and keeps delivering.
    send_frame(&mut alice, json!({ "type": "focus", "focused": false })).await;
    let update = presence_update(&mut bob, &alice_id, Duration::from_secs(5))
        .await
        .expect("offline once away");
    assert_eq!(update["online"], false);
    let (online, left_at) = presence_of(&client, addr, &bob_token, &alice_id).await;
    assert!(!online);
    send_text(&client, addr, &bob_token, &alice_id).await;
    assert!(
        next_of_type(&mut alice, "message.new", Duration::from_secs(5))
            .await
            .is_some()
    );

    // Closing it changes nothing anyone can see.
    let _ = alice.close(None).await;
    assert!(presence_update(&mut bob, &alice_id, short).await.is_none());
    assert_eq!(
        presence_of(&client, addr, &bob_token, &alice_id).await,
        (false, left_at)
    );

    let _ = bob.close(None).await;
    let _ = shutdown_tx.send(());
    let _ = server.await;
}

/// Human: A socket opened while the app was on screen is kept by the background connection
/// when the app leaves. `focus:false` with `"background": true` turns it into a background
/// socket; a plain `focus:false` (a browser tab in the background) keeps its user online.
#[tokio::test]
async fn a_socket_kept_for_the_background_connection_goes_offline() {
    let Some(pool) = test_pool().await else {
        eprintln!(
            "skipping a_socket_kept_for_the_background_connection_goes_offline: DATABASE_URL unavailable"
        );
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let (alice_token, alice_id) = register(&client, addr).await;
    let (bob_token, bob_id) = register(&client, addr).await;
    become_contacts(
        &client,
        addr,
        (&alice_token, &alice_id),
        (&bob_token, &bob_id),
    )
    .await;
    let mut bob = connect_authed(addr, &bob_token).await;
    let short = Duration::from_millis(500);

    let mut alice = connect_authed(addr, &alice_token).await;
    let update = presence_update(&mut bob, &alice_id, Duration::from_secs(5))
        .await
        .expect("online");
    assert_eq!(update["online"], true);

    send_frame(&mut alice, json!({ "type": "focus", "focused": false })).await;
    assert!(presence_update(&mut bob, &alice_id, short).await.is_none());
    assert!(presence_of(&client, addr, &bob_token, &alice_id).await.0);

    send_frame(
        &mut alice,
        json!({ "type": "focus", "focused": false, "background": true }),
    )
    .await;
    let update = presence_update(&mut bob, &alice_id, Duration::from_secs(5))
        .await
        .expect("offline");
    assert_eq!(update["online"], false);
    assert!(!presence_of(&client, addr, &bob_token, &alice_id).await.0);

    send_frame(&mut alice, json!({ "type": "focus", "focused": true })).await;
    let update = presence_update(&mut bob, &alice_id, Duration::from_secs(5))
        .await
        .expect("online again");
    assert_eq!(update["online"], true);
    let _ = alice.close(None).await;
    let update = presence_update(&mut bob, &alice_id, Duration::from_secs(5))
        .await
        .expect("offline on close");
    assert_eq!(update["online"], false);

    let _ = bob.close(None).await;
    let _ = shutdown_tx.send(());
    let _ = server.await;
}

/// Human: Removing a phone whose only socket is its background connection still reaches it:
/// that is how a removal wipes a phone without a push distributor.
#[tokio::test]
async fn removing_a_device_closes_its_background_socket() {
    let Some(pool) = test_pool().await else {
        eprintln!(
            "skipping removing_a_device_closes_its_background_socket: DATABASE_URL unavailable"
        );
        return;
    };
    let (addr, shutdown_tx, server) = spawn_app(pool).await;
    let client = reqwest::Client::new();
    let alice = unique_username();
    let (phone_token, alice_id) = register_as(&client, addr, &alice).await;
    let (android_token, android_id) = log_in(&client, addr, &alice, None).await;
    let (mut android, _) = connect_background(addr, &android_token).await;
    let (bob_token, bob_id) = register(&client, addr).await;
    become_contacts(
        &client,
        addr,
        (&phone_token, &alice_id),
        (&bob_token, &bob_id),
    )
    .await;
    send_text(&client, addr, &bob_token, &alice_id).await;
    assert!(
        next_of_type(&mut android, "message.new", Duration::from_secs(5))
            .await
            .is_some()
    );

    let removed = client
        .delete(format!("http://{addr}/api/v1/devices/{android_id}"))
        .bearer_auth(&phone_token)
        .send()
        .await
        .expect("remove device");
    assert_eq!(removed.status(), reqwest::StatusCode::NO_CONTENT);
    assert_removed(&mut android).await;

    let _ = shutdown_tx.send(());
    let _ = server.await;
}

/// Human: Behind a load balancer the push decision and the presence answer can come from a
/// replica that does not hold the socket. A background socket writes no online entry to
/// Redis, so it counts there as nowhere; in front it counts like any socket. Also needs
/// `REDIS_URL` (a throwaway instance).
#[tokio::test]
async fn a_background_socket_counts_nowhere_across_replicas() {
    let Some(pool) = test_pool().await else {
        eprintln!(
            "skipping a_background_socket_counts_nowhere_across_replicas: DATABASE_URL unavailable"
        );
        return;
    };
    let Ok(redis_url) = std::env::var("REDIS_URL") else {
        eprintln!("skipping a_background_socket_counts_nowhere_across_replicas: REDIS_URL unset");
        return;
    };
    let api_state = redis_replica(pool.clone(), &redis_url).await;
    let (api, api_shutdown, api_server) = spawn_state(api_state.clone()).await;
    let (ws, ws_shutdown, ws_server) = spawn_state(redis_replica(pool, &redis_url).await).await;
    let client = reqwest::Client::new();
    let (token, user_id) = register(&client, api).await;
    let user: Uuid = user_id.parse().unwrap();

    let (mut socket, ok) = connect_background(ws, &token).await;
    let device: Uuid = ok["device_id"].as_str().unwrap().parse().unwrap();
    let hub = &api_state.realtime;
    // Seen from the other replica: not online, not in front (so its pushes go out).
    assert!(!hub.is_user_online(user).await);
    assert!(!hub.is_device_online(user, device).await);
    assert!(!hub.is_device_foreground(user, device).await);

    // Waits until the other replica sees the user online and the device in front (or both
    // not), or fails: the frame is handled on the socket's replica in its own time.
    let settle = |want: bool| {
        let hub = hub.clone();
        async move {
            let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
            loop {
                let seen = (
                    hub.is_user_online(user).await,
                    hub.is_device_online(user, device).await,
                    hub.is_device_foreground(user, device).await,
                );
                if seen == (want, want, want) {
                    return;
                }
                assert!(
                    tokio::time::Instant::now() < deadline,
                    "never became {want}: {seen:?}"
                );
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        }
    };
    send_frame(&mut socket, json!({ "type": "focus", "focused": true })).await;
    settle(true).await;

    send_frame(&mut socket, json!({ "type": "focus", "focused": false })).await;
    settle(false).await;

    let _ = socket.close(None).await;
    for (shutdown, server) in [(api_shutdown, api_server), (ws_shutdown, ws_server)] {
        let _ = shutdown.send(());
        let _ = server.await;
    }
}
