//! Integration tests for pre-key bundle routes.
//!
//! Human: Requires Postgres — set `DATABASE_URL` (see `server/.env.example`).
//! Agent: HTTP keys routes; public material only.

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

fn b64(len: usize) -> String {
    BASE64.encode(vec![0xAB_u8; len])
}

fn unique_user() -> (String, String) {
    let id = &Uuid::new_v4().simple().to_string()[..12];
    (format!("k_{id}"), "correct-horse-battery".into())
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
                    json!({
                        "username": username,
                        "password": password,
                        "device_name": "Keys Test"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::CREATED);
    let body = json_body(response).await;
    let token = body["token"].as_str().unwrap().to_string();
    let user_id = body["user"]["id"].as_str().unwrap().to_string();
    (token, user_id)
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

fn sample_bundle(otpk_count: usize) -> Value {
    let otpk: Vec<Value> = (0..otpk_count)
        .map(|i| {
            json!({
                "key_id": i,
                "public_key": b64(32)
            })
        })
        .collect();
    json!({
        "registration_id": 42,
        "identity_key": b64(32),
        "signed_pre_key": {
            "key_id": 7,
            "public_key": b64(32),
            "signature": b64(64)
        },
        "one_time_pre_keys": otpk
    })
}

#[tokio::test]
async fn put_status_get_consumes_otpk() {
    let Some(app) = test_app().await else {
        eprintln!("skipping put_status_get_consumes_otpk: DATABASE_URL unavailable");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let put = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/keys/bundle")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(sample_bundle(3).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(put.status(), StatusCode::NO_CONTENT);

    let status = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/keys/status")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(status.status(), StatusCode::OK);
    let status_json = json_body(status).await;
    assert_eq!(status_json["has_identity"], true);
    assert_eq!(status_json["signed_pre_key_id"], 7);
    assert_eq!(status_json["otpk_count"], 3);

    let get = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/keys/bundle/{user_a}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(get.status(), StatusCode::OK);
    let bundle = json_body(get).await;
    assert_eq!(bundle["registration_id"], 42);
    assert!(bundle["one_time_pre_key"]["key_id"].is_number());

    let status2 = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/keys/status")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let status2_json = json_body(status2).await;
    assert_eq!(status2_json["otpk_count"], 2);
}

#[tokio::test]
async fn identity_get_does_not_consume_otpk() {
    let Some(app) = test_app().await else {
        eprintln!("skipping identity_get_does_not_consume_otpk: DATABASE_URL unavailable");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let put = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/keys/bundle")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(sample_bundle(3).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(put.status(), StatusCode::NO_CONTENT);

    let identity = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/keys/identity/{user_a}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(identity.status(), StatusCode::OK);
    let body = json_body(identity).await;
    assert_eq!(body["user_id"], user_a);
    assert!(body["identity_key"].is_string());
    assert!(body.get("one_time_pre_key").is_none());

    let status = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/keys/status")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let status_json = json_body(status).await;
    assert_eq!(status_json["otpk_count"], 3);
}

#[tokio::test]
async fn get_without_keys_returns_keys_required() {
    let Some(app) = test_app().await else {
        eprintln!("skipping get_without_keys_returns_keys_required: DATABASE_URL unavailable");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let get = app
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/keys/bundle/{user_a}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(get.status(), StatusCode::NOT_FOUND);
    let body = json_body(get).await;
    assert_eq!(body["error"]["code"], "KEYS_REQUIRED");
}

#[tokio::test]
async fn get_bundle_requires_contact() {
    let Some(app) = test_app().await else {
        eprintln!("skipping get_bundle_requires_contact: DATABASE_URL unavailable");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, _) = register(&app).await;

    let put = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/keys/bundle")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(sample_bundle(1).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(put.status(), StatusCode::NO_CONTENT);

    // Not contacts — same KEYS_REQUIRED as missing keys (no enumeration).
    let get = app
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/keys/bundle/{user_a}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(get.status(), StatusCode::NOT_FOUND);
    let body = json_body(get).await;
    assert_eq!(body["error"]["code"], "KEYS_REQUIRED");
}

#[tokio::test]
async fn rejects_oversized_otpk_batch() {
    let Some(app) = test_app().await else {
        eprintln!("skipping rejects_oversized_otpk_batch: DATABASE_URL unavailable");
        return;
    };

    let (token, _) = register(&app).await;
    let put = app
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/keys/bundle")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(sample_bundle(101).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(put.status(), StatusCode::BAD_REQUEST);
    let body = json_body(put).await;
    assert_eq!(body["error"]["code"], "VALIDATION_ERROR");
}

fn sample_bundle_with_reg(registration_id: i32, otpk_count: usize) -> Value {
    let mut bundle = sample_bundle(otpk_count);
    bundle["registration_id"] = json!(registration_id);
    bundle
}

#[tokio::test]
async fn list_bundles_returns_all_devices_and_consumes_otpk_each() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping list_bundles_returns_all_devices_and_consumes_otpk_each: DATABASE_URL unavailable"
        );
        return;
    };

    let (username, password) = unique_user();
    let register_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username": username,
                        "password": password,
                        "device_name": "Device One"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(register_resp.status(), StatusCode::CREATED);
    let reg_body = json_body(register_resp).await;
    let token_d1 = reg_body["token"].as_str().unwrap().to_string();
    let user_a = reg_body["user"]["id"].as_str().unwrap().to_string();

    // Second device for the same account (no device_id → new device).
    let login2 = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/login")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username": username,
                        "password": password,
                        "device_name": "Device Two"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(login2.status(), StatusCode::OK);
    let login2_body = json_body(login2).await;
    let token_d2 = login2_body["token"].as_str().unwrap().to_string();
    assert_ne!(login2_body["device"]["id"], reg_body["device"]["id"]);

    for (token, reg_id) in [(&token_d1, 11), (&token_d2, 22)] {
        let put = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("PUT")
                    .uri("/api/v1/keys/bundle")
                    .header(header::AUTHORIZATION, format!("Bearer {token}"))
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(sample_bundle_with_reg(reg_id, 2).to_string()))
                    .expect("request"),
            )
            .await
            .expect("response");
        assert_eq!(put.status(), StatusCode::NO_CONTENT);
    }

    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_d1, &user_a, &token_b, &user_b).await;
    let list = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/keys/bundles/{user_a}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(list.status(), StatusCode::OK);
    let list_json = json_body(list).await;
    assert_eq!(list_json["user_id"], user_a);
    let bundles = list_json["bundles"].as_array().expect("bundles array");
    assert_eq!(bundles.len(), 2);
    for b in bundles {
        assert!(b["device_id"].is_string());
        assert!(b["one_time_pre_key"]["key_id"].is_number());
        assert!(b["signed_pre_key"]["key_id"].is_number());
    }
    let regs: Vec<i64> = bundles
        .iter()
        .map(|b| b["registration_id"].as_i64().unwrap())
        .collect();
    assert!(regs.contains(&11));
    assert!(regs.contains(&22));

    // Each device should have lost one OTPK (2 → 1).
    for token in [&token_d1, &token_d2] {
        let status = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v1/keys/status")
                    .header(header::AUTHORIZATION, format!("Bearer {token}"))
                    .body(Body::empty())
                    .expect("request"),
            )
            .await
            .expect("response");
        assert_eq!(status.status(), StatusCode::OK);
        let status_json = json_body(status).await;
        assert_eq!(status_json["otpk_count"], 1);
    }
}
