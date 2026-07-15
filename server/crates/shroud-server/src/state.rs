//! Shared Axum application state.

use sqlx::PgPool;

/// State injected into every API handler.
#[derive(Clone)]
pub struct AppState {
    /// Shared Postgres pool for metadata queries (no ciphertext storage here yet).
    pub pool: PgPool,
}
