//! Rate limits and retention the console shows.
//!
//! Copied from `shroud-server` (`rate_limit::budgets`, `REVOKED_SESSION_RETENTION_DAYS`,
//! `ORPHAN_TTL_MINUTES`, `RINGING_TIMEOUT_SECS`, `PARTICIPANT_TIMEOUT_SECS`, and the three
//! sweep intervals). This crate does not depend on the API crate. The unit test below fails
//! when these tables drift from `fixtures/rate-limits.json` and `fixtures/retention.json`.

use serde::Serialize;

/// `routes/media.rs` `MAX_MEDIA_BYTES`: 2 GiB.
pub const MAX_OBJECT_BYTES: i64 = 2_147_483_648;

/// `auth/session.rs` `REVOKED_SESSION_RETENTION_DAYS`.
pub const REVOKED_SESSION_RETENTION_DAYS: i64 = 30;
/// `auth/session.rs` `SESSION_PURGE_INTERVAL_SECS`.
pub const SESSION_PURGE_INTERVAL_SECS: i64 = 60 * 60;

/// `routes/media.rs` `ORPHAN_TTL_MINUTES`.
pub const ORPHAN_TTL_MINUTES: i64 = 60;
/// `routes/media.rs` `ORPHAN_GC_INTERVAL_SECS`.
pub const ORPHAN_GC_INTERVAL_SECS: i64 = 15 * 60;

/// `routes/calls.rs` `RINGING_TIMEOUT_SECS`.
pub const RINGING_TIMEOUT_SECS: i64 = 60;
/// `routes/calls.rs` `PARTICIPANT_TIMEOUT_SECS`.
pub const PARTICIPANT_TIMEOUT_SECS: i64 = 45;
/// `routes/calls.rs` `CALL_GC_INTERVAL_SECS`.
pub const CALL_GC_INTERVAL_SECS: i64 = 10;

const DAY_SECS: i64 = 24 * 60 * 60;
const MINUTE_SECS: i64 = 60;

#[derive(Serialize)]
pub struct RateLimit {
    pub what: &'static str,
    pub counted: &'static str,
    pub limit: u64,
    pub window_secs: u64,
    pub note: Option<&'static str>,
}

#[derive(Serialize)]
pub struct Retention {
    pub automatic: Vec<Automatic>,
    pub kept: Vec<Kept>,
    pub jobs: Vec<Job>,
}

#[derive(Serialize)]
pub struct Automatic {
    pub record: &'static str,
    pub after_secs: i64,
    pub every_secs: i64,
    pub stays: &'static str,
}

#[derive(Serialize)]
pub struct Kept {
    pub record: &'static str,
    pub goes_when: &'static str,
}

#[derive(Serialize)]
pub struct Job {
    pub name: &'static str,
    pub every_secs: Option<i64>,
    pub detail: &'static str,
}

/// The `budgets` module, in the order the Rate limits frame lists them.
pub fn rate_limits() -> Vec<RateLimit> {
    vec![
        row("Register or log in", "ip", 10, 60, None),
        row(
            "Register or log in",
            "username",
            5,
            60,
            Some("Keyed by the username hash"),
        ),
        row("Password change, account delete", "account", 5, 3600, None),
        row(
            "Session status from locked clients",
            "ip",
            60,
            60,
            Some("Each open tab asks about twice a minute"),
        ),
        row(
            "PIN-guard unlock",
            "ip",
            20,
            60,
            Some("The guard's own counter is the real limit"),
        ),
        row("PIN change", "account", 10, 3600, None),
        row("User lookup", "ip", 30, 60, None),
        row(
            "New share code",
            "account",
            10,
            3600,
            Some("Breaks every QR code and link handed out before"),
        ),
        row("Contact request", "account", 10, 3600, None),
        row("Key bundle or identity fetch", "account", 60, 60, None),
        row("Key bundle or identity fetch", "ip", 120, 60, None),
        row("Device rename", "account", 60, 3600, None),
        row("Message send", "account", 120, 60, None),
        row(
            "Reaction set, replace or remove",
            "account",
            120,
            60,
            Some("Separate from the message budget"),
        ),
        row("Placing a call", "account", 30, 60, None),
        row(
            "Call signals and heartbeats",
            "account",
            600,
            60,
            Some("Offer, answer, ICE and heartbeats"),
        ),
        row("Media upload registration", "account", 60, 60, None),
        row("WebSocket connect", "ip", 30, 60, None),
        row("Link-preview relay connect", "ip", 120, 60, None),
        row(
            "Link-preview relay connect",
            "account",
            60,
            60,
            Some("At most 6 open pipes per account"),
        ),
        row("Test notification", "device", 6, 60, None),
    ]
}

