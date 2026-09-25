//! Liveness and readiness probes for load balancers and local dev.
//!
//! - `GET /health/live` — process is up (no dependency checks).
//! - `GET /health/ready` — Postgres required; Redis required when `REDIS_URL` was set. The
//!   media store is reported but not required: without it only media fails (with 503), so the
//!   replica keeps serving chats.
//! - `GET /health` — same as readiness (backward compatible).

use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;

use crate::error::AppError;
use crate::state::AppState;

/// Liveness payload — no dependency checks.
#[derive(Debug, Serialize)]
pub struct LiveResponse {
    pub status: &'static str,
}

/// Readiness payload with dependency detail.
#[derive(Debug, Serialize)]
pub struct ReadyResponse {
    /// `"ok"` when all required dependencies are healthy.
    pub status: &'static str,
    pub database: &'static str,
    /// `"ok"` | `"error"` | `"skipped"` (Redis not configured).
    pub redis: &'static str,
    /// `"ok"` | `"error"`: the media store answers (Nebular) or takes writes (local).
    pub media: &'static str,
}

/// `GET /health/live` — always 200 while the process can serve HTTP.
pub async fn live() -> Json<LiveResponse> {
    Json(LiveResponse { status: "ok" })
}

/// `GET /health/ready` and `GET /health` — dependency gate for traffic.
pub async fn ready(state: axum::extract::State<AppState>) -> Response {
    let database = match check_database(&state).await {
        Ok(()) => "ok",
        Err(err) => {
            tracing::warn!(error = %err, "readiness: database check failed");
            "error"
        }
    };

    let redis = if state.redis_required {
        match state.realtime.ping_redis().await {
            Ok(()) => "ok",
            Err(err) => {
                tracing::warn!(error = %err, "readiness: redis check failed");
                "error"
            }
        }
    } else {
        "skipped"
    };

    let media = match state.media.check().await {
        Ok(()) => "ok",
        Err(err) => {
            tracing::warn!(
                backend = state.media.backend_name(),
                error = %err,
                "readiness: media store check failed"
            );
            "error"
        }
    };

    let ready = database == "ok" && (redis == "ok" || redis == "skipped");
    let body = ReadyResponse {
        status: if ready { "ok" } else { "not_ready" },
        database,
        redis,
        media,
    };

    if ready {
        (StatusCode::OK, Json(body)).into_response()
    } else {
        (StatusCode::SERVICE_UNAVAILABLE, Json(body)).into_response()
    }
}

/// Backward-compatible alias: same as readiness (proper 503 when dependencies fail).
pub async fn health(state: axum::extract::State<AppState>) -> Response {
    ready(state).await
}

/// `GET /metrics` — Prometheus text exposition (process-local counters).
pub async fn metrics(state: axum::extract::State<AppState>) -> impl IntoResponse {
    (
        StatusCode::OK,
        [(
            axum::http::header::CONTENT_TYPE,
            "text/plain; version=0.0.4; charset=utf-8",
        )],
        state.metrics.render(),
    )
}

async fn check_database(state: &AppState) -> Result<(), AppError> {
    // Human: Lightweight query proves the pool can talk to Postgres.
    // Agent: DB SELECT 1; no user data.
    sqlx::query_scalar::<_, i32>("SELECT 1")
        .fetch_one(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("database health check failed: {err}")))?;
    Ok(())
}
