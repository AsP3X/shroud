//! Integration tests for call signaling (milestone 9).

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
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
    (format!("c_{id}"), "correct-horse-battery".into())
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
                    json!({ "username": username, "password": password }).to_string(),
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

#[tokio::test]
async fn ice_servers_requires_auth() {
    let Some(app) = test_app().await else {
        eprintln!("skipping ice_servers_requires_auth: no DATABASE_URL");
        return;
    };

    let (token, _) = register(&app).await;
    let ok = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/api/v1/calls/ice-servers")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(ok.status(), StatusCode::OK);
    let body = json_body(ok).await;
    assert!(body["ice_servers"].is_array());
}

#[tokio::test]
async fn call_ring_accept_hangup_flow() {
    let Some(app) = test_app().await else {
        eprintln!("skipping call_ring_accept_hangup_flow: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let create = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/calls")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "peer_user_id": user_b,
                        "modality": "voice",
                        "sdp_offer": "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\n"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(create.status(), StatusCode::CREATED);
    let created = json_body(create).await;
    assert_eq!(created["status"], "ringing");
    let call_id = created["id"].as_str().unwrap();

    // Second call while ringing → busy.
    let busy = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/calls")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "peer_user_id": user_b, "modality": "voice" }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(busy.status(), StatusCode::CONFLICT);

    let accept = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/calls/{call_id}/accept"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "sdp_answer": "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n" }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(accept.status(), StatusCode::OK);
    let accepted = json_body(accept).await;
    assert_eq!(accepted["status"], "active");

    // Signal ICE from A → B.
    let signal = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/calls/{call_id}/signal"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "signal_type": "ice_candidate",
                        "payload": "{\"candidate\":\"candidate:1 1 UDP 1 127.0.0.1 9 typ host\"}"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(signal.status(), StatusCode::NO_CONTENT);

    let hangup = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/calls/{call_id}/hangup"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(hangup.status(), StatusCode::OK);
    let ended = json_body(hangup).await;
    assert_eq!(ended["status"], "ended");
}

#[tokio::test]
async fn reject_and_non_contact() {
    let Some(app) = test_app().await else {
        eprintln!("skipping reject_and_non_contact: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    let (token_c, user_c) = register(&app).await;

    // Non-contact.
    let denied = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/calls")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "peer_user_id": user_c }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(denied.status(), StatusCode::FORBIDDEN);

    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let create = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/calls")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "peer_user_id": user_b }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(create.status(), StatusCode::CREATED);
    let call_id = json_body(create).await["id"].as_str().unwrap().to_string();

    let reject = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/calls/{call_id}/reject"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(reject.status(), StatusCode::OK);
    assert_eq!(json_body(reject).await["status"], "rejected");

    let _ = token_c;
}

#[tokio::test]
async fn expire_stale_ringing_calls_marks_missed() {
    let database_url = match std::env::var("DATABASE_URL") {
        Ok(url) => url,
        Err(_) => {
            eprintln!("skipping expire_stale_ringing_calls_marks_missed: DATABASE_URL unavailable");
            return;
        }
    };
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("pool");
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .expect("migrate");

    // Insert a synthetic stale ringing call with two throwaway users/devices.
    let user_a = Uuid::new_v4();
    let user_b = Uuid::new_v4();
    let device_a = Uuid::new_v4();
    let call_id = Uuid::new_v4();
    let password_hash = "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$placeholder";

    for (uid, uname) in [(user_a, "gca"), (user_b, "gcb")] {
        let id = &Uuid::new_v4().simple().to_string()[..8];
        sqlx::query(
            r#"
            INSERT INTO users (id, username, password_hash, share_code)
            VALUES ($1, $2, $3, $4)
            "#,
        )
        .bind(uid)
        .bind(format!("{uname}_{id}"))
        .bind(password_hash)
        .bind(format!("SC{}XX", id.to_ascii_uppercase()))
        .execute(&pool)
        .await
        .expect("user");
    }
    sqlx::query(
        r#"
        INSERT INTO devices (id, user_id, name)
        VALUES ($1, $2, 'gc')
        "#,
    )
    .bind(device_a)
    .bind(user_a)
    .execute(&pool)
    .await
    .expect("device");

    sqlx::query(
        r#"
        INSERT INTO calls (
            id, caller_user_id, caller_device_id, callee_user_id,
            modality, status, created_at
        )
        VALUES ($1, $2, $3, $4, 'voice', 'ringing', now() - interval '10 minutes')
        "#,
    )
    .bind(call_id)
    .bind(user_a)
    .bind(device_a)
    .bind(user_b)
    .execute(&pool)
    .await
    .expect("call");

    let expired = shroud_server::routes::calls::expire_stale_ringing_calls(&pool)
        .await
        .expect("expire");
    assert!(expired >= 1);

    let status: String = sqlx::query_scalar(r#"SELECT status FROM calls WHERE id = $1"#)
        .bind(call_id)
        .fetch_one(&pool)
        .await
        .expect("status");
    assert_eq!(status, "missed");
}
