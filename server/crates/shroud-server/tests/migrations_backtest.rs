//! Migration backtest: apply all forward migrations and assert core schema.
//!
//! Human: Requires Postgres — set `DATABASE_URL`. CI uses a fresh database.
//! Agent: CALLS sqlx::migrate!; READS _sqlx_migrations + information_schema.
//!
//! Seeded previous-release dumps live under `tests/fixtures/` (see README there).

use sqlx::postgres::PgPoolOptions;

/// Expected number of forward migration files under `server/migrations/postgres/`.
const EXPECTED_MIGRATION_COUNT: i64 = 19;

#[tokio::test]
async fn all_migrations_apply_and_core_tables_exist() {
    let Ok(database_url) = std::env::var("DATABASE_URL") else {
        eprintln!("skipping all_migrations_apply_and_core_tables_exist: DATABASE_URL unavailable");
        return;
    };

    let pool = match PgPoolOptions::new()
        .max_connections(2)
        .connect(&database_url)
        .await
    {
        Ok(pool) => pool,
        Err(err) => {
            eprintln!("skipping all_migrations_apply_and_core_tables_exist: {err}");
            return;
        }
    };

    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .expect("migrate");

    let applied: i64 = sqlx::query_scalar("SELECT COUNT(*)::bigint FROM _sqlx_migrations")
        .fetch_one(&pool)
        .await
        .expect("count migrations");
    assert!(
        applied >= EXPECTED_MIGRATION_COUNT,
        "expected at least {EXPECTED_MIGRATION_COUNT} applied migrations, got {applied}"
    );

    for table in [
        "users",
        "devices",
        "sessions",
        "device_identity_keys",
        "conversations",
        "messages",
        "media_objects",
        "push_tokens",
        "calls",
    ] {
        let exists: bool = sqlx::query_scalar(
            r#"
            SELECT EXISTS(
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = $1
            )
            "#,
        )
        .bind(table)
        .fetch_one(&pool)
        .await
        .expect("table exists check");
        assert!(exists, "missing table {table}");
    }
}
