//! Visibility switches: read receipts, typing indicators, presence (migration 025).
//!
//! Human: Each switch works both ways — whoever hides theirs stops seeing everyone else's —
//! and the server enforces both directions (docs/privacy-options.md, phase 2).
//! Agent: Requires Postgres (`DATABASE_URL`); binds an ephemeral listener for the WS cases.

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

struct Server {
    addr: std::net::SocketAddr,
    shutdown: tokio::sync::oneshot::Sender<()>,
    task: tokio::task::JoinHandle<()>,
    client: reqwest::Client,
}

impl Server {
    async fn start(pool: sqlx::PgPool) -> Self {
        let app = axum::Router::new()
            .merge(routes::router())
            .with_state(shroud_server::state::AppState::for_integration_tests(pool));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind");
        let addr = listener.local_addr().expect("addr");
        let (shutdown, shutdown_rx) = tokio::sync::oneshot::channel::<()>();
        let task = tokio::spawn(async move {
            axum::serve(listener, app)
                .with_graceful_shutdown(async {
                    let _ = shutdown_rx.await;
                })
                .await
                .expect("serve");
        });
        tokio::time::sleep(Duration::from_millis(50)).await;
        Server {
            addr,
            shutdown,
            task,
            client: reqwest::Client::new(),
        }
    }

    async fn stop(self) {
        let _ = self.shutdown.send(());
        let _ = self.task.await;
    }

    fn url(&self, path: &str) -> String {
        format!("http://{}/api/v1{path}", self.addr)
    }

    /// Registers a fresh user.
    async fn user(&self) -> User {
        let username = format!("pv_{}", &Uuid::new_v4().simple().to_string()[..12]);
        let response = self
            .client
            .post(self.url("/auth/register"))
            .json(&json!({ "username_hash": shroud_server::auth::username_hash_b64(&username), "password": "correct-horse-battery" }))
            .send()
            .await
            .expect("register");
        assert_eq!(response.status(), reqwest::StatusCode::CREATED);
        let body: Value = response.json().await.expect("json");
        User {
            token: body["token"].as_str().unwrap().to_string(),
            id: body["user"]["id"].as_str().unwrap().to_string(),
        }
    }

