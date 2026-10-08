//! `GET /overview` (§3.2). Counts are cached for a minute (R8). A `SELECT 1` still runs on
//! every view, so a database that has stopped answers `502 UPSTREAM upstream=postgres`
//! instead of a stale page. The readiness probe and `/metrics` come from the API.

use axum::Json;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::response::{IntoResponse, Response};
use axum::routing::{Router, get};
use chrono::{DateTime, SecondsFormat, TimeDelta, Utc};
use serde::Serialize;
use serde_json::Value;
use sqlx::Row;

use crate::auth::{self, Admission};
use crate::error::ApiError;
use crate::probe;
use crate::state::{AppState, Counts};

/// Same cap as `shroud-server` `routes/link_relay.rs` `MAX_CONCURRENT_PER_USER`.
const LINK_RELAY_MAX_PER_ACCOUNT: i64 = 6;
const TURN_TTL_DEFAULT: i64 = 43_200;

pub fn routes() -> Router<AppState> {
    Router::new().route("/overview", get(overview))
}

#[derive(Serialize)]
struct OverviewBody {
    server: ServerBody,
    stats: StatsBody,
    ready: ReadyBody,
    configured: ConfiguredBody,
    metrics: MetricsBody,
    attention: Vec<AttentionBody>,
}

#[derive(Serialize)]
struct ServerBody {
    version: String,
    started_at: String,
    checked_at: String,
}

#[derive(Serialize)]
struct StatsBody {
    accounts: i64,
    accounts_7d: i64,
    accounts_deleted: i64,
    devices_active_30d: i64,
    ws_connections: u64,
    messages_sent_total: u64,
}

#[derive(Serialize)]
struct ReadyBody {
    status: String,
    database: String,
    redis: String,
    media: String,
}

#[derive(Serialize)]
struct ConfiguredBody {
    apns: Option<ApnsBody>,
    web_push: Option<WebPushBody>,
    unifiedpush: Option<UnifiedPushBody>,
    turn: Option<TurnBody>,
    link_relay: LinkRelayBody,
}

#[derive(Serialize)]
struct ApnsBody {
    environment: &'static str,
    topic: String,
}

#[derive(Serialize)]
struct WebPushBody {
    subscriptions: i64,
}

#[derive(Serialize)]
struct UnifiedPushBody {
    allowed_hosts: Vec<String>,
    public_hosts: bool,
}

#[derive(Serialize)]
struct TurnBody {
    urls: Vec<String>,
    credential_ttl_secs: i64,
}

#[derive(Serialize)]
struct LinkRelayBody {
    max_per_account: i64,
}

#[derive(Serialize)]
struct MetricsBody {
    http_requests_total: u64,
    http_errors_total: u64,
    media_puts_total: u64,
    media_gets_total: u64,
    media_store_errors_total: u64,
    media_legacy_reads_total: u64,
    media_migrated_total: u64,
    calls_created_total: u64,
}

#[derive(Serialize)]
struct AttentionBody {
    kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    count: Option<u64>,
}

async fn overview(State(state): State<AppState>, headers: HeaderMap) -> Result<Response, ApiError> {
    if let Admission::Out(response) = auth::admit(&state, &headers).await? {
        return Ok(response);
    }
    let base = api_base().ok_or_else(ApiError::upstream_api)?;
    let counts = load_counts(&state).await?;
    let ready_url = format!("{base}/api/v1/health/ready");
    let metrics_url = format!("{base}/api/v1/metrics");
    let version_url = format!("{base}/api/v1/client-version?platform=ios&version=0.0.0");
    let (ready_res, metrics_res, version_res) = tokio::join!(
        probe::get(&ready_url),
        probe::get(&metrics_url),
        probe::get(&version_url),
    );
    let ready_fetched = ready_res.map_err(|_| ApiError::upstream_api())?;
    let metrics_fetched = metrics_res.map_err(|_| ApiError::upstream_api())?;
    let version_fetched = version_res.map_err(|_| ApiError::upstream_api())?;
    if metrics_fetched.status != 200 || version_fetched.status != 200 {
        return Err(ApiError::upstream_api());
    }
    let ready = parse_ready(&ready_fetched.body, ready_fetched.status)?;
    let samples = parse_metrics(&metrics_fetched.body)?;
    let version = parse_version(&version_fetched.body)?;
    let checked_at = Utc::now();
    let started_at = checked_at - TimeDelta::seconds(i64::try_from(samples.uptime).unwrap_or(0));
    let configured = configured_from(&counts);
    let attention = attention_for(&ready, configured_env_has_min_version(), samples.legacy);
    Ok(Json(OverviewBody {
        server: ServerBody {
            version,
            started_at: stamp(started_at),
            checked_at: stamp(checked_at),
        },
        stats: StatsBody {
            accounts: counts.accounts,
            accounts_7d: counts.accounts_7d,
            accounts_deleted: counts.accounts_deleted,
            devices_active_30d: counts.devices_active_30d,
            ws_connections: samples.ws,
            messages_sent_total: samples.messages,
        },
        ready,
        configured,
        metrics: MetricsBody {
            http_requests_total: samples.http_requests,
            http_errors_total: samples.http_errors,
            media_puts_total: samples.media_puts,
            media_gets_total: samples.media_gets,
            media_store_errors_total: samples.media_store_errors,
            media_legacy_reads_total: samples.legacy,
            media_migrated_total: samples.migrated,
            calls_created_total: samples.calls,
        },
        attention,
    })
    .into_response())
}

