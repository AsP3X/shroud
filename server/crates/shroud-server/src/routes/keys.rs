//! Pre-key bundle upload, status, replenish, and fetch.

use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::keys::{
    OTPK_BATCH_MAX, OTPK_POOL_MAX, decode_public_key, decode_signature, encode_b64,
    validate_key_id, validate_registration_id,
};
use crate::state::AppState;

#[derive(Debug, Deserialize)]
pub struct PutBundleRequest {
    pub registration_id: i32,
    pub identity_key: String,
    pub signed_pre_key: SignedPreKeyIn,
    #[serde(default)]
    pub one_time_pre_keys: Vec<OneTimePreKeyIn>,
}

#[derive(Debug, Deserialize)]
pub struct SignedPreKeyIn {
    pub key_id: i32,
    pub public_key: String,
    pub signature: String,
}

#[derive(Debug, Deserialize)]
pub struct OneTimePreKeyIn {
    pub key_id: i32,
    pub public_key: String,
}

#[derive(Debug, Deserialize)]
pub struct PostOtpkRequest {
    pub one_time_pre_keys: Vec<OneTimePreKeyIn>,
}

#[derive(Debug, Serialize)]
pub struct BundleResponse {
    pub user_id: Uuid,
    pub device_id: Uuid,
    pub registration_id: i32,
    pub identity_key: String,
    pub signed_pre_key: SignedPreKeyOut,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub one_time_pre_key: Option<OneTimePreKeyOut>,
}

/// Identity-only public material (no OTPK consume).
#[derive(Debug, Serialize)]
pub struct IdentityResponse {
    pub user_id: Uuid,
    pub device_id: Uuid,
    pub registration_id: i32,
    pub identity_key: String,
}

#[derive(Debug, Serialize)]
pub struct SignedPreKeyOut {
    pub key_id: i32,
    pub public_key: String,
    pub signature: String,
}

#[derive(Debug, Serialize)]
pub struct OneTimePreKeyOut {
    pub key_id: i32,
    pub public_key: String,
}

#[derive(Debug, Serialize)]
pub struct KeysStatusResponse {
    pub device_id: Uuid,
    pub has_identity: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signed_pre_key_id: Option<i32>,
    pub otpk_count: i64,
}

#[derive(Debug, FromRow)]
struct IdentityRow {
    registration_id: i32,
    public_key: Vec<u8>,
}

#[derive(Debug, FromRow)]
struct SignedPreKeyRow {
    key_id: i32,
    public_key: Vec<u8>,
    signature: Vec<u8>,
}

#[derive(Debug, FromRow)]
struct OtpkRow {
    key_id: i32,
    public_key: Vec<u8>,
}

/// `PUT /keys/bundle` — upsert identity + SPK; merge OTPKs for current device.
pub async fn put_bundle(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<PutBundleRequest>,
) -> Result<StatusCode, AppError> {
    validate_registration_id(body.registration_id)?;
    validate_key_id(body.signed_pre_key.key_id)?;
    let identity = decode_public_key("identity_key", &body.identity_key)?;
    let spk_pk = decode_public_key("signed_pre_key.public_key", &body.signed_pre_key.public_key)?;
    let spk_sig = decode_signature("signed_pre_key.signature", &body.signed_pre_key.signature)?;
    let otpk = decode_otpk_batch(&body.one_time_pre_keys)?;

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let now: DateTime<Utc> = Utc::now();
    sqlx::query(
        r#"
        INSERT INTO device_identity_keys (device_id, registration_id, public_key, created_at, updated_at)
        VALUES ($1, $2, $3, $4, $4)
        ON CONFLICT (device_id) DO UPDATE SET
            registration_id = EXCLUDED.registration_id,
            public_key = EXCLUDED.public_key,
            updated_at = EXCLUDED.updated_at
        "#,
    )
    .bind(auth.device_id)
    .bind(body.registration_id)
    .bind(&identity)
    .bind(now)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("upsert identity key failed: {err}")))?;

    sqlx::query(
        r#"
        INSERT INTO device_signed_prekeys (device_id, key_id, public_key, signature, uploaded_at)
        VALUES ($1, $2, $3, $4, $5)
        ON CONFLICT (device_id) DO UPDATE SET
            key_id = EXCLUDED.key_id,
            public_key = EXCLUDED.public_key,
            signature = EXCLUDED.signature,
            uploaded_at = EXCLUDED.uploaded_at
        "#,
    )
    .bind(auth.device_id)
    .bind(body.signed_pre_key.key_id)
    .bind(&spk_pk)
    .bind(&spk_sig)
    .bind(now)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("upsert signed pre-key failed: {err}")))?;

    merge_otpks(&mut tx, auth.device_id, &otpk).await?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit key bundle failed: {err}")))?;

    tracing::info!(
        user_id = %auth.user_id,
        device_id = %auth.device_id,
        otpk_added = otpk.len(),
        "keys.bundle_put ok"
    );

    Ok(StatusCode::NO_CONTENT)
}

