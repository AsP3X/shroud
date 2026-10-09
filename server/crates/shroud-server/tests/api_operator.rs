//! The internal operator listener.
//!
//! Human: The admin console calls this port. The public port does not have it.
//! Agent: No database for the 404/403 checks (`connect_lazy`). The write test needs
//! `DATABASE_URL` and fails loudly when that URL is set but unusable. A skip is not proof.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use futures_util::{SinkExt, StreamExt};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use shroud_server::client_version::{ClientVersions, Version};
use shroud_server::state::AppState;
use sqlx::postgres::PgPoolOptions;
use tokio_tungstenite::tungstenite::Message;
use tower::ServiceExt;
use uuid::Uuid;

const TOKEN: &str = "operator-test-token";
const PASSWORD: &str = "correct-horse-battery";

fn lazy_pool() -> sqlx::PgPool {
    PgPoolOptions::new()
        .connect_lazy("postgres://unused:unused@127.0.0.1:1/unused")
        .expect("lazy pool")
}

/// A public app that would refuse a request with no `X-Shroud-Client`.
fn gated_app() -> axum::Router {
    let mut state = AppState::for_integration_tests(lazy_pool());
    let mut versions = ClientVersions::default();
    versions.ios.minimum = Some(Version::parse_strict("9.0.0").expect("version"));
    state.client_versions = Arc::new(versions);
    shroud_server::app(state, &[])
}

fn operator_app() -> axum::Router {
    shroud_server::routes::operator::router(Arc::from(TOKEN))
        .with_state(AppState::for_integration_tests(lazy_pool()))
}

async fn call(
    app: &axum::Router,
    method: &str,
    uri: &str,
    authorization: Option<&str>,
) -> axum::response::Response {
    let mut builder = Request::builder().method(method).uri(uri);
    if let Some(authorization) = authorization {
        builder = builder.header(header::AUTHORIZATION, authorization);
    }
    app.clone()
        .oneshot(builder.body(Body::empty()).expect("request"))
        .await
        .expect("response")
}

async fn body_bytes(response: axum::response::Response) -> Vec<u8> {
    response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes()
        .to_vec()
}

#[tokio::test]
async fn public_port_answers_404_for_operator_paths() {
    let app = gated_app();
    let device = Uuid::new_v4();
    for uri in [
        format!("/operator/devices/{device}/remove"),
        "/operator".to_string(),
        "/operator/".to_string(),
    ] {
        let response = call(&app, "POST", &uri, Some(&format!("Bearer {TOKEN}"))).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{uri}");
    }

    let login = call(&app, "POST", "/api/v1/auth/login", None).await;
    assert_eq!(login.status(), StatusCode::UPGRADE_REQUIRED);
    let login_body: Value = serde_json::from_slice(&body_bytes(login).await).expect("json");
    assert_eq!(login_body["error"]["code"], "UPDATE_REQUIRED");

    let live = call(&app, "GET", "/api/v1/health/live", None).await;
    assert_eq!(live.status(), StatusCode::OK);
}

