//! HTTP route tree for `/api/v1`.

use axum::{
    Router,
    routing::{delete, get, post},
};

use crate::state::AppState;

pub mod auth;
pub mod devices;
pub mod health;

/// Builds the versioned API router mounted at `/api/v1`.
pub fn router() -> Router<AppState> {
    Router::new().nest(
        "/api/v1",
        Router::new()
            .route("/health", get(health::health))
            .route("/auth/register", post(auth::register))
            .route("/auth/login", post(auth::login))
            .route("/auth/logout", post(auth::logout))
            .route("/auth/me", get(auth::me))
            .route("/auth/password", post(auth::change_password))
            .route("/devices", get(devices::list_devices))
            .route("/devices/{id}", delete(devices::delete_device)),
    )
}
