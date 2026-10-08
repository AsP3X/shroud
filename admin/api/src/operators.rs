//! Operator routes in §3.6. The list is visible to every signed-in operator. Inviting,
//! changing a role, disabling and resetting an authenticator need role `write` and a fresh
//! re-auth. A setup link is a relative `/setup/{token}` path, shown once, and expires in
//! 15 minutes. Disabling an operator, or resetting their authenticator, ends their sessions.
//! Nobody can demote or disable themselves.

use axum::Json;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{Router, get, post};
use chrono::{DateTime, SecondsFormat, TimeDelta, Utc};
use serde::{Deserialize, Serialize};
use sqlx::PgPool;
use sqlx::Row;
use uuid::Uuid;

use crate::auth::{self, SignedIn};
use crate::crypto;
use crate::error::ApiError;
use crate::state::AppState;

const INVITE_TTL_SECS: i64 = 15 * 60;

pub(crate) fn routes() -> Router<AppState> {
    Router::new()
        .route("/operators", get(list).post(invite))
        .route("/operators/{id}", axum::routing::patch(update))
        .route("/operators/{id}/reset-totp", post(reset_totp))
}

#[derive(Serialize)]
struct OperatorBody {
    id: String,
    name: String,
    role: String,
    enabled: bool,
    last_sign_in_at: Option<String>,
    totp_enrolled: bool,
}

#[derive(Serialize)]
struct SetupBody {
    setup_url: String,
}

#[derive(Deserialize)]
struct InviteBody {
    name: String,
    role: String,
}

#[derive(Deserialize)]
struct PatchBody {
    role: Option<String>,
    enabled: Option<bool>,
}

async fn list(State(state): State<AppState>, headers: HeaderMap) -> Result<Response, ApiError> {
    if let auth::Admission::Out(response) = auth::admit(&state, &headers).await? {
        return Ok(response);
    }
    let pool = pool_of(&state)?;
    let rows = sqlx::query(
        "SELECT id, name, role, enabled, last_sign_in_at,
                password_hash IS NOT NULL AS totp_enrolled
         FROM admin.operators
         ORDER BY created_at ASC, id ASC",
    )
    .fetch_all(&pool)
    .await
    .map_err(db_err)?;
    let mut items = Vec::with_capacity(rows.len());
    for row in rows {
        let signed_in_at: Option<DateTime<Utc>> = row.try_get("last_sign_in_at").map_err(db_err)?;
        items.push(OperatorBody {
            id: row.try_get::<Uuid, _>("id").map_err(db_err)?.to_string(),
            name: row.try_get("name").map_err(db_err)?,
            role: row.try_get("role").map_err(db_err)?,
            enabled: row.try_get("enabled").map_err(db_err)?,
            last_sign_in_at: signed_in_at.map(stamp),
            totp_enrolled: row.try_get("totp_enrolled").map_err(db_err)?,
        });
    }
    Ok(Json(items).into_response())
}

async fn invite(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Result<Json<InviteBody>, JsonRejection>,
) -> Result<Response, ApiError> {
    let signed = match auth::admit_mutation(&state, &headers).await? {
        Ok(signed) => signed,
        Err(response) => return Ok(response),
    };
    let pool = pool_of(&state)?;
    authorize(&pool, &signed, "operator.create", None).await?;
    let Json(body) = match body {
        Ok(body) => body,
        Err(_) => {
            refused(&pool, &signed, "operator.create", None, Some("Body")).await?;
            return Err(ApiError::validation("The request body isn't valid."));
        }
    };
    let name = match clean_name(&body.name) {
        Ok(name) => name,
        Err(error) => {
            refused(&pool, &signed, "operator.create", None, Some("Name")).await?;
            return Err(error);
        }
    };
    if role_of(Some(body.role.as_str())).is_none() {
        refused(&pool, &signed, "operator.create", None, Some("Role")).await?;
        return Err(ApiError::validation("Role must be read or write."));
    }
    let token = crypto::random_token();
    let token_hash = crypto::sha256(token.as_bytes());
    let expires = Utc::now() + TimeDelta::seconds(INVITE_TTL_SECS);
    let mut tx = pool.begin().await.map_err(db_err)?;
    let inserted: Result<Uuid, sqlx::Error> =
        sqlx::query_scalar("INSERT INTO admin.operators (name, role) VALUES ($1, $2) RETURNING id")
            .bind(&name)
            .bind(&body.role)
            .fetch_one(&mut *tx)
            .await;
    let operator_id = match inserted {
        Ok(id) => id,
        Err(err) if is_unique(&err) => {
            refused(&pool, &signed, "operator.create", None, Some("Name")).await?;
            return Err(ApiError::validation("An operator already has that name."));
        }
        Err(err) => return Err(db_err(err)),
    };
    insert_link(&mut tx, &token_hash, operator_id, expires).await?;
    auth::record(
        &mut *tx,
        signed.operator_id,
        "operator.create",
        Some("operator"),
        Some(operator_id),
        "ok",
        None,
    )
    .await?;
    tx.commit().await.map_err(db_err)?;
    Ok(Json(SetupBody {
        setup_url: setup_path(&token),
    })
    .into_response())
}