    /// Mutual requests connect the pair, as in the contacts tests.
    async fn befriend(&self, a: &User, b: &User) {
        for (from, to) in [(a, b), (b, a)] {
            let response = self
                .client
                .post(self.url("/contacts/requests"))
                .bearer_auth(&from.token)
                .json(&json!({ "user_id": to.id }))
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

    async fn settings(&self, user: &User, update: Option<Value>) -> Value {
        let request = match update {
            Some(body) => self.client.put(self.url("/privacy/settings")).json(&body),
            None => self.client.get(self.url("/privacy/settings")),
        };
        let response = request
            .bearer_auth(&user.token)
            .send()
            .await
            .expect("settings");
        assert_eq!(response.status(), reqwest::StatusCode::OK);
        response.json().await.expect("json")
    }

    async fn send_text(&self, from: &User, to: &User) -> String {
        let response = self
            .client
            .post(self.url("/messages"))
            .bearer_auth(&from.token)
            .json(&json!({
                "peer_user_id": to.id,
                "client_message_id": Uuid::new_v4(),
                "content_type": "text",
                "ciphertext": "aGVsbG8tY2lwaGVydGV4dA==",
            }))
            .send()
            .await
            .expect("send");
        assert_eq!(response.status(), reqwest::StatusCode::CREATED);
        let body: Value = response.json().await.expect("json");
        body["id"].as_str().unwrap().to_string()
    }

    /// `reader` opens the chat with `peer`; returns how many receipts that sent.
    async fn read_chat(&self, reader: &User, peer: &User) -> u64 {
        let response = self
            .client
            .post(self.url(&format!("/conversations/{}/read", peer.id)))
            .bearer_auth(&reader.token)
            .json(&json!({}))
            .send()
            .await
            .expect("read chat");
        assert_eq!(response.status(), reqwest::StatusCode::OK);
        let body: Value = response.json().await.expect("json");
        body["receipts"].as_u64().expect("receipts")
    }

    /// The `read` flag `viewer` sees on its own message `id` in the chat with `peer`.
    async fn read_flag(&self, viewer: &User, peer: &User, id: &str) -> bool {
        let response = self
            .client
            .get(self.url(&format!("/messages?peer_user_id={}", peer.id)))
            .bearer_auth(&viewer.token)
            .send()
            .await
            .expect("list");
        assert_eq!(response.status(), reqwest::StatusCode::OK);
        let body: Value = response.json().await.expect("json");
        let message = body["messages"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["id"] == id)
            .expect("message listed");
        message["read"].as_bool().expect("read flag on own message")
    }

    async fn presence(&self, viewer: &User, of: &User) -> Value {
        let response = self
            .client
            .get(self.url(&format!("/presence/{}", of.id)))
            .bearer_auth(&viewer.token)
            .send()
            .await
            .expect("presence");
        assert_eq!(response.status(), reqwest::StatusCode::OK);
        response.json().await.expect("json")
    }

    async fn socket(&self, user: &User) -> Socket {
        let (mut socket, _) =
            tokio_tungstenite::connect_async(format!("ws://{}/api/v1/ws", self.addr))
                .await
                .expect("ws connect");
        socket
            .send(Message::Text(
                json!({ "type": "auth", "token": user.token })
                    .to_string()
                    .into(),
            ))
            .await
            .expect("send auth");
        assert!(
            next_of_type(&mut socket, "auth.ok", Duration::from_secs(5))
                .await
                .is_some()
        );
        socket
    }
}

struct User {
    token: String,
    id: String,
}

impl Server {
    async fn me(&self, user: &User) -> Value {
        let response = self
            .client
            .get(self.url("/auth/me"))
            .bearer_auth(&user.token)
            .send()
            .await
            .expect("me");
        assert_eq!(response.status(), reqwest::StatusCode::OK);
        response.json().await.expect("json")
    }

    /// Status of `viewer` looking `path` up (`/users/by-username/…` or `/users/by-code/…`).
    async fn lookup(&self, viewer: &User, path: &str) -> reqwest::StatusCode {
        self.client
            .get(self.url(path))
            .bearer_auth(&viewer.token)
            .send()
            .await
            .expect("lookup")
            .status()
    }
}

type Socket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

/// The next event of `kind` (optionally about `user_id`), skipping others; None on timeout.
async fn next_matching(
    socket: &mut Socket,
    kind: &str,
    user_id: Option<&str>,
    wait: Duration,
) -> Option<Value> {
    let deadline = tokio::time::Instant::now() + wait;
    loop {
        let frame = tokio::time::timeout_at(deadline, socket.next())
            .await
            .ok()??
            .ok()?;
        let Message::Text(text) = frame else { continue };
        let event: Value = serde_json::from_str(&text).ok()?;
        if event["type"] == kind && user_id.is_none_or(|id| event["user_id"] == id) {
            return Some(event);
        }
    }
}

async fn next_of_type(socket: &mut Socket, kind: &str, wait: Duration) -> Option<Value> {
    next_matching(socket, kind, None, wait).await
}

async fn send_typing(socket: &mut Socket, peer: &User) {
    socket
        .send(Message::Text(
            json!({ "type": "typing", "peer_user_id": peer.id, "is_typing": true })
                .to_string()
                .into(),
        ))
        .await
        .expect("send typing");
}

#[tokio::test]
async fn settings_default_on_and_update_partially() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping settings_default_on_and_update_partially: DATABASE_URL unavailable");
        return;
    };
    let server = Server::start(pool).await;
    let alice = server.user().await;

    let defaults = server.settings(&alice, None).await;
    assert_eq!(defaults["send_read_receipts"], true);
    assert_eq!(defaults["send_typing"], true);
    assert_eq!(defaults["share_presence"], true);
    assert_eq!(defaults["allow_peer_chat_delete"], false);
    assert_eq!(defaults["discoverable_by_username"], true);

