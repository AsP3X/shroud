//! The admin console's retention check (`GET /operator/retention/check`).
//!
//! Human: Each automatic rule on the Data retention page is checked against the database: rows
//! its job should already have deleted or ended are counted, with one run of the job and a
//! little time on top. Any left means the job isn't running or keeps failing. The same
//! conditions and constants as the jobs, so the check can't disagree with them. Only counts
//! leave this module.
//! Agent: one read-only SELECT over sessions, devices, media_objects and calls.

use serde::Serialize;
use sqlx::{PgPool, Row};

use crate::auth::session::{REVOKED_SESSION_RETENTION_DAYS, SESSION_PURGE_INTERVAL_SECS};
use crate::routes::calls::{CALL_GC_INTERVAL_SECS, PARTICIPANT_TIMEOUT_SECS, RINGING_TIMEOUT_SECS};
use crate::routes::media::{ORPHAN_GC_INTERVAL_SECS, ORPHAN_TTL_MINUTES};

/// Slack for the hourly and quarter-hourly jobs: a run takes time, and the first one waits an
/// interval after the server starts.
const SLOW_JOB_GRACE_SECS: i64 = 5 * 60;
/// Slack for the 10-second call sweep.
const CALL_SWEEP_GRACE_SECS: i64 = 20;

/// One line of the check, named like the page's rows.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct RetentionCheck {
    pub item: &'static str,
    /// `ok` or `failed`.
    pub state: &'static str,
    pub detail: String,
}

/// Overdue and waiting counts for each rule.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
struct Counts {
    sessions_overdue: i64,
    sessions_waiting: i64,
    media_overdue: i64,
    media_waiting: i64,
    ringing_overdue: i64,
    ringing_now: i64,
    silent_overdue: i64,
    active_now: i64,
}

pub async fn check(pool: &PgPool) -> Vec<RetentionCheck> {
    match counts(pool).await {
        Ok(counts) => lines(&counts),
        Err(_) => ITEMS
            .iter()
            .map(|item| RetentionCheck {
                item,
                state: "failed",
                detail: "Couldn't read the database.".into(),
            })
            .collect(),
    }
}

const ITEMS: [&str; 4] = [
    "Revoked sessions",
    "Unlinked media",
    "Unanswered calls",
    "Silent call participants",
];

async fn counts(pool: &PgPool) -> Result<Counts, sqlx::Error> {
    let sessions_after = REVOKED_SESSION_RETENTION_DAYS * 86_400
        + SESSION_PURGE_INTERVAL_SECS as i64
        + SLOW_JOB_GRACE_SECS;
    let media_after =
        ORPHAN_TTL_MINUTES * 60 + ORPHAN_GC_INTERVAL_SECS as i64 + SLOW_JOB_GRACE_SECS;
    let ringing_after = RINGING_TIMEOUT_SECS + CALL_GC_INTERVAL_SECS as i64 + CALL_SWEEP_GRACE_SECS;
    let silent_after =
        PARTICIPANT_TIMEOUT_SECS + CALL_GC_INTERVAL_SECS as i64 + CALL_SWEEP_GRACE_SECS;
    // The purge's own condition: a revoked session of a device that is still active.
    let row = sqlx::query(
        r#"
        WITH purgeable AS (
            SELECT s.revoked_at FROM sessions s
            WHERE s.revoked_at IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM devices d WHERE d.id = s.device_id AND d.revoked_at IS NOT NULL
              )
        )
        SELECT
            (SELECT count(*) FROM purgeable
             WHERE revoked_at < now() - make_interval(secs => $1)) AS sessions_overdue,
            (SELECT count(*) FROM purgeable
             WHERE revoked_at >= now() - make_interval(secs => $1)) AS sessions_waiting,
            (SELECT count(*) FROM media_objects
             WHERE message_id IS NULL AND created_at < now() - make_interval(secs => $2))
                AS media_overdue,
            (SELECT count(*) FROM media_objects
             WHERE message_id IS NULL AND created_at >= now() - make_interval(secs => $2))
                AS media_waiting,
            (SELECT count(*) FROM calls
             WHERE status = 'ringing' AND created_at < now() - make_interval(secs => $3))
                AS ringing_overdue,
            (SELECT count(*) FROM calls WHERE status = 'ringing') AS ringing_now,
            (SELECT count(*) FROM calls
             WHERE (status = 'ringing'
                    AND COALESCE(caller_seen_at, created_at) < now() - make_interval(secs => $4))
                OR (status = 'active'
                    AND (COALESCE(caller_seen_at, answered_at, created_at)
                             < now() - make_interval(secs => $4)
                         OR COALESCE(callee_seen_at, answered_at, created_at)
                             < now() - make_interval(secs => $4))))
                AS silent_overdue,
            (SELECT count(*) FROM calls WHERE status = 'active') AS active_now
        "#,
    )
    .bind(sessions_after as f64)
    .bind(media_after as f64)
    .bind(ringing_after as f64)
    .bind(silent_after as f64)
    .fetch_one(pool)
    .await?;
    Ok(Counts {
        sessions_overdue: row.try_get("sessions_overdue")?,
        sessions_waiting: row.try_get("sessions_waiting")?,
        media_overdue: row.try_get("media_overdue")?,
        media_waiting: row.try_get("media_waiting")?,
        ringing_overdue: row.try_get("ringing_overdue")?,
        ringing_now: row.try_get("ringing_now")?,
        silent_overdue: row.try_get("silent_overdue")?,
        active_now: row.try_get("active_now")?,
    })
}

