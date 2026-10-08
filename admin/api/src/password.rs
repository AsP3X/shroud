//! Operator passwords: argon2id, same cost the API uses for chat accounts (19 MiB, t=2, p=1).
//! The console asks for 12 characters because that is what the setup page enforces.

use std::sync::OnceLock;

use argon2::password_hash::rand_core::OsRng;
use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::{Algorithm, Argon2, Params, Version};

pub const MIN_CHARS: usize = 12;
pub const MAX_BYTES: usize = 1024;

pub fn acceptable(password: &str) -> Result<(), &'static str> {
    if password.chars().count() < MIN_CHARS {
        return Err("Use at least 12 characters.");
    }
    if password.len() > MAX_BYTES {
        return Err("That password is too long.");
    }
    Ok(())
}

fn argon() -> Argon2<'static> {
    let params = Params::new(19 * 1024, 2, 1, None).expect("argon2id parameters are valid");
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
}

#[derive(Debug)]
pub struct HashError;

pub fn hash(password: &str) -> Result<String, HashError> {
    let salt = SaltString::generate(&mut OsRng);
    let hashed = argon()
        .hash_password(password.as_bytes(), &salt)
        .map_err(|_| HashError)?;
    Ok(hashed.to_string())
}

pub fn verify(password: &str, password_hash: &str) -> Result<bool, HashError> {
    let parsed = PasswordHash::new(password_hash).map_err(|_| HashError)?;
    Ok(argon()
        .verify_password(password.as_bytes(), &parsed)
        .is_ok())
}

/// A real argon2id hash used when the operator does not exist, so the failure takes the same work.
pub fn dummy_hash() -> &'static str {
    static CELL: OnceLock<String> = OnceLock::new();
    CELL.get_or_init(|| hash("not-a-real-operator-password").expect("dummy argon2 hash"))
}

pub fn warm_dummy() {
    let _ = dummy_hash();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_a_short_password() {
        assert_eq!(acceptable("short"), Err("Use at least 12 characters."));
    }

    #[test]
    fn hash_and_verify() {
        let hashed = hash("correct-horse").unwrap();
        assert!(hashed.starts_with("$argon2id$"));
        assert!(verify("correct-horse", &hashed).unwrap());
        assert!(!verify("wrong-password-xx", &hashed).unwrap());
        assert!(!hashed.contains("correct-horse"));
    }
}
