//! Shroud HTTP API server.
//!
//! Human: Boots Axum, runs Postgres migrations, and serves the `/api/v1` surface.
//! Agent: READS env config, DB migrate on startup, HTTP router; never handles message plaintext.

pub mod auth;
pub mod client_version;
pub mod config;
pub mod error;
pub mod ice_check;
pub mod keys;
pub mod link_relay;
pub mod logging;
pub mod media_store;
pub mod metrics;
pub mod push;
pub mod rate_limit;
pub mod rate_limit_check;
pub mod realtime;
pub mod request_tracking;
pub mod retention_check;
pub mod reserved_names;
pub mod routes;
pub mod state;
pub mod turn;
pub mod username_kdf;

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use axum::http::{HeaderName, HeaderValue, Method, header};
use axum::{
    Router,
    extract::{MatchedPath, Request},
    middleware,
};
use sqlx::postgres::PgPoolOptions;
use tower_http::LatencyUnit;
use tower_http::classify::ServerErrorsFailureClass;
use tower_http::cors::{AllowOrigin, CorsLayer};
use tower_http::trace::{DefaultOnResponse, TraceLayer};
use tracing::{Level, Span};

use crate::config::{Config, MediaConfig};
use crate::error::AppError;
use crate::media_store::MediaStore;
use crate::push::{ApnsClient, PushService, apns_config_from_env};
use crate::rate_limit::RateLimiter;
use crate::realtime::RealtimeHub;
use crate::state::AppState;

/// Counts HTTP requests / 5xx for Prometheus (`/operator/metrics`).
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
///
/// Human: Names the route as written (`/api/v1/users/{user_id}`), never the path the client
/// sent or its query: those hold user ids, share codes and media ids.
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
        route = %route_of(request),
        version = ?request.version(),
    )
}

/// The route template a request matched, or `(no route)`.
fn route_of(request: &Request) -> &str {
    request
        .extensions()
        .get::<MatchedPath>()
        .map_or("(no route)", MatchedPath::as_str)
}