    let updated = server
        .settings(&alice, Some(json!({ "send_typing": false })))
        .await;
    assert_eq!(updated["send_typing"], false);
    assert_eq!(
        updated["send_read_receipts"], true,
        "absent fields stay as they were"
    );
    assert_eq!(updated["share_presence"], true);
    assert_eq!(updated["allow_peer_chat_delete"], false);

    // The older client's body (one field) still works.
    let old_client = server
        .settings(&alice, Some(json!({ "allow_peer_chat_delete": true })))
        .await;
    assert_eq!(old_client["allow_peer_chat_delete"], true);
    assert_eq!(old_client["send_typing"], false);

    server.stop().await;
}

/// Human: Bob hiding his receipts means Alice never sees his reads, and he stops seeing hers.
/// Reads made while hidden are never recorded, so turning receipts back on doesn't reveal them.
#[tokio::test]
async fn read_receipts_hidden_both_ways() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping read_receipts_hidden_both_ways: DATABASE_URL unavailable");
        return;
    };
    let server = Server::start(pool).await;
    let alice = server.user().await;
    let bob = server.user().await;
    server.befriend(&alice, &bob).await;

    // Both on: the read reaches Alice.
    let first = server.send_text(&alice, &bob).await;
    assert_eq!(server.read_chat(&bob, &alice).await, 1);
    assert!(server.read_flag(&alice, &bob, &first).await);

    // Bob hides: his next read isn't recorded, and Alice no longer sees his earlier one.
    server
        .settings(&bob, Some(json!({ "send_read_receipts": false })))
        .await;
    let second = server.send_text(&alice, &bob).await;
    assert_eq!(server.read_chat(&bob, &alice).await, 0);
    assert!(!server.read_flag(&alice, &bob, &second).await);
    assert!(
        !server.read_flag(&alice, &bob, &first).await,
        "hidden while Bob hides his"
    );

    // Bob doesn't see Alice's reads while his are hidden.
    let reply = server.send_text(&bob, &alice).await;
    assert_eq!(
        server.read_chat(&alice, &bob).await,
        0,
        "Bob hides, so Alice's read sends nothing"
    );
    assert!(!server.read_flag(&bob, &alice, &reply).await);

    // The unread count still clears for the reader.
    let conversations: Value = server
        .client
        .get(server.url("/conversations"))
        .bearer_auth(&bob.token)
        .send()
        .await
        .expect("conversations")
        .json()
        .await
        .expect("json");
    let chat = conversations["conversations"]
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["peer"]["id"] == alice.id.as_str())
        .expect("chat listed");
    assert_eq!(chat["unread_count"], 0);

    // Back on: the read made before hiding shows again; the one made while hidden stays unread
    // until Bob reads again, and then it's receipted at that later time.
    server
        .settings(&bob, Some(json!({ "send_read_receipts": true })))
        .await;
    assert!(server.read_flag(&alice, &bob, &first).await);
    assert!(!server.read_flag(&alice, &bob, &second).await);
    assert_eq!(server.read_chat(&bob, &alice).await, 1);
    assert!(server.read_flag(&alice, &bob, &second).await);

    // Single-message and bulk reads obey the switch as well.
    server
        .settings(&alice, Some(json!({ "send_read_receipts": false })))
        .await;
    let third = server.send_text(&alice, &bob).await;
    let single = server
        .client
        .post(server.url(&format!("/messages/{third}/read")))
        .bearer_auth(&bob.token)
        .send()
        .await
        .expect("single read");
    assert_eq!(single.status(), reqwest::StatusCode::NO_CONTENT);
    let bulk: Value = server
        .client
        .post(server.url("/messages/read"))
        .bearer_auth(&bob.token)
        .json(&json!({ "peer_user_id": alice.id, "up_to_message_id": third }))
        .send()
        .await
        .expect("bulk read")
        .json()
        .await
        .expect("json");
    assert_eq!(bulk["marked"], 0);
    server
        .settings(&alice, Some(json!({ "send_read_receipts": true })))
        .await;
    assert!(
        !server.read_flag(&alice, &bob, &third).await,
        "reads while Alice hid were never recorded"
    );

    server.stop().await;
}

