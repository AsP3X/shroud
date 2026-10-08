//! Which app versions are current, and which are too old to keep working.
//!
//! Human: The operator publishes a release (TestFlight, an APK, a web deploy) and tells the
//! server about it; every client asks `GET /client-version` at start and when it comes back to
//! the foreground, and prompts its user to update. Below the latest version the prompt can be
//! dismissed; below the minimum the app blocks until it is updated.
//! The web client has no version numbers: `./deploy.sh` stamps one build id into the bundle and,
//! once the new web container is up, into a file the API reads on each question
//! (`WEB_BUILD_FILE`), so a web-only deploy never restarts the API. A tab whose id differs is
//! offered a reload. Reloading always fetches the deployed bundle, so the web has no minimum.
//!
//! The minimum is also enforced here, not only on the apps' word: every request names its app
//! in `X-Shroud-Client` (see [`require_supported_client`]). Once a minimum is set, a request from
//! an older app, or from a build so old that it sends no header, gets `426 UPDATE_REQUIRED`.
//! Agent: READS IOS_LATEST_VERSION, IOS_MIN_VERSION, IOS_UPDATE_URL, ANDROID_LATEST_VERSION,
//! ANDROID_MIN_VERSION, ANDROID_UPDATE_URL, WEB_BUILD_FILE (or a fixed WEB_BUILD). Nothing set =
//! every client is current.

use std::cmp::Ordering;
use std::collections::HashMap;
use std::path::PathBuf;

use axum::{
    extract::{Query, Request, State},
    http::{HeaderMap, HeaderName, Uri},
    middleware::Next,
    response::{IntoResponse, Response},
};
use serde::Serialize;

use crate::error::AppError;
use crate::state::AppState;

/// Names the app on every request: `ios/1.2.0`, `android/0.3.0`, `web/<build id>`.
pub static CLIENT_HEADER: HeaderName = HeaderName::from_static("x-shroud-client");
/// The same value as a query parameter, for WebSocket upgrades: a browser can't add headers there.
pub const CLIENT_QUERY: &str = "client";
/// Routes any build may call: health checks, and the version check that tells an app to update.
const OPEN_PATHS: [&str; 5] = [
    "/api/v1/health",
    "/api/v1/health/live",
    "/api/v1/health/ready",
    "/api/v1/metrics",
    "/api/v1/client-version",
];

/// Release components a version may have (`1`, `1.2`, `1.2.3`, `1.2.3.4`).
const MAX_VERSION_PARTS: usize = 4;
/// Longest web build id accepted; deploy.sh writes 12 hex characters.
const MAX_WEB_BUILD_LEN: usize = 64;

/// Every platform's release settings (`GET /client-version`).
#[derive(Debug, Clone, Default)]
pub struct ClientVersions {
    pub ios: AppRelease,
    pub android: AppRelease,
    /// Where the deployed web bundle's build id comes from.
    pub web_build: WebBuild,
}

/// The deployed web bundle's build id.
#[derive(Debug, Clone, Default)]
pub enum WebBuild {
    /// Tabs are never told to reload.
    #[default]
    Unknown,
    /// `WEB_BUILD`: one id for the life of the process (tests, hand-run setups).
    Fixed(String),
    /// `WEB_BUILD_FILE`: re-read on every question, so a deploy can change it under a running API.
    File(PathBuf),
}

/// One app platform's release settings.
#[derive(Debug, Clone, Default)]
pub struct AppRelease {
    /// Older versions are offered an update they may dismiss.
    pub latest: Option<Version>,
    /// Older versions are blocked until they update.
    pub minimum: Option<Version>,
    /// Where the update prompt sends people (App Store, TestFlight, an APK page).
    pub update_url: Option<String>,
}

/// What a client should do about its version.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum UpdateStatus {
    Current,
    UpdateAvailable,
    UpdateRequired,
}

/// The answer for one client.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ClientVersionCheck {
    pub status: UpdateStatus,
    /// The version (or web build id) to update to; clients remember a dismissal by it.
    pub latest_version: Option<String>,
    pub update_url: Option<String>,
    /// This server's release, for the apps' About page.
    pub server_version: &'static str,
}

