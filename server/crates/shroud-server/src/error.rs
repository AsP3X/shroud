//! Canonical API error type and JSON envelope for `/api/v1`.

use axum::{
    Json,
    http::{HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use chrono::{DateTime, Utc};
use serde::Serialize;
use thiserror::Error;
use uuid::Uuid;

/// Top-level error envelope returned to API clients.
#[derive(Debug, Serialize)]
pub struct ErrorBody {
    pub error: ErrorDetail,
    /// `DEVICE_LIMIT` only: the device a login retried with `replace_device_id` would sign out
    /// unless the user picks another; the first of `devices`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub oldest_device: Option<LimitDevice>,
    /// `DEVICE_LIMIT` only: every device of the account, least recently active first, for the
    /// user to pick a different one to sign out.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub devices: Option<Vec<LimitDevice>>,
    /// `DEVICE_LIMIT` only: the account's published identity key (Base64), for the client to
    /// check the encryption phrase before it offers to sign a device out. Absent while no device
    /// of the account has published keys.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub identity_key: Option<String>,
}

/// A device of an account whose every slot is signed in, offered for sign-out. Its name stays
/// sealed (Base64, as in `GET /devices`): the client opens it once the user's phrase checked out
/// against `identity_key`, so only someone with the password and the phrase reads it.
#[derive(Debug, Clone, Serialize)]
pub struct LimitDevice {
    pub id: Uuid,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sealed_name: Option<String>,
    pub created_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_seen_at: Option<DateTime<Utc>>,
}

/// Safe, user-facing error fields — no stack traces or internal details.
#[derive(Debug, Serialize)]
pub struct ErrorDetail {
    pub code: String,
    pub message: String,
}

/// Application-wide error type; maps to HTTP status and JSON body.
///
/// Human: `code` values match `docs/server-plan.md` (SCREAMING_SNAKE).
/// Agent: RETURNS { error: { code, message } }; never put secrets in message.
#[derive(Debug, Error)]
pub enum AppError {
    #[error("{message}")]
    Api {
        status: StatusCode,
        code: &'static str,
        message: String,
    },

    /// Every device slot is signed in; the body names the device a login may sign out instead.
    #[error(
        "This account already has the maximum number of devices (5). Remove a device and try again."
    )]
    DeviceLimit {
        /// Least recently active first; never empty.
        devices: Vec<LimitDevice>,
        identity_key: Option<String>,
    },

    /// Rate limit exceeded; `retry_after_secs` drives the `Retry-After` response header.
    #[error("Too many requests. Try again later.")]
    RateLimited { retry_after_secs: u64 },

    #[error("{0}")]
    Internal(String),
}

impl AppError {
    pub fn validation(message: impl Into<String>) -> Self {
        Self::Api {
            status: StatusCode::BAD_REQUEST,
            code: "VALIDATION_ERROR",
            message: message.into(),
        }
    }

    pub fn username_taken() -> Self {
        Self::Api {
            status: StatusCode::CONFLICT,
            code: "USERNAME_TAKEN",
            message: "That username is already taken.".into(),
        }
    }

    pub fn username_reserved() -> Self {
        Self::Api {
            status: StatusCode::BAD_REQUEST,
            code: "USERNAME_RESERVED",
            message: "That username is not available.".into(),
        }
    }

    pub fn password_too_short() -> Self {
        Self::Api {
            status: StatusCode::BAD_REQUEST,
            code: "PASSWORD_TOO_SHORT",
            message: "Password must be at least 8 characters.".into(),
        }
    }

    pub fn password_too_common() -> Self {
        Self::Api {
            status: StatusCode::BAD_REQUEST,
            code: "PASSWORD_TOO_COMMON",
            message: "Password is too common. Choose a stronger password.".into(),
        }
    }

    pub fn invalid_credentials() -> Self {
        Self::Api {
            status: StatusCode::UNAUTHORIZED,
            code: "INVALID_CREDENTIALS",
            message: "Invalid username or password.".into(),
        }
    }

    pub fn device_limit(devices: Vec<LimitDevice>, identity_key: Option<String>) -> Self {
        Self::DeviceLimit {
            devices,
            identity_key,
        }
    }

    pub fn unauthorized() -> Self {
        Self::Api {
            status: StatusCode::UNAUTHORIZED,
            code: "UNAUTHORIZED",
            message: "Authentication required.".into(),
        }
    }

    /// The token belonged to a device the account removed. Clients take it as final and wipe
    /// everything of the account at once, where a plain 401 only counts toward a sign-out.
    pub fn device_removed() -> Self {
        Self::Api {
            status: StatusCode::UNAUTHORIZED,
            code: "DEVICE_REMOVED",
            message: "This device was removed from your account.".into(),
        }
    }

    pub fn forbidden(message: impl Into<String>) -> Self {
        Self::Api {
            status: StatusCode::FORBIDDEN,
            code: "FORBIDDEN",
            message: message.into(),
        }
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self::Api {
            status: StatusCode::NOT_FOUND,
            code: "NOT_FOUND",
            message: message.into(),
        }
    }

    /// Rate limited with a one-minute `Retry-After` hint (default budgets).
    pub fn rate_limited() -> Self {
        Self::rate_limited_after(60)
    }

