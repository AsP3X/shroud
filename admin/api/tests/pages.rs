//! Read-only pages against a throwaway Postgres and a stand-in API.
//!
//! `ADMIN_TEST_DATABASE_URL` is `shroud_admin`. `GRANT_TEST_SUPER_URL` seeds rows the console
//! role cannot insert. Without both, the test returns immediately and a green run is not proof.

use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

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

const OPERATOR: &str = "00000000-0000-4000-8000-00000000b114";
const SESSION: &str = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
const USER: &str = "b1110000-0000-4000-8000-0000000000a1";
const DEVICE: &str = "b1110000-0000-4000-8000-0000000000d1";
const OTHER: &str = "b1110000-0000-4000-8000-0000000000ff";
const SEALED: &[u8] = b"SEALED-DEVICE-NAME-DO-NOT-LEAK!!";
const TOKEN: &str = "apns-token-do-not-leak";
const LEAKS: [&str; 8] = [
    "SEALED-DEVICE-NAME-DO-NOT-LEAK!!",
    "operator-test-token",
    "apns-token-do-not-leak",
    "redis-do-not-leak",
    "turn-secret-do-not-leak",
    "pem-do-not-leak",
    "vapid-do-not-leak",
    "nebular-do-not-leak",
];

#[tokio::test]
async fn read_only_pages_match_the_schema() {
    let Some(admin_url) = std::env::var("ADMIN_TEST_DATABASE_URL")
        .ok()
        .filter(|value| !value.is_empty())
    else {
        eprintln!("ADMIN_TEST_DATABASE_URL unset; pages test not run");
        return;
    };
    let Some(super_url) = std::env::var("GRANT_TEST_SUPER_URL")
        .ok()
        .filter(|value| !value.is_empty())
    else {
        eprintln!("GRANT_TEST_SUPER_URL unset; pages test not run");
        return;
    };

    let unsigned = shroud_admin::router_with(None, AppState::disconnected());
    let missing = send(&unsigned, "/api/admin/rate-limits", None).await;
    assert_eq!(missing.status, StatusCode::UNAUTHORIZED);
    assert_eq!(missing.body, fixture("error.unauthenticated.json"));

    let down = PgPoolOptions::new()
        .acquire_timeout(Duration::from_secs(2))
        .connect_lazy("postgres://shroud:shroud@127.0.0.1:1/shroud")
        .expect("lazy pool");
    let down = shroud_admin::router_with(None, AppState::connected(down, [7u8; 32]));
    let stopped = send(&down, "/api/admin/rate-limits", Some(SESSION)).await;
    assert_eq!(stopped.status, StatusCode::BAD_GATEWAY);
    assert_eq!(stopped.body, fixture("error.upstream-postgres.json"));

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
    sqlx::raw_sql(include_str!("../grants.sql"))
        .execute(&owner)
        .await
        .expect("column grants");
    let operator = Uuid::parse_str(OPERATOR).unwrap();
    let user = Uuid::parse_str(USER).unwrap();
    clear(&admin, &owner, operator, user).await;
    seed(&admin, &owner, operator, user).await;

    let mode = Arc::new(AtomicU8::new(0));
    let web_build = Arc::new(Mutex::new(Some("e3b0c44298fc".to_owned())));
    let base = spawn_api(Arc::clone(&mode), Arc::clone(&web_build)).await;
    let build_dir = std::env::temp_dir().join(format!("shroud-admin-pages-{}", std::process::id()));
    std::fs::create_dir_all(&build_dir).unwrap();
    let build_file = build_dir.join("web-build");
    std::fs::write(&build_file, "e3b0c44298fc\n").unwrap();

    let _env = EnvGuard::set(&[
        ("API_INTERNAL_URL", Some(base.as_str())),
        ("OPERATOR_PORT", Some(base.rsplit(':').next().unwrap())),
        ("OPERATOR_TOKEN", Some("operator-test-token")),
        ("HOST", Some("0.0.0.0")),
        ("PORT", Some("8082")),
        ("WEB_PUBLIC_URL", Some("https://chat.example.org")),
        ("CORS_ALLOWED_ORIGINS", Some("https://chat.example.org")),
        ("TRUST_FORWARDED_HEADERS", Some("true")),
        ("DATABASE_URL", Some("postgres://shroud@db:5432/shroud")),
        ("DATABASE_POOL_MAX", Some("10")),
        ("RUN_MIGRATIONS", Some("true")),
        ("REDIS_URL", Some("redis://redis:6379")),
        ("REDIS_PASSWORD_SET", Some("true")),
        ("REDIS_PASSWORD", Some("redis-do-not-leak")),
        ("NEBULAR_URL", Some("https://s3.nebular.example")),
        ("NEBULAR_MEDIA_BUCKET", Some("shroud-media")),
        ("NEBULAR_SECRET_ACCESS_KEY", Some("nebular-do-not-leak")),
        ("NEBULAR_SECRET_ACCESS_KEY_SET", Some("true")),
        ("MEDIA_DATA_DIR", Some("/data/shroud-media")),
        (
            "TURN_URLS",
            Some(
                "turn:turn.example.org:3478?transport=udp,turn:turn.example.org:3478?transport=tcp",
            ),
        ),
        ("TURN_SECRET", Some("turn-secret-do-not-leak")),
        ("TURN_SECRET_SET", Some("true")),
        ("TURN_CREDENTIAL_TTL_SECS", Some("43200")),
        ("ICE_SERVERS_JSON", None),
        ("APNS_TOPIC", Some("de.corespace.shroud")),
        ("APNS_KEY_PEM", Some("pem-do-not-leak")),
        ("APNS_KEY_PEM_SET", Some("true")),
        ("WEB_PUSH_SUBJECT", Some("mailto:ops@example.org")),
        ("WEB_PUSH_VAPID_PRIVATE_KEY", Some("vapid-do-not-leak")),
        ("WEB_PUSH_VAPID_PRIVATE_KEY_SET", Some("true")),
        ("UNIFIEDPUSH_ALLOWED_HOSTS", Some("ntfy.sh, up.example.org")),
        ("IOS_LATEST_VERSION", Some("1.1")),
        ("IOS_MIN_VERSION", Some("1.1")),
        (
            "IOS_UPDATE_URL",
            Some("https://testflight.apple.com/join/example"),
        ),
        ("ANDROID_LATEST_VERSION", Some("0.2.0")),
        ("ANDROID_MIN_VERSION", None),
        (
            "ANDROID_UPDATE_URL",
            Some("https://chat.example.org/shroud.apk"),
        ),
        ("WEB_BUILD", None),
        ("WEB_BUILD_FILE", Some(build_file.to_str().unwrap())),
        ("REACTIONS_MAX_PER_USER", Some("5")),
        ("RUST_LOG", Some("info")),
    ]);

    let app = shroud_admin::router_with(None, AppState::connected(admin.clone(), [7u8; 32]));

    let limits = send(&app, "/api/admin/rate-limits", Some(SESSION)).await;
    assert_eq!(limits.status, StatusCode::OK, "{}", limits.text);
    assert_eq!(limits.body, fixture("rate-limits.json"));
    assert_schema("rate-limits.schema.json", &limits.body);

    let retention = send(&app, "/api/admin/retention", Some(SESSION)).await;
    assert_eq!(retention.status, StatusCode::OK, "{}", retention.text);
    assert_eq!(retention.body, fixture("retention.json"));
    assert_schema("retention.schema.json", &retention.body);

    let storage = send(&app, "/api/admin/storage", Some(SESSION)).await;
    assert_eq!(storage.status, StatusCode::OK, "{}", storage.text);
    assert_schema("storage.schema.json", &storage.body);
    assert_eq!(storage.body["backend"], "nebular");
    assert_eq!(storage.body["bucket"], "shroud-media");
    assert_eq!(storage.body["data_dir"], "/data/shroud-media");
    assert_eq!(storage.body["legacy_reads_total"], 3);
    assert_eq!(storage.body["migrated_total"], 9);
    assert_eq!(storage.body["max_object_bytes"], 2_147_483_648_i64);
    let counted = storage_sql(&admin).await;
    assert_eq!(storage.body["objects"], counted.0);
    assert_eq!(storage.body["bytes"], counted.1);
    assert_eq!(storage.body["unlinked_objects"], counted.2);
    assert_private(&storage.text);

    let versions = send(&app, "/api/admin/client-versions", Some(SESSION)).await;
    assert_eq!(versions.status, StatusCode::OK, "{}", versions.text);
    assert_schema("client-versions.schema.json", &versions.body);
    let sample = fixture("client-versions.json");
    assert_eq!(versions.body["server_version"], "0.1.0");
    assert_eq!(versions.body["ios"], sample["ios"]);
    assert_eq!(versions.body["android"], sample["android"]);
    assert_eq!(versions.body["web"]["deployed_build"], "e3b0c44298fc");
    assert!(versions.body["web"]["fixed_build"].is_null());
    assert_eq!(versions.body["told"], sample["told"]);

    let push = send(&app, "/api/admin/push", Some(SESSION)).await;
    assert_eq!(push.status, StatusCode::OK, "{}", push.text);
    assert_schema("push.schema.json", &push.body);
    let check = send(&app, "/api/admin/push/check", Some(SESSION)).await;
    assert_eq!(check.status, StatusCode::OK, "{}", check.text);
    assert_schema("push-check.schema.json", &check.body);
    assert_eq!(check.body, fixture("push-check.json"));

    let pushes = push_sql(&admin).await;
    assert_eq!(push.body["apns_tokens"], pushes.0);
    assert_eq!(push.body["apns_voip_tokens"], pushes.1);
    assert_eq!(push.body["web_push_subscriptions"], pushes.2);
    assert_eq!(push.body["unifiedpush_subscriptions"], pushes.3);
    assert_eq!(push.body["notifications_off"], pushes.4);
    assert_eq!(
        push.body["channels"][0]["sent_to"],
        "api.push.apple.com · topic de.corespace.shroud"
    );
    assert_eq!(
        push.body["channels"][1]["sent_to"],
        "api.push.apple.com · topic de.corespace.shroud.voip · a second token"
    );
    assert_eq!(
        push.body["channels"][3]["sent_to"],
        "The device's distributor · hosts limited to ntfy.sh, up.example.org"
    );
    assert_private(&push.text);

    let retention_check = send(&app, "/api/admin/retention/check", Some(SESSION)).await;
    assert_eq!(
        retention_check.status,
        StatusCode::OK,
        "{}",
        retention_check.text
    );
    assert_schema("retention-check.schema.json", &retention_check.body);
    assert_eq!(retention_check.body, fixture("retention-check.json"));

    let call_check = send(&app, "/api/admin/calls/check", Some(SESSION)).await;
    assert_eq!(call_check.status, StatusCode::OK, "{}", call_check.text);
    assert_schema("calls-check.schema.json", &call_check.body);
    assert_eq!(call_check.body, fixture("calls-check.json"));

    let calls = send(&app, "/api/admin/calls", Some(SESSION)).await;
    assert_eq!(calls.status, StatusCode::OK, "{}", calls.text);
    assert_schema("calls.schema.json", &calls.body);
    assert_eq!(calls.body["created_total"], 5);
    assert_eq!(
        calls.body["ice_servers"],
        serde_json::json!([
            {"urls": "stun:turn.example.org:3478"},
            {"urls": "turn:turn.example.org:3478?transport=udp"},
            {"urls": "turn:turn.example.org:3478?transport=tcp"}
        ])
    );
    assert_eq!(calls.body["turn"]["credential_ttl_secs"], 43200);
    assert_eq!(calls.body["gc"]["ringing_timeout_secs"], 60);
    assert_eq!(calls.body["gc"]["participant_timeout_secs"], 45);
    assert_eq!(calls.body["gc"]["sweep_secs"], 10);
    assert_private(&calls.text);

    let privacy = send(&app, "/api/admin/privacy-checks", Some(SESSION)).await;
    assert_eq!(privacy.status, StatusCode::OK, "{}", privacy.text);
    assert_schema("privacy-checks.schema.json", &privacy.body);
    let accounts: i64 = sqlx::query_scalar("SELECT count(id) FROM users")
        .fetch_one(&admin)
        .await
        .unwrap();
    let hash = find(&privacy.body, "Username hashes are quick to guess");
    assert_eq!(hash["state"], "stored");
    assert_eq!(
        hash["detail"],
        format!(
            "Plain SHA-256. {} accounts still need the slow hash.",
            grouped(accounts)
        )
    );
    assert_eq!(
        find(&privacy.body, "Usernames are not stored")["state"],
        "hashed"
    );
    assert_eq!(find(&privacy.body, "Message text")["state"], "sealed");
    assert_eq!(
        find(&privacy.body, "Photos, video, voice")["state"],
        "sealed"
    );
    assert_eq!(find(&privacy.body, "Device names")["state"], "sealed");
    assert_eq!(find(&privacy.body, "Usernames")["state"], "sealed");
    assert_eq!(
        find(&privacy.body, "Addresses while connected")["detail"],
        "Known to this server."
    );
    assert_eq!(
        find(
            &privacy.body,
            "Redis needs a password, keeps nothing on disk"
        )["detail"],
        "Password set, and nothing is kept on disk."
    );
    assert_eq!(
        find(&privacy.body, "No Google STUN fallback")["state"],
        "not_stored"
    );
    // RUST_LOG=info, and the stand-in API has no public /metrics.
    assert_eq!(
        find(&privacy.body, "The API log doesn't name who messages whom")["state"],
        "not_stored"
    );
    assert_eq!(
        find(&privacy.body, "Activity counters aren't public")["state"],
        "not_stored"
    );
    // The seeded iPhone registered no payload key, so its pushes still carry readable ids.
    let unsealed: i64 =
        sqlx::query_scalar("SELECT count(device_id) FROM admin_unsealed_push_devices")
            .fetch_one(&admin)
            .await
            .unwrap();
    assert!(unsealed >= 1);
    let pushes = find(&privacy.body, "Apple doesn't see chat IDs in pushes");
    assert_eq!(pushes["state"], "stored");
    assert_eq!(
        pushes["detail"],
        format!(
            "{} iPhones on older builds still get chat, sender and message IDs in plain text.",
            grouped(unsealed)
        )
    );
    assert_private(&privacy.text);

    // The stand-in publishes a strong username KDF, so the same page drops the fast-hash warning.
    mode.store(2, Ordering::Relaxed);
    let slow = send(&app, "/api/admin/privacy-checks", Some(SESSION)).await;
    assert_eq!(slow.status, StatusCode::OK, "{}", slow.text);
    assert_schema("privacy-checks.schema.json", &slow.body);
    let slow_hash = find(&slow.body, "Username hashes are slow to guess");
    assert_eq!(slow_hash["state"], "hashed");
    assert_eq!(
        slow_hash["detail"],
        "Argon2id, 65536 KiB and 8 iterations. A guess takes at least 100 ms."
    );
    assert!(
        slow.body
            .as_array()
            .unwrap()
            .iter()
            .all(|row| row["item"] != "Username hashes are quick to guess")
    );
    // A cheap published set turns the warning back on. The account count is still the users table.
    mode.store(3, Ordering::Relaxed);
    let cheap = send(&app, "/api/admin/privacy-checks", Some(SESSION)).await;
    assert_eq!(cheap.status, StatusCode::OK, "{}", cheap.text);
    let cheap_hash = find(&cheap.body, "Username hashes are quick to guess");
    assert_eq!(cheap_hash["state"], "stored");
    assert_eq!(
        cheap_hash["detail"],
        format!(
            "Plain SHA-256. {} accounts still need the slow hash.",
            grouped(accounts)
        )
    );
    mode.store(0, Ordering::Relaxed);

    let config = send(&app, "/api/admin/configuration", Some(SESSION)).await;
    assert_eq!(config.status, StatusCode::OK, "{}", config.text);
    assert_schema("configuration.schema.json", &config.body);
    let host = find_var(&config.body, "HOST / PORT");
    assert_eq!(host["value"], "0.0.0.0:8082");
    assert_eq!(host["secret"], false);
    for name in [
        "REDIS_PASSWORD",
        "NEBULAR_SECRET_ACCESS_KEY",
        "TURN_SECRET",
        "APNS_KEY_PEM",
        "WEB_PUSH_VAPID_PRIVATE_KEY",
    ] {
        let variable = find_var(&config.body, name);
        assert!(variable["value"].is_null(), "{name}");
        assert_eq!(variable["secret"], true, "{name}");
        assert_eq!(variable["set"], true, "{name}");
    }
    assert_eq!(
        find_var(&config.body, "DATABASE_URL")["value"],
        "postgres://shroud@db:5432/shroud"
    );
    assert_eq!(find_var(&config.body, "ICE_SERVERS_JSON")["set"], false);
    assert_private(&config.text);

    let page = send(
        &app,
        &format!("/api/admin/audit-log?target={USER}&limit=1"),
        Some(SESSION),
    )
    .await;
    assert_eq!(page.status, StatusCode::OK, "{}", page.text);
    assert_schema("audit-log.schema.json", &page.body);
    assert_eq!(page.body["items"].as_array().unwrap().len(), 1);
    assert_eq!(page.body["items"][0]["target_kind"], "device");
    assert_eq!(page.body["items"][0]["target_id"], DEVICE);
    assert_eq!(page.body["items"][0]["operator"], "pages-probe");
    let cursor = page.body["next_cursor"].as_str().unwrap();
    let rest = send(
        &app,
        &format!("/api/admin/audit-log?target={USER}&limit=10&cursor={cursor}"),
        Some(SESSION),
    )
    .await;
    assert_eq!(rest.status, StatusCode::OK, "{}", rest.text);
    assert_eq!(rest.body["items"].as_array().unwrap().len(), 1);
    assert_eq!(rest.body["items"][0]["target_kind"], "user");
    assert_eq!(rest.body["items"][0]["target_id"], USER);
    assert!(rest.body["next_cursor"].is_null());
    let bad = send(&app, "/api/admin/audit-log?target=nope", Some(SESSION)).await;
    assert_eq!(bad.status, StatusCode::BAD_REQUEST);
    assert_eq!(bad.body["code"], "VALIDATION_ERROR");
    assert_eq!(bad.body["message"], "Target must be an account id.");

    mode.store(1, Ordering::Relaxed);
    unsafe {
        std::env::set_var("WEB_BUILD_FILE", "/run/shroud/web-build");
        std::env::remove_var("IOS_LATEST_VERSION");
        std::env::remove_var("IOS_MIN_VERSION");
        std::env::remove_var("IOS_UPDATE_URL");
        std::env::remove_var("ANDROID_LATEST_VERSION");
        std::env::remove_var("ANDROID_UPDATE_URL");
    }
    let nothing = send(&app, "/api/admin/client-versions", Some(SESSION)).await;
    assert_eq!(nothing.status, StatusCode::OK, "{}", nothing.text);
    assert_eq!(nothing.body, fixture("client-versions.nothing-set.json"));

    // The API is down: its public port and its operator port (the counters) both refuse.
    unsafe {
        std::env::set_var("API_INTERNAL_URL", "http://127.0.0.1:1");
        std::env::set_var("OPERATOR_PORT", "1");
    }
    let closed = send(&app, "/api/admin/calls", Some(SESSION)).await;
    assert_eq!(closed.status, StatusCode::BAD_GATEWAY);
    assert_eq!(closed.body, fixture("error.upstream-api.json"));
    let unchecked = send(&app, "/api/admin/calls/check", Some(SESSION)).await;
    assert_eq!(unchecked.status, StatusCode::BAD_GATEWAY);
    assert_eq!(unchecked.body, fixture("error.upstream-api.json"));

    clear(&admin, &owner, operator, user).await;
    let _ = std::fs::remove_dir_all(&build_dir);
}

