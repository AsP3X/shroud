//! `GET /users` and `GET /users/{id}` against a throwaway Postgres.
//!
//! `ADMIN_TEST_DATABASE_URL` is `shroud_admin`. `GRANT_TEST_SUPER_URL` seeds rows the console
//! role cannot insert. Without both, the test returns immediately and a green run is not proof.

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use http_body_util::BodyExt;
use serde_json::Value;
use sha2::{Digest, Sha256};
use shroud_admin::AppState;
use sqlx::PgPool;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

const OPERATOR: &str = "00000000-0000-4000-8000-00000000b112";
const SESSION: &str = "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd";
const USERS: [&str; 4] = [
    "a11ce000-0000-4000-8000-000000000001",
    "a11ce000-0000-4000-8000-000000000002",
    "a11ce000-0000-4000-8000-000000000003",
    "a11ce000-0000-4000-8000-000000000004",
];
const DEVICES: [&str; 4] = [
    "a11ce000-0000-4000-8000-0000000000d1",
    "a11ce000-0000-4000-8000-0000000000d2",
    "a11ce000-0000-4000-8000-0000000000d3",
    "a11ce000-0000-4000-8000-0000000000d4",
];
const SEALED: &[u8] = b"SEALED-DEVICE-NAME-DO-NOT-LEAK!!";
const TOKEN: &str = "apns-token-do-not-leak";
const ENDPOINT: &str = "https://leak.example/endpoint-do-not-leak";
const OBJECT_KEY: &str = "secret/object-key-do-not-leak";

