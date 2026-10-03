//! Username validation and case-folding for account identity.

use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use sha2::{Digest, Sha256};

use crate::error::AppError;

/// Minimum username length (inclusive).
pub const USERNAME_MIN_LEN: usize = 3;
/// Maximum username length (inclusive).
pub const USERNAME_MAX_LEN: usize = 32;

/// Usernames that must never be registered (case-folded match).
const RESERVED_EXACT: &[&str] = &[
    "admin",
    "administrator",
    "support",
    "help",
    "shroud",
    "system",
    "root",
    "security",
    "null",
    "undefined",
    "api",
    "www",
    "mail",
    "email",
    "mod",
    "moderator",
    "staff",
    "official",
    "everyone",
    "all",
    "me",
    "self",
    "owner",
];

/// Case-folded prefixes that are blocked.
const RESERVED_PREFIXES: &[&str] = &["shroud_", "system_", "admin_", "support_"];

/// Normalizes and validates a username for storage/lookup.
///
/// Human: Store only the case-folded form; uniqueness is case-insensitive.
/// Agent: RETURNS lowercase [a-z0-9_] 3–32 or AppError validation/reserved.
pub fn normalize_username(raw: &str) -> Result<String, AppError> {
    let trimmed = raw.trim();
    if trimmed.len() < USERNAME_MIN_LEN || trimmed.len() > USERNAME_MAX_LEN {
        return Err(AppError::validation(format!(
            "Username must be between {USERNAME_MIN_LEN} and {USERNAME_MAX_LEN} characters."
        )));
    }

    if !trimmed
        .chars()
        .all(|c| c.is_ascii_lowercase() || c.is_ascii_uppercase() || c.is_ascii_digit() || c == '_')
    {
        return Err(AppError::validation(
            "Username may only contain letters, digits, and underscores.",
        ));
    }

    let folded = trimmed.to_ascii_lowercase();

    if RESERVED_EXACT.contains(&folded.as_str())
        || RESERVED_PREFIXES
            .iter()
            .any(|prefix| folded.starts_with(prefix))
    {
        return Err(AppError::username_reserved());
    }

    Ok(folded)
}

/// SHA-256 of a normalized username, standard Base64. This is the only form that is sent or stored.
pub fn username_hash_b64(normalized: impl AsRef<str>) -> String {
    let digest = Sha256::digest(normalized.as_ref().as_bytes());
    BASE64.encode(digest)
}

/// Decode a client-supplied login hash. The name itself never arrives.
pub fn parse_username_hash(raw: &str) -> Result<[u8; 32], AppError> {
    let bytes = BASE64
        .decode(raw.trim().as_bytes())
        .ok()
        .filter(|bytes| bytes.len() == 32)
        .ok_or_else(|| AppError::validation("username_hash must be a Base64 SHA-256 digest."))?;
    let mut hash = [0u8; 32];
    hash.copy_from_slice(&bytes);
    Ok(hash)
}

/// [`parse_username_hash`], and a reserved name's hash is refused. Used at registration only.
pub fn decode_username_hash(raw: &str) -> Result<[u8; 32], AppError> {
    let hash = parse_username_hash(raw)?;
    if reserved_hashes().iter().any(|reserved| reserved == &hash) {
        return Err(AppError::username_reserved());
    }
    Ok(hash)
}

fn reserved_hashes() -> &'static [[u8; 32]] {
    use std::sync::OnceLock;
    static HASHES: OnceLock<Vec<[u8; 32]>> = OnceLock::new();
    HASHES.get_or_init(|| {
        let mut names = Vec::new();
        for name in RESERVED_EXACT {
            names.push(hash_bytes(name));
        }
        // Prefixes are not enumerable. Clients refuse them before hashing; a hash of a
        // prefixed name is not in this list, and the server has no name to inspect.
        let _ = RESERVED_PREFIXES;
        names
    })
}

fn hash_bytes(normalized: &str) -> [u8; 32] {
    let digest = Sha256::digest(normalized.as_bytes());
    let mut hash = [0u8; 32];
    hash.copy_from_slice(&digest);
    hash
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_valid_username() {
        assert_eq!(normalize_username("Alice_1").unwrap(), "alice_1");
    }

    #[test]
    fn rejects_reserved() {
        let err = normalize_username("Admin").unwrap_err();
        assert!(matches!(
            err,
            AppError::Api {
                code: "USERNAME_RESERVED",
                ..
            }
        ));
    }

    #[test]
    fn rejects_prefix() {
        assert!(normalize_username("shroud_bot").is_err());
    }

    #[test]
    fn rejects_short() {
        assert!(normalize_username("ab").is_err());
    }

    #[test]
    fn hash_is_sha256_standard_base64() {
        // Pinned so every client hashes the same way. SHA-256("alice"), standard Base64.
        assert_eq!(
            username_hash_b64("alice"),
            "K9gGyX8OAK8aH8Myj6djqSaXI8jbj6xPk69x2xhtbpA="
        );
    }

    #[test]
    fn reserved_exact_name_is_refused_from_its_hash() {
        let hash = username_hash_b64("admin");
        assert!(decode_username_hash(&hash).is_err());
        assert!(parse_username_hash(&hash).is_ok());
    }
}
