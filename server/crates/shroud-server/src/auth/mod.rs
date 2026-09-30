//! Authentication helpers: usernames, passwords, session tokens, extractors.

pub mod password;
pub mod session;
pub mod share_code;
pub mod token;
pub mod username;

pub use password::{hash_password, validate_password_policy, verify_password};
pub use share_code::{generate_share_code, is_valid_share_code_format, normalize_share_code};
pub use token::{hash_token, issue_session_token};
pub use username::normalize_username;

/// Maximum linked devices per account (enforced in application code).
pub const MAX_DEVICES_PER_USER: i64 = 5;
