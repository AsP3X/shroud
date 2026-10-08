//! The console's Postgres connection. Schema `admin` is migrated here; column grants on the
//! API's tables are applied by the table owner (`admin/api/grants.sql`), not by this role.

use sqlx::PgPool;

/// Session advisory lock so two consoles do not migrate at once.
const MIGRATION_LOCK: i64 = 0x5348_4441;

pub async fn connect(url: &str) -> Result<PgPool, sqlx::Error> {
    sqlx::postgres::PgPoolOptions::new()
        .max_connections(5)
        .connect(url)
        .await
}

/// Apply `migrations/` while holding [`MIGRATION_LOCK`]. The lock is released even when a
/// migration fails.
pub async fn migrate(pool: &PgPool) -> Result<(), sqlx::Error> {
    let mut conn = pool.acquire().await?;
    sqlx::query("SELECT pg_advisory_lock($1)")
        .bind(MIGRATION_LOCK)
        .execute(&mut *conn)
        .await?;
    // sqlx records applied files in `_sqlx_migrations` on the search path. Point that at
    // schema admin: shroud_admin cannot create tables in public, and public._sqlx_migrations
    // belongs to the API role.
    sqlx::query("CREATE SCHEMA IF NOT EXISTS admin")
        .execute(&mut *conn)
        .await?;
    sqlx::query("SET search_path TO admin")
        .execute(&mut *conn)
        .await?;
    let migrated = sqlx::migrate!("./migrations").run(&mut *conn).await;
    let reset = sqlx::query("SET search_path TO public")
        .execute(&mut *conn)
        .await;
    let unlocked = sqlx::query("SELECT pg_advisory_unlock($1)")
        .bind(MIGRATION_LOCK)
        .execute(&mut *conn)
        .await;
    migrated.map_err(|err| sqlx::Error::Migrate(Box::new(err)))?;
    reset?;
    unlocked?;
    Ok(())
}

/// Log a database failure without a connection string. sqlx sometimes includes the URL.
pub(crate) fn log_db(context: &str, err: &sqlx::Error) {
    let text = err.to_string();
    if text.contains("://") || text.contains('@') {
        tracing::error!(context, "database error");
    } else {
        tracing::error!(context, error = %text, "database error");
    }
}
