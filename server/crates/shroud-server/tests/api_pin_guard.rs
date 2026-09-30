//! Integration tests for the PIN guard: the pepper only leaves for the right auth key, wrong
//! keys are counted, and the guard is gone after too many of them or a logout.

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use shroud_server::routes;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

async fn test_app() -> Option<axum::Router> {
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
    Some(
        axum::Router::new()
            .merge(routes::router())
            .with_state(shroud_server::state::AppState::for_integration_tests(pool)),
    )
}

async fn call(
    app: &axum::Router,
    method: &str,
    uri: &str,
    token: Option<&str>,
    body: Value,
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(uri)
        .header(header::CONTENT_TYPE, "application/json");
    if let Some(token) = token {
        request = request.header(header::AUTHORIZATION, format!("Bearer {token}"));
    }
    let response = app
        .clone()
        .oneshot(request.body(Body::from(body.to_string())).expect("request"))
        .await
        .expect("response");
    let status = response.status();
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    let json = if bytes.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&bytes).expect("json")
    };
    (status, json)
}

async fn register(app: &axum::Router) -> String {
    let username = format!("g_{}", &Uuid::new_v4().simple().to_string()[..12]);
    let (status, body) = call(
        app,
        "POST",
        "/api/v1/auth/register",
        None,
        json!({ "username": username, "password": "correct-horse-battery" }),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    body["token"].as_str().unwrap().to_owned()
}

async fn create_guard(app: &axum::Router, token: &str, auth_key: &[u8; 32]) -> (String, String) {
    let verifier = STANDARD.encode(Sha256::digest(auth_key));
    let (status, body) = call(
        app,
        "POST",
        "/api/v1/pin-guard",
        Some(token),
        json!({ "verifier": verifier }),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    (
        body["guard_id"].as_str().unwrap().to_owned(),
        body["pepper"].as_str().unwrap().to_owned(),
    )
}

async fn unlock(app: &axum::Router, guard_id: &str, auth_key: &[u8; 32]) -> (StatusCode, Value) {
    call(
        app,
        "POST",
        "/api/v1/pin-guard/unlock",
        None,
        json!({ "guard_id": guard_id, "auth_key": STANDARD.encode(auth_key) }),
    )
    .await
}

#[tokio::test]
async fn pepper_only_for_the_right_key_and_gone_after_ten_wrong_ones() {
    let Some(app) = test_app().await else {
        eprintln!("skipping pin guard test: no DATABASE_URL");
        return;
    };
    let token = register(&app).await;
    let right = [7_u8; 32];
    let wrong = [9_u8; 32];
    let (guard_id, pepper) = create_guard(&app, &token, &right).await;

    let (status, body) = unlock(&app, &guard_id, &right).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["pepper"], pepper);

    // A success resets the counter, so nine misses, a hit and nine more still leave it alive.
    for left in (1..10).rev() {
        let (status, body) = unlock(&app, &guard_id, &wrong).await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        assert_eq!(body["error"]["code"], "PIN_INCORRECT");
        assert!(
            body["error"]["message"]
                .as_str()
                .unwrap()
                .contains(&left.to_string())
        );
    }
    assert_eq!(unlock(&app, &guard_id, &right).await.0, StatusCode::OK);
    for _ in 0..9 {
        assert_eq!(
            unlock(&app, &guard_id, &wrong).await.0,
            StatusCode::FORBIDDEN
        );
    }

    // The tenth miss in a row deletes the pepper; the right key cannot bring it back.
    let (status, body) = unlock(&app, &guard_id, &wrong).await;
    assert_eq!(status, StatusCode::GONE);
    assert_eq!(body["error"]["code"], "PIN_GUARD_GONE");
    assert_eq!(unlock(&app, &guard_id, &right).await.0, StatusCode::GONE);
}

#[tokio::test]
async fn new_pin_replaces_the_guard_and_logout_deletes_it() {
    let Some(app) = test_app().await else {
        eprintln!("skipping pin guard test: no DATABASE_URL");
        return;
    };
    let token = register(&app).await;
    let first = [1_u8; 32];
    let second = [2_u8; 32];
    let (old_id, old_pepper) = create_guard(&app, &token, &first).await;
    let (new_id, new_pepper) = create_guard(&app, &token, &second).await;
    assert_ne!(old_id, new_id);
    assert_ne!(old_pepper, new_pepper);
    assert_eq!(unlock(&app, &old_id, &first).await.0, StatusCode::GONE);
    assert_eq!(unlock(&app, &new_id, &second).await.0, StatusCode::OK);

    let (status, _) = call(
        &app,
        "POST",
        "/api/v1/auth/logout",
        Some(&token),
        Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_eq!(unlock(&app, &new_id, &second).await.0, StatusCode::GONE);
}

#[tokio::test]
async fn abandoning_a_guard_needs_no_session_and_the_pin_stops_working() {
    let Some(app) = test_app().await else {
        eprintln!("skipping pin guard test: no DATABASE_URL");
        return;
    };
    let token = register(&app).await;
    let key = [4_u8; 32];
    let (guard_id, _) = create_guard(&app, &token, &key).await;

    let (status, _) = call(
        &app,
        "POST",
        "/api/v1/pin-guard/abandon",
        None,
        json!({ "guard_id": guard_id }),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_eq!(unlock(&app, &guard_id, &key).await.0, StatusCode::GONE);

    // Already gone, and an id that was never a guard: both are the same answer.
    let (again, _) = call(
        &app,
        "POST",
        "/api/v1/pin-guard/abandon",
        None,
        json!({ "guard_id": guard_id }),
    )
    .await;
    assert_eq!(again, StatusCode::NO_CONTENT);
    let (missing, _) = call(
        &app,
        "POST",
        "/api/v1/pin-guard/abandon",
        None,
        json!({ "guard_id": Uuid::new_v4() }),
    )
    .await;
    assert_eq!(missing, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn creating_a_guard_needs_a_session() {
    let Some(app) = test_app().await else {
        eprintln!("skipping pin guard test: no DATABASE_URL");
        return;
    };
    let verifier = STANDARD.encode([0_u8; 32]);
    let (status, _) = call(
        &app,
        "POST",
        "/api/v1/pin-guard",
        None,
        json!({ "verifier": verifier }),
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}
