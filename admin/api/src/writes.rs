//! Write routes in §3.5. Each one needs role `write`, a fresh re-auth, and the CSRF header.
//! The change itself is a call to the operator listener. One `admin.audit_log` row is written
//! for the attempt: `ok`, `refused`, or `failed`.

use axum::Json;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{Router, post};
use chrono::{DateTime, Utc};
use serde::Deserialize;
use sqlx::PgPool;
use sqlx::Row;
use uuid::Uuid;

use crate::auth::{self, SignedIn};
use crate::error::ApiError;
use crate::operator_api::{self, Answer};
use crate::state::AppState;

const DEVICE_DONE: &str = "That device is already removed.";
const ACCOUNT_DONE: &str = "That account is already deleted.";
const SIGNED_OUT: &str = "Those devices are already signed out.";

pub(crate) fn routes() -> Router<AppState> {
    Router::new()
        .route(
            "/users/{id}/devices/{device_id}/remove",
            post(remove_device),
        )
        .route("/users/{id}/sign-out-all", post(sign_out_all))
        .route("/users/{id}/delete", post(delete_account))
}

async fn remove_device(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((user_id, device_id)): Path<(String, String)>,
) -> Result<Response, ApiError> {
    let signed = match open(&state, &headers).await? {
        Ok(signed) => signed,
        Err(response) => return Ok(response),
    };
    let Some(user_id) = parse_id(&user_id) else {
        return Err(ApiError::missing_account());
    };
    let Some(device_id) = parse_id(&device_id) else {
        return Err(ApiError::missing_device());
    };
    let pool = pool_of(&state)?;
    gate(&pool, &signed, "device.remove", "device", device_id).await?;
    let Some(device) = load_device(&pool, device_id).await? else {
        refuse(&pool, &signed, "device.remove", "device", device_id, None).await?;
        return Err(ApiError::missing_device());
    };
    if device.user_id != user_id {
        refuse(&pool, &signed, "device.remove", "device", device_id, None).await?;
        return Err(ApiError::missing_device());
    }
    if device.revoked {
        refuse(&pool, &signed, "device.remove", "device", device_id, None).await?;
        return Err(ApiError::already_done(DEVICE_DONE));
    }
    finish(
        &pool,
        &signed,
        "device.remove",
        "device",
        device_id,
        operator_api::post(&format!("/operator/devices/{device_id}/remove")).await,
        DEVICE_DONE,
    )
    .await
}

async fn sign_out_all(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(user_id): Path<String>,
) -> Result<Response, ApiError> {
    let signed = match open(&state, &headers).await? {
        Ok(signed) => signed,
        Err(response) => return Ok(response),
    };
    let Some(user_id) = parse_id(&user_id) else {
        return Err(ApiError::missing_account());
    };
    let pool = pool_of(&state)?;
    gate(&pool, &signed, "user.sign_out_all", "user", user_id).await?;
    match load_account(&pool, user_id).await? {
        Account::Missing => {
            refuse(&pool, &signed, "user.sign_out_all", "user", user_id, None).await?;
            Err(ApiError::missing_account())
        }
        Account::Deleted => {
            refuse(&pool, &signed, "user.sign_out_all", "user", user_id, None).await?;
            Err(ApiError::already_done(ACCOUNT_DONE))
        }
        Account::Live => {
            finish(
                &pool,
                &signed,
                "user.sign_out_all",
                "user",
                user_id,
                operator_api::post(&format!("/operator/users/{user_id}/sign-out-all")).await,
                SIGNED_OUT,
            )
            .await
        }
    }
}

async fn delete_account(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(user_id): Path<String>,
    body: Result<Json<ConfirmBody>, JsonRejection>,
) -> Result<Response, ApiError> {
    let signed = match open(&state, &headers).await? {
        Ok(signed) => signed,
        Err(response) => return Ok(response),
    };
    let Some(user_id) = parse_id(&user_id) else {
        return Err(ApiError::missing_account());
    };
    let pool = pool_of(&state)?;
    gate(&pool, &signed, "user.delete", "user", user_id).await?;
    let Json(body) = match body {
        Ok(body) => body,
        Err(_) => {
            refuse(
                &pool,
                &signed,
                "user.delete",
                "user",
                user_id,
                Some("Confirm"),
            )
            .await?;
            return Err(ApiError::validation("The request body isn't valid."));
        }
    };
    if Uuid::parse_str(body.confirm.trim()).ok().as_ref() != Some(&user_id) {
        refuse(
            &pool,
            &signed,
            "user.delete",
            "user",
            user_id,
            Some("Confirm"),
        )
        .await?;
        return Err(ApiError::validation("Type the full account id to confirm."));
    }
    match load_account(&pool, user_id).await? {
        Account::Missing => {
            refuse(&pool, &signed, "user.delete", "user", user_id, None).await?;
            Err(ApiError::missing_account())
        }
        Account::Deleted => {
            refuse(&pool, &signed, "user.delete", "user", user_id, None).await?;
            Err(ApiError::already_done(ACCOUNT_DONE))
        }
        Account::Live => {
            finish(
                &pool,
                &signed,
                "user.delete",
                "user",
                user_id,
                operator_api::post(&format!("/operator/users/{user_id}/delete")).await,
                ACCOUNT_DONE,
            )
            .await
        }
    }
}

