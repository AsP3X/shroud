//! Environment-backed server configuration.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::client_version::ClientVersions;
use crate::error::AppError;
use crate::media_store::NebularConfig;
use crate::push::UnifiedPushPolicy;
use crate::turn::TurnConfig;

/// Default Postgres pool size when `DATABASE_POOL_MAX` is unset.
pub const DEFAULT_DATABASE_POOL_MAX: u32 = 10;
/// Telegram Premium allows 3; Shroud starts at 5 (see `docs/server-plan.md`).
const DEFAULT_REACTIONS_MAX_PER_USER: u32 = 5;
const MAX_REACTIONS_PER_USER: u32 = 20;
const DEFAULT_MEDIA_DATA_DIR: &str = "/data/shroud-media";
const DEFAULT_MEDIA_BUCKET: &str = "shroud-media";
/// Nebular ignores the region, but SigV4 scopes every signature to one.
const DEFAULT_NEBULAR_REGION: &str = "us-east-1";
/// Nebular warns about shorter access-key secrets; this API refuses them.
const MIN_NEBULAR_SECRET_LEN: usize = 16;

/// WebRTC ICE server entry advertised to clients.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IceServer {
    pub urls: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub username: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub credential: Option<String>,
}

/// Default bind for the internal operator listener (`OPERATOR_PORT`).
const DEFAULT_OPERATOR_PORT: u16 = 8090;
/// Longer than this is refused: the listener hashes the token on every request.
const MAX_OPERATOR_TOKEN_LEN: usize = 256;

/// Internal listener the admin console calls.
///
/// Absent from [`Config`] when `OPERATOR_TOKEN` is empty: the process does not bind the port.
#[derive(Clone)]
pub struct OperatorListener {
    pub port: u16,
    token: String,
}

impl std::fmt::Debug for OperatorListener {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("OperatorListener")
            .field("port", &self.port)
            .field("token", &"set")
            .finish()
    }
}

impl OperatorListener {
    /// The bearer token. [`Debug`] prints `set` and never this value.
    pub fn token(&self) -> &str {
        &self.token
    }
}

/// Validated settings required before the server accepts traffic.
#[derive(Debug, Clone)]
pub struct Config {
    /// Postgres connection string (credentials in env only — never logged).
    pub database_url: String,
    /// Max connections in the sqlx pool for this process.
    pub database_pool_max: u32,
    /// When true, apply sqlx migrations at startup (disable on non-migrator replicas).
    pub run_migrations: bool,
    pub host: IpAddr,
    pub port: u16,
    /// Where encrypted media blobs live.
    pub media: MediaConfig,
    /// Optional Redis URL for multi-replica WebSocket fan-out and shared rate limits.
    pub redis_url: Option<String>,
    /// When true, honor `X-Forwarded-For` / `X-Real-IP` for rate-limit keys (trusted proxy only).
    pub trust_forwarded_headers: bool,
    /// Browser origins allowed to call the API directly (empty = same-origin / no CORS).
    /// Set from `CORS_ALLOWED_ORIGINS` and/or `WEB_PUBLIC_URL` by the deploy wizard.
    pub cors_allowed_origins: Vec<String>,
    /// STUN/TURN servers every WebRTC client gets as they are.
    pub ice_servers: Vec<IceServer>,
    /// TURN whose logins `GET /calls/ice-servers` mints per user (`TURN_SECRET`).
    pub turn: Option<TurnConfig>,
    /// Most emoji one person may leave on one message (`REACTIONS_MAX_PER_USER`, 1–20).
    pub reactions_max_per_user: u32,
    /// Where Android (UnifiedPush) subscriptions may point (`UNIFIEDPUSH_*`).
    pub unifiedpush: UnifiedPushPolicy,
    /// Released app versions (`IOS_*`, `ANDROID_*`, `WEB_BUILD`).
    pub client_versions: ClientVersions,
    /// Internal operator listener. `None` when `OPERATOR_TOKEN` is unset or blank:
    /// the process does not bind `OPERATOR_PORT`.
    pub operator: Option<OperatorListener>,
}

