//! Column grants for role `shroud_admin` (docs/admin-plan.md R3).
//!
//! Run against a throwaway Postgres started with `server/docker/init-admin-role.sh`:
//! `GRANT_TEST_SUPER_URL` is the table owner, `GRANT_TEST_ADMIN_URL` is `shroud_admin`.
//! Without those variables the test does not claim to have checked anything.

use sqlx::PgPool;
use sqlx::postgres::PgPoolOptions;

#[tokio::test]
async fn shroud_admin_cannot_read_forbidden_columns() {
    let Some(super_url) = nonempty("GRANT_TEST_SUPER_URL") else {
        eprintln!("GRANT_TEST_SUPER_URL unset; grant test not run");
        return;
    };
    let Some(admin_url) = nonempty("GRANT_TEST_ADMIN_URL") else {
        eprintln!("GRANT_TEST_ADMIN_URL unset; grant test not run");
        return;
    };

    let owner = PgPoolOptions::new()
        .connect(&super_url)
        .await
        .expect("owner connection");
    sqlx::migrate!("../../server/migrations/postgres")
        .run(&owner)
        .await
        .expect("server migrations");

    let admin = PgPoolOptions::new()
        .connect(&admin_url)
        .await
        .expect("shroud_admin connection");
    shroud_admin::db::migrate(&admin)
        .await
        .expect("admin migrations");

    let grants = include_str!("../grants.sql");
    sqlx::raw_sql(grants)
        .execute(&owner)
        .await
        .expect("column grants");
    // Deploy applies this file on every release. CREATE OR REPLACE VIEW and GRANT SELECT
    // both have to run cleanly a second time.
    sqlx::raw_sql(grants)
        .execute(&owner)
        .await
        .expect("column grants re-apply");

    // The plan names this `body`. The column the server stores is `ciphertext`.
    expect_denied(&admin, "SELECT ciphertext FROM messages").await;
    expect_denied(&admin, "SELECT username_hash FROM users").await;
    expect_denied(&admin, "SELECT sealed_name FROM devices").await;
    expect_denied(&admin, "SELECT object_key FROM media_objects").await;
    expect_denied(&admin, "SELECT bucket FROM media_objects").await;
    expect_denied(&admin, "SELECT apns_token FROM push_tokens").await;
    expect_denied(&admin, "SELECT endpoint FROM web_push_subscriptions").await;
    expect_denied(&admin, "SELECT payload_key FROM push_tokens").await;
    sqlx::query("SELECT device_id FROM admin_unsealed_push_devices")
        .fetch_all(&admin)
        .await
        .expect("the unsealed-push view, without payload_key itself");

    sqlx::query("SELECT id FROM users")
        .fetch_all(&admin)
        .await
        .expect("granted column users.id");
    sqlx::query("SELECT id FROM admin.operators")
        .fetch_all(&admin)
        .await
        .expect("own schema");
}

fn nonempty(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

async fn expect_denied(pool: &PgPool, sql: &str) {
    let err = sqlx::query(sql).fetch_optional(pool).await.expect_err(sql);
    let code = err
        .as_database_error()
        .and_then(|db| db.code().map(|code| code.to_string()));
    assert_eq!(code.as_deref(), Some("42501"), "{sql}: {err}");
}