#[derive(Deserialize)]
struct ConfirmBody {
    confirm: String,
}

enum Account {
    Missing,
    Deleted,
    Live,
}

struct DeviceRow {
    user_id: Uuid,
    revoked: bool,
}

async fn open(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<Result<SignedIn, Response>, ApiError> {
    auth::admit_mutation(state, headers).await
}

fn pool_of(state: &AppState) -> Result<PgPool, ApiError> {
    state
        .pool()
        .cloned()
        .ok_or_else(ApiError::upstream_postgres)
}

fn parse_id(value: &str) -> Option<Uuid> {
    Uuid::parse_str(value).ok()
}

async fn gate(
    pool: &PgPool,
    signed: &SignedIn,
    action: &str,
    target_kind: &str,
    target_id: Uuid,
) -> Result<(), ApiError> {
    if signed.role != "write" {
        refuse(
            pool,
            signed,
            action,
            target_kind,
            target_id,
            Some("View only"),
        )
        .await?;
        return Err(ApiError::forbidden());
    }
    if !auth::reauth_current(signed.reauth_until) {
        return Err(ApiError::reauth_required());
    }
    Ok(())
}

async fn refuse(
    pool: &PgPool,
    signed: &SignedIn,
    action: &str,
    target_kind: &str,
    target_id: Uuid,
    detail: Option<&str>,
) -> Result<(), ApiError> {
    auth::record(
        pool,
        signed.operator_id,
        action,
        Some(target_kind),
        Some(target_id),
        "refused",
        detail,
    )
    .await
}

async fn finish(
    pool: &PgPool,
    signed: &SignedIn,
    action: &str,
    target_kind: &str,
    target_id: Uuid,
    answer: Answer,
    already: &'static str,
) -> Result<Response, ApiError> {
    let (outcome, detail, response) = match answer {
        Answer::Done(done) => (
            "ok",
            done.detail,
            Ok(StatusCode::NO_CONTENT.into_response()),
        ),
        Answer::Already => ("refused", None, Err(ApiError::already_done(already))),
        Answer::Failed => ("failed", None, Err(ApiError::upstream_api())),
    };
    auth::record(
        pool,
        signed.operator_id,
        action,
        Some(target_kind),
        Some(target_id),
        outcome,
        detail.as_deref(),
    )
    .await?;
    response
}

async fn load_account(pool: &PgPool, id: Uuid) -> Result<Account, ApiError> {
    let Some(row) = sqlx::query("SELECT deleted_at FROM users WHERE id = $1")
        .bind(id)
        .fetch_optional(pool)
        .await
        .map_err(db_err)?
    else {
        return Ok(Account::Missing);
    };
    let deleted_at: Option<DateTime<Utc>> = row.try_get("deleted_at").map_err(db_err)?;
    Ok(if deleted_at.is_some() {
        Account::Deleted
    } else {
        Account::Live
    })
}

async fn load_device(pool: &PgPool, id: Uuid) -> Result<Option<DeviceRow>, ApiError> {
    let Some(row) = sqlx::query("SELECT user_id, revoked_at FROM devices WHERE id = $1")
        .bind(id)
        .fetch_optional(pool)
        .await
        .map_err(db_err)?
    else {
        return Ok(None);
    };
    let revoked_at: Option<DateTime<Utc>> = row.try_get("revoked_at").map_err(db_err)?;
    Ok(Some(DeviceRow {
        user_id: row.try_get("user_id").map_err(db_err)?,
        revoked: revoked_at.is_some(),
    }))
}

fn db_err(err: sqlx::Error) -> ApiError {
    crate::db::log_db("writes", &err);
    ApiError::upstream_postgres()
}

type JsonRejection = axum::extract::rejection::JsonRejection;
