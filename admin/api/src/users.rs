//! `GET /users` and `GET /users/{id}` (§3.3, §3.9 #3).
//!
//! The list pages on `(created_at, id)`. Totals are the whole table, cached with the overview
//! counts, and do not follow the status filter. Contacts, blocks and conversations are always
//! null: the console does not read the social graph.

use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::HeaderMap;
use axum::response::{IntoResponse, Response};
use axum::routing::{Router, get};
use chrono::{DateTime, SecondsFormat, Utc};
use serde::Serialize;
use sqlx::PgPool;
use sqlx::Row;
use uuid::Uuid;

use crate::auth::{self, Admission};
use crate::error::ApiError;
use crate::overview;
use crate::state::AppState;

const DEFAULT_LIMIT: i64 = 50;
const MAX_LIMIT: i64 = 100;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/users", get(list_users))
        .route("/users/{id}", get(user_detail))
}

#[derive(serde::Deserialize)]
struct UsersQuery {
    q: Option<String>,
    cursor: Option<String>,
    limit: Option<i64>,
    status: Option<String>,
}

#[derive(Serialize)]
struct UsersBody {
    items: Vec<UserRow>,
    next_cursor: Option<String>,
    totals: TotalsBody,
}

#[derive(Serialize)]
struct TotalsBody {
    accounts: i64,
    active: i64,
    deleted: i64,
}

#[derive(Serialize)]
struct UserRow {
    id: String,
    created_on: String,
    devices: i64,
    last_active_on: Option<String>,
    push: &'static str,
    status: &'static str,
}

#[derive(Serialize)]
struct UserBody {
    id: String,
    created_on: String,
    status: &'static str,
    last_active_on: Option<String>,
    counts: CountsBody,
    devices: Vec<DeviceBody>,
    pin_guard: bool,
}

#[derive(Serialize)]
struct CountsBody {
    contacts: Option<i64>,
    blocks: Option<i64>,
    conversations: Option<i64>,
    media_objects: i64,
    media_bytes: i64,
}

#[derive(Serialize)]
struct DeviceBody {
    id: String,
    platform: &'static str,
    added_on: String,
    last_seen_on: Option<String>,
    revoked: bool,
    push: &'static str,
    session: &'static str,
}

struct Listed {
    id: Uuid,
    created_at: DateTime<Utc>,
    deleted: bool,
    devices: i64,
    last_seen: Option<DateTime<Utc>>,
}

async fn list_users(
    State(state): State<AppState>,
    headers: HeaderMap,
    query: Result<Query<UsersQuery>, axum::extract::rejection::QueryRejection>,
) -> Result<Response, ApiError> {
    if let Admission::Out(response) = auth::admit(&state, &headers).await? {
        return Ok(response);
    }
    let Query(query) = query.map_err(|_| ApiError::validation("The request query isn't valid."))?;
    let prefix = search_prefix(query.q)?;
    let status = status_filter(query.status)?;
    let limit = limit_of(query.limit)?;
    let cursor = cursor_of(query.cursor)?;
    let pool = state
        .pool()
        .cloned()
        .ok_or_else(ApiError::upstream_postgres)?;
    let counts = overview::load_counts(&state).await?;
    let mut rows = fetch_page(&pool, prefix.as_deref(), status, cursor, limit + 1).await?;
    let next_cursor = if rows.len() > limit as usize {
        rows.pop();
        rows.last().map(cursor_stamp)
    } else {
        None
    };
    let pushes = push_for_users(&pool, &rows).await?;
    let items = rows
        .iter()
        .map(|row| UserRow {
            id: row.id.to_string(),
            created_on: day(row.created_at),
            devices: row.devices,
            last_active_on: row.last_seen.map(day),
            push: pushes.get(&row.id).copied().unwrap_or("none"),
            status: if row.deleted { "deleted" } else { "active" },
        })
        .collect();
    Ok(Json(UsersBody {
        items,
        next_cursor,
        totals: TotalsBody {
            accounts: counts.accounts.max(0),
            active: (counts.accounts - counts.accounts_deleted).max(0),
            deleted: counts.accounts_deleted.max(0),
        },
    })
    .into_response())
}