#[tokio::test]
async fn presence_hidden_both_ways() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping presence_hidden_both_ways: DATABASE_URL unavailable");
        return;
    };
    let server = Server::start(pool).await;
    let alice = server.user().await;
    let bob = server.user().await;
    server.befriend(&alice, &bob).await;

    // Registering counts as being seen.
    assert!(server.presence(&bob, &alice).await["last_seen_at"].is_string());

    let mut alice_socket = server.socket(&alice).await;
    assert_eq!(server.presence(&bob, &alice).await["online"], true);

    server
        .settings(&alice, Some(json!({ "share_presence": false })))
        .await;
    let hidden = server.presence(&bob, &alice).await;
    assert_eq!(hidden["online"], false);
    assert!(hidden.get("last_seen_at").is_none());

    // Alice no longer sees Bob either.
    let mut bob_socket = server.socket(&bob).await;
    let reciprocal = server.presence(&alice, &bob).await;
    assert_eq!(reciprocal["online"], false);
    assert!(reciprocal.get("last_seen_at").is_none());
    // Her own presence is still hers to see.
    assert_eq!(server.presence(&alice, &alice).await["online"], true);

    server
        .settings(&alice, Some(json!({ "share_presence": true })))
        .await;
    assert_eq!(server.presence(&bob, &alice).await["online"], true);
    assert_eq!(server.presence(&alice, &bob).await["online"], true);

    for socket in [&mut alice_socket, &mut bob_socket] {
        let _ = socket.close(None).await;
    }
    server.stop().await;
}

/// Human: Contacts' apps show the last presence they heard, so hiding has to reach them at once,
/// and a hidden user's connects and disconnects must not reach anyone.
#[tokio::test]
async fn presence_events_follow_the_switch() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping presence_events_follow_the_switch: DATABASE_URL unavailable");
        return;
    };
    let server = Server::start(pool).await;
    let alice = server.user().await;
    let bob = server.user().await;
    server.befriend(&alice, &bob).await;

    let mut bob_socket = server.socket(&bob).await;
    let alice_socket = server.socket(&alice).await;
    let online = next_matching(
        &mut bob_socket,
        "presence.update",
        Some(&alice.id),
        Duration::from_secs(5),
    )
    .await
    .expect("Bob hears Alice come online");
    assert_eq!(online["online"], true);

    server
        .settings(&alice, Some(json!({ "share_presence": false })))
        .await;
    let hidden = next_matching(
        &mut bob_socket,
        "presence.update",
        Some(&alice.id),
        Duration::from_secs(5),
    )
    .await
    .expect("Bob hears Alice hide");
    assert_eq!(hidden["online"], false);
    assert!(hidden["last_seen_at"].is_null());

    // While hidden, going offline and online again tells Bob nothing.
    let mut closing = alice_socket;
    let _ = closing.close(None).await;
    drop(closing);
    let mut alice_again = server.socket(&alice).await;
    assert!(
        next_matching(
            &mut bob_socket,
            "presence.update",
            Some(&alice.id),
            Duration::from_millis(700)
        )
        .await
        .is_none()
    );

    // Sharing again tells Bob she's online right away.
    server
        .settings(&alice, Some(json!({ "share_presence": true })))
        .await;
    let back = next_matching(
        &mut bob_socket,
        "presence.update",
        Some(&alice.id),
        Duration::from_secs(5),
    )
    .await
    .expect("Bob hears Alice share again");
    assert_eq!(back["online"], true);

    let _ = alice_again.close(None).await;
    let _ = bob_socket.close(None).await;
    server.stop().await;
}

