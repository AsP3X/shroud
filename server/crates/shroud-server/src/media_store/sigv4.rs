//! AWS Signature Version 4 for the media store's requests to Nebular OS.
//!
//! Only the header-signed form with a SHA-256 payload hash. Nebular checks the signature, the
//! payload hash while the body streams, and a ±15 minute window around `x-amz-date`, so a
//! captured request can't be replayed later or carry a different body.

use ring::hmac;
use sha2::{Digest, Sha256};

pub const ALGORITHM: &str = "AWS4-HMAC-SHA256";
const SERVICE: &str = "s3";
const TERMINATOR: &str = "aws4_request";

/// SHA-256 of an empty body: the payload hash of GET, HEAD and DELETE.
pub const EMPTY_PAYLOAD_SHA256: &str =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

/// An access key and the region its requests are scoped to.
#[derive(Clone)]
pub struct Credentials {
    pub access_key_id: String,
    pub secret_access_key: String,
    pub region: String,
}

impl std::fmt::Debug for Credentials {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Credentials")
            .field("access_key_id", &self.access_key_id)
            .field("secret_access_key", &"[REDACTED]")
            .field("region", &self.region)
            .finish()
    }
}

/// One request to sign.
pub struct Request<'a> {
    pub method: &'a str,
    /// The request path exactly as sent, encoded with [`encode_path`].
    pub canonical_uri: &'a str,
    /// Every header to sign, including `host`, `x-amz-date`, `x-amz-content-sha256` and any
    /// other `x-amz-*` header the request carries (Nebular refuses unsigned ones).
    pub headers: &'a [(&'a str, &'a str)],
    /// Hex SHA-256 of the body.
    pub payload_sha256: &'a str,
}

/// The `Authorization` header for `request`, signed at `amz_date` (`YYYYMMDDTHHMMSSZ`).
pub fn authorization(creds: &Credentials, request: &Request<'_>, amz_date: &str) -> String {
    let date = amz_date.get(..8).unwrap_or(amz_date);
    let mut headers: Vec<(String, String)> = request
        .headers
        .iter()
        .map(|(name, value)| (name.to_ascii_lowercase(), collapse_whitespace(value)))
        .collect();
    headers.sort();
    let mut canonical_headers = String::new();
    for (name, value) in &headers {
        canonical_headers.push_str(name);
        canonical_headers.push(':');
        canonical_headers.push_str(value);
        canonical_headers.push('\n');
    }
    let signed_headers = headers
        .iter()
        .map(|(name, _)| name.as_str())
        .collect::<Vec<_>>()
        .join(";");
    // The query string is always empty here, hence the blank line after the URI.
    let canonical_request = format!(
        "{}\n{}\n\n{canonical_headers}\n{signed_headers}\n{}",
        request.method, request.canonical_uri, request.payload_sha256
    );
    let scope = format!("{date}/{}/{SERVICE}/{TERMINATOR}", creds.region);
    let string_to_sign = format!(
        "{ALGORITHM}\n{amz_date}\n{scope}\n{}",
        sha256_hex(canonical_request.as_bytes())
    );
    let key = signing_key(&creds.secret_access_key, date, &creds.region);
    let signature = hex(hmac::sign(&key, string_to_sign.as_bytes()).as_ref());
    format!(
        "{ALGORITHM} Credential={}/{scope}, SignedHeaders={signed_headers}, Signature={signature}",
        creds.access_key_id
    )
}

/// `/{bucket}/{key}` with every byte outside RFC 3986's unreserved set percent-encoded once
/// (`/` kept), which is what S3 and Nebular sign as the canonical URI.
pub fn encode_path(bucket: &str, key: &str) -> String {
    let mut path = String::with_capacity(bucket.len() + key.len() + 2);
    path.push('/');
    uri_encode_into(&mut path, bucket.as_bytes());
    path.push('/');
    uri_encode_into(&mut path, key.as_bytes());
    path
}

/// Lowercase hex SHA-256.
pub fn sha256_hex(bytes: &[u8]) -> String {
    hex(&Sha256::digest(bytes))
}

fn signing_key(secret: &str, date: &str, region: &str) -> hmac::Key {
    let derive = |key: &[u8], data: &str| {
        hmac::sign(&hmac::Key::new(hmac::HMAC_SHA256, key), data.as_bytes())
    };
    let k_date = derive(format!("AWS4{secret}").as_bytes(), date);
    let k_region = derive(k_date.as_ref(), region);
    let k_service = derive(k_region.as_ref(), SERVICE);
    let k_signing = derive(k_service.as_ref(), TERMINATOR);
    hmac::Key::new(hmac::HMAC_SHA256, k_signing.as_ref())
}

