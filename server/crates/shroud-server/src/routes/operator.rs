//! Internal operator listener.
//!
//! Human: The admin console asks this port to remove a device, sign an account out, or
//! delete an account, and reads the server's counters here. It is bound inside the container
//! and not published. The public port has none of these routes: on a small server, live
//! counters would show anyone when people are active.
//! Agent: Bearer `OPERATOR_TOKEN` on every request, or 403. The three POSTs call the same
//! functions as the user-facing handlers; `GET /operator/metrics` is the Prometheus text;
//! `GET /operator/push/check` asks each push relay whether this server's setup works
//! ([`crate::push::PushService::check`]), notifying no one; `GET /operator/calls/check` asks
//! each STUN and TURN server callers are handed to do its job ([`crate::ice_check`]);
//! `GET /operator/retention/check` counts rows the retention jobs should already have removed
//! ([`crate::retention_check`]); `GET /operator/rate-limits/check` tries every budget on the live
//! limiter with throwaway keys ([`crate::rate_limit_check`]).
//! Health and the public API are not mounted.

use std::sync::Arc;

use axum::extract::{Path, Request, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use sha2::{Digest, Sha256};
use sqlx::Row;
use uuid::Uuid;

use crate::routes::auth::{AccountDelete, SignOut};
use crate::routes::devices;
use crate::state::AppState;

/// Longest `Authorization` value this listener will read. A longer one is treated as
/// absent, so a huge header cannot make the compare expensive. The check is on the
/// header, not the secret: the expected token is always hashed. A real token is at most
/// 256 bytes, and `Bearer ` plus that fits under this cap.
const MAX_AUTHORIZATION_LEN: usize = 300;

/// The operator router. `token` is checked before any route, including a path this
/// router does not have. A match on an unknown path is 404.
pub fn router(token: Arc<str>) -> Router<AppState> {
    Router::new()
        .route("/operator/devices/{id}/remove", post(remove_device))
        .route("/operator/users/{id}/sign-out-all", post(sign_out_all))
        .route("/operator/users/{id}/delete", post(delete_account))
        .route("/operator/metrics", get(crate::routes::health::metrics))
        .route("/operator/push/check", get(push_check))
        .route("/operator/calls/check", get(calls_check))
        .route("/operator/retention/check", get(retention_check))
        .route("/operator/rate-limits/check", get(rate_limits_check))
        .fallback(unknown)
        .layer(middleware::from_fn(move |request: Request, next: Next| {
            let token = Arc::clone(&token);
            async move { require_token(&token, request, next).await }
        }))
}

async fn push_check(State(state): State<AppState>) -> Json<Vec<crate::push::PushCheck>> {
    Json(state.push.check().await)
}

async fn calls_check(State(state): State<AppState>) -> Json<Vec<crate::ice_check::CallCheck>> {
    let now = chrono::Utc::now().timestamp().unsigned_abs();
    Json(crate::ice_check::check(&state.ice_servers, state.turn.as_ref(), now).await)
}

async fn retention_check(
    State(state): State<AppState>,
) -> Json<Vec<crate::retention_check::RetentionCheck>> {
    Json(crate::retention_check::check(&state.pool).await)
}

async fn rate_limits_check(
    State(state): State<AppState>,
) -> Json<Vec<crate::rate_limit_check::RateLimitCheck>> {
    Json(crate::rate_limit_check::check(&state.rate_limiter).await)
}

async fn unknown() -> StatusCode {
    StatusCode::NOT_FOUND
}

async fn require_token(token: &str, request: Request, next: Next) -> Response {
    let presented = presented_token(request.headers());
    if !token_matches(token, &presented) {
        return StatusCode::FORBIDDEN.into_response();
    }
    next.run(request).await
}

/// The bearer token, or empty when the header is missing, not `Bearer`, not text, or too long.
fn presented_token(headers: &HeaderMap) -> String {
    let Some(value) = headers.get(header::AUTHORIZATION) else {
        return String::new();
    };
    if value.len() > MAX_AUTHORIZATION_LEN {
        return String::new();
    }
    let Ok(text) = value.to_str() else {
        return String::new();
    };
    let Some((scheme, token)) = text.split_once(' ') else {
        return String::new();
    };
    if !scheme.eq_ignore_ascii_case("bearer") {
        return String::new();
    }
    token.to_string()
}

/// True when `presented` is `expected`. Both are hashed first, so a different length
/// does not return before the compare.
fn token_matches(expected: &str, presented: &str) -> bool {
    let expected_digest = Sha256::digest(expected.as_bytes());
    let presented_digest = Sha256::digest(presented.as_bytes());
    let mut diff = 0u8;
    for (left, right) in expected_digest.iter().zip(presented_digest.iter()) {
        diff |= left ^ right;
    }
    diff == 0
}

fn not_found() -> Response {
    StatusCode::NOT_FOUND.into_response()
}

fn conflict() -> Response {
    StatusCode::CONFLICT.into_response()
}

fn internal(err: impl std::fmt::Display) -> Response {
    tracing::error!(error = %err, "operator request failed");
    StatusCode::INTERNAL_SERVER_ERROR.into_response()
}

fn parse_id(raw: &str) -> Option<Uuid> {
    Uuid::parse_str(raw).ok()
}

async fn remove_device(State(state): State<AppState>, Path(id): Path<String>) -> Response {
    match remove_locked_device(&state, &id).await {
        Ok(response) => response,
        Err(err) => internal(err),
    }
}

async fn remove_locked_device(state: &AppState, id: &str) -> Result<Response, String> {
    let Some(device_id) = parse_id(id) else {
        return Ok(not_found());
    };
    let mut tx = state.pool.begin().await.map_err(|err| err.to_string())?;
    let row = sqlx::query(
        r#"
        SELECT user_id, (revoked_at IS NOT NULL) AS revoked
        FROM devices
        WHERE id = $1
        FOR UPDATE
        "#,
    )
    .bind(device_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| err.to_string())?;
    let Some(row) = row else {
        return Ok(not_found());
    };
    let user_id: Uuid = row.try_get("user_id").map_err(|err| err.to_string())?;
    let revoked: bool = row.try_get("revoked").map_err(|err| err.to_string())?;
    if revoked {
        return Ok(conflict());
    }
    devices::commit_device_removal(state, tx, user_id, device_id)
        .await
        .map_err(|err| err.to_string())?;
    Ok(StatusCode::NO_CONTENT.into_response())
}

async fn sign_out_all(State(state): State<AppState>, Path(id): Path<String>) -> Response {
    let Some(user_id) = parse_id(&id) else {
        return not_found();
    };
    match crate::routes::auth::sign_out_user(&state, user_id).await {
        Ok(SignOut::Missing) => not_found(),
        Ok(SignOut::Deleted | SignOut::Idle) => conflict(),
        Ok(SignOut::SignedOut(count)) => (
            StatusCode::OK,
            Json(serde_json::json!({ "detail": sessions_detail(count) })),
        )
            .into_response(),
        Err(err) => internal(err),
    }
}

/// `1 session` or `N sessions`. The console stores this on the audit row.
fn sessions_detail(count: usize) -> String {
    if count == 1 {
        "1 session".to_string()
    } else {
        format!("{count} sessions")
    }
}

async fn delete_account(State(state): State<AppState>, Path(id): Path<String>) -> Response {
    let Some(user_id) = parse_id(&id) else {
        return not_found();
    };
    match crate::routes::auth::delete_user_account(&state, user_id, None).await {
        Ok(AccountDelete::Missing) => not_found(),
        Ok(AccountDelete::Deleted) => conflict(),
        Ok(AccountDelete::Done) => StatusCode::NO_CONTENT.into_response(),
        Err(err) => internal(err),
    }
}

#[cfg(test)]
mod tests {
    use super::token_matches;

    #[test]
    fn token_matches_compares_the_whole_value() {
        assert!(token_matches("abc", "abc"));
        assert!(!token_matches("abc", "abd"));
        assert!(!token_matches("abc", "ab"));
        assert!(!token_matches("abc", "abcd"));
        assert!(!token_matches("abc", ""));
    }
}