async fn user_detail(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<Response, ApiError> {
    if let Admission::Out(response) = auth::admit(&state, &headers).await? {
        return Ok(response);
    }
    let Some(id) = Uuid::parse_str(&id).ok() else {
        return Err(missing_account());
    };
    let pool = state
        .pool()
        .cloned()
        .ok_or_else(ApiError::upstream_postgres)?;
    let row = sqlx::query("SELECT id, created_at, deleted_at FROM users WHERE id = $1")
        .bind(id)
        .fetch_optional(&pool)
        .await
        .map_err(db_err)?;
    let Some(row) = row else {
        return Err(missing_account());
    };
    let created_at: DateTime<Utc> = row.try_get("created_at").map_err(db_err)?;
    let deleted_at: Option<DateTime<Utc>> = row.try_get("deleted_at").map_err(db_err)?;
    let devices = fetch_devices(&pool, id).await?;
    let last_active = devices.iter().filter_map(|device| device.last_seen).max();
    let (media_objects, media_bytes) = media_for(&pool, id).await?;
    let pin_guard = pin_guard_for(&pool, id).await?;
    Ok(Json(UserBody {
        id: id.to_string(),
        created_on: day(created_at),
        status: if deleted_at.is_some() {
            "deleted"
        } else {
            "active"
        },
        last_active_on: last_active.map(day),
        counts: CountsBody {
            contacts: None,
            blocks: None,
            conversations: None,
            media_objects,
            media_bytes,
        },
        devices: devices
            .into_iter()
            .map(|device| DeviceBody {
                id: device.id.to_string(),
                platform: device.platform,
                added_on: day(device.added_at),
                last_seen_on: device.last_seen.map(day),
                revoked: device.revoked,
                push: device.push,
                session: if device.live { "live" } else { "none" },
            })
            .collect(),
        pin_guard,
    })
    .into_response())
}

async fn fetch_page(
    pool: &PgPool,
    prefix: Option<&str>,
    status: Option<&'static str>,
    cursor: Option<(DateTime<Utc>, Uuid)>,
    limit: i64,
) -> Result<Vec<Listed>, ApiError> {
    let (cursor_at, cursor_id) = cursor.unzip();
    let rows = sqlx::query(
        "SELECT u.id, u.created_at, u.deleted_at IS NOT NULL AS deleted,
                count(d.id) FILTER (WHERE d.revoked_at IS NULL) AS devices,
                max(d.last_seen_at) AS last_seen
         FROM users u
         LEFT JOIN devices d ON d.user_id = u.id
         WHERE ($1::text IS NULL OR lower(u.id::text) LIKE $1 || '%')
           AND ($2::text IS NULL
                OR ($2 = 'active' AND u.deleted_at IS NULL)
                OR ($2 = 'deleted' AND u.deleted_at IS NOT NULL))
           AND ($3::timestamptz IS NULL OR (u.created_at, u.id) < ($3, $4))
         GROUP BY u.id
         ORDER BY u.created_at DESC, u.id DESC
         LIMIT $5",
    )
    .bind(prefix)
    .bind(status)
    .bind(cursor_at)
    .bind(cursor_id)
    .bind(limit)
    .fetch_all(pool)
    .await
    .map_err(db_err)?;
    rows.iter()
        .map(|row| {
            Ok(Listed {
                id: row.try_get("id").map_err(db_err)?,
                created_at: row.try_get("created_at").map_err(db_err)?,
                deleted: row.try_get("deleted").map_err(db_err)?,
                devices: row.try_get("devices").map_err(db_err)?,
                last_seen: row.try_get("last_seen").map_err(db_err)?,
            })
        })
        .collect()
}

async fn push_for_users(
    pool: &PgPool,
    rows: &[Listed],
) -> Result<std::collections::HashMap<Uuid, &'static str>, ApiError> {
    let ids: Vec<Uuid> = rows.iter().map(|row| row.id).collect();
    if ids.is_empty() {
        return Ok(std::collections::HashMap::new());
    }
    let found = sqlx::query(
        "SELECT d.user_id,
                bool_or(pt.device_id IS NOT NULL) AS apns,
                bool_or(w.client = 'browser') AS web,
                bool_or(w.client = 'android') AS unifiedpush
         FROM devices d
         LEFT JOIN push_tokens pt ON pt.device_id = d.id
         LEFT JOIN web_push_subscriptions w ON w.device_id = d.id
         WHERE d.user_id = ANY($1) AND d.revoked_at IS NULL
         GROUP BY d.user_id",
    )
    .bind(&ids)
    .fetch_all(pool)
    .await
    .map_err(db_err)?;
    let mut out = std::collections::HashMap::new();
    for row in found {
        let id: Uuid = row.try_get("user_id").map_err(db_err)?;
        let apns: Option<bool> = row.try_get("apns").map_err(db_err)?;
        let web: Option<bool> = row.try_get("web").map_err(db_err)?;
        let unified: Option<bool> = row.try_get("unifiedpush").map_err(db_err)?;
        out.insert(
            id,
            list_push(
                apns.unwrap_or(false),
                web.unwrap_or(false),
                unified.unwrap_or(false),
            ),
        );
    }
    Ok(out)
}

struct DeviceRow {
    id: Uuid,
    added_at: DateTime<Utc>,
    last_seen: Option<DateTime<Utc>>,
    revoked: bool,
    platform: &'static str,
    push: &'static str,
    live: bool,
}

