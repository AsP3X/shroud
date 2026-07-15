//! Shroud HTTP API server.
//!
//! Human: Boots Axum, runs Postgres migrations, and serves the `/api/v1` surface.
//! Agent: READS env config, DB migrate on startup, HTTP router; never handles message plaintext.

pub mod auth;
pub mod config;
pub mod error;
pub mod keys;
pub mod routes;
pub mod state;

use std::net::SocketAddr;

use axum::Router;
use sqlx::postgres::PgPoolOptions;
use tower_http::trace::TraceLayer;
use tracing_subscriber::{EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};

use crate::config::Config;
use crate::error::AppError;
use crate::state::AppState;

/// Application entrypoint: configure tracing, connect to Postgres, serve HTTP.
pub async fn run() -> Result<(), AppError> {
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .with(tracing_subscriber::fmt::layer())
        .init();

    let config = Config::from_env()?;
    let pool = PgPoolOptions::new()
        .max_connections(10)
        .connect(&config.database_url)
        .await
        .map_err(|err| AppError::Internal(format!("database connection failed: {err}")))?;

    // Human: Migrations run automatically at startup so every instance shares schema version.
    // Agent: CALLS sqlx::migrate! against server/migrations/postgres; DB DDL only.
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .map_err(|err| AppError::Internal(format!("migration failed: {err}")))?;

    let state = AppState { pool };
    let app = Router::new()
        .merge(routes::router())
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let addr: SocketAddr = config.socket_addr()?;
    tracing::info!(%addr, "shroud-server listening");
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .map_err(|err| AppError::Internal(format!("bind failed: {err}")))?;

    axum::serve(listener, app)
        .await
        .map_err(|err| AppError::Internal(format!("server error: {err}")))?;

    Ok(())
}