/// Where encrypted media blobs live (see [`crate::media_store`]).
#[derive(Debug, Clone)]
pub struct MediaConfig {
    /// `MEDIA_DATA_DIR`: the local store, or with Nebular the volume earlier releases wrote,
    /// which is read until its blobs have moved into Nebular.
    pub data_dir: PathBuf,
    /// `NEBULAR_MEDIA_BUCKET`: the bucket new uploads go to.
    pub bucket: String,
    /// Set when `NEBULAR_URL` is: Nebular OS holds the blobs.
    pub nebular: Option<NebularConfig>,
}

impl Config {
    /// Loads and validates configuration from process environment.
    pub fn from_env() -> Result<Self, AppError> {
        let database_url = std::env::var("DATABASE_URL")
            .map_err(|_| AppError::Internal("DATABASE_URL is not set".into()))?;

        let database_pool_max = parse_u32_env(
            "DATABASE_POOL_MAX",
            std::env::var("DATABASE_POOL_MAX").ok().as_deref(),
            DEFAULT_DATABASE_POOL_MAX,
        )?;
        if database_pool_max == 0 {
            return Err(AppError::Internal(
                "DATABASE_POOL_MAX must be at least 1".into(),
            ));
        }

        // Human: Default true so single-instance / Compose keep working; scale-out sets false on workers.
        // Agent: READS RUN_MIGRATIONS; false skips sqlx::migrate at boot.
        let run_migrations = parse_bool_env(
            "RUN_MIGRATIONS",
            std::env::var("RUN_MIGRATIONS").ok().as_deref(),
            true,
        )?;

        let host = std::env::var("HOST")
            .ok()
            .and_then(|value| value.parse().ok())
            .unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));

        let port = std::env::var("PORT")
            .ok()
            .and_then(|value| value.parse().ok())
            .unwrap_or(8080);

        let media = media_config(&|name| std::env::var(name).ok())?;
        if std::env::var_os("MEDIA_PREFER_NEBULAR").is_some() {
            tracing::warn!(
                "MEDIA_PREFER_NEBULAR is no longer used: with NEBULAR_URL set, Nebular is the only media store"
            );
        }

        let redis_url = std::env::var("REDIS_URL")
            .ok()
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());

        // Human: Default false so a publicly bound API cannot have rate-limit IPs spoofed via XFF.
        // Agent: READS TRUST_FORWARDED_HEADERS; Compose sets true behind NPM.
        let trust_forwarded_headers = parse_bool_env(
            "TRUST_FORWARDED_HEADERS",
            std::env::var("TRUST_FORWARDED_HEADERS").ok().as_deref(),
            false,
        )?;

        let ice = crate::turn::ice_config(&|name| std::env::var(name).ok())?;
        if ice.servers.is_empty() && ice.turn.is_none() {
            tracing::warn!(
                "no STUN or TURN server configured (TURN_URLS, ICE_SERVERS_JSON): calls connect only where a direct path exists"
            );
        }
        let cors_allowed_origins = cors_origins_from_env();

        // Human: Handed to clients by `GET /config`; the server can't count sealed emoji, so the
        // clients enforce it (and show at most 20 from anyone, whatever this says).
        let reactions_max_per_user =
            parse_reactions_max_per_user(std::env::var("REACTIONS_MAX_PER_USER").ok().as_deref())?;

        let unifiedpush = unifiedpush_policy(&|name| std::env::var(name).ok())?;
        if unifiedpush.allow_local_http {
            tracing::warn!(
                "UNIFIEDPUSH_ALLOW_LOCAL_HTTP is on: Android subscriptions may point at http:// on \
                 this machine. For end-to-end tests only — never in production"
            );
        }
        if unifiedpush.public_hosts {
            tracing::info!(
                "UNIFIEDPUSH_PUBLIC_HOSTS is on: Android subscriptions may point at any public \
                 https host"
            );
        }

        // Human: Handed to clients by `GET /client-version`, which prompts them to update.
        let client_versions =
            crate::client_version::client_versions(&|name| std::env::var(name).ok())?;

        // Human: Unset token means this process has no operator listener. A set token that
        // cannot be bound (bad port, or the public PORT) refuses startup instead of serving
        // the public API without the console's writes.
        let operator = operator_listener(
            std::env::var("OPERATOR_TOKEN").ok().as_deref(),
            std::env::var("OPERATOR_PORT").ok().as_deref(),
            port,
        )?;

        Ok(Self {
            database_url,
            database_pool_max,
            run_migrations,
            host,
            port,
            media,
            redis_url,
            trust_forwarded_headers,
            cors_allowed_origins,
            ice_servers: ice.servers,
            turn: ice.turn,
            reactions_max_per_user,
            unifiedpush,
            client_versions,
            operator,
        })
    }

    /// Returns the socket address used for `TcpListener::bind`.
    pub fn socket_addr(&self) -> Result<SocketAddr, AppError> {
        Ok(SocketAddr::new(self.host, self.port))
    }
}