async fn update(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Result<Json<PatchBody>, JsonRejection>,
) -> Result<Response, ApiError> {
    let signed = match auth::admit_mutation(&state, &headers).await? {
        Ok(signed) => signed,
        Err(response) => return Ok(response),
    };
    let Some(operator_id) = Uuid::parse_str(&id).ok() else {
        return Err(ApiError::missing_operator());
    };
    let pool = pool_of(&state)?;
    authorize(&pool, &signed, "operator.update", Some(operator_id)).await?;
    let Json(body) = match body {
        Ok(body) => body,
        Err(_) => {
            refused(
                &pool,
                &signed,
                "operator.update",
                Some(operator_id),
                Some("Body"),
            )
            .await?;
            return Err(ApiError::validation("The request body isn't valid."));
        }
    };
    if body.role.is_none() && body.enabled.is_none() {
        refused(
            &pool,
            &signed,
            "operator.update",
            Some(operator_id),
            Some("Body"),
        )
        .await?;
        return Err(ApiError::validation("Say which role or status to set."));
    }
    if body.role.is_some() && role_of(body.role.as_deref()).is_none() {
        refused(
            &pool,
            &signed,
            "operator.update",
            Some(operator_id),
            Some("Role"),
        )
        .await?;
        return Err(ApiError::validation("Role must be read or write."));
    }
    if !exists(&pool, operator_id).await? {
        refused(&pool, &signed, "operator.update", Some(operator_id), None).await?;
        return Err(ApiError::missing_operator());
    }
    if operator_id == signed.operator_id
        && (body.enabled == Some(false) || body.role.as_deref() == Some("read"))
    {
        refused(
            &pool,
            &signed,
            "operator.update",
            Some(operator_id),
            Some("Not yourself"),
        )
        .await?;
        return Err(ApiError::forbidden());
    }
    let detail = patch_detail(&body);
    let mut tx = pool.begin().await.map_err(db_err)?;
    sqlx::query(
        "UPDATE admin.operators
         SET role = COALESCE($2, role), enabled = COALESCE($3, enabled)
         WHERE id = $1",
    )
    .bind(operator_id)
    .bind(&body.role)
    .bind(body.enabled)
    .execute(&mut *tx)
    .await
    .map_err(db_err)?;
    if body.enabled == Some(false) {
        end_sessions(&mut tx, operator_id).await?;
    }
    auth::record(
        &mut *tx,
        signed.operator_id,
        "operator.update",
        Some("operator"),
        Some(operator_id),
        "ok",
        Some(&detail),
    )
    .await?;
    tx.commit().await.map_err(db_err)?;
    Ok(StatusCode::NO_CONTENT.into_response())
}