#[tokio::test]
async fn users_list_and_detail_match_the_schema_and_hide_sealed_names() {
    let Some(admin_url) = nonempty("ADMIN_TEST_DATABASE_URL") else {
        eprintln!("ADMIN_TEST_DATABASE_URL unset; users test not run");
        return;
    };
    let Some(super_url) = nonempty("GRANT_TEST_SUPER_URL") else {
        eprintln!("GRANT_TEST_SUPER_URL unset; users test not run");
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
    let users: Vec<Uuid> = USERS
        .iter()
        .map(|id| Uuid::parse_str(id).unwrap())
        .collect();
    clear(&admin, &owner, operator, &users).await;
    seed(&admin, &owner, operator, &users).await;

    let app = shroud_admin::router_with(None, AppState::connected(admin.clone(), [9u8; 32]));
    let unsigned = send(&app, "/api/admin/users", None).await;
    assert_eq!(unsigned.status, StatusCode::UNAUTHORIZED);

    let bad = send(&app, "/api/admin/users?q=nope", Some(SESSION)).await;
    assert_eq!(bad.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        bad.body,
        serde_json::from_str::<Value>(include_str!("../fixtures/error.validation.json")).unwrap()
    );

    let page = send(&app, "/api/admin/users?q=a11ce000&limit=2", Some(SESSION)).await;
    assert_eq!(page.status, StatusCode::OK, "{}", page.text);
    assert_schema("users.schema.json", &page.body);
    assert_private(&page.text);
    assert_eq!(page.body["items"].as_array().unwrap().len(), 2);
    assert_eq!(page.body["items"][0]["id"], USERS[0]);
    assert_eq!(page.body["items"][0]["devices"], 2);
    assert_eq!(page.body["items"][0]["push"], "mixed");
    assert_eq!(page.body["items"][0]["status"], "active");
    assert_eq!(page.body["items"][0]["last_active_on"], "2026-10-08");
    assert_eq!(page.body["items"][1]["id"], USERS[1]);
    assert_eq!(page.body["items"][1]["push"], "unifiedpush");
    let cursor = page.body["next_cursor"].as_str().unwrap();

    let rest = send(
        &app,
        &format!("/api/admin/users?q=a11ce000&limit=2&cursor={cursor}"),
        Some(SESSION),
    )
    .await;
    assert_eq!(rest.status, StatusCode::OK, "{}", rest.text);
    assert_eq!(rest.body["items"][0]["id"], USERS[2]);
    assert_eq!(rest.body["items"][0]["devices"], 0);
    assert_eq!(rest.body["items"][0]["push"], "none");
    assert!(rest.body["items"][0]["last_active_on"].is_null());
    assert_eq!(rest.body["items"][1]["id"], USERS[3]);
    assert_eq!(rest.body["items"][1]["status"], "deleted");
    assert!(rest.body["next_cursor"].is_null());
    assert_eq!(
        rest.body["totals"]["accounts"],
        page.body["totals"]["accounts"]
    );

    let active = send(
        &app,
        "/api/admin/users?q=a11ce000&status=active&limit=10",
        Some(SESSION),
    )
    .await;
    assert_eq!(active.body["items"].as_array().unwrap().len(), 3);
    assert!(
        active.body["items"]
            .as_array()
            .unwrap()
            .iter()
            .all(|item| item["status"] == "active")
    );
    let deleted = send(
        &app,
        "/api/admin/users?q=a11ce000&status=deleted&limit=10",
        Some(SESSION),
    )
    .await;
    assert_eq!(deleted.body["items"].as_array().unwrap().len(), 1);
    assert_eq!(deleted.body["items"][0]["status"], "deleted");
    assert_eq!(deleted.body["totals"], page.body["totals"]);
    assert!(deleted.body["totals"]["accounts"].as_i64().unwrap() > 1);

    let detail = send(
        &app,
        &format!("/api/admin/users/{}", USERS[0]),
        Some(SESSION),
    )
    .await;
    assert_eq!(detail.status, StatusCode::OK, "{}", detail.text);
    assert_schema("user.schema.json", &detail.body);
    assert_private(&detail.text);
    assert_eq!(detail.body["counts"]["contacts"], Value::Null);
    assert_eq!(detail.body["counts"]["blocks"], Value::Null);
    assert_eq!(detail.body["counts"]["conversations"], Value::Null);
    assert_eq!(detail.body["counts"]["media_objects"], 2);
    assert_eq!(detail.body["counts"]["media_bytes"], 150);
    assert_eq!(detail.body["pin_guard"], true);
    assert_eq!(detail.body["devices"].as_array().unwrap().len(), 3);
    assert_eq!(detail.body["devices"][0]["platform"], "ios");
    assert_eq!(detail.body["devices"][0]["push"], "apns+voip");
    assert_eq!(detail.body["devices"][0]["session"], "live");
    assert_eq!(detail.body["devices"][0]["revoked"], false);
    assert_eq!(detail.body["devices"][1]["platform"], "web");
    assert_eq!(detail.body["devices"][1]["push"], "web");
    assert_eq!(detail.body["devices"][1]["session"], "none");
    assert_eq!(detail.body["devices"][2]["revoked"], true);
    assert_eq!(detail.body["devices"][2]["push"], "none");
    assert_eq!(detail.body["devices"][2]["platform"], "unknown");

    let missing = send(
        &app,
        "/api/admin/users/00000000-0000-4000-8000-000000000099",
        Some(SESSION),
    )
    .await;
    assert_eq!(missing.status, StatusCode::NOT_FOUND);
    assert_eq!(missing.body["code"], "NOT_FOUND");

    clear(&admin, &owner, operator, &users).await;
}

fn assert_private(text: &str) {
    assert!(!text.contains("SEALED-DEVICE-NAME-DO-NOT-LEAK"));
    assert!(!text.contains(TOKEN));
    assert!(!text.contains(ENDPOINT));
    assert!(!text.contains(OBJECT_KEY));
    assert!(!text.contains("leak-bucket"));
}

fn assert_schema(name: &str, body: &Value) {
    let raw = match name {
        "users.schema.json" => include_str!("../fixtures/schema/users.schema.json"),
        "user.schema.json" => include_str!("../fixtures/schema/user.schema.json"),
        _ => panic!("unknown schema"),
    };
    let schema: Value = serde_json::from_str(raw).unwrap();
    let validator = jsonschema::validator_for(&schema).unwrap();
    let errors: Vec<String> = validator
        .iter_errors(body)
        .map(|err| err.to_string())
        .collect();
    assert!(errors.is_empty(), "{errors:?}\n{body}");
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
    sqlx::query("DELETE FROM users WHERE id = ANY($1)")
        .bind(users)
        .execute(owner)
        .await
        .unwrap();
}

async fn seed(admin: &PgPool, owner: &PgPool, operator: Uuid, users: &[Uuid]) {
    sqlx::query(
        "INSERT INTO admin.operators (id, name, role, enabled) VALUES ($1, 'users-probe', 'read', true)",
    )
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

    for (index, id) in users.iter().enumerate() {
        let created = format!("2026-10-0{} 12:00:00+0000", 8 - index);
        if index == 3 {
            sqlx::query("INSERT INTO users (id, created_at, deleted_at) VALUES ($1, $2, $3)")
                .bind(id)
                .bind(sql_time(&created))
                .bind(sql_time("2026-10-06 12:00:00+0000"))
                .execute(owner)
                .await
                .unwrap();
        } else {
            sqlx::query(
                "INSERT INTO users (id, username_hash, password_hash, share_code, created_at)
                 VALUES ($1, $2, 'overview-secret-hash', $3, $4)",
            )
            .bind(id)
            .bind(vec![10 + index as u8; 32])
            .bind(format!("USERS{:04}", index + 1))
            .bind(sql_time(&created))
            .execute(owner)
            .await
            .unwrap();
        }
    }

    let devices: Vec<Uuid> = DEVICES
        .iter()
        .map(|id| Uuid::parse_str(id).unwrap())
        .collect();
    insert_device(
        owner,
        devices[0],
        users[0],
        "2026-03-12 00:00:00+0000",
        Some("2026-10-08 08:00:00+0000"),
        None,
        Some(SEALED),
    )
    .await;
    insert_device(
        owner,
        devices[1],
        users[0],
        "2026-03-20 00:00:00+0000",
        Some("2026-10-07 08:00:00+0000"),
        None,
        None,
    )
    .await;
    insert_device(
        owner,
        devices[2],
        users[0],
        "2026-05-01 00:00:00+0000",
        Some("2026-06-02 08:00:00+0000"),
        Some("2026-06-02 09:00:00+0000"),
        None,
    )
    .await;
    insert_device(
        owner,
        devices[3],
        users[1],
        "2026-04-01 00:00:00+0000",
        Some("2026-10-07 01:00:00+0000"),
        None,
        None,
    )
    .await;

    for kind in ["alert", "voip"] {
        sqlx::query(
            "INSERT INTO push_tokens (device_id, apns_token, environment, kind) VALUES ($1, $2, 'sandbox', $3)",
        )
        .bind(devices[0])
        .bind(format!("{TOKEN}-{kind}"))
        .bind(kind)
        .execute(owner)
        .await
        .unwrap();
    }
    sqlx::query(
        "INSERT INTO web_push_subscriptions (device_id, endpoint, p256dh, auth, client)
         VALUES ($1, $2, $3, $4, 'browser')",
    )
    .bind(devices[1])
    .bind(ENDPOINT)
    .bind(vec![1u8; 65])
    .bind(vec![2u8; 16])
    .execute(owner)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO web_push_subscriptions (device_id, endpoint, p256dh, auth, client)
         VALUES ($1, $2, $3, $4, 'android')",
    )
    .bind(devices[3])
    .bind(format!("{ENDPOINT}-android"))
    .bind(vec![3u8; 65])
    .bind(vec![4u8; 16])
    .execute(owner)
    .await
    .unwrap();
    sqlx::query("INSERT INTO sessions (device_id, token_hash) VALUES ($1, $2)")
        .bind(devices[0])
        .bind(vec![9u8; 32])
        .execute(owner)
        .await
        .unwrap();
    sqlx::query("INSERT INTO device_pin_guards (device_id, verifier, pepper) VALUES ($1, $2, $3)")
        .bind(devices[1])
        .bind(vec![5u8; 32])
        .bind(vec![6u8; 32])
        .execute(owner)
        .await
        .unwrap();
    for (key, size) in [(OBJECT_KEY, 100i64), ("secret/other-object", 50i64)] {
        sqlx::query(
            "INSERT INTO media_objects (uploader_user_id, uploader_device_id, bucket, object_key, size_bytes)
             VALUES ($1, $2, 'leak-bucket', $3, $4)",
        )
        .bind(users[0])
        .bind(devices[0])
        .bind(key)
        .bind(size)
        .execute(owner)
        .await
        .unwrap();
    }
}

