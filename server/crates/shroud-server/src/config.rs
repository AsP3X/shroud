//! Environment-backed server configuration.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use serde::{Deserialize, Serialize};

use crate::error::AppError;

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
    pub host: IpAddr,
    pub port: u16,
    /// Optional Nebular OS base URL; when unset, media uses stub presign URLs.
    pub nebular_url: Option<String>,
    pub nebular_media_bucket: String,
    /// Optional Redis URL for multi-replica WebSocket fan-out.
    pub redis_url: Option<String>,
    /// STUN/TURN servers for WebRTC clients.
    pub ice_servers: Vec<IceServer>,
}

impl Config {
    /// Loads and validates configuration from process environment.
    pub fn from_env() -> Result<Self, AppError> {
        let database_url = std::env::var("DATABASE_URL")
            .map_err(|_| AppError::Internal("DATABASE_URL is not set".into()))?;

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

        let ice_servers = ice_servers_from_env();

        Ok(Self {
            database_url,
            host,
            port,
            nebular_url,
            nebular_media_bucket,
            redis_url,
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
                username: std::env::var("TURN_USERNAME").ok().filter(|s| !s.is_empty()),
                credential: std::env::var("TURN_CREDENTIAL")
                    .ok()
                    .filter(|s| !s.is_empty()),
            });
        }
    }

    servers
}
