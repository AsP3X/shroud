//! Read-only pages (§3.4, §3.9 #4).
//!
//! `GET /push/check` (§3.9 #10) is the API's `GET /operator/push/check` passed through after a
//! shape check: the API asks Apple, the browser push services and the UnifiedPush distributors
//! whether this server's setup works, notifying no one, and names hosts, never endpoints.
//!
//! Rate limits and retention are the tables in [`crate::published`]. Storage counts and push
//! counts use the granted columns. `GET /calls` `created_total` is the process counter
//! `shroud_calls_created_total` (the Calls frame: since the last restart); the `calls` table
//! has no column grant. Client-version rows are what `GET /api/v1/client-version` answers
//! for each band. An empty version is rejected by that route, so the `unnamed` row follows
//! the same rule as a client that names no version: update required once either native
//! minimum is set.

use std::cmp::Ordering;

use axum::Json;
use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::response::{IntoResponse, Response};
use axum::routing::{Router, get};
use chrono::{DateTime, SecondsFormat, Utc};
use serde::Serialize;
use serde_json::Value;
use sqlx::PgPool;
use sqlx::Row;
use uuid::Uuid;

use crate::auth::{self, Admission};
use crate::error::ApiError;
use crate::operator_api;
use crate::overview;
use crate::probe;
use crate::published;
use crate::state::AppState;

const AUDIT_LIMIT: i64 = 100;
const TURN_TTL_DEFAULT: i64 = 43_200;
const WEB_PROBE: &str = "shroud-admin-probe";

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/storage", get(storage))
        .route("/client-versions", get(client_versions))
        .route("/rate-limits", get(rate_limits))
        .route("/retention", get(retention))
        .route("/push", get(push))
        .route("/push/check", get(push_check))
        .route("/calls", get(calls))
        .route("/privacy-checks", get(privacy_checks))
        .route("/configuration", get(configuration))
        .route("/audit-log", get(audit_log))
}

#[derive(Serialize)]
struct StorageBody {
    backend: &'static str,
    bucket: Option<String>,
    data_dir: Option<String>,
    objects: i64,
    bytes: i64,
    unlinked_objects: i64,
    legacy_reads_total: u64,
    migrated_total: u64,
    max_object_bytes: i64,
}

#[derive(Serialize)]
struct ClientVersionsBody {
    server_version: String,
    ios: ReleaseBody,
    android: ReleaseBody,
    web: WebBody,
    told: ToldBody,
}

#[derive(Serialize)]
struct ReleaseBody {
    latest: Option<String>,
    minimum: Option<String>,
    update_url: Option<String>,
}

#[derive(Serialize)]
struct WebBody {
    build_file: Option<String>,
    deployed_build: Option<String>,
    fixed_build: Option<String>,
}

#[derive(Serialize)]
struct ToldBody {
    ios: Vec<ToldRow>,
    android: Vec<ToldRow>,
    web: Vec<ToldRow>,
}

#[derive(Serialize)]
struct ToldRow {
    versions: String,
    status: &'static str,
}

#[derive(Serialize)]
struct PushBody {
    apns_tokens: i64,
    apns_voip_tokens: i64,
    web_push_subscriptions: i64,
    unifiedpush_subscriptions: i64,
    notifications_off: i64,
    channels: Vec<ChannelBody>,
}

#[derive(Serialize)]
struct ChannelBody {
    name: &'static str,
    registered: i64,
    sent_to: String,
    dropped_when: &'static str,
}

#[derive(Serialize)]
struct CallsBody {
    created_total: u64,
    ice_servers: Vec<IceBody>,
    turn: Option<TurnBody>,
    gc: GcBody,
}

#[derive(Serialize)]
struct IceBody {
    urls: String,
}

#[derive(Serialize)]
struct TurnBody {
    urls: Vec<String>,
    credential_ttl_secs: i64,
}

#[derive(Serialize)]
struct GcBody {
    ringing_timeout_secs: i64,
    participant_timeout_secs: i64,
    sweep_secs: i64,
}

#[derive(Serialize)]
struct CheckBody {
    item: &'static str,
    state: &'static str,
    detail: String,
}

#[derive(Serialize)]
struct GroupBody {
    group: &'static str,
    variables: Vec<VariableBody>,
}

#[derive(Serialize)]
struct VariableBody {
    name: &'static str,
    value: Option<String>,
    secret: bool,
    set: bool,
}

#[derive(Serialize)]
struct AuditBody {
    items: Vec<AuditRow>,
    next_cursor: Option<String>,
}

#[derive(Serialize)]
struct AuditRow {
    at: String,
    operator: String,
    action: String,
    target_kind: Option<String>,
    target_id: Option<String>,
    outcome: String,
    detail: Option<String>,
}

struct ListedAudit {
    at: DateTime<Utc>,
    id: Uuid,
    operator: String,
    action: String,
    target_kind: Option<String>,
    target_id: Option<Uuid>,
    outcome: String,
    detail: Option<String>,
}

#[derive(serde::Deserialize)]
struct AuditQuery {
    cursor: Option<String>,
    limit: Option<i64>,
    target: Option<String>,
}

struct VersionAnswer {
    status: &'static str,
    server_version: String,
    latest_version: Option<String>,
}

async fn storage(State(state): State<AppState>, headers: HeaderMap) -> Result<Response, ApiError> {
    if let Some(response) = require(&state, &headers).await? {
        return Ok(response);
    }
    let pool = pool(&state)?;
    let (counts, metrics) = tokio::join!(storage_counts(&pool), metrics_text());
    let (objects, bytes, unlinked) = counts?;
    let metrics = metrics?;
    Ok(Json(StorageBody {
        backend: if nonempty("NEBULAR_URL").is_some() {
            "nebular"
        } else {
            "local"
        },
        bucket: nonempty("NEBULAR_MEDIA_BUCKET"),
        data_dir: nonempty("MEDIA_DATA_DIR"),
        objects,
        bytes,
        unlinked_objects: unlinked,
        legacy_reads_total: sample(&metrics, "shroud_media_legacy_reads_total")?,
        migrated_total: sample(&metrics, "shroud_media_migrated_total")?,
        max_object_bytes: published::MAX_OBJECT_BYTES,
    })
    .into_response())
}

