//! Session resolution from Bearer tokens (auth middleware / extractors).

use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use chrono::{DateTime, Utc};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::hash_token;
use crate::error::AppError;
use crate::state::AppState;

/// Keep revoked session rows this long for audit / debugging, then hard-delete.
pub const REVOKED_SESSION_RETENTION_DAYS: i64 = 30;
/// How often the background purge task runs.
const SESSION_PURGE_INTERVAL_SECS: u64 = 60 * 60;
/// Skip `last_used_at` / `last_seen_at` writes when already touched within this window.
const SESSION_TOUCH_THROTTLE_SECS: i64 = 5 * 60;

/// Authenticated caller bound to a user device and live session.
#[derive(Debug, Clone)]
pub struct AuthContext {
    pub user_id: Uuid,
    pub username: String,
    pub share_code: String,
    pub device_id: Uuid,
    pub device_name: Option<String>,
    pub session_id: Uuid,
}

#[derive(Debug, FromRow)]
struct AuthRow {
    session_id: Uuid,
    device_id: Uuid,
    device_name: Option<String>,
    user_id: Uuid,
    username: String,
    share_code: String,
}

impl FromRequestParts<AppState> for AuthContext {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let auth_header = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .ok_or_else(AppError::unauthorized)?;

        let token = auth_header
            .strip_prefix("Bearer ")
            .ok_or_else(AppError::unauthorized)?;

        if token.is_empty() {
            return Err(AppError::unauthorized());
        }

        let token_hash = hash_token(token);

        // Human: Join session → device → user; only non-revoked sessions authenticate.
        // Agent: DB SELECT by token_hash WHERE revoked_at IS NULL; touch last_used_at.
        let row = sqlx::query_as::<_, AuthRow>(
            r#"
            SELECT
                s.id AS session_id,
                d.id AS device_id,
                d.name AS device_name,
                u.id AS user_id,
                u.username AS username,
                u.share_code AS share_code
            FROM sessions s
            INNER JOIN devices d ON d.id = s.device_id
            INNER JOIN users u ON u.id = d.user_id
            WHERE s.token_hash = $1 AND s.revoked_at IS NULL AND d.revoked_at IS NULL
              AND u.deleted_at IS NULL
            "#,
        )
        .bind(token_hash.as_slice())
        .fetch_optional(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("session lookup failed: {err}")))?
        .ok_or_else(AppError::unauthorized)?;

        // Human: Touch at most every few minutes so hot auth paths are not two UPDATEs each request.
        // Agent: UPDATE sessions/devices only when last_* older than SESSION_TOUCH_THROTTLE_SECS.
        let now: DateTime<Utc> = Utc::now();
        let _ = sqlx::query(
            r#"
            UPDATE sessions
            SET last_used_at = $1
            WHERE id = $2
              AND (last_used_at IS NULL OR last_used_at < $1 - make_interval(secs => $3))
            "#,
        )
        .bind(now)
        .bind(row.session_id)
        .bind(SESSION_TOUCH_THROTTLE_SECS)
        .execute(&state.pool)
        .await;

        let _ = sqlx::query(
            r#"
            UPDATE devices
            SET last_seen_at = $1
            WHERE id = $2
              AND (last_seen_at IS NULL OR last_seen_at < $1 - make_interval(secs => $3))
            "#,
        )
        .bind(now)
        .bind(row.device_id)
        .bind(SESSION_TOUCH_THROTTLE_SECS)
        .execute(&state.pool)
        .await;

        Ok(AuthContext {
            user_id: row.user_id,
            username: row.username,
            share_code: row.share_code,
            device_id: row.device_id,
            device_name: row.device_name,
            session_id: row.session_id,
        })
    }
}

/// The account, device and session a WebSocket's token belongs to.
#[derive(Debug, Clone, Copy, FromRow)]
pub struct AuthIds {
    pub user_id: Uuid,
    pub device_id: Uuid,
    pub session_id: Uuid,
}

