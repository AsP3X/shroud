//! Integration tests for auth register/login/session flows.
//!
//! Human: Requires Postgres — set `DATABASE_URL` (see `server/.env.example`).
//! Agent: HTTP auth routes against real pool + migrations.

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use shroud_server::routes;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

fn test_state(pool: sqlx::PgPool) -> shroud_server::state::AppState {
    shroud_server::state::AppState::for_integration_tests(pool)
}

async fn test_app() -> Option<axum::Router> {
    test_app_and_state().await.map(|(app, _)| app)
}

/// The app plus its state, for tests that listen on the realtime hub.
async fn test_app_and_state() -> Option<(axum::Router, shroud_server::state::AppState)> {
    let database_url = std::env::var("DATABASE_URL").ok()?;
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .ok()?;
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .ok()?;
    let state = test_state(pool);
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state.clone());
    Some((app, state))
}

async fn json_body(response: axum::response::Response) -> Value {
    let body = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    serde_json::from_slice(&body).expect("json")
}

fn unique_user() -> (String, String) {
    // Username max is 32 chars — use a short unique suffix.
    let id = &Uuid::new_v4().simple().to_string()[..12];
    (format!("u_{id}"), "correct-horse-battery".into())
}

#[tokio::test]
async fn register_login_me_logout_flow() {
    let Some(app) = test_app().await else {
        eprintln!("skipping register_login_me_logout_flow: DATABASE_URL unavailable");
        return;
    };

    let (username, password) = unique_user();

    let register = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username_hash": shroud_server::auth::username_hash_b64(&username),
                        "password": password,
                        "device_name": "iPhone Test"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");

    assert_eq!(register.status(), StatusCode::CREATED);
    let registered = json_body(register).await;
    let token = registered["token"].as_str().expect("token");
    let device_id = registered["device"]["id"].as_str().expect("device id");
    assert!(registered["user"].get("username").is_none());
    // An older build's plaintext `device_name` is dropped, not stored or echoed.
    assert!(registered["device"].get("name").is_none());
    assert!(registered["device"].get("sealed_name").is_none());
    let share_code = registered["user"]["share_code"]
        .as_str()
        .expect("share_code");
    assert_eq!(share_code.len(), 10);
    assert!(share_code.chars().all(|c| c.is_ascii_alphanumeric()));

    let me = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/auth/me")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(me.status(), StatusCode::OK);
    let me_json = json_body(me).await;
    assert!(me_json["user"].get("username").is_none());
    assert_eq!(me_json["user"]["share_code"], share_code);
    assert_eq!(me_json["device"]["id"], device_id);

    let logout = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/logout")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);

    let me_after = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/auth/me")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(me_after.status(), StatusCode::UNAUTHORIZED);

    let login = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/login")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username_hash": shroud_server::auth::username_hash_b64(username),
                        "password": password,
                        "device_id": device_id,
                        "device_name": "iPhone Test"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(login.status(), StatusCode::OK);
    let logged_in = json_body(login).await;
    assert_eq!(logged_in["device"]["id"], device_id);
    assert!(logged_in["token"].as_str().is_some());
}

#[tokio::test]
async fn register_rejects_reserved_and_common_password() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping register_rejects_reserved_and_common_password: DATABASE_URL unavailable"
        );
        return;
    };

    let reserved = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username_hash": shroud_server::auth::username_hash_b64("admin"),
                        "password": "correct-horse-battery"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(reserved.status(), StatusCode::BAD_REQUEST);
    let body = json_body(reserved).await;
    assert_eq!(body["error"]["code"], "USERNAME_RESERVED");

    let common = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username_hash": shroud_server::auth::username_hash_b64(format!("ok_{}", &Uuid::new_v4().simple().to_string()[..10])),
                        "password": "password"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(common.status(), StatusCode::BAD_REQUEST);
    let body = json_body(common).await;
    assert_eq!(body["error"]["code"], "PASSWORD_TOO_COMMON");
}

#[tokio::test]
async fn username_kdf_is_public() {
    let Some(app) = test_app().await else {
        eprintln!("skipping username_kdf_is_public: DATABASE_URL unavailable");
        return;
    };

    let response = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/auth/username-kdf")
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::OK);
    let body = json_body(response).await;
    assert_eq!(body["algorithm"], "argon2id");
    assert_eq!(body["version"], 19);
    assert_eq!(body["salt"], "ABEiM0RVZneImaq7zN3u/w==");
    assert_eq!(body["memory_kib"], 65536);
    assert_eq!(body["iterations"], 8);
    assert_eq!(body["parallelism"], 1);
    assert_eq!(body["output_bytes"], 32);
}

#[tokio::test]
async fn register_rejects_the_slow_hash_of_a_reserved_name() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping register_rejects_the_slow_hash_of_a_reserved_name: DATABASE_URL unavailable"
        );
        return;
    };

    let admin = BASE64.encode(shroud_server::auth::UsernameKdf::test_reserved_admin());
    let reserved = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username_hash": admin,
                        "password": "correct-horse-battery"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(reserved.status(), StatusCode::BAD_REQUEST);
    let body = json_body(reserved).await;
    assert_eq!(body["error"]["code"], "USERNAME_RESERVED");
}