fn fixture(name: &str) -> Value {
    let text = match name {
        "error.unauthenticated.json" => include_str!("../fixtures/error.unauthenticated.json"),
        "error.upstream-postgres.json" => include_str!("../fixtures/error.upstream-postgres.json"),
        "error.upstream-api.json" => include_str!("../fixtures/error.upstream-api.json"),
        "rate-limits.json" => include_str!("../fixtures/rate-limits.json"),
        "retention.json" => include_str!("../fixtures/retention.json"),
        "push-check.json" => include_str!("../fixtures/push-check.json"),
        "calls-check.json" => include_str!("../fixtures/calls-check.json"),
        "retention-check.json" => include_str!("../fixtures/retention-check.json"),
        "client-versions.json" => include_str!("../fixtures/client-versions.json"),
        "client-versions.nothing-set.json" => {
            include_str!("../fixtures/client-versions.nothing-set.json")
        }
        other => panic!("unknown fixture {other}"),
    };
    serde_json::from_str(text).unwrap()
}

fn assert_schema(name: &str, body: &Value) {
    let text = match name {
        "rate-limits.schema.json" => include_str!("../fixtures/schema/rate-limits.schema.json"),
        "retention.schema.json" => include_str!("../fixtures/schema/retention.schema.json"),
        "storage.schema.json" => include_str!("../fixtures/schema/storage.schema.json"),
        "client-versions.schema.json" => {
            include_str!("../fixtures/schema/client-versions.schema.json")
        }
        "push.schema.json" => include_str!("../fixtures/schema/push.schema.json"),
        "push-check.schema.json" => include_str!("../fixtures/schema/push-check.schema.json"),
        "calls-check.schema.json" => include_str!("../fixtures/schema/calls-check.schema.json"),
        "retention-check.schema.json" => {
            include_str!("../fixtures/schema/retention-check.schema.json")
        }
        "calls.schema.json" => include_str!("../fixtures/schema/calls.schema.json"),
        "privacy-checks.schema.json" => {
            include_str!("../fixtures/schema/privacy-checks.schema.json")
        }
        "configuration.schema.json" => include_str!("../fixtures/schema/configuration.schema.json"),
        "audit-log.schema.json" => include_str!("../fixtures/schema/audit-log.schema.json"),
        other => panic!("unknown schema {other}"),
    };
    let schema: Value = serde_json::from_str(text).unwrap();
    let validator = jsonschema::validator_for(&schema).unwrap();
    let errors: Vec<String> = validator
        .iter_errors(body)
        .map(|err| err.to_string())
        .collect();
    assert!(errors.is_empty(), "{errors:?}\n{body}");
}

