//! Link-preview relay: which websites the web client may reach, and how the relay connects.
//!
//! Human: A browser cannot read other websites (CORS, and the web client's own CSP), and
//! fetching a link *for* the web client would hand this server the message's plaintext. So the
//! browser runs TLS itself (rustls compiled to WebAssembly) and the relay only moves its
//! encrypted bytes to port 443 of one public host — like Signal's link-preview proxy, the
//! server learns which host was contacted, never the path, the headers, or the page. The
//! website in turn sees this server's address instead of the user's.
//! Agent: DNS lookup → every address must be public → TCP connect to one of *those* addresses
//! (never a second lookup a rebinding DNS could answer differently). NEVER logs the host.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::Duration;

use tokio::net::TcpStream;
use tokio::time::timeout;

/// The only port a production relay connects to.
pub const HTTPS_PORT: u16 = 443;
/// Upper bound for the DNS lookup.
const DNS_TIMEOUT: Duration = Duration::from_secs(5);
/// Upper bound for one TCP connect attempt.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);
/// Addresses tried per connection (a host's first few A/AAAA records).
const MAX_CONNECT_ATTEMPTS: usize = 3;

/// Where the relay may connect.
#[derive(Debug, Clone, Copy)]
pub struct RelayPolicy {
    port: u16,
    allow_private: bool,
}

impl RelayPolicy {
    /// Public DNS names only, port 443.
    pub fn production() -> Self {
        Self {
            port: HTTPS_PORT,
            allow_private: false,
        }
    }

    /// Integration tests: loopback and local names allowed, on the test listener's `port`.
    pub fn for_tests(port: u16) -> Self {
        Self {
            port,
            allow_private: true,
        }
    }
}

/// Why a relay connection was refused.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RelayRejection {
    /// Not a DNS name: an IP literal, a port, a path, or invalid characters.
    InvalidHost,
    /// A local name (`localhost`, `.local`, …) or a name that resolves to a private,
    /// loopback, link-local, or otherwise non-public address.
    NotPublic,
    /// The name does not resolve.
    Unresolvable,
    /// No address accepted a connection in time.
    Unreachable,
}

/// The lowercase DNS name in `raw`, or `InvalidHost`.
///
/// Human: The browser already hands over `URL.hostname` (punycode for international names),
/// so anything that is not plain `a-z 0-9 - .` is refused rather than interpreted.
/// Agent: Pure. Rejects IP literals (dotted digits, anything with `:`), empty labels, labels
/// over 63 characters, and names over 253.
pub fn normalized_host(raw: &str) -> Result<String, RelayRejection> {
    let host = raw.trim().trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() || host.len() > 253 {
        return Err(RelayRejection::InvalidHost);
    }
    let labels_valid = host.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    });
    if !labels_valid {
        return Err(RelayRejection::InvalidHost);
    }
    // Dotted digits (and the all-digit shorthands resolvers accept) are addresses, not names.
    if host
        .split('.')
        .all(|label| label.bytes().all(|byte| byte.is_ascii_digit()))
    {
        return Err(RelayRejection::InvalidHost);
    }
    Ok(host)
}

/// True for a name that belongs to the public DNS — dotted and not a local suffix.
pub fn is_public_name(host: &str) -> bool {
    const LOCAL_SUFFIXES: [&str; 8] = [
        ".local",
        ".localhost",
        ".internal",
        ".lan",
        ".home",
        ".arpa",
        ".intranet",
        ".corp",
    ];
    host.contains('.') && !LOCAL_SUFFIXES.iter().any(|suffix| host.ends_with(suffix))
}

/// True for a globally routable unicast address.
///
/// Agent: Mirrors iOS `LinkPreviewFetcher.isGloballyRoutableIPv4/IPv6` so both clients'
/// fetches follow the same rules. IPv4 inside IPv6 (mapped, NAT64, 6to4) counts as its IPv4.
pub fn is_globally_routable(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => is_globally_routable_v4(v4),
        IpAddr::V6(v6) => is_globally_routable_v6(v6),
    }
}

fn is_globally_routable_v4(ip: Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    let reserved = a == 0
        || a == 10
        || a == 127
        || (a == 100 && (b & 0xC0) == 64) // 100.64/10 carrier-grade NAT
        || (a == 169 && b == 254)
        || (a == 172 && (b & 0xF0) == 16)
        || (a == 192 && b == 0 && c == 0)
        || (a == 192 && b == 0 && c == 2)
        || (a == 192 && b == 168)
        || (a == 198 && (b & 0xFE) == 18) // 198.18/15 benchmarking
        || (a == 198 && b == 51 && c == 100)
        || (a == 203 && b == 0 && c == 113)
        || a >= 224; // multicast, reserved, broadcast
    !reserved
}

