//! Environment-backed server configuration.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use serde::{Deserialize, Serialize};

use crate::error::AppError;

/// Default Postgres pool size when `DATABASE_POOL_MAX` is unset.
pub const DEFAULT_DATABASE_POOL_MAX: u32 = 10;

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
    /// STUN/TURN servers for WebRTC clients.
    pub ice_servers: Vec<IceServer>,
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
            ice_servers,
        })
    }

    /// Returns the socket address used for `TcpListener::bind`.
    pub fn socket_addr(&self) -> Result<SocketAddr, AppError> {
        Ok(SocketAddr::new(self.host, self.port))
    }
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
}
