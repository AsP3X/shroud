//! Tells an app whether a newer release is out (see [`crate::client_version`]).
//!
//! Human: Open to signed-out callers, so the sign-in and lock screens can prompt too. The answer
//! only repeats what the operator published; this route keeps nothing about the caller.
//! Agent: READS AppState.client_versions (and the web build file it names); no database access.

use axum::{
    Json,
    extract::{Query, State},
    http::header,
    response::IntoResponse,
};
use serde::Deserialize;

use crate::client_version::Version;
use crate::error::AppError;
use crate::state::AppState;

/// Longest `version` accepted; real ones are a few characters.
const MAX_VERSION_LEN: usize = 64;

#[derive(Debug, Deserialize)]
pub struct ClientVersionQuery {
    /// `ios`, `android` or `web`.
    pub platform: Option<String>,
    /// The app's version (`1.2.0`), or the web bundle's build id.
    pub version: Option<String>,
}

/// `GET /client-version?platform=ios&version=1.2.0`
pub async fn get_client_version(
    State(state): State<AppState>,
    Query(query): Query<ClientVersionQuery>,
) -> Result<impl IntoResponse, AppError> {
    let version = query.version.as_deref().map(str::trim).unwrap_or_default();
    if version.is_empty() || version.len() > MAX_VERSION_LEN {
        return Err(AppError::validation("Invalid version."));
    }
    let versions = &state.client_versions;
    let check = match query.platform.as_deref() {
        Some("ios") => release_check(&versions.ios, version)?,
        Some("android") => release_check(&versions.android, version)?,
        Some("web") => versions.check_web(version).await,
        _ => return Err(AppError::validation("Unknown platform.")),
    };
    // Human: A cached answer could hide a release, or keep blocking after the minimum dropped.
    Ok(([(header::CACHE_CONTROL, "no-store")], Json(check)))
}

fn release_check(
    release: &crate::client_version::AppRelease,
    version: &str,
) -> Result<crate::client_version::ClientVersionCheck, AppError> {
    let version =
        Version::parse_client(version).ok_or_else(|| AppError::validation("Invalid version."))?;
    Ok(release.check(&version))
}
