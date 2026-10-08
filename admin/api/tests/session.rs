//! Session flow against a throwaway Postgres (docs/admin-plan.md G1.1).
//!
//! Set `ADMIN_TEST_DATABASE_URL` to a `shroud_admin` connection. Without it the test does not
//! claim to have checked the database.

use std::time::Instant;

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use chrono::{DateTime, Utc};
use http_body_util::BodyExt;
use serde_json::Value;
use shroud_admin::auth::bootstrap;
use shroud_admin::totp;
use shroud_admin::{AppState, router_with};
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;

const KEY_HEX: &str = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
const PASSWORD: &str = "correct-horse";

#[tokio::test]
async fn session_flow_matches_the_fixtures_and_stores_no_secret() {
    let Some(url) = nonempty("ADMIN_TEST_DATABASE_URL") else {
        eprintln!("ADMIN_TEST_DATABASE_URL unset; session test not run");
        return;
    };
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&url)
        .await
        .expect("connect");
    shroud_admin::db::migrate(&pool).await.expect("migrate");
    sqlx::query(
        "TRUNCATE admin.audit_log, admin.operator_sessions, admin.recovery_codes, admin.setup_links, admin.operators",
    )
    .execute(&pool)
    .await
    .expect("truncate");

    let key = shroud_admin_key();
    let app = router_with(None, AppState::connected(pool.clone(), key));

    let link = bootstrap(&pool, "http://127.0.0.1:8082", None, false)
        .await
        .expect("bootstrap");
    let token = link.rsplit('/').next().unwrap().to_owned();
    assert!(
        bootstrap(&pool, "http://127.0.0.1:8082", None, false)
            .await
            .is_err()
    );

    let setup = send(
        &app,
        "GET",
        &format!("/api/admin/setup/{token}"),
        None,
        None,
        None,
    )
    .await;
    assert_eq!(setup.status, StatusCode::OK);
    assert!(setup.body["qr_svg"].as_str().unwrap().contains("<svg"));
    let secret = secret_from_uri(setup.body["totp_uri"].as_str().unwrap());
    let code = current_code(&secret);

    let short = send(
        &app,
        "POST",
        &format!("/api/admin/setup/{token}"),
        Some(r#"{"password":"short-pass","totp":"000000"}"#),
        None,
        None,
    )
    .await;
    assert_eq!(short.status, StatusCode::BAD_REQUEST);
    assert_eq!(short.body["code"], "VALIDATION_ERROR");

    let wrong = send(
        &app,
        "POST",
        &format!("/api/admin/setup/{token}"),
        Some(r#"{"password":"correct-horse","totp":"000000"}"#),
        None,
        None,
    )
    .await;
    assert_eq!(wrong.status, StatusCode::UNAUTHORIZED);
    assert_fixture(
        &wrong.text,
        include_str!("../fixtures/error.bad-credentials.json"),
    );

    let enrolled = send(
        &app,
        "POST",
        &format!("/api/admin/setup/{token}"),
        Some(&format!(r#"{{"password":"{PASSWORD}","totp":"{code}"}}"#)),
        None,
        None,
    )
    .await;
    assert_eq!(enrolled.status, StatusCode::OK);
    let codes = enrolled.body["recovery_codes"].as_array().unwrap();
    assert_eq!(codes.len(), 8);

    let used = send(
        &app,
        "POST",
        &format!("/api/admin/setup/{token}"),
        Some(&format!(r#"{{"password":"{PASSWORD}","totp":"{code}"}}"#)),
        None,
        None,
    )
    .await;
    assert_eq!(used.status, StatusCode::GONE);
    assert_fixture(&used.text, include_str!("../fixtures/error.link-used.json"));

    assert_no_plaintext(&pool, PASSWORD, &secret, &token, codes).await;

    let unknown_at = Instant::now();
    let unknown = sign_in(&app, "nobody", PASSWORD, &code).await;
    let unknown_elapsed = unknown_at.elapsed();
    let wrong_at = Instant::now();
    let wrong_password = sign_in(&app, "operator", "wrong-password-xx", &code).await;
    let wrong_elapsed = wrong_at.elapsed();
    assert_eq!(unknown.status, StatusCode::UNAUTHORIZED);
    assert_eq!(wrong_password.status, StatusCode::UNAUTHORIZED);
    assert_fixture(
        &unknown.text,
        include_str!("../fixtures/error.bad-credentials.json"),
    );
    assert_eq!(unknown.text, wrong_password.text);
    assert!(
        unknown_elapsed.as_millis() > 15,
        "unknown operator skipped the password hash"
    );
    assert!(
        wrong_elapsed.as_millis() > 15,
        "wrong password skipped the password hash"
    );

    let wrong_code = sign_in(&app, "operator", PASSWORD, "000000").await;
    assert_eq!(wrong_code.status, StatusCode::UNAUTHORIZED);
    let detail: String = sqlx::query_scalar(
        "SELECT detail FROM admin.audit_log WHERE action = 'session.sign_in' AND outcome = 'refused' ORDER BY at DESC LIMIT 1",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(detail, "Wrong code");

    let locked = AppState::connected(pool.clone(), key);
    let locked_app = router_with(None, locked);
    for _ in 0..10 {
        let failed = sign_in(&locked_app, "operator", "wrong-password-xx", "000000").await;
        assert_eq!(failed.status, StatusCode::UNAUTHORIZED);
    }
    let limited = sign_in(&locked_app, "operator", "wrong-password-xx", "000000").await;
    assert_eq!(limited.status, StatusCode::TOO_MANY_REQUESTS);
    assert_fixture(
        &limited.text,
        include_str!("../fixtures/error.rate-limited.json"),
    );
    let retry = limited
        .headers
        .get(header::RETRY_AFTER)
        .and_then(|value| value.to_str().ok())
        .unwrap();
    assert!(retry.parse::<u64>().unwrap() >= 1);
    let still = sign_in(&locked_app, "operator", PASSWORD, &current_code(&secret)).await;
    assert_eq!(still.status, StatusCode::TOO_MANY_REQUESTS);

    let signed_in = sign_in(&app, "operator", PASSWORD, &current_code(&secret)).await;
    assert_eq!(signed_in.status, StatusCode::NO_CONTENT);
    assert!(signed_in.text.is_empty());
    let cookies = session_cookies(&signed_in.headers);
    assert!(cookies.session_set.contains("HttpOnly"));
    assert!(cookies.session_set.contains("Secure"));
    assert!(cookies.session_set.contains("SameSite=Strict"));
    assert!(!cookies.csrf_set.contains("HttpOnly"));

    let session = send(
        &app,
        "GET",
        "/api/admin/session",
        None,
        Some(&cookies.header),
        None,
    )
    .await;
    assert_eq!(session.status, StatusCode::OK);
    assert_eq!(session.body["operator"]["name"], "operator");
    assert_eq!(session.body["operator"]["role"], "write");
    assert!(session.body["operator"]["id"].as_str().unwrap().len() == 36);
    assert!(session.body["reauth_until"].is_null());
    let expires =
        DateTime::parse_from_rfc3339(session.body["expires_at"].as_str().unwrap()).unwrap();
    let left = expires.signed_duration_since(Utc::now());
    assert!(left.num_minutes() >= 29 && left.num_minutes() <= 30);

    let bad_reauth = send(
        &app,
        "POST",
        "/api/admin/session/reauth",
        Some(r#"{"totp":"000000"}"#),
        Some(&cookies.header),
        Some(&cookies.csrf),
    )
    .await;
    assert_eq!(bad_reauth.status, StatusCode::UNAUTHORIZED);

    let reauth = send(
        &app,
        "POST",
        "/api/admin/session/reauth",
        Some(&format!(r#"{{"totp":"{}"}}"#, current_code(&secret))),
        Some(&cookies.header),
        Some(&cookies.csrf),
    )
    .await;
    assert_eq!(reauth.status, StatusCode::OK);
    let until =
        DateTime::parse_from_rfc3339(reauth.body["reauth_until"].as_str().unwrap()).unwrap();
    let window = until.signed_duration_since(Utc::now());
    assert!(window.num_minutes() >= 4 && window.num_minutes() <= 5);

    let no_csrf = send(
        &app,
        "DELETE",
        "/api/admin/session",
        None,
        Some(&cookies.header),
        None,
    )
    .await;
    assert_eq!(no_csrf.status, StatusCode::FORBIDDEN);
    assert_eq!(no_csrf.body["code"], "CSRF");

    let signed_out = send(
        &app,
        "DELETE",
        "/api/admin/session",
        None,
        Some(&cookies.header),
        Some(&cookies.csrf),
    )
    .await;
    assert_eq!(signed_out.status, StatusCode::NO_CONTENT);
    let gone = send(
        &app,
        "GET",
        "/api/admin/session",
        None,
        Some(&cookies.header),
        None,
    )
    .await;
    assert_eq!(gone.status, StatusCode::UNAUTHORIZED);
    assert_fixture(
        &gone.text,
        include_str!("../fixtures/error.unauthenticated.json"),
    );

    let recovery = codes[0].as_str().unwrap();
    let recovered = send(
        &app,
        "POST",
        "/api/admin/session/recovery",
        Some(&format!(
            r#"{{"operator":"operator","password":"{PASSWORD}","recovery_code":"{recovery}"}}"#
        )),
        None,
        None,
    )
    .await;
    assert_eq!(recovered.status, StatusCode::NO_CONTENT);
    let again = send(
        &app,
        "POST",
        "/api/admin/session/recovery",
        Some(&format!(
            r#"{{"operator":"operator","password":"{PASSWORD}","recovery_code":"{recovery}"}}"#
        )),
        None,
        None,
    )
    .await;
    assert_eq!(again.status, StatusCode::UNAUTHORIZED);

    let actions: Vec<String> = sqlx::query_scalar(
        "SELECT action || ' ' || outcome FROM admin.audit_log ORDER BY at, action",
    )
    .fetch_all(&pool)
    .await
    .unwrap();
    assert!(actions.iter().any(|row| row == "setup.complete ok"));
    assert!(actions.iter().any(|row| row == "session.sign_in ok"));
    assert!(actions.iter().any(|row| row == "session.sign_out ok"));
    assert!(actions.iter().any(|row| row == "session.recovery ok"));

    let fresh = bootstrap(&pool, "http://127.0.0.1:8082/", None, true)
        .await
        .expect("recover");
    assert_ne!(fresh, link);
    let old = send(
        &app,
        "GET",
        &format!("/api/admin/setup/{token}"),
        None,
        None,
        None,
    )
    .await;
    assert_eq!(old.status, StatusCode::GONE);
    let new_token = fresh.rsplit('/').next().unwrap();
    let renewed = send(
        &app,
        "GET",
        &format!("/api/admin/setup/{new_token}"),
        None,
        None,
        None,
    )
    .await;
    assert_eq!(renewed.status, StatusCode::OK);
    let old_password = sign_in(&app, "operator", PASSWORD, &current_code(&secret)).await;
    assert_eq!(old_password.status, StatusCode::UNAUTHORIZED);
}

struct Reply {
    status: StatusCode,
    headers: axum::http::HeaderMap,
    text: String,
    body: Value,
}

struct Cookies {
    header: String,
    session_set: String,
    csrf_set: String,
    csrf: String,
}

fn nonempty(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

fn shroud_admin_key() -> [u8; 32] {
    let mut key = [0u8; 32];
    for (index, byte) in KEY_HEX.as_bytes().chunks(2).enumerate() {
        key[index] = u8::from_str_radix(std::str::from_utf8(byte).unwrap(), 16).unwrap();
    }
    key
}

fn secret_from_uri(uri: &str) -> Vec<u8> {
    let secret = uri
        .split("secret=")
        .nth(1)
        .unwrap()
        .split('&')
        .next()
        .unwrap();
    totp::decode_secret(secret).unwrap()
}

fn current_code(secret: &[u8]) -> String {
    let counter = (Utc::now().timestamp() / 30) as u64;
    format!("{:06}", totp::hotp(secret, counter).unwrap())
}

async fn sign_in(app: &axum::Router, operator: &str, password: &str, code: &str) -> Reply {
    send(
        app,
        "POST",
        "/api/admin/session",
        Some(&format!(
            r#"{{"operator":"{operator}","password":"{password}","totp":"{code}"}}"#
        )),
        None,
        None,
    )
    .await
}

async fn send(
    app: &axum::Router,
    method: &str,
    uri: &str,
    body: Option<&str>,
    cookie: Option<&str>,
    csrf: Option<&str>,
) -> Reply {
    let mut request = Request::builder().method(method).uri(uri);
    if body.is_some() {
        request = request.header(header::CONTENT_TYPE, "application/json");
    }
    if let Some(cookie) = cookie {
        request = request.header(header::COOKIE, cookie);
    }
    if let Some(csrf) = csrf {
        request = request.header("x-admin-csrf", csrf);
    }
    let response = app
        .clone()
        .oneshot(
            request
                .body(Body::from(body.unwrap_or("").to_owned()))
                .unwrap(),
        )
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

fn assert_fixture(actual: &str, fixture: &str) {
    let actual: Value = serde_json::from_str(actual).unwrap();
    let expected: Value = serde_json::from_str(fixture).unwrap();
    assert_eq!(actual, expected);
}

fn session_cookies(headers: &axum::http::HeaderMap) -> Cookies {
    let mut session_set = String::new();
    let mut csrf_set = String::new();
    for value in headers.get_all(header::SET_COOKIE) {
        let text = value.to_str().unwrap();
        if text.starts_with("__Host-admin=") {
            session_set = text.to_owned();
        } else if text.starts_with("admin_csrf=") {
            csrf_set = text.to_owned();
        }
    }
    let session = session_set
        .split(';')
        .next()
        .unwrap()
        .split_once('=')
        .unwrap()
        .1
        .to_owned();
    let csrf = csrf_set
        .split(';')
        .next()
        .unwrap()
        .split_once('=')
        .unwrap()
        .1
        .to_owned();
    Cookies {
        header: format!("__Host-admin={session}; admin_csrf={csrf}"),
        session_set,
        csrf_set,
        csrf,
    }
}

async fn assert_no_plaintext(
    pool: &sqlx::PgPool,
    password: &str,
    secret: &[u8],
    token: &str,
    codes: &[Value],
) {
    let password_hash: String = sqlx::query_scalar("SELECT password_hash FROM admin.operators")
        .fetch_one(pool)
        .await
        .unwrap();
    assert!(password_hash.starts_with("$argon2id$"));
    assert!(!password_hash.contains(password));

    let sealed: Vec<u8> = sqlx::query_scalar("SELECT totp_secret_enc FROM admin.operators")
        .fetch_one(pool)
        .await
        .unwrap();
    assert!(!sealed.windows(secret.len()).any(|window| window == secret));

    let token_hash: Vec<u8> = sqlx::query_scalar("SELECT token_hash FROM admin.setup_links")
        .fetch_one(pool)
        .await
        .unwrap();
    assert_ne!(token_hash, token.as_bytes());

    let stored_codes: Vec<Vec<u8>> =
        sqlx::query_scalar("SELECT code_hash FROM admin.recovery_codes")
            .fetch_all(pool)
            .await
            .unwrap();
    for code in codes {
        let text = code.as_str().unwrap();
        assert!(
            stored_codes
                .iter()
                .all(|hash| hash.as_slice() != text.as_bytes())
        );
        assert!(!password_hash.contains(text));
    }

    let audit: String =
        sqlx::query_scalar("SELECT coalesce(string_agg(detail, ' '), '') FROM admin.audit_log")
            .fetch_one(pool)
            .await
            .unwrap();
    assert!(!audit.contains(password));
    assert!(!audit.contains(token));
    for code in codes {
        assert!(!audit.contains(code.as_str().unwrap()));
    }
    let _ = secret;
}