async fn client_versions(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    if let Some(response) = require(&state, &headers).await? {
        return Ok(response);
    }
    let base = api_base().ok_or_else(ApiError::upstream_api)?;
    let ios = release_from("IOS");
    let android = release_from("ANDROID");
    let build_file = nonempty("WEB_BUILD_FILE");
    let deployed = match &build_file {
        Some(path) => read_build(path).await,
        None => None,
    };
    let fixed = nonempty("WEB_BUILD").and_then(|raw| valid_build(&raw));
    let unnamed = if ios.minimum.is_some() || android.minimum.is_some() {
        "update_required"
    } else {
        "current"
    };
    let mut server_version = None;
    let ios_rows = tell_native(&base, "ios", &ios, unnamed, &mut server_version).await?;
    let android_rows =
        tell_native(&base, "android", &android, unnamed, &mut server_version).await?;
    let web_rows = tell_web(&base, unnamed, &mut server_version).await?;
    let server_version = server_version.ok_or_else(ApiError::upstream_api)?;
    Ok(Json(ClientVersionsBody {
        server_version,
        ios,
        android,
        web: WebBody {
            build_file,
            deployed_build: deployed,
            fixed_build: fixed,
        },
        told: ToldBody {
            ios: ios_rows,
            android: android_rows,
            web: web_rows,
        },
    })
    .into_response())
}

async fn rate_limits(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    if let Some(response) = require(&state, &headers).await? {
        return Ok(response);
    }
    Ok(Json(published::rate_limits()).into_response())
}

async fn retention(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    if let Some(response) = require(&state, &headers).await? {
        return Ok(response);
    }
    Ok(Json(published::retention()).into_response())
}

async fn push(State(state): State<AppState>, headers: HeaderMap) -> Result<Response, ApiError> {
    if let Some(response) = require(&state, &headers).await? {
        return Ok(response);
    }
    let pool = pool(&state)?;
    let row = sqlx::query(
        "SELECT
            (SELECT count(device_id) FROM push_tokens WHERE kind = 'alert') AS apns,
            (SELECT count(device_id) FROM push_tokens WHERE kind = 'voip') AS voip,
            (SELECT count(device_id) FROM web_push_subscriptions WHERE client = 'browser') AS web,
            (SELECT count(device_id) FROM web_push_subscriptions WHERE client = 'android') AS unified,
            (SELECT count(device_id) FROM device_notification_settings WHERE enabled = false) AS off",
    )
    .fetch_one(&pool)
    .await
    .map_err(db_err)?;
    let apns: i64 = row.try_get("apns").map_err(db_err)?;
    let voip: i64 = row.try_get("voip").map_err(db_err)?;
    let web: i64 = row.try_get("web").map_err(db_err)?;
    let unified: i64 = row.try_get("unified").map_err(db_err)?;
    let off: i64 = row.try_get("off").map_err(db_err)?;
    let topic = nonempty("APNS_TOPIC");
    let hosts = split_list("UNIFIEDPUSH_ALLOWED_HOSTS");
    Ok(Json(PushBody {
        apns_tokens: apns,
        apns_voip_tokens: voip,
        web_push_subscriptions: web,
        unifiedpush_subscriptions: unified,
        notifications_off: off,
        channels: vec![
            ChannelBody {
                name: "APNs alerts",
                registered: apns,
                sent_to: match &topic {
                    Some(topic) => format!("api.push.apple.com · topic {topic}"),
                    None => "api.push.apple.com".to_owned(),
                },
                dropped_when: "Apple answers BadDeviceToken, Unregistered, DeviceTokenNotForTopic or ExpiredToken",
            },
            ChannelBody {
                name: "APNs calls (VoIP)",
                registered: voip,
                sent_to: match &topic {
                    Some(topic) => {
                        format!("api.push.apple.com · topic {topic}.voip · a second token")
                    }
                    None => "api.push.apple.com · a second token".to_owned(),
                },
                dropped_when: "Same answers from Apple",
            },
            ChannelBody {
                name: "Web Push",
                registered: web,
                sent_to: "The subscription's endpoint at the browser's push service".to_owned(),
                dropped_when: "The service answers 404 or 410 Gone",
            },
            ChannelBody {
                name: "UnifiedPush",
                registered: unified,
                sent_to: if hosts.is_empty() {
                    "The device's distributor".to_owned()
                } else {
                    format!(
                        "The device's distributor · hosts limited to {}",
                        hosts.join(", ")
                    )
                },
                dropped_when: "The distributor answers 404 or 410 Gone",
            },
        ],
    })
    .into_response())
}

#[derive(Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct PushCheckLine {
    item: String,
    state: String,
    detail: String,
}

async fn push_check(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    if let Some(response) = require(&state, &headers).await? {
        return Ok(response);
    }
    let fetched = operator_api::get("/operator/push/check")
        .await
        .map_err(|_| ApiError::upstream_api())?;
    if fetched.status != 200 {
        return Err(ApiError::upstream_api());
    }
    let lines = push_check_lines(&fetched.body).ok_or_else(ApiError::upstream_api)?;
    Ok(Json(lines).into_response())
}

/// The API's push check, when it has the contract's shape: at least one line, each with a
/// non-empty item and detail and a known state.
fn push_check_lines(body: &str) -> Option<Vec<PushCheckLine>> {
    let lines: Vec<PushCheckLine> = serde_json::from_str(body).ok()?;
    let valid = !lines.is_empty()
        && lines.iter().all(|line| {
            !line.item.is_empty()
                && !line.detail.is_empty()
                && matches!(line.state.as_str(), "ok" | "failed" | "off")
        });
    valid.then_some(lines)
}

async fn calls(State(state): State<AppState>, headers: HeaderMap) -> Result<Response, ApiError> {
    if let Some(response) = require(&state, &headers).await? {
        return Ok(response);
    }
    let metrics = metrics_text().await?;
    let (urls, turn) = ice_from_env();
    Ok(Json(CallsBody {
        created_total: sample(&metrics, "shroud_calls_created_total")?,
        ice_servers: urls.into_iter().map(|urls| IceBody { urls }).collect(),
        turn,
        gc: GcBody {
            ringing_timeout_secs: published::RINGING_TIMEOUT_SECS,
            participant_timeout_secs: published::PARTICIPANT_TIMEOUT_SECS,
            sweep_secs: published::CALL_GC_INTERVAL_SECS,
        },
    })
    .into_response())
}