    /// Rate limited with a scope-specific `Retry-After` (seconds, minimum 1).
    pub fn rate_limited_after(retry_after_secs: u64) -> Self {
        Self::RateLimited {
            retry_after_secs: retry_after_secs.max(1),
        }
    }

    pub fn keys_required() -> Self {
        Self::Api {
            status: StatusCode::NOT_FOUND,
            code: "KEYS_REQUIRED",
            message: "No pre-key bundle is available for this user.".into(),
        }
    }

    pub fn prekey_pool_full() -> Self {
        Self::Api {
            status: StatusCode::CONFLICT,
            code: "PREKEY_POOL_FULL",
            message: "One-time pre-key pool is full for this device (max 200).".into(),
        }
    }

    pub fn already_exists(message: impl Into<String>) -> Self {
        Self::Api {
            status: StatusCode::CONFLICT,
            code: "ALREADY_EXISTS",
            message: message.into(),
        }
    }

    pub fn call_busy() -> Self {
        Self::Api {
            status: StatusCode::CONFLICT,
            code: "CALL_BUSY",
            message: "The other party is busy on another call.".into(),
        }
    }

    pub fn conflict(code: &'static str, message: impl Into<String>) -> Self {
        Self::Api {
            status: StatusCode::CONFLICT,
            code,
            message: message.into(),
        }
    }

    /// The media store can't be reached right now; the upload or download can be retried.
    pub fn media_unavailable() -> Self {
        Self::Api {
            status: StatusCode::SERVICE_UNAVAILABLE,
            code: "MEDIA_UNAVAILABLE",
            message: "Media storage is unavailable. Try again shortly.".into(),
        }
    }

    /// Legacy-friendly constructors used by existing routes/tests.
    /// Wrong PIN against a PIN guard; the message carries the attempts left.
    pub fn pin_incorrect(attempts_left: i32) -> Self {
        Self::Api {
            status: StatusCode::FORBIDDEN,
            code: "PIN_INCORRECT",
            message: format!("Wrong PIN. {attempts_left} attempts left."),
        }
    }

    /// The PIN guard is gone (too many wrong PINs, or never created): only the phrase unlocks.
    pub fn pin_guard_gone() -> Self {
        Self::Api {
            status: StatusCode::GONE,
            code: "PIN_GUARD_GONE",
            message: "This PIN no longer unlocks Shroud here. Use your encryption phrase.".into(),
        }
    }

    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::validation(message)
    }

    /// The client-safe `{ error: { code, message } }` envelope, for channels without an HTTP
    /// status of their own (WebSocket frames).
    ///
    /// Agent: RETURNS the same code/message an HTTP response would carry; internal details
    /// stay out (`Internal` becomes the generic message).
    pub fn body(&self) -> ErrorBody {
        ErrorBody {
            error: ErrorDetail {
                code: self.code().into(),
                message: self.client_message(),
            },
            oldest_device: match self {
                Self::DeviceLimit { devices, .. } => devices.first().cloned(),
                _ => None,
            },
            devices: match self {
                Self::DeviceLimit { devices, .. } => Some(devices.clone()),
                _ => None,
            },
            identity_key: match self {
                Self::DeviceLimit { identity_key, .. } => identity_key.clone(),
                _ => None,
            },
        }
    }

    fn status(&self) -> StatusCode {
        match self {
            Self::Api { status, .. } => *status,
            Self::DeviceLimit { .. } => StatusCode::CONFLICT,
            Self::RateLimited { .. } => StatusCode::TOO_MANY_REQUESTS,
            Self::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    pub(crate) fn code(&self) -> &'static str {
        match self {
            Self::Api { code, .. } => code,
            Self::DeviceLimit { .. } => "DEVICE_LIMIT",
            Self::RateLimited { .. } => "RATE_LIMITED",
            Self::Internal(_) => "INTERNAL_ERROR",
        }
    }

    /// Client-safe message derived from the error variant.
    fn client_message(&self) -> String {
        match self {
            Self::Api { message, .. } => message.clone(),
            Self::DeviceLimit { .. } => self.to_string(),
            Self::RateLimited { .. } => "Too many requests. Try again later.".into(),
            // Human: Internal errors get a generic message; details stay in server logs only.
            Self::Internal(_) => "An unexpected error occurred.".into(),
        }
    }

    fn retry_after_secs(&self) -> Option<u64> {
        match self {
            Self::RateLimited { retry_after_secs } => Some(*retry_after_secs),
            _ => None,
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let status = self.status();
        let retry_after = self.retry_after_secs();
        let body = self.body();

        let code = self.code();
        if status.is_server_error() {
            tracing::error!(
                status = %status,
                code,
                error = %self,
                "internal API error"
            );
        } else if status.is_client_error() {
            // Human: 4xx are expected (auth, validation); keep info-level so local stacks stay readable.
            tracing::info!(
                status = %status,
                code,
                message = %self.client_message(),
                "client API error"
            );
        }

        let mut response = (status, Json(body)).into_response();
        if let Some(secs) = retry_after {
            // Human: Scope-aware backoff (e.g. contact requests use a 1h window).
            // Agent: WRITES Retry-After header from RateLimited.retry_after_secs.
            if let Ok(value) = HeaderValue::from_str(&secs.to_string()) {
                response.headers_mut().insert(header::RETRY_AFTER, value);
            }
        }
        response
    }
}
