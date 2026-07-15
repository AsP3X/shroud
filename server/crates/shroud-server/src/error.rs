//! Canonical API error type and JSON envelope for `/api/v1`.

use axum::{
    Json,
    http::StatusCode,
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
#[derive(Debug, Error)]
pub enum AppError {
    #[error("{0}")]
    BadRequest(String),

    #[error("{0}")]
    Unauthorized(String),

    #[error("{0}")]
    NotFound(String),

    #[error("{0}")]
    Internal(String),
}

impl AppError {
    fn status(&self) -> StatusCode {
        match self {
            Self::BadRequest(_) => StatusCode::BAD_REQUEST,
            Self::Unauthorized(_) => StatusCode::UNAUTHORIZED,
            Self::NotFound(_) => StatusCode::NOT_FOUND,
            Self::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    fn code(&self) -> &'static str {
        match self {
            Self::BadRequest(_) => "bad_request",
            Self::Unauthorized(_) => "unauthorized",
            Self::NotFound(_) => "not_found",
            Self::Internal(_) => "internal_error",
        }
    }

    /// Client-safe message derived from the error variant.
    fn client_message(&self) -> String {
        match self {
            Self::BadRequest(message) => message.clone(),
            Self::Unauthorized(message) => message.clone(),
            Self::NotFound(message) => message.clone(),
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

        if status.is_server_error() {
            tracing::error!(error = %self, "internal API error");
        }

        (status, Json(body)).into_response()
    }
}