fn find(body: &Value, item: &str) -> Value {
    body.as_array()
        .unwrap()
        .iter()
        .find(|row| row["item"] == item)
        .unwrap_or_else(|| panic!("missing {item}"))
        .clone()
}

fn find_var(body: &Value, name: &str) -> Value {
    body.as_array()
        .unwrap()
        .iter()
        .flat_map(|group| group["variables"].as_array().unwrap())
        .find(|variable| variable["name"] == name)
        .unwrap_or_else(|| panic!("missing {name}"))
        .clone()
}

fn assert_private(text: &str) {
    for leak in LEAKS {
        assert!(!text.contains(leak), "{leak} leaked");
    }
}

fn grouped(n: i64) -> String {
    let raw = n.max(0).to_string();
    let mut out = String::new();
    for (index, ch) in raw.chars().rev().enumerate() {
        if index > 0 && index.is_multiple_of(3) {
            out.push(',');
        }
        out.push(ch);
    }
    out.chars().rev().collect()
}

async fn storage_sql(pool: &PgPool) -> (i64, i64, i64) {
    let row = sqlx::query(
        "SELECT count(id) AS objects,
                coalesce(sum(size_bytes), 0)::bigint AS bytes,
                count(id) FILTER (WHERE message_id IS NULL) AS unlinked
         FROM media_objects",
    )
    .fetch_one(pool)
    .await
    .unwrap();
    (
        row.try_get("objects").unwrap(),
        row.try_get("bytes").unwrap(),
        row.try_get("unlinked").unwrap(),
    )
}

