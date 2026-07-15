//! Username validation and case-folding for account identity.

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
}