async fn privacy_checks(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    if let Some(response) = require(&state, &headers).await? {
        return Ok(response);
    }
    let pool = pool(&state)?;
    let counts = overview::load_counts(&state).await?;
    let username_column = username_column(&pool).await?;
    let messages_sealed =
        column_unreadable(&pool, "SELECT ciphertext FROM messages LIMIT 1").await?;
    let names_sealed = column_unreadable(&pool, "SELECT sealed_name FROM devices LIMIT 1").await?;
    let media_sealed =
        column_unreadable(&pool, "SELECT object_key FROM media_objects LIMIT 1").await?;
    let unsealed_pushes: i64 =
        sqlx::query_scalar("SELECT count(device_id) FROM admin_unsealed_push_devices")
            .fetch_one(&pool)
            .await
            .map_err(db_err)?;
    let base = api_base().ok_or_else(ApiError::upstream_api)?;
    let metrics_public = probe::get(&format!("{base}/api/v1/metrics"))
        .await
        .map_err(|_| ApiError::upstream_api())?
        .status
        == 200;
    // A failed or old answer is the fast hash. The salt endpoint is public and does no guessing.
    let username_kdf = probe::get(&format!("{base}/api/v1/auth/username-kdf"))
        .await
        .ok()
        .filter(|fetched| fetched.status == 200)
        .map(|fetched| fetched.body);
    let rust_log = std::env::var("RUST_LOG").unwrap_or_default();
    let (urls, _) = ice_from_env();
    let google = urls
        .iter()
        .any(|url| url.to_ascii_lowercase().contains("google"));
    let redis_password = flag("REDIS_PASSWORD_SET");
    let checks = vec![
        if username_column {
            check(
                "Usernames are not stored",
                "stored",
                "A username column is still on users.",
            )
        } else {
            check(
                "Usernames are not stored",
                "hashed",
                "Accounts are kept by a hash of the name only.",
            )
        },
        username_hash_check(username_kdf.as_deref(), counts.accounts),
        if logs_name_people(&rust_log) {
            check(
                "The API log doesn't name who messages whom",
                "stored",
                format!(
                    "RUST_LOG={} writes user, device and chat ids. Set it back to info.",
                    rust_log.trim()
                ),
            )
        } else {
            check(
                "The API log doesn't name who messages whom",
                "not_stored",
                "At info it writes no user, device, chat or call ids, and routes without their values.",
            )
        },
        check(
            "The web proxy keeps no access log",
            "not_stored",
            "No client addresses or user agents are written.",
        ),
        check(
            "Nginx Proxy Manager access log",
            "not_stored",
            "Can't be checked from here. Turn it off in each proxy host.",
        ),
        check(
            "TURN logins don't name accounts",
            "not_stored",
            "Random logins, and coturn writes no log.",
        ),
        if unsealed_pushes == 0 {
            check(
                "Apple doesn't see chat IDs in pushes",
                "not_stored",
                "IDs are sealed for each iPhone, and threads differ from phone to phone.",
            )
        } else {
            check(
                "Apple doesn't see chat IDs in pushes",
                "stored",
                format!(
                    "{} iPhones on older builds still get chat, sender and message IDs in plain text.",
                    grouped(unsealed_pushes)
                ),
            )
        },
        if redis_password {
            check(
                "Redis needs a password, keeps nothing on disk",
                "not_stored",
                "Password set, and nothing is kept on disk.",
            )
        } else {
            check(
                "Redis needs a password, keeps nothing on disk",
                "stored",
                "No password is set. Nothing is kept on disk.",
            )
        },
        check(
            "Message sizes aren't padded",
            "stored",
            "A message's size shows roughly how long it is.",
        ),
        if metrics_public {
            check(
                "Activity counters aren't public",
                "stored",
                "The API serves /metrics on its public port: live counters show when people are active.",
            )
        } else {
            check(
                "Activity counters aren't public",
                "not_stored",
                "Only this console reads them, on the API's internal operator port.",
            )
        },
        if google {
            check(
                "No Google STUN fallback",
                "stored",
                "A configured ICE server is at Google.",
            )
        } else {
            check(
                "No Google STUN fallback",
                "not_stored",
                "Calls only use servers configured here.",
            )
        },
        sealed_or_open("Message text", messages_sealed, "messages.ciphertext"),
        sealed_or_open(
            "Photos, video, voice",
            media_sealed,
            "media_objects.object_key",
        ),
        if username_column {
            check(
                "Usernames",
                "stored",
                "A username column is still on users.",
            )
        } else {
            check("Usernames", "sealed", "Never reaches this server.")
        },
        sealed_or_open("Device names", names_sealed, "devices.sealed_name"),
        check(
            "Call audio and video",
            "sealed",
            "Never reaches this server.",
        ),
        check("Who talks to whom", "stored", "Known to this server."),
        check("When messages are sent", "stored", "Known to this server."),
        check(
            "Addresses while connected",
            "stored",
            "Known to this server.",
        ),
        check(
            "Push tokens (Apple, Google)",
            "stored",
            "Known to this server.",
        ),
    ];
    Ok(Json(checks).into_response())
}

async fn configuration(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    if let Some(response) = require(&state, &headers).await? {
        return Ok(response);
    }
    Ok(Json(configuration_groups()).into_response())
}

async fn audit_log(
    State(state): State<AppState>,
    headers: HeaderMap,
    query: Result<Query<AuditQuery>, axum::extract::rejection::QueryRejection>,
) -> Result<Response, ApiError> {
    if let Some(response) = require(&state, &headers).await? {
        return Ok(response);
    }
    let Query(query) = query.map_err(|_| ApiError::validation("The request query isn't valid."))?;
    let target = target_of(query.target)?;
    let limit = limit_of(query.limit)?;
    let cursor = cursor_of(query.cursor)?;
    let pool = pool(&state)?;
    let rows = sqlx::query(
        "SELECT a.at, a.id, o.name AS operator, a.action, a.target_kind, a.target_id,
                a.outcome, a.detail
         FROM admin.audit_log a
         JOIN admin.operators o ON o.id = a.operator_id
         WHERE (
             $1::uuid IS NULL
             OR a.target_id = $1
             OR a.target_id IN (SELECT id FROM devices WHERE user_id = $1)
         )
         AND ($2::timestamptz IS NULL OR (a.at, a.id) < ($2, $3))
         ORDER BY a.at DESC, a.id DESC
         LIMIT $4",
    )
    .bind(target)
    .bind(cursor.map(|item| item.0))
    .bind(cursor.map(|item| item.1))
    .bind(limit + 1)
    .fetch_all(&pool)
    .await
    .map_err(db_err)?;
    let mut listed = Vec::with_capacity(rows.len());
    for row in rows {
        listed.push(ListedAudit {
            at: row.try_get("at").map_err(db_err)?,
            id: row.try_get("id").map_err(db_err)?,
            operator: row.try_get("operator").map_err(db_err)?,
            action: row.try_get("action").map_err(db_err)?,
            target_kind: row.try_get("target_kind").map_err(db_err)?,
            target_id: row.try_get("target_id").map_err(db_err)?,
            outcome: row.try_get("outcome").map_err(db_err)?,
            detail: row.try_get("detail").map_err(db_err)?,
        });
    }
    let next_cursor = if listed.len() > limit as usize {
        listed.pop();
        listed.last().map(cursor_stamp)
    } else {
        None
    };
    let items = listed
        .into_iter()
        .map(|row| AuditRow {
            at: row.at.to_rfc3339_opts(SecondsFormat::Secs, true),
            operator: row.operator,
            action: row.action,
            target_kind: row.target_kind,
            target_id: row.target_id.map(|id| id.to_string()),
            outcome: row.outcome,
            detail: row.detail,
        })
        .collect();
    Ok(Json(AuditBody { items, next_cursor }).into_response())
}

