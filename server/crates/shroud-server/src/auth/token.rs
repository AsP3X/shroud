//! Opaque session tokens: issue raw token once, store only SHA-256 hash.

use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use rand::RngCore;
use sha2::{Digest, Sha256};

use crate::error::AppError;

/// Number of random bytes in an opaque session token.
const TOKEN_BYTES: usize = 32;

/// Generates a new opaque session token and its storage hash.
///
/// Human: Return the raw token to the client once; persist only `token_hash`.
/// Agent: CSPRNG 32 bytes → base64url token; SHA-256 → BYTEA hash.
pub fn issue_session_token() -> Result<(String, Vec<u8>), AppError> {
    let mut bytes = [0_u8; TOKEN_BYTES];
    rand::rngs::OsRng
        .try_fill_bytes(&mut bytes)
        .map_err(|err| AppError::Internal(format!("token RNG failed: {err}")))?;

    let token = URL_SAFE_NO_PAD.encode(bytes);
    let token_hash = hash_token(&token);
    Ok((token, token_hash))
}

/// Hashes a presented bearer token for database lookup.
pub fn hash_token(token: &str) -> Vec<u8> {
    Sha256::digest(token.as_bytes()).to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn issue_is_unique_and_hash_stable() {
        let (t1, h1) = issue_session_token().unwrap();
        let (t2, h2) = issue_session_token().unwrap();
        assert_ne!(t1, t2);
        assert_ne!(h1, h2);
        assert_eq!(hash_token(&t1), h1);
    }
}
