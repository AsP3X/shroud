//! Integration test: enabled in-process rate limiter returns RATE_LIMITED.
//!
//! Human: Uses a unique X-Forwarded-For so parallel suites do not share the IP key.
//! Agent: AppState with RateLimiter::new(); hammer POST /auth/login until 429.

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
    let realtime = std::sync::Arc::new(shroud_server::realtime::RealtimeHub::new());
    let push = shroud_server::push::PushService::new(pool.clone(), realtime.clone(), None);
    shroud_server::state::AppState {
        pool,
        nebular_url: None,
        media_bucket: "shroud-media".into(),
        realtime,
        push,
        ice_servers: vec![],
        rate_limiter,
        redis_required: false,
    }
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
async fn auth_login_rate_limited_by_ip() {
    let Some(app) = test_app(RateLimiter::new()).await else {
        eprintln!("skipping auth_login_rate_limited_by_ip: DATABASE_URL unavailable");
        return;
    };

    // Unique IP key so other tests / prior runs do not poison this window.
    let ip = format!("203.0.113.{}", Uuid::new_v4().as_u128() % 250 + 1);
    let body = json!({
        "username": "does_not_exist_rl",
        "password": "wrong-password-here"
    })
    .to_string();

    let mut saw_limited = false;
    // AUTH_IP budget is 10/min — request until we get RATE_LIMITED.
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
            let json = json_body(response).await;
            assert_eq!(json["error"]["code"], "RATE_LIMITED");
            saw_limited = true;
            eprintln!("auth_login_rate_limited_by_ip: limited after {} attempts", i + 1);
            break;
        }

        // Invalid credentials or validation before limit is fine.
        assert!(
            response.status() == StatusCode::UNAUTHORIZED
                || response.status() == StatusCode::BAD_REQUEST,
            "unexpected status before limit: {}",
            response.status()
        );
    }

    assert!(saw_limited, "expected RATE_LIMITED within 15 login attempts");
}
