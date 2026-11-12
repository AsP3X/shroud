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

    // Human: On a database an earlier run already took the audit log over in, a console migration
    // that alters the log waits for grants.sql (security audit A5), as on a redeploy. The file
    // skips schema admin until the console has created it, so this is a no-op on a fresh one.
    let grants = include_str!("../grants.sql");
    sqlx::raw_sql(grants)
        .execute(&owner)
        .await
        .expect("column grants before the console starts");
    let admin = PgPoolOptions::new()
        .connect(&admin_url)
        .await
        .expect("shroud_admin connection");
    shroud_admin::db::migrate(&admin)
        .await
        .expect("admin migrations");

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

/// Console migration 004 adds `admin.audit_log.operator_name` (security audit A5). On a deployment
/// whose log `grants.sql` already gave to the superuser, the console's role cannot add it: the
/// console must refuse to start rather than skip it, and the next `grants.sql` adds it, filled in
/// with each operator's name, without leaving the console an update right.
#[tokio::test]
async fn an_adopted_audit_log_gets_operator_names_from_grants_sql() {
    let Some(super_url) = nonempty("GRANT_TEST_SUPER_URL") else {
        eprintln!("GRANT_TEST_SUPER_URL unset; audit-log upgrade test not run");
        return;
    };
    let Some(admin_url) = nonempty("GRANT_TEST_ADMIN_URL") else {
        eprintln!("GRANT_TEST_ADMIN_URL unset; audit-log upgrade test not run");
        return;
    };
    let base: PgConnectOptions = super_url.parse().expect("GRANT_TEST_SUPER_URL");
    let admin_base: PgConnectOptions = admin_url.parse().expect("GRANT_TEST_ADMIN_URL");
    let mut owner = PgConnection::connect_with(&base)
        .await
        .expect("superuser connection");

    // Human: A database of its own, so the shared one keeps the column the other tests use.
    let tag = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock after 1970")
        .as_nanos();
    let probe = format!("shroud_audit_name_{}_{tag}", std::process::id());
    sqlx::raw_sql(&format!(r#"CREATE DATABASE "{probe}""#))
        .execute(&mut owner)
        .await
        .expect("create the probe database");
    sqlx::raw_sql(&format!(
        r#"GRANT CONNECT, CREATE ON DATABASE "{probe}" TO shroud_admin"#
    ))
    .execute(&mut owner)
    .await
    .expect("let the console into the probe database");

    let outcome = upgrade_adopted_log(&base, &admin_base, &probe, tag).await;

    // Agent: DROPS only the database this test created above.
    sqlx::raw_sql(&format!(r#"DROP DATABASE "{probe}" WITH (FORCE)"#))
        .execute(&mut owner)
        .await
        .expect("drop the probe database");

    let upgrade = outcome.expect("upgrade the adopted audit log");
    assert!(
        upgrade.refused_start.contains("needs column operator_name"),
        "the console started without the column: {}",
        upgrade.refused_start
    );
    assert_eq!(upgrade.names, ["before-upgrade", "before-upgrade"]);
    assert!(
        upgrade.not_null,
        "operator_name accepts entries without a name"
    );
    assert!(
        !upgrade.console_can_update,
        "grants.sql left shroud_admin an update right on the audit log"
    );
}

struct Upgrade {
    refused_start: String,
    names: Vec<String>,
    not_null: bool,
    console_can_update: bool,
}

/// Builds, in `probe`, a deployment from before migration 004 whose audit log the superuser took
/// over, starts the new console on it, applies `grants.sql` twice as a deploy would, and starts the
/// console again.
async fn upgrade_adopted_log(
    base: &PgConnectOptions,
    admin_base: &PgConnectOptions,
    probe: &str,
    tag: u128,
) -> Result<Upgrade, sqlx::Error> {
    let owner = PgPoolOptions::new()
        .max_connections(1)
        .connect_with(base.clone().database(probe))
        .await?;
    sqlx::migrate!("../../server/migrations/postgres")
        .run(&owner)
        .await?;

    // Human: The console as it was before 004: only 001-003 applied, by its own role.
    // Agent: WRITES a temporary directory with those three files and removes it after the run.
    let old = std::env::temp_dir().join(format!("shroud-admin-migrations-{tag}"));
    std::fs::create_dir_all(&old).expect("temporary migrations directory");
    for (file, sql) in [
        ("001_admin.sql", include_str!("../migrations/001_admin.sql")),
        (
            "002_totp_step.sql",
            include_str!("../migrations/002_totp_step.sql"),
        ),
        (
            "003_setup_secret.sql",
            include_str!("../migrations/003_setup_secret.sql"),
        ),
    ] {
        std::fs::write(old.join(file), sql).expect("write an old migration");
    }
    let console = admin_base.clone().database(probe);
    let mut first = PgConnection::connect_with(&console).await?;
    sqlx::raw_sql("CREATE SCHEMA admin; SET search_path TO admin")
        .execute(&mut first)
        .await?;
    let migrated = match sqlx::migrate::Migrator::new(old.as_path()).await {
        Ok(migrator) => migrator.run(&mut first).await,
        Err(err) => Err(err),
    };
    std::fs::remove_dir_all(&old).expect("remove the temporary migrations");
    migrated.map_err(|err| sqlx::Error::Migrate(Box::new(err)))?;
    sqlx::raw_sql(
        "INSERT INTO admin.operators (id, name, role)
         VALUES ('00000000-0000-4000-8000-0000000a5a5a', 'before-upgrade', 'write');
         INSERT INTO admin.audit_log (operator_id, action, outcome)
         VALUES ('00000000-0000-4000-8000-0000000a5a5a', 'session.sign_in', 'ok'),
                ('00000000-0000-4000-8000-0000000a5a5a', 'session.sign_out', 'ok')",
    )
    .execute(&mut first)
    .await?;
    first.close().await?;

    // The superuser took the log over, as grants.sql did before 004.
    sqlx::raw_sql(
        "ALTER SCHEMA admin OWNER TO CURRENT_USER;
         GRANT USAGE, CREATE ON SCHEMA admin TO shroud_admin;
         ALTER TABLE admin.audit_log OWNER TO CURRENT_USER;
         REVOKE ALL ON admin.audit_log FROM shroud_admin;
         GRANT SELECT, INSERT ON admin.audit_log TO shroud_admin",
    )
    .execute(&owner)
    .await?;

    let admin = PgPoolOptions::new()
        .max_connections(1)
        .connect_with(console)
        .await?;
    let refused_start = match shroud_admin::db::migrate(&admin).await {
        Ok(()) => String::new(),
        Err(err) => format!("{err} {:?}", err),
    };

    let grants = include_str!("../grants.sql");
    sqlx::raw_sql(grants).execute(&owner).await?;
    sqlx::raw_sql(grants).execute(&owner).await?;
    shroud_admin::db::migrate(&admin).await?;

    let names = sqlx::query_scalar("SELECT operator_name FROM admin.audit_log ORDER BY action")
        .fetch_all(&admin)
        .await?;
    let not_null = sqlx::query_scalar(
        "SELECT attnotnull FROM pg_catalog.pg_attribute
         WHERE attrelid = 'admin.audit_log'::pg_catalog.regclass AND attname = 'operator_name'",
    )
    .fetch_one(&owner)
    .await?;
    let console_can_update = sqlx::query_scalar(
        "SELECT pg_catalog.has_table_privilege('shroud_admin', 'admin.audit_log', 'UPDATE')
             OR pg_catalog.has_column_privilege('shroud_admin', 'admin.audit_log', 'operator_name', 'UPDATE')",
    )
    .fetch_one(&owner)
    .await?;
    owner.close().await;
    admin.close().await;
    Ok(Upgrade {
        refused_start,
        names,
        not_null,
        console_can_update,
    })
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
        When::Before,
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
        When::Before,
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
        When::AfterOldTakeover,
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
        When::Before,
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

/// The console's role still owns the log while `grants.sql` waits to take it over, so what it
/// attaches in that moment has to be caught too (security audit A2, found in review). Here it holds
/// an ordinary read lock, so the takeover has to wait, and adds a rule and a `PUBLIC` grant then.
#[tokio::test]
async fn grants_refuse_what_is_attached_while_the_takeover_waits() {
    let Some(outcome) = plant_on_audit_log(
        "waiting",
        r#"
        CREATE RULE leak AS ON INSERT TO admin.audit_log
            DO ALSO INSERT INTO admin.loot SELECT password_hash FROM public.users;
        GRANT DELETE ON admin.audit_log TO PUBLIC;
        "#,
        When::WhileTakeoverWaits,
    )
    .await
    else {
        return;
    };
    let refused = outcome
        .refused
        .expect("grants.sql took over an audit log that was given a rule while it waited");
    assert!(
        refused.contains("rule leak on table admin.audit_log"),
        "{refused}"
    );
    assert!(
        refused.contains("a privilege on admin.audit_log for PUBLIC"),
        "{refused}"
    );
    assert_eq!(outcome.leaked, 0, "an audit entry copied password hashes");
    assert_eq!(
        outcome.owner, "shroud_admin",
        "the refused log still changed hands"
    );
}

/// When `plant_in_probe` runs the console's SQL, relative to `grants.sql`.
#[derive(Clone, Copy)]
enum When {
    /// Before `grants.sql` runs, while the console's role owns the log.
    Before,
    /// Before, and the log is then moved the way `grants.sql` did before it checked anything.
    AfterOldTakeover,
    /// While `grants.sql` waits for a read lock the console holds on the log.
    WhileTakeoverWaits,
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
async fn plant_on_audit_log(label: &str, plant: &str, when: When) -> Option<Planted> {
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
        when,
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
/// `shroud_admin` while it owns the audit log, at the point `when` names, and applies `grants.sql`
/// as the superuser.
/// The console then writes one audit entry, and the rows that reached `admin.loot` are counted.
async fn plant_in_probe(
    superuser: &PgConnectOptions,
    console: &PgConnectOptions,
    plant: &str,
    when: When,
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
    if !matches!(when, When::WhileTakeoverWaits) {
        sqlx::raw_sql(plant).execute(&pool).await?;
    }

    if matches!(when, When::AfterOldTakeover) {
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

    let (owner, applied) = if matches!(when, When::WhileTakeoverWaits) {
        plant_while_takeover_waits(superuser, console, owner, plant).await?
    } else {
        let applied = sqlx::raw_sql(include_str!("../grants.sql"))
            .execute(&mut owner)
            .await
            .map(drop);
        (owner, applied)
    };

    // Human: The refusal is the expected outcome, so it is returned rather than raised.
    let refused = match applied {
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

/// Applies `grants.sql` on `owner` while a second console session holds a read lock on the log,
/// runs `plant` in that session once the takeover is waiting for the lock, and commits. Returns the
/// connection and the outcome of `grants.sql`.
async fn plant_while_takeover_waits(
    superuser: &PgConnectOptions,
    console: &PgConnectOptions,
    mut owner: PgConnection,
    plant: &str,
) -> Result<(PgConnection, Result<(), sqlx::Error>), sqlx::Error> {
    let mut holder = PgConnection::connect_with(console).await?;
    sqlx::raw_sql("BEGIN").execute(&mut holder).await?;
    sqlx::raw_sql("LOCK TABLE admin.audit_log IN ACCESS SHARE MODE")
        .execute(&mut holder)
        .await?;

    // Human: Both halves run on this task, so the deploy can borrow `owner`.
    let deploy = async {
        sqlx::raw_sql(include_str!("../grants.sql"))
            .execute(&mut owner)
            .await
            .map(drop)
    };
    let attach = async {
        // Human: Wait until the takeover is queued behind the read lock, for at most 10 s.
        // Agent: READS pg_locks in the probe database every 50 ms.
        let mut watch = PgConnection::connect_with(superuser).await?;
        let mut waiting = false;
        for _ in 0..200 {
            waiting = sqlx::query_scalar(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks
                                WHERE relation = 'admin.audit_log'::regclass AND NOT granted)",
            )
            .fetch_one(&mut watch)
            .await?;
            if waiting {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
        watch.close().await?;
        assert!(
            waiting,
            "grants.sql never waited for the console's read lock"
        );

        sqlx::raw_sql(plant).execute(&mut holder).await?;
        sqlx::raw_sql("COMMIT").execute(&mut holder).await?;
        holder.close().await
    };
    let (applied, attached) = tokio::join!(deploy, attach);
    attached?;
    Ok((owner, applied))
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
