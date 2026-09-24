//! HTTP/2 APNs client (token auth with .p8 / ES256 JWT).

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use jsonwebtoken::{Algorithm, EncodingKey, Header, encode};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::RwLock;

/// Sandbox vs production APNs host.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApnsEnvironment {
    Sandbox,
    Production,
}

impl ApnsEnvironment {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Sandbox => "sandbox",
            Self::Production => "production",
        }
    }

    pub fn parse(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().as_str() {
            "production" | "prod" => Self::Production,
            _ => Self::Sandbox,
        }
    }

    fn host(self) -> &'static str {
        match self {
            Self::Sandbox => "https://api.sandbox.push.apple.com",
            Self::Production => "https://api.push.apple.com",
        }
    }
}

/// Token-auth credentials for APNs.
#[derive(Debug, Clone)]
pub struct ApnsConfig {
    /// Path to AuthKey_XXX.p8 (PEM EC private key). Mutually exclusive with `key_pem`.
    pub key_path: Option<PathBuf>,
    /// Inline PEM contents (e.g. from secret env). Takes precedence over `key_path` when set.
    pub key_pem: Option<String>,
    pub key_id: String,
    pub team_id: String,
    /// Bundle id / apns-topic (e.g. com.example.shroud).
    pub topic: String,
}

/// `apns-push-type`: what the device does with the push.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApnsPushType {
    /// Shown to the user (alert, sound, badge); may pass through the notification extension.
    Alert,
    /// Wakes the app silently (`content-available`).
    Background,
    /// PushKit incoming call; sent to `<topic>.voip`.
    Voip,
}

impl ApnsPushType {
    fn header(self) -> &'static str {
        match self {
            Self::Alert => "alert",
            Self::Background => "background",
            Self::Voip => "voip",
        }
    }
}

/// One APNs request: the payload plus the headers that steer delivery.
#[derive(Debug, Clone)]
pub struct ApnsRequest<'a> {
    pub push_type: ApnsPushType,
    /// 10 = now; 5 = when convenient for the device's battery.
    pub priority: u8,
    /// Unix time after which APNs stops trying; `None` lets APNs pick.
    pub expiration: Option<u64>,
    /// Replaces an earlier notification with the same id on the device.
    pub collapse_id: Option<&'a str>,
    pub payload: &'a Value,
}

/// Result of a single device push attempt.
#[derive(Debug)]
pub enum ApnsSendOutcome {
    /// HTTP 200 — accepted by APNs.
    Accepted { apns_id: Option<String> },
    /// Permanent failure — drop the stored device token.
    InvalidToken { reason: String, status: u16 },
    /// Transient or other failure — keep token, log and move on.
    Failed { reason: String, status: u16 },
}

#[derive(Debug, Serialize, Deserialize)]
struct ApnsClaims {
    iss: String,
    iat: u64,
}

struct CachedJwt {
    token: String,
    /// Instant after which we should refresh (before Apple's 1h max).
    refresh_after: Instant,
}

/// Shared APNs HTTP/2 client with JWT caching.
#[derive(Clone)]
pub struct ApnsClient {
    inner: Arc<ApnsClientInner>,
}

struct ApnsClientInner {
    key_id: String,
    team_id: String,
    topic: String,
    encoding_key: EncodingKey,
    http: reqwest::Client,
    jwt: RwLock<Option<CachedJwt>>,
}

impl ApnsClient {
    /// Builds a client from config (reads .p8 or inline PEM).
    pub fn new(config: ApnsConfig) -> Result<Self, String> {
        let pem_bytes = load_pem(&config)?;
        let encoding_key = EncodingKey::from_ec_pem(&pem_bytes)
            .map_err(|err| format!("invalid APNs EC private key (expected PKCS#8 PEM): {err}"))?;

        let http = reqwest::Client::builder()
            .use_rustls_tls()
            .http2_adaptive_window(true)
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(|err| format!("APNs HTTP client build failed: {err}"))?;

        Ok(Self {
            inner: Arc::new(ApnsClientInner {
                key_id: config.key_id,
                team_id: config.team_id,
                topic: config.topic,
                encoding_key,
                http,
                jwt: RwLock::new(None),
            }),
        })
    }