fn lines(counts: &Counts) -> Vec<RetentionCheck> {
    let line = |item: &'static str, overdue: i64, fine: String, late: String| RetentionCheck {
        item,
        state: if overdue == 0 { "ok" } else { "failed" },
        detail: if overdue == 0 { fine } else { late },
    };
    vec![
        line(
            ITEMS[0],
            counts.sessions_overdue,
            format!(
                "None past 30 days and an hour · {} still within them.",
                some(
                    counts.sessions_waiting,
                    "revoked session",
                    "revoked sessions"
                )
            ),
            format!(
                "{} past 30 days and an hour: the hourly purge isn't running or keeps failing. The API log says why.",
                some(
                    counts.sessions_overdue,
                    "revoked session is",
                    "revoked sessions are"
                )
            ),
        ),
        line(
            ITEMS[1],
            counts.media_overdue,
            format!(
                "None past 60 minutes and a sweep · {} waiting for a message.",
                some(counts.media_waiting, "upload", "uploads")
            ),
            format!(
                "{} past 60 minutes and a sweep: the orphan GC isn't running, or the media store refuses deletes. The API log says which.",
                some(
                    counts.media_overdue,
                    "unlinked upload is",
                    "unlinked uploads are"
                )
            ),
        ),
        line(
            ITEMS[2],
            counts.ringing_overdue,
            format!(
                "None ringing past 60 s and a sweep · {} ringing now.",
                some(counts.ringing_now, "call", "calls")
            ),
            format!(
                "{} still ringing past 60 s and a sweep: the call sweep isn't running.",
                some(counts.ringing_overdue, "call is", "calls are")
            ),
        ),
        line(
            ITEMS[3],
            counts.silent_overdue,
            format!(
                "No side silent past 45 s and a sweep · {} in progress.",
                some(counts.active_now, "call", "calls")
            ),
            format!(
                "{} a side silent past 45 s and a sweep: the call sweep isn't running.",
                some(counts.silent_overdue, "call has", "calls have")
            ),
        ),
    ]
}

/// "1 call", "3 calls", "no calls".
fn some(count: i64, one: &str, many: &str) -> String {
    match count {
        0 => format!("no {many}"),
        1 => format!("1 {one}"),
        n => format!("{n} {many}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nothing_overdue_reads_as_keeping_up() {
        let lines = lines(&Counts {
            sessions_waiting: 12,
            media_waiting: 1,
            ringing_now: 0,
            active_now: 2,
            ..Counts::default()
        });
        assert!(lines.iter().all(|line| line.state == "ok"));
        assert_eq!(
            lines[0].detail,
            "None past 30 days and an hour · 12 revoked sessions still within them."
        );
        assert_eq!(
            lines[1].detail,
            "None past 60 minutes and a sweep · 1 upload waiting for a message."
        );
        assert_eq!(
            lines[2].detail,
            "None ringing past 60 s and a sweep · no calls ringing now."
        );
        assert_eq!(
            lines[3].detail,
            "No side silent past 45 s and a sweep · 2 calls in progress."
        );
    }

    #[test]
    fn overdue_rows_fail_their_line_only() {
        let lines = lines(&Counts {
            media_overdue: 3,
            ringing_overdue: 1,
            ..Counts::default()
        });
        let states: Vec<&str> = lines.iter().map(|line| line.state).collect();
        assert_eq!(states, ["ok", "failed", "failed", "ok"]);
        assert!(
            lines[1]
                .detail
                .starts_with("3 unlinked uploads are past 60 minutes and a sweep")
        );
        assert!(
            lines[2]
                .detail
                .starts_with("1 call is still ringing past 60 s")
        );
    }
}