#[tokio::test]
async fn metrics_are_on_the_operator_port_only() {
    let public = shroud_server::app(AppState::for_integration_tests(lazy_pool()), &[]);
    let response = call(&public, "GET", "/api/v1/metrics", None).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    let app = operator_app();
    for authorization in [None, Some("Bearer wrong-token".to_string())] {
        let response = call(&app, "GET", "/operator/metrics", authorization.as_deref()).await;
        assert_eq!(response.status(), StatusCode::FORBIDDEN);
        assert!(body_bytes(response).await.is_empty());
    }
    let response = call(
        &app,
        "GET",
        "/operator/metrics",
        Some(&format!("Bearer {TOKEN}")),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert!(
        response.headers()[header::CONTENT_TYPE]
            .to_str()
            .unwrap()
            .starts_with("text/plain")
    );
    let text = String::from_utf8(body_bytes(response).await).expect("utf-8");
    assert!(text.contains("shroud_http_requests_total"), "{text}");
}

#[tokio::test]
async fn operator_port_rejects_a_bad_token_and_hides_public_routes() {
    let app = operator_app();
    let device = Uuid::new_v4();
    let remove = format!("/operator/devices/{device}/remove");

    for authorization in [
        None,
        Some("Bearer wrong-token".to_string()),
        Some(format!("Basic {TOKEN}")),
        Some(format!("Bearer{TOKEN}")),
        Some(format!("Bearer {}", "x".repeat(400))),
    ] {
        let response = call(&app, "POST", &remove, authorization.as_deref()).await;
        assert_eq!(response.status(), StatusCode::FORBIDDEN);
        assert!(body_bytes(response).await.is_empty());
    }

    let bearer = format!("Bearer {TOKEN}");
    for (method, uri) in [
        ("GET", "/health"),
        ("GET", "/health/live"),
        ("GET", "/metrics"),
        ("GET", "/api/v1/health/ready"),
        ("POST", "/api/v1/auth/login"),
    ] {
        let response = call(&app, method, uri, Some(&bearer)).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{method} {uri}");
    }
    // The path exists, as POST only.
    let wrong_method = call(&app, "GET", &remove, Some(&bearer)).await;
    assert_eq!(wrong_method.status(), StatusCode::METHOD_NOT_ALLOWED);
}

/// With no STUN or TURN configured the call check is one `off` line; it needs no database.
#[tokio::test]
async fn call_check_without_servers_is_one_off_line() {
    let app = operator_app();
    let response = call(
        &app,
        "GET",
        "/operator/calls/check",
        Some(&format!("Bearer {TOKEN}")),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let lines: Value = serde_json::from_slice(&body_bytes(response).await).expect("json");
    assert_eq!(
        lines,
        json!([{
            "item": "No STUN or TURN server",
            "state": "off",
            "detail": "Calls connect only where a direct path exists, and no outside server learns anyone's address."
        }])
    );
    let refused = call(&app, "GET", "/operator/calls/check", None).await;
    assert_eq!(refused.status(), StatusCode::FORBIDDEN);
}

/// Rows a retention job should already have removed fail its line: a session revoked 31 days
/// ago, an upload no message took for two hours, and a call left ringing for five minutes
/// (which also has a silent caller).
#[tokio::test]
async fn retention_check_counts_what_the_jobs_left_behind() {
    let Some(pool) = test_pool().await else {
        eprintln!(
            "skipping retention_check_counts_what_the_jobs_left_behind: DATABASE_URL unavailable"
        );
        return;
    };
    let state = AppState::for_integration_tests(pool.clone());
    let servers = spawn(state.clone()).await;
    let client = reqwest::Client::new();
    let caller = register(&client, servers.public, &fresh_name()).await;
    let callee = register(&client, servers.public, &fresh_name()).await;
    let caller_user = Uuid::parse_str(&caller.user_id).expect("user");
    let caller_device = Uuid::parse_str(&caller.device_id).expect("device");
    let callee_user = Uuid::parse_str(&callee.user_id).expect("user");

    let session: Uuid = sqlx::query_scalar(
        "INSERT INTO sessions (device_id, token_hash, revoked_at)
         VALUES ($1, $2, now() - interval '31 days') RETURNING id",
    )
    .bind(caller_device)
    .bind(Uuid::new_v4().as_bytes().to_vec())
    .fetch_one(&pool)
    .await
    .expect("old session");
    let media: Uuid = sqlx::query_scalar(
        "INSERT INTO media_objects (uploader_user_id, uploader_device_id, bucket, object_key, created_at)
         VALUES ($1, $2, 'retention-check', $3, now() - interval '2 hours') RETURNING id",
    )
    .bind(caller_user)
    .bind(caller_device)
    .bind(Uuid::new_v4().to_string())
    .fetch_one(&pool)
    .await
    .expect("old upload");
    let call: Uuid = sqlx::query_scalar(
        "INSERT INTO calls (caller_user_id, caller_device_id, callee_user_id, modality, status, created_at)
         VALUES ($1, $2, $3, 'voice', 'ringing', now() - interval '5 minutes') RETURNING id",
    )
    .bind(caller_user)
    .bind(caller_device)
    .bind(callee_user)
    .fetch_one(&pool)
    .await
    .expect("old call");

    let response = client
        .get(format!(
            "http://{}/operator/retention/check",
            servers.operator
        ))
        .header(header::AUTHORIZATION, format!("Bearer {TOKEN}"))
        .send()
        .await
        .expect("retention check");
    assert_eq!(response.status(), reqwest::StatusCode::OK);
    let lines: Vec<Value> = response.json().await.expect("json");
    let items: Vec<&str> = lines
        .iter()
        .map(|line| line["item"].as_str().expect("item"))
        .collect();
    assert_eq!(
        items,
        [
            "Revoked sessions",
            "Unlinked media",
            "Unanswered calls",
            "Silent call participants"
        ]
    );
    for line in &lines {
        assert_eq!(line["state"], "failed", "{line}");
    }
    assert!(
        lines[0]["detail"]
            .as_str()
            .expect("detail")
            .contains("past 30 days and an hour: the hourly purge"),
        "{}",
        lines[0]
    );

    sqlx::query("DELETE FROM calls WHERE id = $1")
        .bind(call)
        .execute(&pool)
        .await
        .expect("cleanup call");
    sqlx::query("DELETE FROM media_objects WHERE id = $1")
        .bind(media)
        .execute(&pool)
        .await
        .expect("cleanup media");
    sqlx::query("DELETE FROM sessions WHERE id = $1")
        .bind(session)
        .execute(&pool)
        .await
        .expect("cleanup session");
    let _ = servers.shutdown.send(true);
    for task in servers.tasks {
        let _ = task.await;
    }
}

/// The rate-limit check tries every budget on the live limiter; in-process here (no Redis).
#[tokio::test]
async fn rate_limit_check_tries_every_budget() {
    let app = shroud_server::routes::operator::router(Arc::from(TOKEN)).with_state(
        AppState::for_integration_tests_with_limiter(
            lazy_pool(),
            shroud_server::rate_limit::RateLimiter::new(),
        ),
    );
    let response = call(
        &app,
        "GET",
        "/operator/rate-limits/check",
        Some(&format!("Bearer {TOKEN}")),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let lines: Vec<Value> = serde_json::from_slice(&body_bytes(response).await).expect("json");
    assert_eq!(lines.len(), 22);
    assert_eq!(lines[0]["item"], "Counter store");
    assert_eq!(lines[0]["state"], "off");
    for line in &lines[1..] {
        assert_eq!(line["state"], "ok", "{line}");
    }
    assert_eq!(lines[21]["item"], "Test notification · per device");
}

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

struct Servers {
    public: SocketAddr,
    operator: SocketAddr,
    shutdown: tokio::sync::watch::Sender<bool>,
    tasks: Vec<tokio::task::JoinHandle<()>>,
}

async fn spawn(state: AppState) -> Servers {
    let (shutdown, shutdown_rx) = tokio::sync::watch::channel(false);
    let public_app = shroud_server::app(state.clone(), &[]);
    let operator_app = shroud_server::routes::operator::router(Arc::from(TOKEN)).with_state(state);
    let (public_listener, public) = bind().await;
    let (operator_listener, operator) = bind().await;
    let tasks = vec![
        serve(public_listener, public_app, shutdown_rx.clone()),
        serve(operator_listener, operator_app, shutdown_rx),
    ];
    tokio::time::sleep(Duration::from_millis(50)).await;
    Servers {
        public,
        operator,
        shutdown,
        tasks,
    }
}

async fn bind() -> (tokio::net::TcpListener, SocketAddr) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let addr = listener.local_addr().expect("addr");
    (listener, addr)
}

fn serve(
    listener: tokio::net::TcpListener,
    app: axum::Router,
    mut shutdown: tokio::sync::watch::Receiver<bool>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        axum::serve(listener, app)
            .with_graceful_shutdown(async move {
                let _ = shutdown.changed().await;
            })
            .await
            .expect("serve");
    })
}