async fn require(state: &AppState, headers: &HeaderMap) -> Result<Option<Response>, ApiError> {
    match auth::admit(state, headers).await? {
        Admission::Out(response) => Ok(Some(response)),
        Admission::In => Ok(None),
    }
}

fn pool(state: &AppState) -> Result<PgPool, ApiError> {
    state
        .pool()
        .cloned()
        .ok_or_else(ApiError::upstream_postgres)
}

async fn storage_counts(pool: &PgPool) -> Result<(i64, i64, i64), ApiError> {
    let row = sqlx::query(
        "SELECT count(id) AS objects,
                coalesce(sum(size_bytes), 0)::bigint AS bytes,
                count(id) FILTER (WHERE message_id IS NULL) AS unlinked
         FROM media_objects",
    )
    .fetch_one(pool)
    .await
    .map_err(db_err)?;
    Ok((
        row.try_get("objects").map_err(db_err)?,
        row.try_get("bytes").map_err(db_err)?,
        row.try_get("unlinked").map_err(db_err)?,
    ))
}

/// The counters, from the operator listener. No `OPERATOR_TOKEN` fails the page
/// (`upstream_api`) instead of showing zeros: zeros would look like a quiet server, and the
/// listener is not bound without the token. The console's writes already need the same token.
async fn metrics_text() -> Result<String, ApiError> {
    let fetched = operator_api::get("/operator/metrics")
        .await
        .map_err(|_| ApiError::upstream_api())?;
    if fetched.status != 200 {
        return Err(ApiError::upstream_api());
    }
    Ok(fetched.body)
}

fn sample(text: &str, name: &str) -> Result<u64, ApiError> {
    probe::metric(text, name).ok_or_else(ApiError::upstream_api)
}

fn release_from(platform: &str) -> ReleaseBody {
    ReleaseBody {
        latest: nonempty(&format!("{platform}_LATEST_VERSION")),
        minimum: nonempty(&format!("{platform}_MIN_VERSION")),
        update_url: nonempty(&format!("{platform}_UPDATE_URL")),
    }
}

async fn tell_native(
    base: &str,
    platform: &str,
    release: &ReleaseBody,
    unnamed: &'static str,
    server_version: &mut Option<String>,
) -> Result<Vec<ToldRow>, ApiError> {
    let mut rows = Vec::new();
    for (label, version) in native_probes(release.minimum.as_deref(), release.latest.as_deref()) {
        let answer = ask(base, platform, &version).await?;
        note_version(server_version, &answer.server_version)?;
        rows.push(ToldRow {
            versions: label,
            status: answer.status,
        });
    }
    rows.push(ToldRow {
        versions: "unnamed".to_owned(),
        status: unnamed,
    });
    Ok(rows)
}

async fn tell_web(
    base: &str,
    unnamed: &'static str,
    server_version: &mut Option<String>,
) -> Result<Vec<ToldRow>, ApiError> {
    let probe = ask(base, "web", WEB_PROBE).await?;
    note_version(server_version, &probe.server_version)?;
    let mut rows = Vec::new();
    match probe.latest_version {
        None => rows.push(ToldRow {
            versions: "any".to_owned(),
            status: probe.status,
        }),
        Some(id) => {
            let current = ask(base, "web", &id).await?;
            note_version(server_version, &current.server_version)?;
            rows.push(ToldRow {
                versions: "other".to_owned(),
                status: probe.status,
            });
            rows.push(ToldRow {
                versions: id,
                status: current.status,
            });
        }
    }
    rows.push(ToldRow {
        versions: "unnamed".to_owned(),
        status: unnamed,
    });
    Ok(rows)
}

async fn ask(base: &str, platform: &str, version: &str) -> Result<VersionAnswer, ApiError> {
    if !version
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
    {
        return Err(ApiError::upstream_api());
    }
    let url = format!("{base}/api/v1/client-version?platform={platform}&version={version}");
    let client = format!("{platform}/{version}");
    let fetched = probe::get_with(&url, &[("X-Shroud-Client", client.as_str())])
        .await
        .map_err(|_| ApiError::upstream_api())?;
    if fetched.status != 200 {
        return Err(ApiError::upstream_api());
    }
    let value: Value = serde_json::from_str(&fetched.body).map_err(|_| ApiError::upstream_api())?;
    let status = status_name(
        value
            .get("status")
            .and_then(Value::as_str)
            .ok_or_else(ApiError::upstream_api)?,
    )?;
    let server_version = value
        .get("server_version")
        .and_then(Value::as_str)
        .filter(|version| !version.is_empty())
        .ok_or_else(ApiError::upstream_api)?
        .to_owned();
    let latest_version = match value.get("latest_version") {
        None | Some(Value::Null) => None,
        Some(Value::String(raw)) => Some(valid_build(raw).ok_or_else(ApiError::upstream_api)?),
        Some(_) => return Err(ApiError::upstream_api()),
    };
    Ok(VersionAnswer {
        status,
        server_version,
        latest_version,
    })
}

fn note_version(current: &mut Option<String>, next: &str) -> Result<(), ApiError> {
    match current {
        Some(have) if have == next => Ok(()),
        Some(_) => Err(ApiError::upstream_api()),
        None => {
            *current = Some(next.to_owned());
            Ok(())
        }
    }
}

fn status_name(status: &str) -> Result<&'static str, ApiError> {
    match status {
        "current" => Ok("current"),
        "update_available" => Ok("update_available"),
        "update_required" => Ok("update_required"),
        _ => Err(ApiError::upstream_api()),
    }
}

/// Bands the client-version route is asked about. The status comes back from the API.
fn native_probes(minimum: Option<&str>, latest: Option<&str>) -> Vec<(String, String)> {
    match (minimum, latest) {
        (None, None) => vec![("any".to_owned(), "0.0.0".to_owned())],
        (Some(minimum), latest) => {
            let mut rows = Vec::new();
            if let Some(below) = version_below(minimum) {
                rows.push((format!("<{minimum}"), below));
            }
            let above = format!(">={minimum}");
            if let Some(latest) =
                latest.filter(|latest| version_ord(minimum, latest) == Some(Ordering::Less))
            {
                rows.push((above, minimum.to_owned()));
                rows.push((format!(">={latest}"), latest.to_owned()));
            } else {
                rows.push((above, minimum.to_owned()));
            }
            rows
        }
        (None, Some(latest)) => {
            let mut rows = Vec::new();
            if let Some(below) = version_below(latest) {
                rows.push((format!("<{latest}"), below));
            }
            rows.push((format!(">={latest}"), latest.to_owned()));
            rows
        }
    }
}

fn version_parts(raw: &str) -> Option<Vec<u32>> {
    if raw.is_empty() {
        return None;
    }
    raw.split('.')
        .map(|part| {
            if part.is_empty() || !part.bytes().all(|byte| byte.is_ascii_digit()) {
                None
            } else {
                part.parse().ok()
            }
        })
        .collect()
}

