//! RFC 6238 TOTP: HMAC-SHA1, 6 digits, 30-second step, one step of skew either way.
//! Authenticator apps still default to SHA1; the setup URI says so by saying nothing else.

use argon2::password_hash::rand_core::{OsRng, RngCore};
use data_encoding::BASE32_NOPAD;
use hmac::{Hmac, Mac};
use qrcode::QrCode;
use qrcode::render::svg;
use sha1::Sha1;
use subtle::ConstantTimeEq;

type HmacSha1 = Hmac<Sha1>;

pub fn hotp(secret: &[u8], counter: u64) -> Option<u32> {
    let mut mac = HmacSha1::new_from_slice(secret).ok()?;
    mac.update(&counter.to_be_bytes());
    let bytes = mac.finalize().into_bytes();
    let offset = (bytes[19] & 0x0f) as usize;
    let bin = u32::from_be_bytes([
        bytes[offset] & 0x7f,
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ]);
    Some(bin % 1_000_000)
}

pub fn accept(secret: &[u8], digits: &str, now_unix: i64) -> bool {
    if digits.len() != 6 || !digits.bytes().all(|byte| byte.is_ascii_digit()) {
        return false;
    }
    let Ok(given) = digits.parse::<u32>() else {
        return false;
    };
    let step = now_unix.div_euclid(30);
    let mut ok = subtle::Choice::from(0);
    for delta in -1i64..=1 {
        let counter = step + delta;
        if counter < 0 {
            continue;
        }
        let Some(code) = hotp(secret, counter as u64) else {
            continue;
        };
        ok |= code.to_ne_bytes().ct_eq(&given.to_ne_bytes());
    }
    bool::from(ok)
}

pub fn secret() -> [u8; 20] {
    let mut raw = [0u8; 20];
    OsRng.fill_bytes(&mut raw);
    raw
}

pub fn uri(account: &str, secret: &[u8]) -> String {
    format!(
        "otpauth://totp/Shroud:{}?secret={}&issuer=Shroud&period=30&digits=6",
        encode_component(account),
        BASE32_NOPAD.encode(secret),
    )
}

#[derive(Debug)]
pub struct QrError;

pub fn qr_svg(uri: &str) -> Result<String, QrError> {
    let code = QrCode::new(uri.as_bytes()).map_err(|_| QrError)?;
    Ok(code
        .render::<svg::Color<'_>>()
        .min_dimensions(160, 160)
        .build())
}

pub fn decode_secret(encoded: &str) -> Option<Vec<u8>> {
    BASE32_NOPAD
        .decode(encoded.trim().to_ascii_uppercase().as_bytes())
        .ok()
}

fn encode_component(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~') {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// RFC 6238 appendix B, SHA1, 8-digit codes reduced to 6 digits.
    #[test]
    fn rfc6238_sha1_vectors() {
        let secret = b"12345678901234567890";
        let cases = [
            (59_i64, 287082),
            (1_111_111_109, 81804),
            (1_111_111_111, 50471),
            (1_234_567_890, 5924),
            (2_000_000_000, 279037),
            (20_000_000_000, 353130),
        ];
        for (time, code) in cases {
            let counter = (time / 30) as u64;
            assert_eq!(hotp(secret, counter), Some(code), "time {time}");
        }
    }

    #[test]
    fn accepts_one_step_either_way() {
        let secret = b"12345678901234567890";
        let code = format!("{:06}", hotp(secret, 1).unwrap());
        assert!(accept(secret, &code, 59));
        assert!(accept(secret, &code, 59 - 30));
        assert!(accept(secret, &code, 59 + 30));
        assert!(!accept(secret, &code, 59 + 90));
        assert!(!accept(secret, "12", 59));
    }

    #[test]
    fn uri_and_qr_carry_the_secret() {
        let secret = b"12345678901234567890";
        let uri = uri("readonly", secret);
        assert!(uri.contains("secret="));
        assert!(uri.starts_with("otpauth://totp/Shroud:readonly?"));
        let svg = qr_svg(&uri).unwrap();
        assert!(svg.contains("<svg"));
    }
}
