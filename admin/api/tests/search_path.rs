//! The console's own connections must not search schema `public`, which `shroud_api` owns in a
//! deploy (`server/docker/api-role.sql`): a function or operator it puts there that matches the
//! argument types more exactly than `pg_catalog`'s would run inside the console's queries with the
//! console's rights (the same class as security audit A1, CVE-2018-1058).
//!
//! Runs in a probe database of its own against a throwaway Postgres started with
//! `server/docker/init-admin-role.sh`: `GRANT_TEST_SUPER_URL` is the superuser,
//! `GRANT_TEST_ADMIN_URL` is `shroud_admin`. Without both the test does not claim to have checked
//! anything.

use std::time::{SystemTime, UNIX_EPOCH};

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use http_body_util::BodyExt;
use serde_json::Value;
use sha2::{Digest, Sha256};
use shroud_admin::AppState;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use sqlx::{Connection, PgConnection, PgPool};
use tower::ServiceExt;
use uuid::Uuid;

const OPERATOR: &str = "00000000-0000-4000-8000-00000000c5a1";
const SESSION: &str = "5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a";
const USER: &str = "5ea7c000-0000-4000-8000-000000000001";
const DEVICE: &str = "5ea7c000-0000-4000-8000-0000000000d1";

/// What the planted `public.count(uuid)` adds, so a count it produced cannot pass for a real one.
const PLANTED_OFFSET: i64 = 1_000_000;

