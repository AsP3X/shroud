//! Liveness and readiness probe for load balancers and local dev.

use axum::Json;
use serde::Serialize;

use crate::error::AppError;
use crate::state::AppState;

/// JSON payload for the health endpoint.
#[derive(Debug, Serialize)]
pub struct HealthResponse {
    pub status: &'static str,
    pub database: &'static str,
}

/// Confirms the process is up and Postgres accepts a simple query.
pub async fn health(
    state: axum::extract::State<AppState>,
) -> Result<Json<HealthResponse>, AppError> {
    // Human: A lightweight query proves migrations ran and the pool is usable.
    // Agent: DB SELECT 1; RETURNS ok/degraded; no user data touched.
    let database = match sqlx::query_scalar::<_, i32>("SELECT 1")
        .fetch_one(&state.pool)
        .await
    {
        Ok(_) => "ok",
        Err(err) => {
            tracing::warn!(error = %err, "health check database query failed");
            "degraded"
        }
    };

    Ok(Json(HealthResponse {
        status: "ok",
        database,
    }))
}