struct Account {
    username: String,
    token: String,
    user_id: String,
    device_id: String,
}

async fn register(client: &reqwest::Client, addr: SocketAddr, username: &str) -> Account {
    let response = client
        .post(format!("http://{addr}/api/v1/auth/register"))
        .json(&json!({
            "username_hash": shroud_server::auth::username_hash_b64(username),
            "password": PASSWORD,
        }))
        .send()
        .await
        .expect("register");
    assert_eq!(response.status(), reqwest::StatusCode::CREATED);
    let body: Value = response.json().await.expect("json");
    Account {
        username: username.to_string(),
        token: body["token"].as_str().expect("token").to_string(),
        user_id: body["user"]["id"].as_str().expect("user").to_string(),
        device_id: body["device"]["id"].as_str().expect("device").to_string(),
    }
}

fn fresh_name() -> String {
    format!("op_{}", &Uuid::new_v4().simple().to_string()[..12])
}

async fn operator_post(
    client: &reqwest::Client,
    addr: SocketAddr,
    path: &str,
    authorization: &str,
) -> reqwest::Response {
    client
        .post(format!("http://{addr}{path}"))
        .header(header::AUTHORIZATION, authorization)
        .send()
        .await
        .expect("operator post")
}

type Socket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn connect_authed(addr: SocketAddr, token: &str) -> Socket {
    let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{addr}/api/v1/ws"))
        .await
        .expect("ws connect");
    socket
        .send(Message::Text(
            json!({ "type": "auth", "token": token }).to_string().into(),
        ))
        .await
        .expect("send auth");
    let ok = next_text(&mut socket).await;
    assert_eq!(ok["type"], "auth.ok");
    socket
}