/// `shroud_api` plants `public.count(uuid)`. `pg_catalog` only has `count("any")`, so on a
/// connection that searches `public` the planted aggregate is the exact match and wins for every
/// `count(<uuid column>)` the console runs: here the device count on `GET /users`.
#[tokio::test]
async fn a_count_planted_in_public_by_the_api_role_never_runs_in_console_queries() {
    let Some(super_url) = nonempty("GRANT_TEST_SUPER_URL") else {
        eprintln!("GRANT_TEST_SUPER_URL unset; console search_path test not run");
        return;
    };
    let Some(admin_url) = nonempty("GRANT_TEST_ADMIN_URL") else {
        eprintln!("GRANT_TEST_ADMIN_URL unset; console search_path test not run");
        return;
    };
    let base: PgConnectOptions = super_url.parse().expect("GRANT_TEST_SUPER_URL");
    let mut owner = PgConnection::connect_with(&base)
        .await
        .expect("superuser connection");

    // Human: The role is cluster-wide, so a deployment's real shroud_api would get this test's
    // objects and grants. Refuse instead of touching it, as the superuser search_path test does.
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

    let (server, _) = admin_url
        .rsplit_once('/')
        .expect("GRANT_TEST_ADMIN_URL ends in /<database>");
    let tag = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock after 1970")
        .as_nanos();
    let probe = format!("shroud_console_path_{}_{tag}", std::process::id());
    let console_url = format!("{server}/{probe}");
    sqlx::raw_sql(&format!(r#"CREATE DATABASE "{probe}""#))
        .execute(&mut owner)
        .await
        .expect("create the probe database");

    // Human: Run on a thread of its own so that a panic in it still reaches the cleanup below
    // instead of leaving a shroud_api role that would make the other tests refuse to run. A
    // blocking thread, because sqlx's migrator future is not Send and tokio::spawn needs that.
    let setup = format!(
        r#"CREATE ROLE shroud_api NOLOGIN;
           GRANT CONNECT, CREATE ON DATABASE "{probe}" TO shroud_admin"#
    );
    // Agent: One simple-query batch is one transaction: on Err neither statement took effect.
    let created = sqlx::raw_sql(&setup).execute(&mut owner).await;
    let outcome = match &created {
        Ok(_) => {
            let target = base.database(&probe);
            let runtime = tokio::runtime::Handle::current();
            let joined = tokio::task::spawn_blocking(move || {
                runtime.block_on(plant_and_list(target, console_url))
            })
            .await;
            match joined {
                Ok(done) => done.map_err(|err| err.to_string()),
                Err(panicked) => Err(panicked.to_string()),
            }
        }
        Err(err) => Err(err.to_string()),
    };

    // Human: Clean up before asserting, so a failing run leaves nothing behind.
    // Agent: DROPS only the database and the role this test created above.
    sqlx::raw_sql(&format!(r#"DROP DATABASE "{probe}" WITH (FORCE)"#))
        .execute(&mut owner)
        .await
        .expect("drop the probe database");
    if created.is_ok() {
        sqlx::raw_sql("DROP ROLE shroud_api")
            .execute(&mut owner)
            .await
            .expect("drop the probe role");
    }

    let (search_path, listed) = outcome.expect("plant the aggregate and list users");
    assert_eq!(listed.status, StatusCode::OK, "{}", listed.text);
    assert_eq!(listed.body["items"][0]["id"], USER);
    assert_eq!(
        listed.body["items"][0]["devices"], 1,
        "GET /users counted devices with the count(uuid) shroud_api planted in public"
    );
    assert_eq!(
        search_path, "pg_catalog,admin,pg_temp",
        "the console's connection searches a schema shroud_api owns"
    );
}

/// Builds the API's tables and the console's schema in the probe database, gives schema `public`
/// to `shroud_api` as `api-role.sql` does, plants `public.count(uuid)` as that role, seeds one
/// user with one device and lists users through the console. Returns the console connection's
/// `search_path` and the reply.
async fn plant_and_list(
    target: PgConnectOptions,
    console_url: String,
) -> Result<(String, Reply), sqlx::Error> {
    let owner = PgPoolOptions::new()
        .max_connections(1)
        .connect_with(target)
        .await?;
    sqlx::migrate!("../../server/migrations/postgres")
        .run(&owner)
        .await?;

    // Human: The console's pool as it opens it in production, pointed at the probe database.
    let console = shroud_admin::db::connect(&console_url).await?;
    shroud_admin::db::migrate(&console).await?;
    sqlx::raw_sql(include_str!("../grants.sql"))
        .execute(&owner)
        .await?;

    // Human: What SQL access as shroud_api allows once it owns public: an aggregate that matches
    // a uuid argument exactly and inflates every count it makes.
    sqlx::raw_sql(&format!(
        r#"
        ALTER SCHEMA public OWNER TO shroud_api;
        SET ROLE shroud_api;
        CREATE FUNCTION public.planted_count(bigint, uuid) RETURNS bigint
            LANGUAGE sql AS 'SELECT $1 OPERATOR(pg_catalog.+) {PLANTED_OFFSET}';
        CREATE AGGREGATE public.count(uuid) (
            SFUNC = public.planted_count, STYPE = bigint, INITCOND = '0'
        );
        RESET ROLE;
        "#
    ))
    .execute(&owner)
    .await?;

    seed(&console, &owner).await?;
    let search_path: String = sqlx::query_scalar("SHOW search_path")
        .fetch_one(&console)
        .await?;
    let app = shroud_admin::router_with(None, AppState::connected(console.clone(), [9u8; 32]));
    let listed = send(&app, "/api/admin/users?q=5ea7c000&limit=10").await;
    console.close().await;
    owner.close().await;
    Ok((search_path, listed))
}

/// One operator with a live session (console tables), and one user with one device (the API's).
async fn seed(console: &PgPool, owner: &PgPool) -> Result<(), sqlx::Error> {
    let operator = Uuid::parse_str(OPERATOR).expect("operator id");
    sqlx::query(
        "INSERT INTO admin.operators (id, name, role, enabled) VALUES ($1, 'path-probe', 'read', true)",
    )
    .bind(operator)
    .execute(console)
    .await?;
    let id_hash: [u8; 32] = Sha256::digest(SESSION.as_bytes()).into();
    sqlx::query(
        "INSERT INTO admin.operator_sessions (id_hash, operator_id, csrf) VALUES ($1, $2, 'csrf')",
    )
    .bind(id_hash.as_slice())
    .bind(operator)
    .execute(console)
    .await?;

    let user = Uuid::parse_str(USER).expect("user id");
    sqlx::query(
        "INSERT INTO public.users (id, username_hash, password_hash, share_code)
         VALUES ($1, $2, 'path-probe-hash', 'SPATH0001')",
    )
    .bind(user)
    .bind(vec![0x5a_u8; 32])
    .execute(owner)
    .await?;
    sqlx::query("INSERT INTO public.devices (id, user_id) VALUES ($1, $2)")
        .bind(Uuid::parse_str(DEVICE).expect("device id"))
        .bind(user)
        .execute(owner)
        .await?;
    Ok(())
}

fn nonempty(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

struct Reply {
    status: StatusCode,
    text: String,
    body: Value,
}

async fn send(app: &axum::Router, uri: &str) -> Reply {
    let request = Request::builder()
        .method("GET")
        .uri(uri)
        .header(header::COOKIE, format!("__Host-admin={SESSION}"))
        .body(Body::empty())
        .expect("request");
    let response = app.clone().oneshot(request).await.expect("response");
    let status = response.status();
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    let text = String::from_utf8(bytes.to_vec()).expect("utf-8 body");
    let body = serde_json::from_str(&text).unwrap_or(Value::Null);
    Reply { status, text, body }
}
