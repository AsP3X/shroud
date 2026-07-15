//! Shared Axum application state.

use sqlx::PgPool;

/// State injected into every API handler.
#[derive(Clone)]
pub struct AppState {
    /// Shared Postgres pool for metadata queries (no message plaintext).
    pub pool: PgPool,
    /// Optional Nebular base URL; `None` enables stub media presigns.
    pub nebular_url: Option<String>,
    /// Object storage bucket name for encrypted media.
    pub media_bucket: String,
}