/// This server's release (the crate version).
pub const SERVER_VERSION: &str = env!("CARGO_PKG_VERSION");

/// A dotted release number. `1.2` and `1.2.0` are equal.
#[derive(Debug, Clone)]
pub struct Version {
    parts: Vec<u32>,
    text: String,
}

impl Version {
    /// Parses `1.2.3` exactly: what an operator writes into the configuration.
    pub fn parse_strict(raw: &str) -> Option<Self> {
        let text = raw.trim();
        if text.is_empty() {
            return None;
        }
        let parts = text
            .split('.')
            .map(|part| {
                if part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit()) {
                    None
                } else {
                    part.parse::<u32>().ok()
                }
            })
            .collect::<Option<Vec<_>>>()?;
        if parts.len() > MAX_VERSION_PARTS {
            return None;
        }
        Some(Self {
            parts,
            text: text.to_string(),
        })
    }

    /// Parses the release number at the start of what an app reports, so a prefix or suffix
    /// (`v1.2.0`, `1.2.0-beta`, `1.2.0 (14)`) or extra parts (`1.2.3.4.5`) don't stop it from
    /// being checked: an app that can't be checked can't be told it must update.
    pub fn parse_client(raw: &str) -> Option<Self> {
        let text = raw.trim();
        let text = text.strip_prefix(['v', 'V']).unwrap_or(text);
        let end = text
            .find(|c: char| !(c.is_ascii_digit() || c == '.'))
            .unwrap_or(text.len());
        let release = text[..end]
            .split('.')
            .take(MAX_VERSION_PARTS)
            .collect::<Vec<_>>()
            .join(".");
        Self::parse_strict(release.trim_end_matches('.'))
    }

    pub fn as_str(&self) -> &str {
        &self.text
    }
}

impl Ord for Version {
    fn cmp(&self, other: &Self) -> Ordering {
        let len = self.parts.len().max(other.parts.len());
        (0..len)
            .map(|i| {
                let a = self.parts.get(i).copied().unwrap_or(0);
                let b = other.parts.get(i).copied().unwrap_or(0);
                a.cmp(&b)
            })
            .find(|ordering| ordering.is_ne())
            .unwrap_or(Ordering::Equal)
    }
}

impl PartialEq for Version {
    fn eq(&self, other: &Self) -> bool {
        self.cmp(other).is_eq()
    }
}

impl Eq for Version {}

impl PartialOrd for Version {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl AppRelease {
    /// The answer for an app at `version`.
    pub fn check(&self, version: &Version) -> ClientVersionCheck {
        let status = if self.minimum.as_ref().is_some_and(|min| version < min) {
            UpdateStatus::UpdateRequired
        } else if self.latest.as_ref().is_some_and(|latest| version < latest) {
            UpdateStatus::UpdateAvailable
        } else {
            UpdateStatus::Current
        };
        // Human: With only a minimum set, a blocked app is told to get at least that one.
        let target = self.latest.as_ref().or(self.minimum.as_ref());
        ClientVersionCheck {
            status,
            latest_version: target.map(|v| v.as_str().to_string()),
            update_url: self.update_url.clone(),
            server_version: SERVER_VERSION,
        }
    }
}

impl ClientVersions {
    /// The answer for a web tab built as `build`.
    pub async fn check_web(&self, build: &str) -> ClientVersionCheck {
        let deployed = self.web_build.current().await;
        let status = match &deployed {
            Some(deployed) if deployed != build => UpdateStatus::UpdateAvailable,
            _ => UpdateStatus::Current,
        };
        ClientVersionCheck {
            status,
            latest_version: deployed,
            update_url: None,
            server_version: SERVER_VERSION,
        }
    }