fn version_ord(left: &str, right: &str) -> Option<Ordering> {
    let left = version_parts(left)?;
    let right = version_parts(right)?;
    let len = left.len().max(right.len());
    Some(
        (0..len)
            .map(|index| {
                let a = left.get(index).copied().unwrap_or(0);
                let b = right.get(index).copied().unwrap_or(0);
                a.cmp(&b)
            })
            .find(|ordering| ordering.is_ne())
            .unwrap_or(Ordering::Equal),
    )
}

fn version_below(raw: &str) -> Option<String> {
    let mut parts = version_parts(raw)?;
    for part in parts.iter_mut().rev() {
        if *part > 0 {
            *part -= 1;
            return Some(
                parts
                    .iter()
                    .map(ToString::to_string)
                    .collect::<Vec<_>>()
                    .join("."),
            );
        }
    }
    None
}

async fn read_build(path: &str) -> Option<String> {
    let raw = tokio::fs::read_to_string(path).await.ok()?;
    valid_build(&raw)
}

fn valid_build(raw: &str) -> Option<String> {
    let build = raw.trim();
    let valid = !build.is_empty()
        && build.len() <= 64
        && build
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'));
    valid.then(|| build.to_owned())
}

fn ice_from_env() -> (Vec<String>, Option<TurnBody>) {
    ice_from(&|name| nonempty(name))
}

fn ice_from(lookup: &dyn Fn(&str) -> Option<String>) -> (Vec<String>, Option<TurnBody>) {
    let turn_urls = lookup("TURN_URLS")
        .map(|raw| split_csv(&raw))
        .unwrap_or_default();
    let turn = if turn_urls.is_empty() {
        None
    } else {
        let credential_ttl_secs = lookup("TURN_CREDENTIAL_TTL_SECS")
            .and_then(|raw| raw.parse::<i64>().ok())
            .filter(|secs| *secs >= 0)
            .unwrap_or(TURN_TTL_DEFAULT);
        Some(TurnBody {
            urls: turn_urls.clone(),
            credential_ttl_secs,
        })
    };
    let custom = lookup("ICE_SERVERS_JSON").and_then(|raw| parse_ice_urls(&raw));
    let secret = lookup("TURN_SECRET_SET").as_deref() == Some("true");
    let mut urls = Vec::new();
    if let Some(custom) = custom {
        urls.extend(custom);
        if secret {
            urls.extend(turn_urls);
        }
    } else if secret {
        urls.extend(stun_of(&turn_urls));
        urls.extend(turn_urls);
    } else {
        urls.extend(turn_urls);
    }
    (urls, turn)
}

fn parse_ice_urls(raw: &str) -> Option<Vec<String>> {
    let servers: Vec<IceJson> = serde_json::from_str(raw).ok()?;
    if servers.is_empty() {
        return None;
    }
    let urls = servers
        .into_iter()
        .flat_map(|server| server.urls)
        .filter(|url| !url.is_empty())
        .collect::<Vec<_>>();
    (!urls.is_empty()).then_some(urls)
}

#[derive(serde::Deserialize)]
struct IceJson {
    urls: Vec<String>,
}

fn stun_of(turn_urls: &[String]) -> Vec<String> {
    let mut urls = Vec::new();
    for url in turn_urls {
        let Some(rest) = url.strip_prefix("turn:") else {
            continue;
        };
        let host_port = rest.split('?').next().unwrap_or_default();
        if host_port.is_empty() {
            continue;
        }
        let stun = format!("stun:{host_port}");
        if !urls.contains(&stun) {
            urls.push(stun);
        }
    }
    urls
}

fn configuration_groups() -> Vec<GroupBody> {
    vec![
        GroupBody {
            group: "Network",
            variables: vec![
                listen_variable(),
                plain("WEB_PUBLIC_URL"),
                plain("CORS_ALLOWED_ORIGINS"),
                plain("TRUST_FORWARDED_HEADERS"),
            ],
        },
        GroupBody {
            group: "Database",
            variables: vec![
                plain("DATABASE_URL"),
                plain("DATABASE_POOL_MAX"),
                plain("RUN_MIGRATIONS"),
                plain("REDIS_URL"),
                secret("REDIS_PASSWORD", "REDIS_PASSWORD_SET"),
            ],
        },
        GroupBody {
            group: "Media",
            variables: vec![
                plain("NEBULAR_URL"),
                plain("NEBULAR_MEDIA_BUCKET"),
                secret("NEBULAR_SECRET_ACCESS_KEY", "NEBULAR_SECRET_ACCESS_KEY_SET"),
                plain("MEDIA_DATA_DIR"),
            ],
        },
        GroupBody {
            group: "Calls",
            variables: vec![
                plain("TURN_URLS"),
                secret("TURN_SECRET", "TURN_SECRET_SET"),
                plain("TURN_CREDENTIAL_TTL_SECS"),
                plain("ICE_SERVERS_JSON"),
            ],
        },
        GroupBody {
            group: "Push",
            variables: vec![
                plain("APNS_TOPIC"),
                secret("APNS_KEY_PEM", "APNS_KEY_PEM_SET"),
                plain("WEB_PUSH_SUBJECT"),
                secret(
                    "WEB_PUSH_VAPID_PRIVATE_KEY",
                    "WEB_PUSH_VAPID_PRIVATE_KEY_SET",
                ),
                plain("UNIFIEDPUSH_ALLOWED_HOSTS"),
            ],
        },
        GroupBody {
            group: "Limits and logging",
            variables: vec![plain("REACTIONS_MAX_PER_USER"), plain("RUST_LOG")],
        },
    ]
}

fn listen_variable() -> VariableBody {
    let host = nonempty("HOST").unwrap_or_else(|| "0.0.0.0".to_owned());
    let port = nonempty("PORT").unwrap_or_else(|| "8082".to_owned());
    VariableBody {
        name: "HOST / PORT",
        value: Some(format!("{host}:{port}")),
        secret: false,
        set: true,
    }
}

fn plain(name: &'static str) -> VariableBody {
    match nonempty(name) {
        Some(value) => VariableBody {
            name,
            value: Some(value),
            secret: false,
            set: true,
        },
        None => VariableBody {
            name,
            value: None,
            secret: false,
            set: false,
        },
    }
}

fn secret(name: &'static str, flag_name: &'static str) -> VariableBody {
    VariableBody {
        name,
        value: None,
        secret: true,
        set: flag(flag_name),
    }
}