/// `POST /keys/otpk` — replenish one-time pre-keys for current device.
pub async fn post_otpk(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<PostOtpkRequest>,
) -> Result<StatusCode, AppError> {
    let otpk = decode_otpk_batch(&body.one_time_pre_keys)?;
    if otpk.is_empty() {
        return Err(AppError::validation(
            "one_time_pre_keys must contain at least one key.",
        ));
    }

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    merge_otpks(&mut tx, auth.device_id, &otpk).await?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit otpk failed: {err}")))?;

    tracing::info!(
        user_id = %auth.user_id,
        device_id = %auth.device_id,
        otpk_added = otpk.len(),
        "keys.otpk_replenish ok"
    );

    Ok(StatusCode::NO_CONTENT)
}

/// `GET /keys/status` — current device key publication status.
pub async fn keys_status(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<Json<KeysStatusResponse>, AppError> {
    let has_identity: bool = sqlx::query_scalar(
        r#"SELECT EXISTS(SELECT 1 FROM device_identity_keys WHERE device_id = $1)"#,
    )
    .bind(auth.device_id)
    .fetch_one(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("identity exists check failed: {err}")))?;

    let signed_pre_key_id: Option<i32> =
        sqlx::query_scalar(r#"SELECT key_id FROM device_signed_prekeys WHERE device_id = $1"#)
            .bind(auth.device_id)
            .fetch_optional(&state.pool)
            .await
            .map_err(|err| AppError::Internal(format!("signed pre-key lookup failed: {err}")))?;

    let otpk_count: i64 = sqlx::query_scalar(
        r#"SELECT COUNT(*)::bigint FROM device_one_time_prekeys WHERE device_id = $1"#,
    )
    .bind(auth.device_id)
    .fetch_one(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("otpk count failed: {err}")))?;

    Ok(Json(KeysStatusResponse {
        device_id: auth.device_id,
        has_identity,
        signed_pre_key_id,
        otpk_count,
    }))
}

/// `GET /keys/identity/:user_id` — public identity only (does **not** consume OTPKs).
///
/// Human: Used for ongoing sealed messaging after first contact; full bundle remains for session setup.
/// Agent: READS preferred device identity; never DELETE from device_one_time_prekeys.
pub async fn get_identity(
    State(state): State<AppState>,
    _auth: AuthContext,
    Path(user_id): Path<Uuid>,
) -> Result<Json<IdentityResponse>, AppError> {
    let row = sqlx::query_as::<_, IdentityDeviceRow>(
        r#"
        SELECT d.id AS device_id, ik.registration_id, ik.public_key
        FROM devices d
        INNER JOIN device_identity_keys ik ON ik.device_id = d.id
        WHERE d.user_id = $1
        ORDER BY d.last_seen_at DESC NULLS LAST, d.created_at DESC
        LIMIT 1
        "#,
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("select identity device failed: {err}")))?;

    let Some(row) = row else {
        return Err(AppError::keys_required());
    };

    tracing::debug!(
        target_user_id = %user_id,
        device_id = %row.device_id,
        "keys.identity_get ok"
    );

    Ok(Json(IdentityResponse {
        user_id,
        device_id: row.device_id,
        registration_id: row.registration_id,
        identity_key: encode_b64(&row.public_key),
    }))
}

#[derive(Debug, FromRow)]
struct IdentityDeviceRow {
    device_id: Uuid,
    registration_id: i32,
    public_key: Vec<u8>,
}

