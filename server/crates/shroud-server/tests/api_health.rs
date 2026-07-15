//! Integration tests for HTTP-visible API behavior.
//!
//! Human: Requires Postgres — set `DATABASE_URL` (see `server/.env.example`) before running.
//! Agent: HTTP GET /api/v1/health; DB optional for unit-style router tests.

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

#[tokio::test]
async fn health_returns_ok_when_database_is_available() {
    let pool = match test_pool().await {
        Ok(pool) => pool,
        Err(err) => {
            eprintln!("skipping health_returns_ok_when_database_is_available: {err}");
            return;
        }
    };

    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(test_state(pool));

    let response = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/health")
                .body(Body::empty())
                .expect("valid request"),
        )
        .await
        .expect("response");

    assert_eq!(response.status(), StatusCode::OK);

    let body = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    let json: serde_json::Value = serde_json::from_slice(&body).expect("json");
    assert_eq!(json["status"], "ok");
    assert_eq!(json["database"], "ok");
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
