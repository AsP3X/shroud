//! Case-folded usernames that must never be registered.
//!
//! Shared by the fast SHA-256 reject list and the argon2id reject list. Prefixes
//! (`shroud_`, `system_`, `admin_`, `support_`) stay with the clients: they are not enumerable.

/// Exact names, case-folded. Order is the order of the embedded test-salt digests.
pub const RESERVED_EXACT: &[&str] = &[
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
