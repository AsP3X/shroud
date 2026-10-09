//! `GET /overview` against a throwaway Postgres and a stand-in for the API.
//!
//! `ADMIN_TEST_DATABASE_URL` is `shroud_admin`. `GRANT_TEST_SUPER_URL` is the table owner,
//! which seeds rows the console role cannot insert. Without both, the count test returns
//! immediately and a green run is not proof. The 401 and stopped-database cases always run.

use std::sync::Arc;
use std::sync::atomic::{AtomicU8, Ordering};

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use http_body_util::BodyExt;
use serde_json::Value;
use sha2::{Digest, Sha256};
use shroud_admin::AppState;
use sqlx::postgres::PgPoolOptions;
use sqlx::{PgPool, Row};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tower::ServiceExt;
use uuid::Uuid;

const OPERATOR: &str = "00000000-0000-4000-8000-00000000a112";
const USER_OLD: &str = "00000000-0000-4000-8000-00000000a201";
const USER_NEW: &str = "00000000-0000-4000-8000-00000000a202";
const USER_DELETED: &str = "00000000-0000-4000-8000-00000000a203";
const SEALED_NAME: &[u8] = b"SEALED-DEVICE-NAME-DO-NOT-LEAK!!";
const SESSION: &str = "abababababababababababababababababababababababababababababababab";