/// True when `RUST_LOG` lets the API's per-event lines through: at debug and trace they name
/// users, devices, chats and calls. Other crates at debug (`tower_http`, `sqlx`) don't.
///
/// Read the way the API's `EnvFilter` reads it. A bare target (`shroud_server`) is trace. A
/// later directive for the same target replaces an earlier one, and `shroud_server=info` covers
/// the whole crate, so it quiets a global `debug`. A span or field filter
/// (`shroud_server[span]=debug`) can still let a line through. A directive the filter cannot
/// parse makes the server fall back to its info default, so that string does not name people.
/// Spaces around a directive and around `=` are ignored.
fn logs_name_people(rust_log: &str) -> bool {
    let Some(directives) = parse_rust_log(rust_log) else {
        return false;
    };
    // The same target (and, for a span filter, the same span) keeps the later level.
    let mut kept: Vec<LogDirective> = Vec::new();
    for directive in directives {
        if let Some(existing) = kept
            .iter_mut()
            .find(|item| item.dynamic == directive.dynamic && item.key == directive.key)
        {
            *existing = directive;
        } else {
            kept.push(directive);
        }
    }
    if kept
        .iter()
        .any(|directive| directive.dynamic && directive.level.verbose() && directive.applies())
    {
        return true;
    }
    // The longest target that is a prefix of the crate names every module in it, unless a
    // longer child says otherwise.
    let base_verbose = kept
        .iter()
        .filter(|directive| !directive.dynamic && "shroud_server".starts_with(&directive.target))
        .max_by_key(|directive| directive.target.len())
        .is_some_and(|directive| directive.level.verbose());
    let child_verbose = kept.iter().any(|directive| {
        !directive.dynamic
            && directive.level.verbose()
            && directive.target.starts_with("shroud_server::")
    });
    base_verbose || child_verbose
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum LogLevel {
    Off,
    Error,
    Warn,
    Info,
    Debug,
    Trace,
}

impl LogLevel {
    fn verbose(self) -> bool {
        matches!(self, Self::Debug | Self::Trace)
    }
}

struct LogDirective {
    /// Empty when the directive names every target.
    target: String,
    /// What a later copy replaces: the target, or the whole `target[span]` for a span filter.
    key: String,
    level: LogLevel,
    /// A span or field filter. It can enable a line while the target's own level stays quiet.
    dynamic: bool,
}

impl LogDirective {
    /// Whether this directive can apply to a `shroud_server` event. Matching is a prefix, as
    /// in the filter: `shroud` covers the crate, `shroud_server_extra` does not.
    fn applies(&self) -> bool {
        self.target.is_empty()
            || self.target == "shroud_server"
            || self.target.starts_with("shroud_server::")
            || "shroud_server".starts_with(&self.target)
    }
}

/// `None` when the string is not a filter the server would install.
fn parse_rust_log(rust_log: &str) -> Option<Vec<LogDirective>> {
    let mut directives = Vec::new();
    for piece in rust_log.split(',') {
        let piece = piece.trim();
        if piece.is_empty() {
            continue;
        }
        directives.push(parse_log_directive(piece)?);
    }
    Some(directives)
}

fn parse_log_directive(raw: &str) -> Option<LogDirective> {
    let (head, level) = match split_log_level(raw) {
        LogSplit::Invalid => return None,
        LogSplit::Bare(text) => {
            let text = text.trim();
            if !text.contains('[')
                && let Some(level) = parse_log_level_token(text)
            {
                return Some(LogDirective {
                    target: String::new(),
                    key: String::new(),
                    level,
                    dynamic: false,
                });
            }
            (text, LogLevel::Trace)
        }
        LogSplit::Level(head, level) => (head.trim(), parse_log_level(level)?),
    };
    if head.is_empty() {
        return None;
    }
    let (target, dynamic) = match head.find('[') {
        None => {
            if !is_log_target(head) {
                return None;
            }
            (head, false)
        }
        Some(at) => {
            let target = head[..at].trim();
            if !target.is_empty() && !is_log_target(target) {
                return None;
            }
            if !log_brackets_balance(&head[at..]) {
                return None;
            }
            (target, true)
        }
    };
    Some(LogDirective {
        target: target.to_owned(),
        key: if dynamic {
            head.to_owned()
        } else {
            target.to_owned()
        },
        level,
        dynamic,
    })
}

enum LogSplit<'a> {
    Bare(&'a str),
    Level(&'a str, &'a str),
    Invalid,
}

/// The `=` that sets the level. One inside `{field=value}` is not it. Two of them is a
/// directive the server rejects.
fn split_log_level(raw: &str) -> LogSplit<'_> {
    let mut depth = 0i32;
    let mut eq_at = None;
    for (index, ch) in raw.char_indices() {
        match ch {
            '[' | '{' => depth += 1,
            ']' | '}' => {
                depth -= 1;
                if depth < 0 {
                    return LogSplit::Invalid;
                }
            }
            '=' if depth == 0 => {
                if eq_at.is_some() {
                    return LogSplit::Invalid;
                }
                eq_at = Some(index);
            }
            _ => {}
        }
    }
    if depth != 0 {
        return LogSplit::Invalid;
    }
    match eq_at {
        Some(index) => LogSplit::Level(&raw[..index], &raw[index + 1..]),
        None => LogSplit::Bare(raw),
    }
}

/// An empty level is trace (`shroud_server=`). Anything else the filter does not know is rejected.
fn parse_log_level(raw: &str) -> Option<LogLevel> {
    let raw = raw.trim();
    if raw.is_empty() {
        return Some(LogLevel::Trace);
    }
    parse_log_level_token(raw)
}

fn parse_log_level_token(raw: &str) -> Option<LogLevel> {
    if let Ok(number) = raw.parse::<u8>() {
        return match number {
            0 => Some(LogLevel::Off),
            1 => Some(LogLevel::Error),
            2 => Some(LogLevel::Warn),
            3 => Some(LogLevel::Info),
            4 => Some(LogLevel::Debug),
            5 => Some(LogLevel::Trace),
            _ => None,
        };
    }
    if raw.eq_ignore_ascii_case("off") {
        Some(LogLevel::Off)
    } else if raw.eq_ignore_ascii_case("error") {
        Some(LogLevel::Error)
    } else if raw.eq_ignore_ascii_case("warn") {
        Some(LogLevel::Warn)
    } else if raw.eq_ignore_ascii_case("info") {
        Some(LogLevel::Info)
    } else if raw.eq_ignore_ascii_case("debug") {
        Some(LogLevel::Debug)
    } else if raw.eq_ignore_ascii_case("trace") {
        Some(LogLevel::Trace)
    } else {
        None
    }
}

fn is_log_target(target: &str) -> bool {
    !target.is_empty()
        && target
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || ch == '_' || ch == ':' || ch == '-')
}

fn log_brackets_balance(spec: &str) -> bool {
    if !spec.starts_with('[') || !spec.ends_with(']') {
        return false;
    }
    let mut square = 0i32;
    let mut brace = 0i32;
    for ch in spec.chars() {
        match ch {
            '[' => square += 1,
            ']' => {
                square -= 1;
                if square < 0 {
                    return false;
                }
            }
            '{' => brace += 1,
            '}' => {
                brace -= 1;
                if brace < 0 {
                    return false;
                }
            }
            _ => {}
        }
    }
    square == 0 && brace == 0
}