#[tokio::test]
async fn login_migrates_a_sha256_username_and_a_device_limit_rolls_it_back() {
    let Some((app, state)) = test_app_and_state().await else {
        eprintln!(
            "skipping login_migrates_a_sha256_username_and_a_device_limit_rolls_it_back: DATABASE_URL unavailable"
        );
        return;
    };

    let (username, password) = unique_user();
    let legacy = shroud_server::auth::username_hash_b64(&username);
    let slow = tokio::task::spawn_blocking({
        let username = username.clone();
        move || {
            shroud_server::auth::UsernameKdf::for_tests()
                .hash_b64(&username)
                .expect("hash")
        }
    })
    .await
    .expect("join");
    let slow_bytes = BASE64.decode(&slow).expect("digest");

    let auth_request = |body: serde_json::Value| {
        Request::builder()
            .method("POST")
            .uri("/api/v1/auth/login")
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(body.to_string()))
            .expect("request")
    };
    async fn stored_hash(pool: &sqlx::PgPool, user_id: Uuid) -> Vec<u8> {
        let hash: Option<Vec<u8>> =
            sqlx::query_scalar("SELECT username_hash FROM users WHERE id = $1")
                .bind(user_id)
                .fetch_one(pool)
                .await
                .expect("row");
        hash.expect("username hash")
    }

    let register = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username_hash": legacy, "password": password }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(register.status(), StatusCode::CREATED);
    let registered = json_body(register).await;
    let user_id = Uuid::parse_str(registered["user"]["id"].as_str().expect("user id")).unwrap();

    for _ in 1..shroud_server::auth::MAX_DEVICES_PER_USER {
        let login = app
            .clone()
            .oneshot(auth_request(
                json!({ "username_hash": legacy, "password": password }),
            ))
            .await
            .expect("response");
        assert_eq!(login.status(), StatusCode::OK);
    }

    let refused = app
        .clone()
        .oneshot(auth_request(json!({
            "username_hash": slow,
            "legacy_username_hash": legacy,
            "password": password
        })))
        .await
        .expect("response");
    assert_eq!(refused.status(), StatusCode::CONFLICT);
    let refused_body = json_body(refused).await;
    assert_eq!(refused_body["error"]["code"], "DEVICE_LIMIT");
    assert_eq!(
        stored_hash(&state.pool, user_id).await,
        BASE64.decode(&legacy).unwrap(),
        "a refused login leaves the old digest in place"
    );
    let oldest = refused_body["oldest_device"]["id"]
        .as_str()
        .expect("oldest device")
        .to_string();

    let migrated = app
        .clone()
        .oneshot(auth_request(json!({
            "username_hash": slow,
            "legacy_username_hash": legacy,
            "password": password,
            "replace_device_id": oldest
        })))
        .await
        .expect("response");
    assert_eq!(migrated.status(), StatusCode::OK);
    let migrated_body = json_body(migrated).await;
    assert_eq!(stored_hash(&state.pool, user_id).await, slow_bytes);
    // The account is still full: the slow digest alone signs in only by reusing this device.
    let device_id = migrated_body["device"]["id"].as_str().expect("device");

    let again = app
        .clone()
        .oneshot(auth_request(json!({
            "username_hash": slow,
            "password": password,
            "device_id": device_id
        })))
        .await
        .expect("response");
    assert_eq!(again.status(), StatusCode::OK);

    let stale = app
        .oneshot(auth_request(
            json!({ "username_hash": legacy, "password": password }),
        ))
        .await
        .expect("response");
    assert_eq!(stale.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn purge_revoked_sessions_deletes_old_rows() {
    let Some(app) = test_app().await else {
        eprintln!("skipping purge_revoked_sessions_deletes_old_rows: DATABASE_URL unavailable");
        return;
    };

    let (username, password) = unique_user();
    let register = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username_hash": shroud_server::auth::username_hash_b64(username),
                        "password": password,
                        "device_name": "Purge Test"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(register.status(), StatusCode::CREATED);
    let reg = json_body(register).await;
    let token = reg["token"].as_str().unwrap().to_string();

    let logout = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/logout")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);

    let pool = sqlx::postgres::PgPoolOptions::new()
        .max_connections(2)
        .connect(&std::env::var("DATABASE_URL").unwrap())
        .await
        .unwrap();

    // Backdate revoked_at past the 30-day retention window.
    sqlx::query(
        r#"
        UPDATE sessions
        SET revoked_at = now() - interval '40 days'
        WHERE revoked_at IS NOT NULL
        "#,
    )
    .execute(&pool)
    .await
    .unwrap();

    let purged = shroud_server::auth::session::purge_revoked_sessions(&pool)
        .await
        .expect("purge");
    assert!(purged >= 1);
}

#[tokio::test]
async fn login_reuses_device_and_lists_devices() {
    let Some(app) = test_app().await else {
        eprintln!("skipping login_reuses_device_and_lists_devices: DATABASE_URL unavailable");
        return;
    };

    let (username, password) = unique_user();
    let register = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username_hash": shroud_server::auth::username_hash_b64(&username),
                        "password": password,
                        "device_name": "Phone A"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    let registered = json_body(register).await;
    let token = registered["token"].as_str().unwrap().to_string();
    let device_id = registered["device"]["id"].as_str().unwrap().to_string();

    let devices = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/devices")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(devices.status(), StatusCode::OK);
    let list = json_body(devices).await;
    assert_eq!(list["devices"].as_array().unwrap().len(), 1);
    assert_eq!(list["devices"][0]["is_current"], true);

    // Second login without device_id creates another device.
    let login2 = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/login")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username_hash": shroud_server::auth::username_hash_b64(username),
                        "password": password,
                        "device_name": "Phone B"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(login2.status(), StatusCode::OK);
    let second = json_body(login2).await;
    assert_ne!(second["device"]["id"], device_id);

    let token_b = second["token"].as_str().unwrap();
    let devices2 = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/devices")
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let list2 = json_body(devices2).await;
    assert_eq!(list2["devices"].as_array().unwrap().len(), 2);
}

#[tokio::test]
async fn delete_account_requires_password_and_removes_user() {
    let Some((app, state)) = test_app_and_state().await else {
        eprintln!(
            "skipping delete_account_requires_password_and_removes_user: DATABASE_URL unavailable"
        );
        return;
    };

    let (username, password) = unique_user();
    let register = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username_hash": shroud_server::auth::username_hash_b64(&username),
                        "password": password,
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(register.status(), StatusCode::CREATED);
    let registered = json_body(register).await;
    let token = registered["token"].as_str().unwrap();
    let device_id = registered["device"]["id"].as_str().unwrap();
    let named = authed(
        &app,
        "PUT",
        &format!("/api/v1/devices/{device_id}/name"),
        token,
        Some(json!({ "sealed_name": BASE64.encode([3_u8; 156]) })),
    )
    .await;
    assert_eq!(named.status(), StatusCode::NO_CONTENT);

    let wrong = app
        .clone()
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri("/api/v1/auth/account")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "password": "wrong-password-value" }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(wrong.status(), StatusCode::UNAUTHORIZED);

    let deleted = app
        .clone()
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri("/api/v1/auth/account")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "password": password }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);

    let me = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/auth/me")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(me.status(), StatusCode::UNAUTHORIZED);

    // The row stays as a placeholder with nothing that names or signs in the account.
    let user_id = registered["user"]["id"].as_str().unwrap();
    let share_code = registered["user"]["share_code"].as_str().unwrap();
    let scrubbed: bool = sqlx::query_scalar(
        r#"
        SELECT deleted_at IS NOT NULL AND username_hash IS NULL AND share_code IS NULL
               AND password_hash IS NULL
        FROM users WHERE id = $1
        "#,
    )
    .bind(Uuid::parse_str(user_id).unwrap())
    .fetch_one(&state.pool)
    .await
    .expect("deleted user row");
    assert!(scrubbed);
    let live_devices: i64 = sqlx::query_scalar(
        r#"
        SELECT COUNT(*)::bigint FROM devices
        WHERE user_id = $1 AND (revoked_at IS NULL OR sealed_name IS NOT NULL)
        "#,
    )
    .bind(Uuid::parse_str(user_id).unwrap())
    .fetch_one(&state.pool)
    .await
    .expect("devices");
    assert_eq!(live_devices, 0);

    let login = login_request(&app, &username, &password).await;
    assert_eq!(login.status(), StatusCode::UNAUTHORIZED);

    // Nobody can find it any more, and its username and share code are free again.
    let (other, _, _) = register_user(&app, &unique_user().0, &password).await;
    for uri in [
        format!("/api/v1/users/{user_id}"),
        format!("/api/v1/users/by-username/{username}"),
        format!("/api/v1/users/by-code/{share_code}"),
    ] {
        let lookup = authed(&app, "GET", &uri, &other, None).await;
        assert_eq!(lookup.status(), StatusCode::NOT_FOUND, "{uri}");
    }
    let request = authed(
        &app,
        "POST",
        "/api/v1/contacts/requests",
        &other,
        Some(json!({ "user_id": user_id })),
    )
    .await;
    assert_eq!(request.status(), StatusCode::NOT_FOUND);

    let (_, new_id, _) = register_user(&app, &username, &password).await;
    assert_ne!(new_id, user_id);
}

/// `POST /auth/login` without a device id.
async fn login_request(
    app: &axum::Router,
    username: &str,
    password: &str,
) -> axum::response::Response {
    app.clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/login")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username_hash": shroud_server::auth::username_hash_b64(username), "password": password }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response")
}

