//! Web Push to browsers and Android: RFC 8291 payload encryption, RFC 8292 VAPID, RFC 8030
//! delivery.
//!
//! Human: The browser's push service (FCM, Mozilla, Apple, WNS) relays the push; it sees the
//! endpoint and ciphertext only. The payload is encrypted to the browser's own key, so naming
//! the sender in it tells the push service nothing. The Android app is reached the same way
//! through UnifiedPush: a distributor app the user chose (ntfy, Sunup, …) hands it an endpoint
//! on its push server, and that server relays the same ciphertext.
//! Agent: ring for ECDH P-256 / HKDF / AES-128-GCM / ES256; endpoints outside the client's host
//! policy are refused (the server would otherwise POST to any URL a client names): browsers
//! the push-service allowlist, Android [`android_endpoint`]. Google's hosts are never an
//! Android endpoint.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD as B64URL};
use ring::aead::{AES_128_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use ring::agreement::{ECDH_P256, EphemeralPrivateKey, UnparsedPublicKey, agree_ephemeral};
use ring::hkdf::{HKDF_SHA256, KeyType, Salt};
use ring::rand::{SecureRandom, SystemRandom};
use ring::signature::{ECDSA_P256_SHA256_FIXED_SIGNING, EcdsaKeyPair, KeyPair};
use sqlx::PgPool;
use tokio::sync::RwLock;

/// Record size advertised in the aes128gcm header. One record holds every payload we send.
const RECORD_SIZE: u32 = 4096;
/// Largest plaintext that fits one record (RFC 8291 caps payloads at 3993 bytes).
pub const MAX_PAYLOAD_BYTES: usize = 3993;
/// `server_keys.name` of the generated VAPID key (PKCS#8).
const VAPID_KEY_NAME: &str = "vapid_p256_pkcs8";
/// VAPID tokens may live 24 hours; renew well before.
const VAPID_TOKEN_LIFETIME_SECS: u64 = 12 * 60 * 60;
const VAPID_TOKEN_RENEW_BEFORE_SECS: u64 = 60 * 60;

/// Push services browsers subscribe with. A subscription must point at one of these (or a host
/// from `WEB_PUSH_ALLOWED_HOSTS`): anything else would let a client aim the server's requests
/// at an arbitrary address.
const DEFAULT_PUSH_HOSTS: &[&str] = &[
    "fcm.googleapis.com",
    "push.services.mozilla.com",
    "push.apple.com",
    "notify.windows.com",
];

/// UnifiedPush servers an Android subscription may point at without any configuration: the
/// public ntfy, Conversations' push server, and Mozilla's autopush (which Sunup uses). More
/// come from `UNIFIEDPUSH_ALLOWED_HOSTS`, or any public host with `UNIFIEDPUSH_PUBLIC_HOSTS`.
pub const UNIFIEDPUSH_DEFAULT_HOSTS: &[&str] = &[
    "ntfy.sh",
    "up.conversations.im",
    "push.services.mozilla.com",
];

/// Never an Android endpoint, whatever the configuration says: an "embedded FCM distributor"
/// would hand the app a Google endpoint, and the Android app is Google-free by decision
/// (`android-port-specs/00-plan.md`, decision record 1).
const REFUSED_ANDROID_HOST_SUFFIXES: &[&str] = &["googleapis.com"];

/// Upper bound for the DNS lookup of a public-host-mode distributor.
const DNS_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, thiserror::Error)]
pub enum WebPushError {
    #[error("invalid subscription key")]
    BadKey,
    #[error("payload too large")]
    TooLarge,
    #[error("crypto failure")]
    Crypto,
}

/// The server's VAPID identity: an ECDSA P-256 key the browser pinned at subscribe time.
pub struct VapidKey {
    key_pair: EcdsaKeyPair,
    rng: SystemRandom,
}

impl std::fmt::Debug for VapidKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("VapidKey")
            .field("public_key", &self.public_key_b64url())
            .finish_non_exhaustive()
    }
}

impl VapidKey {
    /// A fresh key, returned with its PKCS#8 encoding for storage.
    pub fn generate() -> Result<(Self, Vec<u8>), String> {
        let rng = SystemRandom::new();
        let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, &rng)
            .map_err(|_| "VAPID key generation failed".to_string())?;
        let bytes = pkcs8.as_ref().to_vec();
        Ok((Self::from_pkcs8(&bytes)?, bytes))
    }

    pub fn from_pkcs8(pkcs8: &[u8]) -> Result<Self, String> {
        let rng = SystemRandom::new();
        let key_pair = EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, pkcs8, &rng)
            .map_err(|err| format!("invalid VAPID PKCS#8 key: {err}"))?;
        Ok(Self { key_pair, rng })
    }

    /// The `web-push generate-vapid-keys` format: base64url private scalar and public point.
    pub fn from_raw_b64url(private: &str, public: &str) -> Result<Self, String> {
        let private = B64URL
            .decode(private.trim().trim_end_matches('='))
            .map_err(|_| "WEB_PUSH_VAPID_PRIVATE_KEY is not base64url".to_string())?;
        let public = B64URL
            .decode(public.trim().trim_end_matches('='))
            .map_err(|_| "WEB_PUSH_VAPID_PUBLIC_KEY is not base64url".to_string())?;
        let rng = SystemRandom::new();
        let key_pair = EcdsaKeyPair::from_private_key_and_public_key(
            &ECDSA_P256_SHA256_FIXED_SIGNING,
            &private,
            &public,
            &rng,
        )
        .map_err(|err| format!("VAPID key pair rejected: {err}"))?;
        Ok(Self { key_pair, rng })
    }

    /// Uncompressed public point, the `applicationServerKey` browsers subscribe with.
    pub fn public_key(&self) -> &[u8] {
        self.key_pair.public_key().as_ref()
    }

    pub fn public_key_b64url(&self) -> String {
        B64URL.encode(self.public_key())
    }

    /// ES256 JWT for one push service origin (RFC 8292).
    fn token(&self, audience: &str, subject: &str, expires_at: u64) -> Result<String, String> {
        let header = B64URL.encode(br#"{"typ":"JWT","alg":"ES256"}"#);
        let claims = serde_json::json!({ "aud": audience, "exp": expires_at, "sub": subject });
        let claims = B64URL.encode(claims.to_string());
        let signing_input = format!("{header}.{claims}");
        let signature = self
            .key_pair
            .sign(&self.rng, signing_input.as_bytes())
            .map_err(|_| "VAPID signing failed".to_string())?;
        Ok(format!(
            "{signing_input}.{}",
            B64URL.encode(signature.as_ref())
        ))
    }
}

