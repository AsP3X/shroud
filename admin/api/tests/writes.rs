//! §3.5 writes against a throwaway Postgres and a stand-in operator listener.
//!
//! `ADMIN_TEST_DATABASE_URL` is `shroud_admin`. `GRANT_TEST_SUPER_URL` owns the API tables and
//! applies `grants.sql`. Without both, the test returns immediately and a green run is not proof.

use std::sync::atomic::{AtomicU16, Ordering};
use std::sync::{Arc, Mutex};

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use http_body_util::BodyExt;
use serde_json::Value;
use sha2::{Digest, Sha256};
use shroud_admin::AppState;
use sqlx::PgPool;
use sqlx::Row;
use sqlx::postgres::PgPoolOptions;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tower::ServiceExt;
use uuid::Uuid;

const WRITER: &str = "00000000-0000-4000-8000-00000000c221";
const READER: &str = "00000000-0000-4000-8000-00000000c222";
const WRITE_COOKIE: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const READ_COOKIE: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const WRITE_CSRF: &str = "csrf-write-token";
const READ_CSRF: &str = "csrf-read-token";
const LIVE: &str = "c2210000-0000-4000-8000-0000000000a1";
const GONE: &str = "c2210000-0000-4000-8000-0000000000a2";
const DEVICE: &str = "c2210000-0000-4000-8000-0000000000d1";
const REVOKED: &str = "c2210000-0000-4000-8000-0000000000d2";
const TOKEN: &str = "operator-token-test";