    pub fn topic(&self) -> &str {
        &self.inner.topic
    }

    pub fn key_id(&self) -> &str {
        &self.inner.key_id
    }

    /// Sends one push to one device token.
    pub async fn send(
        &self,
        device_token: &str,
        environment: ApnsEnvironment,
        request: ApnsRequest<'_>,
    ) -> ApnsSendOutcome {
        let token = device_token.trim();
        if token.is_empty() {
            return ApnsSendOutcome::InvalidToken {
                reason: "empty token".into(),
                status: 0,
            };
        }

        let bearer = match self.bearer_token().await {
            Ok(t) => t,
            Err(err) => {
                return ApnsSendOutcome::Failed {
                    reason: format!("jwt: {err}"),
                    status: 0,
                };
            }
        };

        let url = format!("{}/3/device/{}", environment.host(), token);
        let topic = match request.push_type {
            ApnsPushType::Voip => format!("{}.voip", self.inner.topic),
            _ => self.inner.topic.clone(),
        };
        let mut builder = self
            .inner
            .http
            .post(&url)
            .header("authorization", format!("bearer {bearer}"))
            .header("apns-topic", topic)
            .header("apns-push-type", request.push_type.header())
            .header("apns-priority", request.priority.to_string())
            .header("content-type", "application/json");
        if let Some(expiration) = request.expiration {
            builder = builder.header("apns-expiration", expiration.to_string());
        }
        if let Some(collapse_id) = request.collapse_id {
            builder = builder.header("apns-collapse-id", collapse_id);
        }
        let response = builder.json(request.payload).send().await;

        let response = match response {
            Ok(r) => r,
            Err(err) => {
                // Without the URL: its path is the device token.
                return ApnsSendOutcome::Failed {
                    reason: format!("transport: {}", err.without_url()),
                    status: 0,
                };
            }
        };

        let status = response.status().as_u16();
        let apns_id = response
            .headers()
            .get("apns-id")
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);

        if status == 200 {
            return ApnsSendOutcome::Accepted { apns_id };
        }

        let body = response.text().await.unwrap_or_default();
        let reason = parse_apns_reason(&body).unwrap_or_else(|| {
            if body.is_empty() {
                format!("http_{status}")
            } else {
                body.chars().take(200).collect()
            }
        });

        if is_invalid_token_reason(&reason) || status == 410 {
            ApnsSendOutcome::InvalidToken { reason, status }
        } else {
            ApnsSendOutcome::Failed { reason, status }
        }
    }

    async fn bearer_token(&self) -> Result<String, String> {
        {
            let guard = self.inner.jwt.read().await;
            if let Some(cached) = guard.as_ref()
                && Instant::now() < cached.refresh_after
            {
                return Ok(cached.token.clone());
            }
        }

        let mut guard = self.inner.jwt.write().await;
        if let Some(cached) = guard.as_ref()
            && Instant::now() < cached.refresh_after
        {
            return Ok(cached.token.clone());
        }

        let token = mint_jwt(
            &self.inner.team_id,
            &self.inner.key_id,
            &self.inner.encoding_key,
        )?;
        // Apple allows up to 1 hour; refresh after 50 minutes.
        *guard = Some(CachedJwt {
            token: token.clone(),
            refresh_after: Instant::now() + Duration::from_secs(50 * 60),
        });
        Ok(token)
    }
}

