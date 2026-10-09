//! The admin console's rate-limit check (`GET /operator/rate-limits/check`).
//!
//! Human: Each budget on the Rate limits page is tried on the live limiter: a made-up key under
//! the budget's own scope is counted up to its limit, the next hit must be refused, and the key
//! is dropped. No person's counter is touched, since a real key is an IP, a user id, a
//! username hash or a device id and the check's are `check-<random>`. A first line says where
//! counts are kept.
//! Agent: about 1,600 counter hits in all (the call-signal budget alone is 601), all budgets at
//! once; Redis INCR when attached, else the in-process windows.

use std::time::Duration;

use futures_util::future::join_all;
use serde::Serialize;
use uuid::Uuid;

use crate::rate_limit::{RateLimiter, Store, budgets};

/// One line of the check, named like the page's rows: what the budget guards, and per what.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct RateLimitCheck {
    pub item: String,
    /// `ok`, `failed`, or `off` (the store line without Redis).
    pub state: &'static str,
    pub detail: String,
}

/// The budgets as the page lists them: name, counted per, the scope the API passes, the budget.
const BUDGETS: [(&str, &str, &str, (u64, Duration)); 21] = [
    ("Register or log in", "IP", "auth_ip", budgets::AUTH_IP),
    (
        "Register or log in",
        "username",
        "auth_user",
        budgets::AUTH_USERNAME,
    ),
    (
        "Password change, account delete",
        "account",
        "auth_sensitive_user",
        budgets::AUTH_SENSITIVE_USER,
    ),
    (
        "Session status from locked clients",
        "IP",
        "session_status_ip",
        budgets::SESSION_STATUS_IP,
    ),
    (
        "PIN-guard unlock",
        "IP",
        "pin_guard_ip",
        budgets::PIN_GUARD_IP,
    ),
    (
        "PIN change",
        "account",
        "pin_guard_user",
        budgets::PIN_GUARD_USER,
    ),
    (
        "User lookup",
        "IP",
        "user_lookup_ip",
        budgets::USER_LOOKUP_IP,
    ),
    (
        "New share code",
        "account",
        "share_code_rotate_user",
        budgets::SHARE_CODE_ROTATE_USER,
    ),
    (
        "Contact request",
        "account",
        "contact_req_user",
        budgets::CONTACT_REQUEST_USER,
    ),
    (
        "Key bundle or identity fetch",
        "account",
        "keys_user",
        budgets::KEYS_USER,
    ),
    (
        "Key bundle or identity fetch",
        "IP",
        "keys_ip",
        budgets::KEYS_IP,
    ),
    (
        "Device rename",
        "account",
        "device_name_user",
        budgets::DEVICE_NAME_USER,
    ),
    (
        "Message send",
        "account",
        "message_send_user",
        budgets::MESSAGE_SEND_USER,
    ),
    (
        "Reaction set, replace or remove",
        "account",
        "reaction_user",
        budgets::REACTION_USER,
    ),
    ("Placing a call", "account", "call_user", budgets::CALL_USER),
    (
        "Call signals and heartbeats",
        "account",
        "call_signal_user",
        budgets::CALL_SIGNAL_USER,
    ),
    (
        "Media upload registration",
        "account",
        "media_presign_user",
        budgets::MEDIA_PRESIGN_USER,
    ),
    ("WebSocket connect", "IP", "ws_ip", budgets::WS_CONNECT_IP),
    (
        "Link-preview relay connect",
        "IP",
        "link_relay_ip",
        budgets::LINK_RELAY_IP,
    ),
    (
        "Link-preview relay connect",
        "account",
        "link_relay_user",
        budgets::LINK_RELAY_USER,
    ),
    (
        "Test notification",
        "device",
        "push_test_device",
        budgets::PUSH_TEST_DEVICE,
    ),
];

const STORE_ITEM: &str = "Counter store";