fn row(
    what: &'static str,
    counted: &'static str,
    limit: u64,
    window_secs: u64,
    note: Option<&'static str>,
) -> RateLimit {
    RateLimit {
        what,
        counted,
        limit,
        window_secs,
        note,
    }
}

pub fn retention() -> Retention {
    Retention {
        automatic: vec![
            Automatic {
                record: "Revoked sessions",
                after_secs: REVOKED_SESSION_RETENTION_DAYS * DAY_SECS,
                every_secs: SESSION_PURGE_INTERVAL_SECS,
                stays: "The sessions of removed devices, so the device learns it was removed",
            },
            Automatic {
                record: "Unlinked media",
                after_secs: ORPHAN_TTL_MINUTES * MINUTE_SECS,
                every_secs: ORPHAN_GC_INTERVAL_SECS,
                stays: "A blob that couldn't be deleted keeps its row and is retried",
            },
            Automatic {
                record: "Unanswered calls",
                after_secs: RINGING_TIMEOUT_SECS,
                every_secs: CALL_GC_INTERVAL_SECS,
                stays: "The call row, ended with its reason",
            },
            Automatic {
                record: "Silent call participants",
                after_secs: PARTICIPANT_TIMEOUT_SECS,
                every_secs: CALL_GC_INTERVAL_SECS,
                stays: "The call row, ended with its reason",
            },
        ],
        kept: vec![
            Kept {
                record: "Messages",
                goes_when: "When the sender unsends it, or with the account",
            },
            Kept {
                record: "Media",
                goes_when: "With its message; the orphan job then removes the blob",
            },
            Kept {
                record: "Call rows",
                goes_when: "With the account of either side",
            },
            Kept {
                record: "Contact requests",
                goes_when: "With the account",
            },
            Kept {
                record: "Clear and hide markers",
                goes_when: "With the account",
            },
            Kept {
                record: "Devices",
                goes_when: "Never · a removed device keeps its row, pending deliveries are dropped",
            },
            Kept {
                record: "Accounts",
                goes_when: "Never · a placeholder keeps \"Deleted account\" in other people's chats",
            },
        ],
        jobs: vec![
            Job {
                name: "Revoked-session purge",
                every_secs: Some(SESSION_PURGE_INTERVAL_SECS),
                detail: "Deletes sessions revoked over 30 days ago",
            },
            Job {
                name: "Orphan media GC",
                every_secs: Some(ORPHAN_GC_INTERVAL_SECS),
                detail: "Unlinked media older than 60 minutes",
            },
            Job {
                name: "Legacy media migration",
                every_secs: None,
                detail: "Until the local volume is empty. Moves blobs into Nebular",
            },
            Job {
                name: "Call sweep",
                every_secs: Some(CALL_GC_INTERVAL_SECS),
                detail: "Ends unanswered and silent calls",
            },
        ],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rate_limits_and_retention_match_the_fixtures() {
        let limits = serde_json::to_value(rate_limits()).unwrap();
        let expected: serde_json::Value =
            serde_json::from_str(include_str!("../fixtures/rate-limits.json")).unwrap();
        assert_eq!(limits, expected);

        let kept = serde_json::to_value(retention()).unwrap();
        let expected: serde_json::Value =
            serde_json::from_str(include_str!("../fixtures/retention.json")).unwrap();
        assert_eq!(kept, expected);
        assert_eq!(MAX_OBJECT_BYTES, 2_147_483_648);
    }
}
