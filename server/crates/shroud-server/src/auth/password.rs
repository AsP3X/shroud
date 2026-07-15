//! Password policy and argon2id hashing.

use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::{Algorithm, Argon2, Params, Version};
use password_hash::rand_core::OsRng;

use crate::error::AppError;

/// Minimum password length (inclusive).
pub const PASSWORD_MIN_LEN: usize = 8;

/// Embedded common-password denylist (lowercase). Expand with releases.
///
/// Human: Not exhaustive — rejects obvious weak choices at register/change.
/// Agent: READS static list; case-insensitive contains check on full password.
const COMMON_PASSWORDS: &[&str] = &[
    "password",
    "password1",
    "password123",
    "12345678",
    "123456789",
    "1234567890",
    "qwerty123",
    "qwertyui",
    "letmein1",
    "welcome1",
    "admin123",
    "iloveyou",
    "monkey12",
    "dragon12",
    "master12",
    "login123",
    "abc12345",
    "passw0rd",
    "changeme",
    "secret12",
    "football",
    "baseball",
    "sunshine",
    "princess",
    "superman",
    "trustno1",
    "whatever",
    "password!",
    "p@ssw0rd",
    "shroud123",
];

/// OWASP 2023 argon2id baseline: 19 MiB, t=2, p=1.
fn argon2() -> Result<Argon2<'static>, AppError> {
    let params = Params::new(19 * 1024, 2, 1, None)
        .map_err(|err| AppError::Internal(format!("invalid argon2 params: {err}")))?;
    Ok(Argon2::new(Algorithm::Argon2id, Version::V0x13, params))
}

/// Validates password policy before hashing.
pub fn validate_password_policy(password: &str) -> Result<(), AppError> {
    if password.len() < PASSWORD_MIN_LEN {
        return Err(AppError::password_too_short());
    }

    let lower = password.to_ascii_lowercase();
    if COMMON_PASSWORDS.iter().any(|entry| *entry == lower) {
        return Err(AppError::password_too_common());
    }

    Ok(())
}

/// Hashes a password with argon2id (PHC string).
///
/// Human: Never log the password or hash comparison details beyond success/fail.
/// Agent: CALLS argon2id; RETURNS PHC string for password_hash column.
pub fn hash_password(password: &str) -> Result<String, AppError> {
    validate_password_policy(password)?;
    let salt = SaltString::generate(&mut OsRng);
    let argon = argon2()?;
    let hash = argon
        .hash_password(password.as_bytes(), &salt)
        .map_err(|err| AppError::Internal(format!("password hash failed: {err}")))?;
    Ok(hash.to_string())
}

/// Verifies a password against a stored PHC hash.
pub fn verify_password(password: &str, password_hash: &str) -> Result<bool, AppError> {
    let parsed = PasswordHash::new(password_hash)
        .map_err(|err| AppError::Internal(format!("invalid stored password hash: {err}")))?;
    let argon = argon2()?;
    Ok(argon.verify_password(password.as_bytes(), &parsed).is_ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_short_and_common() {
        assert!(matches!(
            validate_password_policy("short"),
            Err(AppError::Api {
                code: "PASSWORD_TOO_SHORT",
                ..
            })
        ));
        assert!(matches!(
            validate_password_policy("password"),
            Err(AppError::Api {
                code: "PASSWORD_TOO_COMMON",
                ..
            })
        ));
    }

    #[test]
    fn hash_and_verify_roundtrip() {
        let hash = hash_password("correct-horse-battery").unwrap();
        assert!(verify_password("correct-horse-battery", &hash).unwrap());
        assert!(!verify_password("wrong-password-xx", &hash).unwrap());
    }
}