#[tokio::test]
async fn typing_hidden_both_ways() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping typing_hidden_both_ways: DATABASE_URL unavailable");
        return;
    };
    let server = Server::start(pool).await;
    let alice = server.user().await;
    let bob = server.user().await;
    server.befriend(&alice, &bob).await;
    let mut a = server.socket(&alice).await;
    let mut b = server.socket(&bob).await;

    send_typing(&mut a, &bob).await;
    assert!(
        next_of_type(&mut b, "typing", Duration::from_secs(5))
            .await
            .is_some()
    );

    server
        .settings(&alice, Some(json!({ "send_typing": false })))
        .await;
    send_typing(&mut a, &bob).await;
    assert!(
        next_of_type(&mut b, "typing", Duration::from_millis(600))
            .await
            .is_none(),
        "Alice hides her typing"
    );
    send_typing(&mut b, &alice).await;
    assert!(
        next_of_type(&mut a, "typing", Duration::from_millis(600))
            .await
            .is_none(),
        "and no longer sees Bob's"
    );
    // Recording a voice note is typing activity too.
    b.send(Message::Text(
        json!({ "type": "recording", "peer_user_id": alice.id, "is_recording": true })
            .to_string()
            .into(),
    ))
    .await
    .expect("send recording");
    assert!(
        next_of_type(&mut a, "recording", Duration::from_millis(600))
            .await
            .is_none()
    );

    server
        .settings(&alice, Some(json!({ "send_typing": true })))
        .await;
    send_typing(&mut b, &alice).await;
    assert!(
        next_of_type(&mut a, "typing", Duration::from_secs(5))
            .await
            .is_some()
    );

    let _ = a.close(None).await;
    let _ = b.close(None).await;
    server.stop().await;
}

/// A username is not a way to find an account. Strangers, contacts, and the account itself
/// all get the same not-found. A share code still resolves, and it does not include a name.
#[tokio::test]
async fn a_username_is_not_a_way_to_find_an_account() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping a_username_is_not_a_way_to_find_an_account: DATABASE_URL unavailable");
        return;
    };
    let server = Server::start(pool).await;
    let alice = server.user().await;
    let friend = server.user().await;
    let stranger = server.user().await;
    server.befriend(&alice, &friend).await;
    let me = server.me(&alice).await;
    assert!(me["user"].get("username").is_none());
    let code = me["user"]["share_code"].as_str().unwrap().to_string();
    let by_name = "/users/by-username/pv_not_a_directory";

    for viewer in [&stranger, &friend, &alice] {
        assert_eq!(
            server.lookup(viewer, by_name).await,
            reqwest::StatusCode::NOT_FOUND
        );
    }

    let found = server
        .client
        .get(server.url(&format!("/users/by-code/{code}")))
        .bearer_auth(&stranger.token)
        .send()
        .await
        .expect("lookup")
        .json::<Value>()
        .await
        .expect("json");
    assert_eq!(found["id"], alice.id.as_str());
    assert_eq!(found["share_code"], code.as_str());
    assert!(found.get("username").is_none());

    server.stop().await;
}

#[tokio::test]
async fn rotating_the_share_code_retires_the_old_one() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping rotating_the_share_code_retires_the_old_one: DATABASE_URL unavailable");
        return;
    };
    let server = Server::start(pool).await;
    let alice = server.user().await;
    let bob = server.user().await;
    let old = server.me(&alice).await["user"]["share_code"]
        .as_str()
        .unwrap()
        .to_string();

    let response = server
        .client
        .post(server.url("/users/me/share-code"))
        .bearer_auth(&alice.token)
        .send()
        .await
        .expect("rotate");
    assert_eq!(response.status(), reqwest::StatusCode::OK);
    let body: Value = response.json().await.expect("json");
    let new = body["share_code"].as_str().unwrap().to_string();
    assert_ne!(new, old);

    assert_eq!(
        server.lookup(&bob, &format!("/users/by-code/{old}")).await,
        reqwest::StatusCode::NOT_FOUND
    );
    let found: Value = server
        .client
        .get(server.url(&format!("/users/by-code/{new}")))
        .bearer_auth(&bob.token)
        .send()
        .await
        .expect("lookup")
        .json()
        .await
        .expect("json");
    assert_eq!(found["id"], alice.id.as_str());
    assert_eq!(
        server.me(&alice).await["user"]["share_code"],
        new.as_str(),
        "the session shows the new code"
    );

    server.stop().await;
}
