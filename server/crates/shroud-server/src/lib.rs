//! Shroud HTTP API server.
//!
//! Human: Boots Axum, runs Postgres migrations, and serves the `/api/v1` surface.
//! Agent: READS env config, DB migrate on startup, HTTP router; never handles message plaintext.

pub mod auth;
pub mod config;
pub mod error;
pub mod keys;
pub mod push;
pub mod realtime;
pub mod routes;
pub mod state;

use std::net::SocketAddr;
use std::sync::Arc;

use axum::Router;
use sqlx::postgres::PgPoolOptions;
use tower_http::trace::TraceLayer;
use tracing_subscriber::{EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};

use crate::config::Config;
use crate::error::AppError;
use crate::push::{PushService, apns_config_from_env};
use crate::realtime::RealtimeHub;
use crate::state::AppState;

/// Application entrypoint: configure tracing, connect to Postgres, serve HTTP.
pub async fn run() -> Result<(), AppError> {
    // Human: Local dev uses server/.env; production injects real env vars (dotenv is a no-op if missing).
    // Agent: CALLS dotenvy::dotenv before Config::from_env; never logs secret values.
    let _ = dotenvy::dotenv();

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

    let realtime = Arc::new(RealtimeHub::new());
    if let Some(redis_url) = config.redis_url.clone() {
        match redis::Client::open(redis_url.as_str()) {
            Ok(client) => match redis::aio::ConnectionManager::new(client).await {
                Ok(manager) => {
                    realtime.set_redis(manager).await;
                    crate::realtime::spawn_redis_subscriber(realtime.clone(), redis_url);
                    tracing::info!("realtime fan-out: Redis pub/sub enabled");
                }
                Err(err) => {
                    tracing::error!(
                        error = %err,
                        "REDIS_URL set but connection manager failed; using in-process only"
                    );
                }
            },
            Err(err) => {
                tracing::error!(
                    error = %err,
                    "REDIS_URL invalid; using in-process realtime only"
                );
            }
        }
    } else {
        tracing::info!("realtime fan-out: in-process only (set REDIS_URL for multi-replica)");
    }

    let apns = apns_config_from_env();
    if apns.is_some() {
        tracing::info!("apns: token auth configured");
    } else {
        tracing::info!("apns: credentials not set (token register works; send is log-only)");
    }
    let push = PushService::new(pool.clone(), realtime.clone(), apns);

    let state = AppState {
        pool,
        nebular_url: config.nebular_url.clone(),
        media_bucket: config.nebular_media_bucket.clone(),
        realtime,
        push,
    };
    let app = Router::new()
        .merge(routes::router())
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let addr: SocketAddr = config.socket_addr()?;
    if config.nebular_url.is_some() {
        tracing::info!("media presign: Nebular");
    } else {
        tracing::info!("media presign: stub (set NEBULAR_URL for real object storage)");
    }
    tracing::info!(%addr, "shroud-server listening");
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .map_err(|err| AppError::Internal(format!("bind failed: {err}")))?;

    axum::serve(listener, app)
        .await
        .map_err(|err| AppError::Internal(format!("server error: {err}")))?;

    Ok(())
}