#[tokio::test]
async fn overview_without_a_session_is_401_and_a_stopped_database_is_502() {
    let app = shroud_admin::router(None);
    let missing = send(&app, None).await;
    assert_eq!(missing.status, StatusCode::UNAUTHORIZED);
    assert_eq!(
        missing.body,
        serde_json::from_str::<Value>(include_str!("../fixtures/error.unauthenticated.json"))
            .unwrap()
    );
    assert_eq!(
        missing.headers.get(header::CACHE_CONTROL).unwrap(),
        "no-store"
    );

    let pool = PgPoolOptions::new()
        .connect_lazy("postgres://shroud_admin:unused@127.0.0.1:1/shroud")
        .expect("lazy pool");
    let dead = shroud_admin::router_with(None, AppState::connected(pool, [9u8; 32]));
    let refused = send(&dead, Some(SESSION)).await;
    assert_eq!(refused.status, StatusCode::BAD_GATEWAY);
    assert_eq!(
        refused.body,
        serde_json::from_str::<Value>(include_str!("../fixtures/error.upstream-postgres.json"))
            .unwrap()
    );

    let unsigned = send(&dead, None).await;
    assert_eq!(unsigned.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn overview_matches_the_schema_and_a_failing_probe_is_not_ready() {
    let Some(admin_url) = nonempty("ADMIN_TEST_DATABASE_URL") else {
        eprintln!("ADMIN_TEST_DATABASE_URL unset; overview count test not run");
        return;
    };
    let Some(super_url) = nonempty("GRANT_TEST_SUPER_URL") else {
        eprintln!("GRANT_TEST_SUPER_URL unset; overview count test not run");
        return;
    };

    let admin = PgPoolOptions::new()
        .connect(&admin_url)
        .await
        .expect("shroud_admin connection");
    shroud_admin::db::migrate(&admin)
        .await
        .expect("admin migrations");
    let owner = PgPoolOptions::new()
        .connect(&super_url)
        .await
        .expect("owner connection");

    let operator = Uuid::parse_str(OPERATOR).unwrap();
    let users = [
        Uuid::parse_str(USER_OLD).unwrap(),
        Uuid::parse_str(USER_NEW).unwrap(),
        Uuid::parse_str(USER_DELETED).unwrap(),
    ];
    clear(&admin, &owner, operator, &users).await;
    seed(&admin, &owner, operator, &users).await;

    let mode = Arc::new(AtomicU8::new(0));
    let base = spawn_api(Arc::clone(&mode)).await;
    set_env("API_INTERNAL_URL", Some(&base));
    set_env("OPERATOR_PORT", Some(base.rsplit(':').next().unwrap()));
    set_env("OPERATOR_TOKEN", Some("operator-test-token"));
    configure_env(true);

    let app = shroud_admin::router_with(None, AppState::connected(admin.clone(), [9u8; 32]));
    let first = send(&app, Some(SESSION)).await;
    assert_eq!(first.status, StatusCode::OK, "{}", first.text);
    assert_schema(&first.body);
    assert!(!first.text.contains("SEALED-DEVICE-NAME-DO-NOT-LEAK"));
    assert!(!first.text.contains("overview-secret-hash"));
    assert!(!first.text.contains("OVERVIEW"));

    let expected = count_rows(&admin).await;
    assert_eq!(first.body["stats"]["accounts"], expected.accounts);
    assert_eq!(first.body["stats"]["accounts_7d"], expected.accounts_7d);
    assert_eq!(
        first.body["stats"]["accounts_deleted"],
        expected.accounts_deleted
    );
    assert_eq!(
        first.body["stats"]["devices_active_30d"],
        expected.devices_active_30d
    );
    assert_eq!(first.body["stats"]["ws_connections"], 7);
    assert_eq!(first.body["stats"]["messages_sent_total"], 40);
    assert_eq!(first.body["metrics"]["http_requests_total"], 10);
    assert_eq!(first.body["metrics"]["media_legacy_reads_total"], 4);
    assert_eq!(first.body["metrics"]["calls_created_total"], 5);
    assert_eq!(first.body["ready"]["status"], "ok");
    assert_eq!(first.body["ready"]["redis"], "skipped");
    assert_eq!(first.body["server"]["version"], "0.1.0");
    assert_eq!(
        first.body["configured"]["apns"]["environment"],
        "production"
    );
    assert_eq!(
        first.body["configured"]["apns"]["topic"],
        "de.corespace.shroud"
    );
    assert_eq!(
        first.body["configured"]["web_push"]["subscriptions"],
        expected.web_push_subscriptions
    );
    assert_eq!(
        first.body["configured"]["unifiedpush"]["allowed_hosts"][0],
        "ntfy.sh"
    );
    assert_eq!(
        first.body["configured"]["unifiedpush"]["public_hosts"],
        false
    );
    assert_eq!(
        first.body["configured"]["turn"]["credential_ttl_secs"],
        43200
    );
    assert_eq!(first.body["configured"]["link_relay"]["max_per_account"], 6);
    assert_eq!(first.body["attention"][0]["kind"], "legacy_media");
    assert_eq!(first.body["attention"][0]["count"], 4);
    assert!(first.body["attention"][1].is_null());
    let started =
        chrono::DateTime::parse_from_rfc3339(first.body["server"]["started_at"].as_str().unwrap())
            .unwrap();
    let checked =
        chrono::DateTime::parse_from_rfc3339(first.body["server"]["checked_at"].as_str().unwrap())
            .unwrap();
    assert_eq!((checked - started).num_seconds(), 120);

    sqlx::query("DELETE FROM users WHERE id = $1")
        .bind(users[1])
        .execute(&owner)
        .await
        .unwrap();
    let second = send(&app, Some(SESSION)).await;
    assert_eq!(second.status, StatusCode::OK, "{}", second.text);
    assert_eq!(second.body["stats"]["accounts"], expected.accounts);
    let after_delete = count_rows(&admin).await;
    assert!(after_delete.accounts < expected.accounts);

    configure_env(false);
    let open = send(&app, Some(SESSION)).await;
    assert_eq!(open.status, StatusCode::OK, "{}", open.text);
    let kinds: Vec<&str> = open.body["attention"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["kind"].as_str().unwrap())
        .collect();
    assert!(kinds.contains(&"no_min_version"));
    assert!(kinds.contains(&"legacy_media"));
    assert!(open.body["configured"]["apns"].is_null());
    assert!(open.body["configured"]["turn"].is_null());

    mode.store(1, Ordering::Relaxed);
    configure_env(true);
    let down = send(&app, Some(SESSION)).await;
    assert_eq!(down.status, StatusCode::OK, "{}", down.text);
    assert_eq!(down.body["ready"]["status"], "not_ready");
    assert_eq!(down.body["ready"]["database"], "error");
    assert_eq!(down.body["attention"].as_array().unwrap().len(), 1);
    assert_eq!(down.body["attention"][0]["kind"], "not_ready");
    assert!(down.body["attention"][0].get("count").is_none());
    assert_schema(&down.body);

    set_env("API_INTERNAL_URL", Some("http://127.0.0.1:1"));
    let unreachable = send(&app, Some(SESSION)).await;
    assert_eq!(unreachable.status, StatusCode::BAD_GATEWAY);
    assert_eq!(
        unreachable.body,
        serde_json::from_str::<Value>(include_str!("../fixtures/error.upstream-api.json")).unwrap()
    );

    clear(&admin, &owner, operator, &users).await;
    clear_env();
}

struct Counts {
    accounts: i64,
    accounts_7d: i64,
    accounts_deleted: i64,
    devices_active_30d: i64,
    web_push_subscriptions: i64,
}

async fn count_rows(pool: &PgPool) -> Counts {
    let row = sqlx::query(
        "SELECT
            (SELECT count(id) FROM users) AS accounts,
            (SELECT count(id) FROM users WHERE deleted_at IS NOT NULL) AS accounts_deleted,
            (SELECT count(id) FROM users WHERE created_at >= now() - interval '7 days') AS accounts_7d,
            (SELECT count(id) FROM devices
                WHERE revoked_at IS NULL AND last_seen_at >= now() - interval '30 days')
                AS devices_active_30d,
            (SELECT count(device_id) FROM web_push_subscriptions) AS web_push_subscriptions",
    )
    .fetch_one(pool)
    .await
    .expect("counts");
    Counts {
        accounts: row.get("accounts"),
        accounts_7d: row.get("accounts_7d"),
        accounts_deleted: row.get("accounts_deleted"),
        devices_active_30d: row.get("devices_active_30d"),
        web_push_subscriptions: row.get("web_push_subscriptions"),
    }
}

async fn clear(admin: &PgPool, owner: &PgPool, operator: Uuid, users: &[Uuid]) {
    sqlx::query("DELETE FROM admin.operator_sessions WHERE operator_id = $1")
        .bind(operator)
        .execute(admin)
        .await
        .unwrap();
    sqlx::query("DELETE FROM admin.operators WHERE id = $1")
        .bind(operator)
        .execute(admin)
        .await
        .unwrap();
    sqlx::query("DELETE FROM devices WHERE user_id = ANY($1)")
        .bind(users)
        .execute(owner)
        .await
        .unwrap();
    sqlx::query("DELETE FROM users WHERE id = ANY($1)")
        .bind(users)
        .execute(owner)
        .await
        .unwrap();
}

async fn seed(admin: &PgPool, owner: &PgPool, operator: Uuid, users: &[Uuid]) {
    sqlx::query("INSERT INTO admin.operators (id, name, role, enabled) VALUES ($1, 'overview-probe', 'read', true)")
        .bind(operator)
        .execute(admin)
        .await
        .unwrap();
    let digest = Sha256::digest(SESSION.as_bytes());
    let mut id_hash = [0u8; 32];
    id_hash.copy_from_slice(&digest);
    sqlx::query(
        "INSERT INTO admin.operator_sessions (id_hash, operator_id, csrf) VALUES ($1, $2, 'csrf')",
    )
    .bind(id_hash.as_slice())
    .bind(operator)
    .execute(admin)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO users (id, username_hash, password_hash, share_code, created_at)
         VALUES ($1, $2, 'overview-secret-hash', 'OVERVIEW01', now() - interval '40 days')",
    )
    .bind(users[0])
    .bind(vec![1u8; 32])
    .execute(owner)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO users (id, username_hash, password_hash, share_code, created_at)
         VALUES ($1, $2, 'overview-secret-hash', 'OVERVIEW02', now())",
    )
    .bind(users[1])
    .bind(vec![2u8; 32])
    .execute(owner)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO users (id, created_at, deleted_at) VALUES ($1, now() - interval '2 days', now())",
    )
    .bind(users[2])
    .execute(owner)
    .await
    .unwrap();
    sqlx::query("INSERT INTO devices (user_id, sealed_name, last_seen_at) VALUES ($1, $2, now())")
        .bind(users[1])
        .bind(SEALED_NAME)
        .execute(owner)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO devices (user_id, last_seen_at) VALUES ($1, now() - interval '40 days')",
    )
    .bind(users[0])
    .execute(owner)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO devices (user_id, last_seen_at, revoked_at) VALUES ($1, now(), now())",
    )
    .bind(users[1])
    .execute(owner)
    .await
    .unwrap();
}

