//! Shroud HTTP API server.
//!
//! Human: Boots Axum, runs Postgres migrations, and serves the `/api/v1` surface.
//! Agent: READS env config, DB migrate on startup, HTTP router; never handles message plaintext.

pub mod auth;
pub mod config;
pub mod error;
pub mod keys;
pub mod link_relay;
pub mod logging;
pub mod metrics;
pub mod push;
pub mod rate_limit;
pub mod realtime;
pub mod request_tracking;
pub mod routes;
pub mod state;

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use axum::http::{HeaderName, HeaderValue, Method, header};
use axum::{Router, extract::Request, middleware};
use sqlx::postgres::PgPoolOptions;
use tower_http::LatencyUnit;
use tower_http::classify::ServerErrorsFailureClass;
use tower_http::cors::{AllowOrigin, CorsLayer};
use tower_http::trace::{DefaultOnResponse, TraceLayer};
use tracing::{Level, Span};

use crate::config::Config;
use crate::error::AppError;
use crate::push::{ApnsClient, PushService, apns_config_from_env};
use crate::rate_limit::RateLimiter;
use crate::realtime::RealtimeHub;
use crate::state::AppState;

/// Counts HTTP requests / 5xx for Prometheus `/metrics`.
async fn metrics_http_middleware(
    axum::extract::State(state): axum::extract::State<AppState>,
    request: Request,
    next: middleware::Next,
) -> axum::response::Response {
    let response = next.run(request).await;
    if response.status().is_server_error() {
        state.metrics.inc_http_err();
    } else {
        state.metrics.inc_http_ok();
    }
    response
}

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
        database_pool_max = config.database_pool_max,
        run_migrations = config.run_migrations,
        redis = config.redis_url.is_some(),
        trust_forwarded_headers = config.trust_forwarded_headers,
        nebular = config.nebular_url.is_some(),
        media_bucket = %config.nebular_media_bucket,
        "configuration loaded"
    );

    tracing::info!("connecting to postgres");
    let pool = PgPoolOptions::new()
        .max_connections(config.database_pool_max)
        .connect(&config.database_url)
        .await
        .map_err(|err| AppError::Internal(format!("database connection failed: {err}")))?;
    tracing::info!(
        max_connections = config.database_pool_max,
        "postgres connection pool ready"
    );

    // Human: Only the migrator replica should apply DDL when horizontally scaled.
    // Agent: CALLS sqlx::migrate! when RUN_MIGRATIONS=true; skips otherwise.
    if config.run_migrations {
        tracing::info!("running database migrations");
        sqlx::migrate!("../../migrations/postgres")
            .run(&pool)
            .await
            .map_err(|err| AppError::Internal(format!("migration failed: {err}")))?;
        tracing::info!("database migrations applied");
    } else {
        tracing::info!("skipping database migrations (RUN_MIGRATIONS=false)");
    }

    let http_client = reqwest::Client::new();

    let realtime = Arc::new(RealtimeHub::new());
    let rate_limiter = RateLimiter::new();
    let redis_required = config.redis_url.is_some();
    if let Some(redis_url) = config.redis_url.clone() {
        match redis::Client::open(redis_url.as_str()) {
            Ok(client) => match redis::aio::ConnectionManager::new(client).await {
                Ok(manager) => {
                    realtime.set_redis(manager.clone()).await;
                    rate_limiter.set_redis(manager).await;
                    crate::realtime::spawn_redis_subscriber(realtime.clone(), redis_url);
                    tracing::info!("realtime fan-out + rate limits: Redis enabled");
                }
                Err(err) => {
                    tracing::error!(
                        error = %err,
                        "REDIS_URL set but connection manager failed; readiness will report redis error"
                    );
                }
            },
            Err(err) => {
                tracing::error!(
                    error = %err,
                    "REDIS_URL invalid; readiness will report redis error"
                );
            }
        }
    } else {
        tracing::info!(
            "realtime fan-out + rate limits: in-process only (set REDIS_URL for multi-replica)"
        );
    }

    let apns = match apns_config_from_env() {
        None => {
            tracing::info!("apns: credentials not set (token register works; send is log-only)");
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

    tracing::info!(
        ice_server_count = config.ice_servers.len(),
        "webrtc ice servers loaded"
    );

    // Human: Drop abandoned uploads that never linked to a message.
    crate::routes::media::spawn_orphan_gc(pool.clone());
    // Human: Drop revoked session rows after the 30-day retention window.
    crate::auth::session::spawn_revoked_session_purge(pool.clone());
    // Human: Mark unanswered ringing calls as missed so busy detection cannot stick.
    crate::routes::calls::spawn_ringing_call_gc(pool.clone());

    let media_prefer_nebular = config.nebular_url.is_some()
        && std::env::var("MEDIA_PREFER_NEBULAR")
            .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
            .unwrap_or(true);

    let state = AppState {
        pool,
        nebular_url: config.nebular_url.clone(),
        media_bucket: config.nebular_media_bucket.clone(),
        media_prefer_nebular,
        realtime,
        push,
        ice_servers: config.ice_servers.clone(),
        rate_limiter,
        redis_required,
        http_client,
        trust_forwarded_headers: config.trust_forwarded_headers,
        metrics: Arc::new(crate::metrics::Metrics::new()),
        link_relay: Arc::new(crate::routes::link_relay::LinkRelay::new(
            crate::link_relay::RelayPolicy::production(),
        )),
    };

    // Human: Last `.layer` is outermost — request-id runs first, then metrics, then TraceLayer.
    // Agent: OUTER CORS (if any) → request_id → metrics → TraceLayer → routes.
    let mut app = Router::new()
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
        .layer(middleware::from_fn_with_state(
            state.clone(),
            metrics_http_middleware,
        ))
        .layer(middleware::from_fn(request_tracking::request_id_middleware))
        .with_state(state);

    if !config.cors_allowed_origins.is_empty() {
        tracing::info!(
            origins = ?config.cors_allowed_origins,
            "cors: allowing web client origins"
        );
        if let Some(layer) = cors_layer(&config.cors_allowed_origins) {
            app = app.layer(layer);
        }
    }

    let addr: SocketAddr = config.socket_addr()?;
    if let Some(ref url) = config.nebular_url {
        tracing::info!(
            nebular_url = %url,
            bucket = %config.nebular_media_bucket,
            "media: local volume + Nebular mirror"
        );
    } else {
        tracing::info!("media: local volume only (set NEBULAR_URL to mirror ciphertext blobs)");
    }
    tracing::info!(%addr, "shroud-server listening");
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .map_err(|err| AppError::Internal(format!("bind failed: {err}")))?;

    // Human: Drain in-flight HTTP after SIGTERM/Ctrl-C so orchestrators can stop cleanly.
    // Agent: CALLS axum::serve.with_graceful_shutdown; WS clients see close on process exit.
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .map_err(|err| AppError::Internal(format!("server error: {err}")))?;

    tracing::info!("shroud-server shut down");
    Ok(())
}

/// Browser web client talking to a split API host (web.shroud.app → api.shroud.app).
/// Same-origin deploys (nginx proxying `/api/v1`) leave `CORS_ALLOWED_ORIGINS` empty.
fn cors_layer(origins: &[String]) -> Option<CorsLayer> {
    let parsed: Vec<HeaderValue> = origins
        .iter()
        .filter_map(|origin| HeaderValue::from_str(origin).ok())
        .collect();
    if parsed.is_empty() {
        return None;
    }
    Some(
        CorsLayer::new()
            .allow_origin(AllowOrigin::list(parsed))
            .allow_methods([
                Method::GET,
                Method::POST,
                Method::PUT,
                Method::PATCH,
                Method::DELETE,
                Method::OPTIONS,
            ])
            .allow_headers([
                header::AUTHORIZATION,
                header::CONTENT_TYPE,
                header::ACCEPT,
                HeaderName::from_static("x-request-id"),
            ])
            .max_age(Duration::from_secs(3600)),
    )
}

/// Waits for Ctrl-C or SIGTERM before returning.
async fn shutdown_signal() {
    let ctrl_c = async {
        if let Err(err) = tokio::signal::ctrl_c().await {
            tracing::error!(error = %err, "failed to install Ctrl-C handler");
        }
    };

    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut stream) => {
                stream.recv().await;
            }
            Err(err) => {
                tracing::error!(error = %err, "failed to install SIGTERM handler");
            }
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        () = ctrl_c => {
            tracing::info!("shutdown signal: ctrl-c");
        }
        () = terminate => {
            tracing::info!("shutdown signal: sigterm");
        }
    }
}
