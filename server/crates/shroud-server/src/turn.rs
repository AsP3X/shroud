//! STUN/TURN settings for WebRTC calls, and the TURN logins the API mints.
//!
//! Human: coturn runs with `--use-auth-secret`: it shares a secret with this API and accepts any
//! login the API derived from it until the login's expiry. Nobody holds a standing password, so
//! nothing in the repository or `.env.example` opens the relay.
//! Agent: username = `<expiry unix>:<user id>`, credential = base64(HMAC-SHA1(secret, username))
//! (coturn's TURN REST API). READS ICE_SERVERS_JSON, TURN_URLS, TURN_SECRET,
//! TURN_CREDENTIAL_TTL_SECS, and the legacy fixed TURN_USERNAME / TURN_CREDENTIAL.

use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use ring::hmac;
use uuid::Uuid;

use crate::config::IceServer;
use crate::error::AppError;

/// Google's public STUN server: the default when no TURN server is configured.
const DEFAULT_STUN_URL: &str = "stun:stun.l.google.com:19302";
/// How long a minted TURN login works. A call keeps using the login it started with (TURN
/// refreshes its allocation every few minutes), so this bounds the longest call over TURN.
pub const DEFAULT_TURN_CREDENTIAL_TTL_SECS: u64 = 12 * 60 * 60;
const MIN_TURN_CREDENTIAL_TTL_SECS: u64 = 60 * 60;
const MAX_TURN_CREDENTIAL_TTL_SECS: u64 = 7 * 24 * 60 * 60;
/// coturn takes any string; a short one is guessable offline from a single minted login.
const MIN_TURN_SECRET_LEN: usize = 16;

/// A TURN server whose logins the API mints per request (`TURN_URLS` + `TURN_SECRET`).
#[derive(Clone)]
pub struct TurnConfig {
    /// `turn:` / `turns:` URLs of one coturn deployment.
    pub urls: Vec<String>,
    /// coturn's `static-auth-secret`.
    secret: String,
    pub ttl_secs: u64,
}

impl std::fmt::Debug for TurnConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TurnConfig")
            .field("urls", &self.urls)
            .field("secret", &"<redacted>")
            .field("ttl_secs", &self.ttl_secs)
            .finish()
    }
}

impl TurnConfig {
    pub fn new(urls: Vec<String>, secret: String, ttl_secs: u64) -> Self {
        Self {
            urls,
            secret,
            ttl_secs,
        }
    }

    /// A login for `user_id` that coturn accepts until `now_unix + ttl_secs`.
    pub fn credential_for(&self, user_id: Uuid, now_unix: u64) -> IceServer {
        let username = format!("{}:{user_id}", now_unix + self.ttl_secs);
        let key = hmac::Key::new(hmac::HMAC_SHA1_FOR_LEGACY_USE_ONLY, self.secret.as_bytes());
        let tag = hmac::sign(&key, username.as_bytes());
        IceServer {
            urls: self.urls.clone(),
            username: Some(username),
            credential: Some(BASE64.encode(tag.as_ref())),
        }
    }
}

/// The ICE servers every client gets as they are, and the TURN server whose logins are minted.
#[derive(Debug, Clone, Default)]
pub struct IceConfig {
    pub servers: Vec<IceServer>,
    pub turn: Option<TurnConfig>,
}