/// The key from `WEB_PUSH_VAPID_PRIVATE_KEY` / `WEB_PUSH_VAPID_PUBLIC_KEY`, else the one this
/// server generated on first start (kept in `server_keys`, shared by every replica).
///
/// Human: Changing the key orphans every browser subscription — they were made for the old
/// public key — so it is generated once and never rotated silently.
pub async fn load_vapid_key(pool: &PgPool) -> Result<VapidKey, String> {
    let private = std::env::var("WEB_PUSH_VAPID_PRIVATE_KEY")
        .ok()
        .filter(|value| !value.trim().is_empty());
    let public = std::env::var("WEB_PUSH_VAPID_PUBLIC_KEY")
        .ok()
        .filter(|value| !value.trim().is_empty());
    match (private, public) {
        (Some(private), Some(public)) => return VapidKey::from_raw_b64url(&private, &public),
        (None, None) => {}
        _ => {
            return Err(
                "set both WEB_PUSH_VAPID_PRIVATE_KEY and WEB_PUSH_VAPID_PUBLIC_KEY, or neither"
                    .into(),
            );
        }
    }

    let (_, generated) = VapidKey::generate()?;
    // Human: Two replicas starting together both generate; the first insert wins and both
    // read back the same row.
    sqlx::query(
        r#"
        INSERT INTO server_keys (name, secret) VALUES ($1, $2)
        ON CONFLICT (name) DO NOTHING
        "#,
    )
    .bind(VAPID_KEY_NAME)
    .bind(&generated)
    .execute(pool)
    .await
    .map_err(|err| format!("store VAPID key failed: {err}"))?;
    let stored: Vec<u8> = sqlx::query_scalar(r#"SELECT secret FROM server_keys WHERE name = $1"#)
        .bind(VAPID_KEY_NAME)
        .fetch_one(pool)
        .await
        .map_err(|err| format!("load VAPID key failed: {err}"))?;
    VapidKey::from_pkcs8(&stored)
}

/// A push subscription as stored.
#[derive(Debug, Clone)]
pub struct WebSubscription {
    pub endpoint: String,
    pub p256dh: Vec<u8>,
    pub auth: Vec<u8>,
    /// Who holds it, which decides the host policy it is sent under.
    pub client: SubscriptionClient,
}

/// Who holds a Web Push subscription (`web_push_subscriptions.client`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubscriptionClient {
    /// A browser's service worker: it must show a notification for every push.
    Browser,
    /// The Android app through a UnifiedPush distributor: it decides what to show, so it also
    /// gets rings in front, `call_ended` and `read`.
    Android,
}

impl SubscriptionClient {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Browser => "browser",
            Self::Android => "android",
        }
    }

    /// The stored or requested value; `None` for anything else.
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "browser" => Some(Self::Browser),
            "android" => Some(Self::Android),
            _ => None,
        }
    }

    /// A column value as stored. Rows from before migration 028 are browsers.
    pub fn from_column(value: Option<&str>) -> Self {
        value.and_then(Self::parse).unwrap_or(Self::Browser)
    }
}

/// Where Android subscriptions may point (env `UNIFIEDPUSH_*`, read by `config`).
///
/// Human: A UnifiedPush endpoint is on whatever push server the user's distributor uses — the
/// public ntfy, or someone's own. The built-in list covers the public ones; an operator adds
/// their own with `UNIFIEDPUSH_ALLOWED_HOSTS`, or opens it to every public host. Browsers keep
/// their own list (`WEB_PUSH_ALLOWED_HOSTS`).
#[derive(Debug, Clone, Default)]
pub struct UnifiedPushPolicy {
    /// `UNIFIEDPUSH_ALLOWED_HOSTS`: host suffixes beyond [`UNIFIEDPUSH_DEFAULT_HOSTS`].
    pub allowed_hosts: Vec<String>,
    /// `UNIFIEDPUSH_PUBLIC_HOSTS=true`: any `https` host on port 443 whose name is public and
    /// whose every address is globally routable, contacted only at the addresses checked.
    pub public_hosts: bool,
    /// `UNIFIEDPUSH_ALLOW_LOCAL_HTTP=true`, tests only: `http://` to loopback (an end-to-end
    /// run's local ntfy). Never set in the compose files.
    pub allow_local_http: bool,
    /// Fixed addresses for names, used instead of DNS in public-host mode, like `reqwest`'s
    /// `resolve`. Tests stand in for DNS with it; nothing reads it from the environment.
    pub resolve_overrides: HashMap<String, Vec<IpAddr>>,
}

/// How the server reaches an endpoint its client's policy accepted.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EndpointRoute {
    /// A listed push service, contacted by name.
    Listed,
    /// Public-host mode: contacted only at addresses checked to be globally routable.
    PublicHost,
    /// `UNIFIEDPUSH_ALLOW_LOCAL_HTTP`: plain `http` to loopback.
    LocalHttp,
}

/// How urgently the push service should wake the device (RFC 8030 §5.3).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Urgency {
    Normal,
    High,
}

impl Urgency {
    fn as_str(self) -> &'static str {
        match self {
            Self::Normal => "normal",
            Self::High => "high",
        }
    }
}

/// Delivery options for one push.
#[derive(Debug, Clone)]
pub struct WebPushOptions {
    pub ttl_secs: u32,
    pub urgency: Urgency,
    /// Replaces a queued, undelivered push with the same topic (≤ 32 base64url characters).
    pub topic: Option<String>,
}

#[derive(Debug)]
pub enum WebPushOutcome {
    Accepted,
    /// 404/410: the subscription is gone — drop it.
    Gone {
        status: u16,
    },
    Failed {
        status: u16,
        reason: String,
    },
}

/// Sends Web Push requests with a cached VAPID token per push-service origin.
#[derive(Clone)]
pub struct WebPushClient {
    inner: Arc<WebPushInner>,
}