async fn push_sql(pool: &PgPool) -> (i64, i64, i64, i64, i64) {
    let row = sqlx::query(
        "SELECT
            (SELECT count(device_id) FROM push_tokens WHERE kind = 'alert') AS apns,
            (SELECT count(device_id) FROM push_tokens WHERE kind = 'voip') AS voip,
            (SELECT count(device_id) FROM web_push_subscriptions WHERE client = 'browser') AS web,
            (SELECT count(device_id) FROM web_push_subscriptions WHERE client = 'android') AS unified,
            (SELECT count(device_id) FROM device_notification_settings WHERE enabled = false) AS off",
    )
    .fetch_one(pool)
    .await
    .unwrap();
    (
        row.try_get("apns").unwrap(),
        row.try_get("voip").unwrap(),
        row.try_get("web").unwrap(),
        row.try_get("unified").unwrap(),
        row.try_get("off").unwrap(),
    )
}

async fn clear(admin: &PgPool, owner: &PgPool, operator: Uuid, user: Uuid) {
    sqlx::query("DELETE FROM admin.audit_log WHERE operator_id = $1")
        .bind(operator)
        .execute(admin)
        .await
        .unwrap();
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
    sqlx::query("DELETE FROM users WHERE id = $1")
        .bind(user)
        .execute(owner)
        .await
        .unwrap();
}