/// `GET /keys/bundle/:user_id` — fetch + optionally consume one OTPK.
pub async fn get_bundle(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(user_id): Path<Uuid>,
) -> Result<Json<BundleResponse>, AppError> {
    // Human: Unknown user and “no keys” share KEYS_REQUIRED to avoid account enumeration.
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let device_id: Option<Uuid> = sqlx::query_scalar(
        r#"
        SELECT d.id
        FROM devices d
        INNER JOIN device_identity_keys ik ON ik.device_id = d.id
        INNER JOIN device_signed_prekeys spk ON spk.device_id = d.id
        WHERE d.user_id = $1
        ORDER BY d.last_seen_at DESC NULLS LAST, d.created_at DESC
        LIMIT 1
        "#,
    )
    .bind(user_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("select key device failed: {err}")))?;

    let Some(device_id) = device_id else {
        return Err(AppError::keys_required());
    };

    let identity = sqlx::query_as::<_, IdentityRow>(
        r#"
        SELECT registration_id, public_key
        FROM device_identity_keys
        WHERE device_id = $1
        "#,
    )
    .bind(device_id)
    .fetch_one(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("load identity failed: {err}")))?;

    let spk = sqlx::query_as::<_, SignedPreKeyRow>(
        r#"
        SELECT key_id, public_key, signature
        FROM device_signed_prekeys
        WHERE device_id = $1
        "#,
    )
    .bind(device_id)
    .fetch_one(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("load signed pre-key failed: {err}")))?;

    // Atomically consume one OTPK if present.
    let otpk = sqlx::query_as::<_, OtpkRow>(
        r#"
        DELETE FROM device_one_time_prekeys
        WHERE device_id = $1
          AND key_id = (
            SELECT key_id FROM device_one_time_prekeys
            WHERE device_id = $1
            ORDER BY key_id ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
          )
        RETURNING key_id, public_key
        "#,
    )
    .bind(device_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("consume otpk failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit get bundle failed: {err}")))?;

    tracing::info!(
        requester_user_id = %auth.user_id,
        target_user_id = %user_id,
        device_id = %device_id,
        otpk_consumed = otpk.is_some(),
        "keys.bundle_get ok"
    );

    Ok(Json(BundleResponse {
        user_id,
        device_id,
        registration_id: identity.registration_id,
        identity_key: encode_b64(&identity.public_key),
        signed_pre_key: SignedPreKeyOut {
            key_id: spk.key_id,
            public_key: encode_b64(&spk.public_key),
            signature: encode_b64(&spk.signature),
        },
        one_time_pre_key: otpk.map(|row| OneTimePreKeyOut {
            key_id: row.key_id,
            public_key: encode_b64(&row.public_key),
        }),
    }))
}

struct DecodedOtpk {
    key_id: i32,
    public_key: Vec<u8>,
}

fn decode_otpk_batch(items: &[OneTimePreKeyIn]) -> Result<Vec<DecodedOtpk>, AppError> {
    if items.len() > OTPK_BATCH_MAX {
        return Err(AppError::validation(format!(
            "one_time_pre_keys may contain at most {OTPK_BATCH_MAX} items."
        )));
    }

    let mut out = Vec::with_capacity(items.len());
    let mut seen = std::collections::HashSet::new();
    for item in items {
        validate_key_id(item.key_id)?;
        if !seen.insert(item.key_id) {
            return Err(AppError::validation(
                "one_time_pre_keys contains duplicate key_id values.",
            ));
        }
        let public_key = decode_public_key("one_time_pre_keys.public_key", &item.public_key)?;
        out.push(DecodedOtpk {
            key_id: item.key_id,
            public_key,
        });
    }
    Ok(out)
}

async fn merge_otpks(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    device_id: Uuid,
    otpk: &[DecodedOtpk],
) -> Result<(), AppError> {
    if otpk.is_empty() {
        return Ok(());
    }

    let existing: i64 = sqlx::query_scalar(
        r#"SELECT COUNT(*)::bigint FROM device_one_time_prekeys WHERE device_id = $1"#,
    )
    .bind(device_id)
    .fetch_one(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("count otpk failed: {err}")))?;

    // Count how many key_ids are new (not already present).
    let mut new_count = 0_i64;
    for item in otpk {
        let exists: bool = sqlx::query_scalar(
            r#"
            SELECT EXISTS(
                SELECT 1 FROM device_one_time_prekeys
                WHERE device_id = $1 AND key_id = $2
            )
            "#,
        )
        .bind(device_id)
        .bind(item.key_id)
        .fetch_one(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("otpk exists check failed: {err}")))?;
        if !exists {
            new_count += 1;
        }
    }

    if existing + new_count > OTPK_POOL_MAX {
        return Err(AppError::prekey_pool_full());
    }

    let now: DateTime<Utc> = Utc::now();
    for item in otpk {
        sqlx::query(
            r#"
            INSERT INTO device_one_time_prekeys (device_id, key_id, public_key, created_at)
            VALUES ($1, $2, $3, $4)
            ON CONFLICT (device_id, key_id) DO UPDATE SET
                public_key = EXCLUDED.public_key
            "#,
        )
        .bind(device_id)
        .bind(item.key_id)
        .bind(&item.public_key)
        .bind(now)
        .execute(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("insert otpk failed: {err}")))?;
    }

    Ok(())
}