#[tokio::test]
async fn login_at_device_cap_reclaims_an_idle_device() {
    let Some(app) = test_app().await else {
        eprintln!("skipping login_at_device_cap_reclaims_an_idle_device: DATABASE_URL unavailable");
        return;
    };

    let (username, password) = unique_user();
    let auth_request = |uri: &str, body: Value| {
        Request::builder()
            .method("POST")
            .uri(uri)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(body.to_string()))
            .expect("request")
    };

    let register = app
        .clone()
        .oneshot(auth_request(
            "/api/v1/auth/register",
            json!({ "username_hash": shroud_server::auth::username_hash_b64(&username), "password": password }),
        ))
        .await
        .expect("response");
    assert_eq!(register.status(), StatusCode::CREATED);
    let registered = json_body(register).await;
    let first_device = registered["device"]["id"].as_str().unwrap().to_string();
    let first_token = registered["token"].as_str().unwrap().to_string();

    // Fill the cap with live devices.
    for _ in 1..shroud_server::auth::MAX_DEVICES_PER_USER {
        let login = app
            .clone()
            .oneshot(auth_request(
                "/api/v1/auth/login",
                json!({ "username_hash": shroud_server::auth::username_hash_b64(&username), "password": password }),
            ))
            .await
            .expect("response");
        assert_eq!(login.status(), StatusCode::OK);
    }

    // Every device is signed in: nothing to reclaim.
    let refused = app
        .clone()
        .oneshot(auth_request(
            "/api/v1/auth/login",
            json!({ "username_hash": shroud_server::auth::username_hash_b64(&username), "password": password }),
        ))
        .await
        .expect("response");
    assert_eq!(refused.status(), StatusCode::CONFLICT);

    // The first device published keys; its private halves leave with the logout wipe.
    let stale = BASE64.encode([0xAB_u8; 32]);
    let bundle = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/keys/bundle")
                .header(header::AUTHORIZATION, format!("Bearer {first_token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "registration_id": 42,
                        "identity_key": stale,
                        "signed_pre_key": {
                            "key_id": 7,
                            "public_key": stale,
                            "signature": BASE64.encode([0xAB_u8; 64]),
                        },
                        "one_time_pre_keys": (1..=3)
                            .map(|id| json!({ "key_id": 100 + id, "public_key": stale }))
                            .collect::<Vec<_>>(),
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(bundle.status(), StatusCode::NO_CONTENT);

    // The first holder had named its device; the name describes them, not whoever comes next.
    let named = authed(
        &app,
        "PUT",
        &format!("/api/v1/devices/{first_device}/name"),
        &first_token,
        Some(json!({ "sealed_name": BASE64.encode([0x5A_u8; 156]) })),
    )
    .await;
    assert_eq!(named.status(), StatusCode::NO_CONTENT);

    // The first device logs out and forgets its id; the next login gets that device back.
    let logout = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/logout")
                .header(header::AUTHORIZATION, format!("Bearer {first_token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);

    let reclaimed = app
        .clone()
        .oneshot(auth_request(
            "/api/v1/auth/login",
            json!({ "username_hash": shroud_server::auth::username_hash_b64(username), "password": password, "device_name": "Browser" }),
        ))
        .await
        .expect("response");
    assert_eq!(reclaimed.status(), StatusCode::OK);
    let reclaimed = json_body(reclaimed).await;
    assert_eq!(reclaimed["device"]["id"], first_device);
    assert!(reclaimed["device"].get("name").is_none());
    assert!(reclaimed["device"].get("sealed_name").is_none());

    // Nothing the previous holder published is left for peers to encrypt to.
    let reclaimed_token = reclaimed["token"].as_str().unwrap();
    let status = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/keys/status")
                .header(header::AUTHORIZATION, format!("Bearer {reclaimed_token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(status.status(), StatusCode::OK);
    let status = json_body(status).await;
    assert_eq!(status["has_identity"], false);
    assert_eq!(status["signed_pre_key_id"], Value::Null);
    assert_eq!(status["otpk_count"], 0);
}

#[tokio::test]
async fn login_at_device_cap_signs_out_the_oldest_device_once_agreed() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping login_at_device_cap_signs_out_the_oldest_device_once_agreed: DATABASE_URL unavailable"
        );
        return;
    };

    let (username, password) = unique_user();
    let (oldest_token, _, oldest) = register_user(&app, &username, &password).await;
    for _ in 1..shroud_server::auth::MAX_DEVICES_PER_USER {
        let login = login_request(&app, &username, &password).await;
        assert_eq!(login.status(), StatusCode::OK);
    }
    let login_with = |replace: Value| {
        let body = json!({
            "username_hash": shroud_server::auth::username_hash_b64(&username),
            "password": password,
            "replace_device_id": replace,
        });
        app.clone().oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/login")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(body.to_string()))
                .expect("request"),
        )
    };

    // Every device is signed in: the refusal names the least recently active one.
    let refused = login_request(&app, &username, &password).await;
    assert_eq!(refused.status(), StatusCode::CONFLICT);
    let refused = json_body(refused).await;
    assert_eq!(refused["error"]["code"], "DEVICE_LIMIT");
    assert_eq!(refused["oldest_device"]["id"], oldest.as_str());
    assert!(refused["oldest_device"]["created_at"].is_string());
    assert!(refused["oldest_device"].get("sealed_name").is_none());
    // Every device is listed, least recently active first, for the user to pick another.
    let listed = refused["devices"].as_array().expect("devices");
    assert_eq!(
        listed.len() as i64,
        shroud_server::auth::MAX_DEVICES_PER_USER
    );
    assert_eq!(listed[0]["id"], oldest.as_str());

    // Names travel sealed: only a client whose phrase checked out can open them.
    let sealed = BASE64.encode([0x5A_u8; 156]);
    let named = authed(
        &app,
        "PUT",
        &format!("/api/v1/devices/{oldest}/name"),
        &oldest_token,
        Some(json!({ "sealed_name": sealed })),
    )
    .await;
    assert_eq!(named.status(), StatusCode::NO_CONTENT);
    let refused = json_body(login_request(&app, &username, &password).await).await;
    assert_eq!(refused["oldest_device"]["sealed_name"], sealed.as_str());
    assert_eq!(refused["devices"][0]["sealed_name"], sealed.as_str());
    // No device has published keys yet, so there is no identity to check a phrase against.
    assert!(refused.get("identity_key").is_none());

    // Once one has, the refusal carries the key the phrase must derive.
    let identity = BASE64.encode([0x42_u8; 32]);
    let bundle = authed(
        &app,
        "PUT",
        "/api/v1/keys/bundle",
        &oldest_token,
        Some(json!({
            "registration_id": 7,
            "identity_key": identity,
            "signed_pre_key": {
                "key_id": 1,
                "public_key": identity,
                "signature": BASE64.encode([0x42_u8; 64]),
            },
        })),
    )
    .await;
    assert_eq!(bundle.status(), StatusCode::NO_CONTENT);
    let refused = login_request(&app, &username, &password).await;
    assert_eq!(refused.status(), StatusCode::CONFLICT);
    let refused = json_body(refused).await;
    assert_eq!(refused["identity_key"], identity.as_str());

    // A device the user never agreed to (or one already gone) signs nobody out.
    let unknown = login_with(json!(Uuid::new_v4())).await.expect("response");
    assert_eq!(unknown.status(), StatusCode::CONFLICT);
    assert_eq!(
        json_body(unknown).await["oldest_device"]["id"],
        oldest.as_str()
    );

    // Agreed: the oldest device is removed and the login gets a fresh one.
    let replaced = login_with(json!(oldest)).await.expect("response");
    assert_eq!(replaced.status(), StatusCode::OK);
    let replaced = json_body(replaced).await;
    let new_device = replaced["device"]["id"].as_str().unwrap().to_string();
    assert_ne!(new_device, oldest);
    let new_token = replaced["token"].as_str().unwrap().to_string();

    // The signed-out device is told it was removed, so it wipes itself.
    let me = authed(&app, "GET", "/api/v1/auth/me", &oldest_token, None).await;
    assert_eq!(me.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(json_body(me).await["error"]["code"], "DEVICE_REMOVED");

    let list = authed(&app, "GET", "/api/v1/devices", &new_token, None).await;
    assert_eq!(list.status(), StatusCode::OK);
    let list = json_body(list).await;
    let ids: Vec<&str> = list["devices"]
        .as_array()
        .unwrap()
        .iter()
        .map(|device| device["id"].as_str().unwrap())
        .collect();
    assert_eq!(ids.len() as i64, shroud_server::auth::MAX_DEVICES_PER_USER);
    assert!(!ids.contains(&oldest.as_str()));
    assert!(ids.contains(&new_device.as_str()));

    // Retrying with the same, now removed, id asks again about the next oldest device.
    let again = login_with(json!(oldest)).await.expect("response");
    assert_eq!(again.status(), StatusCode::CONFLICT);
    let again = json_body(again).await;
    assert_ne!(again["oldest_device"]["id"], oldest.as_str());
}

/// Sends one authenticated request and returns the response.
#[tokio::test]
async fn device_names_are_kept_sealed_only() {
    let Some(app) = test_app().await else {
        eprintln!("skipping device_names_are_kept_sealed_only: DATABASE_URL unavailable");
        return;
    };
    let (username, password) = unique_user();
    let (phone_token, _, phone) = register_user(&app, &username, &password).await;
    let login = login_request(&app, &username, &password).await;
    assert_eq!(login.status(), StatusCode::OK);
    let browser_session = json_body(login).await;
    let browser_token = browser_session["token"].as_str().unwrap().to_string();
    let browser = browser_session["device"]["id"]
        .as_str()
        .unwrap()
        .to_string();

    // Unnamed until a client of the account seals a name.
    let list = json_body(authed(&app, "GET", "/api/v1/devices", &phone_token, None).await).await;
    for device in list["devices"].as_array().unwrap() {
        assert!(device.get("name").is_none());
        assert!(device.get("sealed_name").is_none());
    }

    let sealed = BASE64.encode((0..156).map(|i| i as u8).collect::<Vec<_>>());
    let put = authed(
        &app,
        "PUT",
        &format!("/api/v1/devices/{phone}/name"),
        &phone_token,
        Some(json!({ "sealed_name": sealed })),
    )
    .await;
    assert_eq!(put.status(), StatusCode::NO_CONTENT);

    // Another device of the account may name this one (a phone renaming a browser).
    let browser_sealed = BASE64.encode([7_u8; 156]);
    let put = authed(
        &app,
        "PUT",
        &format!("/api/v1/devices/{browser}/name"),
        &phone_token,
        Some(json!({ "sealed_name": browser_sealed })),
    )
    .await;
    assert_eq!(put.status(), StatusCode::NO_CONTENT);

    let list = json_body(authed(&app, "GET", "/api/v1/devices", &browser_token, None).await).await;
    let by_id = |id: &str| {
        list["devices"]
            .as_array()
            .unwrap()
            .iter()
            .find(|device| device["id"] == id)
            .cloned()
            .expect("device listed")
    };
    assert_eq!(by_id(&phone)["sealed_name"], sealed);
    assert_eq!(by_id(&browser)["sealed_name"], browser_sealed);

    let me = json_body(authed(&app, "GET", "/api/v1/auth/me", &phone_token, None).await).await;
    assert_eq!(me["device"]["sealed_name"], sealed);
    assert!(me["device"].get("name").is_none());

    // Signing in again on the same device keeps its name.
    let again = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/login")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username_hash": shroud_server::auth::username_hash_b64(username), "password": password, "device_id": browser })
                        .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(again.status(), StatusCode::OK);
    let again = json_body(again).await;
    assert_eq!(again["device"]["id"], browser);
    assert_eq!(again["device"]["sealed_name"], browser_sealed);
    let browser_token = again["token"].as_str().unwrap().to_string();

    // Not Base64, too short to be sealed, too long.
    for bad in [
        "not base64!".to_string(),
        BASE64.encode([1_u8; 27]),
        BASE64.encode([1_u8; 513]),
    ] {
        let put = authed(
            &app,
            "PUT",
            &format!("/api/v1/devices/{phone}/name"),
            &browser_token,
            Some(json!({ "sealed_name": bad })),
        )
        .await;
        assert_eq!(put.status(), StatusCode::BAD_REQUEST);
    }

    // Someone else's device, and a removed one, are not found.
    let (stranger, _) = unique_user();
    let (stranger_token, _, _) = register_user(&app, &stranger, &password).await;
    let put = authed(
        &app,
        "PUT",
        &format!("/api/v1/devices/{phone}/name"),
        &stranger_token,
        Some(json!({ "sealed_name": sealed })),
    )
    .await;
    assert_eq!(put.status(), StatusCode::NOT_FOUND);

    let removed = authed(
        &app,
        "DELETE",
        &format!("/api/v1/devices/{browser}"),
        &phone_token,
        None,
    )
    .await;
    assert_eq!(removed.status(), StatusCode::NO_CONTENT);
    let put = authed(
        &app,
        "PUT",
        &format!("/api/v1/devices/{browser}/name"),
        &phone_token,
        Some(json!({ "sealed_name": sealed })),
    )
    .await;
    assert_eq!(put.status(), StatusCode::NOT_FOUND);
}

