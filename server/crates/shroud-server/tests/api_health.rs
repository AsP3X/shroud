//! Integration tests for HTTP health probes and error envelope.
//!
//! Human: Requires Postgres — set `DATABASE_URL` (see `server/.env.example`) before running.
//! Agent: GET /health/live, /health/ready, /health; DB optional for unit-style error tests.

use axum::http::{Request, StatusCode};
use axum::{body::Body, response::IntoResponse};
use http_body_util::BodyExt;
use shroud_server::error::AppError;
use shroud_server::routes;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;

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
        redis_required: false,
    }
}

async fn test_pool() -> Result<sqlx::PgPool, AppError> {
    let database_url = std::env::var("DATABASE_URL")
        .map_err(|_| AppError::Internal("DATABASE_URL is not set".into()))?;

    let pool = PgPoolOptions::new()
        .max_connections(2)
        .connect(&database_url)
        .await
        .map_err(|err| AppError::Internal(format!("database connection failed: {err}")))?;

    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .map_err(|err| AppError::Internal(format!("migration failed: {err}")))?;

    Ok(pool)
}

async fn json_body(response: axum::response::Response) -> serde_json::Value {
    let body = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    serde_json::from_slice(&body).expect("json")
}

#[tokio::test]
async fn live_returns_ok_without_dependencies() {
    // Live probe must not require Postgres — use a pool only to build state.
    let pool = match test_pool().await {
        Ok(pool) => pool,
        Err(err) => {
            eprintln!("skipping live_returns_ok_without_dependencies: {err}");
            return;
        }
    };

    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(test_state(pool));

    let response = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/health/live")
                .body(Body::empty())
                .expect("valid request"),
        )
        .await
        .expect("response");

    assert_eq!(response.status(), StatusCode::OK);
    let json = json_body(response).await;
    assert_eq!(json["status"], "ok");
}

#[tokio::test]
async fn ready_and_health_ok_when_database_available() {
    let pool = match test_pool().await {
        Ok(pool) => pool,
        Err(err) => {
            eprintln!("skipping ready_and_health_ok_when_database_available: {err}");
            return;
        }
    };

    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(test_state(pool));

    for uri in ["/api/v1/health/ready", "/api/v1/health"] {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(uri)
                    .body(Body::empty())
                    .expect("valid request"),
            )
            .await
            .expect("response");

        assert_eq!(response.status(), StatusCode::OK, "uri={uri}");
        let json = json_body(response).await;
        assert_eq!(json["status"], "ok", "uri={uri}");
        assert_eq!(json["database"], "ok", "uri={uri}");
        assert_eq!(json["redis"], "skipped", "uri={uri}");
    }
}

#[tokio::test]
async fn ready_fails_when_redis_required_but_missing() {
    let pool = match test_pool().await {
        Ok(pool) => pool,
        Err(err) => {
            eprintln!("skipping ready_fails_when_redis_required_but_missing: {err}");
            return;
        }
    };

    let mut state = test_state(pool);
    state.redis_required = true;
    // No redis attached on hub → readiness must 503.

    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state);

    let response = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/health/ready")
                .body(Body::empty())
                .expect("valid request"),
        )
        .await
        .expect("response");

    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    let json = json_body(response).await;
    assert_eq!(json["status"], "not_ready");
    assert_eq!(json["database"], "ok");
    assert_eq!(json["redis"], "error");
}

#[tokio::test]
async fn health_error_envelope_shape() {
    // Human: Verifies the canonical `{ "error": { "code", "message" } }` contract without DB.
    let err = AppError::not_found("resource missing");
    let response = err.into_response();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    let body = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    let json: serde_json::Value = serde_json::from_slice(&body).expect("json");
    assert_eq!(json["error"]["code"], "NOT_FOUND");
    assert_eq!(json["error"]["message"], "resource missing");
}
