//! Integration tests for enabled in-process rate limiter.
//!
//! Human: Uses unique X-Forwarded-For / user keys so parallel suites do not share windows.
//! Agent: AppState with RateLimiter::new(); trust_forwarded_headers=true via test helper.

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use shroud_server::rate_limit::RateLimiter;
use shroud_server::routes;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

fn test_state(pool: sqlx::PgPool, rate_limiter: RateLimiter) -> shroud_server::state::AppState {
    shroud_server::state::AppState::for_integration_tests_with_limiter(pool, rate_limiter)
}

async fn test_app(rate_limiter: RateLimiter) -> Option<axum::Router> {
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
            .with_state(test_state(pool, rate_limiter)),
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
async fn auth_login_rate_limited_by_ip_sets_retry_after() {
    let Some(app) = test_app(RateLimiter::new()).await else {
        eprintln!(
            "skipping auth_login_rate_limited_by_ip_sets_retry_after: DATABASE_URL unavailable"
        );
        return;
    };

    let ip = format!("203.0.113.{}", Uuid::new_v4().as_u128() % 250 + 1);
    let body = json!({
        "username": "does_not_exist_rl",
        "password": "wrong-password-here"
    })
    .to_string();

    let mut saw_limited = false;
    for i in 0..15 {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/auth/login")
                    .header(header::CONTENT_TYPE, "application/json")
                    .header("x-forwarded-for", &ip)
                    .body(Body::from(body.clone()))
                    .expect("request"),
            )
            .await
            .expect("oneshot");

        if response.status() == StatusCode::TOO_MANY_REQUESTS {
            let retry = response
                .headers()
                .get(header::RETRY_AFTER)
                .and_then(|v| v.to_str().ok())
                .expect("Retry-After");
            assert_eq!(retry, "60");
            let json = json_body(response).await;
            assert_eq!(json["error"]["code"], "RATE_LIMITED");
            saw_limited = true;
            eprintln!("auth_login_rate_limited: limited after {} attempts", i + 1);
            break;
        }

        assert!(
            response.status() == StatusCode::UNAUTHORIZED
                || response.status() == StatusCode::BAD_REQUEST,
            "unexpected status before limit: {}",
            response.status()
        );
    }

    assert!(
        saw_limited,
        "expected RATE_LIMITED within 15 login attempts"
    );
}

#[tokio::test]
async fn untrusted_forwarded_headers_share_unknown_ip_bucket() {
    // Human: When TRUST_FORWARDED_HEADERS is false, XFF must not isolate rate-limit keys.
    let database_url = match std::env::var("DATABASE_URL") {
        Ok(url) => url,
        Err(_) => {
            eprintln!(
                "skipping untrusted_forwarded_headers_share_unknown_ip_bucket: DATABASE_URL unavailable"
            );
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

    let mut state = shroud_server::state::AppState::for_integration_tests_with_limiter(
        pool,
        RateLimiter::new(),
    );
    state.trust_forwarded_headers = false;
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state);

    let body = json!({
        "username": "does_not_exist_xff",
        "password": "wrong-password-here"
    })
    .to_string();

    let mut limited = 0u32;
    for i in 0..20 {
        let ip = format!("198.51.100.{i}");
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/auth/login")
                    .header(header::CONTENT_TYPE, "application/json")
                    .header("x-forwarded-for", &ip)
                    .body(Body::from(body.clone()))
                    .expect("request"),
            )
            .await
            .expect("oneshot");
        if response.status() == StatusCode::TOO_MANY_REQUESTS {
            limited += 1;
        }
    }

    assert!(
        limited > 0,
        "spoofed XFF must not bypass the shared unknown IP bucket when untrusted"
    );
}

#[tokio::test]
async fn message_send_rate_limited_per_user() {
    let Some(app) = test_app(RateLimiter::new()).await else {
        eprintln!("skipping message_send_rate_limited_per_user: DATABASE_URL unavailable");
        return;
    };

    async fn register(app: &axum::Router, name: &str) -> (String, String) {
        let id = &Uuid::new_v4().simple().to_string()[..12];
        let username = format!("{name}_{id}");
        let password = "correct-horse-battery";
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
                            "device_name": "RL"
                        })
                        .to_string(),
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

    let (token_a, user_a) = register(&app, "ra").await;
    let (token_b, user_b) = register(&app, "rb").await;

    // Become contacts.
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
    let reqs = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/contacts/requests")
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let reqs_json = json_body(reqs).await;
    let request_id = reqs_json["requests"][0]["id"]
        .as_str()
        .expect("pending incoming contact request id");
    app.clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/contacts/requests/{request_id}/accept"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");

    let ciphertext = "YQ=="; // "a" base64
    let mut saw_limited = false;
    let _ = user_a;
    // MESSAGE_SEND_USER is 120/min — send until RATE_LIMITED.
    for i in 0..125 {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/messages")
                    .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(
                        json!({
                            "peer_user_id": user_b,
                            "client_message_id": Uuid::new_v4(),
                            "content_type": "text",
                            "ciphertext": ciphertext
                        })
                        .to_string(),
                    ))
                    .expect("request"),
            )
            .await
            .expect("oneshot");

        if response.status() == StatusCode::TOO_MANY_REQUESTS {
            let retry = response
                .headers()
                .get(header::RETRY_AFTER)
                .and_then(|v| v.to_str().ok())
                .expect("Retry-After");
            assert_eq!(retry, "60");
            saw_limited = true;
            eprintln!("message_send_rate_limited: limited after {} sends", i + 1);
            break;
        }
        assert!(
            response.status() == StatusCode::CREATED || response.status() == StatusCode::OK,
            "unexpected status: {}",
            response.status()
        );
    }

    assert!(
        saw_limited,
        "expected message send RATE_LIMITED within 125 tries"
    );
}

#[tokio::test]
async fn session_status_rate_limited_by_ip() {
    let Some(app) = test_app(RateLimiter::new()).await else {
        eprintln!("skipping session_status_rate_limited_by_ip: DATABASE_URL unavailable");
        return;
    };

    // A locked browser asks about twice a minute; a script probing hashes hits the budget.
    let ip = format!("198.51.100.{}", Uuid::new_v4().as_u128() % 250 + 1);
    let body = json!({ "token_hash": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" }).to_string();
    let mut statuses = Vec::new();
    for _ in 0..70 {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/auth/session-status")
                    .header(header::CONTENT_TYPE, "application/json")
                    .header("x-forwarded-for", &ip)
                    .body(Body::from(body.clone()))
                    .expect("request"),
            )
            .await
            .expect("oneshot");
        statuses.push(response.status());
    }
    assert!(
        statuses[..60]
            .iter()
            .all(|status| *status == StatusCode::OK)
    );
    assert!(
        statuses[60..]
            .iter()
            .all(|status| *status == StatusCode::TOO_MANY_REQUESTS)
    );
}