async fn authed(
    app: &axum::Router,
    method: &str,
    uri: &str,
    token: &str,
    body: Option<Value>,
) -> axum::response::Response {
    let builder = Request::builder()
        .method(method)
        .uri(uri)
        .header(header::AUTHORIZATION, format!("Bearer {token}"));
    let request = match body {
        Some(body) => builder
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(body.to_string())),
        None => builder.body(Body::empty()),
    }
    .expect("request");
    app.clone().oneshot(request).await.expect("response")
}

#[tokio::test]
async fn removing_a_device_keeps_what_it_sent() {
    let Some(app) = test_app().await else {
        eprintln!("skipping removing_a_device_keeps_what_it_sent: DATABASE_URL unavailable");
        return;
    };

    let auth_request = |uri: &str, body: Value| {
        Request::builder()
            .method("POST")
            .uri(uri)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(body.to_string()))
            .expect("request")
    };

    // Alice on a phone and a laptop; Bob on one device.
    let (alice, password) = unique_user();
    let phone = app
        .clone()
        .oneshot(auth_request(
            "/api/v1/auth/register",
            json!({ "username_hash": shroud_server::auth::username_hash_b64(&alice), "password": password, "device_name": "Phone" }),
        ))
        .await
        .expect("response");
    assert_eq!(phone.status(), StatusCode::CREATED);
    let phone = json_body(phone).await;
    let phone_token = phone["token"].as_str().unwrap().to_string();
    let alice_id = phone["user"]["id"].as_str().unwrap().to_string();

    let laptop = app
        .clone()
        .oneshot(auth_request(
            "/api/v1/auth/login",
            json!({ "username_hash": shroud_server::auth::username_hash_b64(&alice), "password": password, "device_name": "Laptop" }),
        ))
        .await
        .expect("response");
    assert_eq!(laptop.status(), StatusCode::OK);
    let laptop = json_body(laptop).await;
    let laptop_token = laptop["token"].as_str().unwrap().to_string();
    let laptop_id = laptop["device"]["id"].as_str().unwrap().to_string();

    let (bob, bob_password) = unique_user();
    let bob = app
        .clone()
        .oneshot(auth_request(
            "/api/v1/auth/register",
            json!({ "username_hash": shroud_server::auth::username_hash_b64(&bob), "password": bob_password }),
        ))
        .await
        .expect("response");
    let bob = json_body(bob).await;
    let bob_token = bob["token"].as_str().unwrap().to_string();
    let bob_id = bob["user"]["id"].as_str().unwrap().to_string();

    let request = authed(
        &app,
        "POST",
        "/api/v1/contacts/requests",
        &phone_token,
        Some(json!({ "user_id": bob_id })),
    )
    .await;
    assert!(request.status().is_success());
    let accept = authed(
        &app,
        "POST",
        "/api/v1/contacts/requests",
        &bob_token,
        Some(json!({ "user_id": alice_id })),
    )
    .await;
    assert_eq!(accept.status(), StatusCode::OK);

    // The laptop sends a text and a photo; Bob's device acknowledges the text.
    let text = authed(
        &app,
        "POST",
        "/api/v1/messages",
        &laptop_token,
        Some(json!({
            "peer_user_id": bob_id,
            "client_message_id": Uuid::new_v4(),
            "content_type": "text",
            "ciphertext": BASE64.encode(b"sealed-from-laptop"),
        })),
    )
    .await;
    assert_eq!(text.status(), StatusCode::CREATED);
    let text_id = json_body(text).await["id"].as_str().unwrap().to_string();

    let upload = authed(
        &app,
        "POST",
        "/api/v1/media/uploads",
        &laptop_token,
        Some(json!({ "size_bytes": 1024, "content_type": "application/octet-stream" })),
    )
    .await;
    assert_eq!(upload.status(), StatusCode::CREATED);
    let media_id = json_body(upload).await["media_object_id"]
        .as_str()
        .unwrap()
        .to_string();
    let photo = authed(
        &app,
        "POST",
        "/api/v1/messages",
        &laptop_token,
        Some(json!({
            "peer_user_id": bob_id,
            "client_message_id": Uuid::new_v4(),
            "content_type": "media",
            "ciphertext": BASE64.encode(b"sealed-photo-envelope"),
            "media_object_id": media_id,
        })),
    )
    .await;
    assert_eq!(photo.status(), StatusCode::CREATED);
    let photo_id = json_body(photo).await["id"].as_str().unwrap().to_string();

    let delivered = authed(
        &app,
        "POST",
        &format!("/api/v1/messages/{text_id}/delivered"),
        &bob_token,
        None,
    )
    .await;
    assert_eq!(delivered.status(), StatusCode::NO_CONTENT);

    // The phone removes the laptop.
    let removed = authed(
        &app,
        "DELETE",
        &format!("/api/v1/devices/{laptop_id}"),
        &phone_token,
        None,
    )
    .await;
    assert_eq!(removed.status(), StatusCode::NO_CONTENT);

    // Both sides still see everything the laptop sent, with its sender device intact.
    for (token, peer) in [(&bob_token, &alice_id), (&phone_token, &bob_id)] {
        let list = authed(
            &app,
            "GET",
            &format!("/api/v1/messages?peer_user_id={peer}"),
            token,
            None,
        )
        .await;
        assert_eq!(list.status(), StatusCode::OK);
        let history = json_body(list).await;
        let messages = history["messages"].as_array().unwrap();
        let mut ids: Vec<&str> = messages.iter().map(|m| m["id"].as_str().unwrap()).collect();
        ids.sort_unstable();
        let mut expected = vec![text_id.as_str(), photo_id.as_str()];
        expected.sort_unstable();
        assert_eq!(ids, expected);
        assert!(messages.iter().all(|m| m["sender_device_id"] == laptop_id));
    }

    // The text keeps its delivered tick for the sender.
    let alice_view = authed(
        &app,
        "GET",
        &format!("/api/v1/messages?peer_user_id={bob_id}"),
        &phone_token,
        None,
    )
    .await;
    let alice_view = json_body(alice_view).await;
    let text_row = alice_view["messages"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["id"] == text_id.as_str())
        .unwrap()
        .clone();
    assert_eq!(text_row["delivered"], true);

    // The photo it uploaded still downloads.
    let download = authed(
        &app,
        "POST",
        &format!("/api/v1/media/{media_id}/download"),
        &bob_token,
        None,
    )
    .await;
    assert_eq!(download.status(), StatusCode::OK);

    // The laptop is gone: signed out, unlisted, not removable twice.
    let me = authed(&app, "GET", "/api/v1/auth/me", &laptop_token, None).await;
    assert_eq!(me.status(), StatusCode::UNAUTHORIZED);
    let devices = authed(&app, "GET", "/api/v1/devices", &phone_token, None).await;
    let devices = json_body(devices).await;
    let devices = devices["devices"].as_array().unwrap();
    assert_eq!(devices.len(), 1);
    assert_ne!(devices[0]["id"], laptop_id.as_str());
    let again = authed(
        &app,
        "DELETE",
        &format!("/api/v1/devices/{laptop_id}"),
        &phone_token,
        None,
    )
    .await;
    assert_eq!(again.status(), StatusCode::NOT_FOUND);

    // New messages no longer queue for it, and a login presenting its id gets a fresh device.
    let later = authed(
        &app,
        "POST",
        "/api/v1/messages",
        &bob_token,
        Some(json!({
            "peer_user_id": alice_id,
            "client_message_id": Uuid::new_v4(),
            "content_type": "text",
            "ciphertext": BASE64.encode(b"sealed-reply"),
        })),
    )
    .await;
    assert_eq!(later.status(), StatusCode::CREATED);

    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect(&std::env::var("DATABASE_URL").unwrap())
        .await
        .expect("pool");
    let laptop_uuid = Uuid::parse_str(&laptop_id).unwrap();
    let pending: i64 = sqlx::query_scalar(
        "SELECT COUNT(*)::bigint FROM message_deliveries WHERE device_id = $1 AND delivered_at IS NULL",
    )
    .bind(laptop_uuid)
    .fetch_one(&pool)
    .await
    .expect("pending deliveries");
    assert_eq!(pending, 0);

    let relogin = app
        .oneshot(auth_request(
            "/api/v1/auth/login",
            json!({ "username_hash": shroud_server::auth::username_hash_b64(&alice), "password": password, "device_id": laptop_id }),
        ))
        .await
        .expect("response");
    assert_eq!(relogin.status(), StatusCode::OK);
    let relogin = json_body(relogin).await;
    assert_ne!(relogin["device"]["id"], laptop_id.as_str());
}

