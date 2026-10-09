//! The admin console's call check (`GET /operator/calls/check`).
//!
//! Human: Each STUN and TURN URL callers are handed is asked to do its job, from this server.
//! A STUN server gets a binding request and must say which address it saw. A TURN server must
//! refuse an allocation without a login (one that hands out relays to anyone is an open relay),
//! then accept a login minted for this check (or the fixed one it is handed out with) and give
//! out a relay address, which is released at once. Nothing reaches a person and nothing is
//! kept; the only address involved is this server's own.
//! Agent: READS `AppState::ice_servers` and `AppState::turn`. One UDP, TCP or TLS connection per
//! URL, all at once, each bounded by [`STEP_TIMEOUT`] so the console's 10-second limit holds.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures_util::future::join_all;
use md5::{Digest, Md5};
use ring::hmac;
use serde::Serialize;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};
use tokio_rustls::TlsConnector;
use tokio_rustls::rustls::pki_types::ServerName;
use tokio_rustls::rustls::{self, ClientConfig, RootCertStore};

use crate::config::IceServer;
use crate::turn::TurnConfig;

/// Upper bound for one URL's whole exchange.
const STEP_TIMEOUT: Duration = Duration::from_secs(5);
/// A lost UDP request is sent again after this long, until [`STEP_TIMEOUT`].
const RETRANSMIT: Duration = Duration::from_millis(500);
const MAGIC_COOKIE: u32 = 0x2112_A442;

const BINDING: u16 = 0x0001;
const ALLOCATE: u16 = 0x0003;
const REFRESH: u16 = 0x0004;
const SUCCESS: u16 = 0x0100;

const MAPPED_ADDRESS: u16 = 0x0001;
const USERNAME: u16 = 0x0006;
const MESSAGE_INTEGRITY: u16 = 0x0008;
const ERROR_CODE: u16 = 0x0009;
const LIFETIME: u16 = 0x000D;
const REALM: u16 = 0x0014;
const NONCE: u16 = 0x0015;
const XOR_RELAYED_ADDRESS: u16 = 0x0016;
const REQUESTED_TRANSPORT: u16 = 0x0019;
const XOR_MAPPED_ADDRESS: u16 = 0x0020;
/// `REQUESTED-TRANSPORT` for UDP relaying (protocol 17), as WebRTC asks for it.
const UDP_RELAY: [u8; 4] = [17, 0, 0, 0];

/// One line of the check: a URL callers are handed, in the order they get them.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct CallCheck {
    pub item: String,
    /// `ok`, `failed`, or `off` (no server configured).
    pub state: &'static str,
    pub detail: String,
}

/// Checks every URL in `servers` and the minted TURN, once each.
pub async fn check(
    servers: &[IceServer],
    turn: Option<&TurnConfig>,
    now_unix: u64,
) -> Vec<CallCheck> {
    let targets = targets(servers, turn, now_unix);
    if targets.is_empty() {
        return vec![CallCheck {
            item: "No STUN or TURN server".into(),
            state: "off",
            detail: "Calls connect only where a direct path exists, and no outside server learns anyone's address.".into(),
        }];
    }
    join_all(targets.iter().map(|target| async move {
        let answer = tokio::time::timeout(STEP_TIMEOUT, probe(target))
            .await
            .unwrap_or_else(|_| Err(format!("No answer in {} s.", STEP_TIMEOUT.as_secs())));
        match answer {
            Ok(detail) => CallCheck {
                item: target.url.clone(),
                state: "ok",
                detail,
            },
            Err(detail) => CallCheck {
                item: target.url.clone(),
                state: "failed",
                detail,
            },
        }
    }))
    .await
}

struct Target {
    url: String,
    login: Option<Login>,
}

struct Login {
    username: String,
    password: String,
    /// Minted from `TURN_SECRET` for this check, rather than a fixed login handed out as is.
    minted: bool,
}

