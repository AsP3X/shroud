//! APNs device token registration.

use axum::{Json, extract::State, http::StatusCode};
use serde::Deserialize;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::state::AppState;

#[derive(Debug, Deserialize)]
pub struct PutPushTokenRequest {
    pub token: String,
    pub environment: String,
}

/// `PUT /push/token` — bind APNs token to current device.
pub async fn put_token(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<PutPushTokenRequest>,
) -> Result<StatusCode, AppError> {
    let token = body.token.trim();
    if token.is_empty() || token.len() > 200 {
        return Err(AppError::validation("Invalid APNs token."));
    }
    // Hex or opaque string — accept common lengths without being overly strict.
    let environment = body.environment.trim().to_ascii_lowercase();
    if environment != "sandbox" && environment != "production" {
        return Err(AppError::validation(
            "environment must be 'sandbox' or 'production'.",
        ));
    }

    sqlx::query(
        r#"
        INSERT INTO push_tokens (device_id, apns_token, environment, updated_at)
        VALUES ($1, $2, $3, now())
        ON CONFLICT (device_id) DO UPDATE SET
            apns_token = EXCLUDED.apns_token,
            environment = EXCLUDED.environment,
            updated_at = now()
        "#,
    )
    .bind(auth.device_id)
    .bind(token)
    .bind(&environment)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("upsert push token failed: {err}")))?;

    Ok(StatusCode::NO_CONTENT)
}
