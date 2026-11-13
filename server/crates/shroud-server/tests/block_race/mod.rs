//! Shared by `api_messages.rs` and `api_calls.rs`: a block or a contact removal held open on
//! the pair lock, so a test can race a send or a ring against it (security audit S6, SHRO-14).

use uuid::Uuid;

/// Human: Opens what `POST /blocks` writes for `blocker` → `blocked`, left uncommitted: the
/// pair lock (taken as `contacts::lock_pair` takes it), the contact rows gone, the block row in.
/// Agent: RETURNS the open transaction and its backend pid; commit it to land the block.
pub async fn stage_block(
    pool: &sqlx::PgPool,
    blocker: Uuid,
    blocked: Uuid,
) -> (sqlx::Transaction<'static, sqlx::Postgres>, i32) {
    let mut tx = pool.begin().await.expect("begin");
    let pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
        .fetch_one(&mut *tx)
        .await
        .expect("pid");
    let (low, high) = if blocker < blocked {
        (blocker, blocked)
    } else {
        (blocked, blocker)
    };
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))")
        .bind(format!("contact-pair:{low}:{high}"))
        .execute(&mut *tx)
        .await
        .expect("pair lock");
    sqlx::query(
        "DELETE FROM contacts WHERE (user_id = $1 AND contact_user_id = $2) \
         OR (user_id = $2 AND contact_user_id = $1)",
    )
    .bind(blocker)
    .bind(blocked)
    .execute(&mut *tx)
    .await
    .expect("delete contacts");
    sqlx::query("INSERT INTO blocks (blocker_id, blocked_id, created_at) VALUES ($1, $2, now())")
        .bind(blocker)
        .bind(blocked)
        .execute(&mut *tx)
        .await
        .expect("insert block");
    (tx, pid)
}

/// Waits until `task` is stuck behind backend `pid`, or has finished without waiting on it.
pub async fn until_waiting_on<T>(pool: &sqlx::PgPool, pid: i32, task: &tokio::task::JoinHandle<T>) {
    for _ in 0..100 {
        if task.is_finished() {
            return;
        }
        let waiting: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE $1 = ANY(pg_blocking_pids(pid)))",
        )
        .bind(pid)
        .fetch_one(pool)
        .await
        .expect("pg_stat_activity");
        if waiting {
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    panic!("the request neither finished nor waited on backend {pid}");
}

/// Human: Opens what `DELETE /contacts/{id}` writes for the pair, left uncommitted: the pair
/// lock (taken as `contacts::lock_pair` takes it) and the contact rows gone (SHRO-46).
/// Agent: RETURNS the open transaction and its backend pid; commit it to land the removal.
pub async fn stage_removal(
    pool: &sqlx::PgPool,
    remover: Uuid,
    peer: Uuid,
) -> (sqlx::Transaction<'static, sqlx::Postgres>, i32) {
    let mut tx = pool.begin().await.expect("begin");
    let pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
        .fetch_one(&mut *tx)
        .await
        .expect("pid");
    let (low, high) = if remover < peer {
        (remover, peer)
    } else {
        (peer, remover)
    };
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))")
        .bind(format!("contact-pair:{low}:{high}"))
        .execute(&mut *tx)
        .await
        .expect("pair lock");
    sqlx::query(
        "DELETE FROM contacts WHERE (user_id = $1 AND contact_user_id = $2) \
         OR (user_id = $2 AND contact_user_id = $1)",
    )
    .bind(remover)
    .bind(peer)
    .execute(&mut *tx)
    .await
    .expect("delete contacts");
    (tx, pid)
}