async fn load_counts(state: &AppState) -> Result<Counts, ApiError> {
    let pool = state
        .pool()
        .cloned()
        .ok_or_else(ApiError::upstream_postgres)?;
    sqlx::query_scalar::<_, i32>("SELECT 1")
        .fetch_one(&pool)
        .await
        .map_err(db_err)?;
    if let Some(cached) = state.cached_counts() {
        return Ok(cached);
    }
    let row = sqlx::query(
        "SELECT
            (SELECT count(id) FROM users) AS accounts,
            (SELECT count(id) FROM users WHERE deleted_at IS NOT NULL) AS accounts_deleted,
            (SELECT count(id) FROM users WHERE created_at >= now() - interval '7 days') AS accounts_7d,
            (SELECT count(id) FROM devices
                WHERE revoked_at IS NULL AND last_seen_at >= now() - interval '30 days')
                AS devices_active_30d,
            (SELECT count(device_id) FROM web_push_subscriptions) AS web_push_subscriptions",
    )
    .fetch_one(&pool)
    .await
    .map_err(db_err)?;
    let counts = Counts {
        accounts: row.try_get("accounts").map_err(db_err)?,
        accounts_7d: row.try_get("accounts_7d").map_err(db_err)?,
        accounts_deleted: row.try_get("accounts_deleted").map_err(db_err)?,
        devices_active_30d: row.try_get("devices_active_30d").map_err(db_err)?,
        web_push_subscriptions: row.try_get("web_push_subscriptions").map_err(db_err)?,
    };
    state.store_counts(counts);
    Ok(counts)
}

fn db_err(err: sqlx::Error) -> ApiError {
    crate::db::log_db("overview", &err);
    ApiError::upstream_postgres()
}

struct Samples {
    uptime: u64,
    ws: u64,
    messages: u64,
    http_requests: u64,
    http_errors: u64,
    media_puts: u64,
    media_gets: u64,
    media_store_errors: u64,
    legacy: u64,
    migrated: u64,
    calls: u64,
}

fn parse_metrics(text: &str) -> Result<Samples, ApiError> {
    let sample = |name: &str| probe::metric(text, name).ok_or_else(ApiError::upstream_api);
    Ok(Samples {
        uptime: sample("shroud_uptime_seconds")?,
        ws: sample("shroud_ws_connections")?,
        messages: sample("shroud_messages_sent_total")?,
        http_requests: sample("shroud_http_requests_total")?,
        http_errors: sample("shroud_http_errors_total")?,
        media_puts: sample("shroud_media_puts_total")?,
        media_gets: sample("shroud_media_gets_total")?,
        media_store_errors: sample("shroud_media_store_errors_total")?,
        legacy: sample("shroud_media_legacy_reads_total")?,
        migrated: sample("shroud_media_migrated_total")?,
        calls: sample("shroud_calls_created_total")?,
    })
}

fn parse_ready(body: &str, http_status: u16) -> Result<ReadyBody, ApiError> {
    if http_status != 200 && http_status != 503 {
        return Err(ApiError::upstream_api());
    }
    let value: Value = serde_json::from_str(body).map_err(|_| ApiError::upstream_api())?;
    let database = enum_field(&value, "database", &["ok", "error"])?;
    let redis = enum_field(&value, "redis", &["ok", "error", "skipped"])?;
    let media = enum_field(&value, "media", &["ok", "error"])?;
    let mut status = enum_field(&value, "status", &["ok", "not_ready"])?;
    if http_status != 200 {
        status = "not_ready".to_owned();
    }
    Ok(ReadyBody {
        status,
        database,
        redis,
        media,
    })
}

fn parse_version(body: &str) -> Result<String, ApiError> {
    let value: Value = serde_json::from_str(body).map_err(|_| ApiError::upstream_api())?;
    value
        .get("server_version")
        .and_then(Value::as_str)
        .filter(|version| !version.is_empty())
        .map(str::to_owned)
        .ok_or_else(ApiError::upstream_api)
}

fn enum_field(value: &Value, name: &str, allowed: &[&str]) -> Result<String, ApiError> {
    let text = value
        .get(name)
        .and_then(Value::as_str)
        .ok_or_else(ApiError::upstream_api)?;
    allowed
        .iter()
        .find(|item| **item == text)
        .map(|item| (*item).to_owned())
        .ok_or_else(ApiError::upstream_api)
}

