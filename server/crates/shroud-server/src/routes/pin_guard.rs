//! PIN guard: the server half of the web client's PIN unlock.
//!
//! Human: A 6-digit PIN has a million values, so a vault key wrapped under the PIN alone falls to
//! anyone holding a copy of the browser profile. The browser therefore wraps it under the PIN
//! *and* a random pepper that only this table holds. The pepper is released against an auth key
//! derived from the PIN, and each wrong one counts: after [`MAX_FAILED_ATTEMPTS`] the guard is
//! deleted, the PIN stops working for good, and only the 12-word phrase opens the vault.
//!
//! Agent: `POST /pin-guard` (session) creates or replaces this device's guard.
//! `POST /pin-guard/unlock` takes no session — the web client seals its token inside the vault,
//! so it has none before unlock. The guard id plus the auth key are the credential.
//! `POST /pin-guard/abandon` takes no session either: "Forgot PIN" on the lock screen cannot
//! read the token, and it has to delete the guard so a copied profile cannot still unlock.
//! `DELETE /pin-guard` (session) drops it. Never log auth keys, verifiers or peppers.

use axum::{
    Json,
    extract::State,
    http::{HeaderMap, StatusCode},
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::rate_limit::budgets;
use crate::state::AppState;

/// Wrong auth keys a guard survives. The next one deletes it.
pub const MAX_FAILED_ATTEMPTS: i32 = 10;
const KEY_BYTES: usize = 32;

#[derive(Debug, Deserialize)]
pub struct CreateGuardRequest {
    /// Base64 SHA-256 of the client's PIN-derived auth key.
    pub verifier: String,
}

#[derive(Debug, Serialize)]
pub struct CreateGuardResponse {
    pub guard_id: Uuid,
    /// Base64 pepper; the client mixes it into the PIN wrap and keeps it only in memory.
    pub pepper: String,
    pub max_attempts: i32,
}

#[derive(Debug, Deserialize)]
pub struct UnlockRequest {
    pub guard_id: Uuid,
    /// Base64 PIN-derived auth key.
    pub auth_key: String,
}

#[derive(Debug, Serialize)]
pub struct UnlockResponse {
    pub pepper: String,
}

fn decode_key(value: &str, field: &str) -> Result<Vec<u8>, AppError> {
    let bytes = STANDARD
        .decode(value.trim())
        .map_err(|_| AppError::validation(format!("{field} must be base64.")))?;
    if bytes.len() != KEY_BYTES {
        return Err(AppError::validation(format!(
            "{field} must be {KEY_BYTES} bytes."
        )));
    }
    Ok(bytes)
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0_u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// `POST /pin-guard` — create or replace this device's guard (a new PIN).
pub async fn create_guard(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<CreateGuardRequest>,
) -> Result<(StatusCode, Json<CreateGuardResponse>), AppError> {
    state
        .rate_limiter
        .check_budget(
            "pin_guard_user",
            &auth.user_id.to_string(),
            budgets::PIN_GUARD_USER,
        )
        .await?;
    let verifier = decode_key(&body.verifier, "verifier")?;

    let mut pepper = [0_u8; KEY_BYTES];
    rand::rngs::OsRng
        .try_fill_bytes(&mut pepper)
        .map_err(|err| AppError::Internal(format!("pepper RNG failed: {err}")))?;

    // A new PIN is a new guard: new id, new pepper, attempts back to zero.
    let guard_id: Uuid = sqlx::query_scalar(
        r#"
        INSERT INTO device_pin_guards (device_id, verifier, pepper)
        VALUES ($1, $2, $3)
        ON CONFLICT (device_id) DO UPDATE
        SET id = gen_random_uuid(),
            verifier = EXCLUDED.verifier,
            pepper = EXCLUDED.pepper,
            failed_attempts = 0,
            created_at = now()
        RETURNING id
        "#,
    )
    .bind(auth.device_id)
    .bind(&verifier)
    .bind(&pepper[..])
    .fetch_one(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("create pin guard failed: {err}")))?;

    tracing::info!(user_id = %auth.user_id, device_id = %auth.device_id, "pin_guard.create ok");

    Ok((
        StatusCode::CREATED,
        Json(CreateGuardResponse {
            guard_id,
            pepper: STANDARD.encode(pepper),
            max_attempts: MAX_FAILED_ATTEMPTS,
        }),
    ))
}

/// `POST /pin-guard/unlock` — release the pepper for the right auth key.
///
/// Wrong key: 403 `PIN_INCORRECT` with the attempts left. The attempt that uses up the budget
/// deletes the guard, and it and every later call get 410 `PIN_GUARD_GONE`.
pub async fn unlock(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<UnlockRequest>,
) -> Result<Json<UnlockResponse>, AppError> {
    let ip = state.client_ip(&headers);
    state
        .rate_limiter
        .check_budget("pin_guard_ip", &ip, budgets::PIN_GUARD_IP)
        .await?;
    let auth_key = decode_key(&body.auth_key, "auth_key")?;

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    // The row lock serialises concurrent guesses, so none of them slips past the counter.
    let row: Option<(Vec<u8>, Vec<u8>, i32)> = sqlx::query_as(
        r#"
        SELECT verifier, pepper, failed_attempts
        FROM device_pin_guards
        WHERE id = $1
        FOR UPDATE
        "#,
    )
    .bind(body.guard_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("pin guard lookup failed: {err}")))?;

    let Some((verifier, pepper, failed)) = row else {
        return Err(AppError::pin_guard_gone());
    };

    if constant_time_eq(&Sha256::digest(&auth_key), &verifier) {
        if failed != 0 {
            sqlx::query(r#"UPDATE device_pin_guards SET failed_attempts = 0 WHERE id = $1"#)
                .bind(body.guard_id)
                .execute(&mut *tx)
                .await
                .map_err(|err| AppError::Internal(format!("pin guard reset failed: {err}")))?;
        }
        tx.commit()
            .await
            .map_err(|err| AppError::Internal(format!("commit pin guard failed: {err}")))?;
        return Ok(Json(UnlockResponse {
            pepper: STANDARD.encode(pepper),
        }));
    }

    let failed = failed + 1;
    if failed >= MAX_FAILED_ATTEMPTS {
        sqlx::query(r#"DELETE FROM device_pin_guards WHERE id = $1"#)
            .bind(body.guard_id)
            .execute(&mut *tx)
            .await
            .map_err(|err| AppError::Internal(format!("pin guard delete failed: {err}")))?;
    } else {
        sqlx::query(r#"UPDATE device_pin_guards SET failed_attempts = $2 WHERE id = $1"#)
            .bind(body.guard_id)
            .bind(failed)
            .execute(&mut *tx)
            .await
            .map_err(|err| AppError::Internal(format!("pin guard count failed: {err}")))?;
    }
    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit pin guard failed: {err}")))?;

    tracing::warn!(guard_id = %body.guard_id, failed, "pin_guard.unlock wrong key");
    if failed >= MAX_FAILED_ATTEMPTS {
        return Err(AppError::pin_guard_gone());
    }
    Err(AppError::pin_incorrect(MAX_FAILED_ATTEMPTS - failed))
}

#[derive(Debug, Deserialize)]
pub struct AbandonRequest {
    pub guard_id: Uuid,
}

/// `POST /pin-guard/abandon` — delete one guard without a session.
///
/// Human: The lock screen no longer has the session token (it is sealed in the vault). Choosing
/// the encryption phrase has to make this PIN stop working everywhere a copy of this browser
/// exists, which means deleting the pepper. Knowing the guard id is enough: it is a random
/// UUID already sitting in that profile, and ten wrong guesses delete the guard anyway.
/// Agent: RATE LIMITS per IP; DELETES device_pin_guards by id; 204 when it is already gone.
pub async fn abandon(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<AbandonRequest>,
) -> Result<StatusCode, AppError> {
    let ip = state.client_ip(&headers);
    state
        .rate_limiter
        .check_budget("pin_guard_abandon_ip", &ip, budgets::PIN_GUARD_IP)
        .await?;
    sqlx::query(r#"DELETE FROM device_pin_guards WHERE id = $1"#)
        .bind(body.guard_id)
        .execute(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("abandon pin guard failed: {err}")))?;
    tracing::info!("pin_guard.abandon");
    Ok(StatusCode::NO_CONTENT)
}

/// `DELETE /pin-guard` — forget this device's guard.
pub async fn delete_guard(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<StatusCode, AppError> {
    sqlx::query(r#"DELETE FROM device_pin_guards WHERE device_id = $1"#)
        .bind(auth.device_id)
        .execute(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("delete pin guard failed: {err}")))?;
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constant_time_eq_matches_only_equal_slices() {
        assert!(constant_time_eq(b"abc", b"abc"));
        assert!(!constant_time_eq(b"abc", b"abd"));
        assert!(!constant_time_eq(b"abc", b"ab"));
    }

    #[test]
    fn keys_must_be_32_bytes_of_base64() {
        assert!(decode_key(&STANDARD.encode([1_u8; 32]), "k").is_ok());
        assert!(decode_key(&STANDARD.encode([1_u8; 31]), "k").is_err());
        assert!(decode_key("not base64!", "k").is_err());
    }
}
