//! HTTP route tree for `/api/v1`.

use axum::{Router, routing::get};

use crate::state::AppState;

pub mod health;

/// Builds the versioned API router mounted at `/api/v1`.
pub fn router() -> Router<AppState> {
    Router::new().nest(
        "/api/v1",
        Router::new().route("/health", get(health::health)),
    )
}