fn check(item: &'static str, state: &'static str, detail: impl Into<String>) -> CheckBody {
    CheckBody {
        item,
        state,
        detail: detail.into(),
    }
}

/// What `GET /api/v1/auth/username-kdf` said. The same floor the clients enforce: a cheaper
/// answer is still the fast hash, and a missing answer is an old server.
enum UsernameHashes {
    Slow { memory_kib: u64, iterations: u64 },
    Quick,
}

fn classify_username_kdf(body: &str) -> UsernameHashes {
    let Ok(value) = serde_json::from_str::<Value>(body) else {
        return UsernameHashes::Quick;
    };
    let memory = value["memory_kib"].as_u64();
    let iterations = value["iterations"].as_u64();
    let salt_ok = value["salt"].as_str().is_some_and(|salt| {
        data_encoding::BASE64
            .decode(salt.as_bytes())
            .is_ok_and(|bytes| (16..=64).contains(&bytes.len()))
    });
    match (memory, iterations) {
        (Some(memory_kib), Some(iterations))
            if value["algorithm"].as_str() == Some("argon2id")
                && value["version"].as_u64() == Some(19)
                && value["parallelism"].as_u64() == Some(1)
                && value["output_bytes"].as_u64() == Some(32)
                && memory_kib >= 65_536
                && iterations >= 8
                && salt_ok =>
        {
            UsernameHashes::Slow {
                memory_kib,
                iterations,
            }
        }
        _ => UsernameHashes::Quick,
    }
}

fn username_hash_check(body: Option<&str>, accounts: i64) -> CheckBody {
    match body.map(classify_username_kdf) {
        Some(UsernameHashes::Slow {
            memory_kib,
            iterations,
        }) => check(
            "Username hashes are slow to guess",
            "hashed",
            format!(
                "Argon2id, {memory_kib} KiB and {iterations} iterations. A guess takes at least 100 ms."
            ),
        ),
        _ => check(
            "Username hashes are quick to guess",
            "stored",
            format!(
                "Plain SHA-256. {} accounts still need the slow hash.",
                grouped(accounts)
            ),
        ),
    }
}

fn sealed_or_open(item: &'static str, sealed: bool, column: &str) -> CheckBody {
    if sealed {
        check(item, "sealed", "Never reaches this server.")
    } else {
        check(item, "stored", format!("The console can read {column}."))
    }
}

async fn username_column(pool: &PgPool) -> Result<bool, ApiError> {
    let found: Option<i32> = sqlx::query_scalar(
        "SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'username'",
    )
    .fetch_optional(pool)
    .await
    .map_err(db_err)?;
    Ok(found.is_some())
}

/// `true` when the column cannot be read (the grant holds, or the column is gone).
async fn column_unreadable(pool: &PgPool, sql: &str) -> Result<bool, ApiError> {
    match sqlx::query(sql).fetch_optional(pool).await {
        Ok(_) => Ok(false),
        Err(err) if is_code(&err, "42501") || is_code(&err, "42703") => Ok(true),
        Err(err) => Err(db_err(err)),
    }
}

fn is_code(err: &sqlx::Error, code: &str) -> bool {
    err.as_database_error()
        .and_then(|db| db.code())
        .is_some_and(|got| got == code)
}

fn grouped(n: i64) -> String {
    let raw = n.max(0).to_string();
    let mut out = String::new();
    for (index, ch) in raw.chars().rev().enumerate() {
        if index > 0 && index.is_multiple_of(3) {
            out.push(',');
        }
        out.push(ch);
    }
    out.chars().rev().collect()
}

fn target_of(target: Option<String>) -> Result<Option<Uuid>, ApiError> {
    let Some(target) = target.filter(|value| !value.is_empty()) else {
        return Ok(None);
    };
    Uuid::parse_str(target.trim())
        .map(Some)
        .map_err(|_| ApiError::validation("Target must be an account id."))
}

fn limit_of(limit: Option<i64>) -> Result<i64, ApiError> {
    match limit {
        None => Ok(AUDIT_LIMIT),
        Some(limit) if (1..=AUDIT_LIMIT).contains(&limit) => Ok(limit),
        Some(_) => Err(ApiError::validation(
            "Limit must be a number from 1 to 100.",
        )),
    }
}

fn cursor_of(cursor: Option<String>) -> Result<Option<(DateTime<Utc>, Uuid)>, ApiError> {
    let Some(cursor) = cursor.filter(|value| !value.is_empty()) else {
        return Ok(None);
    };
    let Some((stamp, id)) = cursor.split_once(',') else {
        return Err(ApiError::validation("That page token isn't valid."));
    };
    let stamp = DateTime::parse_from_rfc3339(stamp)
        .map_err(|_| ApiError::validation("That page token isn't valid."))?
        .with_timezone(&Utc);
    let id =
        Uuid::parse_str(id).map_err(|_| ApiError::validation("That page token isn't valid."))?;
    Ok(Some((stamp, id)))
}

fn cursor_stamp(row: &ListedAudit) -> String {
    format!(
        "{},{}",
        row.at.to_rfc3339_opts(SecondsFormat::Micros, true),
        row.id
    )
}

fn split_list(name: &str) -> Vec<String> {
    nonempty(name)
        .map(|raw| split_csv(&raw))
        .unwrap_or_default()
}

