//! Integration tests for push registration: APNs tokens, and Web Push subscriptions from
//! browsers and from the Android app (UnifiedPush, with its distributor host policy).

use std::collections::HashMap;
use std::net::IpAddr;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD as B64URL};
use http_body_util::BodyExt;
use ring::agreement::{ECDH_P256, EphemeralPrivateKey};
use ring::rand::SystemRandom;
use serde_json::{Value, json};
use shroud_server::push::{PushService, UnifiedPushPolicy};
use shroud_server::routes;
use shroud_server::state::AppState;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

fn test_state(pool: sqlx::PgPool) -> shroud_server::state::AppState {
    shroud_server::state::AppState::for_integration_tests(pool)
}

async fn test_app() -> Option<axum::Router> {
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
    Some(
        axum::Router::new()
            .merge(routes::router())
            .with_state(test_state(pool)),
    )
}

async fn json_body(response: axum::response::Response) -> Value {
    let body = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    serde_json::from_slice(&body).expect("json")
}

#[tokio::test]
async fn register_push_token() {
    let Some(app) = test_app().await else {
        eprintln!("skipping register_push_token: no DATABASE_URL");
        return;
    };

    let username = format!("p_{}", &Uuid::new_v4().simple().to_string()[..12]);
    let reg = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username": username,
                        "password": "correct-horse-battery"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(reg.status(), StatusCode::CREATED);
    let body = json_body(reg).await;
    let token = body["token"].as_str().unwrap();

    let put = app
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/push/token")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "token": "abcdef0123456789",
                        "environment": "sandbox"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(put.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn logout_forgets_the_devices_push_token() {
    let Ok(database_url) = std::env::var("DATABASE_URL") else {
        eprintln!("skipping logout_forgets_the_devices_push_token: no DATABASE_URL");
        return;
    };
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("connect");
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .expect("migrate");
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(test_state(pool.clone()));

    let username = format!("p_{}", &Uuid::new_v4().simple().to_string()[..12]);
    let reg = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username": username, "password": "correct-horse-battery" })
                        .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(reg.status(), StatusCode::CREATED);
    let body = json_body(reg).await;
    let token = body["token"].as_str().unwrap().to_owned();
    let device_id: Uuid = body["device"]["id"].as_str().unwrap().parse().unwrap();

    let put = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/push/token")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "token": "abcdef0123456789", "environment": "sandbox" }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(put.status(), StatusCode::NO_CONTENT);

    let tokens = || async {
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM push_tokens WHERE device_id = $1")
            .bind(device_id)
            .fetch_one(&pool)
            .await
            .expect("count tokens")
    };
    assert_eq!(tokens().await, 1);

    let logout = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/logout")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);

    // A logged-out phone must not keep receiving this account's pushes and calls.
    assert_eq!(tokens().await, 0);
    // The device itself stays: its messages and uploads cascade from it.
    let devices: i64 = sqlx::query_scalar("SELECT count(*) FROM devices WHERE id = $1")
        .bind(device_id)
        .fetch_one(&pool)
        .await
        .expect("count devices");
    assert_eq!(devices, 1);
}

// MARK: Android (UnifiedPush) subscriptions

/// The refusal an Android app shows as "this server refuses your distributor".
const REFUSED_DISTRIBUTOR: &str = "This server doesn’t send to that UnifiedPush distributor.";

/// A recording state whose Android subscriptions are held to `policy`. None without
/// `DATABASE_URL` (the test skips); set but unusable fails loudly.
async fn android_state(policy: UnifiedPushPolicy) -> Option<(axum::Router, AppState)> {
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
    let mut state = test_state(pool.clone());
    state.push = PushService::recording_with_unifiedpush(pool, state.realtime.clone(), policy);
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state.clone());
    Some((app, state))
}

/// Registers a fresh account; returns (token, device id).
async fn sign_up(app: &axum::Router) -> (String, Uuid) {
    let username = format!("up_{}", &Uuid::new_v4().simple().to_string()[..12]);
    let reg = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username": username, "password": "correct-horse-battery" })
                        .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(reg.status(), StatusCode::CREATED);
    let body = json_body(reg).await;
    (
        body["token"].as_str().unwrap().to_string(),
        body["device"]["id"].as_str().unwrap().parse().unwrap(),
    )
}

/// `PUT /push/web/subscription` with fresh RFC 8291 keys and `client` (left out when None).
async fn subscribe(
    app: &axum::Router,
    token: &str,
    endpoint: &str,
    client: Option<&str>,
) -> (StatusCode, Value) {
    let key = EphemeralPrivateKey::generate(&ECDH_P256, &SystemRandom::new()).unwrap();
    let p256dh = B64URL.encode(key.compute_public_key().unwrap().as_ref());
    let mut body = json!({
        "endpoint": endpoint,
        "keys": { "p256dh": p256dh, "auth": B64URL.encode([9u8; 16]) },
    });
    if let Some(client) = client {
        body["client"] = json!(client);
    }
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/push/web/subscription")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(body.to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    let status = response.status();
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    let value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
    (status, value)
}

/// The device's stored subscription: (endpoint, client).
async fn stored(state: &AppState, device_id: Uuid) -> Option<(String, String)> {
    sqlx::query_as("SELECT endpoint, client FROM web_push_subscriptions WHERE device_id = $1")
        .bind(device_id)
        .fetch_optional(&state.pool)
        .await
        .expect("read subscription")
}

/// The refusal for an Android endpoint outside the policy: 400 with the app's copy.
fn assert_refused_distributor(endpoint: &str, (status, body): &(StatusCode, Value)) {
    assert_eq!(*status, StatusCode::BAD_REQUEST, "{endpoint}: {body}");
    assert_eq!(body["error"]["code"], "VALIDATION_ERROR", "{endpoint}");
    assert_eq!(body["error"]["message"], REFUSED_DISTRIBUTOR, "{endpoint}");
}

#[tokio::test]
async fn a_subscription_says_which_client_holds_it() {
    let Some((app, state)) = android_state(UnifiedPushPolicy::default()).await else {
        eprintln!("skipping a_subscription_says_which_client_holds_it: no DATABASE_URL");
        return;
    };
    let (token, device) = sign_up(&app).await;
    let browser = format!("https://fcm.googleapis.com/fcm/send/{}", Uuid::new_v4());

    // Browsers send no `client`, as they always have.
    let (status, body) = subscribe(&app, &token, &browser, None).await;
    assert_eq!(status, StatusCode::NO_CONTENT, "{body}");
    assert_eq!(
        stored(&state, device).await,
        Some((browser.clone(), "browser".into()))
    );
    let (status, _) = subscribe(&app, &token, &browser, Some("browser")).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_eq!(stored(&state, device).await.unwrap().1, "browser");

    // The Android app's endpoint from its distributor replaces it, and says so.
    let android = format!("https://ntfy.sh/up{}?up=1", Uuid::new_v4().simple());
    let (status, body) = subscribe(&app, &token, &android, Some("android")).await;
    assert_eq!(status, StatusCode::NO_CONTENT, "{body}");
    assert_eq!(
        stored(&state, device).await,
        Some((android.clone(), "android".into()))
    );

    // Anything else is refused and changes nothing.
    for bad in ["ios", "Android", ""] {
        let (status, body) = subscribe(&app, &token, &android, Some(bad)).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{bad}");
        assert_eq!(
            body["error"]["message"],
            "client must be 'browser' or 'android'."
        );
    }
    assert_eq!(stored(&state, device).await.unwrap().1, "android");

    // Logout forgets it like a browser's.
    let logout = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/logout")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);
    assert_eq!(stored(&state, device).await, None);
}

#[tokio::test]
async fn rows_from_before_android_are_browsers() {
    let Some((app, state)) = android_state(UnifiedPushPolicy::default()).await else {
        eprintln!("skipping rows_from_before_android_are_browsers: no DATABASE_URL");
        return;
    };
    let (_token, device) = sign_up(&app).await;
    // Written the way the server wrote rows before migration 028.
    sqlx::query(
        "INSERT INTO web_push_subscriptions (device_id, endpoint, p256dh, auth, updated_at) \
         VALUES ($1, $2, $3, $4, now())",
    )
    .bind(device)
    .bind(format!(
        "https://updates.push.services.mozilla.com/wpush/v2/{}",
        Uuid::new_v4()
    ))
    .bind(vec![4u8; 65])
    .bind(vec![5u8; 16])
    .execute(&state.pool)
    .await
    .expect("insert an old-style row");
    assert_eq!(stored(&state, device).await.unwrap().1, "browser");

    // The column takes nothing else.
    let other =
        sqlx::query("UPDATE web_push_subscriptions SET client = 'ios' WHERE device_id = $1")
            .bind(device)
            .execute(&state.pool)
            .await;
    assert!(
        other.is_err(),
        "the check constraint refuses unknown clients"
    );
}

#[tokio::test]
async fn android_endpoints_are_held_to_the_distributor_policy() {
    let Some((app, state)) = android_state(UnifiedPushPolicy::default()).await else {
        eprintln!("skipping android_endpoints_are_held_to_the_distributor_policy: no DATABASE_URL");
        return;
    };
    let (token, device) = sign_up(&app).await;
    let id = Uuid::new_v4().simple().to_string();

    // The built-in UnifiedPush servers: ntfy, Conversations, Mozilla autopush (Sunup).
    for allowed in [
        format!("https://ntfy.sh/up{id}?up=1"),
        format!("https://up.conversations.im/push/{id}"),
        format!("https://updates.push.services.mozilla.com/wpush/v2/{id}"),
    ] {
        let (status, body) = subscribe(&app, &token, &allowed, Some("android")).await;
        assert_eq!(status, StatusCode::NO_CONTENT, "{allowed}: {body}");
        assert_eq!(stored(&state, device).await.unwrap().0, allowed);
    }

    // Google is never an Android endpoint (an "embedded FCM distributor"), nor are other
    // browser push services, hosts nobody listed, addresses, ports, plain http, or the
    // backslash trick (the host contacted would be attacker.example).
    for refused in [
        format!("https://fcm.googleapis.com/fcm/send/{id}"),
        format!("https://android.googleapis.com/gcm/send/{id}"),
        format!("https://fcm.googleapis.com./fcm/send/{id}"),
        format!("https://web.push.apple.com/{id}"),
        format!("https://push.example.org/up{id}"),
        format!("https://93.184.215.14/up{id}"),
        format!("https://[2606:2800:21f:cb07:6820:80da:af6b:8b2c]/up{id}"),
        format!("https://ntfy.sh:8443/up{id}"),
        format!("http://ntfy.sh/up{id}"),
        format!("http://localhost:2586/up{id}?up=1"),
        format!("https://attacker.example\\.ntfy.sh/up{id}"),
    ] {
        let outcome = subscribe(&app, &token, &refused, Some("android")).await;
        assert_refused_distributor(&refused, &outcome);
    }
    // A refusal leaves the last accepted endpoint in place.
    assert_eq!(
        stored(&state, device).await.unwrap().0,
        format!("https://updates.push.services.mozilla.com/wpush/v2/{id}")
    );

    // Browsers keep their own list: Chrome's push service yes, a distributor no.
    let (status, _) = subscribe(
        &app,
        &token,
        &format!("https://fcm.googleapis.com/fcm/send/{id}"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, body) =
        subscribe(&app, &token, &format!("https://ntfy.sh/up{id}?up=1"), None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(
        body["error"]["message"],
        "endpoint must be an https URL of a browser push service."
    );
}

#[tokio::test]
async fn an_operator_adds_its_own_distributor() {
    // Read the way `Config::from_env` reads it.
    let env = HashMap::from([(
        "UNIFIEDPUSH_ALLOWED_HOSTS",
        " Push.Example.org ,.ntfy.example.net",
    )]);
    let policy = shroud_server::config::unifiedpush_policy(&|name| {
        env.get(name).map(|value| (*value).to_string())
    })
    .expect("policy");
    let Some((app, state)) = android_state(policy).await else {
        eprintln!("skipping an_operator_adds_its_own_distributor: no DATABASE_URL");
        return;
    };
    let (token, device) = sign_up(&app).await;
    for allowed in [
        "https://push.example.org/up1?up=1",
        "https://eu.push.example.org/up1?up=1",
        "https://ntfy.example.net/up1",
        "https://ntfy.sh/up1?up=1",
    ] {
        let (status, body) = subscribe(&app, &token, allowed, Some("android")).await;
        assert_eq!(status, StatusCode::NO_CONTENT, "{allowed}: {body}");
        assert_eq!(stored(&state, device).await.unwrap().0, allowed);
    }
    for refused in [
        "https://push.example.org.evil.example/up1",
        "https://notpush.example.org/up1",
        "https://example.org/up1",
        "https://push.example.org:8443/up1",
    ] {
        let outcome = subscribe(&app, &token, refused, Some("android")).await;
        assert_refused_distributor(refused, &outcome);
    }
    // Listing a Google host does not make it one.
    let google = HashMap::from([("UNIFIEDPUSH_ALLOWED_HOSTS", "googleapis.com")]);
    let policy = shroud_server::config::unifiedpush_policy(&|name| {
        google.get(name).map(|value| (*value).to_string())
    })
    .expect("policy");
    let (app, _) = android_state(policy).await.expect("state");
    let (token, _) = sign_up(&app).await;
    let outcome = subscribe(
        &app,
        &token,
        "https://fcm.googleapis.com/fcm/send/x",
        Some("android"),
    )
    .await;
    assert_refused_distributor("googleapis.com listed", &outcome);
}

#[tokio::test]
async fn public_host_mode_takes_only_names_whose_every_address_is_public() {
    let ip = |text: &str| text.parse::<IpAddr>().unwrap();
    // DNS stands in for the test: the same table the HTTP client would connect with.
    let policy = UnifiedPushPolicy {
        public_hosts: true,
        resolve_overrides: HashMap::from([
            (
                "push.selfhosted.example".to_string(),
                vec![
                    ip("93.184.215.14"),
                    ip("2606:2800:21f:cb07:6820:80da:af6b:8b2c"),
                ],
            ),
            ("push.private.example".to_string(), vec![ip("10.0.0.5")]),
            (
                "push.mixed.example".to_string(),
                vec![ip("93.184.215.14"), ip("127.0.0.1")],
            ),
            (
                "push.mapped.example".to_string(),
                vec![ip("::ffff:192.168.1.1")],
            ),
            ("push.nothing.example".to_string(), vec![]),
            ("attacker.example".to_string(), vec![ip("169.254.169.254")]),
        ]),
        ..UnifiedPushPolicy::default()
    };
    let Some((app, state)) = android_state(policy).await else {
        eprintln!(
            "skipping public_host_mode_takes_only_names_whose_every_address_is_public: no DATABASE_URL"
        );
        return;
    };
    let (token, device) = sign_up(&app).await;

    let (status, body) = subscribe(
        &app,
        &token,
        "https://push.selfhosted.example/up1?up=1",
        Some("android"),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT, "{body}");
    assert_eq!(
        stored(&state, device).await.unwrap().0,
        "https://push.selfhosted.example/up1?up=1"
    );

    for refused in [
        // A private, a partly private, a mapped-private and an unresolvable name.
        "https://push.private.example/up1",
        "https://push.mixed.example/up1",
        "https://push.mapped.example/up1",
        "https://push.nothing.example/up1",
        // Names that are never public, and address literals however written.
        "https://localhost/up1",
        "https://ntfy.local/up1",
        "https://nas.home/up1",
        "https://intranet/up1",
        "https://10.0.0.5/up1",
        "https://0x7f.0.0.1/up1",
        "https://2130706433/up1",
        "https://[::1]/up1",
        // Port 443 only, https only.
        "https://push.selfhosted.example:8443/up1",
        "http://push.selfhosted.example/up1",
        // The host checked is the host contacted: here attacker.example, a metadata address.
        "https://attacker.example\\.push.selfhosted.example/up1",
        // Google stays out in this mode too.
        "https://fcm.googleapis.com/fcm/send/x",
    ] {
        let outcome = subscribe(&app, &token, refused, Some("android")).await;
        assert_refused_distributor(refused, &outcome);
    }
    // Public-host mode is for Android subscriptions only.
    let (status, _) = subscribe(
        &app,
        &token,
        "https://push.selfhosted.example/up1?up=1",
        None,
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn local_http_is_for_end_to_end_runs_only() {
    let policy = UnifiedPushPolicy {
        allow_local_http: true,
        ..UnifiedPushPolicy::default()
    };
    let Some((app, state)) = android_state(policy).await else {
        eprintln!("skipping local_http_is_for_end_to_end_runs_only: no DATABASE_URL");
        return;
    };
    let (token, device) = sign_up(&app).await;
    let local = "http://localhost:2586/upE2E?up=1";
    let (status, body) = subscribe(&app, &token, local, Some("android")).await;
    assert_eq!(status, StatusCode::NO_CONTENT, "{body}");
    assert_eq!(
        stored(&state, device).await,
        Some((local.to_string(), "android".into()))
    );
    for refused in [
        "http://10.0.2.2:2586/upE2E?up=1",
        "http://192.168.1.20:2586/upE2E",
        "http://ntfy.sh/upE2E",
        "http://localhost.evil.example:2586/upE2E",
    ] {
        let outcome = subscribe(&app, &token, refused, Some("android")).await;
        assert_refused_distributor(refused, &outcome);
    }
    // Never for a browser.
    let (status, _) = subscribe(&app, &token, local, None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}
