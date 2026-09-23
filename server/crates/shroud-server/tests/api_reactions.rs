//! Integration tests for message reactions (`/messages/:id/reaction`, catch-up, history).

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use http_body_util::BodyExt;
use serde_json::{Value, json};
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
    token: &str,
    body: Option<Value>,
) -> (StatusCode, Value) {
    let mut builder = Request::builder()
        .method(method)
        .uri(uri)
        .header(header::AUTHORIZATION, format!("Bearer {token}"));
    let body = match body {
        Some(value) => {
            builder = builder.header(header::CONTENT_TYPE, "application/json");
            Body::from(value.to_string())
        }
        None => Body::empty(),
    };
    let response = app
        .clone()
        .oneshot(builder.body(body).expect("request"))
        .await
        .expect("response");
    let status = response.status();
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    let value = if bytes.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&bytes).expect("json")
    };
    (status, value)
}

async fn register(app: &axum::Router) -> (String, String) {
    let id = &Uuid::new_v4().simple().to_string()[..12];
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username": format!("r_{id}"), "password": "correct-horse-battery" })
                        .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::CREATED);
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    let body: Value = serde_json::from_slice(&bytes).expect("json");
    (
        body["token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
    )
}

async fn become_contacts(app: &axum::Router, a: &(String, String), b: &(String, String)) {
    let (status, _) = call(
        app,
        "POST",
        "/api/v1/contacts/requests",
        &a.0,
        Some(json!({ "user_id": b.1 })),
    )
    .await;
    assert!(status.is_success());
    let (status, _) = call(
        app,
        "POST",
        "/api/v1/contacts/requests",
        &b.0,
        Some(json!({ "user_id": a.1 })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
}

async fn send(app: &axum::Router, from: &(String, String), to: &str, content_type: &str) -> String {
    let (status, body) = call(
        app,
        "POST",
        "/api/v1/messages",
        &from.0,
        Some(json!({
            "peer_user_id": to,
            "client_message_id": Uuid::new_v4(),
            "content_type": content_type,
            "ciphertext": BASE64.encode(b"sealed"),
        })),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    body["id"].as_str().unwrap().to_string()
}

async fn react(
    app: &axum::Router,
    who: &(String, String),
    message_id: &str,
    blob: &[u8],
) -> (StatusCode, Value) {
    call(
        app,
        "PUT",
        &format!("/api/v1/messages/{message_id}/reaction"),
        &who.0,
        Some(json!({ "ciphertext": BASE64.encode(blob) })),
    )
    .await
}

async fn changes(app: &axum::Router, who: &(String, String), peer: &str, after: i64) -> Value {
    let (status, body) = call(
        app,
        "GET",
        &format!("/api/v1/conversations/{peer}/reactions?after_seq={after}"),
        &who.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body
}

async fn history(app: &axum::Router, who: &(String, String), peer: &str) -> Value {
    let (status, body) = call(
        app,
        "GET",
        &format!("/api/v1/messages?peer_user_id={peer}"),
        &who.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body
}

fn find_message<'a>(page: &'a Value, id: &str) -> &'a Value {
    page["messages"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["id"] == id)
        .expect("message in page")
}

#[tokio::test]
async fn set_replace_remove_and_catch_up() {
    let Some(app) = test_app().await else {
        eprintln!("skipping set_replace_remove_and_catch_up: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let message = send(&app, &a, &b.1, "text").await;

    let start = history(&app, &b, &a.1).await["reaction_seq"]
        .as_i64()
        .expect("newest page carries reaction_seq");

    // B reacts, then replaces the reaction: one row, a newer seq.
    let (status, first) = react(&app, &b, &message, b"heart").await;
    assert_eq!(status, StatusCode::OK, "{first}");
    assert_eq!(first["user_id"], b.1);
    let (status, second) = react(&app, &b, &message, b"fire").await;
    assert_eq!(status, StatusCode::OK);
    assert!(second["seq"].as_i64() > first["seq"].as_i64());

    // A reacts too.
    let (status, _) = react(&app, &a, &message, b"thumbs").await;
    assert_eq!(status, StatusCode::OK);

    let page = history(&app, &a, &b.1).await;
    let reactions = find_message(&page, &message)["reactions"]
        .as_array()
        .expect("reactions embedded");
    assert_eq!(reactions.len(), 2);
    assert_eq!(reactions[0]["ciphertext"], BASE64.encode(b"fire"));
    assert_eq!(reactions[1]["user_id"], a.1);

    // B takes it back; a second removal is a no-op.
    let (status, removed) = call(
        &app,
        "DELETE",
        &format!("/api/v1/messages/{message}/reaction"),
        &b.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(removed["ciphertext"].is_null());
    let (status, _) = call(
        &app,
        "DELETE",
        &format!("/api/v1/messages/{message}/reaction"),
        &b.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    let page = history(&app, &a, &b.1).await;
    assert_eq!(
        find_message(&page, &message)["reactions"]
            .as_array()
            .unwrap()
            .len(),
        1
    );

    // Catch-up from before any of it: one row per user, the latest state, oldest seq first.
    let caught_up = changes(&app, &a, &b.1, start).await;
    let rows = caught_up["reactions"].as_array().unwrap();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0]["user_id"], a.1);
    assert_eq!(rows[1]["user_id"], b.1);
    assert!(rows[1]["ciphertext"].is_null(), "removal is reported");
    assert_eq!(caught_up["next_seq"], rows[1]["seq"]);

    // Nothing new after the cursor.
    let next = caught_up["next_seq"].as_i64().unwrap();
    let empty = changes(&app, &a, &b.1, next).await;
    assert!(empty["reactions"].as_array().unwrap().is_empty());
    assert_eq!(empty["next_seq"], next);
}

#[tokio::test]
async fn reaction_access_rules() {
    let Some(app) = test_app().await else {
        eprintln!("skipping reaction_access_rules: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    let outsider = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let message = send(&app, &a, &b.1, "text").await;

    let (status, _) = react(&app, &outsider, &message, b"heart").await;
    assert_eq!(status, StatusCode::NOT_FOUND, "outsiders cannot tell it exists");
    let (status, _) = react(&app, &b, &Uuid::new_v4().to_string(), b"heart").await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/messages/{message}/reaction"),
        &b.0,
        Some(json!({ "ciphertext": "not base64!" })),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _) = react(&app, &b, &message, &[7u8; 4 * 1024 + 1]).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _) = react(&app, &b, &message, b"").await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    let annotation = send(&app, &a, &b.1, "annotation").await;
    let (status, _) = react(&app, &b, &annotation, b"heart").await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // Saved Messages: reacting to your own note works.
    let note = send(&app, &a, &a.1, "text").await;
    let (status, _) = react(&app, &a, &note, b"star").await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn delete_for_everyone_clears_reactions() {
    let Some(app) = test_app().await else {
        eprintln!("skipping delete_for_everyone_clears_reactions: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let message = send(&app, &a, &b.1, "text").await;

    let (status, set) = react(&app, &b, &message, b"heart").await;
    assert_eq!(status, StatusCode::OK);
    let cursor = set["seq"].as_i64().unwrap();

    let (status, _) = call(
        &app,
        "DELETE",
        &format!("/api/v1/messages/{message}?scope=everyone"),
        &a.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    // The removal reaches devices that only knew the reaction.
    let caught_up = changes(&app, &b, &a.1, cursor).await;
    let rows = caught_up["reactions"].as_array().unwrap();
    assert_eq!(rows.len(), 1);
    assert!(rows[0]["ciphertext"].is_null());

    let page = history(&app, &b, &a.1).await;
    assert!(find_message(&page, &message).get("reactions").is_none());

    let (status, _) = react(&app, &b, &message, b"heart").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}
