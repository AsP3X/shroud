//! Environment-backed server configuration.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use crate::error::AppError;

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

        Ok(Self {
            database_url,
            host,
            port,
            nebular_url,
            nebular_media_bucket,
        })
    }

    /// Returns the socket address used for `TcpListener::bind`.
    pub fn socket_addr(&self) -> Result<SocketAddr, AppError> {
        Ok(SocketAddr::new(self.host, self.port))
    }
}
