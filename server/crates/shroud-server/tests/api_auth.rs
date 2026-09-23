//! Integration tests for auth register/login/session flows.
//!
//! Human: Requires Postgres — set `DATABASE_URL` (see `server/.env.example`).
//! Agent: HTTP auth routes against real pool + migrations.

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
    // Username max is 32 chars — use a short unique suffix.
    let id = &Uuid::new_v4().simple().to_string()[..12];
    (format!("u_{id}"), "correct-horse-battery".into())
}

#[tokio::test]
async fn register_login_me_logout_flow() {
    let Some(app) = test_app().await else {
        eprintln!("skipping register_login_me_logout_flow: DATABASE_URL unavailable");
        return;
    };

    let (username, password) = unique_user();

    let register = app
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
                        "device_name": "iPhone Test"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");

    assert_eq!(register.status(), StatusCode::CREATED);
    let registered = json_body(register).await;
    let token = registered["token"].as_str().expect("token");
    let device_id = registered["device"]["id"].as_str().expect("device id");
    assert_eq!(registered["user"]["username"], username);
    assert_eq!(registered["device"]["name"], "iPhone Test");
    let share_code = registered["user"]["share_code"]
        .as_str()
        .expect("share_code");
    assert_eq!(share_code.len(), 10);
    assert!(share_code.chars().all(|c| c.is_ascii_alphanumeric()));

    let me = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/auth/me")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(me.status(), StatusCode::OK);
    let me_json = json_body(me).await;
    assert_eq!(me_json["user"]["username"], username);
    assert_eq!(me_json["user"]["share_code"], share_code);
    assert_eq!(me_json["device"]["id"], device_id);

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

    let me_after = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/auth/me")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(me_after.status(), StatusCode::UNAUTHORIZED);

    let login = app
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
                        "device_id": device_id,
                        "device_name": "iPhone Test"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(login.status(), StatusCode::OK);
    let logged_in = json_body(login).await;
    assert_eq!(logged_in["device"]["id"], device_id);
    assert!(logged_in["token"].as_str().is_some());
}

#[tokio::test]
async fn register_rejects_reserved_and_common_password() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping register_rejects_reserved_and_common_password: DATABASE_URL unavailable"
        );
        return;
    };

    let reserved = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username": "admin",
                        "password": "correct-horse-battery"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(reserved.status(), StatusCode::BAD_REQUEST);
    let body = json_body(reserved).await;
    assert_eq!(body["error"]["code"], "USERNAME_RESERVED");

    let common = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username": format!("ok_{}", &Uuid::new_v4().simple().to_string()[..10]),
                        "password": "password"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(common.status(), StatusCode::BAD_REQUEST);
    let body = json_body(common).await;
    assert_eq!(body["error"]["code"], "PASSWORD_TOO_COMMON");
}

#[tokio::test]
async fn purge_revoked_sessions_deletes_old_rows() {
    let Some(app) = test_app().await else {
        eprintln!("skipping purge_revoked_sessions_deletes_old_rows: DATABASE_URL unavailable");
        return;
    };

    let (username, password) = unique_user();
    let register = app
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
                        "device_name": "Purge Test"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(register.status(), StatusCode::CREATED);
    let reg = json_body(register).await;
    let token = reg["token"].as_str().unwrap().to_string();

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

    let pool = sqlx::postgres::PgPoolOptions::new()
        .max_connections(2)
        .connect(&std::env::var("DATABASE_URL").unwrap())
        .await
        .unwrap();

    // Backdate revoked_at past the 30-day retention window.
    sqlx::query(
        r#"
        UPDATE sessions
        SET revoked_at = now() - interval '40 days'
        WHERE revoked_at IS NOT NULL
        "#,
    )
    .execute(&pool)
    .await
    .unwrap();

    let purged = shroud_server::auth::session::purge_revoked_sessions(&pool)
        .await
        .expect("purge");
    assert!(purged >= 1);
}

#[tokio::test]
async fn login_reuses_device_and_lists_devices() {
    let Some(app) = test_app().await else {
        eprintln!("skipping login_reuses_device_and_lists_devices: DATABASE_URL unavailable");
        return;
    };

    let (username, password) = unique_user();
    let register = app
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
                        "device_name": "Phone A"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    let registered = json_body(register).await;
    let token = registered["token"].as_str().unwrap().to_string();
    let device_id = registered["device"]["id"].as_str().unwrap().to_string();

    let devices = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/devices")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(devices.status(), StatusCode::OK);
    let list = json_body(devices).await;
    assert_eq!(list["devices"].as_array().unwrap().len(), 1);
    assert_eq!(list["devices"][0]["is_current"], true);

    // Second login without device_id creates another device.
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
                        "device_name": "Phone B"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(login2.status(), StatusCode::OK);
    let second = json_body(login2).await;
    assert_ne!(second["device"]["id"], device_id);

    let token_b = second["token"].as_str().unwrap();
    let devices2 = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/devices")
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let list2 = json_body(devices2).await;
    assert_eq!(list2["devices"].as_array().unwrap().len(), 2);
}