async fn spawn_api(mode: Arc<AtomicU8>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                break;
            };
            let mode = Arc::clone(&mode);
            tokio::spawn(async move {
                let mut buf = vec![0u8; 2048];
                let mut filled = 0usize;
                loop {
                    let header_done =
                        filled >= 4 && buf[..filled].windows(4).any(|window| window == b"\r\n\r\n");
                    if header_done || filled == buf.len() {
                        break;
                    }
                    let n = socket.read(&mut buf[filled..]).await.unwrap_or(0);
                    if n == 0 {
                        break;
                    }
                    filled += n;
                }
                let request = String::from_utf8_lossy(&buf[..filled]);
                let path = request.split_whitespace().nth(1).unwrap_or("");
                let failing = mode.load(Ordering::Relaxed) == 1;
                let (status, content_type, body) = if path.starts_with("/api/v1/health/ready") {
                    if failing {
                        (
                            503,
                            "application/json",
                            r#"{"status":"not_ready","database":"error","redis":"ok","media":"ok"}"#
                                .to_owned(),
                        )
                    } else {
                        (
                            200,
                            "application/json",
                            r#"{"status":"ok","database":"ok","redis":"skipped","media":"ok"}"#
                                .to_owned(),
                        )
                    }
                } else if path.starts_with("/operator/metrics")
                    && request.contains("Authorization: Bearer operator-test-token\r\n")
                {
                    let legacy = if failing { 0 } else { 4 };
                    (
                        200,
                        "text/plain",
                        format!(
                            "shroud_uptime_seconds 120\n\
                             shroud_http_requests_total 10\n\
                             shroud_http_errors_total 2\n\
                             shroud_ws_connections 7\n\
                             shroud_messages_sent_total 40\n\
                             shroud_media_puts_total 1\n\
                             shroud_media_gets_total 3\n\
                             shroud_media_store_errors_total 0\n\
                             shroud_media_legacy_reads_total {legacy}\n\
                             shroud_media_migrated_total 8\n\
                             shroud_calls_created_total 5\n"
                        ),
                    )
                } else if path.starts_with("/api/v1/client-version") {
                    (
                        200,
                        "application/json",
                        r#"{"status":"current","latest_version":null,"update_url":null,"server_version":"0.1.0"}"#
                            .to_owned(),
                    )
                } else {
                    (404, "text/plain", "no".to_owned())
                };
                let response = format!(
                    "HTTP/1.1 {status} X\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = socket.write_all(response.as_bytes()).await;
            });
        }
    });
    format!("http://127.0.0.1:{port}")
}