fn uri_encode_into(out: &mut String, bytes: &[u8]) {
    for &b in bytes {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~' | b'/') {
            out.push(char::from(b));
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
}

fn collapse_whitespace(value: &str) -> String {
    value.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        let _ = write!(out, "{b:02x}");
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    // The worked examples from AWS's "Signature Calculations for the Authorization Header:
    // Transferring Payload in a Single Chunk" page for S3 (the GET one is also the vector
    // Nebular's own verifier is tested with).
    fn aws_example_key() -> Credentials {
        Credentials {
            access_key_id: "AKIAIOSFODNN7EXAMPLE".into(),
            secret_access_key: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".into(),
            region: "us-east-1".into(),
        }
    }

    #[test]
    fn aws_get_object_example() {
        let headers = [
            ("host", "examplebucket.s3.amazonaws.com"),
            ("range", "bytes=0-9"),
            ("x-amz-content-sha256", EMPTY_PAYLOAD_SHA256),
            ("x-amz-date", "20130524T000000Z"),
        ];
        let request = Request {
            method: "GET",
            canonical_uri: "/test.txt",
            headers: &headers,
            payload_sha256: EMPTY_PAYLOAD_SHA256,
        };
        assert_eq!(
            authorization(&aws_example_key(), &request, "20130524T000000Z"),
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, \
             SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, \
             Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
        );
    }

    #[test]
    fn aws_put_object_example() {
        let payload = sha256_hex(b"Welcome to Amazon S3.");
        assert_eq!(
            payload,
            "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072"
        );
        // Header names arrive in any case and order; the signer lowercases and sorts them.
        let headers = [
            ("X-Amz-Storage-Class", "REDUCED_REDUNDANCY"),
            ("Host", "examplebucket.s3.amazonaws.com"),
            ("Date", "Fri, 24 May 2013 00:00:00 GMT"),
            ("x-amz-date", "20130524T000000Z"),
            ("x-amz-content-sha256", payload.as_str()),
        ];
        let request = Request {
            method: "PUT",
            canonical_uri: "/test%24file.text",
            headers: &headers,
            payload_sha256: &payload,
        };
        assert_eq!(
            authorization(&aws_example_key(), &request, "20130524T000000Z"),
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, \
             SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, \
             Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"
        );
    }

    #[test]
    fn a_different_body_date_or_key_changes_the_signature() {
        let sign = |creds: &Credentials, payload: &str, date: &str| {
            let headers = [
                ("host", "nebular:9000"),
                ("x-amz-content-sha256", payload),
                ("x-amz-date", date),
            ];
            authorization(
                creds,
                &Request {
                    method: "PUT",
                    canonical_uri: "/shroud-media/media/ab/x",
                    headers: &headers,
                    payload_sha256: payload,
                },
                date,
            )
        };
        let key = aws_example_key();
        let base = sign(&key, &sha256_hex(b"one"), "20260925T120000Z");
        assert_ne!(base, sign(&key, &sha256_hex(b"two"), "20260925T120000Z"));
        assert_ne!(base, sign(&key, &sha256_hex(b"one"), "20260925T120001Z"));
        let other = Credentials {
            secret_access_key: "another-secret".into(),
            ..aws_example_key()
        };
        assert_ne!(base, sign(&other, &sha256_hex(b"one"), "20260925T120000Z"));
    }

    #[test]
    fn encode_path_keeps_unreserved_and_slashes() {
        assert_eq!(
            encode_path(
                "shroud-media",
                "media/0f/0f6d1c2e-aaaa-4bbb-8ccc-123456789abc"
            ),
            "/shroud-media/media/0f/0f6d1c2e-aaaa-4bbb-8ccc-123456789abc"
        );
        assert_eq!(encode_path("b", "a b/c+d%é"), "/b/a%20b/c%2Bd%25%C3%A9");
    }

    #[test]
    fn debug_redacts_the_secret() {
        let shown = format!("{:?}", aws_example_key());
        assert!(shown.contains("AKIAIOSFODNN7EXAMPLE"));
        assert!(!shown.contains("wJalrXUtnFEMI"));
    }
}