/// Application entrypoint: configure tracing, connect to Postgres, serve HTTP.
pub async fn run() -> Result<(), AppError> {
    // Human: Local dev uses server/.env; production injects real env vars (dotenv is a no-op if missing).
    // Agent: CALLS dotenvy::dotenv before Config::from_env; never logs secret values.
    let _ = dotenvy::dotenv();

    logging::init_subscriber();

    let config = Config::from_env()?;
    let operator_port = config.operator.as_ref().map(|listener| listener.port);
    tracing::info!(
        host = %config.host,
        port = config.port,
        database_pool_max = config.database_pool_max,
        run_migrations = config.run_migrations,
        redis = config.redis_url.is_some(),
        trust_forwarded_headers = config.trust_forwarded_headers,
        media_store = if config.media.nebular.is_some() { "nebular" } else { "local" },
        media_bucket = %config.media.bucket,
        operator = config.operator.is_some(),
        ?operator_port,
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

    let media = Arc::new(build_media_store(&config.media)?);
    media.prepare().await;
    // Compose starts the API only once Nebular is healthy; elsewhere a store that is still
    // starting is only worth a warning — uploads and downloads answer 503 until it is up.
    match media.check().await {
        Ok(()) => tracing::info!(
            backend = media.backend_name(),
            location = %media.location(),
            "media store ready"
        ),
        Err(err) => tracing::warn!(
            backend = media.backend_name(),
            location = %media.location(),
            error = %err,
            "media store not reachable yet"
        ),
    }

    let realtime = Arc::new(RealtimeHub::new());
    let rate_limiter = RateLimiter::new();
    let redis_required = config.redis_url.is_some();
    if let Some(redis_url) = config.redis_url.clone() {
        match redis::Client::open(redis_url.as_str()) {
            Ok(client) => {
                realtime.set_redis_client(client.clone());
                match redis::aio::ConnectionManager::new_with_config(client, redis_manager_config())
                    .await
                {
                    Ok(manager) => {
                        realtime.set_redis(manager.clone()).await;
                        rate_limiter.set_redis(manager).await;
                        relay_redis_replacements(
                            realtime.redis_replacements(),
                            rate_limiter.clone(),
                        );
                        let hub = realtime.clone();
                        rate_limiter.on_silent_redis(move || hub.report_silent_redis());
                        crate::realtime::spawn_redis_subscriber(realtime.clone(), redis_url);
                        tracing::info!("realtime fan-out + rate limits: Redis enabled");
                    }
                    Err(err) => {
                        tracing::error!(
                            error = %err,
                            "REDIS_URL set but connection manager failed; readiness will report redis error"
                        );
                    }
                }
            }
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
    // Human: Browsers subscribe with this key; it is generated once and kept in the database
    // (or set with WEB_PUSH_VAPID_*), since a new key orphans every subscription.
    let web_push = match load_web_push(&pool, &config.unifiedpush).await {
        Ok(client) => Some(client),
        Err(err) => {
            // Before migrations ran (RUN_MIGRATIONS=false on a fresh database) there is no
            // table yet; the server serves everything else and keeps trying.
            tracing::error!(error = %err, "web push unavailable: VAPID key could not be loaded");
            None
        }
    };
    let web_push_pending = web_push.is_none();
    let push = PushService::new(pool.clone(), realtime.clone(), apns, web_push);
    if web_push_pending {
        let (pool, push) = (pool.clone(), push.clone());
        let unifiedpush = config.unifiedpush.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(60)).await;
                if let Ok(client) = load_web_push(&pool, &unifiedpush).await {
                    push.set_web(client);
                    tracing::info!("web push: VAPID key loaded");
                    break;
                }
            }
        });
    }

    tracing::info!(
        ice_server_count = config.ice_servers.len(),
        minted_turn = config.turn.is_some(),
        "webrtc ice servers loaded"
    );

    let metrics = Arc::new(crate::metrics::Metrics::new());
    // Human: Drop abandoned uploads that never linked to a message, and media deletes unlinked.
    crate::routes::media::spawn_orphan_gc(pool.clone(), media.clone(), metrics.clone());
    // Human: Blobs earlier releases kept on the local volume move into Nebular.
    media.spawn_legacy_migration(pool.clone(), metrics.clone());
    // Human: Drop revoked session rows after the 30-day retention window.
    crate::auth::session::spawn_revoked_session_purge(pool.clone());

    let state = AppState {
        pool,
        media,
        realtime,
        push,
        ice_servers: config.ice_servers.clone(),
        turn: config.turn.clone(),
        rate_limiter,
        redis_required,
        trust_forwarded_headers: config.trust_forwarded_headers,
        metrics,
        link_relay: Arc::new(crate::routes::link_relay::LinkRelay::new(
            crate::link_relay::RelayPolicy::production(),
        )),
        reactions_max_per_user: config.reactions_max_per_user,
        client_versions: Arc::new(config.client_versions.clone()),
        username_kdf: config.username_kdf.clone(),
    };

    // The reserved-name list is a few seconds of argon2id. Fill it off the runtime so the
    // first registration does not wait, and so a worker is never blocked on it.
    let warming = state.username_kdf.clone();
    tokio::task::spawn_blocking(move || warming.warm());

    // Human: End calls nobody answered, and calls whose devices went quiet, so a crashed app
    // never leaves anyone busy; both sides hear `call.ended`.
    crate::routes::calls::spawn_call_gc(state.clone());

    let public_app = app(state.clone(), &config.cors_allowed_origins);
    let (shutdown_tx, shutdown_rx) = listen_shutdown();

    let addr = config.socket_addr()?;
    tracing::info!(%addr, "shroud-server listening");
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .map_err(|err| AppError::Internal(format!("bind failed: {err}")))?;

    // Human: Drain in-flight HTTP on the public listener and, when it is on, the operator
    // listener after SIGTERM/Ctrl-C. Either one exiting stops the other.
    // Agent: one watch fed by shutdown_signal; both serves use with_graceful_shutdown.
    if let Some(operator) = config.operator.clone() {
        let operator_addr = SocketAddr::new(config.host, operator.port);
        let operator_listener = tokio::net::TcpListener::bind(operator_addr)
            .await
            .map_err(|err| AppError::Internal(format!("operator bind failed: {err}")))?;
        tracing::info!(%operator_addr, "operator listener");
        let operator_app = routes::operator::router(Arc::from(operator.token())).with_state(state);
        let public_done = shutdown_tx.clone();
        let operator_done = shutdown_tx;
        let public_stop = shutdown_rx.clone();
        let operator_stop = shutdown_rx;
        // Whichever listener returns tells the other to drain, then both are awaited.
        let public_task = tokio::spawn(async move {
            let result = axum::serve(listener, public_app)
                .with_graceful_shutdown(until_shutdown(public_stop))
                .await;
            let _ = public_done.send(true);
            result
        });
        let operator_task = tokio::spawn(async move {
            let result = axum::serve(operator_listener, operator_app)
                .with_graceful_shutdown(until_shutdown(operator_stop))
                .await;
            let _ = operator_done.send(true);
            result
        });
        let (public_result, operator_result) = tokio::join!(public_task, operator_task);
        public_result
            .map_err(|err| AppError::Internal(format!("server task failed: {err}")))?
            .map_err(serve_error)?;
        operator_result
            .map_err(|err| AppError::Internal(format!("operator task failed: {err}")))?
            .map_err(serve_error)?;
    } else {
        tracing::info!("operator listener off");
        axum::serve(listener, public_app)
            .with_graceful_shutdown(until_shutdown(shutdown_rx))
            .await
            .map_err(serve_error)?;
        drop(shutdown_tx);
    }

    tracing::info!("shroud-server shut down");
    Ok(())
}

