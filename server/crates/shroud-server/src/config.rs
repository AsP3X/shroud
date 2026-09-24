//! Environment-backed server configuration.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use serde::{Deserialize, Serialize};

use crate::error::AppError;

/// Default Postgres pool size when `DATABASE_POOL_MAX` is unset.
pub const DEFAULT_DATABASE_POOL_MAX: u32 = 10;
/// Telegram Premium allows 3; Shroud starts at 5 (see `docs/server-plan.md`).
const DEFAULT_REACTIONS_MAX_PER_USER: u32 = 5;
const MAX_REACTIONS_PER_USER: u32 = 20;

/// WebRTC ICE server entry advertised to clients.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IceServer {
    pub urls: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub username: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub credential: Option<String>,
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
    /// Optional Nebular OS base URL; when unset, media uses the local volume only.
    pub nebular_url: Option<String>,
    pub nebular_media_bucket: String,
    /// Optional Redis URL for multi-replica WebSocket fan-out and shared rate limits.
    pub redis_url: Option<String>,
    /// When true, honor `X-Forwarded-For` / `X-Real-IP` for rate-limit keys (trusted proxy only).
    pub trust_forwarded_headers: bool,
    /// Browser origins allowed to call the API directly (empty = same-origin / no CORS).
    /// Set from `CORS_ALLOWED_ORIGINS` and/or `WEB_PUBLIC_URL` by the deploy wizard.
    pub cors_allowed_origins: Vec<String>,
    /// STUN/TURN servers for WebRTC clients.
    pub ice_servers: Vec<IceServer>,
    /// Most emoji one person may leave on one message (`REACTIONS_MAX_PER_USER`, 1–20).
    pub reactions_max_per_user: u32,
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

        let nebular_url = std::env::var("NEBULAR_URL")
            .ok()
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());

        let nebular_media_bucket =
            std::env::var("NEBULAR_MEDIA_BUCKET").unwrap_or_else(|_| "shroud-media".into());

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

        let ice_servers = ice_servers_from_env();
        let cors_allowed_origins = cors_origins_from_env();

        // Human: Handed to clients by `GET /config`; the server can't count sealed emoji, so the
        // clients enforce it (and show at most 20 from anyone, whatever this says).
        let reactions_max_per_user =
            parse_reactions_max_per_user(std::env::var("REACTIONS_MAX_PER_USER").ok().as_deref())?;

        Ok(Self {
            database_url,
            database_pool_max,
            run_migrations,
            host,
            port,
            nebular_url,
            nebular_media_bucket,
            redis_url,
            trust_forwarded_headers,
            cors_allowed_origins,
            ice_servers,
            reactions_max_per_user,
        })
    }

    /// Returns the socket address used for `TcpListener::bind`.
    pub fn socket_addr(&self) -> Result<SocketAddr, AppError> {
        Ok(SocketAddr::new(self.host, self.port))
    }
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

/// Parse ICE servers from env.
///
/// - `ICE_SERVERS_JSON` — full JSON array of `{urls, username?, credential?}`
/// - else default Google STUN + optional `TURN_URLS` / `TURN_USERNAME` / `TURN_CREDENTIAL`
pub fn ice_servers_from_env() -> Vec<IceServer> {
    if let Ok(raw) = std::env::var("ICE_SERVERS_JSON") {
        let trimmed = raw.trim();
        if !trimmed.is_empty() {
            if let Ok(servers) = serde_json::from_str::<Vec<IceServer>>(trimmed) {
                if !servers.is_empty() {
                    return servers;
                }
            } else {
                tracing::warn!("ICE_SERVERS_JSON invalid; falling back to defaults");
            }
        }
    }

    let mut servers = vec![IceServer {
        urls: vec!["stun:stun.l.google.com:19302".into()],
        username: None,
        credential: None,
    }];

    if let Ok(turn_urls) = std::env::var("TURN_URLS") {
        let urls: Vec<String> = turn_urls
            .split(',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect();
        if !urls.is_empty() {
            servers.push(IceServer {
                urls,
                username: std::env::var("TURN_USERNAME")
                    .ok()
                    .filter(|s| !s.is_empty()),
                credential: std::env::var("TURN_CREDENTIAL")
                    .ok()
                    .filter(|s| !s.is_empty()),
            });
        }
    }

    servers
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
}