    /// Whether a request from the app named by `client` (an `X-Shroud-Client` value) is served.
    ///
    /// Human: With no minimum set, everything is. Once one is, an app must name itself: every build
    /// that sends the header is newer than the ones a minimum is there to stop, so a request
    /// without it (or with one we can't read) is refused. Web tabs pass on any build id, since a
    /// reload always fetches the deployed bundle; only a tab too old to send the header is stopped.
    pub fn admits(&self, client: Option<&str>) -> bool {
        if self.ios.minimum.is_none() && self.android.minimum.is_none() {
            return true;
        }
        let Some((platform, version)) = client.and_then(|raw| raw.trim().split_once('/')) else {
            return false;
        };
        let below_minimum = |release: &AppRelease| {
            Version::parse_client(version)
                .is_none_or(|version| release.minimum.as_ref().is_some_and(|min| &version < min))
        };
        match platform {
            "ios" => !below_minimum(&self.ios),
            "android" => !below_minimum(&self.android),
            "web" => valid_web_build(version).is_some(),
            _ => false,
        }
    }
}

/// Refuses requests from apps below the operator's minimum with `426 UPDATE_REQUIRED`
/// ([`ClientVersions::admits`]); health checks and `GET /client-version` stay open to every build.
pub async fn require_supported_client(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> Response {
    if OPEN_PATHS.contains(&request.uri().path()) {
        return next.run(request).await;
    }
    let client = request_client(request.headers(), request.uri());
    if state.client_versions.admits(client.as_deref()) {
        return next.run(request).await;
    }
    // Human: A token that no longer authenticates gets its 401 first, as it would have without
    // the gate: an old build learns that its device was removed (and wipes itself) or that it was
    // signed out. Only a session that still works is told to update. The lookup runs on refusals
    // only; if it fails, the 426 stands.
    let bearer = request
        .headers()
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "));
    if let Some(token) = bearer
        && let Err(rejection) = crate::auth::session::ids_for_token(&state.pool, token).await
        && rejection.status() == axum::http::StatusCode::UNAUTHORIZED
    {
        return rejection.into_response();
    }
    AppError::update_required().into_response()
}

/// The app a request names: `X-Shroud-Client`, else the `client` query parameter.
pub fn request_client(headers: &HeaderMap, uri: &Uri) -> Option<String> {
    headers
        .get(&CLIENT_HEADER)
        .and_then(|value| value.to_str().ok())
        .map(str::to_string)
        .or_else(|| client_from_query(uri))
}

/// The `client` query parameter, percent-decoded (`web%2Fabc` → `web/abc`).
fn client_from_query(uri: &Uri) -> Option<String> {
    let Query(mut params) = Query::<HashMap<String, String>>::try_from_uri(uri).ok()?;
    params.remove(CLIENT_QUERY)
}

impl WebBuild {
    /// The id now deployed. A missing file (no deploy has written it yet) or a bad one is
    /// unknown: no tab is told to reload on a guess.
    pub async fn current(&self) -> Option<String> {
        match self {
            Self::Unknown => None,
            Self::Fixed(build) => Some(build.clone()),
            Self::File(path) => {
                let raw = match tokio::fs::read_to_string(path).await {
                    Ok(raw) => raw,
                    Err(err) if err.kind() == std::io::ErrorKind::NotFound => return None,
                    Err(err) => {
                        tracing::warn!(error = %err, path = %path.display(), "cannot read WEB_BUILD_FILE");
                        return None;
                    }
                };
                let build = valid_web_build(&raw);
                if build.is_none() && !raw.trim().is_empty() {
                    tracing::warn!(path = %path.display(), "WEB_BUILD_FILE holds no valid build id");
                }
                build
            }
        }
    }
}

/// Release settings, read through `lookup` (the process environment outside tests). A bad
/// value stops startup, so apps are never told about a release the operator didn't mean.
pub fn client_versions(
    lookup: &dyn Fn(&str) -> Option<String>,
) -> Result<ClientVersions, AppError> {
    Ok(ClientVersions {
        ios: app_release(lookup, "IOS")?,
        android: app_release(lookup, "ANDROID")?,
        web_build: web_build(
            non_empty(lookup("WEB_BUILD")),
            non_empty(lookup("WEB_BUILD_FILE")),
        )?,
    })
}

fn app_release(
    lookup: &dyn Fn(&str) -> Option<String>,
    platform: &str,
) -> Result<AppRelease, AppError> {
    let version = |suffix: &str| -> Result<Option<Version>, AppError> {
        let name = format!("{platform}_{suffix}");
        match non_empty(lookup(&name)) {
            None => Ok(None),
            Some(raw) => Version::parse_strict(&raw).map(Some).ok_or_else(|| {
                AppError::Internal(format!(
                    "{name} must be a version like 1.2.3 (up to {MAX_VERSION_PARTS} numbers \
                     below 4294967296, separated by dots), got {raw:?}"
                ))
            }),
        }
    };
    let latest = version("LATEST_VERSION")?;
    let minimum = version("MIN_VERSION")?;
    if let (Some(latest), Some(minimum)) = (&latest, &minimum)
        && minimum > latest
    {
        return Err(AppError::Internal(format!(
            "{platform}_MIN_VERSION ({}) is newer than {platform}_LATEST_VERSION ({})",
            minimum.as_str(),
            latest.as_str()
        )));
    }

    // Human: The iPhone app opens only https links, so an http one would be a dead button.
    let url_name = format!("{platform}_UPDATE_URL");
    let update_url = non_empty(lookup(&url_name));
    let http_allowed = platform != "IOS";
    if let Some(url) = &update_url
        && !(url.starts_with("https://") || (http_allowed && url.starts_with("http://")))
    {
        let wanted = if http_allowed {
            "an http(s)"
        } else {
            "an https"
        };
        return Err(AppError::Internal(format!(
            "{url_name} must be {wanted} link, got {url:?}"
        )));
    }

    Ok(AppRelease {
        latest,
        minimum,
        update_url,
    })
}

fn web_build(fixed: Option<String>, file: Option<String>) -> Result<WebBuild, AppError> {
    match (fixed, file) {
        (Some(_), Some(_)) => Err(AppError::Internal(
            "set WEB_BUILD or WEB_BUILD_FILE, not both".into(),
        )),
        (Some(build), None) => valid_web_build(&build).map(WebBuild::Fixed).ok_or_else(|| {
            AppError::Internal(format!(
                "WEB_BUILD must be up to {MAX_WEB_BUILD_LEN} letters, digits, '-', '_' or '.', got {build:?}"
            ))
        }),
        (None, Some(path)) => Ok(WebBuild::File(PathBuf::from(path))),
        (None, None) => Ok(WebBuild::Unknown),
    }
}

/// A build id as deploy.sh writes it, or `None`.
fn valid_web_build(raw: &str) -> Option<String> {
    let build = raw.trim();
    let valid = !build.is_empty()
        && build.len() <= MAX_WEB_BUILD_LEN
        && build
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'));
    valid.then(|| build.to_string())
}

fn non_empty(raw: Option<String>) -> Option<String> {
    raw.map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn v(raw: &str) -> Version {
        Version::parse_strict(raw).unwrap()
    }

    fn release(latest: Option<&str>, minimum: Option<&str>) -> AppRelease {
        AppRelease {
            latest: latest.map(v),
            minimum: minimum.map(v),
            update_url: Some("https://example.com/app".into()),
        }
    }

    fn from(vars: &[(&str, &str)]) -> Result<ClientVersions, AppError> {
        let map: HashMap<String, String> = vars
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        client_versions(&|name| map.get(name).cloned())
    }

    #[test]
    fn versions_compare_numerically_and_ignore_trailing_zeros() {
        assert!(v("1.10") > v("1.9"));
        assert!(v("2") > v("1.99.99"));
        assert_eq!(v("1.2").cmp(&v("1.2.0")), Ordering::Equal);
        assert!(v("1.2.1") > v("1.2"));
    }

    #[test]
    fn strict_parse_refuses_anything_but_numbers_and_dots() {
        for bad in [
            "",
            "1.",
            ".1",
            "1..2",
            "1.2.3.4.5",
            "v1.2",
            "1.2-beta",
            "1.-2",
            "+1",
        ] {
            assert!(Version::parse_strict(bad).is_none(), "{bad:?}");
        }
    }

    #[test]
    fn client_parse_keeps_the_leading_release_number() {
        assert_eq!(Version::parse_client("1.2.0-beta").unwrap(), v("1.2.0"));
        assert_eq!(Version::parse_client("1.2.0 (14)").unwrap(), v("1.2.0"));
        assert_eq!(Version::parse_client(" 1.3 ").unwrap(), v("1.3"));
        assert_eq!(Version::parse_client("1.").unwrap(), v("1"));
        assert_eq!(Version::parse_client("v0.2.0").unwrap(), v("0.2.0"));
        assert_eq!(Version::parse_client("1.2.3.4.5").unwrap(), v("1.2.3.4"));
        assert!(Version::parse_client("beta").is_none());
        assert!(Version::parse_client("4294967296").is_none());
        assert!(Version::parse_client("").is_none());
    }

    #[test]
    fn below_minimum_is_required_below_latest_is_available() {
        let r = release(Some("1.4.0"), Some("1.2.0"));
        assert_eq!(r.check(&v("1.1.9")).status, UpdateStatus::UpdateRequired);
        assert_eq!(r.check(&v("1.2.0")).status, UpdateStatus::UpdateAvailable);
        assert_eq!(r.check(&v("1.4")).status, UpdateStatus::Current);
        assert_eq!(r.check(&v("1.5.0")).status, UpdateStatus::Current);
        let check = r.check(&v("1.0"));
        assert_eq!(check.latest_version.as_deref(), Some("1.4.0"));
        assert_eq!(check.update_url.as_deref(), Some("https://example.com/app"));
    }

    #[test]
    fn a_minimum_alone_names_itself_as_the_target() {
        let r = release(None, Some("2.0"));
        let check = r.check(&v("1.9"));
        assert_eq!(check.status, UpdateStatus::UpdateRequired);
        assert_eq!(check.latest_version.as_deref(), Some("2.0"));
        assert_eq!(r.check(&v("2.0")).status, UpdateStatus::Current);
    }

    #[tokio::test]
    async fn nothing_configured_is_current() {
        let versions = from(&[]).unwrap();
        let check = versions.ios.check(&v("0.1"));
        assert_eq!(check.status, UpdateStatus::Current);
        assert_eq!(check.latest_version, None);
        assert_eq!(check.update_url, None);
        assert_eq!(
            versions.check_web("abc").await.status,
            UpdateStatus::Current
        );
    }

    #[tokio::test]
    async fn web_offers_a_reload_when_the_build_differs() {
        let versions = from(&[("WEB_BUILD", "0a1b2c3d4e5f")]).unwrap();
        assert_eq!(
            versions.check_web("0a1b2c3d4e5f").await.status,
            UpdateStatus::Current
        );
        let stale = versions.check_web("ffffffffffff").await;
        assert_eq!(stale.status, UpdateStatus::UpdateAvailable);
        assert_eq!(stale.latest_version.as_deref(), Some("0a1b2c3d4e5f"));
        assert_eq!(stale.update_url, None);
    }

    #[tokio::test]
    async fn the_build_file_is_read_on_every_question() {
        let dir = std::env::temp_dir().join(format!("shroud-web-build-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("web-build");
        let versions = from(&[("WEB_BUILD_FILE", file.to_str().unwrap())]).unwrap();

        // No deploy has written it yet: nobody is told to reload.
        assert_eq!(
            versions.check_web("aaa").await.status,
            UpdateStatus::Current
        );

        std::fs::write(&file, "bbb\n").unwrap();
        let check = versions.check_web("aaa").await;
        assert_eq!(check.status, UpdateStatus::UpdateAvailable);
        assert_eq!(check.latest_version.as_deref(), Some("bbb"));
        assert_eq!(
            versions.check_web("bbb").await.status,
            UpdateStatus::Current
        );

        std::fs::write(&file, "not a build id").unwrap();
        assert_eq!(
            versions.check_web("aaa").await.status,
            UpdateStatus::Current
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn env_is_read_per_platform() {
        let versions = from(&[
            ("IOS_LATEST_VERSION", "1.3"),
            ("IOS_MIN_VERSION", " 1.1 "),
            ("IOS_UPDATE_URL", "https://testflight.apple.com/join/abc"),
            ("ANDROID_LATEST_VERSION", "0.2.0"),
            ("ANDROID_MIN_VERSION", ""),
        ])
        .unwrap();
        assert_eq!(versions.ios.minimum, Some(v("1.1")));
        assert_eq!(versions.android.minimum, None);
        assert_eq!(versions.android.update_url, None);
        assert_eq!(
            versions.android.check(&v("0.1.0")).status,
            UpdateStatus::UpdateAvailable
        );
    }

    #[test]
    fn bad_settings_stop_startup() {
        assert!(from(&[("IOS_LATEST_VERSION", "1.2-beta")]).is_err());
        assert!(from(&[("ANDROID_MIN_VERSION", "two")]).is_err());
        assert!(
            from(&[
                ("ANDROID_LATEST_VERSION", "1.0"),
                ("ANDROID_MIN_VERSION", "1.1")
            ])
            .is_err()
        );
        assert!(from(&[("IOS_UPDATE_URL", "itms-apps://apps.apple.com/app/id1")]).is_err());
        assert!(from(&[("IOS_UPDATE_URL", "http://example.com/app")]).is_err());
        assert!(from(&[("ANDROID_UPDATE_URL", "http://192.168.1.2/shroud.apk")]).is_ok());
        assert!(from(&[("IOS_LATEST_VERSION", "4294967296")]).is_err());
        assert!(from(&[("WEB_BUILD", "abc"), ("WEB_BUILD_FILE", "/run/x")]).is_err());
        assert!(from(&[("WEB_BUILD", "a b")]).is_err());
        assert!(from(&[("WEB_BUILD", &"a".repeat(65))]).is_err());
    }

    #[test]
    fn requests_pass_freely_until_a_minimum_is_set() {
        let none = from(&[("IOS_LATEST_VERSION", "1.4")]).unwrap();
        assert!(none.admits(None));
        assert!(none.admits(Some("ios/0.1")));
        assert!(none.admits(Some("garbage")));
    }

    #[test]
    fn a_minimum_refuses_older_apps_and_apps_that_dont_say() {
        let versions = from(&[("IOS_MIN_VERSION", "1.1"), ("ANDROID_MIN_VERSION", "0.2")]).unwrap();
        assert!(versions.admits(Some("ios/1.1")));
        assert!(versions.admits(Some("ios/1.2.0 (14)")));
        assert!(versions.admits(Some(" android/0.2.0 ")));
        assert!(versions.admits(Some("web/0a1b2c3d4e5f")));
        assert!(!versions.admits(Some("ios/1.0")));
        assert!(!versions.admits(Some("android/0.1.9")));
        assert!(!versions.admits(None));
        assert!(!versions.admits(Some("")));
        assert!(!versions.admits(Some("ios")));
        assert!(!versions.admits(Some("ios/beta")));
        assert!(!versions.admits(Some("web/")));
        assert!(!versions.admits(Some("web/a b")));
        assert!(!versions.admits(Some("windows/9.0")));
    }

    #[test]
    fn one_platforms_minimum_leaves_the_other_platform_free_but_named() {
        let versions = from(&[("IOS_MIN_VERSION", "1.1")]).unwrap();
        assert!(versions.admits(Some("android/0.0.1")));
        assert!(!versions.admits(Some("android/unknown")));
        assert!(!versions.admits(None));
    }

    #[test]
    fn status_serializes_in_snake_case() {
        let check = release(Some("1.4"), None).check(&v("1.0"));
        let json = serde_json::to_value(&check).unwrap();
        assert_eq!(
            json,
            serde_json::json!({
                "status": "update_available",
                "latest_version": "1.4",
                "update_url": "https://example.com/app",
                "server_version": SERVER_VERSION,
            })
        );
    }
}