/// Registers a user and returns (token, user id, device id).
async fn register_user(
    app: &axum::Router,
    username: &str,
    password: &str,
) -> (String, String, String) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username_hash": shroud_server::auth::username_hash_b64(username), "password": password }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::CREATED);
    let body = json_body(response).await;
    (
        body["token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device"]["id"].as_str().unwrap().to_string(),
    )
}

/// Mutual requests between (token, user id) pairs: the second one auto-accepts.
async fn become_contacts(app: &axum::Router, a: (&str, &str), b: (&str, &str)) {
    let request = authed(
        app,
        "POST",
        "/api/v1/contacts/requests",
        a.0,
        Some(json!({ "user_id": b.1 })),
    )
    .await;
    assert!(request.status().is_success());
    let accept = authed(
        app,
        "POST",
        "/api/v1/contacts/requests",
        b.0,
        Some(json!({ "user_id": a.1 })),
    )
    .await;
    assert_eq!(accept.status(), StatusCode::OK);
}

/// Sends a sealed text and returns the response.
async fn send_sealed(
    app: &axum::Router,
    token: &str,
    peer: &str,
    sealed: &[u8],
) -> axum::response::Response {
    authed(
        app,
        "POST",
        "/api/v1/messages",
        token,
        Some(json!({
            "peer_user_id": peer,
            "client_message_id": Uuid::new_v4(),
            "content_type": "text",
            "ciphertext": BASE64.encode(sealed),
        })),
    )
    .await
}