async fn next_text(socket: &mut Socket) -> Value {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    loop {
        let frame = tokio::time::timeout_at(deadline, socket.next())
            .await
            .expect("socket timed out")
            .expect("socket closed")
            .expect("frame");
        let Message::Text(text) = frame else { continue };
        return serde_json::from_str(&text).expect("json");
    }
}

async fn assert_closed(socket: &mut Socket, code: &str) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    let mut saw = false;
    loop {
        match tokio::time::timeout_at(deadline, socket.next()).await {
            Err(_) => panic!("socket still open"),
            Ok(Some(Ok(Message::Text(text)))) => {
                let event: Value = serde_json::from_str(&text).expect("json");
                assert_eq!(event["type"], "auth.error");
                assert_eq!(event["error"]["code"], code);
                saw = true;
            }
            Ok(Some(Ok(Message::Close(_))) | Some(Err(_)) | None) => break,
            Ok(Some(Ok(_))) => {}
        }
    }
    assert!(saw, "no close frame");
}

async fn device_revoked(pool: &sqlx::PgPool, device_id: &str) -> bool {
    sqlx::query_scalar("SELECT revoked_at IS NOT NULL FROM devices WHERE id = $1")
        .bind(Uuid::parse_str(device_id).expect("device id"))
        .fetch_one(pool)
        .await
        .expect("device row")
}

fn token_hash(token: &str) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD.encode(ring::digest::digest(
        &ring::digest::SHA256,
        token.as_bytes(),
    ))
}

#[tokio::test]
async fn operator_writes_close_sockets_like_the_user() {
    let Some(pool) = test_pool().await else {
        eprintln!("skipping operator_writes_close_sockets_like_the_user: DATABASE_URL unavailable");
        return;
    };
    let state = AppState::for_integration_tests(pool);
    let servers = spawn(state.clone()).await;
    let client = reqwest::Client::new();
    let bearer = format!("Bearer {TOKEN}");

    let missed = client
        .post(format!(
            "http://{}/operator/devices/{}/remove",
            servers.public,
            Uuid::new_v4()
        ))
        .header(header::AUTHORIZATION, &bearer)
        .send()
        .await
        .expect("public operator path");
    assert_eq!(missed.status(), reqwest::StatusCode::NOT_FOUND);

    remove_device(&client, &servers, &state).await;
    sign_out(&client, &servers, &state).await;
    delete_account(&client, &servers, &state).await;

    let _ = servers.shutdown.send(true);
    for task in servers.tasks {
        let _ = task.await;
    }
}

/// The push check answers each line from the relays' setup and touches no subscription. Here:
/// no APNs, the generated VAPID key, and a browser subscription on a host the policy doesn't
/// allow, which is refused before anything is sent.
#[tokio::test]
async fn push_check_reports_each_relay_without_sending() {
    let Some(pool) = test_pool().await else {
        eprintln!(
            "skipping push_check_reports_each_relay_without_sending: DATABASE_URL unavailable"
        );
        return;
    };
    let state = AppState::for_integration_tests(pool.clone());
    let servers = spawn(state.clone()).await;
    let client = reqwest::Client::new();
    let account = register(&client, servers.public, &fresh_name()).await;
    let host = format!(
        "{}.example.invalid",
        &Uuid::new_v4().simple().to_string()[..12]
    );
    sqlx::query(
        "INSERT INTO web_push_subscriptions (device_id, endpoint, p256dh, auth, client)
         VALUES ($1, $2, $3, $4, 'browser')",
    )
    .bind(Uuid::parse_str(&account.device_id).expect("device id"))
    .bind(format!("https://{host}/send/{}", Uuid::new_v4()))
    .bind(vec![4u8; 65])
    .bind(vec![7u8; 16])
    .execute(&pool)
    .await
    .expect("subscription");

    let response = client
        .get(format!("http://{}/operator/push/check", servers.operator))
        .header(header::AUTHORIZATION, format!("Bearer {TOKEN}"))
        .send()
        .await
        .expect("push check");
    assert_eq!(response.status(), reqwest::StatusCode::OK);
    let lines: Vec<Value> = response.json().await.expect("json");
    let items: Vec<&str> = lines
        .iter()
        .map(|line| line["item"].as_str().expect("item"))
        .collect();
    assert_eq!(
        items,
        [
            "APNs key",
            "Apple accepts alerts",
            "Apple accepts calls (VoIP)",
            "Web Push key",
            "Browser push services answer",
            "UnifiedPush distributors answer",
        ]
    );
    let state_of = |index: usize| lines[index]["state"].as_str().expect("state");
    assert_eq!(
        [state_of(0), state_of(1), state_of(2)],
        ["off", "off", "off"]
    );
    assert_eq!(state_of(3), "ok", "{}", lines[3]);
    assert_eq!(state_of(4), "failed", "{}", lines[4]);
    let browsers = lines[4]["detail"].as_str().expect("detail");
    assert!(
        browsers.contains(&format!("{host} (1 subscription): no longer allowed")),
        "{browsers}"
    );
    assert!(
        !browsers.contains("/send/"),
        "an endpoint leaked: {browsers}"
    );

    let refused = client
        .get(format!("http://{}/operator/push/check", servers.operator))
        .send()
        .await
        .expect("push check without token");
    assert_eq!(refused.status(), reqwest::StatusCode::FORBIDDEN);

    let _ = servers.shutdown.send(true);
    for task in servers.tasks {
        let _ = task.await;
    }
}