fn is_globally_routable_v6(ip: Ipv6Addr) -> bool {
    if let Some(v4) = ip.to_ipv4_mapped() {
        return is_globally_routable_v4(v4);
    }
    let [s0, s1, s2, s3, s4, s5, s6, s7] = ip.segments();
    let embedded = |hi: u16, lo: u16| Ipv4Addr::from((u32::from(hi) << 16) | u32::from(lo));
    // 64:ff9b::/96 (NAT64) carries an IPv4 address in its last 32 bits.
    if [s0, s1, s2, s3, s4, s5] == [0x0064, 0xff9b, 0, 0, 0, 0] {
        return is_globally_routable_v4(embedded(s6, s7));
    }
    // 2002::/16 (6to4) carries one in bits 16–47.
    if s0 == 0x2002 {
        return is_globally_routable_v4(embedded(s1, s2));
    }
    let reserved = ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_multicast()
        || ip.is_unique_local()
        || ip.is_unicast_link_local()
        || (s0 == 0x2001 && s1 == 0x0db8) // documentation
        || (s0 == 0x2001 && s1 == 0x0000); // Teredo
    !reserved
}

/// Resolves `host` and connects to it on the policy's port.
///
/// Human: The addresses are checked *before* connecting and the connection goes to exactly
/// those addresses, so a name cannot pass the check with a public address and then connect
/// to a private one.
/// Agent: DNS (≤ 5 s) → reject if any address is not public (production) → TCP connect to up
/// to three of them (≤ 8 s each). RETURNS the stream or why it was refused; logs nothing.
pub async fn connect(policy: RelayPolicy, raw_host: &str) -> Result<TcpStream, RelayRejection> {
    let host = normalized_host(raw_host)?;
    if !policy.allow_private && !is_public_name(&host) {
        return Err(RelayRejection::NotPublic);
    }
    let addresses: Vec<SocketAddr> = match timeout(
        DNS_TIMEOUT,
        tokio::net::lookup_host((host.as_str(), policy.port)),
    )
    .await
    {
        Ok(Ok(found)) => found.collect(),
        Ok(Err(_)) | Err(_) => return Err(RelayRejection::Unresolvable),
    };
    if addresses.is_empty() {
        return Err(RelayRejection::Unresolvable);
    }
    if !policy.allow_private
        && addresses
            .iter()
            .any(|address| !is_globally_routable(address.ip()))
    {
        return Err(RelayRejection::NotPublic);
    }
    for address in addresses.iter().take(MAX_CONNECT_ATTEMPTS) {
        if let Ok(Ok(stream)) = timeout(CONNECT_TIMEOUT, TcpStream::connect(address)).await {
            return Ok(stream);
        }
    }
    Err(RelayRejection::Unreachable)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_are_normalized_and_literals_refused() {
        assert_eq!(normalized_host("Example.COM.").unwrap(), "example.com");
        assert_eq!(
            normalized_host("xn--mnchen-3ya.de").unwrap(),
            "xn--mnchen-3ya.de"
        );
        for bad in [
            "",
            "127.0.0.1",
            "10.1.2.3",
            "2130706433",
            "[::1]",
            "::1",
            "example.com:8443",
            "example.com/path",
            "exa mple.com",
            "-bad.example.com",
            "bad-.example.com",
            "a..b",
            "münchen.de",
        ] {
            assert_eq!(
                normalized_host(bad),
                Err(RelayRejection::InvalidHost),
                "{bad}"
            );
        }
        let long_label = format!("{}.com", "a".repeat(64));
        assert_eq!(
            normalized_host(&long_label),
            Err(RelayRejection::InvalidHost)
        );
    }

    #[test]
    fn local_names_are_not_public() {
        assert!(is_public_name("example.com"));
        for local in [
            "localhost",
            "intranet",
            "printer.local",
            "nas.home",
            "db.internal",
            "x.localhost",
            "1.0.0.127.in-addr.arpa",
        ] {
            assert!(!is_public_name(local), "{local}");
        }
    }

    #[test]
    fn only_public_addresses_are_routable() {
        for public in [
            "1.1.1.1",
            "8.8.8.8",
            "2606:4700:4700::1111",
            "64:ff9b::101:101",
        ] {
            assert!(is_globally_routable(public.parse().unwrap()), "{public}");
        }
        for private in [
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.5.5",
            "192.0.2.1",
            "192.168.1.1",
            "198.18.0.1",
            "224.0.0.1",
            "255.255.255.255",
            "::",
            "::1",
            "::ffff:127.0.0.1",
            "::ffff:192.168.0.1",
            "64:ff9b::a00:1",
            "2002:c0a8:0101::1",
            "fc00::1",
            "fd12::1",
            "fe80::1",
            "ff02::1",
            "2001:db8::1",
            "2001::1",
        ] {
            assert!(!is_globally_routable(private.parse().unwrap()), "{private}");
        }
    }

    #[tokio::test]
    async fn production_policy_refuses_local_targets_before_dns() {
        let policy = RelayPolicy::production();
        assert_eq!(
            connect(policy, "localhost").await.err(),
            Some(RelayRejection::NotPublic)
        );
        assert_eq!(
            connect(policy, "127.0.0.1").await.err(),
            Some(RelayRejection::InvalidHost)
        );
        assert_eq!(
            connect(policy, "printer.local").await.err(),
            Some(RelayRejection::NotPublic)
        );
    }
}