async fn seed(admin: &PgPool, owner: &PgPool, operator: Uuid, user: Uuid) {
    sqlx::query(
        "INSERT INTO admin.operators (id, name, role, enabled) VALUES ($1, 'pages-probe', 'read', true)",
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
    sqlx::query(
        "INSERT INTO users (id, username_hash, password_hash, share_code, created_at)
         VALUES ($1, $2, 'pages-secret-hash', 'PAGES0001', '2026-10-08 12:00:00+0000')",
    )
    .bind(user)
    .bind(vec![11u8; 32])
    .execute(owner)
    .await
    .unwrap();
    let device = Uuid::parse_str(DEVICE).unwrap();
    sqlx::query(
        "INSERT INTO devices (id, user_id, sealed_name, created_at)
         VALUES ($1, $2, $3, '2026-10-08 12:00:00+0000')",
    )
    .bind(device)
    .bind(user)
    .bind(SEALED)
    .execute(owner)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO push_tokens (device_id, apns_token, environment, kind)
         VALUES ($1, $2, 'sandbox', 'alert')",
    )
    .bind(device)
    .bind(TOKEN)
    .execute(owner)
    .await
    .unwrap();
    sqlx::query("INSERT INTO device_notification_settings (device_id, enabled) VALUES ($1, false)")
        .bind(device)
        .execute(owner)
        .await
        .unwrap();
    insert_audit(
        admin,
        operator,
        "b1110000-0000-4000-8000-00000000a003",
        "2026-10-08 14:00:00+0000",
        "device.remove",
        Some("device"),
        Some(DEVICE),
    )
    .await;
    insert_audit(
        admin,
        operator,
        "b1110000-0000-4000-8000-00000000a002",
        "2026-10-08 13:00:00+0000",
        "user.sign_out_all",
        Some("user"),
        Some(USER),
    )
    .await;
    insert_audit(
        admin,
        operator,
        "b1110000-0000-4000-8000-00000000a001",
        "2026-10-08 12:00:00+0000",
        "user.delete",
        Some("user"),
        Some(OTHER),
    )
    .await;
    insert_audit(
        admin,
        operator,
        "b1110000-0000-4000-8000-00000000a004",
        "2026-10-08 15:00:00+0000",
        "session.sign_in",
        None,
        None,
    )
    .await;
}