fn targets(servers: &[IceServer], turn: Option<&TurnConfig>, now_unix: u64) -> Vec<Target> {
    let mut out: Vec<Target> = Vec::new();
    let mut add = |url: &str, login: Option<Login>| {
        if !out.iter().any(|target| target.url == url) {
            out.push(Target {
                url: url.to_string(),
                login,
            });
        }
    };
    for server in servers {
        for url in &server.urls {
            let login = match (&server.username, &server.credential) {
                (Some(username), Some(password)) => Some(Login {
                    username: username.clone(),
                    password: password.clone(),
                    minted: false,
                }),
                _ => None,
            };
            add(url, login);
        }
    }
    if let Some(turn) = turn {
        let minted = turn.check_credential(now_unix);
        for url in &minted.urls {
            add(
                url,
                Some(Login {
                    username: minted.username.clone().unwrap_or_default(),
                    password: minted.credential.clone().unwrap_or_default(),
                    minted: true,
                }),
            );
        }
    }
    out
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Scheme {
    Stun,
    Turn,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Transport {
    Udp,
    Tcp,
    Tls,
}

#[derive(Debug, PartialEq, Eq)]
struct Endpoint {
    scheme: Scheme,
    host: String,
    port: u16,
    transport: Transport,
}

/// `stun:`, `stuns:`, `turn:` or `turns:`, a host (a bracketed IPv6 literal too), an optional
/// port, and an optional `?transport=udp|tcp` (RFC 7064, RFC 7065).
fn parse(url: &str) -> Result<Endpoint, String> {
    let (scheme, rest, secure) = if let Some(rest) = url.strip_prefix("stuns:") {
        (Scheme::Stun, rest, true)
    } else if let Some(rest) = url.strip_prefix("stun:") {
        (Scheme::Stun, rest, false)
    } else if let Some(rest) = url.strip_prefix("turns:") {
        (Scheme::Turn, rest, true)
    } else if let Some(rest) = url.strip_prefix("turn:") {
        (Scheme::Turn, rest, false)
    } else {
        return Err("Not a STUN or TURN URL.".into());
    };
    let (authority, query) = rest.split_once('?').unwrap_or((rest, ""));
    let asked = query
        .split('&')
        .find_map(|pair| pair.strip_prefix("transport="));
    let transport = match (secure, asked) {
        (true, None | Some("tcp")) => Transport::Tls,
        (false, None | Some("udp")) => Transport::Udp,
        (false, Some("tcp")) => Transport::Tcp,
        (_, Some(other)) => return Err(format!("Unknown transport {other}.")),
    };
    let default_port = if secure { 5349 } else { 3478 };
    let (host, port) = if let Some(bracketed) = authority.strip_prefix('[') {
        let (host, after) = bracketed
            .split_once(']')
            .ok_or_else(|| "Unclosed IPv6 address.".to_string())?;
        let port = match after.strip_prefix(':') {
            Some(port) => port.parse().map_err(|_| "Bad port.".to_string())?,
            None if after.is_empty() => default_port,
            None => return Err("Bad port.".into()),
        };
        (host.to_string(), port)
    } else {
        match authority.rsplit_once(':') {
            Some((host, port)) => (
                host.to_string(),
                port.parse().map_err(|_| "Bad port.".to_string())?,
            ),
            None => (authority.to_string(), default_port),
        }
    };
    if host.is_empty() {
        return Err("No host.".into());
    }
    Ok(Endpoint {
        scheme,
        host,
        port,
        transport,
    })
}

async fn probe(target: &Target) -> Result<String, String> {
    let endpoint = parse(&target.url)?;
    let started = Instant::now();
    let mut connection = Connection::open(&endpoint).await?;
    match (endpoint.scheme, &target.login) {
        (Scheme::Stun, _) => {
            let seen = binding(&mut connection).await?;
            Ok(format!(
                "Answered in {} ms · sees this server at {seen}.",
                started.elapsed().as_millis()
            ))
        }
        (Scheme::Turn, None) => {
            binding(&mut connection).await?;
            Ok(format!(
                "Answered in {} ms. It's handed out without a login, so relaying can't be tried.",
                started.elapsed().as_millis()
            ))
        }
        (Scheme::Turn, Some(login)) => {
            allocate(&mut connection, login).await?;
            let what = if login.minted {
                "a login minted for this check"
            } else {
                "the login it's handed out with"
            };
            Ok(format!(
                "Refused a relay without a login, then gave one to {what} in {} ms; released at once.",
                started.elapsed().as_millis()
            ))
        }
    }
}

/// A binding request; the address the server saw this server at.
async fn binding(connection: &mut Connection) -> Result<IpAddr, String> {
    let request = Message::new(BINDING);
    let reply = connection.exchange(&request).await?;
    if reply.kind != BINDING | SUCCESS {
        return Err(refusal("a binding request", &reply));
    }
    reply
        .address(XOR_MAPPED_ADDRESS, &request.transaction)
        .or_else(|| reply.plain_address(MAPPED_ADDRESS))
        .ok_or_else(|| "Answered without the address it saw.".to_string())
}

/// Asks for a UDP relay without a login (it must be refused), then with `login`, and releases
/// what it got.
async fn allocate(connection: &mut Connection, login: &Login) -> Result<(), String> {
    let mut anonymous = Message::new(ALLOCATE);
    anonymous.attribute(REQUESTED_TRANSPORT, &UDP_RELAY);
    let reply = connection.exchange(&anonymous).await?;
    if reply.kind == ALLOCATE | SUCCESS {
        let mut release = Message::new(REFRESH);
        release.attribute(LIFETIME, &0u32.to_be_bytes());
        let _ = connection.exchange(&release).await;
        return Err("Hands out relays without a login: anyone can relay through it. Turn on use-auth-secret in coturn.".into());
    }
    match reply.error() {
        Some((401, _)) => {}
        Some((code, reason)) => return Err(format!("Refused a relay: {code} {reason}.")),
        None => return Err(refusal("a relay request", &reply)),
    }
    let realm = reply
        .attribute_value(REALM)
        .ok_or_else(|| "Asked for a login without naming its realm.".to_string())?
        .to_vec();
    let mut nonce = reply
        .attribute_value(NONCE)
        .ok_or_else(|| "Asked for a login without a nonce.".to_string())?
        .to_vec();
    let key = long_term_key(&login.username, &realm, &login.password);

    // A stale nonce (438) is renewed once, as a client would.
    for _ in 0..2 {
        let mut request = Message::new(ALLOCATE);
        request.attribute(REQUESTED_TRANSPORT, &UDP_RELAY);
        request.attribute(USERNAME, login.username.as_bytes());
        request.attribute(REALM, &realm);
        request.attribute(NONCE, &nonce);
        request.integrity(&key);
        let reply = connection.exchange(&request).await?;
        if reply.kind == ALLOCATE | SUCCESS {
            if reply
                .address(XOR_RELAYED_ADDRESS, &request.transaction)
                .is_none()
            {
                return Err("Accepted the login but named no relay address.".into());
            }
            let mut release = Message::new(REFRESH);
            release.attribute(LIFETIME, &0u32.to_be_bytes());
            release.attribute(USERNAME, login.username.as_bytes());
            release.attribute(REALM, &realm);
            release.attribute(NONCE, &nonce);
            release.integrity(&key);
            let _ = connection.exchange(&release).await;
            return Ok(());
        }
        match reply.error() {
            Some((438, _)) => {
                nonce = reply
                    .attribute_value(NONCE)
                    .ok_or_else(|| "Renewed its nonce without sending one.".to_string())?
                    .to_vec();
            }
            Some((401, _)) if login.minted => {
                return Err("Refused the login the API mints (401): TURN_SECRET doesn't match coturn's static-auth-secret, or the clocks are hours apart.".into());
            }
            Some((401, _)) => {
                return Err("Refused the login it's handed out with (401).".into());
            }
            Some((code, reason)) => return Err(format!("Refused a relay: {code} {reason}.")),
            None => return Err(refusal("a relay request", &reply)),
        }
    }
    Err("Kept calling its nonce stale.".into())
}

fn refusal(what: &str, reply: &Reply) -> String {
    match reply.error() {
        Some((code, reason)) => format!("Refused {what}: {code} {reason}."),
        None => format!("Answered {what} with message type {:#06x}.", reply.kind),
    }
}

/// RFC 8489's long-term credential key: MD5 of `username:realm:password`.
fn long_term_key(username: &str, realm: &[u8], password: &str) -> Vec<u8> {
    let mut md5 = Md5::new();
    md5.update(username.as_bytes());
    md5.update(b":");
    md5.update(realm);
    md5.update(b":");
    md5.update(password.as_bytes());
    md5.finalize().to_vec()
}

/// A STUN request being built.
struct Message {
    bytes: Vec<u8>,
    transaction: [u8; 12],
}

impl Message {
    fn new(kind: u16) -> Self {
        let transaction: [u8; 12] = rand::random();
        let mut bytes = Vec::with_capacity(128);
        bytes.extend_from_slice(&kind.to_be_bytes());
        bytes.extend_from_slice(&0u16.to_be_bytes());
        bytes.extend_from_slice(&MAGIC_COOKIE.to_be_bytes());
        bytes.extend_from_slice(&transaction);
        Self { bytes, transaction }
    }

    fn attribute(&mut self, kind: u16, value: &[u8]) {
        self.bytes.extend_from_slice(&kind.to_be_bytes());
        self.bytes
            .extend_from_slice(&(value.len() as u16).to_be_bytes());
        self.bytes.extend_from_slice(value);
        while !self.bytes.len().is_multiple_of(4) {
            self.bytes.push(0);
        }
        self.set_length(self.bytes.len() - 20);
    }

    /// `MESSAGE-INTEGRITY` over everything before it, with the header's length already
    /// counting the attribute (RFC 8489 §14.5).
    fn integrity(&mut self, key: &[u8]) {
        self.set_length(self.bytes.len() - 20 + 24);
        let tag = integrity_tag(&self.bytes, key);
        self.attribute(MESSAGE_INTEGRITY, &tag);
    }

    fn set_length(&mut self, length: usize) {
        self.bytes[2..4].copy_from_slice(&(length as u16).to_be_bytes());
    }
}

fn integrity_tag(signed: &[u8], key: &[u8]) -> Vec<u8> {
    let key = hmac::Key::new(hmac::HMAC_SHA1_FOR_LEGACY_USE_ONLY, key);
    hmac::sign(&key, signed).as_ref().to_vec()
}

/// A STUN answer.
struct Reply {
    kind: u16,
    attributes: Vec<(u16, Vec<u8>)>,
}

impl Reply {
    fn parse(bytes: &[u8]) -> Option<Self> {
        if bytes.len() < 20 || bytes[4..8] != MAGIC_COOKIE.to_be_bytes() {
            return None;
        }
        let kind = u16::from_be_bytes([bytes[0], bytes[1]]);
        let length = usize::from(u16::from_be_bytes([bytes[2], bytes[3]]));
        let body = bytes.get(20..20 + length)?;
        let mut attributes = Vec::new();
        let mut at = 0;
        while at + 4 <= body.len() {
            let kind = u16::from_be_bytes([body[at], body[at + 1]]);
            let size = usize::from(u16::from_be_bytes([body[at + 2], body[at + 3]]));
            let value = body.get(at + 4..at + 4 + size)?;
            attributes.push((kind, value.to_vec()));
            at += 4 + size.div_ceil(4) * 4;
        }
        Some(Self { kind, attributes })
    }

    fn attribute_value(&self, kind: u16) -> Option<&[u8]> {
        self.attributes
            .iter()
            .find(|(found, _)| *found == kind)
            .map(|(_, value)| value.as_slice())
    }

    fn error(&self) -> Option<(u16, String)> {
        let value = self.attribute_value(ERROR_CODE)?;
        if value.len() < 4 {
            return None;
        }
        let code = u16::from(value[2] & 0x07) * 100 + u16::from(value[3]);
        let reason = String::from_utf8_lossy(&value[4..]).trim().to_string();
        Some((code, reason))
    }

    /// An `XOR-MAPPED-ADDRESS` or `XOR-RELAYED-ADDRESS`, un-XORed.
    fn address(&self, kind: u16, transaction: &[u8; 12]) -> Option<IpAddr> {
        let value = self.attribute_value(kind)?;
        let cookie = MAGIC_COOKIE.to_be_bytes();
        match value.get(1)? {
            1 => {
                let raw = value.get(4..8)?;
                Some(IpAddr::V4(Ipv4Addr::new(
                    raw[0] ^ cookie[0],
                    raw[1] ^ cookie[1],
                    raw[2] ^ cookie[2],
                    raw[3] ^ cookie[3],
                )))
            }
            2 => {
                let raw = value.get(4..20)?;
                let mut mask = [0u8; 16];
                mask[..4].copy_from_slice(&cookie);
                mask[4..].copy_from_slice(transaction);
                let mut octets = [0u8; 16];
                for (index, octet) in octets.iter_mut().enumerate() {
                    *octet = raw[index] ^ mask[index];
                }
                Some(IpAddr::V6(Ipv6Addr::from(octets)))
            }
            _ => None,
        }
    }

    /// A plain `MAPPED-ADDRESS`, from servers that predate the XOR form.
    fn plain_address(&self, kind: u16) -> Option<IpAddr> {
        let value = self.attribute_value(kind)?;
        match value.get(1)? {
            1 => {
                let raw = value.get(4..8)?;
                Some(IpAddr::V4(Ipv4Addr::new(raw[0], raw[1], raw[2], raw[3])))
            }
            2 => {
                let raw: [u8; 16] = value.get(4..20)?.try_into().ok()?;
                Some(IpAddr::V6(Ipv6Addr::from(raw)))
            }
            _ => None,
        }
    }
}

trait Stream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Stream for T {}

/// One connection to a STUN or TURN server; TURN's allocation lives as long as it does.
enum Connection {
    Udp(UdpSocket),
    Stream(Box<dyn Stream>),
}

impl Connection {
    async fn open(endpoint: &Endpoint) -> Result<Self, String> {
        let address = resolve(&endpoint.host, endpoint.port).await?;
        match endpoint.transport {
            Transport::Udp => {
                let local: SocketAddr = if address.is_ipv4() {
                    (Ipv4Addr::UNSPECIFIED, 0).into()
                } else {
                    (Ipv6Addr::UNSPECIFIED, 0).into()
                };
                let socket = UdpSocket::bind(local)
                    .await
                    .map_err(|err| format!("Couldn't open a UDP socket: {err}."))?;
                socket
                    .connect(address)
                    .await
                    .map_err(|err| network("Couldn't reach it over UDP", &err))?;
                Ok(Self::Udp(socket))
            }
            Transport::Tcp => {
                let stream = TcpStream::connect(address)
                    .await
                    .map_err(|err| network("Couldn't connect over TCP", &err))?;
                Ok(Self::Stream(Box::new(stream)))
            }
            Transport::Tls => {
                let stream = TcpStream::connect(address)
                    .await
                    .map_err(|err| network("Couldn't connect over TCP", &err))?;
                let name = ServerName::try_from(endpoint.host.clone())
                    .map_err(|_| "Its host isn't a valid TLS name.".to_string())?;
                let tls = TlsConnector::from(tls_config()?)
                    .connect(name, stream)
                    .await
                    .map_err(|err| format!("TLS failed: {err}."))?;
                Ok(Self::Stream(Box::new(tls)))
            }
        }
    }

    /// Sends `request` and returns the answer with its transaction id. UDP resends until the
    /// caller's timeout; a stream reads whole STUN messages (RFC 8489 §7.2.2 framing).
    async fn exchange(&mut self, request: &Message) -> Result<Reply, String> {
        match self {
            Self::Udp(socket) => {
                let mut buffer = vec![0u8; 2048];
                loop {
                    socket
                        .send(&request.bytes)
                        .await
                        .map_err(|err| network("Couldn't send over UDP", &err))?;
                    let deadline = Instant::now() + RETRANSMIT;
                    while let Ok(received) =
                        tokio::time::timeout_at(deadline.into(), socket.recv(&mut buffer)).await
                    {
                        let size = received.map_err(|err| network("No UDP answer", &err))?;
                        if let Some(reply) = matching(&buffer[..size], &request.transaction) {
                            return Ok(reply);
                        }
                    }
                }
            }
            Self::Stream(stream) => {
                stream
                    .write_all(&request.bytes)
                    .await
                    .map_err(|err| format!("Couldn't send: {err}."))?;
                loop {
                    let mut header = [0u8; 20];
                    stream
                        .read_exact(&mut header)
                        .await
                        .map_err(|err| format!("The connection closed: {err}."))?;
                    let length = usize::from(u16::from_be_bytes([header[2], header[3]]));
                    let mut message = header.to_vec();
                    message.resize(20 + length, 0);
                    stream
                        .read_exact(&mut message[20..])
                        .await
                        .map_err(|err| format!("The connection closed: {err}."))?;
                    if let Some(reply) = matching(&message, &request.transaction) {
                        return Ok(reply);
                    }
                }
            }
        }
    }
}

/// A socket error in words: a refused connection means nothing listens on that port.
fn network(what: &str, err: &std::io::Error) -> String {
    if err.kind() == std::io::ErrorKind::ConnectionRefused {
        format!("{what}: nothing listens on that port.")
    } else {
        format!("{what}: {err}.")
    }
}

fn matching(bytes: &[u8], transaction: &[u8; 12]) -> Option<Reply> {
    (bytes.get(8..20)? == transaction)
        .then(|| Reply::parse(bytes))
        .flatten()
}

async fn resolve(host: &str, port: u16) -> Result<SocketAddr, String> {
    let found: Vec<SocketAddr> = tokio::net::lookup_host((host, port))
        .await
        .map_err(|_| format!("Couldn't resolve {host}."))?
        .collect();
    // IPv4 first: a server with no IPv6 route would otherwise wait out the timeout.
    found
        .iter()
        .find(|address| address.is_ipv4())
        .or_else(|| found.first())
        .copied()
        .ok_or_else(|| format!("Couldn't resolve {host}."))
}

fn tls_config() -> Result<Arc<ClientConfig>, String> {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let config =
        ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .map_err(|err| format!("TLS setup failed: {err}."))?
            .with_root_certificates(roots)
            .with_no_client_auth();
    Ok(Arc::new(config))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urls_parse_like_webrtc_reads_them() {
        let cases = [
            (
                "stun:turn.example.org",
                Scheme::Stun,
                "turn.example.org",
                3478,
                Transport::Udp,
            ),
            (
                "stun:turn.example.org:19302",
                Scheme::Stun,
                "turn.example.org",
                19302,
                Transport::Udp,
            ),
            (
                "turn:turn.example.org:3478?transport=tcp",
                Scheme::Turn,
                "turn.example.org",
                3478,
                Transport::Tcp,
            ),
            (
                "turn:turn.example.org?transport=udp",
                Scheme::Turn,
                "turn.example.org",
                3478,
                Transport::Udp,
            ),
            (
                "turns:turn.example.org",
                Scheme::Turn,
                "turn.example.org",
                5349,
                Transport::Tls,
            ),
            (
                "turn:[2001:db8::1]:3479",
                Scheme::Turn,
                "2001:db8::1",
                3479,
                Transport::Udp,
            ),
        ];
        for (url, scheme, host, port, transport) in cases {
            assert_eq!(
                parse(url),
                Ok(Endpoint {
                    scheme,
                    host: host.into(),
                    port,
                    transport
                }),
                "{url}"
            );
        }
        for bad in [
            "http://x",
            "turn:",
            "turn:x:port",
            "turns:x?transport=udp",
            "turn:[::1",
        ] {
            assert!(parse(bad).is_err(), "{bad}");
        }
    }

    /// RFC 5769 §2.2, the IPv4 response: its address and its MESSAGE-INTEGRITY (short-term
    /// key, the password itself).
    #[test]
    fn rfc5769_response_decodes_and_verifies() {
        let bytes: [u8; 80] = [
            0x01, 0x01, 0x00, 0x3c, 0x21, 0x12, 0xa4, 0x42, 0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34,
            0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae, 0x80, 0x22, 0x00, 0x0b, 0x74, 0x65, 0x73, 0x74,
            0x20, 0x76, 0x65, 0x63, 0x74, 0x6f, 0x72, 0x20, 0x00, 0x20, 0x00, 0x08, 0x00, 0x01,
            0xa1, 0x47, 0xe1, 0x12, 0xa6, 0x43, 0x00, 0x08, 0x00, 0x14, 0x2b, 0x91, 0xf5, 0x99,
            0xfd, 0x9e, 0x90, 0xc3, 0x8c, 0x74, 0x89, 0xf9, 0x2a, 0xf9, 0xba, 0x53, 0xf0, 0x6b,
            0xe7, 0xd7, 0x80, 0x28, 0x00, 0x04, 0xc0, 0x7d, 0x4c, 0x96,
        ];
        let transaction: [u8; 12] = bytes[8..20].try_into().unwrap();
        let reply = matching(&bytes, &transaction).expect("parses");
        assert_eq!(reply.kind, BINDING | SUCCESS);
        assert_eq!(
            reply.address(XOR_MAPPED_ADDRESS, &transaction),
            Some(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1)))
        );
        // Signed: everything before MESSAGE-INTEGRITY, length counting it but not FINGERPRINT.
        let mut signed = bytes[..48].to_vec();
        signed[2..4].copy_from_slice(&(0x3c_u16 - 8).to_be_bytes());
        assert_eq!(
            integrity_tag(&signed, b"VOkJxbRl1RmTxUk/WvJxBt"),
            bytes[52..72].to_vec()
        );
    }

    #[test]
    fn error_codes_read_class_and_number() {
        let mut message = Message::new(ALLOCATE | 0x0110);
        message.attribute(ERROR_CODE, &[0, 0, 4, 38, b'S', b't', b'a', b'l', b'e']);
        let reply = Reply::parse(&message.bytes).expect("parses");
        assert_eq!(reply.error(), Some((438, "Stale".into())));
    }

    /// A fake TURN server on UDP: refuses the anonymous allocation with a realm and nonce,
    /// checks the second one's MESSAGE-INTEGRITY with the long-term key, answers with a relay
    /// address, and records the release.
    #[tokio::test]
    async fn allocation_logs_in_and_releases() {
        let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let address = server.local_addr().unwrap();
        let fake = tokio::spawn(async move {
            let key = long_term_key("1700000000:abc", b"shroud", "secret");
            let mut seen = Vec::new();
            let mut buffer = vec![0u8; 2048];
            for _ in 0..3 {
                let (size, peer) = server.recv_from(&mut buffer).await.unwrap();
                let request = &buffer[..size];
                let reply = Reply::parse(request).unwrap();
                let transaction: [u8; 12] = request[8..20].try_into().unwrap();
                let signed = reply.attribute_value(MESSAGE_INTEGRITY).map(|tag| {
                    let at = size - 24;
                    let mut prefix = request[..at].to_vec();
                    prefix[2..4].copy_from_slice(&((at - 20 + 24) as u16).to_be_bytes());
                    integrity_tag(&prefix, &key) == tag
                });
                seen.push((
                    reply.kind,
                    signed,
                    reply.attribute_value(LIFETIME).map(<[u8]>::to_vec),
                ));
                let mut answer = Message::new(0);
                answer.transaction = transaction;
                answer.bytes[8..20].copy_from_slice(&transaction);
                match (reply.kind, signed) {
                    (ALLOCATE, None) => {
                        answer.bytes[0..2].copy_from_slice(&(ALLOCATE | 0x0110).to_be_bytes());
                        answer.attribute(ERROR_CODE, &[0, 0, 4, 1, b'U']);
                        answer.attribute(REALM, b"shroud");
                        answer.attribute(NONCE, b"n1");
                    }
                    (ALLOCATE, Some(true)) => {
                        answer.bytes[0..2].copy_from_slice(&(ALLOCATE | SUCCESS).to_be_bytes());
                        let cookie = MAGIC_COOKIE.to_be_bytes();
                        let ip = [203 ^ cookie[0], cookie[1], 113 ^ cookie[2], 9 ^ cookie[3]];
                        answer.attribute(
                            XOR_RELAYED_ADDRESS,
                            &[0, 1, 0, 0, ip[0], ip[1], ip[2], ip[3]],
                        );
                    }
                    _ => {
                        answer.bytes[0..2].copy_from_slice(&(reply.kind | SUCCESS).to_be_bytes());
                    }
                }
                server.send_to(&answer.bytes, peer).await.unwrap();
            }
            seen
        });
        let endpoint = Endpoint {
            scheme: Scheme::Turn,
            host: "127.0.0.1".into(),
            port: address.port(),
            transport: Transport::Udp,
        };
        let mut connection = Connection::open(&endpoint).await.unwrap();
        let login = Login {
            username: "1700000000:abc".into(),
            password: "secret".into(),
            minted: true,
        };
        allocate(&mut connection, &login).await.expect("allocated");
        let seen = fake.await.unwrap();
        assert_eq!(
            seen,
            vec![
                (ALLOCATE, None, None),
                (ALLOCATE, Some(true), None),
                (REFRESH, Some(true), Some(vec![0, 0, 0, 0])),
            ]
        );
    }

    #[tokio::test]
    async fn no_servers_is_one_off_line() {
        let lines = check(&[], None, 0).await;
        assert_eq!(lines.len(), 1);
        assert_eq!(lines[0].state, "off");
    }
}