fn split_csv(raw: &str) -> Vec<String> {
    raw.split(',')
        .map(str::trim)
        .filter(|item| !item.is_empty())
        .map(str::to_owned)
        .collect()
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

fn db_err(err: sqlx::Error) -> ApiError {
    crate::db::log_db("pages", &err);
    ApiError::upstream_postgres()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_check_lines_pass_only_the_contract_shape() {
        let fine = r#"[{"item":"APNs key","state":"ok","detail":"Key K signs."},{"item":"Web Push key","state":"off","detail":"Not set."}]"#;
        assert_eq!(push_check_lines(fine).map(|lines| lines.len()), Some(2));
        for refused in [
            "[]",
            "{}",
            r#"[{"item":"APNs key","state":"maybe","detail":"x"}]"#,
            r#"[{"item":"","state":"ok","detail":"x"}]"#,
            r#"[{"item":"APNs key","state":"ok","detail":""}]"#,
            r#"[{"item":"APNs key","state":"ok","detail":"x","endpoint":"https://fcm.googleapis.com/fcm/send/t"}]"#,
        ] {
            assert!(push_check_lines(refused).is_none(), "{refused}");
        }
    }

    #[test]
    fn only_a_verbose_api_log_names_people() {
        for quiet in [
            "",
            "info",
            "warn",
            "info,sqlx=warn",
            "shroud_server=info,tower_http=debug,sqlx=debug",
            "shroud_server_extra=debug",
            "SHROUD_SERVER=debug",
            "debug,shroud_server=info",
            "shroud_server=debug,shroud_server=info",
            "shroud=info,debug",
            "shroud_server=3",
            "shroud_server=nope",
            "debug,shroud_server=nope",
            // A comma inside the field filter splits the directive, so the server rejects the
            // whole string and falls back to info.
            "[{a=1,b=2}]=debug",
            "shroud_server[span]=debug,shroud_server[span]=info",
        ] {
            assert!(!logs_name_people(quiet), "{quiet}");
        }
        for verbose in [
            "debug",
            "TRACE",
            "info,shroud_server=debug",
            "shroud_server::routes=trace",
            " shroud_server = debug ",
            "shroud_server",
            "shroud_server=",
            "shroud_server=4",
            "shroud=debug",
            "info,debug",
            "shroud_server[span]=debug",
            "shroud_server::routes[http.request]=trace",
            "shroud_server[{field=value}]=debug",
            "[{field=value}]=debug",
            "debug,shroud_server=info,shroud_server::push=debug",
            "debug,shroud_server::routes=info",
        ] {
            assert!(logs_name_people(verbose), "{verbose}");
        }
    }

    #[test]
    fn username_hash_check_follows_the_published_kdf() {
        let strong = r#"{"algorithm":"argon2id","version":19,"salt":"ABEiM0RVZneImaq7zN3u/w==","memory_kib":65536,"iterations":8,"parallelism":1,"output_bytes":32}"#;
        let slow = username_hash_check(Some(strong), 1284);
        assert_eq!(slow.item, "Username hashes are slow to guess");
        assert_eq!(slow.state, "hashed");
        assert_eq!(
            slow.detail,
            "Argon2id, 65536 KiB and 8 iterations. A guess takes at least 100 ms."
        );
        let raised = strong
            .replace("65536", "131072")
            .replace("\"iterations\":8", "\"iterations\":12");
        let slower = username_hash_check(Some(&raised), 1);
        assert_eq!(slower.state, "hashed");
        assert_eq!(
            slower.detail,
            "Argon2id, 131072 KiB and 12 iterations. A guess takes at least 100 ms."
        );

        let quick = |body: Option<&str>| username_hash_check(body, 1284);
        for body in [
            None,
            Some("not json"),
            Some(
                r#"{"algorithm":"argon2id","version":19,"memory_kib":65536,"iterations":8,"parallelism":1,"output_bytes":32}"#,
            ),
            Some(
                r#"{"algorithm":"argon2id","version":19,"salt":"AAAAAAAAAAA=","memory_kib":65536,"iterations":8,"parallelism":1,"output_bytes":32}"#,
            ),
            Some(
                r#"{"algorithm":"argon2id","version":19,"salt":"ABEiM0RVZneImaq7zN3u/w==","memory_kib":19456,"iterations":2,"parallelism":1,"output_bytes":32}"#,
            ),
            Some(
                r#"{"algorithm":"argon2id","version":19,"salt":"ABEiM0RVZneImaq7zN3u/w==","memory_kib":65536,"iterations":2,"parallelism":1,"output_bytes":32}"#,
            ),
            Some(
                r#"{"algorithm":"argon2i","version":19,"salt":"ABEiM0RVZneImaq7zN3u/w==","memory_kib":65536,"iterations":8,"parallelism":1,"output_bytes":32}"#,
            ),
            Some(
                r#"{"algorithm":"argon2id","version":16,"salt":"ABEiM0RVZneImaq7zN3u/w==","memory_kib":65536,"iterations":8,"parallelism":1,"output_bytes":32}"#,
            ),
            Some(
                r#"{"algorithm":"argon2id","version":19,"salt":"ABEiM0RVZneImaq7zN3u/w==","memory_kib":65536,"iterations":8,"parallelism":4,"output_bytes":32}"#,
            ),
            Some(
                r#"{"algorithm":"argon2id","version":19,"salt":"ABEiM0RVZneImaq7zN3u_w==","memory_kib":65536,"iterations":8,"parallelism":1,"output_bytes":32}"#,
            ),
        ] {
            let row = quick(body);
            assert_eq!(row.item, "Username hashes are quick to guess", "{body:?}");
            assert_eq!(row.state, "stored", "{body:?}");
            assert_eq!(
                row.detail, "Plain SHA-256. 1,284 accounts still need the slow hash.",
                "{body:?}"
            );
        }
    }

    #[test]
    fn bands_match_the_version_fixtures() {
        let ios = native_probes(Some("1.1"), Some("1.1"));
        assert_eq!(
            ios,
            vec![
                ("<1.1".to_owned(), "1.0".to_owned()),
                (">=1.1".to_owned(), "1.1".to_owned()),
            ]
        );
        let android = native_probes(None, Some("0.2.0"));
        assert_eq!(
            android,
            vec![
                ("<0.2.0".to_owned(), "0.1.0".to_owned()),
                (">=0.2.0".to_owned(), "0.2.0".to_owned()),
            ]
        );
        assert_eq!(
            native_probes(None, None),
            vec![("any".to_owned(), "0.0.0".to_owned())]
        );
        assert_eq!(grouped(1284), "1,284");
        assert_eq!(grouped(1_000_000), "1,000,000");
    }

    #[test]
    fn ice_urls_drop_credentials_and_add_stun_only_with_a_secret() {
        let lookup = |name: &str| {
            match name {
            "TURN_URLS" => Some(
                "turn:turn.example.org:3478?transport=udp, turn:turn.example.org:3478?transport=tcp"
                    .to_owned(),
            ),
            "TURN_SECRET_SET" => Some("true".to_owned()),
            "ICE_SERVERS_JSON" => Some(
                r#"[{"urls":["stun:stun.l.google.com:19302"],"username":"leak","credential":"leak"}]"#
                    .to_owned(),
            ),
            _ => None,
        }
        };
        let (urls, turn) = ice_from(&lookup);
        assert_eq!(
            urls,
            vec![
                "stun:stun.l.google.com:19302".to_owned(),
                "turn:turn.example.org:3478?transport=udp".to_owned(),
                "turn:turn.example.org:3478?transport=tcp".to_owned(),
            ]
        );
        assert!(urls.iter().all(|url| !url.contains("leak")));
        assert_eq!(turn.unwrap().credential_ttl_secs, TURN_TTL_DEFAULT);

        let minted = |name: &str| match name {
            "TURN_URLS" => Some("turn:turn.example.org:3478?transport=udp".to_owned()),
            "TURN_SECRET_SET" => Some("true".to_owned()),
            _ => None,
        };
        let (urls, _) = ice_from(&minted);
        assert_eq!(
            urls,
            vec![
                "stun:turn.example.org:3478".to_owned(),
                "turn:turn.example.org:3478?transport=udp".to_owned(),
            ]
        );
    }
}