async fn insert_audit(
    admin: &PgPool,
    operator: Uuid,
    id: &str,
    at: &str,
    action: &str,
    kind: Option<&str>,
    target: Option<&str>,
) {
    sqlx::query(
        "INSERT INTO admin.audit_log (id, at, operator_id, action, target_kind, target_id, outcome)
         VALUES ($1, $2, $3, $4, $5, $6, 'ok')",
    )
    .bind(Uuid::parse_str(id).unwrap())
    .bind(sql_time(at))
    .bind(operator)
    .bind(action)
    .bind(kind)
    .bind(target.map(|value| Uuid::parse_str(value).unwrap()))
    .execute(admin)
    .await
    .unwrap();
}

async fn spawn_api(mode: Arc<AtomicU8>, web_build: Arc<Mutex<Option<String>>>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                break;
            };
            let mode = Arc::clone(&mode);
            let web_build = Arc::clone(&web_build);
            tokio::spawn(async move {
                let mut buf = vec![0u8; 4096];
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
                let (status, content_type, body) = if path.starts_with("/operator/metrics")
                    && request.contains("Authorization: Bearer operator-test-token\r\n")
                {
                    (
                        200,
                        "text/plain",
                        "shroud_media_legacy_reads_total 3\n\
                         shroud_media_migrated_total 9\n\
                         shroud_calls_created_total 5\n"
                            .to_owned(),
                    )
                } else if path.starts_with("/operator/retention/check")
                    && request.contains("Authorization: Bearer operator-test-token\r\n")
                {
                    (
                        200,
                        "application/json",
                        include_str!("../fixtures/retention-check.json").to_owned(),
                    )
                } else if path.starts_with("/operator/calls/check")
                    && request.contains("Authorization: Bearer operator-test-token\r\n")
                {
                    (
                        200,
                        "application/json",
                        include_str!("../fixtures/calls-check.json").to_owned(),
                    )
                } else if path.starts_with("/operator/push/check")
                    && request.contains("Authorization: Bearer operator-test-token\r\n")
                {
                    (
                        200,
                        "application/json",
                        include_str!("../fixtures/push-check.json").to_owned(),
                    )
                } else if path.starts_with("/api/v1/client-version") {
                    (
                        200,
                        "application/json",
                        version_body(&request, path, &mode, &web_build),
                    )
                } else if path.starts_with("/api/v1/auth/username-kdf") {
                    match mode.load(Ordering::Relaxed) {
                        2 => (
                            200,
                            "application/json",
                            "{\"algorithm\":\"argon2id\",\"version\":19,\"salt\":\"ABEiM0RVZneImaq7zN3u/w==\",\"memory_kib\":65536,\"iterations\":8,\"parallelism\":1,\"output_bytes\":32}"
                                .to_owned(),
                        ),
                        3 => (
                            200,
                            "application/json",
                            "{\"algorithm\":\"argon2id\",\"version\":19,\"salt\":\"ABEiM0RVZneImaq7zN3u/w==\",\"memory_kib\":19456,\"iterations\":2,\"parallelism\":1,\"output_bytes\":32}"
                                .to_owned(),
                        ),
                        _ => (404, "text/plain", "no".to_owned()),
                    }
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

fn version_body(
    request: &str,
    path: &str,
    mode: &AtomicU8,
    web_build: &Mutex<Option<String>>,
) -> String {
    let platform = query_param(path, "platform").unwrap_or_default();
    let version = query_param(path, "version").unwrap_or_default();
    let named = header_value(request, "x-shroud-client").is_some();
    if mode.load(Ordering::Relaxed) == 1 {
        return answer("current", None);
    }
    match platform.as_str() {
        "ios" => answer(
            if !named || version_before(&version, "1.1") {
                "update_required"
            } else {
                "current"
            },
            Some("1.1"),
        ),
        "android" => {
            let status = if !named {
                "update_required"
            } else if version_before(&version, "0.2.0") {
                "update_available"
            } else {
                "current"
            };
            answer(status, Some("0.2.0"))
        }
        "web" => {
            let build = web_build.lock().unwrap().clone();
            match build.as_deref() {
                Some(build) if build == version => answer("current", Some(build)),
                Some(build) => answer("update_available", Some(build)),
                None => answer("current", None),
            }
        }
        _ => answer("current", None),
    }
}

fn sql_time(text: &str) -> chrono::DateTime<chrono::Utc> {
    chrono::DateTime::parse_from_str(text, "%Y-%m-%d %H:%M:%S%z")
        .unwrap()
        .with_timezone(&chrono::Utc)
}

fn answer(status: &str, latest: Option<&str>) -> String {
    let latest = latest
        .map(|value| format!("\"{value}\""))
        .unwrap_or_else(|| "null".to_owned());
    format!(
        r#"{{"status":"{status}","latest_version":{latest},"update_url":null,"server_version":"0.1.0"}}"#
    )
}

fn version_before(version: &str, boundary: &str) -> bool {
    let parse = |raw: &str| -> Option<Vec<u32>> {
        raw.split('.')
            .map(|part| part.parse::<u32>().ok())
            .collect()
    };
    let Some(left) = parse(version) else {
        return true;
    };
    let Some(right) = parse(boundary) else {
        return false;
    };
    let len = left.len().max(right.len());
    for index in 0..len {
        let ordering = left
            .get(index)
            .copied()
            .unwrap_or(0)
            .cmp(&right.get(index).copied().unwrap_or(0));
        if ordering.is_ne() {
            return ordering.is_lt();
        }
    }
    false
}

fn query_param(path: &str, key: &str) -> Option<String> {
    let query = path.split_once('?')?.1;
    query.split('&').find_map(|pair| {
        let (name, value) = pair.split_once('=')?;
        (name == key).then(|| value.to_owned())
    })
}

fn header_value(request: &str, name: &str) -> Option<String> {
    request.lines().find_map(|line| {
        let (got, value) = line.split_once(':')?;
        got.eq_ignore_ascii_case(name)
            .then(|| value.trim().to_owned())
    })
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
