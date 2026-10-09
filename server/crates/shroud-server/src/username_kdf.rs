//! Slow username lookup hash: argon2id with a per-server salt.
//!
//! The salt is public (`GET /auth/username-kdf`). A secret pepper would not help someone who
//! already has the database. Cost parameters are fixed so a rewritten config cannot make a
//! guess cheap. Existing SHA-256 rows move to this digest on the next successful login.

use std::sync::{Arc, OnceLock};

use argon2::{Algorithm, Argon2, Params, Version};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use serde::Serialize;

use crate::error::AppError;
use crate::reserved_names::RESERVED_EXACT;

/// Memory cost in kibibytes (64 MiB). One guess is about a quarter-second on a server CPU.
pub const MEMORY_KIB: u32 = 65_536;
/// Time cost. Parallelism stays 1 so one core pays the full time.
pub const ITERATIONS: u32 = 8;
pub const PARALLELISM: u32 = 1;
/// RFC 9106 version 0x13. Clients see this as JSON `19`.
pub const VERSION: u32 = 0x13;
pub const OUTPUT_LEN: usize = 32;
pub const SALT_LEN: usize = 16;

/// Published interoperability salt. Production refuses it, so a copy of the docs is not a
/// rainbow table for a live server.
pub const TEST_SALT_HEX: &str = "00112233445566778899aabbccddeeff";
const TEST_SALT: [u8; 16] = decode_hex(TEST_SALT_HEX);

/// Argon2id of each [`RESERVED_EXACT`] name under [`TEST_SALT`]. Tests compare bytes and never
/// recompute the list. Index 0 is `admin`.
const TEST_RESERVED: [[u8; 32]; 23] = [
    decode_hex("87d029ce18f6f40faccbb9adcd39b28bd2f3ddd36f53a7a7a6b22303978d459b"),
    decode_hex("198b9534ad2602a5ed59543b2b67019e2ae6b0b7186a5b2772e5a30594470ae1"),
    decode_hex("2b0b3f427a5a4f53c1a6e693e4de138c5be6b720fdd8cac2957f01b3c7f4e7da"),
    decode_hex("2697bb8f71769bfea84ce76d75ab2adb2707ad0c13aaed6cbdf643ccb4758880"),
    decode_hex("01054a4b1b36803ce90686e35e6b3f747b8d73020cc6e534e067ec5da3062a2f"),
    decode_hex("999bf05a4d12e0c0b4c407f97fa2fa6034ea11ba300dc99a5da15c5c7afb5d20"),
    decode_hex("280e0dfcccb6ec44e83385cb05fe9e29027b094fbb95840c4121a2808ee2324b"),
    decode_hex("267b86093ac6034cc1a5754926e99e54d034d0f33fc4a2d14c890042f8c178a4"),
    decode_hex("ad821c645a5864d87cd3b8b581c12c72629a4b7e19507b8e5bf5b49401326a5f"),
    decode_hex("1d67176c089d512ad4932516f3d0bc6da87b1c41cacf03e13d0bf05271c16401"),
    decode_hex("01714b0ca3b9f89e0dd265bd7d03675d0e4cce591695609e235c11e85b4f9e48"),
    decode_hex("0737b025bbcdbfa70a18b2899c91fa0904ac85aa4eae76cd4601fe32775bb9f7"),
    decode_hex("89d13a0dc8c41c43c1f7f8de8f7957a2dc4fc6547c505ad3524d74201cc11570"),
    decode_hex("cb656c112da590538b0895567820158871cd5305dca7943437c385f2635a1b5b"),
    decode_hex("728f55588e44ca967d8da7b94897c574bcd739dc5cb8be7c19376ffb120520ee"),
    decode_hex("58b72333ba5b8b7221540755508cbcb8a8293a6fd0097eed31999851bd3ed941"),
    decode_hex("5cba6ec4af9f4aa4c73752c5e7c85f260b284fe70b8c4a34e24687fbb36d4a75"),
    decode_hex("5a496be15d66c411db32b4bb0e39dff55eb571b9974dcbf17815e464ab457075"),
    decode_hex("ef068f60f3611e0f12baf0e20ede896a04c05c4a77edbdb26bc672b1a96c6da1"),
    decode_hex("660d9160ddaa3de79bfda5756fed221dfbcd13d24c61b98f24891bbcbd7424e1"),
    decode_hex("f58423702972120ce4ca970c397f9186c7c5af792f7f276f5e4c09c317f00427"),
    decode_hex("41c04b9649fe016d9827fea6dd692059c55ad7216368d123015faed0861d22cb"),
    decode_hex("234f30f0142cb5cbdd62338b73c8a77b8e5168b7aa21117808f15a441d3e4481"),
];