async fn insert_device(
    owner: &PgPool,
    id: Uuid,
    user: Uuid,
    created: &str,
    seen: Option<&str>,
    revoked: Option<&str>,
    sealed: Option<&[u8]>,
) {
    sqlx::query(
        "INSERT INTO devices (id, user_id, sealed_name, created_at, last_seen_at, revoked_at)
         VALUES ($1, $2, $3, $4, $5, $6)",
    )
    .bind(id)
    .bind(user)
    .bind(sealed)
    .bind(sql_time(created))
    .bind(seen.map(sql_time))
    .bind(revoked.map(sql_time))
    .execute(owner)
    .await
    .unwrap();
}

fn sql_time(text: &str) -> chrono::DateTime<chrono::Utc> {
    chrono::DateTime::parse_from_str(text, "%Y-%m-%d %H:%M:%S%z")
        .unwrap()
        .with_timezone(&chrono::Utc)
}

fn nonempty(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

struct Reply {
    status: StatusCode,
    text: String,
    body: Value,
}

async fn send(app: &axum::Router, uri: &str, session: Option<&str>) -> Reply {
    let mut request = Request::builder().method("GET").uri(uri);
    if let Some(session) = session {
        request = request.header(header::COOKIE, format!("__Host-admin={session}"));
    }
    let response = app
        .clone()
        .oneshot(request.body(Body::empty()).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let text = String::from_utf8(bytes.to_vec()).unwrap();
    let body = serde_json::from_str(&text).unwrap_or(Value::Null);
    Reply { status, text, body }
}
