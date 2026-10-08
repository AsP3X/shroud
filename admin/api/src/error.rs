//! JSON errors from §3 of `docs/admin-plan.md`.

use axum::Json;
use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Serialize;

#[derive(Debug)]
pub struct ApiError {
    status: StatusCode,
    code: &'static str,
    message: &'static str,
    upstream: Option<&'static str>,
    retry_after: Option<u64>,
}

#[derive(Serialize)]
struct Body<'a> {
    code: &'a str,
    message: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    upstream: Option<&'a str>,
}

impl ApiError {
    pub fn bad_credentials() -> Self {
        Self::new(
            StatusCode::UNAUTHORIZED,
            "BAD_CREDENTIALS",
            "Those details didn't match.",
        )
    }

    pub fn unauthenticated() -> Self {
        Self::new(
            StatusCode::UNAUTHORIZED,
            "UNAUTHENTICATED",
            "Sign in to continue.",
        )
    }

    pub fn rate_limited(retry_after: u64) -> Self {
        Self {
            retry_after: Some(retry_after.max(1)),
            ..Self::new(
                StatusCode::TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Too many attempts. Try again later.",
            )
        }
    }

    pub fn link_used() -> Self {
        Self::new(
            StatusCode::GONE,
            "LINK_USED",
            "This setup link has already been used.",
        )
    }

    pub fn link_unknown() -> Self {
        Self::new(
            StatusCode::NOT_FOUND,
            "NOT_FOUND",
            "This setup link isn't valid.",
        )
    }

    pub fn not_found() -> Self {
        Self::new(StatusCode::NOT_FOUND, "NOT_FOUND", "No such admin route.")
    }

    pub fn validation(message: &'static str) -> Self {
        Self::new(StatusCode::BAD_REQUEST, "VALIDATION_ERROR", message)
    }

    pub fn csrf() -> Self {
        Self::new(
            StatusCode::FORBIDDEN,
            "CSRF",
            "This request didn't include the right security token.",
        )
    }

    pub fn upstream_postgres() -> Self {
        Self {
            upstream: Some("postgres"),
            ..Self::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM",
                "The database didn't answer within 10 seconds.",
            )
        }
    }

    pub fn internal() -> Self {
        Self::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "INTERNAL",
            "The console couldn't finish that.",
        )
    }

    fn new(status: StatusCode, code: &'static str, message: &'static str) -> Self {
        Self {
            status,
            code,
            message,
            upstream: None,
            retry_after: None,
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = Body {
            code: self.code,
            message: self.message,
            upstream: self.upstream,
        };
        let mut response = (self.status, Json(body)).into_response();
        if let Some(secs) = self.retry_after
            && let Ok(value) = HeaderValue::from_str(&secs.to_string())
        {
            response.headers_mut().insert(header::RETRY_AFTER, value);
        }
        response
    }
}