#[tokio::test]
async fn writes_require_reauth_and_audit_each_attempt() {
    let Some(admin_url) = nonempty("ADMIN_TEST_DATABASE_URL") else {
        eprintln!("ADMIN_TEST_DATABASE_URL unset; writes test not run");
        return;
    };
    let Some(super_url) = nonempty("GRANT_TEST_SUPER_URL") else {
        eprintln!("GRANT_TEST_SUPER_URL unset; writes test not run");
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
    sqlx::raw_sql(include_str!("../grants.sql"))
        .execute(&owner)
        .await
        .expect("column grants");

    let writer = Uuid::parse_str(WRITER).unwrap();
    let reader = Uuid::parse_str(READER).unwrap();
    let live = Uuid::parse_str(LIVE).unwrap();
    let gone = Uuid::parse_str(GONE).unwrap();
    let device = Uuid::parse_str(DEVICE).unwrap();
    let revoked = Uuid::parse_str(REVOKED).unwrap();
    let ids = Ids {
        writer,
        reader,
        live,
        gone,
        device,
        revoked,
    };
    clear(&admin, &owner, &[writer, reader], &[live, gone]).await;
    seed(&admin, &owner, &ids).await;

    let (addr, hits, status) = stand_in().await;
    let _env = EnvGuard::set(&[
        ("API_INTERNAL_URL", Some("http://127.0.0.1:9")),
        ("OPERATOR_PORT", Some(&addr.port().to_string())),
        ("OPERATOR_TOKEN", Some(TOKEN)),
    ]);
    let app = shroud_admin::router_with(None, AppState::connected(admin.clone(), [7u8; 32]));

    let unsigned = call(&app, "POST", &remove_path(), None, None, None).await;
    assert_eq!(unsigned.status, StatusCode::UNAUTHORIZED);
    assert_eq!(unsigned.body, fixture("error.unauthenticated.json"));
    assert!(audits(&admin, writer).await.is_empty());

    let csrf = call(&app, "POST", &remove_path(), Some(WRITE_COOKIE), None, None).await;
    assert_eq!(csrf.status, StatusCode::FORBIDDEN);
    assert_eq!(csrf.body["code"], "CSRF");
    assert!(audits(&admin, writer).await.is_empty());
    assert!(hits.lock().unwrap().is_empty());

    let view = call(
        &app,
        "POST",
        &remove_path(),
        Some(READ_COOKIE),
        Some(READ_CSRF),
        None,
    )
    .await;
    assert_eq!(view.status, StatusCode::FORBIDDEN);
    assert_eq!(view.body, fixture("error.forbidden.json"));
    let row = only_audit(&admin, reader).await;
    assert_eq!(row.action, "device.remove");
    assert_eq!(row.outcome, "refused");
    assert_eq!(row.detail.as_deref(), Some("View only"));
    assert_eq!(row.target_id, Some(device));
    clear_audits(&admin).await;

    set_reauth(&admin, writer, false).await;
    let stale = call(
        &app,
        "POST",
        &remove_path(),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        None,
    )
    .await;
    assert_eq!(stale.status, StatusCode::FORBIDDEN);
    assert_eq!(stale.body, fixture("error.reauth-required.json"));
    assert!(audits(&admin, writer).await.is_empty());
    assert!(hits.lock().unwrap().is_empty());
    set_reauth(&admin, writer, true).await;

    let already = call(
        &app,
        "POST",
        &format!("/api/admin/users/{LIVE}/devices/{REVOKED}/remove"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        None,
    )
    .await;
    assert_eq!(already.status, StatusCode::CONFLICT);
    assert_eq!(already.body, fixture("error.already-done.json"));
    assert!(!already.text.contains(TOKEN));
    let row = only_audit(&admin, writer).await;
    assert_eq!(row.outcome, "refused");
    assert_eq!(row.target_id, Some(revoked));
    assert!(hits.lock().unwrap().is_empty());
    clear_audits(&admin).await;

    status.store(204, Ordering::SeqCst);
    let removed = call(
        &app,
        "POST",
        &remove_path(),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        None,
    )
    .await;
    assert_eq!(removed.status, StatusCode::NO_CONTENT, "{}", removed.text);
    assert!(removed.text.is_empty());
    let hit = only_hit(&hits);
    assert_eq!(hit.method, "POST");
    assert_eq!(hit.path, format!("/operator/devices/{DEVICE}/remove"));
    assert_eq!(hit.authorization, format!("Bearer {TOKEN}"));
    let row = only_audit(&admin, writer).await;
    assert_eq!(row.action, "device.remove");
    assert_eq!(row.target_kind.as_deref(), Some("device"));
    assert_eq!(row.target_id, Some(device));
    assert_eq!(row.outcome, "ok");
    assert!(row.detail.is_none());
    clear_audits(&admin).await;
    hits.lock().unwrap().clear();

    status.store(200, Ordering::SeqCst);
    let signed_out = call(
        &app,
        "POST",
        &format!("/api/admin/users/{LIVE}/sign-out-all"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        None,
    )
    .await;
    assert_eq!(
        signed_out.status,
        StatusCode::NO_CONTENT,
        "{}",
        signed_out.text
    );
    let hit = only_hit(&hits);
    assert_eq!(hit.path, format!("/operator/users/{LIVE}/sign-out-all"));
    let row = only_audit(&admin, writer).await;
    assert_eq!(row.action, "user.sign_out_all");
    assert_eq!(row.target_kind.as_deref(), Some("user"));
    assert_eq!(row.target_id, Some(live));
    assert_eq!(row.outcome, "ok");
    assert_eq!(row.detail.as_deref(), Some("4 sessions"));
    clear_audits(&admin).await;
    hits.lock().unwrap().clear();

    let bad_confirm = call(
        &app,
        "POST",
        &format!("/api/admin/users/{LIVE}/delete"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(r#"{"confirm":"not-the-id"}"#),
    )
    .await;
    assert_eq!(bad_confirm.status, StatusCode::BAD_REQUEST);
    assert_eq!(bad_confirm.body["code"], "VALIDATION_ERROR");
    assert_eq!(
        bad_confirm.body["message"],
        "Type the full account id to confirm."
    );
    let row = only_audit(&admin, writer).await;
    assert_eq!(row.action, "user.delete");
    assert_eq!(row.outcome, "refused");
    assert!(hits.lock().unwrap().is_empty());
    clear_audits(&admin).await;

    status.store(204, Ordering::SeqCst);
    let deleted = call(
        &app,
        "POST",
        &format!("/api/admin/users/{LIVE}/delete"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(&format!(r#"{{"confirm":"{LIVE}"}}"#)),
    )
    .await;
    assert_eq!(deleted.status, StatusCode::NO_CONTENT, "{}", deleted.text);
    assert!(!deleted.text.contains(TOKEN));
    let hit = only_hit(&hits);
    assert_eq!(hit.path, format!("/operator/users/{LIVE}/delete"));
    let row = only_audit(&admin, writer).await;
    assert_eq!(row.action, "user.delete");
    assert_eq!(row.outcome, "ok");
    assert_eq!(row.target_id, Some(live));
    clear_audits(&admin).await;
    hits.lock().unwrap().clear();

    let gone_delete = call(
        &app,
        "POST",
        &format!("/api/admin/users/{GONE}/delete"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(&format!(r#"{{"confirm":"{GONE}"}}"#)),
    )
    .await;
    assert_eq!(gone_delete.status, StatusCode::CONFLICT);
    assert_eq!(gone_delete.body["code"], "ALREADY_DONE");
    assert_eq!(
        gone_delete.body["message"],
        "That account is already deleted."
    );
    assert_eq!(only_audit(&admin, writer).await.outcome, "refused");
    assert!(hits.lock().unwrap().is_empty());
    clear_audits(&admin).await;

    status.store(500, Ordering::SeqCst);
    let refused = call(
        &app,
        "POST",
        &format!("/api/admin/users/{LIVE}/sign-out-all"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        None,
    )
    .await;
    assert_eq!(refused.status, StatusCode::BAD_GATEWAY);
    assert_eq!(refused.body, fixture("error.upstream-api.json"));
    let row = only_audit(&admin, writer).await;
    assert_eq!(row.outcome, "failed");
    assert_eq!(row.action, "user.sign_out_all");
    clear_audits(&admin).await;

    unsafe { std::env::set_var("OPERATOR_PORT", "1") };
    let down = call(
        &app,
        "POST",
        &remove_path(),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        None,
    )
    .await;
    assert_eq!(down.status, StatusCode::BAD_GATEWAY);
    assert_eq!(down.body, fixture("error.upstream-api.json"));
    assert_eq!(only_audit(&admin, writer).await.outcome, "failed");

    clear(&admin, &owner, &[writer, reader], &[live, gone]).await;
}

struct Hit {
    method: String,
    path: String,
    authorization: String,
}

async fn stand_in() -> (std::net::SocketAddr, Arc<Mutex<Vec<Hit>>>, Arc<AtomicU16>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let hits = Arc::new(Mutex::new(Vec::new()));
    let status = Arc::new(AtomicU16::new(204));
    let recorded = Arc::clone(&hits);
    let codes = Arc::clone(&status);
    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                break;
            };
            let mut buf = vec![0u8; 8192];
            let mut got = 0usize;
            loop {
                if got >= buf.len() {
                    break;
                }
                let Ok(n) = socket.read(&mut buf[got..]).await else {
                    break;
                };
                if n == 0 {
                    break;
                }
                got += n;
                if buf[..got].windows(4).any(|window| window == b"\r\n\r\n") {
                    break;
                }
            }
            let text = String::from_utf8_lossy(&buf[..got]);
            let mut lines = text.split("\r\n");
            let mut parts = lines.next().unwrap_or("").split_whitespace();
            let method = parts.next().unwrap_or("").to_owned();
            let path = parts.next().unwrap_or("").to_owned();
            let mut authorization = String::new();
            for line in lines {
                if let Some(value) = line.strip_prefix("Authorization: ") {
                    authorization = value.to_owned();
                }
            }
            recorded.lock().unwrap().push(Hit {
                method,
                path,
                authorization,
            });
            let code = codes.load(Ordering::SeqCst);
            let body = if code == 200 {
                r#"{"detail":"4 sessions"}"#
            } else {
                ""
            };
            let response = format!(
                "HTTP/1.1 {code} X\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = socket.write_all(response.as_bytes()).await;
        }
    });
    (addr, hits, status)
}

fn only_hit(hits: &Mutex<Vec<Hit>>) -> Hit {
    let mut hits = hits.lock().unwrap();
    assert_eq!(
        hits.len(),
        1,
        "{:?}",
        hits.iter().map(|hit| &hit.path).collect::<Vec<_>>()
    );
    hits.pop().unwrap()
}

struct Audit {
    action: String,
    target_kind: Option<String>,
    target_id: Option<Uuid>,
    outcome: String,
    detail: Option<String>,
}

async fn audits(pool: &PgPool, operator: Uuid) -> Vec<Audit> {
    let rows = sqlx::query(
        "SELECT action, target_kind, target_id, outcome, detail
         FROM admin.audit_log WHERE operator_id = $1 ORDER BY at ASC, id ASC",
    )
    .bind(operator)
    .fetch_all(pool)
    .await
    .unwrap();
    rows.into_iter()
        .map(|row| Audit {
            action: row.try_get("action").unwrap(),
            target_kind: row.try_get("target_kind").unwrap(),
            target_id: row.try_get("target_id").unwrap(),
            outcome: row.try_get("outcome").unwrap(),
            detail: row.try_get("detail").unwrap(),
        })
        .collect()
}

async fn only_audit(pool: &PgPool, operator: Uuid) -> Audit {
    let mut rows = audits(pool, operator).await;
    assert_eq!(rows.len(), 1);
    rows.pop().unwrap()
}

async fn clear_audits(pool: &PgPool) {
    sqlx::query("DELETE FROM admin.audit_log")
        .execute(pool)
        .await
        .unwrap();
}

async fn set_reauth(pool: &PgPool, operator: Uuid, fresh: bool) {
    let sql = if fresh {
        "UPDATE admin.operator_sessions SET reauth_until = now() + interval '4 minutes' WHERE operator_id = $1"
    } else {
        "UPDATE admin.operator_sessions SET reauth_until = NULL WHERE operator_id = $1"
    };
    sqlx::query(sql).bind(operator).execute(pool).await.unwrap();
}

async fn clear(admin: &PgPool, owner: &PgPool, operators: &[Uuid], users: &[Uuid]) {
    sqlx::query("DELETE FROM admin.audit_log WHERE operator_id = ANY($1)")
        .bind(operators)
        .execute(admin)
        .await
        .unwrap();
    sqlx::query("DELETE FROM admin.operator_sessions WHERE operator_id = ANY($1)")
        .bind(operators)
        .execute(admin)
        .await
        .unwrap();
    sqlx::query("DELETE FROM admin.operators WHERE id = ANY($1)")
        .bind(operators)
        .execute(admin)
        .await
        .unwrap();
    sqlx::query("DELETE FROM users WHERE id = ANY($1)")
        .bind(users)
        .execute(owner)
        .await
        .unwrap();
}

struct Ids {
    writer: Uuid,
    reader: Uuid,
    live: Uuid,
    gone: Uuid,
    device: Uuid,
    revoked: Uuid,
}

async fn seed(admin: &PgPool, owner: &PgPool, ids: &Ids) {
    sqlx::query(
        "INSERT INTO admin.operators (id, name, role, enabled, password_hash)
         VALUES ($1, 'writes-admin', 'write', true, 'hash'), ($2, 'writes-view', 'read', true, 'hash')",
    )
    .bind(ids.writer)
    .bind(ids.reader)
    .execute(admin)
    .await
    .unwrap();
    insert_session(admin, ids.writer, WRITE_COOKIE, WRITE_CSRF).await;
    insert_session(admin, ids.reader, READ_COOKIE, READ_CSRF).await;
    set_reauth(admin, ids.writer, true).await;
    set_reauth(admin, ids.reader, true).await;

    sqlx::query(
        "INSERT INTO users (id, username_hash, password_hash, share_code, created_at)
         VALUES ($1, $2, 'secret-hash', 'WRITE0001', '2026-10-08 12:00:00+00')",
    )
    .bind(ids.live)
    .bind(vec![11u8; 32])
    .execute(owner)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO users (id, created_at, deleted_at)
         VALUES ($1, '2026-10-01 12:00:00+00', '2026-10-06 12:00:00+00')",
    )
    .bind(ids.gone)
    .execute(owner)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO devices (id, user_id, created_at) VALUES ($1, $2, '2026-10-08 12:00:00+00')",
    )
    .bind(ids.device)
    .bind(ids.live)
    .execute(owner)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO devices (id, user_id, created_at, revoked_at)
         VALUES ($1, $2, '2026-06-01 12:00:00+00', '2026-06-02 12:00:00+00')",
    )
    .bind(ids.revoked)
    .bind(ids.live)
    .execute(owner)
    .await
    .unwrap();
}

async fn insert_session(pool: &PgPool, operator: Uuid, cookie: &str, csrf: &str) {
    let digest = Sha256::digest(cookie.as_bytes());
    let mut id_hash = [0u8; 32];
    id_hash.copy_from_slice(&digest);
    sqlx::query(
        "INSERT INTO admin.operator_sessions (id_hash, operator_id, csrf) VALUES ($1, $2, $3)",
    )
    .bind(id_hash.as_slice())
    .bind(operator)
    .bind(csrf)
    .execute(pool)
    .await
    .unwrap();
}

fn remove_path() -> String {
    format!("/api/admin/users/{LIVE}/devices/{DEVICE}/remove")
}

struct Reply {
    status: StatusCode,
    text: String,
    body: Value,
}

async fn call(
    app: &axum::Router,
    method: &str,
    uri: &str,
    session: Option<&str>,
    csrf: Option<&str>,
    body: Option<&str>,
) -> Reply {
    let mut request = Request::builder().method(method).uri(uri);
    if let Some(session) = session {
        let cookie = match csrf {
            Some(csrf) => format!("__Host-admin={session}; admin_csrf={csrf}"),
            None => format!("__Host-admin={session}"),
        };
        request = request.header(header::COOKIE, cookie);
    }
    if let Some(csrf) = csrf {
        request = request.header("x-admin-csrf", csrf);
    }
    let payload = body.unwrap_or("").to_owned();
    if body.is_some() {
        request = request.header(header::CONTENT_TYPE, "application/json");
    }
    let response = app
        .clone()
        .oneshot(request.body(Body::from(payload)).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let text = String::from_utf8(bytes.to_vec()).unwrap();
    let body = if text.is_empty() {
        Value::Null
    } else {
        serde_json::from_str(&text).unwrap_or(Value::Null)
    };
    Reply { status, text, body }
}

fn fixture(name: &str) -> Value {
    let raw = match name {
        "error.unauthenticated.json" => include_str!("../fixtures/error.unauthenticated.json"),
        "error.forbidden.json" => include_str!("../fixtures/error.forbidden.json"),
        "error.reauth-required.json" => include_str!("../fixtures/error.reauth-required.json"),
        "error.already-done.json" => include_str!("../fixtures/error.already-done.json"),
        "error.upstream-api.json" => include_str!("../fixtures/error.upstream-api.json"),
        _ => panic!("unknown fixture {name}"),
    };
    serde_json::from_str(raw).unwrap()
}

fn nonempty(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

struct EnvGuard {
    saved: Vec<(String, Option<String>)>,
}

impl EnvGuard {
    fn set(pairs: &[(&str, Option<&str>)]) -> Self {
        let saved = pairs
            .iter()
            .map(|(name, value)| {
                let previous = std::env::var(name).ok();
                match value {
                    Some(value) => unsafe { std::env::set_var(name, value) },
                    None => unsafe { std::env::remove_var(name) },
                }
                ((*name).to_owned(), previous)
            })
            .collect();
        Self { saved }
    }
}

impl Drop for EnvGuard {
    fn drop(&mut self) {
        for (name, previous) in &self.saved {
            match previous {
                Some(value) => unsafe { std::env::set_var(name, value) },
                None => unsafe { std::env::remove_var(name) },
            }
        }
    }
}