/// What `GET /auth/username-kdf` returns. Clients refuse anything below this cost.
#[derive(Debug, Clone, Serialize)]
pub struct UsernameKdfParams {
    pub algorithm: &'static str,
    pub version: u32,
    pub salt: String,
    pub memory_kib: u32,
    pub iterations: u32,
    pub parallelism: u32,
    pub output_bytes: u32,
}

/// Per-server username KDF. Cheap to clone: every clone shares the reserved-name cache.
#[derive(Clone)]
pub struct UsernameKdf {
    salt: [u8; SALT_LEN],
    reserved: Arc<OnceLock<Vec<[u8; 32]>>>,
}

impl std::fmt::Debug for UsernameKdf {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("UsernameKdf")
            .field("algorithm", &"argon2id")
            .field("memory_kib", &MEMORY_KIB)
            .field("iterations", &ITERATIONS)
            .field("parallelism", &PARALLELISM)
            .finish_non_exhaustive()
    }
}

impl UsernameKdf {
    /// `USERNAME_KDF_SALT`: 32 hex characters. The published test salt is refused.
    pub fn from_hex(raw: &str) -> Result<Self, AppError> {
        let trimmed = raw.trim();
        if trimmed.len() != SALT_LEN * 2 || !trimmed.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err(AppError::Internal(
                "USERNAME_KDF_SALT must be 32 hex characters (16 bytes). Generate one with \
                 `openssl rand -hex 16` and keep it. Rotating it makes every username stop matching."
                    .into(),
            ));
        }
        if trimmed.eq_ignore_ascii_case(TEST_SALT_HEX) {
            return Err(AppError::Internal(
                "USERNAME_KDF_SALT is the published test salt. Generate a new one with \
                 `openssl rand -hex 16`."
                    .into(),
            ));
        }
        let mut salt = [0u8; SALT_LEN];
        for (index, byte) in salt.iter_mut().enumerate() {
            *byte = u8::from_str_radix(&trimmed[index * 2..index * 2 + 2], 16)
                .expect("hex digits were checked");
        }
        Ok(Self {
            salt,
            reserved: Arc::new(OnceLock::new()),
        })
    }

    /// The published test salt, with the reserved-name digests already filled in.
    pub fn for_tests() -> Self {
        let reserved = Arc::new(OnceLock::new());
        reserved
            .set(TEST_RESERVED.to_vec())
            .expect("the lock is empty");
        Self {
            salt: TEST_SALT,
            reserved,
        }
    }

    /// Argon2id of `admin` under the test salt. Registration tests send this without hashing.
    pub fn test_reserved_admin() -> &'static [u8; 32] {
        &TEST_RESERVED[0]
    }

    /// Raw 32-byte digest of a normalized username.
    pub fn hash(&self, normalized: &str) -> Result<[u8; OUTPUT_LEN], AppError> {
        let params = Params::new(MEMORY_KIB, ITERATIONS, PARALLELISM, Some(OUTPUT_LEN))
            .map_err(|err| AppError::Internal(format!("invalid username kdf params: {err}")))?;
        let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
        let mut out = [0u8; OUTPUT_LEN];
        argon
            .hash_password_into(normalized.as_bytes(), &self.salt, &mut out)
            .map_err(|err| AppError::Internal(format!("username hash failed: {err}")))?;
        Ok(out)
    }

    /// [`Self::hash`] as standard Base64, the `username_hash` clients send.
    pub fn hash_b64(&self, normalized: &str) -> Result<String, AppError> {
        Ok(BASE64.encode(self.hash(normalized)?))
    }

    /// Refuses the argon2id digest of a reserved name under this server's salt.
    ///
    /// The first call hashes every reserved name (a few seconds). Call it from `spawn_blocking`,
    /// and warm it at boot the same way, so a tokio worker is not blocked.
    pub fn reject_reserved(&self, hash: &[u8; 32]) -> Result<(), AppError> {
        if self
            .reserved_hashes()
            .iter()
            .any(|reserved| reserved == hash)
        {
            return Err(AppError::username_reserved());
        }
        Ok(())
    }

    /// Fills the reserved-name cache. Safe to call more than once.
    pub fn warm(&self) {
        let _ = self.reserved_hashes();
    }

    pub fn public_params(&self) -> UsernameKdfParams {
        UsernameKdfParams {
            algorithm: "argon2id",
            version: VERSION,
            salt: BASE64.encode(self.salt),
            memory_kib: MEMORY_KIB,
            iterations: ITERATIONS,
            parallelism: PARALLELISM,
            output_bytes: OUTPUT_LEN as u32,
        }
    }

    fn reserved_hashes(&self) -> &[[u8; 32]] {
        self.reserved.get_or_init(|| {
            RESERVED_EXACT
                .iter()
                .map(|name| self.hash(name).expect("username kdf parameters are valid"))
                .collect()
        })
    }
}