async fn remove_device(client: &reqwest::Client, servers: &Servers, state: &AppState) {
    let account = register(client, servers.public, &fresh_name()).await;
    let mut socket = connect_authed(servers.public, &account.token).await;
    let path = format!("/operator/devices/{}/remove", account.device_id);

    let refused = operator_post(client, servers.operator, &path, "Bearer wrong-token").await;
    assert_eq!(refused.status(), reqwest::StatusCode::FORBIDDEN);
    assert!(refused.bytes().await.expect("body").is_empty());
    assert!(!device_revoked(&state.pool, &account.device_id).await);

    let removed = operator_post(client, servers.operator, &path, &format!("Bearer {TOKEN}")).await;
    assert_eq!(removed.status(), reqwest::StatusCode::NO_CONTENT);
    assert_closed(&mut socket, "DEVICE_REMOVED").await;
    assert!(device_revoked(&state.pool, &account.device_id).await);

    let (mut again, _) =
        tokio_tungstenite::connect_async(format!("ws://{}/api/v1/ws", servers.public))
            .await
            .expect("reconnect");
    again
        .send(Message::Text(
            json!({ "type": "auth", "token": account.token })
                .to_string()
                .into(),
        ))
        .await
        .expect("send auth");
    assert_closed(&mut again, "DEVICE_REMOVED").await;

    let again = operator_post(client, servers.operator, &path, &format!("bearer {TOKEN}")).await;
    assert_eq!(again.status(), reqwest::StatusCode::CONFLICT);
    let unknown = operator_post(
        client,
        servers.operator,
        &format!("/operator/devices/{}/remove", Uuid::new_v4()),
        &format!("Bearer {TOKEN}"),
    )
    .await;
    assert_eq!(unknown.status(), reqwest::StatusCode::NOT_FOUND);
    let bad_id = operator_post(
        client,
        servers.operator,
        "/operator/devices/not-a-uuid/remove",
        &format!("Bearer {TOKEN}"),
    )
    .await;
    assert_eq!(bad_id.status(), reqwest::StatusCode::NOT_FOUND);
}

