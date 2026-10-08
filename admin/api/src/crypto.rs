//! Random tokens, SHA-256, and AES-256-GCM for the console's own secrets.
//!
//! Session ids, setup tokens and recovery codes are stored as SHA-256. The authenticator
//! secret is stored as AES-256-GCM ciphertext under `ADMIN_SECRET_KEY`.

use aes_gcm::aead::Aead;
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use argon2::password_hash::rand_core::{OsRng, RngCore};
use data_encoding::{BASE64URL_NOPAD, HEXLOWER};
use sha2::{Digest, Sha256};

const CODE_ALPHABET: &[u8] = b"abcdefghijkmnpqrstuvwxyz23456789";

pub fn key_from_hex(value: &str) -> Option<[u8; 32]> {
    let decoded = HEXLOWER
        .decode(value.trim().to_ascii_lowercase().as_bytes())
        .ok()?;
    if decoded.len() != 32 {
        return None;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&decoded);
    Some(key)
}

pub fn sha256(bytes: &[u8]) -> [u8; 32] {
    let digest = Sha256::digest(bytes);
    let mut out = [0u8; 32];
    out.copy_from_slice(&digest);
    out
}

pub fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    OsRng.fill_bytes(&mut buf);
    HEXLOWER.encode(&buf)
}

pub fn random_token() -> String {
    let mut buf = [0u8; 32];
    OsRng.fill_bytes(&mut buf);
    BASE64URL_NOPAD.encode(&buf)
}

/// Sixteen characters from an unambiguous alphabet, shown in groups of four.
pub fn recovery_code() -> String {
    let mut raw = [0u8; 16];
    OsRng.fill_bytes(&mut raw);
    let mut out = String::with_capacity(19);
    for (index, byte) in raw.iter().enumerate() {
        if index > 0 && index % 4 == 0 {
            out.push('-');
        }
        out.push(CODE_ALPHABET[(byte % 32) as usize] as char);
    }
    out
}

pub fn normalize_code(input: &str) -> String {
    input
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .flat_map(|c| c.to_lowercase())
        .collect()
}

#[derive(Debug)]
pub struct SealError;

pub fn seal(key: &[u8; 32], plaintext: &[u8]) -> Result<Vec<u8>, SealError> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| SealError)?;
    let mut nonce_bytes = [0u8; 12];
    OsRng.fill_bytes(&mut nonce_bytes);
    let mut nonce = Nonce::default();
    nonce.copy_from_slice(&nonce_bytes);
    let ciphertext = cipher.encrypt(&nonce, plaintext).map_err(|_| SealError)?;
    let mut out = Vec::with_capacity(nonce_bytes.len() + ciphertext.len());
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

pub fn open(key: &[u8; 32], blob: &[u8]) -> Result<Vec<u8>, SealError> {
    if blob.len() < 12 + 16 {
        return Err(SealError);
    }
    let (nonce_bytes, ciphertext) = blob.split_at(12);
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| SealError)?;
    let mut nonce = Nonce::default();
    nonce.copy_from_slice(nonce_bytes);
    cipher.decrypt(&nonce, ciphertext).map_err(|_| SealError)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn key_rejects_the_wrong_length() {
        assert!(key_from_hex("abcd").is_none());
        let key = key_from_hex(&"ab".repeat(32)).unwrap();
        assert_eq!(key[0], 0xab);
    }

    #[test]
    fn seal_roundtrip_and_wrong_key_fails() {
        let key = [7u8; 32];
        let other = [8u8; 32];
        let blob = seal(&key, b"totp-secret").unwrap();
        assert_eq!(open(&key, &blob).unwrap(), b"totp-secret");
        assert!(open(&other, &blob).is_err());
        assert!(
            !blob
                .windows(b"totp-secret".len())
                .any(|window| window == b"totp-secret")
        );
    }

    #[test]
    fn recovery_code_shape() {
        assert_eq!(CODE_ALPHABET.len(), 32);
        let code = recovery_code();
        assert_eq!(code.len(), 19);
        assert_eq!(normalize_code(&code.to_uppercase()).len(), 16);
    }
}