/// Resolves a raw session token to its user, device and session, for WebSocket endpoints.
///
/// Human: WebSockets authenticate with their first frame instead of a header, so the token
/// never lands in a URL, a proxy log, or browser history. `/ws` and the link-preview relay
/// share this lookup.
/// Agent: DB SELECT by token_hash WHERE revoked_at IS NULL; RETURNS [`AuthIds`] or
/// `AppError::unauthorized` for an empty, unknown, or revoked token.
pub async fn ids_for_token(pool: &sqlx::PgPool, token: &str) -> Result<AuthIds, AppError> {
    if token.is_empty() {
        return Err(AppError::unauthorized());
    }
    let token_hash = hash_token(token);
    sqlx::query_as::<_, AuthIds>(
        r#"
        SELECT u.id AS user_id, d.id AS device_id, s.id AS session_id
        FROM sessions s
        INNER JOIN devices d ON d.id = s.device_id
        INNER JOIN users u ON u.id = d.user_id
        WHERE s.token_hash = $1 AND s.revoked_at IS NULL AND d.revoked_at IS NULL
          AND u.deleted_at IS NULL
        "#,
    )
    .bind(token_hash.as_slice())
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("ws auth lookup failed: {err}")))?
    .ok_or_else(AppError::unauthorized)
}

/// True while a session may keep its WebSocket open: not revoked, its device not removed, its
/// account not deleted.
///
/// Human: A socket authenticates once, so `/ws` re-checks on its heartbeat. A revocation the
/// socket's replica never heard about (its Redis subscriber was reconnecting) still closes
/// the socket within one tick.
/// Agent: DB SELECT EXISTS by session id with the same filters as [`ids_for_token`].
pub async fn session_is_live(pool: &sqlx::PgPool, session_id: Uuid) -> Result<bool, AppError> {
    sqlx::query_scalar(
        r#"
        SELECT EXISTS (
            SELECT 1
            FROM sessions s
            INNER JOIN devices d ON d.id = s.device_id
            INNER JOIN users u ON u.id = d.user_id
            WHERE s.id = $1 AND s.revoked_at IS NULL AND d.revoked_at IS NULL
              AND u.deleted_at IS NULL
        )
        "#,
    )
    .bind(session_id)
    .fetch_one(pool)
    .await
    .map_err(|err| AppError::Internal(format!("session check failed: {err}")))
}

/// Hard-delete revoked sessions older than [`REVOKED_SESSION_RETENTION_DAYS`].
///
/// Human: Live sessions (`revoked_at IS NULL`) are never removed by this job.
/// Agent: DELETE FROM sessions WHERE revoked_at < now() - 30 days.
pub async fn purge_revoked_sessions(pool: &sqlx::PgPool) -> Result<u64, AppError> {
    let result = sqlx::query(
        r#"
        DELETE FROM sessions
        WHERE revoked_at IS NOT NULL
          AND revoked_at < now() - ($1::text || ' days')::interval
        "#,
    )
    .bind(REVOKED_SESSION_RETENTION_DAYS.to_string())
    .execute(pool)
    .await
    .map_err(|err| AppError::Internal(format!("purge revoked sessions failed: {err}")))?;

    Ok(result.rows_affected())
}

/// Background loop: purge old revoked sessions hourly.
pub fn spawn_revoked_session_purge(pool: sqlx::PgPool) {
    tokio::spawn(async move {
        let mut interval =
            tokio::time::interval(std::time::Duration::from_secs(SESSION_PURGE_INTERVAL_SECS));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        interval.tick().await;
        loop {
            interval.tick().await;
            match purge_revoked_sessions(&pool).await {
                Ok(0) => {
                    tracing::debug!("sessions.revoked_purge: nothing to purge");
                }
                Ok(n) => {
                    tracing::info!(purged = n, "sessions.revoked_purge ok");
                }
                Err(err) => {
                    tracing::warn!(error = %err, "sessions.revoked_purge failed");
                }
            }
        }
    });
}