async fn sign_out(client: &reqwest::Client, servers: &Servers, state: &AppState) {
    let account = register(client, servers.public, &fresh_name()).await;
    let second = client
        .post(format!("http://{}/api/v1/auth/login", servers.public))
        .json(&json!({
            "username_hash": shroud_server::auth::username_hash_b64(&account.username),
            "password": PASSWORD,
        }))
        .send()
        .await
        .expect("second device");
    assert_eq!(second.status(), reqwest::StatusCode::OK);
    let second: Value = second.json().await.expect("json");
    let second_device = second["device"]["id"].as_str().expect("device").to_string();
    let mut socket = connect_authed(servers.public, &account.token).await;

    let path = format!("/operator/users/{}/sign-out-all", account.user_id);
    let signed_out =
        operator_post(client, servers.operator, &path, &format!("Bearer {TOKEN}")).await;
    assert_eq!(signed_out.status(), reqwest::StatusCode::OK);
    let detail: Value = signed_out.json().await.expect("json");
    assert_eq!(detail, json!({ "detail": "2 sessions" }));
    assert_closed(&mut socket, "UNAUTHORIZED").await;
    assert!(!device_revoked(&state.pool, &account.device_id).await);
    assert!(!device_revoked(&state.pool, &second_device).await);

    let me = client
        .get(format!("http://{}/api/v1/auth/me", servers.public))
        .bearer_auth(&account.token)
        .send()
        .await
        .expect("me");
    assert_eq!(me.status(), reqwest::StatusCode::UNAUTHORIZED);

    let status = client
        .post(format!(
            "http://{}/api/v1/auth/session-status",
            servers.public
        ))
        .json(&json!({ "token_hash": token_hash(&account.token) }))
        .send()
        .await
        .expect("session-status");
    assert_eq!(status.status(), reqwest::StatusCode::OK);
    let status: Value = status.json().await.expect("json");
    assert_eq!(status["removed"], false);

    let idle = operator_post(client, servers.operator, &path, &format!("Bearer {TOKEN}")).await;
    assert_eq!(idle.status(), reqwest::StatusCode::CONFLICT);

    let login = client
        .post(format!("http://{}/api/v1/auth/login", servers.public))
        .json(&json!({
            "username_hash": shroud_server::auth::username_hash_b64(&account.username),
            "password": PASSWORD,
        }))
        .send()
        .await
        .expect("login after sign-out");
    assert_eq!(login.status(), reqwest::StatusCode::OK);

    let missing = operator_post(
        client,
        servers.operator,
        &format!("/operator/users/{}/sign-out-all", Uuid::new_v4()),
        &format!("Bearer {TOKEN}"),
    )
    .await;
    assert_eq!(missing.status(), reqwest::StatusCode::NOT_FOUND);
}

async fn delete_account(client: &reqwest::Client, servers: &Servers, state: &AppState) {
    let username = fresh_name();
    let account = register(client, servers.public, &username).await;
    let mut socket = connect_authed(servers.public, &account.token).await;
    let path = format!("/operator/users/{}/delete", account.user_id);

    let deleted = operator_post(client, servers.operator, &path, &format!("bearer {TOKEN}")).await;
    assert_eq!(deleted.status(), reqwest::StatusCode::NO_CONTENT);
    assert_closed(&mut socket, "DEVICE_REMOVED").await;

    let user_id = Uuid::parse_str(&account.user_id).expect("user id");
    let scrubbed: bool = sqlx::query_scalar(
        r#"
        SELECT deleted_at IS NOT NULL AND username_hash IS NULL AND share_code IS NULL
               AND password_hash IS NULL
        FROM users WHERE id = $1
        "#,
    )
    .bind(user_id)
    .fetch_one(&state.pool)
    .await
    .expect("user row");
    assert!(scrubbed);
    let live_devices: i64 = sqlx::query_scalar(
        r#"
        SELECT COUNT(*)::bigint FROM devices
        WHERE user_id = $1 AND (revoked_at IS NULL OR sealed_name IS NOT NULL)
        "#,
    )
    .bind(user_id)
    .fetch_one(&state.pool)
    .await
    .expect("devices");
    assert_eq!(live_devices, 0);

    let me = client
        .get(format!("http://{}/api/v1/auth/me", servers.public))
        .bearer_auth(&account.token)
        .send()
        .await
        .expect("me");
    assert_eq!(me.status(), reqwest::StatusCode::UNAUTHORIZED);
    let login = client
        .post(format!("http://{}/api/v1/auth/login", servers.public))
        .json(&json!({
            "username_hash": shroud_server::auth::username_hash_b64(&username),
            "password": PASSWORD,
        }))
        .send()
        .await
        .expect("login after delete");
    assert_eq!(login.status(), reqwest::StatusCode::UNAUTHORIZED);

    let again = operator_post(client, servers.operator, &path, &format!("Bearer {TOKEN}")).await;
    assert_eq!(again.status(), reqwest::StatusCode::CONFLICT);
    let signed_out = operator_post(
        client,
        servers.operator,
        &format!("/operator/users/{}/sign-out-all", account.user_id),
        &format!("Bearer {TOKEN}"),
    )
    .await;
    assert_eq!(signed_out.status(), reqwest::StatusCode::CONFLICT);
    let missing = operator_post(
        client,
        servers.operator,
        &format!("/operator/users/{}/delete", Uuid::new_v4()),
        &format!("Bearer {TOKEN}"),
    )
    .await;
    assert_eq!(missing.status(), reqwest::StatusCode::NOT_FOUND);

    let reused = register(client, servers.public, &username).await;
    assert_ne!(reused.user_id, account.user_id);
}