/// How the shared Redis connection reconnects once Redis went away.
///
/// Human: The defaults retry 1 s and then 60 s apart, with no limit on one attempt: a Redis
/// back after ten seconds was used again only a minute later, and an attempt against a host
/// that accepts but never answers waited for good. The realtime hub and the rate limiter bound
/// each of their calls into Redis besides. A connection that goes silent without an error is
/// never reconnected by the manager; the hub opens a fresh one itself, also when the limiter
/// reports one (see [`relay_redis_replacements`]).
/// Agent: Attempts 1–2 s apart, 2 s each; after the 6 retries the next call starts again.
fn redis_manager_config() -> redis::aio::ConnectionManagerConfig {
    redis::aio::ConnectionManagerConfig::new()
        .set_connection_timeout(Duration::from_secs(2))
        .set_max_delay(1_000)
}

/// Hands each connection manager the realtime hub swaps in for a silent one to the rate
/// limiter, which was handed the same connection.
///
/// Human: Without it the limiter kept the silent connection after the hub had moved on, and
/// counted in-process until the OS gave up on the socket, hours later.
/// Agent: Runs until the hub's replacement channel closes (the hub is dropped).
fn relay_redis_replacements(
    mut replacements: tokio::sync::watch::Receiver<Option<redis::aio::ConnectionManager>>,
    rate_limiter: RateLimiter,
) {
    tokio::spawn(async move {
        while replacements.changed().await.is_ok() {
            let manager = replacements.borrow_and_update().clone();
            if let Some(manager) = manager {
                rate_limiter.set_redis(manager).await;
            }
        }
    });
}

fn serve_error(err: std::io::Error) -> AppError {
    AppError::Internal(format!("server error: {err}"))
}

/// A flag set on SIGTERM/Ctrl-C. `run` also sets it when either listener returns, so the
/// other drains.
fn listen_shutdown() -> (
    tokio::sync::watch::Sender<bool>,
    tokio::sync::watch::Receiver<bool>,
) {
    let (tx, rx) = tokio::sync::watch::channel(false);
    let signal = tx.clone();
    tokio::spawn(async move {
        shutdown_signal().await;
        let _ = signal.send(true);
    });
    (tx, rx)
}