struct WebPushInner {
    vapid: VapidKey,
    subject: String,
    allowed_hosts: Vec<String>,
    unifiedpush: UnifiedPushPolicy,
    http: reqwest::Client,
    /// For public-host-mode endpoints: resolves through `resolver`, never through a proxy.
    public_http: reqwest::Client,
    resolver: Arc<PublicResolver>,
    tokens: RwLock<HashMap<String, (String, u64)>>,
}

impl WebPushClient {
    pub fn new(
        vapid: VapidKey,
        subject: String,
        allowed_hosts: Vec<String>,
        unifiedpush: UnifiedPushPolicy,
    ) -> Result<Self, String> {
        let http = reqwest::Client::builder()
            .use_rustls_tls()
            .timeout(Duration::from_secs(15))
            // A push service answers directly; a redirect would be a way around the allowlist.
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|err| format!("web push HTTP client build failed: {err}"))?;
        let resolver = Arc::new(PublicResolver {
            overrides: unifiedpush.resolve_overrides.clone(),
        });
        // Human: The address check and the connection share one lookup (this resolver), so a
        // name cannot pass with a public address and then connect to a private one. A proxy
        // would look the name up again itself, so this client never uses one.
        let public_http = reqwest::Client::builder()
            .use_rustls_tls()
            .timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::none())
            .no_proxy()
            .dns_resolver(resolver.clone())
            .build()
            .map_err(|err| format!("unifiedpush HTTP client build failed: {err}"))?;
        Ok(Self {
            inner: Arc::new(WebPushInner {
                vapid,
                subject,
                allowed_hosts,
                unifiedpush,
                http,
                public_http,
                resolver,
                tokens: RwLock::new(HashMap::new()),
            }),
        })
    }

    pub fn public_key_b64url(&self) -> String {
        self.inner.vapid.public_key_b64url()
    }

    /// `endpoint` as it would be contacted, when it is on an allowed browser push service.
    pub fn allowed_endpoint(&self, endpoint: &str) -> Option<reqwest::Url> {
        allowed_endpoint(endpoint, &self.inner.allowed_hosts)
    }

    /// `endpoint` as it would be contacted, and how, when `client`'s policy allows it.
    fn route(
        &self,
        endpoint: &str,
        client: SubscriptionClient,
    ) -> Option<(reqwest::Url, EndpointRoute)> {
        match client {
            SubscriptionClient::Browser => self
                .allowed_endpoint(endpoint)
                .map(|url| (url, EndpointRoute::Listed)),
            SubscriptionClient::Android => android_endpoint(endpoint, &self.inner.unifiedpush),
        }
    }

    /// `endpoint` as it is stored and contacted, when this server sends to it for `client`.
    ///
    /// Human: A public-host-mode endpoint is resolved now as well, so a name whose addresses
    /// are private is refused at once rather than failing at every send. Each send checks
    /// again: DNS can change.
    pub async fn accept_endpoint(
        &self,
        endpoint: &str,
        client: SubscriptionClient,
    ) -> Option<reqwest::Url> {
        let (url, route) = self.route(endpoint, client)?;
        if route == EndpointRoute::PublicHost {
            self.inner.resolver.addresses(url.host_str()?).await.ok()?;
        }
        Some(url)
    }

    /// Encrypts `payload` to the subscription and posts it.
    pub async fn send(
        &self,
        subscription: &WebSubscription,
        payload: &[u8],
        options: &WebPushOptions,
    ) -> WebPushOutcome {
        let Some((endpoint, route)) = self.route(&subscription.endpoint, subscription.client)
        else {
            return WebPushOutcome::Failed {
                status: 0,
                reason: "endpoint not allowed".into(),
            };
        };
        let http = if route == EndpointRoute::PublicHost {
            &self.inner.public_http
        } else {
            &self.inner.http
        };
        let body = match encrypt(&subscription.p256dh, &subscription.auth, payload) {
            Ok(body) => body,
            Err(err) => {
                return WebPushOutcome::Failed {
                    status: 0,
                    reason: err.to_string(),
                };
            }
        };
        let audience = endpoint.origin().ascii_serialization();
        let token = match self.token_for(&audience).await {
            Ok(token) => token,
            Err(reason) => return WebPushOutcome::Failed { status: 0, reason },
        };

        let mut request = http
            .post(endpoint)
            .header(
                "authorization",
                format!(
                    "vapid t={token}, k={}",
                    self.inner.vapid.public_key_b64url()
                ),
            )
            .header("content-encoding", "aes128gcm")
            .header("content-type", "application/octet-stream")
            .header("ttl", options.ttl_secs.to_string())
            .header("urgency", options.urgency.as_str());
        if let Some(topic) = &options.topic {
            request = request.header("topic", topic);
        }
        let response = match request.body(body).send().await {
            Ok(response) => response,
            Err(err) => {
                // Without the URL: it holds the subscription's secret push token.
                return WebPushOutcome::Failed {
                    status: 0,
                    reason: format!("transport: {}", err.without_url()),
                };
            }
        };
        let status = response.status().as_u16();
        match status {
            200..=299 => WebPushOutcome::Accepted,
            404 | 410 => WebPushOutcome::Gone { status },
            _ => WebPushOutcome::Failed {
                status,
                reason: error_snippet(response).await,
            },
        }
    }

    async fn token_for(&self, audience: &str) -> Result<String, String> {
        let now = unix_now();
        if let Some((token, expires_at)) = self.inner.tokens.read().await.get(audience)
            && *expires_at > now + VAPID_TOKEN_RENEW_BEFORE_SECS
        {
            return Ok(token.clone());
        }
        let expires_at = now + VAPID_TOKEN_LIFETIME_SECS;
        let token = self
            .inner
            .vapid
            .token(audience, &self.inner.subject, expires_at)?;
        self.inner
            .tokens
            .write()
            .await
            .insert(audience.to_string(), (token.clone(), expires_at));
        Ok(token)
    }
}

/// Validates a browser's subscription keys as the push API hands them over.
pub fn parse_subscription_keys(p256dh: &str, auth: &str) -> Option<(Vec<u8>, Vec<u8>)> {
    let p256dh = B64URL.decode(p256dh.trim().trim_end_matches('=')).ok()?;
    let auth = B64URL.decode(auth.trim().trim_end_matches('=')).ok()?;
    if p256dh.len() != 65 || p256dh[0] != 0x04 || auth.len() != 16 {
        return None;
    }
    // Rejects a point that is not on the curve now rather than at every send.
    let rng = SystemRandom::new();
    let probe = EphemeralPrivateKey::generate(&ECDH_P256, &rng).ok()?;
    agree_ephemeral(probe, &UnparsedPublicKey::new(&ECDH_P256, &p256dh), |_| ()).ok()?;
    Some((p256dh, auth))
}