/// The operator listener, or `None` when `token` is unset or only whitespace.
///
/// A blank token leaves the listener off and ignores `port`, so a half-filled environment
/// still boots the public API. A real token is kept exactly as written. It must not contain
/// a line break or be longer than 256 bytes. The port defaults to 8090 and must not be 0
/// or the public `PORT`.
fn operator_listener(
    token: Option<&str>,
    port: Option<&str>,
    public_port: u16,
) -> Result<Option<OperatorListener>, AppError> {
    let Some(token) = token.filter(|value| !value.trim().is_empty()) else {
        return Ok(None);
    };
    if token.len() > MAX_OPERATOR_TOKEN_LEN || token.contains('\r') || token.contains('\n') {
        return Err(AppError::Internal(
            "OPERATOR_TOKEN must be at most 256 bytes and must not contain a line break".into(),
        ));
    }
    let port = match port.map(str::trim).filter(|value| !value.is_empty()) {
        None => DEFAULT_OPERATOR_PORT,
        Some(raw) => raw
            .parse::<u16>()
            .map_err(|_| AppError::Internal("OPERATOR_PORT must be a port number".into()))?,
    };
    if port == 0 || port == public_port {
        return Err(AppError::Internal(
            "OPERATOR_PORT must be a free port and must differ from PORT".into(),
        ));
    }
    Ok(Some(OperatorListener {
        port,
        token: token.to_string(),
    }))
}

/// Browser origins that may call this API cross-origin.
///
/// `CORS_ALLOWED_ORIGINS` is a comma-separated list. `WEB_PUBLIC_URL` is also
/// accepted so a single deploy-wizard value covers the official web client.
pub fn cors_origins_from_env() -> Vec<String> {
    parse_cors_origins(
        std::env::var("CORS_ALLOWED_ORIGINS").ok().as_deref(),
        std::env::var("WEB_PUBLIC_URL").ok().as_deref(),
    )
}

/// Normalize `https://host[:port]/path` down to a CORS origin (`https://host[:port]`).
pub fn parse_cors_origins(cors_allowed: Option<&str>, web_public_url: Option<&str>) -> Vec<String> {
    let mut origins = Vec::new();
    for raw in [cors_allowed, web_public_url].into_iter().flatten() {
        for part in raw.split(',') {
            let Some(origin) = origin_from_url(part) else {
                continue;
            };
            if !origins.iter().any(|existing| existing == &origin) {
                origins.push(origin);
            }
        }
    }
    origins
}

