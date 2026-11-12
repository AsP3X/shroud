//! Column grants for role `shroud_admin` (docs/admin-plan.md R3), and the search path of the SQL
//! a deploy runs as the superuser (security audit A1).
//!
//! Run against a throwaway Postgres started with `server/docker/init-admin-role.sh`:
//! `GRANT_TEST_SUPER_URL` is the table owner, `GRANT_TEST_ADMIN_URL` is `shroud_admin`.
//! Without those variables the test does not claim to have checked anything.

use std::time::{SystemTime, UNIX_EPOCH};

use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use sqlx::{Connection, PgConnection, PgPool};

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

/// The API's role must not be able to run code as the superuser through the SQL a deploy runs
/// (security audit A1, CVE-2018-1058). `shroud_api` owns schema `public`, so an operator it puts
/// there that matches argument types exactly would win over `pg_catalog`'s on any superuser
/// session that searches `public`.
#[tokio::test]
async fn api_role_cannot_hijack_the_superuser_deploy_sql() {
    let Some(super_url) = nonempty("GRANT_TEST_SUPER_URL") else {
        eprintln!("GRANT_TEST_SUPER_URL unset; search_path test not run");
        return;
    };
    let base: PgConnectOptions = super_url.parse().expect("GRANT_TEST_SUPER_URL");
    let mut owner = PgConnection::connect_with(&base)
        .await
        .expect("superuser connection");

    // Human: The role is cluster-wide, so a deployment's real shroud_api would get this test's
    // password. Refuse instead of touching it.
    let api_role_exists: bool = sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'shroud_api')",
    )
    .fetch_one(&mut owner)
    .await
    .expect("look up shroud_api");
    assert!(
        !api_role_exists,
        "role shroud_api already exists; run this test on a throwaway Postgres only"
    );

    // Human: A database of its own, because api-role.sql gives the database and schema public to
    // shroud_api and pins the superuser's search_path in it; the other tests share theirs.
    let tag = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock after 1970")
        .as_nanos();
    let probe = format!("shroud_search_path_{}_{tag}", std::process::id());
    let password = format!("probe{tag}");
    sqlx::raw_sql(&format!(r#"CREATE DATABASE "{probe}""#))
        .execute(&mut owner)
        .await
        .expect("create the probe database");

    let outcome = plant_and_redeploy(&base, &probe, &password).await;

    // Human: Clean up before asserting, so a failing run does not leave a superuser role behind.
    // Agent: DROPS only the database and role this test created above.
    sqlx::raw_sql(&format!(r#"DROP DATABASE "{probe}" WITH (FORCE)"#))
        .execute(&mut owner)
        .await
        .expect("drop the probe database");
    sqlx::raw_sql("DROP ROLE shroud_api")
        .execute(&mut owner)
        .await
        .expect("drop the probe role");

    let (rolsuper, superuser_search_path) = outcome.expect("plant the operator and redeploy");
    assert!(
        !rolsuper,
        "an operator planted in public by shroud_api ran as the superuser and made shroud_api superuser"
    );
    assert_eq!(
        superuser_search_path, "pg_catalog, pg_temp",
        "a new superuser session in the API's database still searches a schema shroud_api owns"
    );
}

/// Applies `api-role.sql` to `probe` as the superuser, plants `public.<>(oid, regrole)` as
/// `shroud_api`, and applies the file again as a deploy would. Returns whether `shroud_api` is
/// then a superuser, and the `search_path` a fresh superuser session in `probe` starts with.
async fn plant_and_redeploy(
    base: &PgConnectOptions,
    probe: &str,
    password: &str,
) -> Result<(bool, String), sqlx::Error> {
    let target = base.clone().database(probe);
    let role_sql = api_role_sql(probe, password);

    // First deploy, on an empty database (init-api-role.sh).
    let mut first = PgConnection::connect_with(&target).await?;
    sqlx::raw_sql(&role_sql).execute(&mut first).await?;
    first.close().await?;

    // Human: What SQL access as shroud_api allows: an exact-match `<>` for api-role.sql's
    // `c.relowner <> 'shroud_api'::regrole` (pg_catalog's needs a cast), a table so that
    // comparison runs, and a database default that puts public back on everyone's path.
    let mut api =
        PgConnection::connect_with(&target.clone().username("shroud_api").password(password))
            .await?;
    sqlx::raw_sql(&format!(
        r#"
        CREATE TABLE public.bait (id int);
        CREATE FUNCTION public.hijack_ne(oid, regrole) RETURNS boolean LANGUAGE plpgsql AS $fn$
        BEGIN
          EXECUTE 'ALTER ROLE shroud_api SUPERUSER';
          RETURN $1 OPERATOR(pg_catalog.<>) $2::oid;
        END
        $fn$;
        CREATE OPERATOR public.<> (LEFTARG = oid, RIGHTARG = regrole, FUNCTION = public.hijack_ne);
        ALTER DATABASE "{probe}" SET search_path = public;
        "#
    ))
    .execute(&mut api)
    .await?;
    api.close().await?;

    // The next deploy: a new superuser session, as `psql -f` opens one.
    let mut second = PgConnection::connect_with(&target).await?;
    sqlx::raw_sql(&role_sql).execute(&mut second).await?;
    second.close().await?;

    let mut check = PgConnection::connect_with(&target).await?;
    let superuser_search_path: String = sqlx::query_scalar("SHOW search_path")
        .fetch_one(&mut check)
        .await?;
    let rolsuper: bool =
        sqlx::query_scalar("SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = 'shroud_api'")
            .fetch_one(&mut check)
            .await?;
    check.close().await?;
    Ok((rolsuper, superuser_search_path))
}

/// `server/docker/api-role.sql` with the psql variables deploy passes (`pwd`, `dbname`) filled in
/// and `\gset` turned into a plain statement end, so sqlx can send it. Both names are letters,
/// digits and underscores, so no quoting is needed.
fn api_role_sql(dbname: &str, password: &str) -> String {
    let file = include_str!("../../../server/docker/api-role.sql");
    for psql_only in [":'pwd'", r#":"dbname""#, r"\gset"] {
        assert!(
            file.contains(psql_only),
            "api-role.sql no longer uses {psql_only}; update this test's substitution"
        );
    }
    file.replace(":'pwd'", &format!("'{password}'"))
        .replace(r#":"dbname""#, &format!(r#""{dbname}""#))
        .replace(r"\gset", ";")
}

/// A rule the console's role put on the audit log while it owned it must not become the
/// superuser's when `grants.sql` takes the log over (security audit A2): Postgres checks a rule's
/// actions with the table owner's rights, so every entry the console wrote would copy password
/// hashes into a table of its own.
#[tokio::test]
async fn grants_refuse_an_audit_log_with_a_planted_rule() {
    let Some(outcome) = plant_on_audit_log(
        "rule",
        r#"
        CREATE RULE leak AS ON INSERT TO admin.audit_log
            DO ALSO INSERT INTO admin.loot SELECT password_hash FROM public.users;
        "#,
        false,
    )
    .await
    else {
        return;
    };
    let refused = outcome
        .refused
        .expect("grants.sql took over an audit log with a planted rule");
    assert!(
        refused.contains("rule leak on table admin.audit_log"),
        "{refused}"
    );
    assert_eq!(outcome.leaked, 0, "an audit entry copied password hashes");
    assert_eq!(
        outcome.owner, "shroud_admin",
        "the refused log still changed hands"
    );
}

/// A trigger the console's role put on the audit log can rewrite or drop every entry, and would
/// run as the superuser whenever that role writes the log (security audit A2).
#[tokio::test]
async fn grants_refuse_an_audit_log_with_a_planted_trigger() {
    let Some(outcome) = plant_on_audit_log(
        "trigger",
        r#"
        CREATE FUNCTION admin.scrub() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN
          NEW.action := 'session.sign_in';
          RETURN NEW;
        END
        $fn$;
        CREATE TRIGGER scrub BEFORE INSERT ON admin.audit_log
            FOR EACH ROW EXECUTE FUNCTION admin.scrub();
        "#,
        false,
    )
    .await
    else {
        return;
    };
    let refused = outcome
        .refused
        .expect("grants.sql took over an audit log with a planted trigger");
    assert!(
        refused.contains("trigger scrub on table admin.audit_log"),
        "{refused}"
    );
    assert_eq!(
        outcome.owner, "shroud_admin",
        "the refused log still changed hands"
    );
}

/// The same check on a deployment that an earlier `grants.sql` already moved, with a rule in
/// place: the next deploy stops instead of keeping it.
#[tokio::test]
async fn grants_refuse_an_audit_log_already_taken_with_a_rule() {
    let Some(outcome) = plant_on_audit_log(
        "taken",
        r#"
        CREATE RULE leak AS ON INSERT TO admin.audit_log
            DO ALSO INSERT INTO admin.loot SELECT password_hash FROM public.users;
        "#,
        true,
    )
    .await
    else {
        return;
    };
    let refused = outcome
        .refused
        .expect("a later deploy kept an audit log with a planted rule");
    assert!(
        refused.contains("rule leak on table admin.audit_log"),
        "{refused}"
    );
}

/// Two things an owner leaves behind that a rule check alone misses: an index expression, which
/// `ANALYZE`, `VACUUM` and `REINDEX` run as the table's owner, and a grant to `PUBLIC`, which
/// survives the owner change and would let the console delete entries.
#[tokio::test]
async fn grants_refuse_an_audit_log_with_an_owner_run_index_or_a_public_grant() {
    let Some(outcome) = plant_on_audit_log(
        "index",
        r#"
        CREATE FUNCTION admin.same(text) RETURNS text LANGUAGE sql IMMUTABLE AS 'SELECT $1';
        CREATE INDEX audit_log_same_idx ON admin.audit_log (admin.same(action));
        GRANT DELETE ON admin.audit_log TO PUBLIC;
        "#,
        false,
    )
    .await
    else {
        return;
    };
    let refused = outcome
        .refused
        .expect("grants.sql took over an audit log with an owner-run index");
    assert!(
        refused.contains("index admin.audit_log_same_idx uses function admin.same(text)"),
        "{refused}"
    );
    assert!(
        refused.contains("a privilege on admin.audit_log for PUBLIC"),
        "{refused}"
    );
}

/// What `plant_on_audit_log` saw after `grants.sql` ran.
struct Planted {
    /// The `DETAIL` of the refusal, which lists what was found, or `None` if the file applied.
    refused: Option<String>,
    /// Rows in `admin.loot` after the console wrote one audit entry.
    leaked: i64,
    /// Owner of `admin.audit_log` afterwards.
    owner: String,
}

/// Runs `plant_in_probe` in a database of its own and drops that database again, so the planted
/// objects never reach the database the other tests share. `None` when the grant-test variables
/// are unset.
async fn plant_on_audit_log(label: &str, plant: &str, taken_before: bool) -> Option<Planted> {
    let Some(super_url) = nonempty("GRANT_TEST_SUPER_URL") else {
        eprintln!("GRANT_TEST_SUPER_URL unset; audit log test not run");
        return None;
    };
    let Some(admin_url) = nonempty("GRANT_TEST_ADMIN_URL") else {
        eprintln!("GRANT_TEST_ADMIN_URL unset; audit log test not run");
        return None;
    };
    let base: PgConnectOptions = super_url.parse().expect("GRANT_TEST_SUPER_URL");
    let admin: PgConnectOptions = admin_url.parse().expect("GRANT_TEST_ADMIN_URL");
    let mut owner = PgConnection::connect_with(&base)
        .await
        .expect("superuser connection");

    let tag = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock after 1970")
        .as_nanos();
    let probe = format!("shroud_audit_{label}_{}_{tag}", std::process::id());
    // Human: Two calls: CREATE DATABASE cannot share the implicit transaction of a multi-statement
    // query.
    sqlx::raw_sql(&format!(r#"CREATE DATABASE "{probe}""#))
        .execute(&mut owner)
        .await
        .expect("create the probe database");
    sqlx::raw_sql(&format!(
        r#"GRANT CONNECT, CREATE ON DATABASE "{probe}" TO shroud_admin"#
    ))
    .execute(&mut owner)
    .await
    .expect("let shroud_admin into the probe database");

    let outcome = plant_in_probe(
        &base.clone().database(&probe),
        &admin.database(&probe),
        plant,
        taken_before,
    )
    .await;

    // Agent: DROPS only the database this test created above.
    sqlx::raw_sql(&format!(r#"DROP DATABASE "{probe}" WITH (FORCE)"#))
        .execute(&mut owner)
        .await
        .expect("drop the probe database");
    Some(outcome.expect("plant on the audit log and apply grants.sql"))
}

/// Builds a deployment in `probe` the way deploy does (server migrations as the superuser, the
/// console's migrations as `shroud_admin`, one account with a password hash), runs `plant` as
/// `shroud_admin` while it owns the audit log, and applies `grants.sql` as the superuser. With
/// `taken_before`, the log is first moved the way `grants.sql` did before it checked anything.
/// The console then writes one audit entry, and the rows that reached `admin.loot` are counted.
async fn plant_in_probe(
    superuser: &PgConnectOptions,
    console: &PgConnectOptions,
    plant: &str,
    taken_before: bool,
) -> Result<Planted, sqlx::Error> {
    let mut owner = PgConnection::connect_with(superuser).await?;
    sqlx::raw_sql("GRANT USAGE ON SCHEMA public TO shroud_admin")
        .execute(&mut owner)
        .await?;
    sqlx::migrate!("../../server/migrations/postgres")
        .run(&mut owner)
        .await?;
    sqlx::raw_sql(
        r#"INSERT INTO public.users (username_hash, password_hash, share_code)
           VALUES ('\x01', 'argon2id-hash-of-a-test-account', 'AUDITTEST')"#,
    )
    .execute(&mut owner)
    .await?;

    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_with(console.clone())
        .await?;
    shroud_admin::db::migrate(&pool).await?;
    // Human: The table a leaking rule writes to. The console owns it, so its INSERT is allowed
    // whoever the rule runs as.
    sqlx::raw_sql("CREATE TABLE admin.loot (stolen text)")
        .execute(&pool)
        .await?;
    sqlx::raw_sql(plant).execute(&pool).await?;

    if taken_before {
        sqlx::raw_sql(
            r#"
            ALTER SCHEMA admin OWNER TO CURRENT_USER;
            GRANT USAGE, CREATE ON SCHEMA admin TO shroud_admin;
            ALTER TABLE admin.audit_log OWNER TO CURRENT_USER;
            REVOKE ALL ON admin.audit_log FROM shroud_admin;
            GRANT SELECT, INSERT ON admin.audit_log TO shroud_admin;
            "#,
        )
        .execute(&mut owner)
        .await?;
    }

    // Human: The refusal is the expected outcome, so it is returned rather than raised.
    let refused = match sqlx::raw_sql(include_str!("../grants.sql"))
        .execute(&mut owner)
        .await
    {
        Ok(_) => None,
        Err(err) => {
            let detail = err
                .as_database_error()
                .and_then(|db| db.try_downcast_ref::<sqlx::postgres::PgDatabaseError>())
                .filter(|pg| pg.message().starts_with("admin.audit_log carries objects"))
                .and_then(|pg| pg.detail().map(str::to_owned));
            match detail {
                Some(detail) => Some(detail),
                None => return Err(err),
            }
        }
    };
    owner.close().await?;

    // Human: A refused log stays with shroud_admin, and then the rule runs with its rights,
    // which cannot read password_hash: that entry fails, which is fine here.
    let _ = sqlx::raw_sql(
        r#"
        INSERT INTO admin.operators (name, role) VALUES ('auditor', 'write');
        INSERT INTO admin.audit_log (operator_id, action, outcome)
            SELECT id, 'session.sign_in', 'ok' FROM admin.operators;
        "#,
    )
    .execute(&pool)
    .await;
    pool.close().await;

    let mut check = PgConnection::connect_with(superuser).await?;
    let leaked: i64 = sqlx::query_scalar("SELECT count(*) FROM admin.loot")
        .fetch_one(&mut check)
        .await?;
    let owner: String = sqlx::query_scalar(
        "SELECT relowner::regrole::text FROM pg_catalog.pg_class WHERE oid = 'admin.audit_log'::regclass",
    )
    .fetch_one(&mut check)
    .await?;
    check.close().await?;
    Ok(Planted {
        refused,
        leaked,
        owner,
    })
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
