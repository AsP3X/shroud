//! Push registration: APNs tokens (alerts and PushKit VoIP), Web Push subscriptions, and a
//! test notification.
//!
//! Human: One device, one relay: an iPhone registers APNs tokens, a browser a Web Push
//! subscription. A token or endpoint that moves to another device (a reinstall, a second
//! account in the same browser) is taken away from the device that had it, so one person's
//! notifications never ring on someone else's screen.
//! Agent: DB push_tokens (PK device_id + kind), web_push_subscriptions; CALLS PushService.

use axum::{
    Json,
    extract::{Query, State},
    http::StatusCode,
};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use serde::{Deserialize, Serialize};

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::push::TestPushOutcome;
use crate::push::web_push::parse_subscription_keys;
use crate::rate_limit::budgets;
use crate::state::AppState;

#[derive(Debug, Deserialize)]
pub struct PutPushTokenRequest {
    pub token: String,
    pub environment: String,
    /// `alert` (default) or `voip`. Older builds sent VoIP tokens with a `voip:` prefix.
    pub kind: Option<String>,
    /// Base64 of 32 random bytes (alerts only): the notification extension opens the sender's
    /// name with it, so Apple never reads the name.
    pub payload_key: Option<String>,
}

/// `PUT /push/token` — bind an APNs token to this device.
pub async fn put_token(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<PutPushTokenRequest>,
) -> Result<StatusCode, AppError> {
    let mut token = body.token.trim();
    let mut kind = body
        .kind
        .as_deref()
        .unwrap_or("alert")
        .trim()
        .to_ascii_lowercase();
    if let Some(rest) = token.strip_prefix("voip:") {
        token = rest;
        kind = "voip".into();
    }
    if kind != "alert" && kind != "voip" {
        return Err(AppError::validation("kind must be 'alert' or 'voip'."));
    }
    if token.is_empty() || token.len() > 200 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(AppError::validation("Invalid APNs token."));
    }
    let token = token.to_ascii_lowercase();
    let token = token.as_str();
    let environment = body.environment.trim().to_ascii_lowercase();
    if environment != "sandbox" && environment != "production" {
        return Err(AppError::validation(
            "environment must be 'sandbox' or 'production'.",
        ));
    }
    let payload_key = match (&body.payload_key, kind.as_str()) {
        (None, _) => None,
        (Some(key), "alert") => {
            let bytes = BASE64
                .decode(key.trim())
                .map_err(|_| AppError::validation("payload_key must be base64."))?;
            if bytes.len() != 32 {
                return Err(AppError::validation("payload_key must be 32 bytes."));
            }
            Some(bytes)
        }
        (Some(_), _) => {
            return Err(AppError::validation(
                "payload_key belongs to the alert token.",
            ));
        }
    };

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;
    // The same token on another device means the phone changed hands (or accounts).
    sqlx::query(
        r#"DELETE FROM push_tokens WHERE apns_token = $1 AND kind = $2 AND device_id <> $3"#,
    )
    .bind(token)
    .bind(&kind)
    .bind(auth.device_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("release push token failed: {err}")))?;
    sqlx::query(
        r#"
        INSERT INTO push_tokens (device_id, kind, apns_token, environment, payload_key, updated_at)
        VALUES ($1, $2, $3, $4, $5, now())
        ON CONFLICT (device_id, kind) DO UPDATE SET
            apns_token = EXCLUDED.apns_token,
            environment = EXCLUDED.environment,
            payload_key = COALESCE(EXCLUDED.payload_key, push_tokens.payload_key),
            updated_at = now()
        "#,
    )
    .bind(auth.device_id)
    .bind(&kind)
    .bind(token)
    .bind(&environment)
    .bind(payload_key)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("upsert push token failed: {err}")))?;
    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit push token failed: {err}")))?;

    tracing::info!(device_id = %auth.device_id, %kind, %environment, "push.token registered");
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize)]
pub struct DeletePushTokenQuery {
    /// `alert` or `voip`; omitted removes both.
    pub kind: Option<String>,
}