fn origin_from_url(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let (scheme, rest) = trimmed.split_once("://")?;
    if scheme != "http" && scheme != "https" {
        return None;
    }
    let hostport = rest.split('/').next().unwrap_or("").trim();
    if hostport.is_empty() || hostport.contains(' ') {
        return None;
    }
    Some(format!("{scheme}://{hostport}"))
}

fn parse_u32_env(name: &str, raw: Option<&str>, default: u32) -> Result<u32, AppError> {
    let Some(raw) = raw else {
        return Ok(default);
    };
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Ok(default);
    }
    trimmed
        .parse::<u32>()
        .map_err(|_| AppError::Internal(format!("{name} must be a positive integer, got {raw:?}")))
}

/// `REACTIONS_MAX_PER_USER`: 1–20, default 5. Out of range stops startup rather than being
/// clamped quietly, so clients get the value the operator set.
fn parse_reactions_max_per_user(raw: Option<&str>) -> Result<u32, AppError> {
    let value = parse_u32_env(
        "REACTIONS_MAX_PER_USER",
        raw,
        DEFAULT_REACTIONS_MAX_PER_USER,
    )?;
    if !(1..=MAX_REACTIONS_PER_USER).contains(&value) {
        return Err(AppError::Internal(format!(
            "REACTIONS_MAX_PER_USER must be between 1 and {MAX_REACTIONS_PER_USER}, got {value}"
        )));
    }
    Ok(value)
}

/// Media settings, read through `lookup` (the process environment outside tests).
///
/// With `NEBULAR_URL` set, the access key Nebular gave this API is required: Nebular OS 0.2
/// authenticates it with SigV4 only, and `./deploy.sh` writes the pair into `.env`.
pub fn media_config(lookup: &dyn Fn(&str) -> Option<String>) -> Result<MediaConfig, AppError> {
    let value = |name: &str| {
        lookup(name)
            .map(|raw| raw.trim().to_string())
            .filter(|raw| !raw.is_empty())
    };

    let data_dir =
        PathBuf::from(value("MEDIA_DATA_DIR").unwrap_or_else(|| DEFAULT_MEDIA_DATA_DIR.into()));
    let bucket = value("NEBULAR_MEDIA_BUCKET").unwrap_or_else(|| DEFAULT_MEDIA_BUCKET.into());
    if !is_valid_bucket_name(&bucket) {
        return Err(AppError::Internal(format!(
            "NEBULAR_MEDIA_BUCKET must be 3–63 lowercase letters, digits, '-' or '.', starting and \
             ending with a letter or digit; got {bucket:?}"
        )));
    }

    let Some(url) = value("NEBULAR_URL") else {
        return Ok(MediaConfig {
            data_dir,
            bucket,
            nebular: None,
        });
    };
    let (Some(access_key_id), Some(secret_access_key)) = (
        value("NEBULAR_ACCESS_KEY_ID"),
        value("NEBULAR_SECRET_ACCESS_KEY"),
    ) else {
        return Err(AppError::Internal(
            "NEBULAR_URL is set but NEBULAR_ACCESS_KEY_ID / NEBULAR_SECRET_ACCESS_KEY are not. \
             Nebular OS authenticates this API with a SigV4 access key (its NOS_S3_ACCESS_KEY / \
             NOS_S3_SECRET_KEY); ./deploy.sh adds one to .env."
                .into(),
        ));
    };
    if access_key_id.len() > 128 || access_key_id.contains(|c: char| c == '/' || c.is_whitespace())
    {
        return Err(AppError::Internal(
            "NEBULAR_ACCESS_KEY_ID must be at most 128 characters without '/' or spaces".into(),
        ));
    }
    if secret_access_key.len() < MIN_NEBULAR_SECRET_LEN {
        return Err(AppError::Internal(format!(
            "NEBULAR_SECRET_ACCESS_KEY must be at least {MIN_NEBULAR_SECRET_LEN} characters \
             (generate one with `openssl rand -hex 32`)"
        )));
    }
    let region = value("NEBULAR_REGION").unwrap_or_else(|| DEFAULT_NEBULAR_REGION.into());
    if region.contains(|c: char| c == '/' || c.is_whitespace()) {
        return Err(AppError::Internal(
            "NEBULAR_REGION may not contain '/' or spaces".into(),
        ));
    }

    Ok(MediaConfig {
        data_dir,
        bucket,
        nebular: Some(NebularConfig {
            url,
            access_key_id,
            secret_access_key,
            region,
        }),
    })
}