fn configure_env(full: bool) {
    if full {
        set_env("APNS_TOPIC", Some("de.corespace.shroud"));
        set_env("APNS_KEY_ID", Some("KEYID123"));
        set_env("APNS_TEAM_ID", Some("TEAMID12"));
        set_env("APNS_ENVIRONMENT", Some("production"));
        set_env("APNS_KEY_PATH_SET", Some("true"));
        set_env("WEB_PUSH_VAPID_PUBLIC_KEY", Some("public-key"));
        set_env("UNIFIEDPUSH_ALLOWED_HOSTS", Some("ntfy.sh, up.example.org"));
        set_env("UNIFIEDPUSH_PUBLIC_HOSTS", Some("false"));
        set_env("TURN_URLS", Some("turn:turn.example.org:3478"));
        set_env("TURN_CREDENTIAL_TTL_SECS", None);
        set_env("IOS_MIN_VERSION", Some("1.0.0"));
        set_env("ANDROID_MIN_VERSION", Some("1.0.0"));
    } else {
        for name in [
            "APNS_TOPIC",
            "APNS_KEY_ID",
            "APNS_TEAM_ID",
            "APNS_ENVIRONMENT",
            "APNS_KEY_PATH_SET",
            "APNS_KEY_PEM_SET",
            "WEB_PUSH_VAPID_PRIVATE_KEY_SET",
            "WEB_PUSH_VAPID_PUBLIC_KEY",
            "UNIFIEDPUSH_ALLOWED_HOSTS",
            "UNIFIEDPUSH_PUBLIC_HOSTS",
            "TURN_URLS",
            "TURN_CREDENTIAL_TTL_SECS",
            "IOS_MIN_VERSION",
            "ANDROID_MIN_VERSION",
        ] {
            set_env(name, None);
        }
    }
}

fn clear_env() {
    configure_env(false);
    set_env("API_INTERNAL_URL", None);
}

/// The overview test is the only code in this process that writes these variables,
/// and it does so while no other test in this binary reads them.
fn set_env(name: &str, value: Option<&str>) {
    match value {
        Some(value) => unsafe { std::env::set_var(name, value) },
        None => unsafe { std::env::remove_var(name) },
    }
}

fn nonempty(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

fn assert_schema(body: &Value) {
    let schema: Value =
        serde_json::from_str(include_str!("../fixtures/schema/overview.schema.json")).unwrap();
    let validator = jsonschema::validator_for(&schema).expect("overview schema");
    let errors: Vec<String> = validator
        .iter_errors(body)
        .map(|err| err.to_string())
        .collect();
    assert!(errors.is_empty(), "{errors:?}\n{body}");
}

struct Reply {
    status: StatusCode,
    headers: axum::http::HeaderMap,
    text: String,
    body: Value,
}

async fn send(app: &axum::Router, session: Option<&str>) -> Reply {
    let mut request = Request::builder().method("GET").uri("/api/admin/overview");
    if let Some(session) = session {
        request = request.header(header::COOKIE, format!("__Host-admin={session}"));
    }
    let response = app
        .clone()
        .oneshot(request.body(Body::empty()).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let headers = response.headers().clone();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let text = String::from_utf8(bytes.to_vec()).unwrap();
    let body = if text.is_empty() {
        Value::Null
    } else {
        serde_json::from_str(&text).unwrap_or(Value::Null)
    };
    Reply {
        status,
        headers,
        text,
        body,
    }
}