const fn hex_val(byte: u8) -> u8 {
    match byte {
        b'0'..=b'9' => byte - b'0',
        b'a'..=b'f' => byte - b'a' + 10,
        b'A'..=b'F' => byte - b'A' + 10,
        _ => panic!("username kdf hex table is not hexadecimal"),
    }
}

const fn decode_hex<const N: usize>(text: &str) -> [u8; N] {
    let bytes = text.as_bytes();
    assert!(bytes.len() == N * 2);
    let mut out = [0u8; N];
    let mut index = 0;
    while index < N {
        out[index] = (hex_val(bytes[index * 2]) << 4) | hex_val(bytes[index * 2 + 1]);
        index += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn alice_matches_the_pinned_vector_and_costs_at_least_100ms() {
        let kdf = UsernameKdf::for_tests();
        let started = std::time::Instant::now();
        let digest = kdf.hash_b64("alice").expect("hash");
        let elapsed = started.elapsed();
        // Pinned with the Rust `argon2` crate and `@noble/hashes` argon2id.
        assert_eq!(digest, "4b4IohXsRZvTapVS+ZJWMkcBP5keMgo8hGWaLN+boI4=");
        assert!(
            elapsed >= std::time::Duration::from_millis(100),
            "argon2id took {elapsed:?}"
        );
    }

    #[test]
    fn reserved_admin_matches_the_embedded_digest() {
        let digest = UsernameKdf::for_tests().hash("admin").expect("hash");
        assert_eq!(&digest, UsernameKdf::test_reserved_admin());
    }

    #[test]
    fn reserved_digest_is_refused_without_hashing_again() {
        let kdf = UsernameKdf::for_tests();
        let started = std::time::Instant::now();
        let err = kdf
            .reject_reserved(UsernameKdf::test_reserved_admin())
            .expect_err("admin");
        assert!(started.elapsed() < std::time::Duration::from_millis(50));
        assert!(matches!(
            err,
            AppError::Api {
                code: "USERNAME_RESERVED",
                ..
            }
        ));
        assert!(kdf.reject_reserved(&[0x11; 32]).is_ok());
    }

    #[test]
    fn from_hex_rejects_the_published_test_salt() {
        assert!(UsernameKdf::from_hex(TEST_SALT_HEX).is_err());
        assert!(UsernameKdf::from_hex("00112233445566778899AABBCCDDEEFF").is_err());
        assert!(UsernameKdf::from_hex("").is_err());
        assert!(UsernameKdf::from_hex("abcd").is_err());
        assert!(UsernameKdf::from_hex("zz112233445566778899aabbccddeeff").is_err());
        let kdf = UsernameKdf::from_hex("0123456789abcdeffedcba9876543210").expect("salt");
        let params = kdf.public_params();
        assert_eq!(params.algorithm, "argon2id");
        assert_eq!(params.version, 19);
        assert_eq!(params.memory_kib, MEMORY_KIB);
        assert_eq!(params.iterations, ITERATIONS);
        assert_eq!(params.parallelism, 1);
        assert_eq!(params.output_bytes, 32);
        assert!(!format!("{kdf:?}").contains("0123456789abcdef"));
    }

    #[test]
    fn test_params_publish_the_known_salt() {
        let params = UsernameKdf::for_tests().public_params();
        assert_eq!(params.salt, "ABEiM0RVZneImaq7zN3u/w==");
        assert_eq!(TEST_RESERVED.len(), RESERVED_EXACT.len());
    }
}
