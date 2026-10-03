//! Link-preview relay: auth, target policy, byte piping, and limits.
//!
//! Human: Requires Postgres — set `DATABASE_URL` (see `server/.env.example`). The pipe tests
//! point the relay at a local echo listener through `RelayPolicy::for_tests`; the policy
//! tests use the production policy, which must refuse local and literal targets.
//! Agent: Binds ephemeral listeners; tokio-tungstenite client; shuts the server down per test.

use std::net::SocketAddr;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use shroud_server::link_relay::RelayPolicy;
use shroud_server::routes;
use shroud_server::state::AppState;
use sqlx::postgres::PgPoolOptions;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio_tungstenite::tungstenite::Message;
use uuid::Uuid;

type Client =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

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

/// Serves `state` on an ephemeral port; drop the sender to shut it down.
async fn serve(state: AppState) -> (SocketAddr, tokio::sync::oneshot::Sender<()>) {
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state);
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    let addr = listener.local_addr().expect("addr");
    let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();
    tokio::spawn(async move {
        axum::serve(listener, app)
            .with_graceful_shutdown(async {
                let _ = shutdown_rx.await;
            })
            .await
            .expect("serve");
    });
    tokio::time::sleep(Duration::from_millis(50)).await;
    (addr, shutdown_tx)
}

/// A website stand-in: answers every read with `echo:` + the bytes.
async fn echo_listener() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind echo");
    let port = listener.local_addr().expect("echo addr").port();
    tokio::spawn(async move {
        while let Ok((mut socket, _)) = listener.accept().await {
            tokio::spawn(async move {
                let mut buffer = vec![0u8; 128 * 1024];
                while let Ok(read) = socket.read(&mut buffer).await {
                    if read == 0 {
                        break;
                    }
                    let mut reply = b"echo:".to_vec();
                    reply.extend_from_slice(&buffer[..read]);
                    if socket.write_all(&reply).await.is_err() {
                        break;
                    }
                }
            });
        }
    });
    port
}

async fn register(addr: SocketAddr) -> String {
    let id = &Uuid::new_v4().simple().to_string()[..12];
    let response = reqwest::Client::new()
        .post(format!("http://{addr}/api/v1/auth/register"))
        .json(&json!({
            "username_hash": shroud_server::auth::username_hash_b64(format!("r_{id}")),
            "password": "correct-horse-battery",
            "device_name": "Relay Test"
        }))
        .send()
        .await
        .expect("register");
    assert_eq!(response.status(), reqwest::StatusCode::CREATED);
    let body: Value = response.json().await.expect("json");
    body["token"].as_str().expect("token").to_string()
}

async fn open(addr: SocketAddr, hello: Value) -> Client {
    let (mut socket, _) =
        tokio_tungstenite::connect_async(format!("ws://{addr}/api/v1/link-relay"))
            .await
            .expect("connect relay");
    socket
        .send(Message::Text(hello.to_string().into()))
        .await
        .expect("send hello");
    socket
}

/// The first text frame the relay answers with.
async fn reply(socket: &mut Client) -> Value {
    loop {
        let frame = tokio::time::timeout(Duration::from_secs(10), socket.next())
            .await
            .expect("reply in time")
            .expect("frame")
            .expect("ok frame");
        if let Message::Text(text) = frame {
            return serde_json::from_str(&text).expect("json frame");
        }
    }
}

/// True once the relay has closed the socket.
async fn closed(socket: &mut Client) -> bool {
    loop {
        match tokio::time::timeout(Duration::from_secs(10), socket.next()).await {
            Ok(None | Some(Ok(Message::Close(_)) | Err(_))) => return true,
            Ok(Some(Ok(_))) => {}
            Err(_) => return false,
        }
    }
}

#[tokio::test]
async fn relay_refuses_a_connection_without_a_session() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping: DATABASE_URL unavailable");
        return;
    };
    let (addr, shutdown) = serve(AppState::for_integration_tests(pool)).await;

    let mut socket = open(addr, json!({ "type": "connect", "host": "example.com" })).await;
    let answer = reply(&mut socket).await;
    assert_eq!(answer["type"], "error");
    assert_eq!(answer["error"]["code"], "UNAUTHORIZED");
    assert!(closed(&mut socket).await);

    let mut socket = open(
        addr,
        json!({ "type": "connect", "token": "not-a-session", "host": "example.com" }),
    )
    .await;
    assert_eq!(reply(&mut socket).await["error"]["code"], "UNAUTHORIZED");
    let _ = shutdown.send(());
}