/// Where Android subscriptions may point, read through `lookup` (the process environment
/// outside tests).
///
/// Human: The built-in UnifiedPush servers need no setting. `UNIFIEDPUSH_ALLOWED_HOSTS` adds
/// an operator's own (host suffixes, comma separated); `UNIFIEDPUSH_PUBLIC_HOSTS=true` lets the
/// app use any public https host; `UNIFIEDPUSH_ALLOW_LOCAL_HTTP=true` is for end-to-end tests
/// against a local ntfy only. A switch that is not true/false stops startup.
pub fn unifiedpush_policy(
    lookup: &dyn Fn(&str) -> Option<String>,
) -> Result<UnifiedPushPolicy, AppError> {
    Ok(UnifiedPushPolicy {
        allowed_hosts: crate::push::web_push::host_suffixes(
            &lookup("UNIFIEDPUSH_ALLOWED_HOSTS").unwrap_or_default(),
        ),
        public_hosts: parse_bool_env(
            "UNIFIEDPUSH_PUBLIC_HOSTS",
            lookup("UNIFIEDPUSH_PUBLIC_HOSTS").as_deref(),
            false,
        )?,
        allow_local_http: parse_bool_env(
            "UNIFIEDPUSH_ALLOW_LOCAL_HTTP",
            lookup("UNIFIEDPUSH_ALLOW_LOCAL_HTTP").as_deref(),
            false,
        )?,
        resolve_overrides: std::collections::HashMap::new(),
    })
}

/// S3's bucket naming rules, which also keep Nebular's system directories out of reach.
fn is_valid_bucket_name(name: &str) -> bool {
    let bytes = name.as_bytes();
    (3..=63).contains(&bytes.len())
        && bytes
            .iter()
            .all(|&b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'.')
        && bytes.first().is_some_and(u8::is_ascii_alphanumeric)
        && bytes.last().is_some_and(u8::is_ascii_alphanumeric)
}

