//! Integration tests for auth register/login/session flows.
//!
//! Human: Requires Postgres — set `DATABASE_URL` (see `server/.env.example`).
//! Agent: HTTP auth routes against real pool + migrations.

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use shroud_server::routes;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

fn test_state(pool: sqlx::PgPool) -> shroud_server::state::AppState {
    let realtime = std::sync::Arc::new(shroud_server::realtime::RealtimeHub::new());
    let push = shroud_server::push::PushService::new(pool.clone(), realtime.clone(), None);
    shroud_server::state::AppState {
        pool,
        nebular_url: None,
        media_bucket: "shroud-media".into(),
        realtime,
        push,
        ice_servers: vec![],
        rate_limiter: shroud_server::rate_limit::RateLimiter::disabled(),
    }
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