/// `DELETE /push/token` — stop APNs pushes to this device.
pub async fn delete_token(
    State(state): State<AppState>,
    auth: AuthContext,
    Query(query): Query<DeletePushTokenQuery>,
) -> Result<StatusCode, AppError> {
    let kind = query.kind.as_deref().map(str::to_ascii_lowercase);
    if let Some(kind) = kind.as_deref()
        && kind != "alert"
        && kind != "voip"
    {
        return Err(AppError::validation("kind must be 'alert' or 'voip'."));
    }
    sqlx::query(
        r#"DELETE FROM push_tokens WHERE device_id = $1 AND ($2::text IS NULL OR kind = $2)"#,
    )
    .bind(auth.device_id)
    .bind(kind)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("delete push token failed: {err}")))?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Serialize)]
pub struct WebPushKeyResponse {
    /// The VAPID public key (base64url, uncompressed P-256): `applicationServerKey`.
    pub public_key: String,
}

/// `GET /push/web/key` — what a browser subscribes with.
pub async fn web_key(
    State(state): State<AppState>,
    _auth: AuthContext,
) -> Result<Json<WebPushKeyResponse>, AppError> {
    let public_key = state
        .push
        .web_public_key()
        .ok_or_else(|| AppError::not_found("Web Push is not available on this server."))?;
    Ok(Json(WebPushKeyResponse { public_key }))
}

/// The browser's `PushSubscription.toJSON()`.
#[derive(Debug, Deserialize)]
pub struct WebSubscriptionRequest {
    pub endpoint: String,
    pub keys: WebSubscriptionKeys,
}

#[derive(Debug, Deserialize)]
pub struct WebSubscriptionKeys {
    pub p256dh: String,
    pub auth: String,
}

/// `PUT /push/web/subscription` — this browser's push subscription.
pub async fn put_web_subscription(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<WebSubscriptionRequest>,
) -> Result<StatusCode, AppError> {
    if state.push.web_public_key().is_none() {
        return Err(AppError::not_found(
            "Web Push is not available on this server.",
        ));
    }
    // Stored as parsed: the form the HTTP client will contact.
    let endpoint = state
        .push
        .allowed_web_endpoint(body.endpoint.trim())
        .ok_or_else(|| {
            AppError::validation("endpoint must be an https URL of a browser push service.")
        })?;
    let endpoint = endpoint.as_str();
    let (p256dh, auth_secret) = parse_subscription_keys(&body.keys.p256dh, &body.keys.auth)
        .ok_or_else(|| AppError::validation("keys must be a P-256 point and a 16-byte secret."))?;

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;
    sqlx::query(r#"DELETE FROM web_push_subscriptions WHERE endpoint = $1 AND device_id <> $2"#)
        .bind(endpoint)
        .bind(auth.device_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("release web push endpoint failed: {err}")))?;
    sqlx::query(
        r#"
        INSERT INTO web_push_subscriptions (device_id, endpoint, p256dh, auth, updated_at)
        VALUES ($1, $2, $3, $4, now())
        ON CONFLICT (device_id) DO UPDATE SET
            endpoint = EXCLUDED.endpoint,
            p256dh = EXCLUDED.p256dh,
            auth = EXCLUDED.auth,
            updated_at = now()
        "#,
    )
    .bind(auth.device_id)
    .bind(endpoint)
    .bind(p256dh)
    .bind(auth_secret)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("save web push subscription failed: {err}")))?;
    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit web push subscription failed: {err}")))?;

    tracing::info!(device_id = %auth.device_id, "push.web subscribed");
    Ok(StatusCode::NO_CONTENT)
}

/// `DELETE /push/web/subscription` — stop Web Push to this browser.
pub async fn delete_web_subscription(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<StatusCode, AppError> {
    sqlx::query(r#"DELETE FROM web_push_subscriptions WHERE device_id = $1"#)
        .bind(auth.device_id)
        .execute(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("delete web push subscription failed: {err}")))?;
    Ok(StatusCode::NO_CONTENT)
}

/// `POST /push/test` — a notification to this device through its relay, even while the app
/// is open, so the settings screen can prove the whole path works.
pub async fn test_push(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<Json<TestPushOutcome>, AppError> {
    state
        .rate_limiter
        .check_budget(
            "push_test_device",
            &auth.device_id.to_string(),
            budgets::PUSH_TEST_DEVICE,
        )
        .await?;
    let outcome = state.push.send_test(auth.user_id, auth.device_id).await;
    tracing::info!(
        device_id = %auth.device_id,
        channel = ?outcome.channel,
        status = outcome.status,
        "push.test"
    );
    Ok(Json(outcome))
}