/// `endpoint` as the HTTP client will contact it, when that is `https` on an allowed push
/// service: a domain name (never an IP address), no port, no credentials.
///
/// Human: Checked on the URL the HTTP client parses, not on the string: a split by hand read
/// `https://attacker.example\.fcm.googleapis.com/` as a Google host, and the client, which
/// ends a host at `\` like browsers do, sent the push to `attacker.example`.
/// Agent: store and send `url.as_str()`; the parser lowercases and punycodes the host.
pub fn allowed_endpoint(endpoint: &str, extra_hosts: &[String]) -> Option<reqwest::Url> {
    if endpoint.len() > 1024 {
        return None;
    }
    let url = reqwest::Url::parse(endpoint).ok()?;
    if url.scheme() != "https"
        || url.port().is_some()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return None;
    }
    // `None` for IPv4 and IPv6 literals, however they were written.
    let host = url.domain()?;
    let allowed = |suffix: &str| host == suffix || host.ends_with(&format!(".{suffix}"));
    (DEFAULT_PUSH_HOSTS.iter().any(|suffix| allowed(suffix))
        || extra_hosts.iter().any(|suffix| allowed(suffix)))
    .then_some(url)
}

/// An Android (UnifiedPush) `endpoint` as the HTTP client will contact it, and how, when
/// `policy` allows it: `https` on the default port, a domain name, no credentials, on
/// [`UNIFIEDPUSH_DEFAULT_HOSTS`] or `policy.allowed_hosts` — or, in public-host mode, any
/// public name (its addresses are checked whenever it is resolved). Google's hosts never.
///
/// Human: Checked on the URL the HTTP client parses, as for browsers (see
/// [`allowed_endpoint`]). A trailing dot is refused outright: `fcm.googleapis.com.` is Google.
/// Agent: pure; the address check of public-host mode is [`PublicResolver::addresses`].
pub fn android_endpoint(
    endpoint: &str,
    policy: &UnifiedPushPolicy,
) -> Option<(reqwest::Url, EndpointRoute)> {
    if endpoint.len() > 1024 {
        return None;
    }
    let url = reqwest::Url::parse(endpoint).ok()?;
    if !url.username().is_empty() || url.password().is_some() {
        return None;
    }
    if url.scheme() == "http" {
        let loopback = match url.domain() {
            Some(name) => name == "localhost",
            None => url
                .host_str()
                .map(|host| host.trim_start_matches('[').trim_end_matches(']'))
                .and_then(|host| host.parse::<IpAddr>().ok())
                .is_some_and(|ip| ip.is_loopback()),
        };
        return (policy.allow_local_http && loopback).then_some((url, EndpointRoute::LocalHttp));
    }
    if url.scheme() != "https" || url.port().is_some() {
        return None;
    }
    // `None` for IPv4 and IPv6 literals, however they were written.
    let host = url.domain()?;
    let under = |suffix: &str| host == suffix || host.ends_with(&format!(".{suffix}"));
    if host.ends_with('.') || REFUSED_ANDROID_HOST_SUFFIXES.iter().any(|s| under(s)) {
        return None;
    }
    if UNIFIEDPUSH_DEFAULT_HOSTS.iter().any(|s| under(s))
        || policy.allowed_hosts.iter().any(|s| under(s))
    {
        return Some((url, EndpointRoute::Listed));
    }
    let public = policy.public_hosts
        && crate::link_relay::normalized_host(host).is_ok_and(|name| name == host)
        && crate::link_relay::is_public_name(host);
    public.then_some((url, EndpointRoute::PublicHost))
}

/// Resolves public-host-mode distributor names: every address must be globally routable, and
/// the HTTP client connects to exactly the addresses checked (it is that client's resolver).
#[derive(Debug, Default)]
pub struct PublicResolver {
    overrides: HashMap<String, Vec<IpAddr>>,
}

impl PublicResolver {
    /// The addresses `host` may be contacted at, or why not.
    pub async fn addresses(&self, host: &str) -> Result<Vec<IpAddr>, &'static str> {
        lookup_public(host.to_string(), self.overrides.get(host).cloned()).await
    }
}

impl reqwest::dns::Resolve for PublicResolver {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        let host = name.as_str().to_string();
        let pinned = self.overrides.get(&host).cloned();
        Box::pin(async move {
            let addresses = lookup_public(host, pinned).await.map_err(
                |reason| -> Box<dyn std::error::Error + Send + Sync> {
                    Box::new(std::io::Error::other(reason))
                },
            )?;
            // Port 0: the client puts in the URL's (443).
            let addrs: reqwest::dns::Addrs = Box::new(
                addresses
                    .into_iter()
                    .map(|ip| SocketAddr::new(ip, 0))
                    .collect::<Vec<_>>()
                    .into_iter(),
            );
            Ok(addrs)
        })
    }
}

async fn lookup_public(
    host: String,
    pinned: Option<Vec<IpAddr>>,
) -> Result<Vec<IpAddr>, &'static str> {
    let found = match pinned {
        Some(addresses) => addresses,
        None => {
            match tokio::time::timeout(DNS_TIMEOUT, tokio::net::lookup_host((host.as_str(), 443)))
                .await
            {
                Ok(Ok(found)) => found.map(|address| address.ip()).collect(),
                Ok(Err(_)) | Err(_) => return Err("unresolvable"),
            }
        }
    };
    public_addresses(found)
}

/// All of `found` when every one is globally routable. A name with one private address is
/// refused whole: which one a connection would get is not ours to choose.
fn public_addresses(found: Vec<IpAddr>) -> Result<Vec<IpAddr>, &'static str> {
    if found.is_empty() {
        return Err("unresolvable");
    }
    if found
        .iter()
        .any(|ip| !crate::link_relay::is_globally_routable(*ip))
    {
        return Err("not public");
    }
    Ok(found)
}