fn load_pem(config: &ApnsConfig) -> Result<Vec<u8>, String> {
    if let Some(ref pem) = config.key_pem {
        let trimmed = pem.trim();
        if trimmed.is_empty() {
            return Err("APNS_KEY_PEM is empty".into());
        }
        return Ok(trimmed.as_bytes().to_vec());
    }
    let path = config
        .key_path
        .as_ref()
        .ok_or_else(|| "APNS key path or PEM required".to_string())?;
    std::fs::read(path).map_err(|err| format!("read APNs key at {}: {err}", path.display()))
}

fn mint_jwt(team_id: &str, key_id: &str, encoding_key: &EncodingKey) -> Result<String, String> {
    let iat = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|err| format!("system clock error: {err}"))?
        .as_secs();
    let mut header = Header::new(Algorithm::ES256);
    header.kid = Some(key_id.to_string());
    let claims = ApnsClaims {
        iss: team_id.to_string(),
        iat,
    };
    encode(&header, &claims, encoding_key).map_err(|err| format!("APNs JWT encode failed: {err}"))
}

fn parse_apns_reason(body: &str) -> Option<String> {
    let value: Value = serde_json::from_str(body).ok()?;
    value
        .get("reason")
        .and_then(|r| r.as_str())
        .map(str::to_string)
}

fn is_invalid_token_reason(reason: &str) -> bool {
    // Only permanent *device* token failures — do not drop tokens on provider JWT errors.
    matches!(
        reason,
        "BadDeviceToken" | "Unregistered" | "DeviceTokenNotForTopic" | "ExpiredToken"
    )
}

/// Apple refused the *server's* setup, not the device: its key (or the key's environment or
/// team), or the topic. Every push fails the same way until `APNS_*` is fixed, so it is
/// reported as that rather than as Apple being unreachable. A key that cannot be read or
/// signed with fails before sending (`jwt: …`).
pub(super) fn is_provider_config_reason(reason: &str) -> bool {
    reason.starts_with("jwt: ")
        || matches!(
            reason,
            "InvalidProviderToken"
                | "ExpiredProviderToken"
                | "MissingProviderToken"
                | "BadEnvironmentKeyInToken"
                | "UnrelatedKeyIdInToken"
                | "BadCertificate"
                | "BadCertificateEnvironment"
                | "BadTopic"
                | "MissingTopic"
                | "TopicDisallowed"
                | "Forbidden"
        )
}

