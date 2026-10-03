//! Integration tests for presence and read receipts (milestone 6).

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use shroud_server::routes;
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

fn unique_user() -> (String, String) {
    let id = &Uuid::new_v4().simple().to_string()[..12];
    (format!("pr_{id}"), "correct-horse-battery".into())
}

async fn register(app: &axum::Router) -> (String, String) {
    let (username, password) = unique_user();
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username_hash": shroud_server::auth::username_hash_b64(&username), "password": password }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::CREATED);
    let body = json_body(response).await;
    (
        body["token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
    )
}

async fn become_contacts(
    app: &axum::Router,
    token_a: &str,
    user_a: &str,
    token_b: &str,
    user_b: &str,
) {
    app.clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/contacts/requests")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "user_id": user_b }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    let r2 = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/contacts/requests")
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "user_id": user_a }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(r2.status(), StatusCode::OK);
}

async fn send_message(
    app: &axum::Router,
    token: &str,
    peer_user_id: &str,
    client_message_id: Uuid,
) -> String {
    let ct = BASE64.encode(b"hello-ciphertext");
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/messages")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "peer_user_id": peer_user_id,
                        "client_message_id": client_message_id,
                        "content_type": "text",
                        "ciphertext": ct,
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::CREATED);
    let body = json_body(response).await;
    body["id"].as_str().unwrap().to_string()
}

#[tokio::test]
async fn presence_contacts_only() {
    let Some(app) = test_app().await else {
        eprintln!("skipping presence_contacts_only: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    let (token_c, user_c) = register(&app).await;

    // Not contacts → forbidden.
    let denied = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/api/v1/presence/{user_b}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(denied.status(), StatusCode::FORBIDDEN);

    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let ok = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/api/v1/presence/{user_b}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(ok.status(), StatusCode::OK);
    let body = json_body(ok).await;
    assert_eq!(body["user_id"], user_b);
    assert_eq!(body["online"], false);

    // Self presence always allowed.
    let self_p = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/api/v1/presence/{user_a}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(self_p.status(), StatusCode::OK);

    // C is not a contact of A.
    let _ = token_c;
    let stranger = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/api/v1/presence/{user_c}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(stranger.status(), StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn read_receipts_single_and_bulk() {
    let Some(app) = test_app().await else {
        eprintln!("skipping read_receipts_single_and_bulk: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let msg1 = send_message(&app, &token_a, &user_b, Uuid::new_v4()).await;
    let msg2 = send_message(&app, &token_a, &user_b, Uuid::new_v4()).await;
    let msg3 = send_message(&app, &token_a, &user_b, Uuid::new_v4()).await;

    // Sender cannot mark own message as read.
    let own = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/messages/{msg1}/read"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(own.status(), StatusCode::BAD_REQUEST);

    // Single read by B.
    let read1 = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/messages/{msg1}/read"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(read1.status(), StatusCode::NO_CONTENT);

    // Idempotent single read.
    let read1b = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/messages/{msg1}/read"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(read1b.status(), StatusCode::NO_CONTENT);

    // Bulk: mark up to msg3 (msg1 already read → marks msg2 + msg3).
    let bulk = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/messages/read")
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "peer_user_id": user_a,
                        "up_to_message_id": msg3,
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(bulk.status(), StatusCode::OK);
    let bulk_body = json_body(bulk).await;
    assert_eq!(bulk_body["marked"], 2);

    // Bulk again → 0 new.
    let bulk2 = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/messages/read")
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "peer_user_id": user_a,
                        "up_to_message_id": msg3,
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(bulk2.status(), StatusCode::OK);
    let bulk2_body = json_body(bulk2).await;
    assert_eq!(bulk2_body["marked"], 0);

    let _ = msg2;
}

/// Human: A background socket (the Android app's background connection) does not make its
/// user online in `GET /presence` until its app is in front, and not after it left again.
#[tokio::test]
async fn presence_ignores_a_background_socket_until_its_app_is_in_front() {
    let Some(database_url) = std::env::var("DATABASE_URL").ok() else {
        eprintln!(
            "skipping presence_ignores_a_background_socket_until_its_app_is_in_front: no DATABASE_URL"
        );
        return;
    };
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("connect to DATABASE_URL");
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .expect("apply migrations");
    let state = test_state(pool);
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state.clone());

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;
    let online = |viewer: String, user: String| {
        let app = app.clone();
        async move {
            let response = app
                .oneshot(
                    Request::builder()
                        .method("GET")
                        .uri(format!("/api/v1/presence/{user}"))
                        .header(header::AUTHORIZATION, format!("Bearer {viewer}"))
                        .body(Body::empty())
                        .expect("request"),
                )
                .await
                .expect("response");
            assert_eq!(response.status(), StatusCode::OK);
            json_body(response).await["online"].as_bool().unwrap()
        }
    };

    let b: Uuid = user_b.parse().unwrap();
    let device: Uuid = sqlx::query_scalar("SELECT id FROM devices WHERE user_id = $1")
        .bind(b)
        .fetch_one(&state.pool)
        .await
        .expect("device");
    let socket = state
        .realtime
        .subscribe_with(
            b,
            device,
            Uuid::new_v4(),
            shroud_server::realtime::SocketMode::Background,
        )
        .await
        .expect("background socket");
    assert!(!online(token_a.clone(), user_b.clone()).await);
    // Its own user sees it the same way.
    assert!(!online(token_b.clone(), user_b.clone()).await);

    state.realtime.set_focus(b, device, socket.id, true).await;
    assert!(online(token_a.clone(), user_b.clone()).await);

    state.realtime.set_focus(b, device, socket.id, false).await;
    assert!(!online(token_a.clone(), user_b.clone()).await);
}

#[tokio::test]
async fn read_requires_participant() {
    let Some(app) = test_app().await else {
        eprintln!("skipping read_requires_participant: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    let (token_c, _user_c) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let msg = send_message(&app, &token_a, &user_b, Uuid::new_v4()).await;

    let outsider = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/messages/{msg}/read"))
                .header(header::AUTHORIZATION, format!("Bearer {token_c}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(outsider.status(), StatusCode::NOT_FOUND);
}