#[tokio::test]
async fn production_relay_refuses_local_and_literal_hosts() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping: DATABASE_URL unavailable");
        return;
    };
    let (addr, shutdown) = serve(AppState::for_integration_tests(pool)).await;
    let token = register(addr).await;

    for (host, code) in [
        ("localhost", "FORBIDDEN"),
        ("printer.local", "FORBIDDEN"),
        ("127.0.0.1", "VALIDATION_ERROR"),
        ("10.0.0.8", "VALIDATION_ERROR"),
        ("[::1]", "VALIDATION_ERROR"),
        ("example.com:8443", "VALIDATION_ERROR"),
    ] {
        let mut socket = open(
            addr,
            json!({ "type": "connect", "token": token, "host": host }),
        )
        .await;
        let answer = reply(&mut socket).await;
        assert_eq!(answer["type"], "error", "{host}");
        assert_eq!(answer["error"]["code"], code, "{host}");
        // The refusal never echoes the host back.
        assert!(!answer.to_string().contains(host), "{host}");
    }
    let _ = shutdown.send(());
}

#[tokio::test]
async fn relay_pipes_bytes_both_ways() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping: DATABASE_URL unavailable");
        return;
    };
    let port = echo_listener().await;
    let state =
        AppState::for_integration_tests(pool).with_link_relay_policy(RelayPolicy::for_tests(port));
    let (addr, shutdown) = serve(state).await;
    let token = register(addr).await;

    let mut socket = open(
        addr,
        json!({ "type": "connect", "token": token, "host": "localhost" }),
    )
    .await;
    assert_eq!(reply(&mut socket).await["type"], "connected");
    socket
        .send(Message::Binary(b"hello".to_vec().into()))
        .await
        .expect("send bytes");
    let echoed = loop {
        if let Message::Binary(bytes) = tokio::time::timeout(Duration::from_secs(5), socket.next())
            .await
            .expect("echo in time")
            .expect("frame")
            .expect("ok frame")
        {
            break bytes;
        }
    };
    assert_eq!(&echoed[..], b"echo:hello");
    let _ = shutdown.send(());
}

#[tokio::test]
async fn relay_closes_a_pipe_that_sends_too_much() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping: DATABASE_URL unavailable");
        return;
    };
    let port = echo_listener().await;
    let state =
        AppState::for_integration_tests(pool).with_link_relay_policy(RelayPolicy::for_tests(port));
    let (addr, shutdown) = serve(state).await;
    let token = register(addr).await;

    let mut socket = open(
        addr,
        json!({ "type": "connect", "token": token, "host": "localhost" }),
    )
    .await;
    assert_eq!(reply(&mut socket).await["type"], "connected");
    // A browser sends a TLS handshake and one GET — never 70 KB.
    socket
        .send(Message::Binary(vec![0u8; 70 * 1024].into()))
        .await
        .expect("send");
    assert!(closed(&mut socket).await);
    let _ = shutdown.send(());
}

#[tokio::test]
async fn relay_limits_open_pipes_per_account() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping: DATABASE_URL unavailable");
        return;
    };
    let port = echo_listener().await;
    let state =
        AppState::for_integration_tests(pool).with_link_relay_policy(RelayPolicy::for_tests(port));
    let (addr, shutdown) = serve(state).await;
    let token = register(addr).await;
    let hello = json!({ "type": "connect", "token": token, "host": "localhost" });

    let mut open_pipes = Vec::new();
    for _ in 0..routes::link_relay::MAX_CONCURRENT_PER_USER {
        let mut socket = open(addr, hello.clone()).await;
        assert_eq!(reply(&mut socket).await["type"], "connected");
        open_pipes.push(socket);
    }
    let mut extra = open(addr, hello.clone()).await;
    assert_eq!(reply(&mut extra).await["error"]["code"], "RATE_LIMITED");

    // Closing one frees its slot.
    let mut first = open_pipes.remove(0);
    first.close(None).await.expect("close");
    drop(first);
    tokio::time::sleep(Duration::from_millis(200)).await;
    let mut again = open(addr, hello).await;
    assert_eq!(reply(&mut again).await["type"], "connected");
    let _ = shutdown.send(());
}