fn configured_from(counts: &Counts) -> ConfiguredBody {
    ConfiguredBody {
        apns: apns_from_env(),
        web_push: web_push_from_env(counts.web_push_subscriptions),
        unifiedpush: unifiedpush_from_env(),
        turn: turn_from_env(),
        link_relay: LinkRelayBody {
            max_per_account: LINK_RELAY_MAX_PER_ACCOUNT,
        },
    }
}

fn apns_from_env() -> Option<ApnsBody> {
    let topic = nonempty("APNS_TOPIC")?;
    if nonempty("APNS_KEY_ID").is_none() || nonempty("APNS_TEAM_ID").is_none() {
        return None;
    }
    if !flag("APNS_KEY_PATH_SET") && !flag("APNS_KEY_PEM_SET") {
        return None;
    }
    let environment = match nonempty("APNS_ENVIRONMENT")
        .unwrap_or_default()
        .to_ascii_lowercase()
        .as_str()
    {
        "production" | "prod" => "production",
        _ => "sandbox",
    };
    Some(ApnsBody { environment, topic })
}

fn web_push_from_env(subscriptions: i64) -> Option<WebPushBody> {
    if flag("WEB_PUSH_VAPID_PRIVATE_KEY_SET") || nonempty("WEB_PUSH_VAPID_PUBLIC_KEY").is_some() {
        Some(WebPushBody {
            subscriptions: subscriptions.max(0),
        })
    } else {
        None
    }
}

fn unifiedpush_from_env() -> Option<UnifiedPushBody> {
    let allowed_hosts = nonempty("UNIFIEDPUSH_ALLOWED_HOSTS")
        .map(|raw| {
            raw.split(',')
                .map(str::trim)
                .filter(|host| !host.is_empty())
                .map(str::to_owned)
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let public_hosts = matches!(
        nonempty("UNIFIEDPUSH_PUBLIC_HOSTS")
            .unwrap_or_default()
            .to_ascii_lowercase()
            .as_str(),
        "true" | "1" | "yes"
    );
    if allowed_hosts.is_empty() && !public_hosts {
        None
    } else {
        Some(UnifiedPushBody {
            allowed_hosts,
            public_hosts,
        })
    }
}

fn turn_from_env() -> Option<TurnBody> {
    let urls = nonempty("TURN_URLS")?
        .split(',')
        .map(str::trim)
        .filter(|url| !url.is_empty())
        .map(str::to_owned)
        .collect::<Vec<_>>();
    if urls.is_empty() {
        return None;
    }
    let credential_ttl_secs = nonempty("TURN_CREDENTIAL_TTL_SECS")
        .and_then(|raw| raw.parse::<i64>().ok())
        .filter(|secs| *secs >= 0)
        .unwrap_or(TURN_TTL_DEFAULT);
    Some(TurnBody {
        urls,
        credential_ttl_secs,
    })
}

fn configured_env_has_min_version() -> bool {
    nonempty("IOS_MIN_VERSION").is_some() || nonempty("ANDROID_MIN_VERSION").is_some()
}

fn attention_for(ready: &ReadyBody, min_version_set: bool, legacy: u64) -> Vec<AttentionBody> {
    let mut items = Vec::new();
    if ready.status != "ok" {
        items.push(AttentionBody {
            kind: "not_ready",
            count: None,
        });
    }
    if !min_version_set {
        items.push(AttentionBody {
            kind: "no_min_version",
            count: None,
        });
    }
    if legacy > 0 {
        items.push(AttentionBody {
            kind: "legacy_media",
            count: Some(legacy),
        });
    }
    items
}

fn api_base() -> Option<String> {
    nonempty("API_INTERNAL_URL").map(|url| url.trim_end_matches('/').to_owned())
}

fn nonempty(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
}

fn flag(name: &str) -> bool {
    nonempty(name).is_some_and(|value| value == "true")
}

fn stamp(time: DateTime<Utc>) -> String {
    time.to_rfc3339_opts(SecondsFormat::Secs, true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn attention_lists_every_reason() {
        let ready = ReadyBody {
            status: "not_ready".to_owned(),
            database: "error".to_owned(),
            redis: "ok".to_owned(),
            media: "ok".to_owned(),
        };
        let items = attention_for(&ready, false, 3);
        assert_eq!(items.len(), 3);
        assert_eq!(items[0].kind, "not_ready");
        assert!(items[0].count.is_none());
        assert_eq!(items[2].kind, "legacy_media");
        assert_eq!(items[2].count, Some(3));
    }

    #[test]
    fn a_non_ok_probe_forces_not_ready() {
        let body = r#"{"status":"ok","database":"error","redis":"ok","media":"ok"}"#;
        let ready = parse_ready(body, 503).unwrap();
        assert_eq!(ready.status, "not_ready");
        assert_eq!(ready.database, "error");
    }
}