#[tokio::test]
async fn delete_account_requires_password_and_removes_user() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping delete_account_requires_password_and_removes_user: DATABASE_URL unavailable"
        );
        return;
    };

    let (username, password) = unique_user();
    let register = app
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
                        "device_name": "Delete Me"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(register.status(), StatusCode::CREATED);
    let registered = json_body(register).await;
    let token = registered["token"].as_str().unwrap();

    let wrong = app
        .clone()
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri("/api/v1/auth/account")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "password": "wrong-password-value" }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(wrong.status(), StatusCode::UNAUTHORIZED);

    let deleted = app
        .clone()
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri("/api/v1/auth/account")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "password": password }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);

    let me = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/auth/me")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(me.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn login_at_device_cap_reclaims_an_idle_device() {
    let Some(app) = test_app().await else {
        eprintln!("skipping login_at_device_cap_reclaims_an_idle_device: DATABASE_URL unavailable");
        return;
    };

    let (username, password) = unique_user();
    let auth_request = |uri: &str, body: Value| {
        Request::builder()
            .method("POST")
            .uri(uri)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(body.to_string()))
            .expect("request")
    };

    let register = app
        .clone()
        .oneshot(auth_request(
            "/api/v1/auth/register",
            json!({ "username": username, "password": password }),
        ))
        .await
        .expect("response");
    assert_eq!(register.status(), StatusCode::CREATED);
    let registered = json_body(register).await;
    let first_device = registered["device"]["id"].as_str().unwrap().to_string();
    let first_token = registered["token"].as_str().unwrap().to_string();

    // Fill the cap with live devices.
    for _ in 1..shroud_server::auth::MAX_DEVICES_PER_USER {
        let login = app
            .clone()
            .oneshot(auth_request(
                "/api/v1/auth/login",
                json!({ "username": username, "password": password }),
            ))
            .await
            .expect("response");
        assert_eq!(login.status(), StatusCode::OK);
    }

    // Every device is signed in: nothing to reclaim.
    let refused = app
        .clone()
        .oneshot(auth_request(
            "/api/v1/auth/login",
            json!({ "username": username, "password": password }),
        ))
        .await
        .expect("response");
    assert_eq!(refused.status(), StatusCode::CONFLICT);

    // The first device published keys; its private halves leave with the logout wipe.
    let stale = BASE64.encode([0xAB_u8; 32]);
    let bundle = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/keys/bundle")
                .header(header::AUTHORIZATION, format!("Bearer {first_token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "registration_id": 42,
                        "identity_key": stale,
                        "signed_pre_key": {
                            "key_id": 7,
                            "public_key": stale,
                            "signature": BASE64.encode([0xAB_u8; 64]),
                        },
                        "one_time_pre_keys": (1..=3)
                            .map(|id| json!({ "key_id": 100 + id, "public_key": stale }))
                            .collect::<Vec<_>>(),
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(bundle.status(), StatusCode::NO_CONTENT);

    // The first device logs out and forgets its id; the next login gets that device back.
    let logout = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/logout")
                .header(header::AUTHORIZATION, format!("Bearer {first_token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);

    let reclaimed = app
        .clone()
        .oneshot(auth_request(
            "/api/v1/auth/login",
            json!({ "username": username, "password": password, "device_name": "Browser" }),
        ))
        .await
        .expect("response");
    assert_eq!(reclaimed.status(), StatusCode::OK);
    let reclaimed = json_body(reclaimed).await;
    assert_eq!(reclaimed["device"]["id"], first_device);
    assert_eq!(reclaimed["device"]["name"], "Browser");

    // Nothing the previous holder published is left for peers to encrypt to.
    let reclaimed_token = reclaimed["token"].as_str().unwrap();
    let status = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/keys/status")
                .header(header::AUTHORIZATION, format!("Bearer {reclaimed_token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(status.status(), StatusCode::OK);
    let status = json_body(status).await;
    assert_eq!(status["has_identity"], false);
    assert_eq!(status["signed_pre_key_id"], Value::Null);
    assert_eq!(status["otpk_count"], 0);
}
