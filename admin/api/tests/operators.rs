//! §3.6 operators against a throwaway Postgres.
//!
//! `ADMIN_TEST_DATABASE_URL` is `shroud_admin`. Without it the test returns immediately and a
//! green run is not proof. Server migrations are not required: these tables are schema `admin`.

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use http_body_util::BodyExt;
use serde_json::Value;
use sha2::{Digest, Sha256};
use shroud_admin::AppState;
use sqlx::PgPool;
use sqlx::Row;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

const WRITER: &str = "00000000-0000-4000-8000-00000000c231";
const READER: &str = "00000000-0000-4000-8000-00000000c232";
const WRITE_COOKIE: &str = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
const READ_COOKIE: &str = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
const WRITE_CSRF: &str = "csrf-ops-write";
const READ_CSRF: &str = "csrf-ops-read";
const NAMES: &[&str] = &["ops-admin", "ops-view", "ops2"];

#[tokio::test]
async fn operators_list_invites_and_end_sessions() {
    let Some(admin_url) = nonempty("ADMIN_TEST_DATABASE_URL") else {
        eprintln!("ADMIN_TEST_DATABASE_URL unset; operators test not run");
        return;
    };
    let admin = PgPoolOptions::new()
        .connect(&admin_url)
        .await
        .expect("shroud_admin connection");
    shroud_admin::db::migrate(&admin)
        .await
        .expect("admin migrations");
    clear(&admin).await;
    let writer = Uuid::parse_str(WRITER).unwrap();
    let reader = Uuid::parse_str(READER).unwrap();
    seed(&admin, writer, reader).await;
    let app = shroud_admin::router_with(None, AppState::connected(admin.clone(), [5u8; 32]));

    let unsigned = call(&app, "GET", "/api/admin/operators", None, None, None).await;
    assert_eq!(unsigned.status, StatusCode::UNAUTHORIZED);
    assert_eq!(unsigned.body, fixture("error.unauthenticated.json"));

    let listed = call(
        &app,
        "GET",
        "/api/admin/operators",
        Some(READ_COOKIE),
        None,
        None,
    )
    .await;
    assert_eq!(listed.status, StatusCode::OK, "{}", listed.text);
    assert_schema("operators.schema.json", &listed.body);
    assert!(!listed.text.contains("do-not-leak-hash"));
    assert_eq!(listed.body.as_array().unwrap().len(), 2);
    assert_eq!(listed.body[0]["name"], "ops-admin");
    assert_eq!(listed.body[0]["role"], "write");
    assert_eq!(listed.body[0]["totp_enrolled"], true);
    assert_eq!(listed.body[0]["last_sign_in_at"], "2026-10-08T14:02:11Z");
    assert_eq!(listed.body[1]["name"], "ops-view");
    assert_eq!(listed.body[1]["role"], "read");
    assert_eq!(listed.body[1]["totp_enrolled"], false);
    assert!(listed.body[1]["last_sign_in_at"].is_null());

    let forbidden = call(
        &app,
        "POST",
        "/api/admin/operators",
        Some(READ_COOKIE),
        Some(READ_CSRF),
        Some(r#"{"name":"ops2","role":"read"}"#),
    )
    .await;
    assert_eq!(forbidden.status, StatusCode::FORBIDDEN);
    assert_eq!(forbidden.body, fixture("error.forbidden.json"));
    assert_eq!(only_audit(&admin, reader).await.outcome, "refused");
    clear_audits(&admin, &[reader]).await;

    set_reauth(&admin, writer, false).await;
    let stale = call(
        &app,
        "POST",
        "/api/admin/operators",
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(r#"{"name":"ops2","role":"read"}"#),
    )
    .await;
    assert_eq!(stale.status, StatusCode::FORBIDDEN);
    assert_eq!(stale.body, fixture("error.reauth-required.json"));
    assert!(audits(&admin, writer).await.is_empty());
    set_reauth(&admin, writer, true).await;

    let bad_name = call(
        &app,
        "POST",
        "/api/admin/operators",
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(r#"{"name":"x","role":"read"}"#),
    )
    .await;
    assert_eq!(bad_name.status, StatusCode::BAD_REQUEST);
    assert_eq!(bad_name.body["code"], "VALIDATION_ERROR");
    assert_eq!(only_audit(&admin, writer).await.outcome, "refused");
    clear_audits(&admin, &[writer]).await;

    let created = call(
        &app,
        "POST",
        "/api/admin/operators",
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(r#"{"name":"ops2","role":"read"}"#),
    )
    .await;
    assert_eq!(created.status, StatusCode::OK, "{}", created.text);
    assert_schema("setup-url.schema.json", &created.body);
    let setup_url = created.body["setup_url"].as_str().unwrap();
    assert!(setup_url.starts_with("/setup/"));
    assert!(!setup_url.starts_with("http"));
    let token = setup_url.trim_start_matches("/setup/");
    let page = call(
        &app,
        "GET",
        &format!("/api/admin/setup/{token}"),
        None,
        None,
        None,
    )
    .await;
    assert_eq!(page.status, StatusCode::OK, "{}", page.text);
    assert_eq!(page.body["operator_name"], "ops2");
    let fresh = within_fifteen_minutes(&admin, "ops2").await;
    assert!(fresh);
    let row = only_audit(&admin, writer).await;
    assert_eq!(row.action, "operator.create");
    assert_eq!(row.outcome, "ok");
    assert_eq!(row.target_kind.as_deref(), Some("operator"));
    clear_audits(&admin, &[writer]).await;

    let again = call(
        &app,
        "POST",
        "/api/admin/operators",
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(r#"{"name":"ops2","role":"write"}"#),
    )
    .await;
    assert_eq!(again.status, StatusCode::BAD_REQUEST);
    assert_eq!(again.body["message"], "An operator already has that name.");
    clear_audits(&admin, &[writer]).await;

    let ops2 = operator_id(&admin, "ops2").await;
    let promoted = call(
        &app,
        "PATCH",
        &format!("/api/admin/operators/{ops2}"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(r#"{"role":"write"}"#),
    )
    .await;
    assert_eq!(promoted.status, StatusCode::NO_CONTENT, "{}", promoted.text);
    assert_eq!(role_of(&admin, ops2).await, "write");
    let row = only_audit(&admin, writer).await;
    assert_eq!(row.action, "operator.update");
    assert_eq!(row.detail.as_deref(), Some("role write"));
    assert_eq!(row.target_id, Some(ops2));
    clear_audits(&admin, &[writer]).await;

    let demote = call(
        &app,
        "PATCH",
        &format!("/api/admin/operators/{writer}"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(r#"{"role":"read"}"#),
    )
    .await;
    assert_eq!(demote.status, StatusCode::FORBIDDEN);
    assert_eq!(demote.body, fixture("error.forbidden.json"));
    assert_eq!(role_of(&admin, writer).await, "write");
    assert_eq!(
        only_audit(&admin, writer).await.detail.as_deref(),
        Some("Not yourself")
    );
    clear_audits(&admin, &[writer]).await;

    insert_session(&admin, ops2, &"e".repeat(64), "csrf-ops2").await;
    let disabled = call(
        &app,
        "PATCH",
        &format!("/api/admin/operators/{ops2}"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(r#"{"enabled":false}"#),
    )
    .await;
    assert_eq!(disabled.status, StatusCode::NO_CONTENT, "{}", disabled.text);
    assert!(!enabled(&admin, ops2).await);
    assert_eq!(session_count(&admin, ops2).await, 0);
    assert_eq!(session_count(&admin, writer).await, 1);
    assert_eq!(
        only_audit(&admin, writer).await.detail.as_deref(),
        Some("disabled")
    );
    clear_audits(&admin, &[writer]).await;

    let self_disable = call(
        &app,
        "PATCH",
        &format!("/api/admin/operators/{writer}"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(r#"{"enabled":false}"#),
    )
    .await;
    assert_eq!(self_disable.status, StatusCode::FORBIDDEN);
    assert!(enabled(&admin, writer).await);
    assert_eq!(session_count(&admin, writer).await, 1);
    clear_audits(&admin, &[writer]).await;

    sqlx::query("UPDATE admin.operators SET password_hash = 'do-not-leak-hash' WHERE id = $1")
        .bind(ops2)
        .execute(&admin)
        .await
        .unwrap();
    let reset = call(
        &app,
        "POST",
        &format!("/api/admin/operators/{ops2}/reset-totp"),
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        None,
    )
    .await;
    assert_eq!(reset.status, StatusCode::OK, "{}", reset.text);
    assert_schema("setup-url.schema.json", &reset.body);
    let new_url = reset.body["setup_url"].as_str().unwrap();
    assert!(new_url.starts_with("/setup/"));
    assert_ne!(new_url, setup_url);
    let password: Option<String> =
        sqlx::query_scalar("SELECT password_hash FROM admin.operators WHERE id = $1")
            .bind(ops2)
            .fetch_one(&admin)
            .await
            .unwrap();
    assert!(password.is_none());
    assert_eq!(session_count(&admin, ops2).await, 0);
    let used = call(
        &app,
        "GET",
        &format!("/api/admin/setup/{token}"),
        None,
        None,
        None,
    )
    .await;
    assert_eq!(used.status, StatusCode::GONE);
    assert_eq!(used.body["code"], "LINK_USED");
    let new_token = new_url.trim_start_matches("/setup/");
    let renewed = call(
        &app,
        "GET",
        &format!("/api/admin/setup/{new_token}"),
        None,
        None,
        None,
    )
    .await;
    assert_eq!(renewed.status, StatusCode::OK, "{}", renewed.text);
    assert_eq!(renewed.body["operator_name"], "ops2");
    let row = only_audit(&admin, writer).await;
    assert_eq!(row.action, "operator.reset_totp");
    assert_eq!(row.outcome, "ok");
    assert_eq!(row.target_id, Some(ops2));

    let missing = call(
        &app,
        "PATCH",
        "/api/admin/operators/00000000-0000-4000-8000-000000000099",
        Some(WRITE_COOKIE),
        Some(WRITE_CSRF),
        Some(r#"{"enabled":true}"#),
    )
    .await;
    assert_eq!(missing.status, StatusCode::NOT_FOUND);
    assert_eq!(missing.body["message"], "No operator has that id.");

    clear(&admin).await;
}

async fn within_fifteen_minutes(pool: &PgPool, name: &str) -> bool {
    sqlx::query_scalar(
        "SELECT EXISTS (
            SELECT 1 FROM admin.setup_links l
            JOIN admin.operators o ON o.id = l.operator_id
            WHERE o.name = $1 AND l.used_at IS NULL
              AND l.expires_at > now() + interval '14 minutes'
              AND l.expires_at <= now() + interval '16 minutes'
         )",
    )
    .bind(name)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn operator_id(pool: &PgPool, name: &str) -> Uuid {
    sqlx::query_scalar("SELECT id FROM admin.operators WHERE name = $1")
        .bind(name)
        .fetch_one(pool)
        .await
        .unwrap()
}

async fn role_of(pool: &PgPool, id: Uuid) -> String {
    sqlx::query_scalar("SELECT role FROM admin.operators WHERE id = $1")
        .bind(id)
        .fetch_one(pool)
        .await
        .unwrap()
}

async fn enabled(pool: &PgPool, id: Uuid) -> bool {
    sqlx::query_scalar("SELECT enabled FROM admin.operators WHERE id = $1")
        .bind(id)
        .fetch_one(pool)
        .await
        .unwrap()
}

async fn session_count(pool: &PgPool, id: Uuid) -> i64 {
    sqlx::query_scalar("SELECT count(id_hash) FROM admin.operator_sessions WHERE operator_id = $1")
        .bind(id)
        .fetch_one(pool)
        .await
        .unwrap()
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
    assert_eq!(
        rows.len(),
        1,
        "{:?}",
        rows.iter().map(|row| &row.action).collect::<Vec<_>>()
    );
    rows.pop().unwrap()
}

async fn clear_audits(pool: &PgPool, operators: &[Uuid]) {
    sqlx::query("DELETE FROM admin.audit_log WHERE operator_id = ANY($1)")
        .bind(operators)
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

async fn seed(pool: &PgPool, writer: Uuid, reader: Uuid) {
    sqlx::query(
        "INSERT INTO admin.operators (id, name, role, enabled, password_hash, last_sign_in_at)
         VALUES ($1, 'ops-admin', 'write', true, 'do-not-leak-hash', '2026-10-08 14:02:11+00')",
    )
    .bind(writer)
    .execute(pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO admin.operators (id, name, role, enabled) VALUES ($1, 'ops-view', 'read', true)",
    )
    .bind(reader)
    .execute(pool)
    .await
    .unwrap();
    insert_session(pool, writer, WRITE_COOKIE, WRITE_CSRF).await;
    insert_session(pool, reader, READ_COOKIE, READ_CSRF).await;
    set_reauth(pool, writer, true).await;
    set_reauth(pool, reader, true).await;
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

async fn clear(pool: &PgPool) {
    let ids: Vec<Uuid> = sqlx::query_scalar("SELECT id FROM admin.operators WHERE name = ANY($1)")
        .bind(NAMES)
        .fetch_all(pool)
        .await
        .unwrap();
    if ids.is_empty() {
        return;
    }
    sqlx::query("DELETE FROM admin.audit_log WHERE operator_id = ANY($1) OR target_id = ANY($1)")
        .bind(&ids)
        .execute(pool)
        .await
        .unwrap();
    sqlx::query("DELETE FROM admin.operator_sessions WHERE operator_id = ANY($1)")
        .bind(&ids)
        .execute(pool)
        .await
        .unwrap();
    sqlx::query("DELETE FROM admin.setup_links WHERE operator_id = ANY($1)")
        .bind(&ids)
        .execute(pool)
        .await
        .unwrap();
    sqlx::query("DELETE FROM admin.recovery_codes WHERE operator_id = ANY($1)")
        .bind(&ids)
        .execute(pool)
        .await
        .unwrap();
    sqlx::query("DELETE FROM admin.operators WHERE id = ANY($1)")
        .bind(&ids)
        .execute(pool)
        .await
        .unwrap();
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
        _ => panic!("unknown fixture {name}"),
    };
    serde_json::from_str(raw).unwrap()
}

fn assert_schema(name: &str, body: &Value) {
    let raw = match name {
        "operators.schema.json" => include_str!("../fixtures/schema/operators.schema.json"),
        "setup-url.schema.json" => include_str!("../fixtures/schema/setup-url.schema.json"),
        _ => panic!("unknown schema {name}"),
    };
    let schema: Value = serde_json::from_str(raw).unwrap();
    let validator = jsonschema::validator_for(&schema).unwrap();
    let errors: Vec<String> = validator
        .iter_errors(body)
        .map(|err| err.to_string())
        .collect();
    assert!(errors.is_empty(), "{errors:?}\n{body}");
}

fn nonempty(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}
