//! Canonical API error type and JSON envelope for `/api/v1`.

use axum::{
    Json,
    http::{HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use serde::Serialize;
use thiserror::Error;

/// Top-level error envelope returned to API clients.
#[derive(Debug, Serialize)]
pub struct ErrorBody {
    pub error: ErrorDetail,
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

    pub fn device_limit() -> Self {
        Self::Api {
            status: StatusCode::CONFLICT,
            code: "DEVICE_LIMIT",
            message: "This account already has the maximum number of devices (5). Remove a device and try again.".into(),
        }
    }

    pub fn unauthorized() -> Self {
        Self::Api {
            status: StatusCode::UNAUTHORIZED,
            code: "UNAUTHORIZED",
            message: "Authentication required.".into(),
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

    pub fn rate_limited() -> Self {
        Self::Api {
            status: StatusCode::TOO_MANY_REQUESTS,
            code: "RATE_LIMITED",
            message: "Too many requests. Try again later.".into(),
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

    /// Legacy-friendly constructors used by existing routes/tests.
    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::validation(message)
    }

    fn status(&self) -> StatusCode {
        match self {
            Self::Api { status, .. } => *status,
            Self::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    fn code(&self) -> &'static str {
        match self {
            Self::Api { code, .. } => code,
            Self::Internal(_) => "INTERNAL_ERROR",
        }
    }

    /// Client-safe message derived from the error variant.
    fn client_message(&self) -> String {
        match self {
            Self::Api { message, .. } => message.clone(),
            // Human: Internal errors get a generic message; details stay in server logs only.
            Self::Internal(_) => "An unexpected error occurred.".into(),
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let status = self.status();
        let body = ErrorBody {
            error: ErrorDetail {
                code: self.code().into(),
                message: self.client_message(),
            },
        };

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
        if code == "RATE_LIMITED" {
            // Hint clients to back off for one fixed-window period (budgets are per-minute).
            response
                .headers_mut()
                .insert(header::RETRY_AFTER, HeaderValue::from_static("60"));
        }
        response
    }
}
