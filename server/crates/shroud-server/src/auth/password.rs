//! Password policy and argon2id hashing.

use std::sync::OnceLock;
use std::time::Duration;

use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::{Algorithm, Argon2, Params, Version};
use password_hash::rand_core::OsRng;
use tokio::sync::Semaphore;

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

/// How long a request waits for an argon2 slot before it is told to retry.
const HASH_SLOT_WAIT: Duration = Duration::from_secs(5);

/// Argon2 runs that may run at once, one per core: each holds 19 MiB and a blocking thread.
///
/// Human: Without a bound, a flood of logins would take every runtime worker (when hashed in
/// place) or grow memory without limit (when queued on blocking threads). Past the bound a request
/// waits a few seconds, then gets `RATE_LIMITED`, which every client already retries.
fn hash_slots() -> &'static Semaphore {
    static SLOTS: OnceLock<Semaphore> = OnceLock::new();
    SLOTS.get_or_init(|| {
        let cores = std::thread::available_parallelism().map_or(2, |cores| cores.get());
        Semaphore::new(cores.max(2))
    })
}

/// Runs one argon2 job on a blocking thread once a slot is free.
async fn run_hashing<T: Send + 'static>(
    job: impl FnOnce() -> Result<T, AppError> + Send + 'static,
) -> Result<T, AppError> {
    run_in_slot(hash_slots(), HASH_SLOT_WAIT, job).await
}

async fn run_in_slot<T: Send + 'static>(
    slots: &Semaphore,
    wait: Duration,
    job: impl FnOnce() -> Result<T, AppError> + Send + 'static,
) -> Result<T, AppError> {
    let _slot = tokio::time::timeout(wait, slots.acquire())
        .await
        .map_err(|_| AppError::rate_limited_after(1))?
        .map_err(|err| AppError::Internal(format!("argon2 slots closed: {err}")))?;
    tokio::task::spawn_blocking(job)
        .await
        .map_err(|err| AppError::Internal(format!("argon2 task failed: {err}")))?
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

/// Hashes a password with argon2id (PHC string), off the runtime.
///
/// Human: Never log the password or hash comparison details beyond success/fail.
/// Agent: CALLS argon2id; RETURNS PHC string for password_hash column.
pub async fn hash_password(password: &str) -> Result<String, AppError> {
    validate_password_policy(password)?;
    let password = password.to_owned();
    run_hashing(move || hash_blocking(&password)).await
}

/// Verifies a password against a stored PHC hash, off the runtime.
pub async fn verify_password(password: &str, password_hash: &str) -> Result<bool, AppError> {
    let (password, password_hash) = (password.to_owned(), password_hash.to_owned());
    run_hashing(move || verify_blocking(&password, &password_hash)).await
}

/// The same work as a wrong password for an account that exists, for a name that does not.
pub async fn verify_unknown_user(password: &str) -> Result<(), AppError> {
    let password = password.to_owned();
    run_hashing(move || {
        static DUMMY: OnceLock<String> = OnceLock::new();
        let dummy = match DUMMY.get() {
            Some(dummy) => dummy,
            None => {
                let hash = hash_blocking("invalid-login-padding-xx")?;
                DUMMY.get_or_init(|| hash)
            }
        };
        verify_blocking(&password, dummy).map(|_| ())
    })
    .await
}

fn hash_blocking(password: &str) -> Result<String, AppError> {
    let salt = SaltString::generate(&mut OsRng);
    let argon = argon2()?;
    let hash = argon
        .hash_password(password.as_bytes(), &salt)
        .map_err(|err| AppError::Internal(format!("password hash failed: {err}")))?;
    Ok(hash.to_string())
}

fn verify_blocking(password: &str, password_hash: &str) -> Result<bool, AppError> {
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

    #[tokio::test]
    async fn hash_and_verify_roundtrip() {
        let hash = hash_password("correct-horse-battery").await.unwrap();
        assert!(
            verify_password("correct-horse-battery", &hash)
                .await
                .unwrap()
        );
        assert!(!verify_password("wrong-password-xx", &hash).await.unwrap());
        verify_unknown_user("correct-horse-battery").await.unwrap();
    }

    #[tokio::test]
    async fn a_full_set_of_slots_answers_rate_limited() {
        let slots = Semaphore::new(1);
        let held = slots.acquire().await.unwrap();
        let busy = run_in_slot(&slots, Duration::from_millis(20), || Ok(())).await;
        assert!(matches!(busy, Err(AppError::RateLimited { .. })));
        drop(held);
        run_in_slot(&slots, Duration::from_millis(20), || Ok(()))
            .await
            .unwrap();
    }
}