async fn until_shutdown(mut rx: tokio::sync::watch::Receiver<bool>) {
    let _ = rx.changed().await;
}

/// The whole HTTP app: every route behind the middleware stack `run` serves.
///
/// Human: Last `.layer` is outermost — request-id runs first, then metrics, then TraceLayer.
/// Agent: OUTER CORS (if any) → request_id → metrics → TraceLayer → client gate → routes. Tests
/// build the app here too, so they cover the same stack.
pub fn app(state: AppState, cors_allowed_origins: &[String]) -> Router {
    let mut app = Router::new()
        .merge(routes::router())
        .layer(middleware::from_fn_with_state(
            state.clone(),
            client_version::require_supported_client,
        ))
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

    if !cors_allowed_origins.is_empty() {
        tracing::info!(
            origins = ?cors_allowed_origins,
            "cors: allowing web client origins"
        );
        if let Some(layer) = cors_layer(cors_allowed_origins) {
            app = app.layer(layer);
        }
    }
    app
}

/// Nebular when `NEBULAR_URL` is set, with the local volume kept readable while its blobs move
/// over; otherwise the local volume.
fn build_media_store(config: &MediaConfig) -> Result<MediaStore, AppError> {
    match &config.nebular {
        Some(nebular) => MediaStore::nebular(
            nebular.clone(),
            config.bucket.clone(),
            Some(config.data_dir.clone()),
        )
        .map_err(|err| AppError::Internal(format!("media store: {err}"))),
        None => Ok(MediaStore::local(
            config.data_dir.clone(),
            config.bucket.clone(),
        )),
    }
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
                client_version::CLIENT_HEADER.clone(),
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

/// The Web Push client, with the VAPID key from the environment or the database, for
/// browsers and (under `unifiedpush`) Android apps.
async fn load_web_push(
    pool: &sqlx::PgPool,
    unifiedpush: &crate::push::UnifiedPushPolicy,
) -> Result<crate::push::WebPushClient, String> {
    let key = crate::push::web_push::load_vapid_key(pool).await?;
    let subject = crate::push::web_push::subject_from_env();
    let hosts = crate::push::web_push::allowed_hosts_from_env();
    tracing::info!(public_key = %key.public_key_b64url(), %subject, "web push: VAPID key ready");
    crate::push::WebPushClient::new(key, subject, hosts, unifiedpush.clone())
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use axum::body::Body;
    use axum::routing::get;
    use tower::ServiceExt;

    use super::*;

    /// The request log names the route as written, never the ids or query the client sent.
    /// `/ws` is the WebSocket upgrade: a GET under the same nest, so it has a matched path too.
    #[tokio::test]
    async fn the_request_log_names_the_route_not_the_path() {
        let seen = Arc::new(Mutex::new(Vec::<String>::new()));
        let record = Arc::clone(&seen);
        let app = Router::new()
            .nest(
                "/api/v1",
                Router::new()
                    .route("/users/{user_id}", get(|| async { "ok" }))
                    .route("/ws", get(|| async { "ok" })),
            )
            .layer(middleware::from_fn(
                move |request: Request, next: middleware::Next| {
                    record.lock().unwrap().push(route_of(&request).to_owned());
                    next.run(request)
                },
            ));
        for uri in [
            "/api/v1/users/5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b?code=ABCD-EFGH",
            "/api/v1/ws",
            "/api/v1/nothing/here",
        ] {
            let request = Request::builder().uri(uri).body(Body::empty()).unwrap();
            app.clone().oneshot(request).await.unwrap();
        }
        assert_eq!(
            *seen.lock().unwrap(),
            vec![
                "/api/v1/users/{user_id}".to_owned(),
                "/api/v1/ws".to_owned(),
                "(no route)".to_owned()
            ]
        );
    }
}