async fn reset_totp(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<Response, ApiError> {
    let signed = match auth::admit_mutation(&state, &headers).await? {
        Ok(signed) => signed,
        Err(response) => return Ok(response),
    };
    let Some(operator_id) = Uuid::parse_str(&id).ok() else {
        return Err(ApiError::missing_operator());
    };
    let pool = pool_of(&state)?;
    authorize(&pool, &signed, "operator.reset_totp", Some(operator_id)).await?;
    if !exists(&pool, operator_id).await? {
        refused(
            &pool,
            &signed,
            "operator.reset_totp",
            Some(operator_id),
            None,
        )
        .await?;
        return Err(ApiError::missing_operator());
    }
    let token = crypto::random_token();
    let token_hash = crypto::sha256(token.as_bytes());
    let expires = Utc::now() + TimeDelta::seconds(INVITE_TTL_SECS);
    let mut tx = pool.begin().await.map_err(db_err)?;
    sqlx::query(
        "UPDATE admin.operators
         SET password_hash = NULL, totp_secret_enc = NULL
         WHERE id = $1",
    )
    .bind(operator_id)
    .execute(&mut *tx)
    .await
    .map_err(db_err)?;
    end_sessions(&mut tx, operator_id).await?;
    sqlx::query("DELETE FROM admin.recovery_codes WHERE operator_id = $1")
        .bind(operator_id)
        .execute(&mut *tx)
        .await
        .map_err(db_err)?;
    sqlx::query(
        "UPDATE admin.setup_links SET used_at = now() WHERE operator_id = $1 AND used_at IS NULL",
    )
    .bind(operator_id)
    .execute(&mut *tx)
    .await
    .map_err(db_err)?;
    insert_link(&mut tx, &token_hash, operator_id, expires).await?;
    auth::record(
        &mut *tx,
        signed.operator_id,
        "operator.reset_totp",
        Some("operator"),
        Some(operator_id),
        "ok",
        None,
    )
    .await?;
    tx.commit().await.map_err(db_err)?;
    Ok(Json(SetupBody {
        setup_url: setup_path(&token),
    })
    .into_response())
}

fn clean_name(name: &str) -> Result<String, ApiError> {
    let name = name.trim();
    let ok = (2..=32).contains(&name.chars().count())
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
    if ok {
        Ok(name.to_owned())
    } else {
        Err(ApiError::validation(
            "Name must be 2 to 32 letters, digits, dots, dashes or underscores.",
        ))
    }
}

fn role_of(role: Option<&str>) -> Option<&str> {
    match role {
        Some("read" | "write") => role,
        _ => None,
    }
}

fn patch_detail(body: &PatchBody) -> String {
    let mut parts = Vec::new();
    if let Some(role) = &body.role {
        parts.push(format!("role {role}"));
    }
    if let Some(enabled) = body.enabled {
        parts.push((if enabled { "enabled" } else { "disabled" }).to_owned());
    }
    parts.join(", ")
}

fn setup_path(token: &str) -> String {
    format!("/setup/{token}")
}

fn stamp(time: DateTime<Utc>) -> String {
    time.to_rfc3339_opts(SecondsFormat::Secs, true)
}

async fn authorize(
    pool: &PgPool,
    signed: &SignedIn,
    action: &str,
    target_id: Option<Uuid>,
) -> Result<(), ApiError> {
    if signed.role != "write" {
        refused(pool, signed, action, target_id, Some("View only")).await?;
        return Err(ApiError::forbidden());
    }
    if !auth::reauth_current(signed.reauth_until) {
        return Err(ApiError::reauth_required());
    }
    Ok(())
}

async fn refused(
    pool: &PgPool,
    signed: &SignedIn,
    action: &str,
    target_id: Option<Uuid>,
    detail: Option<&str>,
) -> Result<(), ApiError> {
    auth::record(
        pool,
        signed.operator_id,
        action,
        target_id.map(|_| "operator"),
        target_id,
        "refused",
        detail,
    )
    .await
}

async fn exists(pool: &PgPool, id: Uuid) -> Result<bool, ApiError> {
    sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM admin.operators WHERE id = $1)")
        .bind(id)
        .fetch_one(pool)
        .await
        .map_err(db_err)
}

async fn insert_link(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    token_hash: &[u8],
    operator_id: Uuid,
    expires: DateTime<Utc>,
) -> Result<(), ApiError> {
    sqlx::query(
        "INSERT INTO admin.setup_links (token_hash, operator_id, expires_at) VALUES ($1, $2, $3)",
    )
    .bind(token_hash)
    .bind(operator_id)
    .bind(expires)
    .execute(&mut **tx)
    .await
    .map_err(db_err)?;
    Ok(())
}

async fn end_sessions(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    operator_id: Uuid,
) -> Result<(), ApiError> {
    sqlx::query("DELETE FROM admin.operator_sessions WHERE operator_id = $1")
        .bind(operator_id)
        .execute(&mut **tx)
        .await
        .map_err(db_err)?;
    Ok(())
}

fn is_unique(err: &sqlx::Error) -> bool {
    err.as_database_error()
        .and_then(|db| db.code())
        .is_some_and(|code| code == "23505")
}

fn pool_of(state: &AppState) -> Result<PgPool, ApiError> {
    state
        .pool()
        .cloned()
        .ok_or_else(ApiError::upstream_postgres)
}

fn db_err(err: sqlx::Error) -> ApiError {
    crate::db::log_db("operators", &err);
    ApiError::upstream_postgres()
}

type JsonRejection = axum::extract::rejection::JsonRejection;