/// Reads the ICE settings.
///
/// - `ICE_SERVERS_JSON`: a JSON array of `{urls, username?, credential?}` handed out as is. It
///   replaces the default STUN server and the fixed `TURN_USERNAME` login, not a minted one.
/// - `TURN_URLS` + `TURN_SECRET`: TURN with minted logins. With no `ICE_SERVERS_JSON`, STUN is
///   asked of the same coturn (it answers binding requests on its TURN port), not Google.
/// - `TURN_URLS` + `TURN_USERNAME` / `TURN_CREDENTIAL`: one fixed login for everyone, for a
///   hosted TURN service that works that way. Never use it with the bundled coturn.
pub fn ice_config(lookup: &dyn Fn(&str) -> Option<String>) -> Result<IceConfig, AppError> {
    let value = |name: &str| {
        lookup(name)
            .map(|raw| raw.trim().to_string())
            .filter(|raw| !raw.is_empty())
    };

    // Human: A broken value was only ever a warning; refusing to start over it would take a
    // running deployment down on upgrade.
    let custom: Option<Vec<IceServer>> = match value("ICE_SERVERS_JSON") {
        Some(raw) => match serde_json::from_str::<Vec<IceServer>>(&raw) {
            Ok(servers) if !servers.is_empty() => Some(servers),
            Ok(_) => None,
            Err(err) => {
                tracing::warn!(error = %err, "ICE_SERVERS_JSON invalid; falling back to defaults");
                None
            }
        },
        None => None,
    };

    let turn_urls: Vec<String> = value("TURN_URLS")
        .map(|raw| {
            raw.split(',')
                .map(str::trim)
                .filter(|url| !url.is_empty())
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default();

    let secret = value("TURN_SECRET");
    let turn = match secret {
        // Deploy writes a secret into an existing .env before anyone sets TURN_URLS. Refusing
        // to start there takes the API down on upgrade; calls keep working on STUN until both
        // are set.
        Some(_) if turn_urls.is_empty() => {
            tracing::warn!(
                "TURN_SECRET is set but TURN_URLS is empty; calls will use STUN only until both are set"
            );
            None
        }
        Some(secret) => {
            if let Some(bad) = turn_urls
                .iter()
                .find(|url| !(url.starts_with("turn:") || url.starts_with("turns:")))
            {
                return Err(AppError::Internal(format!(
                    "TURN_URLS entries must start with turn: or turns: (got {bad})"
                )));
            }
            if secret.len() < MIN_TURN_SECRET_LEN {
                return Err(AppError::Internal(format!(
                    "TURN_SECRET must be at least {MIN_TURN_SECRET_LEN} characters"
                )));
            }
            if value("TURN_USERNAME").is_some() || value("TURN_CREDENTIAL").is_some() {
                tracing::warn!(
                    "TURN_USERNAME / TURN_CREDENTIAL are ignored: TURN_SECRET mints the TURN logins"
                );
            }
            let ttl_secs = match value("TURN_CREDENTIAL_TTL_SECS") {
                Some(raw) => raw.parse::<u64>().ok().filter(|ttl| {
                    (MIN_TURN_CREDENTIAL_TTL_SECS..=MAX_TURN_CREDENTIAL_TTL_SECS).contains(ttl)
                }).ok_or_else(|| {
                    AppError::Internal(format!(
                        "TURN_CREDENTIAL_TTL_SECS must be {MIN_TURN_CREDENTIAL_TTL_SECS}–{MAX_TURN_CREDENTIAL_TTL_SECS} seconds"
                    ))
                })?,
                None => DEFAULT_TURN_CREDENTIAL_TTL_SECS,
            };
            Some(TurnConfig::new(turn_urls.clone(), secret, ttl_secs))
        }
        None => None,
    };

    let servers = match (custom, &turn) {
        (Some(custom), _) => custom,
        (None, Some(turn)) => stun_servers_of(&turn.urls),
        (None, None) => {
            let mut servers = vec![IceServer {
                urls: vec![DEFAULT_STUN_URL.into()],
                username: None,
                credential: None,
            }];
            if !turn_urls.is_empty() {
                servers.push(IceServer {
                    urls: turn_urls,
                    username: value("TURN_USERNAME"),
                    credential: value("TURN_CREDENTIAL"),
                });
            }
            servers
        }
    };

    Ok(IceConfig { servers, turn })
}

/// `stun:host:port` for each distinct host of the plain `turn:` URLs.
fn stun_servers_of(turn_urls: &[String]) -> Vec<IceServer> {
    let mut urls: Vec<String> = Vec::new();
    for url in turn_urls {
        let Some(rest) = url.strip_prefix("turn:") else {
            continue;
        };
        let host_port = rest.split('?').next().unwrap_or_default();
        if host_port.is_empty() {
            continue;
        }
        let stun = format!("stun:{host_port}");
        if !urls.contains(&stun) {
            urls.push(stun);
        }
    }
    if urls.is_empty() {
        return Vec::new();
    }
    vec![IceServer {
        urls,
        username: None,
        credential: None,
    }]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config(pairs: &[(&str, &str)]) -> Result<IceConfig, AppError> {
        let pairs: Vec<(String, String)> = pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        ice_config(&|name| {
            pairs
                .iter()
                .find(|(key, _)| key == name)
                .map(|(_, value)| value.clone())
        })
    }

    #[test]
    fn without_turn_clients_get_google_stun() {
        let ice = config(&[]).expect("config");
        assert!(ice.turn.is_none());
        assert_eq!(ice.servers.len(), 1);
        assert_eq!(ice.servers[0].urls, vec![DEFAULT_STUN_URL.to_string()]);
    }

    #[test]
    fn a_turn_secret_mints_logins_and_stun_comes_from_the_same_coturn() {
        let ice = config(&[
            (
                "TURN_URLS",
                "turn:turn.example.com:3478?transport=udp, turn:turn.example.com:3478?transport=tcp, turns:turn.example.com:5349?transport=tcp",
            ),
            ("TURN_SECRET", "test-turn-secret-0123456789"),
            ("TURN_USERNAME", "shroud"),
            ("TURN_CREDENTIAL", "shroud"),
        ])
        .expect("config");
        let turn = ice.turn.expect("minted turn");
        assert_eq!(turn.urls.len(), 3);
        assert_eq!(turn.ttl_secs, DEFAULT_TURN_CREDENTIAL_TTL_SECS);
        // The fixed login is never handed out next to a minted one.
        assert_eq!(ice.servers.len(), 1);
        assert_eq!(
            ice.servers[0].urls,
            vec!["stun:turn.example.com:3478".to_string()]
        );
        assert!(ice.servers[0].username.is_none());
    }

    #[test]
    fn minted_login_matches_coturn_rest_api() {
        let turn = TurnConfig::new(
            vec!["turn:turn.example.com:3478".into()],
            "test-turn-secret-0123456789".into(),
            3600,
        );
        let user = Uuid::parse_str("0190a3b4-0000-7000-8000-000000000001").unwrap();
        let server = turn.credential_for(user, 1_767_222_000);
        assert_eq!(
            server.username.as_deref(),
            Some("1767225600:0190a3b4-0000-7000-8000-000000000001")
        );
        // printf '%s' '<username>' | openssl dgst -sha1 -hmac '<secret>' -binary | base64
        assert_eq!(
            server.credential.as_deref(),
            Some("NjQrQoU4nZ30gF3r8irb5dbc+2c=")
        );
    }

    #[test]
    fn the_secret_stays_out_of_debug_output() {
        let turn = TurnConfig::new(vec![], "test-turn-secret-0123456789".into(), 3600);
        assert!(!format!("{turn:?}").contains("0123456789"));
    }

    #[test]
    fn a_secret_without_urls_leaves_turn_off() {
        let ice = config(&[("TURN_SECRET", "test-turn-secret-0123456789")]).expect("config");
        assert!(ice.turn.is_none());
        assert_eq!(ice.servers[0].urls, vec![DEFAULT_STUN_URL.to_string()]);
    }

    #[test]
    fn misconfigured_turn_is_refused() {
        assert!(
            config(&[
                ("TURN_URLS", "turn:turn.example.com:3478"),
                ("TURN_SECRET", "short"),
            ])
            .is_err()
        );
        assert!(
            config(&[
                ("TURN_URLS", "https://turn.example.com"),
                ("TURN_SECRET", "test-turn-secret-0123456789"),
            ])
            .is_err()
        );
        assert!(
            config(&[
                ("TURN_URLS", "turn:turn.example.com:3478"),
                ("TURN_SECRET", "test-turn-secret-0123456789"),
                ("TURN_CREDENTIAL_TTL_SECS", "30"),
            ])
            .is_err()
        );
    }

    #[test]
    fn broken_custom_ice_servers_fall_back_to_the_defaults() {
        let ice = config(&[("ICE_SERVERS_JSON", "{not json")]).expect("config");
        assert_eq!(ice.servers[0].urls, vec![DEFAULT_STUN_URL.to_string()]);
    }

    #[test]
    fn a_fixed_login_still_works_for_hosted_turn() {
        let ice = config(&[
            ("TURN_URLS", "turn:relay.example.net:3478"),
            ("TURN_USERNAME", "hosted-user"),
            ("TURN_CREDENTIAL", "hosted-pass"),
        ])
        .expect("config");
        assert!(ice.turn.is_none());
        assert_eq!(ice.servers.len(), 2);
        assert_eq!(ice.servers[1].username.as_deref(), Some("hosted-user"));
    }

    #[test]
    fn custom_ice_servers_replace_stun_but_keep_minted_turn() {
        let ice = config(&[
            (
                "ICE_SERVERS_JSON",
                r#"[{"urls":["stun:stun.example.org:3478"]}]"#,
            ),
            ("TURN_URLS", "turn:turn.example.com:3478"),
            ("TURN_SECRET", "test-turn-secret-0123456789"),
        ])
        .expect("config");
        assert!(ice.turn.is_some());
        assert_eq!(
            ice.servers[0].urls,
            vec!["stun:stun.example.org:3478".to_string()]
        );
    }
}