pub async fn check(limiter: &RateLimiter) -> Vec<RateLimitCheck> {
    let Some(store) = limiter.store().await else {
        let mut lines = vec![RateLimitCheck {
            item: STORE_ITEM.into(),
            state: "failed",
            detail: "The limiter is off: nothing is counted.".into(),
        }];
        lines.extend(BUDGETS.iter().map(|(what, per, _, _)| RateLimitCheck {
            item: item(what, per),
            state: "failed",
            detail: "Not enforced: the limiter is off.".into(),
        }));
        return lines;
    };
    let (state, detail, place) = match store {
        Store::Redis => (
            "ok",
            "Redis answers: every API process shares the counts.",
            "in Redis",
        ),
        Store::RedisDown => (
            "failed",
            "REDIS_URL is set but Redis doesn't answer: each API process counts on its own until it's back.",
            "in this process",
        ),
        Store::Process => (
            "off",
            "No REDIS_URL: counted in this process, so each API process keeps its own counts.",
            "in this process",
        ),
    };
    let mut lines = vec![RateLimitCheck {
        item: STORE_ITEM.into(),
        state,
        detail: detail.into(),
    }];
    lines.extend(
        join_all(BUDGETS.iter().map(|(what, per, scope, budget)| async move {
            let outcome = try_budget(limiter, scope, *budget).await;
            let (state, detail) = verdict(*budget, outcome, place);
            RateLimitCheck {
                item: item(what, per),
                state,
                detail,
            }
        }))
        .await,
    );
    lines
}

fn item(what: &str, per: &str) -> String {
    format!("{what} · per {per}")
}

/// What trying a budget showed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Outcome {
    /// The first `limit` hits passed and the next was refused.
    Enforced,
    /// Refused at this hit, before the limit.
    RefusedEarly(u64),
    /// Hit `limit + 1` passed too.
    NotEnforced,
}

/// Counts a fresh key up to the limit and one past it, then drops it.
async fn try_budget(limiter: &RateLimiter, scope: &str, budget: (u64, Duration)) -> Outcome {
    let id = format!("check-{}", Uuid::new_v4().simple());
    let (limit, window) = budget;
    let mut outcome = Outcome::NotEnforced;
    for hit in 1..=limit + 1 {
        let passed = limiter.check(scope, &id, limit, window).await.is_ok();
        if !passed {
            outcome = if hit == limit + 1 {
                Outcome::Enforced
            } else {
                Outcome::RefusedEarly(hit)
            };
            break;
        }
    }
    limiter.forget(scope, &id).await;
    outcome
}

fn verdict(budget: (u64, Duration), outcome: Outcome, place: &str) -> (&'static str, String) {
    let (limit, window) = budget;
    let per = window_name(window);
    match outcome {
        Outcome::Enforced => (
            "ok",
            format!("Let {limit} through in {per}, refused the next · counted {place}."),
        ),
        Outcome::RefusedEarly(hit) => (
            "failed",
            format!(
                "Refused hit {hit} of {limit} allowed in {per}: something else is counting under this key."
            ),
        ),
        Outcome::NotEnforced => (
            "failed",
            format!(
                "Let hit {} through: {limit} in {per} isn't enforced.",
                limit + 1
            ),
        ),
    }
}

fn window_name(window: Duration) -> &'static str {
    match window.as_secs() {
        60 => "a minute",
        3600 => "an hour",
        86_400 => "a day",
        _ => "its window",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn every_budget_is_enforced_in_process() {
        let limiter = RateLimiter::new();
        let lines = check(&limiter).await;
        assert_eq!(lines.len(), 22);
        assert_eq!(lines[0].item, "Counter store");
        assert_eq!(lines[0].state, "off");
        for line in &lines[1..] {
            assert_eq!(line.state, "ok", "{line:?}");
        }
        assert_eq!(lines[1].item, "Register or log in · per IP");
        assert_eq!(
            lines[1].detail,
            "Let 10 through in a minute, refused the next · counted in this process."
        );
        assert_eq!(
            lines[16].detail,
            "Let 600 through in a minute, refused the next · counted in this process."
        );
    }

    #[tokio::test]
    async fn the_check_leaves_no_counter_behind() {
        let limiter = RateLimiter::new();
        check(&limiter).await;
        // A fresh window: the check's own keys are gone, and a real key was never touched.
        assert!(
            limiter
                .check("auth_ip", "203.0.113.7", 10, Duration::from_secs(60))
                .await
                .is_ok()
        );
        let outcome = try_budget(&limiter, "auth_ip", budgets::AUTH_IP).await;
        assert_eq!(outcome, Outcome::Enforced);
    }

    #[tokio::test]
    async fn a_disabled_limiter_fails_every_line() {
        let lines = check(&RateLimiter::disabled()).await;
        assert!(lines.iter().all(|line| line.state == "failed"));
    }

    #[test]
    fn verdicts_name_the_failure() {
        let budget = (5, Duration::from_secs(3600));
        assert_eq!(
            verdict(budget, Outcome::NotEnforced, "in Redis").1,
            "Let hit 6 through: 5 in an hour isn't enforced."
        );
        assert_eq!(
            verdict(budget, Outcome::RefusedEarly(3), "in Redis").0,
            "failed"
        );
    }
}