/// The start of a push service's error answer, for logs and the test screen. Reads at most
/// a few hundred bytes: the body is the service's to size.
async fn error_snippet(mut response: reqwest::Response) -> String {
    const LIMIT: usize = 512;
    let mut bytes = Vec::new();
    while bytes.len() < LIMIT {
        match response.chunk().await {
            Ok(Some(chunk)) => bytes.extend_from_slice(&chunk),
            _ => break,
        }
    }
    bytes.truncate(LIMIT);
    String::from_utf8_lossy(&bytes).chars().take(200).collect()
}

/// `WEB_PUSH_ALLOWED_HOSTS`: extra push-service host suffixes, comma separated.
pub fn allowed_hosts_from_env() -> Vec<String> {
    host_suffixes(&std::env::var("WEB_PUSH_ALLOWED_HOSTS").unwrap_or_default())
}

/// A comma-separated host-suffix list as the policies compare it: trimmed, lowercase, no
/// leading dot, no empty entries.
pub fn host_suffixes(raw: &str) -> Vec<String> {
    raw.split(',')
        .map(|host| host.trim().trim_start_matches('.').to_ascii_lowercase())
        .filter(|host| !host.is_empty())
        .collect()
}

/// `WEB_PUSH_SUBJECT`, else the public web URL, else a placeholder contact.
///
/// Human: Push services use the subject to reach the operator about a misbehaving sender, and
/// Apple's refuses a token without one.
pub fn subject_from_env() -> String {
    let explicit = std::env::var("WEB_PUSH_SUBJECT")
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| value.starts_with("mailto:") || value.starts_with("https://"));
    if let Some(subject) = explicit {
        return subject;
    }
    std::env::var("WEB_PUBLIC_URL")
        .ok()
        .and_then(|value| reqwest::Url::parse(value.trim()).ok())
        .filter(|url| url.scheme() == "https")
        .map(|url| url.origin().ascii_serialization())
        .unwrap_or_else(|| "mailto:push@shroud.invalid".into())
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Encrypts `plaintext` for a subscription (`aes128gcm`, one record, fresh salt and key).
pub fn encrypt(
    ua_public: &[u8],
    auth_secret: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, WebPushError> {
    if ua_public.len() != 65 || auth_secret.len() != 16 {
        return Err(WebPushError::BadKey);
    }
    if plaintext.len() > MAX_PAYLOAD_BYTES {
        return Err(WebPushError::TooLarge);
    }
    let rng = SystemRandom::new();
    let mut salt = [0u8; 16];
    rng.fill(&mut salt).map_err(|_| WebPushError::Crypto)?;
    let private =
        EphemeralPrivateKey::generate(&ECDH_P256, &rng).map_err(|_| WebPushError::Crypto)?;
    let as_public = private
        .compute_public_key()
        .map_err(|_| WebPushError::Crypto)?
        .as_ref()
        .to_vec();
    let ecdh_secret = agree_ephemeral(
        private,
        &UnparsedPublicKey::new(&ECDH_P256, ua_public),
        <[u8]>::to_vec,
    )
    .map_err(|_| WebPushError::BadKey)?;
    encrypt_with(
        &ecdh_secret,
        ua_public,
        &as_public,
        auth_secret,
        &salt,
        plaintext,
    )
}

/// RFC 8291 §3.4 with the shared secret and salt given — the deterministic part, tested
/// against the RFC's example.
fn encrypt_with(
    ecdh_secret: &[u8],
    ua_public: &[u8],
    as_public: &[u8],
    auth_secret: &[u8],
    salt: &[u8; 16],
    plaintext: &[u8],
) -> Result<Vec<u8>, WebPushError> {
    let mut key_info = Vec::with_capacity(14 + 65 + 65);
    key_info.extend_from_slice(b"WebPush: info\0");
    key_info.extend_from_slice(ua_public);
    key_info.extend_from_slice(as_public);
    let ikm = hkdf(auth_secret, ecdh_secret, &key_info, 32)?;
    let cek = hkdf(salt, &ikm, b"Content-Encoding: aes128gcm\0", 16)?;
    let nonce = hkdf(salt, &ikm, b"Content-Encoding: nonce\0", 12)?;

    let key =
        LessSafeKey::new(UnboundKey::new(&AES_128_GCM, &cek).map_err(|_| WebPushError::Crypto)?);
    let nonce = Nonce::try_assume_unique_for_key(&nonce).map_err(|_| WebPushError::Crypto)?;
    // Single (last) record: the plaintext, then the 0x02 delimiter, no padding.
    let mut record = Vec::with_capacity(plaintext.len() + 1 + 16);
    record.extend_from_slice(plaintext);
    record.push(0x02);
    key.seal_in_place_append_tag(nonce, Aad::empty(), &mut record)
        .map_err(|_| WebPushError::Crypto)?;

    let mut body = Vec::with_capacity(16 + 4 + 1 + as_public.len() + record.len());
    body.extend_from_slice(salt);
    body.extend_from_slice(&RECORD_SIZE.to_be_bytes());
    body.push(u8::try_from(as_public.len()).map_err(|_| WebPushError::Crypto)?);
    body.extend_from_slice(as_public);
    body.extend_from_slice(&record);
    Ok(body)
}

struct OkmLen(usize);

impl KeyType for OkmLen {
    fn len(&self) -> usize {
        self.0
    }
}

fn hkdf(salt: &[u8], ikm: &[u8], info: &[u8], len: usize) -> Result<Vec<u8>, WebPushError> {
    let prk = Salt::new(HKDF_SHA256, salt).extract(ikm);
    let info = [info];
    let okm = prk
        .expand(&info, OkmLen(len))
        .map_err(|_| WebPushError::Crypto)?;
    let mut out = vec![0u8; len];
    okm.fill(&mut out).map_err(|_| WebPushError::Crypto)?;
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::signature::{ECDSA_P256_SHA256_FIXED, UnparsedPublicKey as SignaturePublicKey};

    fn b64(value: &str) -> Vec<u8> {
        B64URL.decode(value).expect("base64url")
    }

    /// RFC 8291 Appendix A, with the ECDH secret the RFC lists (ring cannot import the example's
    /// private keys; `scratchpad` cross-checked the whole vector with Node's ECDH).
    #[test]
    fn matches_the_rfc_8291_example() {
        let salt: [u8; 16] = b64("DGv6ra1nlYgDCS1FRnbzlw").try_into().unwrap();
        let body = encrypt_with(
            &b64("kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs"),
            &b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
            &b64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"),
            &b64("BTBZMqHH6r4Tts7J_aSIgg"),
            &salt,
            b"When I grow up, I want to be a watermelon",
        )
        .expect("encrypt");
        assert_eq!(
            B64URL.encode(body),
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
        );
    }

    /// What a browser does with the body: its own ECDH with the key in the header, the same
    /// derivation, one record to open.
    #[test]
    fn a_browser_key_opens_what_encrypt_seals() {
        let rng = SystemRandom::new();
        let ua_private = EphemeralPrivateKey::generate(&ECDH_P256, &rng).unwrap();
        let ua_public = ua_private.compute_public_key().unwrap().as_ref().to_vec();
        let mut auth = [0u8; 16];
        rng.fill(&mut auth).unwrap();
        let plaintext = br#"{"kind":"message","sender":"alice"}"#;

        let body = encrypt(&ua_public, &auth, plaintext).expect("encrypt");

        let salt = &body[..16];
        assert_eq!(
            u32::from_be_bytes(body[16..20].try_into().unwrap()),
            RECORD_SIZE
        );
        let id_len = body[20] as usize;
        let as_public = &body[21..21 + id_len];
        let record = &body[21 + id_len..];
        let ecdh = agree_ephemeral(
            ua_private,
            &UnparsedPublicKey::new(&ECDH_P256, as_public),
            <[u8]>::to_vec,
        )
        .unwrap();
        let mut key_info = b"WebPush: info\0".to_vec();
        key_info.extend_from_slice(&ua_public);
        key_info.extend_from_slice(as_public);
        let ikm = hkdf(&auth, &ecdh, &key_info, 32).unwrap();
        let cek = hkdf(salt, &ikm, b"Content-Encoding: aes128gcm\0", 16).unwrap();
        let nonce = hkdf(salt, &ikm, b"Content-Encoding: nonce\0", 12).unwrap();
        let key = LessSafeKey::new(UnboundKey::new(&AES_128_GCM, &cek).unwrap());
        let mut record = record.to_vec();
        let opened = key
            .open_in_place(
                Nonce::try_assume_unique_for_key(&nonce).unwrap(),
                Aad::empty(),
                &mut record,
            )
            .expect("open");
        assert_eq!(opened.last(), Some(&0x02));
        assert_eq!(&opened[..opened.len() - 1], plaintext);
    }

    #[test]
    fn refuses_bad_keys_and_oversized_payloads() {
        assert!(matches!(
            encrypt(&[4; 10], &[0; 16], b"x"),
            Err(WebPushError::BadKey)
        ));
        let rng = SystemRandom::new();
        let key = EphemeralPrivateKey::generate(&ECDH_P256, &rng).unwrap();
        let public = key.compute_public_key().unwrap().as_ref().to_vec();
        let big = vec![b'a'; MAX_PAYLOAD_BYTES + 1];
        assert!(matches!(
            encrypt(&public, &[0; 16], &big),
            Err(WebPushError::TooLarge)
        ));
        // A point off the curve fails the agreement.
        let mut off_curve = public.clone();
        off_curve[64] ^= 1;
        assert!(
            parse_subscription_keys(&B64URL.encode(&off_curve), &B64URL.encode([0u8; 16]))
                .is_none()
        );
        assert!(
            parse_subscription_keys(&B64URL.encode(&public), &B64URL.encode([0u8; 16])).is_some()
        );
        assert!(
            parse_subscription_keys(&B64URL.encode(&public), &B64URL.encode([0u8; 8])).is_none()
        );
    }

    #[test]
    fn vapid_token_verifies_with_the_public_key() {
        let (key, pkcs8) = VapidKey::generate().expect("generate");
        let reloaded = VapidKey::from_pkcs8(&pkcs8).expect("reload");
        assert_eq!(key.public_key(), reloaded.public_key());
        assert_eq!(key.public_key().len(), 65);

        let token = key
            .token(
                "https://fcm.googleapis.com",
                "mailto:ops@example.org",
                1_900_000_000,
            )
            .expect("token");
        let parts: Vec<&str> = token.split('.').collect();
        assert_eq!(parts.len(), 3);
        let header: serde_json::Value = serde_json::from_slice(&b64(parts[0])).unwrap();
        assert_eq!(header["alg"], "ES256");
        let claims: serde_json::Value = serde_json::from_slice(&b64(parts[1])).unwrap();
        assert_eq!(claims["aud"], "https://fcm.googleapis.com");
        assert_eq!(claims["sub"], "mailto:ops@example.org");
        assert_eq!(claims["exp"], 1_900_000_000u64);
        let signature = b64(parts[2]);
        assert_eq!(signature.len(), 64);
        SignaturePublicKey::new(&ECDSA_P256_SHA256_FIXED, key.public_key())
            .verify(format!("{}.{}", parts[0], parts[1]).as_bytes(), &signature)
            .expect("signature verifies");
    }

    #[test]
    fn only_push_service_endpoints_are_allowed() {
        let none: Vec<String> = vec![];
        let allowed = |endpoint: &str| allowed_endpoint(endpoint, &none).is_some();
        assert!(allowed("https://fcm.googleapis.com/fcm/send/abc"));
        assert!(allowed(
            "https://updates.push.services.mozilla.com/wpush/v2/x"
        ));
        assert!(allowed("https://web.push.apple.com/QGuQ"));
        assert!(allowed("https://wns2-par02p.notify.windows.com/w/?token=1"));
        assert!(!allowed("http://fcm.googleapis.com/fcm/send/abc"));
        assert!(!allowed("https://evil.example/fcm.googleapis.com"));
        assert!(!allowed("https://fcm.googleapis.com.evil.example/x"));
        assert!(!allowed("https://127.0.0.1/x"));
        assert!(!allowed("https://fcm.googleapis.com:8443/x"));
        assert!(!allowed("https://user@fcm.googleapis.com/x"));
        assert!(!allowed("https://user:pw@fcm.googleapis.com/x"));
        assert!(!allowed("https://[::1]/x"));
        assert!(!allowed("https://fcm.googleapis.com./x"));
        assert!(!allowed("not a url"));
        let extra = vec!["push.example.org".to_string()];
        assert!(allowed_endpoint("https://eu.push.example.org/sub/1", &extra).is_some());
    }

    #[test]
    fn the_host_checked_is_the_host_contacted() {
        let none: Vec<String> = vec![];
        // A backslash ends the host for the HTTP client, as it does in browsers.
        assert!(
            allowed_endpoint("https://attacker.example\\.fcm.googleapis.com/x", &none).is_none()
        );
        assert!(allowed_endpoint("https://10.0.0.5\\.push.apple.com/", &none).is_none());
        // IPv4 written in other forms is still an address.
        assert!(allowed_endpoint("https://0x7f.0.0.1/x", &none).is_none());
        assert!(allowed_endpoint("https://2130706433/x", &none).is_none());
        // What is stored is the parsed form: the one the client uses.
        let url =
            allowed_endpoint("https://FCM.googleapis.com/fcm/send/abc", &none).expect("allowed");
        assert_eq!(url.as_str(), "https://fcm.googleapis.com/fcm/send/abc");
        assert_eq!(
            url.origin().ascii_serialization(),
            "https://fcm.googleapis.com"
        );
        let url = allowed_endpoint("https://fcm%2egoogleapis.com/x", &none).expect("same host");
        assert_eq!(url.domain(), Some("fcm.googleapis.com"));
    }

    fn android_route(endpoint: &str, policy: &UnifiedPushPolicy) -> Option<EndpointRoute> {
        android_endpoint(endpoint, policy).map(|(_, route)| route)
    }

    /// Every switch at once: the operator's own host, any public host, local http.
    fn open_policy() -> UnifiedPushPolicy {
        UnifiedPushPolicy {
            allowed_hosts: vec!["googleapis.com".into(), "push.example.org".into()],
            public_hosts: true,
            allow_local_http: true,
            resolve_overrides: HashMap::new(),
        }
    }

    #[test]
    fn android_endpoints_are_on_unifiedpush_servers() {
        let defaults = UnifiedPushPolicy::default();
        for listed in [
            "https://ntfy.sh/upAbC123?up=1",
            "https://up.conversations.im/push/abc",
            "https://updates.push.services.mozilla.com/wpush/v2/abc",
        ] {
            assert_eq!(
                android_route(listed, &defaults),
                Some(EndpointRoute::Listed),
                "{listed}"
            );
        }
        // Browser push services that are no UnifiedPush server stay browser-only.
        assert_eq!(
            android_route("https://web.push.apple.com/QGuQ", &defaults),
            None
        );
        assert_eq!(
            android_route(
                "https://wns2-par02p.notify.windows.com/w/?token=1",
                &defaults
            ),
            None
        );
        // A self-hosted distributor only once the operator lists it.
        let own = UnifiedPushPolicy {
            allowed_hosts: host_suffixes(" .Push.Example.org , ,"),
            ..UnifiedPushPolicy::default()
        };
        assert_eq!(own.allowed_hosts, vec!["push.example.org".to_string()]);
        assert_eq!(
            android_route("https://push.example.org/up1?up=1", &own),
            Some(EndpointRoute::Listed)
        );
        assert_eq!(
            android_route("https://eu.push.example.org/up1", &own),
            Some(EndpointRoute::Listed)
        );
        assert_eq!(
            android_route("https://push.example.org.evil.example/up1", &own),
            None
        );
        assert_eq!(
            android_route("https://push.example.org/up1", &defaults),
            None
        );
        // Stored as parsed, like a browser endpoint.
        let (url, _) = android_endpoint("https://NTFY.sh/upX?up=1", &defaults).expect("allowed");
        assert_eq!(url.as_str(), "https://ntfy.sh/upX?up=1");
    }

    #[test]
    fn google_is_never_an_android_endpoint() {
        for google in [
            "https://fcm.googleapis.com/fcm/send/abc",
            "https://android.googleapis.com/gcm/send/abc",
            "https://googleapis.com/x",
            "https://FCM.GoogleAPIs.com/x",
            "https://fcm%2egoogleapis.com/x",
            "https://fcm.googleapis.com./x",
        ] {
            assert_eq!(
                android_route(google, &UnifiedPushPolicy::default()),
                None,
                "{google}"
            );
            // Not even listed by the operator, nor in public-host mode.
            assert_eq!(android_route(google, &open_policy()), None, "{google}");
        }
        // Browsers keep Chrome's push service.
        assert!(allowed_endpoint("https://fcm.googleapis.com/fcm/send/abc", &[]).is_some());
    }

    #[test]
    fn android_endpoint_shapes_are_checked_like_a_browsers() {
        for policy in [UnifiedPushPolicy::default(), open_policy()] {
            for bad in [
                "http://ntfy.sh/up1",
                "https://ntfy.sh:8443/up1",
                "https://user@ntfy.sh/up1",
                "https://user:pw@ntfy.sh/up1",
                "https://127.0.0.1/up1",
                "https://[::1]/up1",
                "https://0x7f.0.0.1/up1",
                "https://2130706433/up1",
                "https://93.184.215.14/up1",
                "ftp://ntfy.sh/up1",
                "not a url",
            ] {
                assert_eq!(android_route(bad, &policy), None, "{bad}");
            }
            let long = format!("https://ntfy.sh/{}", "a".repeat(1024));
            assert_eq!(android_route(&long, &policy), None);
        }
        // The backslash trick: the host checked is the host contacted. With the defaults the
        // real host (`attacker.example`) is not listed; in public-host mode it is the one
        // whose addresses get checked.
        let trick = "https://attacker.example\\.ntfy.sh/x";
        assert_eq!(android_route(trick, &UnifiedPushPolicy::default()), None);
        let (url, route) = android_endpoint(trick, &open_policy()).expect("a public name");
        assert_eq!(route, EndpointRoute::PublicHost);
        assert_eq!(url.domain(), Some("attacker.example"));
    }

    #[test]
    fn public_host_mode_takes_public_names_only() {
        let open = open_policy();
        assert_eq!(
            android_route("https://push.selfhosted.example/up1?up=1", &open),
            Some(EndpointRoute::PublicHost)
        );
        assert_eq!(
            android_route(
                "https://push.selfhosted.example/up1",
                &UnifiedPushPolicy::default()
            ),
            None
        );
        for local in [
            "https://localhost/up1",
            "https://intranet/up1",
            "https://ntfy.local/up1",
            "https://nas.home/up1",
            "https://ntfy.internal/up1",
            "https://ntfy.lan/up1",
            "https://x.localhost/up1",
            "https://under_score.example/up1",
        ] {
            assert_eq!(android_route(local, &open), None, "{local}");
        }
    }

    #[test]
    fn local_http_is_loopback_only_and_off_by_default() {
        let local = UnifiedPushPolicy {
            allow_local_http: true,
            ..UnifiedPushPolicy::default()
        };
        for loopback in [
            "http://localhost:2586/upX?up=1",
            "http://127.0.0.1:2586/upX",
            "http://[::1]:2586/upX",
        ] {
            assert_eq!(
                android_route(loopback, &local),
                Some(EndpointRoute::LocalHttp),
                "{loopback}"
            );
            assert_eq!(android_route(loopback, &UnifiedPushPolicy::default()), None);
        }
        for remote in [
            "http://ntfy.sh/upX",
            "http://10.0.2.2:2586/upX",
            "http://192.168.1.10:2586/upX",
            "http://localhost.evil.example/upX",
            "http://user@localhost:2586/upX",
        ] {
            assert_eq!(android_route(remote, &local), None, "{remote}");
        }
    }

    fn resolver() -> PublicResolver {
        let ip = |text: &str| text.parse::<IpAddr>().unwrap();
        PublicResolver {
            overrides: HashMap::from([
                (
                    "push.public.example".to_string(),
                    vec![
                        ip("93.184.215.14"),
                        ip("2606:2800:21f:cb07:6820:80da:af6b:8b2c"),
                    ],
                ),
                ("push.private.example".to_string(), vec![ip("10.0.0.5")]),
                (
                    "push.mixed.example".to_string(),
                    vec![ip("93.184.215.14"), ip("127.0.0.1")],
                ),
                (
                    "push.mapped.example".to_string(),
                    vec![ip("::ffff:192.168.1.1")],
                ),
                ("push.cgnat.example".to_string(), vec![ip("100.64.0.1")]),
                ("push.none.example".to_string(), vec![]),
            ]),
        }
    }

    #[tokio::test]
    async fn public_host_mode_needs_every_address_public() {
        let resolver = resolver();
        assert_eq!(
            resolver
                .addresses("push.public.example")
                .await
                .unwrap()
                .len(),
            2
        );
        for private in [
            "push.private.example",
            "push.mixed.example",
            "push.mapped.example",
            "push.cgnat.example",
        ] {
            assert_eq!(
                resolver.addresses(private).await,
                Err("not public"),
                "{private}"
            );
        }
        assert_eq!(
            resolver.addresses("push.none.example").await,
            Err("unresolvable")
        );
    }

    /// The HTTP client of public-host mode resolves through the same check, so it connects to
    /// the addresses checked and to nothing else.
    #[tokio::test]
    async fn the_public_clients_resolver_is_the_check() {
        use reqwest::dns::Resolve as _;
        use std::str::FromStr as _;

        let resolver = resolver();
        let name = reqwest::dns::Name::from_str("push.public.example").unwrap();
        let addresses: Vec<SocketAddr> = resolver.resolve(name).await.expect("public").collect();
        assert_eq!(
            addresses,
            vec![
                SocketAddr::new("93.184.215.14".parse().unwrap(), 0),
                SocketAddr::new("2606:2800:21f:cb07:6820:80da:af6b:8b2c".parse().unwrap(), 0),
            ]
        );
        let name = reqwest::dns::Name::from_str("push.mixed.example").unwrap();
        assert!(resolver.resolve(name).await.is_err());
    }

    #[tokio::test]
    async fn each_client_is_held_to_its_own_policy() {
        let (vapid, _) = VapidKey::generate().unwrap();
        let mut policy = open_policy();
        policy.allowed_hosts = vec![];
        policy.resolve_overrides = resolver().overrides;
        let client = WebPushClient::new(vapid, "mailto:t@example.org".into(), vec![], policy)
            .expect("client");
        let accept = |endpoint: &'static str, who| {
            let client = client.clone();
            async move { client.accept_endpoint(endpoint, who).await.is_some() }
        };
        use SubscriptionClient::{Android, Browser};
        assert!(accept("https://ntfy.sh/up1", Android).await);
        assert!(!accept("https://ntfy.sh/up1", Browser).await);
        assert!(accept("https://fcm.googleapis.com/fcm/send/1", Browser).await);
        assert!(!accept("https://fcm.googleapis.com/fcm/send/1", Android).await);
        assert!(accept("https://push.public.example/up1", Android).await);
        assert!(!accept("https://push.private.example/up1", Android).await);
        assert!(!accept("https://push.mixed.example/up1", Android).await);
        assert!(!accept("https://push.public.example/up1", Browser).await);
        assert!(accept("http://localhost:2586/up1", Android).await);
        assert!(!accept("http://localhost:2586/up1", Browser).await);

        // A send checks again: a stored Google endpoint is not contacted for an Android app.
        let key = EphemeralPrivateKey::generate(&ECDH_P256, &SystemRandom::new()).unwrap();
        let subscription = WebSubscription {
            endpoint: "https://fcm.googleapis.com/fcm/send/1".into(),
            p256dh: key.compute_public_key().unwrap().as_ref().to_vec(),
            auth: vec![1; 16],
            client: Android,
        };
        let outcome = client
            .send(
                &subscription,
                b"{}",
                &WebPushOptions {
                    ttl_secs: 60,
                    urgency: Urgency::High,
                    topic: None,
                },
            )
            .await;
        assert!(
            matches!(&outcome, WebPushOutcome::Failed { status: 0, reason } if reason == "endpoint not allowed"),
            "{outcome:?}"
        );
    }

    #[test]
    fn subscription_clients_round_trip() {
        for client in [SubscriptionClient::Browser, SubscriptionClient::Android] {
            assert_eq!(SubscriptionClient::parse(client.as_str()), Some(client));
        }
        assert_eq!(SubscriptionClient::parse("ios"), None);
        assert_eq!(SubscriptionClient::parse("Android"), None);
        assert_eq!(
            SubscriptionClient::from_column(None),
            SubscriptionClient::Browser
        );
        assert_eq!(
            SubscriptionClient::from_column(Some("android")),
            SubscriptionClient::Android
        );
    }
}
