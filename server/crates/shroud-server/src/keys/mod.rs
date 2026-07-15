//! Pre-key validation helpers (public material only).

use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};

use crate::error::AppError;

/// Maximum OTPKs stored per device.
pub const OTPK_POOL_MAX: i64 = 200;
/// Maximum OTPKs accepted in one request body.
pub const OTPK_BATCH_MAX: usize = 100;
/// Client refills when remaining count is under this value.
pub const OTPK_REFILL_THRESHOLD: i64 = 25;

pub const REGISTRATION_ID_MAX: i32 = 16383;
pub const KEY_ID_MAX: i32 = 16_777_215; // 0xFFFFFF

const PUBLIC_KEY_MIN: usize = 32;
const PUBLIC_KEY_MAX: usize = 64;
const SIGNATURE_MIN: usize = 64;
const SIGNATURE_MAX: usize = 128;

/// Decodes a standard Base64 public key and checks length bounds.
pub fn decode_public_key(label: &str, value: &str) -> Result<Vec<u8>, AppError> {
    let bytes = decode_b64(label, value)?;
    if bytes.len() < PUBLIC_KEY_MIN || bytes.len() > PUBLIC_KEY_MAX {
        return Err(AppError::validation(format!(
            "{label} must decode to {PUBLIC_KEY_MIN}–{PUBLIC_KEY_MAX} bytes."
        )));
    }
    Ok(bytes)
}

/// Decodes a standard Base64 signature and checks length bounds.
pub fn decode_signature(label: &str, value: &str) -> Result<Vec<u8>, AppError> {
    let bytes = decode_b64(label, value)?;
    if bytes.len() < SIGNATURE_MIN || bytes.len() > SIGNATURE_MAX {
        return Err(AppError::validation(format!(
            "{label} must decode to {SIGNATURE_MIN}–{SIGNATURE_MAX} bytes."
        )));
    }
    Ok(bytes)
}

pub fn encode_b64(bytes: &[u8]) -> String {
    BASE64.encode(bytes)
}

fn decode_b64(label: &str, value: &str) -> Result<Vec<u8>, AppError> {
    BASE64
        .decode(value.trim().as_bytes())
        .map_err(|_| AppError::validation(format!("{label} must be valid standard Base64.")))
}

pub fn validate_registration_id(id: i32) -> Result<(), AppError> {
    if !(0..=REGISTRATION_ID_MAX).contains(&id) {
        return Err(AppError::validation(format!(
            "registration_id must be between 0 and {REGISTRATION_ID_MAX}."
        )));
    }
    Ok(())
}

pub fn validate_key_id(id: i32) -> Result<(), AppError> {
    if !(0..=KEY_ID_MAX).contains(&id) {
        return Err(AppError::validation(format!(
            "key_id must be between 0 and {KEY_ID_MAX}."
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_32_byte_key() {
        let raw = vec![7_u8; 32];
        let encoded = encode_b64(&raw);
        assert_eq!(decode_public_key("identity_key", &encoded).unwrap(), raw);
    }

    #[test]
    fn rejects_short_key() {
        let encoded = encode_b64(&[1, 2, 3]);
        assert!(decode_public_key("identity_key", &encoded).is_err());
    }
}