/// Sends a sealed text that must be accepted, and returns its message id.
async fn send_text(app: &axum::Router, token: &str, peer: &str, sealed: &[u8]) -> String {
    let response = send_sealed(app, token, peer, sealed).await;
    assert_eq!(response.status(), StatusCode::CREATED);
    json_body(response).await["id"]
        .as_str()
        .unwrap()
        .to_string()
}

/// Every realtime event queued for one device so far, oldest first.
fn drain_events(events: &mut tokio::sync::mpsc::Receiver<String>) -> Vec<Value> {
    let mut drained = Vec::new();
    while let Ok(payload) = events.try_recv() {
        drained.push(serde_json::from_str(&payload).expect("event json"));
    }
    drained
}

/// Sets a sealed reaction that must be accepted.
async fn react(app: &axum::Router, token: &str, message_id: &str, sealed: &[u8]) {
    let response = authed(
        app,
        "PUT",
        &format!("/api/v1/messages/{message_id}/reaction"),
        token,
        Some(json!({ "ciphertext": BASE64.encode(sealed) })),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
}

/// Reaction catch-up: every change in the chat with `peer` after `after_seq`.
async fn reaction_changes(app: &axum::Router, token: &str, peer: &str, after_seq: i64) -> Value {
    let response = authed(
        app,
        "GET",
        &format!("/api/v1/conversations/{peer}/reactions?after_seq={after_seq}"),
        token,
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    json_body(response).await
}

/// The caller's `GET /conversations` entry for the chat with `peer`.
async fn chat_entry(app: &axum::Router, token: &str, peer: &str) -> Value {
    let chats = json_body(authed(app, "GET", "/api/v1/conversations", token, None).await).await;
    chats["conversations"]
        .as_array()
        .unwrap()
        .iter()
        .find(|chat| chat["peer"]["id"] == peer)
        .cloned()
        .expect("chat listed")
}

#[tokio::test]
async fn deleting_an_account_deletes_each_chat_for_both() {
    let Some((app, state)) = test_app_and_state().await else {
        eprintln!(
            "skipping deleting_an_account_deletes_each_chat_for_both: DATABASE_URL unavailable"
        );
        return;
    };

    // Alice deletes her account. Bob keeps the default privacy setting; Carol lets contacts
    // clear chats for her.
    let (alice_name, password) = unique_user();
    let (alice, alice_id, alice_device) = register_user(&app, &alice_name, &password).await;
    let (bob_name, _) = unique_user();
    let (bob, bob_id, bob_device) = register_user(&app, &bob_name, &password).await;
    let (carol_name, _) = unique_user();
    let (carol, carol_id, carol_device) = register_user(&app, &carol_name, &password).await;

    let consent = authed(
        &app,
        "PUT",
        "/api/v1/privacy/settings",
        &carol,
        Some(json!({ "allow_peer_chat_delete": true })),
    )
    .await;
    assert_eq!(consent.status(), StatusCode::OK);
    become_contacts(&app, (&alice, &alice_id), (&bob, &bob_id)).await;
    become_contacts(&app, (&alice, &alice_id), (&carol, &carol_id)).await;

    // Bob's chat: a text and a photo from Alice, and his reply.
    let alice_text = send_text(&app, &alice, &bob_id, b"sealed-from-alice").await;
    let upload = authed(
        &app,
        "POST",
        "/api/v1/media/uploads",
        &alice,
        Some(json!({ "size_bytes": 1024, "content_type": "application/octet-stream" })),
    )
    .await;
    assert_eq!(upload.status(), StatusCode::CREATED);
    let media_id = json_body(upload).await["media_object_id"]
        .as_str()
        .unwrap()
        .to_string();
    let photo = authed(
        &app,
        "POST",
        "/api/v1/messages",
        &alice,
        Some(json!({
            "peer_user_id": bob_id,
            "client_message_id": Uuid::new_v4(),
            "content_type": "media",
            "ciphertext": BASE64.encode(b"sealed-photo-envelope"),
            "media_object_id": media_id,
        })),
    )
    .await;
    assert_eq!(photo.status(), StatusCode::CREATED);
    let alice_photo = json_body(photo).await["id"].as_str().unwrap().to_string();
    let bob_reply = send_text(&app, &bob, &alice_id, b"sealed-from-bob").await;

    // Carol's chat, a note in Alice's Saved Messages, and Alice ringing Bob.
    let to_carol = send_text(&app, &alice, &carol_id, b"sealed-to-carol").await;
    let from_carol = send_text(&app, &carol, &alice_id, b"sealed-from-carol").await;
    let note = send_text(&app, &alice, &alice_id, b"sealed-note").await;
    let call = authed(
        &app,
        "POST",
        "/api/v1/calls",
        &alice,
        Some(json!({ "peer_user_id": bob_id, "modality": "voice", "protocol": 2 })),
    )
    .await;
    assert_eq!(call.status(), StatusCode::CREATED);
    let call_id = json_body(call).await["id"].as_str().unwrap().to_string();

    // Reactions both ways in both chats, Bob's on his own reply, and Alice's on her note. Each
    // peer's devices have caught up to here.
    react(&app, &bob, &alice_text, b"sealed-bob-on-alice").await;
    react(&app, &bob, &bob_reply, b"sealed-bob-on-bob").await;
    react(&app, &alice, &bob_reply, b"sealed-alice-on-bob").await;
    react(&app, &carol, &to_carol, b"sealed-carol-on-alice").await;
    react(&app, &alice, &from_carol, b"sealed-alice-on-carol").await;
    react(&app, &alice, &note, b"sealed-alice-on-note").await;
    let bob_cursor = reaction_changes(&app, &bob, &alice_id, 0).await["next_seq"]
        .as_i64()
        .unwrap();
    let carol_cursor = reaction_changes(&app, &carol, &alice_id, 0).await["next_seq"]
        .as_i64()
        .unwrap();
    assert_eq!(
        chat_entry(&app, &bob, &alice_id).await["unseen_reactions"],
        1
    );

    let parse = |id: &str| Uuid::parse_str(id).unwrap();
    let mut bob_events = state
        .realtime
        .subscribe(parse(&bob_id), parse(&bob_device), Uuid::new_v4())
        .await
        .expect("subscribe bob")
        .events;
    let mut carol_events = state
        .realtime
        .subscribe(parse(&carol_id), parse(&carol_device), Uuid::new_v4())
        .await
        .expect("subscribe carol")
        .events;

    let deleted = authed(
        &app,
        "DELETE",
        "/api/v1/auth/account",
        &alice,
        Some(json!({ "password": password })),
    )
    .await;
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);

    // Bob keeps his side: his reply as sent with only his own reaction left, and Alice's
    // messages as tombstones without reactions that still name her account and device (both
    // apps require those ids).
    let history = authed(
        &app,
        "GET",
        &format!("/api/v1/messages?peer_user_id={alice_id}"),
        &bob,
        None,
    )
    .await;
    assert_eq!(history.status(), StatusCode::OK);
    let history = json_body(history).await;
    let conversation_id = history["conversation_id"].as_str().unwrap().to_string();
    let messages = history["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 3);
    for message in messages {
        let id = message["id"].as_str().unwrap();
        if id == bob_reply {
            assert_eq!(message["deleted_for_everyone"], false);
            assert_eq!(message["ciphertext"], BASE64.encode(b"sealed-from-bob"));
            let reactions = message["reactions"].as_array().unwrap();
            assert_eq!(reactions.len(), 1);
            assert_eq!(reactions[0]["user_id"], bob_id.as_str());
            assert_eq!(
                reactions[0]["ciphertext"],
                BASE64.encode(b"sealed-bob-on-bob")
            );
            continue;
        }
        assert!(
            id == alice_text || id == alice_photo,
            "unexpected message {id}"
        );
        assert_eq!(message["deleted_for_everyone"], true);
        assert_eq!(message["ciphertext"], Value::Null);
        assert!(message.get("media_object_id").is_none());
        assert!(message.get("reactions").is_none());
        assert_eq!(message["sender_user_id"], alice_id.as_str());
        assert_eq!(message["sender_device_id"], alice_device.as_str());
    }

    // His catch-up reports the reactions the deletion cleared, each with a new seq and no
    // ciphertext: his own on Alice's text, now a tombstone, and hers on his reply. His reaction
    // on his reply did not change.
    let caught_up = reaction_changes(&app, &bob, &alice_id, bob_cursor).await;
    let changes = caught_up["reactions"].as_array().unwrap();
    let mut cleared: Vec<(&str, &str)> = changes
        .iter()
        .map(|change| {
            assert_eq!(change["ciphertext"], Value::Null, "still sealed: {change}");
            (
                change["message_id"].as_str().unwrap(),
                change["user_id"].as_str().unwrap(),
            )
        })
        .collect();
    cleared.sort_unstable();
    let mut expected = vec![
        (alice_text.as_str(), bob_id.as_str()),
        (bob_reply.as_str(), alice_id.as_str()),
    ];
    expected.sort_unstable();
    assert_eq!(cleared, expected);
    let seqs: Vec<i64> = changes
        .iter()
        .map(|change| change["seq"].as_i64().unwrap())
        .collect();
    assert!(seqs.windows(2).all(|pair| pair[0] < pair[1]), "{seqs:?}");
    assert_eq!(caught_up["next_seq"], seqs[seqs.len() - 1]);
    assert_eq!(caught_up["has_more"], false);

    let chats = authed(&app, "GET", "/api/v1/conversations", &bob, None).await;
    assert_eq!(chats.status(), StatusCode::OK);
    let chats = json_body(chats).await;
    let chat = chats["conversations"]
        .as_array()
        .unwrap()
        .iter()
        .find(|chat| chat["id"] == conversation_id.as_str())
        .expect("Bob still lists the chat");
    assert_eq!(chat["peer"]["id"], alice_id.as_str());
    assert!(chat["peer"].get("username").is_none());
    assert_eq!(chat["peer"]["deleted"], true);
    // Her reaction left his heart badge, and the chat says catch-up has something new.
    assert_eq!(chat["unseen_reactions"], 0);
    assert_eq!(chat["reaction_seq"], caught_up["next_seq"]);

    let events = drain_events(&mut bob_events);
    let chat_event = events
        .iter()
        .find(|event| event["type"] == "conversation.deleted")
        .expect("conversation.deleted for Bob");
    assert_eq!(chat_event["conversation_id"], conversation_id.as_str());
    assert_eq!(chat_event["user_id"], alice_id.as_str());
    assert_eq!(chat_event["peer_user_id"], bob_id.as_str());
    assert_eq!(chat_event["scope"], "everyone");
    assert_eq!(chat_event["cleared_for_peer"], false);
    assert!(events.iter().any(|event| event["type"] == "contact.removed"
        && event["user_id"] == alice_id.as_str()
        && event["peer_user_id"] == bob_id.as_str()));
    // Her ringing call stops ringing on Bob's side, as if she had hung up.
    let call_event = events
        .iter()
        .find(|event| event["type"] == "call.ended")
        .expect("call.ended for Bob");
    assert_eq!(call_event["call"]["id"], call_id.as_str());
    assert_eq!(call_event["call"]["status"], "cancelled");

    // Alice is no longer a contact of Bob's, he can't write to her, and her photo is gone.
    let contacts = json_body(authed(&app, "GET", "/api/v1/contacts", &bob, None).await).await;
    assert!(contacts["contacts"].as_array().unwrap().is_empty());
    let late = send_sealed(&app, &bob, &alice_id, b"sealed-too-late").await;
    assert_eq!(late.status(), StatusCode::FORBIDDEN);
    let download = authed(
        &app,
        "POST",
        &format!("/api/v1/media/{media_id}/download"),
        &bob,
        None,
    )
    .await;
    assert_eq!(download.status(), StatusCode::NOT_FOUND);

    // Carol allowed contacts to clear chats for her, so her copy went too.
    let carol_history = authed(
        &app,
        "GET",
        &format!("/api/v1/messages?peer_user_id={alice_id}"),
        &carol,
        None,
    )
    .await;
    assert_eq!(carol_history.status(), StatusCode::OK);
    let carol_history = json_body(carol_history).await;
    assert!(carol_history["messages"].as_array().unwrap().is_empty());
    let carol_chats =
        json_body(authed(&app, "GET", "/api/v1/conversations", &carol, None).await).await;
    assert!(
        carol_chats["conversations"]
            .as_array()
            .unwrap()
            .iter()
            .all(|chat| chat["peer"]["id"] != alice_id.as_str())
    );
    let events = drain_events(&mut carol_events);
    let chat_event = events
        .iter()
        .find(|event| event["type"] == "conversation.deleted")
        .expect("conversation.deleted for Carol");
    assert_eq!(chat_event["user_id"], alice_id.as_str());
    assert_eq!(chat_event["cleared_for_peer"], true);
    // The reactions went with the purged messages, so her catch-up has nothing to report, not
    // even a removal.
    let carol_caught_up = reaction_changes(&app, &carol, &alice_id, carol_cursor).await;
    assert!(carol_caught_up["reactions"].as_array().unwrap().is_empty());
    assert_eq!(carol_caught_up["next_seq"], carol_cursor);

    // On the server: no ciphertext of Alice's left, no sealed reaction of hers or on anything
    // she sent, Carol's chat and Alice's notes are empty, and her upload and call are gone.
    let alice_uuid = parse(&alice_id);
    let count = |sql: &'static str, id: Uuid| {
        let pool = state.pool.clone();
        async move {
            sqlx::query_scalar::<_, i64>(sql)
                .bind(id)
                .fetch_one(&pool)
                .await
                .expect(sql)
        }
    };
    assert_eq!(
        count(
            "SELECT COUNT(*)::bigint FROM messages WHERE sender_user_id = $1 AND ciphertext IS NOT NULL",
            alice_uuid,
        )
        .await,
        0
    );
    assert_eq!(
        count(
            "SELECT COUNT(*)::bigint FROM message_reactions WHERE user_id = $1 AND ciphertext IS NOT NULL",
            alice_uuid,
        )
        .await,
        0
    );
    assert_eq!(
        count(
            "SELECT COUNT(*)::bigint FROM message_reactions r
             INNER JOIN messages m ON m.id = r.message_id
             WHERE m.sender_user_id = $1 AND r.ciphertext IS NOT NULL",
            alice_uuid,
        )
        .await,
        0
    );
    assert_eq!(
        count(
            "SELECT COUNT(*)::bigint FROM reaction_reads WHERE user_id = $1",
            alice_uuid,
        )
        .await,
        0
    );
    let carol_conversation = carol_history["conversation_id"].as_str().unwrap();
    assert_eq!(
        count(
            "SELECT COUNT(*)::bigint FROM messages WHERE conversation_id = $1",
            parse(carol_conversation),
        )
        .await,
        0
    );
    assert_eq!(
        count(
            "SELECT COUNT(*)::bigint FROM message_reactions WHERE conversation_id = $1",
            parse(carol_conversation),
        )
        .await,
        0
    );
    assert_eq!(
        count(
            "SELECT COUNT(*)::bigint FROM conversations WHERE user_a_id = $1 AND user_b_id = $1",
            alice_uuid,
        )
        .await,
        0
    );
    assert_eq!(
        count(
            "SELECT COUNT(*)::bigint FROM media_objects WHERE uploader_user_id = $1",
            alice_uuid,
        )
        .await,
        0
    );
    assert_eq!(
        count(
            "SELECT COUNT(*)::bigint FROM calls WHERE caller_user_id = $1 OR callee_user_id = $1",
            alice_uuid,
        )
        .await,
        0
    );

    // Once Bob clears the chat too, nobody can see it and the server drops it.
    let cleared = authed(
        &app,
        "DELETE",
        &format!("/api/v1/conversations/{alice_id}?scope=me"),
        &bob,
        None,
    )
    .await;
    assert_eq!(cleared.status(), StatusCode::OK);
    assert_eq!(
        count(
            "SELECT COUNT(*)::bigint FROM messages WHERE conversation_id = $1",
            parse(&conversation_id),
        )
        .await,
        0
    );
}

#[tokio::test]
async fn a_send_racing_its_device_revocation_is_refused() {
    let Some((app, state)) = test_app_and_state().await else {
        eprintln!(
            "skipping a_send_racing_its_device_revocation_is_refused: DATABASE_URL unavailable"
        );
        return;
    };

    let (alice_name, password) = unique_user();
    let (alice, alice_id, alice_device) = register_user(&app, &alice_name, &password).await;
    let (bob_name, _) = unique_user();
    let (bob, bob_id, _) = register_user(&app, &bob_name, &password).await;
    become_contacts(&app, (&alice, &alice_id), (&bob, &bob_id)).await;
    let alice_device = Uuid::parse_str(&alice_device).unwrap();

    // Account deletion revokes the device inside its transaction; hold one open like it.
    let mut revoke = state.pool.begin().await.expect("begin");
    let revoke_pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
        .fetch_one(&mut *revoke)
        .await
        .expect("pid");
    sqlx::query("UPDATE devices SET revoked_at = now() WHERE id = $1")
        .bind(alice_device)
        .execute(&mut *revoke)
        .await
        .expect("revoke");

    // The send authenticates (the revocation isn't committed) and then queues on the device row.
    let send = tokio::spawn({
        let app = app.clone();
        async move {
            send_sealed(&app, &alice, &bob_id, b"sealed-in-flight")
                .await
                .status()
        }
    });
    wait_until_blocked_by(&state.pool, revoke_pid, "the send").await;
    revoke.commit().await.expect("commit revoke");

    assert_eq!(send.await.expect("send task"), StatusCode::UNAUTHORIZED);
    let sent: i64 =
        sqlx::query_scalar("SELECT COUNT(*)::bigint FROM messages WHERE sender_device_id = $1")
            .bind(alice_device)
            .fetch_one(&state.pool)
            .await
            .expect("messages");
    assert_eq!(sent, 0);
}

#[tokio::test]
async fn a_reaction_racing_its_device_revocation_is_refused() {
    let Some((app, state)) = test_app_and_state().await else {
        eprintln!(
            "skipping a_reaction_racing_its_device_revocation_is_refused: DATABASE_URL unavailable"
        );
        return;
    };

    let (alice_name, password) = unique_user();
    let (alice, alice_id, alice_device) = register_user(&app, &alice_name, &password).await;
    let (bob_name, _) = unique_user();
    let (bob, bob_id, _) = register_user(&app, &bob_name, &password).await;
    become_contacts(&app, (&alice, &alice_id), (&bob, &bob_id)).await;
    let message = send_text(&app, &bob, &alice_id, b"sealed-from-bob").await;

    // As for a send: account deletion revokes the device before it clears the account's
    // reactions, and nothing else stops one that lands after the clear.
    let mut revoke = state.pool.begin().await.expect("begin");
    let revoke_pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
        .fetch_one(&mut *revoke)
        .await
        .expect("pid");
    sqlx::query("UPDATE devices SET revoked_at = now() WHERE id = $1")
        .bind(Uuid::parse_str(&alice_device).unwrap())
        .execute(&mut *revoke)
        .await
        .expect("revoke");

    let reaction = tokio::spawn({
        let app = app.clone();
        async move {
            authed(
                &app,
                "PUT",
                &format!("/api/v1/messages/{message}/reaction"),
                &alice,
                Some(json!({ "ciphertext": BASE64.encode(b"sealed-in-flight") })),
            )
            .await
            .status()
        }
    });
    wait_until_blocked_by(&state.pool, revoke_pid, "the reaction").await;
    revoke.commit().await.expect("commit revoke");

    assert_eq!(
        reaction.await.expect("reaction task"),
        StatusCode::UNAUTHORIZED
    );
    let reactions: i64 =
        sqlx::query_scalar("SELECT COUNT(*)::bigint FROM message_reactions WHERE user_id = $1")
            .bind(Uuid::parse_str(&alice_id).unwrap())
            .fetch_one(&state.pool)
            .await
            .expect("reactions");
    assert_eq!(reactions, 0);
}

/// Waits until a request queues behind the locks of the transaction on backend `pid`.
async fn wait_until_blocked_by(pool: &sqlx::PgPool, pid: i32, what: &str) {
    let mut polls = 0;
    loop {
        let waiting: i64 = sqlx::query_scalar(
            "SELECT COUNT(*)::bigint FROM pg_stat_activity WHERE $1 = ANY(pg_blocking_pids(pid))",
        )
        .bind(pid)
        .fetch_one(pool)
        .await
        .expect("blocked sessions");
        if waiting > 0 {
            return;
        }
        polls += 1;
        assert!(polls < 300, "{what} never waited for the device row");
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
}
