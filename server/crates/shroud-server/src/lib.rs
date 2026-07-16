//! Shroud HTTP API server.
//!
//! Human: Boots Axum, runs Postgres migrations, and serves the `/api/v1` surface.
//! Agent: READS env config, DB migrate on startup, HTTP router; never handles message plaintext.

pub mod auth;
pub mod config;
pub mod error;
pub mod keys;
pub mod logging;
pub mod push;
pub mod realtime;
pub mod request_tracking;
pub mod routes;
pub mod state;

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use axum::{Router, extract::Request, middleware};
use sqlx::postgres::PgPoolOptions;
use tower_http::classify::ServerErrorsFailureClass;
use tower_http::trace::{DefaultOnResponse, TraceLayer};
use tower_http::LatencyUnit;
use tracing::{Level, Span};

use crate::config::Config;
use crate::error::AppError;
use crate::push::{ApnsClient, PushService, apns_config_from_env};
use crate::realtime::RealtimeHub;
use crate::state::AppState;

/// Structured span per HTTP request — correlates with `x-request-id` (Ownly-style).
fn make_request_span(request: &Request) -> Span {
    let request_id = request
        .headers()
        .get(&request_tracking::REQUEST_ID_HEADER)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("missing");
    tracing::info_span!(
        "http.request",
        request_id = %request_id,
        method = %request.method(),
        uri = %request.uri(),
        version = ?request.version(),
    )
}

/// Application entrypoint: configure tracing, connect to Postgres, serve HTTP.
pub async fn run() -> Result<(), AppError> {
    // Human: Local dev uses server/.env; production injects real env vars (dotenv is a no-op if missing).
    // Agent: CALLS dotenvy::dotenv before Config::from_env; never logs secret values.
    let _ = dotenvy::dotenv();

    logging::init_subscriber();

    let config = Config::from_env()?;
    tracing::info!(
        host = %config.host,
        port = config.port,
        redis = config.redis_url.is_some(),
        nebular = config.nebular_url.is_some(),
        media_bucket = %config.nebular_media_bucket,
        "configuration loaded"
    );

    tracing::info!("connecting to postgres");
    let pool = PgPoolOptions::new()
        .max_connections(10)
        .connect(&config.database_url)
        .await
        .map_err(|err| AppError::Internal(format!("database connection failed: {err}")))?;
    tracing::info!("postgres connection pool ready");

    // Human: Migrations run automatically at startup so every instance shares schema version.
    // Agent: CALLS sqlx::migrate! against server/migrations/postgres; DB DDL only.
    tracing::info!("running database migrations");
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .map_err(|err| AppError::Internal(format!("migration failed: {err}")))?;
    tracing::info!("database migrations applied");

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

    let apns = match apns_config_from_env() {
        None => {
            tracing::info!(
                "apns: credentials not set (token register works; send is log-only)"
            );
            None
        }
        Some(config) => match ApnsClient::new(config) {
            Ok(client) => {
                tracing::info!(
                    topic = %client.topic(),
                    key_id = %client.key_id(),
                    "apns: HTTP/2 client ready (token auth)"
                );
                Some(client)
            }
            Err(err) => {
                return Err(AppError::Internal(format!(
                    "APNs configured but client failed to initialize: {err}"
                )));
            }
        },
    };
    let push = PushService::new(pool.clone(), realtime.clone(), apns);

    let state = AppState {
        pool,
        nebular_url: config.nebular_url.clone(),
        media_bucket: config.nebular_media_bucket.clone(),
        realtime,
        push,
    };

    // Human: Last `.layer` is outermost — request-id runs first, then TraceLayer sees the header.
    // Agent: OUTER request_id_middleware → TraceLayer → routes.
    let app = Router::new()
        .merge(routes::router())
        .layer(
            TraceLayer::new_for_http()
                .make_span_with(make_request_span)
                .on_response(
                    DefaultOnResponse::new()
                        .level(Level::INFO)
                        .latency_unit(LatencyUnit::Millis),
                )
                .on_failure(
                    |error: ServerErrorsFailureClass, latency: Duration, _span: &Span| {
                        tracing::error!(
                            error = %error,
                            latency_ms = latency.as_millis() as u64,
                            "http request failed"
                        );
                    },
                ),
        )
        .layer(middleware::from_fn(
            request_tracking::request_id_middleware,
        ))
        .with_state(state);

    let addr: SocketAddr = config.socket_addr()?;
    if let Some(ref url) = config.nebular_url {
        tracing::info!(nebular_url = %url, bucket = %config.nebular_media_bucket, "media presign: Nebular");
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
