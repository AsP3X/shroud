//! Client for the internal operator listener (§3.7).
//!
//! The listener is a second port on the API container. This process calls the host of
//! `API_INTERNAL_URL` on `OPERATOR_PORT` (8090 when unset) with
//! `Authorization: Bearer OPERATOR_TOKEN`. A 204, or a 200 whose JSON is `{"detail":"..."}`,
//! is success. 404 and 409 mean the change was already made. Any other answer, or no answer
//! within 10 seconds, failed. The token is never written to a log or a response.
//!
//! The API's Prometheus counters are read here too (`GET /operator/metrics`): the public port
//! no longer serves them.

use crate::probe::{self, ProbeError};

pub(crate) struct Done {
    pub detail: Option<String>,
}

pub(crate) enum Answer {
    Done(Done),
    Already,
    Failed,
}

pub(crate) async fn post(path: &str) -> Answer {
    let Ok(url) = endpoint(path) else {
        return Answer::Failed;
    };
    let Some(token) = bearer() else {
        return Answer::Failed;
    };
    let header = format!("Bearer {token}");
    match probe::call("POST", &url, &[("Authorization", header.as_str())], b"").await {
        Ok(fetched) if fetched.status == 204 => Answer::Done(Done { detail: None }),
        Ok(fetched) if fetched.status == 200 => Answer::Done(Done {
            detail: detail_of(&fetched.body),
        }),
        Ok(fetched) if fetched.status == 404 || fetched.status == 409 => Answer::Already,
        _ => Answer::Failed,
    }
}

/// `GET` on the operator listener: the answer as it came, or an error when the listener is
/// not configured (no `OPERATOR_TOKEN`) or does not answer.
pub(crate) async fn get(path: &str) -> Result<probe::Fetched, ProbeError> {
    let url = endpoint(path)?;
    let token = bearer().ok_or(ProbeError)?;
    let header = format!("Bearer {token}");
    probe::get_with(&url, &[("Authorization", header.as_str())]).await
}

fn endpoint(path: &str) -> Result<String, ProbeError> {
    if !path.starts_with("/operator/")
        || path
            .bytes()
            .any(|byte| byte.is_ascii_whitespace() || byte == b'?')
    {
        return Err(ProbeError);
    }
    let base = nonempty("API_INTERNAL_URL").ok_or(ProbeError)?;
    let host = probe::http_host(&base)?;
    let port = operator_port()?;
    Ok(format!("http://{host}:{port}{path}"))
}

fn operator_port() -> Result<u16, ProbeError> {
    match nonempty("OPERATOR_PORT") {
        None => Ok(8090),
        Some(value) => {
            let port: u16 = value.parse().map_err(|_| ProbeError)?;
            if port == 0 { Err(ProbeError) } else { Ok(port) }
        }
    }
}

fn bearer() -> Option<String> {
    let token = nonempty("OPERATOR_TOKEN")?;
    if token.len() > 256 || token.bytes().any(|byte| byte == b'\r' || byte == b'\n') {
        return None;
    }
    Some(token)
}

fn nonempty(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

fn detail_of(body: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(body).ok()?;
    let detail = value.get("detail")?.as_str()?.trim();
    if detail.is_empty() || detail.chars().count() > 160 || detail.chars().any(char::is_control) {
        return None;
    }
    Some(detail.to_owned())
}