/// Loads APNs config from env when complete; otherwise `None` (registration still works).
///
/// Accepts either `APNS_KEY_PATH` or `APNS_KEY_PEM` plus KEY_ID, TEAM_ID, TOPIC.
pub fn apns_config_from_env() -> Option<ApnsConfig> {
    let key_pem = std::env::var("APNS_KEY_PEM")
        .ok()
        .filter(|s| !s.trim().is_empty());
    let key_path = std::env::var("APNS_KEY_PATH")
        .ok()
        .filter(|s| !s.is_empty())
        .map(PathBuf::from);

    if key_pem.is_none() && key_path.is_none() {
        return None;
    }

    let key_id = std::env::var("APNS_KEY_ID")
        .ok()
        .filter(|s| !s.is_empty())?;
    let team_id = std::env::var("APNS_TEAM_ID")
        .ok()
        .filter(|s| !s.is_empty())?;
    let topic = std::env::var("APNS_TOPIC").ok().filter(|s| !s.is_empty())?;

    Some(ApnsConfig {
        key_path,
        key_pem,
        key_id,
        team_id,
        topic,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use jsonwebtoken::{DecodingKey, Validation, decode};

    /// Minimal valid-looking EC P-256 PKCS#8 PEM for unit tests (not a production secret).
    /// Generated for tests only.
    fn test_p256_pem() -> &'static str {
        // openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -outform PEM
        // Fixed test key — never use outside unit tests.
        r#"-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgS8p+Y0rQ3L9k7qVx
2m1f0Zb3yN4p5r6s7t8u9v0w1x2hRANCAAQ8k1m2n3o4p5q6r7s8t9u0v1w2x3y4
z5A6B7C8D9E0F1G2H3I4J5K6L7M8N9O0P1Q2R3S4T5U6V7W8X9Y0Z1
-----END PRIVATE KEY-----"#
    }

    // Use a real generated key via openssl if available; fallback unit tests for helpers only.
    #[test]
    fn environment_host_and_parse() {
        assert_eq!(
            ApnsEnvironment::parse("sandbox").host(),
            "https://api.sandbox.push.apple.com"
        );
        assert_eq!(
            ApnsEnvironment::parse("production").host(),
            "https://api.push.apple.com"
        );
        assert_eq!(ApnsEnvironment::parse("prod").as_str(), "production");
    }

    #[test]
    fn invalid_token_reasons() {
        assert!(is_invalid_token_reason("BadDeviceToken"));
        assert!(is_invalid_token_reason("Unregistered"));
        // The server's own setup is never the device token's fault, and the other way round.
        assert!(is_provider_config_reason("BadEnvironmentKeyInToken"));
        assert!(is_provider_config_reason("InvalidProviderToken"));
        assert!(is_provider_config_reason("jwt: invalid key"));
        assert!(!is_provider_config_reason("BadDeviceToken"));
        assert!(!is_provider_config_reason("ServiceUnavailable"));
        assert!(!is_invalid_token_reason("BadEnvironmentKeyInToken"));
        assert!(!is_invalid_token_reason("InternalServerError"));
        assert!(!is_invalid_token_reason("TooManyRequests"));
    }

    #[test]
    fn parse_reason_from_body() {
        assert_eq!(
            parse_apns_reason(r#"{"reason":"BadDeviceToken"}"#).as_deref(),
            Some("BadDeviceToken")
        );
        assert!(parse_apns_reason("not-json").is_none());
    }

    #[test]
    fn mint_jwt_with_openssl_generated_key_if_possible() {
        // Generate ephemeral P-256 key with openssl when present so we don't ship a fake PEM.
        let output = std::process::Command::new("openssl")
            .args([
                "genpkey",
                "-algorithm",
                "EC",
                "-pkeyopt",
                "ec_paramgen_curve:P-256",
            ])
            .output();

        let Ok(output) = output else {
            eprintln!("skipping mint_jwt test: openssl not available");
            let _ = test_p256_pem;
            return;
        };
        if !output.status.success() {
            eprintln!("skipping mint_jwt test: openssl genpkey failed");
            return;
        }

        let pem = output.stdout;
        let encoding_key = EncodingKey::from_ec_pem(&pem).expect("encode key");
        let token = mint_jwt("TEAMID1234", "KEYID12345", &encoding_key).expect("jwt");
        assert_eq!(token.split('.').count(), 3);

        let header = jsonwebtoken::decode_header(&token).expect("header");
        assert_eq!(header.alg, Algorithm::ES256);
        assert_eq!(header.kid.as_deref(), Some("KEYID12345"));

        // Round-trip with public key derived via openssl.
        let mut pub_cmd = std::process::Command::new("openssl");
        pub_cmd
            .args(["pkey", "-pubout"])
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null());
        let mut child = pub_cmd.spawn().expect("openssl pkey");
        use std::io::Write;
        child
            .stdin
            .as_mut()
            .unwrap()
            .write_all(&pem)
            .expect("write pem");
        let pub_out = child.wait_with_output().expect("openssl pkey wait");
        if !pub_out.status.success() {
            eprintln!("skipping jwt verify: openssl pkey -pubout failed");
            return;
        }
        let decoding_key = DecodingKey::from_ec_pem(&pub_out.stdout).expect("decode key");
        let mut validation = Validation::new(Algorithm::ES256);
        validation.set_required_spec_claims(&["iss", "iat"]);
        validation.validate_exp = false;
        let data = decode::<ApnsClaims>(&token, &decoding_key, &validation).expect("decode jwt");
        assert_eq!(data.claims.iss, "TEAMID1234");
    }
}