fn parse_bool_env(name: &str, raw: Option<&str>, default: bool) -> Result<bool, AppError> {
    let Some(raw) = raw else {
        return Ok(default);
    };
    let trimmed = raw.trim().to_ascii_lowercase();
    if trimmed.is_empty() {
        return Ok(default);
    }
    match trimmed.as_str() {
        "1" | "true" | "yes" | "on" => Ok(true),
        "0" | "false" | "no" | "off" => Ok(false),
        _ => Err(AppError::Internal(format!(
            "{name} must be true/false (or 1/0), got {raw:?}"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_bool_env_accepts_common_forms() {
        assert!(parse_bool_env("RUN_MIGRATIONS", Some("yes"), false).unwrap());
        assert!(!parse_bool_env("RUN_MIGRATIONS", Some("0"), true).unwrap());
        assert!(parse_bool_env("RUN_MIGRATIONS", None, true).unwrap());
        assert!(parse_bool_env("RUN_MIGRATIONS", Some(""), true).unwrap());
    }

    #[test]
    fn parse_u32_env_defaults_and_parses() {
        assert_eq!(
            parse_u32_env("DATABASE_POOL_MAX", Some("42"), 10).unwrap(),
            42
        );
        assert_eq!(parse_u32_env("DATABASE_POOL_MAX", None, 10).unwrap(), 10);
        assert!(parse_u32_env("DATABASE_POOL_MAX", Some("nope"), 10).is_err());
    }

    #[test]
    fn reactions_max_per_user_is_one_to_twenty() {
        assert_eq!(parse_reactions_max_per_user(None).unwrap(), 5);
        assert_eq!(parse_reactions_max_per_user(Some(" ")).unwrap(), 5);
        assert_eq!(parse_reactions_max_per_user(Some("1")).unwrap(), 1);
        assert_eq!(parse_reactions_max_per_user(Some("20")).unwrap(), 20);
        assert!(parse_reactions_max_per_user(Some("0")).is_err());
        assert!(parse_reactions_max_per_user(Some("21")).is_err());
        assert!(parse_reactions_max_per_user(Some("-3")).is_err());
    }

    #[test]
    fn parse_cors_origins_strips_paths_and_dedupes() {
        let origins = parse_cors_origins(
            Some("https://web.example.com/app, http://localhost:8081"),
            Some("https://web.example.com/"),
        );
        assert_eq!(
            origins,
            vec![
                "https://web.example.com".to_string(),
                "http://localhost:8081".to_string(),
            ]
        );
    }

    #[test]
    fn parse_cors_origins_skips_scheme_less_and_empty() {
        let origins = parse_cors_origins(Some("web.example.com, , ftp://nope"), Some(""));
        assert!(origins.is_empty());
    }

    fn media_from(pairs: &[(&str, &str)]) -> Result<MediaConfig, AppError> {
        let env: std::collections::HashMap<String, String> = pairs
            .iter()
            .map(|(k, v)| ((*k).to_string(), (*v).to_string()))
            .collect();
        media_config(&|name| env.get(name).cloned())
    }

    const SECRET: &str = "5f0e8c2a9b7d4e1f6a3c0b9d8e7f6a5b";

    #[test]
    fn media_defaults_to_the_local_volume() {
        let media = media_from(&[]).unwrap();
        assert_eq!(media.data_dir, PathBuf::from("/data/shroud-media"));
        assert_eq!(media.bucket, "shroud-media");
        assert!(media.nebular.is_none());
        // Blank values count as unset, like elsewhere in this file.
        assert!(
            media_from(&[("NEBULAR_URL", " ")])
                .unwrap()
                .nebular
                .is_none()
        );
    }

    #[test]
    fn nebular_needs_its_access_key() {
        let err = media_from(&[("NEBULAR_URL", "http://nebular:9000")]).unwrap_err();
        assert!(err.to_string().contains("NEBULAR_ACCESS_KEY_ID"));
        let err = media_from(&[
            ("NEBULAR_URL", "http://nebular:9000"),
            ("NEBULAR_ACCESS_KEY_ID", "SHROUDKEY"),
            ("NEBULAR_SECRET_ACCESS_KEY", "too-short"),
        ])
        .unwrap_err();
        assert!(err.to_string().contains("at least 16"));
        let err = media_from(&[
            ("NEBULAR_URL", "http://nebular:9000"),
            ("NEBULAR_ACCESS_KEY_ID", "SHROUD/KEY"),
            ("NEBULAR_SECRET_ACCESS_KEY", SECRET),
        ])
        .unwrap_err();
        assert!(err.to_string().contains("'/'"));
    }

    #[test]
    fn nebular_settings_are_read_and_the_secret_stays_out_of_debug() {
        let media = media_from(&[
            ("NEBULAR_URL", "http://nebular:9000"),
            ("NEBULAR_ACCESS_KEY_ID", "SHROUDKEY"),
            ("NEBULAR_SECRET_ACCESS_KEY", SECRET),
            ("NEBULAR_MEDIA_BUCKET", "media.shroud-1"),
            ("MEDIA_DATA_DIR", "/srv/media"),
        ])
        .unwrap();
        let nebular = media.nebular.as_ref().expect("nebular");
        assert_eq!(nebular.url, "http://nebular:9000");
        assert_eq!(nebular.access_key_id, "SHROUDKEY");
        assert_eq!(nebular.region, "us-east-1");
        assert_eq!(media.bucket, "media.shroud-1");
        assert_eq!(media.data_dir, PathBuf::from("/srv/media"));
        assert!(!format!("{media:?}").contains(SECRET));
    }

    fn unifiedpush_from(pairs: &[(&str, &str)]) -> Result<UnifiedPushPolicy, AppError> {
        let env: std::collections::HashMap<String, String> = pairs
            .iter()
            .map(|(k, v)| ((*k).to_string(), (*v).to_string()))
            .collect();
        unifiedpush_policy(&|name| env.get(name).cloned())
    }

    #[test]
    fn unifiedpush_is_built_in_hosts_only_by_default() {
        let policy = unifiedpush_from(&[]).unwrap();
        assert!(policy.allowed_hosts.is_empty());
        assert!(!policy.public_hosts);
        assert!(!policy.allow_local_http);
        assert!(policy.resolve_overrides.is_empty());
        let policy = unifiedpush_from(&[
            (
                "UNIFIEDPUSH_ALLOWED_HOSTS",
                " Push.Example.org, .ntfy.example.net ,",
            ),
            ("UNIFIEDPUSH_PUBLIC_HOSTS", "true"),
            ("UNIFIEDPUSH_ALLOW_LOCAL_HTTP", "1"),
        ])
        .unwrap();
        assert_eq!(
            policy.allowed_hosts,
            vec![
                "push.example.org".to_string(),
                "ntfy.example.net".to_string()
            ]
        );
        assert!(policy.public_hosts);
        assert!(policy.allow_local_http);
        let err = unifiedpush_from(&[("UNIFIEDPUSH_PUBLIC_HOSTS", "sometimes")]).unwrap_err();
        assert!(err.to_string().contains("UNIFIEDPUSH_PUBLIC_HOSTS"));
    }

    #[test]
    fn bucket_names_follow_s3_rules() {
        for good in ["shroud-media", "abc", "media.1"] {
            assert!(is_valid_bucket_name(good), "{good}");
        }
        for bad in [
            "ab",
            "Shroud",
            ".media",
            "media-",
            "a/b",
            "_nos",
            &"a".repeat(64),
        ] {
            assert!(!is_valid_bucket_name(bad), "{bad}");
        }
        assert!(media_from(&[("NEBULAR_MEDIA_BUCKET", "../etc")]).is_err());
    }

    #[test]
    fn operator_listener_stays_off_without_a_token() {
        assert!(
            operator_listener(None, Some("nope"), 8080)
                .unwrap()
                .is_none()
        );
        assert!(
            operator_listener(Some("  \n\t"), Some("0"), 8080)
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn operator_listener_keeps_the_token_and_rejects_a_bad_port() {
        let listener = operator_listener(Some(" spaced "), None, 8080)
            .unwrap()
            .expect("listener");
        assert_eq!(listener.port, DEFAULT_OPERATOR_PORT);
        assert_eq!(listener.token(), " spaced ");
        let debug = format!("{listener:?}");
        assert!(debug.contains("set"));
        assert!(!debug.contains(" spaced "));

        assert_eq!(
            operator_listener(Some("tok"), Some(""), 8080)
                .unwrap()
                .unwrap()
                .port,
            DEFAULT_OPERATOR_PORT
        );
        assert!(operator_listener(Some("tok"), Some("8080"), 8080).is_err());
        assert!(operator_listener(Some("tok"), Some("0"), 8080).is_err());
        assert!(operator_listener(Some("tok"), Some("nope"), 8080).is_err());
        assert!(operator_listener(Some("tok\n"), None, 8080).is_err());
        assert!(
            operator_listener(Some(&"x".repeat(MAX_OPERATOR_TOKEN_LEN + 1)), None, 8080).is_err()
        );
        assert!(
            operator_listener(
                Some(&"x".repeat(MAX_OPERATOR_TOKEN_LEN)),
                Some("8090"),
                8080
            )
            .is_ok()
        );
    }
}