async fn fetch_devices(pool: &PgPool, user_id: Uuid) -> Result<Vec<DeviceRow>, ApiError> {
    let rows = sqlx::query(
        "SELECT d.id, d.created_at, d.last_seen_at, d.revoked_at IS NOT NULL AS revoked,
                bool_or(pt.kind = 'alert') AS alert,
                bool_or(pt.kind = 'voip') AS voip,
                max(w.client) AS client,
                EXISTS (
                    SELECT 1 FROM sessions s
                    WHERE s.device_id = d.id AND s.revoked_at IS NULL
                ) AS live
         FROM devices d
         LEFT JOIN push_tokens pt ON pt.device_id = d.id
         LEFT JOIN web_push_subscriptions w ON w.device_id = d.id
         WHERE d.user_id = $1
         GROUP BY d.id
         ORDER BY d.created_at, d.id",
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
    .map_err(db_err)?;
    rows.iter()
        .map(|row| {
            let alert: Option<bool> = row.try_get("alert").map_err(db_err)?;
            let voip: Option<bool> = row.try_get("voip").map_err(db_err)?;
            let client: Option<String> = row.try_get("client").map_err(db_err)?;
            let (platform, push) = device_channel(
                alert.unwrap_or(false),
                voip.unwrap_or(false),
                client.as_deref(),
            );
            Ok(DeviceRow {
                id: row.try_get("id").map_err(db_err)?,
                added_at: row.try_get("created_at").map_err(db_err)?,
                last_seen: row.try_get("last_seen_at").map_err(db_err)?,
                revoked: row.try_get("revoked").map_err(db_err)?,
                platform,
                push,
                live: row.try_get("live").map_err(db_err)?,
            })
        })
        .collect()
}

async fn media_for(pool: &PgPool, user_id: Uuid) -> Result<(i64, i64), ApiError> {
    let row = sqlx::query(
        "SELECT count(id) AS objects, coalesce(sum(size_bytes), 0)::bigint AS bytes
         FROM media_objects WHERE uploader_user_id = $1",
    )
    .bind(user_id)
    .fetch_one(pool)
    .await
    .map_err(db_err)?;
    Ok((
        row.try_get("objects").map_err(db_err)?,
        row.try_get("bytes").map_err(db_err)?,
    ))
}

async fn pin_guard_for(pool: &PgPool, user_id: Uuid) -> Result<bool, ApiError> {
    sqlx::query_scalar(
        "SELECT EXISTS (
            SELECT 1 FROM device_pin_guards g
            JOIN devices d ON d.id = g.device_id
            WHERE d.user_id = $1
         )",
    )
    .bind(user_id)
    .fetch_one(pool)
    .await
    .map_err(db_err)
}

fn search_prefix(q: Option<String>) -> Result<Option<String>, ApiError> {
    let Some(q) = q else {
        return Ok(None);
    };
    let q = q.trim().to_owned();
    if q.is_empty() {
        return Ok(None);
    }
    let ok = (4..=36).contains(&q.len())
        && q.bytes()
            .all(|byte| byte.is_ascii_hexdigit() || byte == b'-');
    if !ok {
        return Err(ApiError::validation(
            "Search must be 4 to 36 hex characters or dashes.",
        ));
    }
    Ok(Some(q.to_ascii_lowercase()))
}

fn status_filter(status: Option<String>) -> Result<Option<&'static str>, ApiError> {
    match status.as_deref().map(str::trim) {
        None | Some("") => Ok(None),
        Some("active") => Ok(Some("active")),
        Some("deleted") => Ok(Some("deleted")),
        Some(_) => Err(ApiError::validation("Status must be active or deleted.")),
    }
}

fn limit_of(limit: Option<i64>) -> Result<i64, ApiError> {
    match limit {
        None => Ok(DEFAULT_LIMIT),
        Some(limit) if (1..=MAX_LIMIT).contains(&limit) => Ok(limit),
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

fn cursor_stamp(row: &Listed) -> String {
    format!(
        "{},{}",
        row.created_at.to_rfc3339_opts(SecondsFormat::Micros, true),
        row.id
    )
}

fn list_push(apns: bool, web: bool, unifiedpush: bool) -> &'static str {
    match (apns, web, unifiedpush) {
        (false, false, false) => "none",
        (true, false, false) => "apns",
        (false, true, false) => "web",
        (false, false, true) => "unifiedpush",
        _ => "mixed",
    }
}

fn device_channel(alert: bool, voip: bool, client: Option<&str>) -> (&'static str, &'static str) {
    if alert || voip {
        let push = if alert && voip { "apns+voip" } else { "apns" };
        return ("ios", push);
    }
    match client {
        Some("browser") => ("web", "web"),
        Some("android") => ("android", "unifiedpush"),
        _ => ("unknown", "none"),
    }
}

fn day(time: DateTime<Utc>) -> String {
    time.format("%Y-%m-%d").to_string()
}

fn missing_account() -> ApiError {
    ApiError::missing_account()
}

fn db_err(err: sqlx::Error) -> ApiError {
    crate::db::log_db("users", &err);
    ApiError::upstream_postgres()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn search_and_push_rules() {
        assert!(search_prefix(Some("abc".to_owned())).is_err());
        assert!(search_prefix(Some("gggg".to_owned())).is_err());
        assert_eq!(
            search_prefix(Some("AB-cd".to_owned())).unwrap().as_deref(),
            Some("ab-cd")
        );
        assert_eq!(list_push(true, true, false), "mixed");
        assert_eq!(list_push(false, false, true), "unifiedpush");
        assert_eq!(device_channel(true, true, None), ("ios", "apns+voip"));
        assert_eq!(
            device_channel(false, false, Some("android")),
            ("android", "unifiedpush")
        );
    }
}
