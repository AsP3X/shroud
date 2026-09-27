//! Integration tests for whole-chat deletion and the peer-consent privacy flag.

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
            .with_state(test_state(pool)),
    )
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
    let id = &Uuid::new_v4().simple().to_string()[..12];
    (format!("c_{id}"), "correct-horse-battery".into())
}

async fn register(app: &axum::Router) -> (String, String) {
    let (username, password) = unique_user();
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username": username, "password": password }).to_string(),
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
    )
}

/// Mutual requests: the second one auto-accepts, leaving both sides as contacts.
async fn become_contacts(
    app: &axum::Router,
    token_a: &str,
    user_a: &str,
    token_b: &str,
    user_b: &str,
) {
    app.clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/contacts/requests")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "user_id": user_b }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    let accepted = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/contacts/requests")
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "user_id": user_a }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(accepted.status(), StatusCode::OK);
}

async fn send_text(app: &axum::Router, token: &str, peer: &str, body: &str) -> StatusCode {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/messages")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "peer_user_id": peer,
                        "client_message_id": Uuid::new_v4(),
                        "content_type": "text",
                        "ciphertext": BASE64.encode(body.as_bytes()),
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    response.status()
}

async fn history(app: &axum::Router, token: &str, peer: &str) -> Vec<Value> {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/messages?peer_user_id={peer}"))
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::OK);
    json_body(response).await["messages"]
        .as_array()
        .cloned()
        .unwrap_or_default()
}

async fn still_contacts(app: &axum::Router, token: &str, peer: &str) -> bool {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/contacts")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::OK);
    json_body(response).await["contacts"]
        .as_array()
        .map(|rows| rows.iter().any(|row| row["user_id"] == json!(peer)))
        .unwrap_or(false)
}

async fn conversations(app: &axum::Router, token: &str) -> Vec<Value> {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/conversations")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::OK);
    json_body(response).await["conversations"]
        .as_array()
        .cloned()
        .unwrap_or_default()
}

async fn delete_chat(
    app: &axum::Router,
    token: &str,
    peer: &str,
    scope: &str,
) -> (StatusCode, Value) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri(format!("/api/v1/conversations/{peer}?scope={scope}"))
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let status = response.status();
    if status == StatusCode::OK {
        (status, json_body(response).await)
    } else {
        (status, Value::Null)
    }
}

async fn set_allow_peer_chat_delete(app: &axum::Router, token: &str, allow: bool) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/privacy/settings")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "allow_peer_chat_delete": allow }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(json_body(response).await["allow_peer_chat_delete"], allow);
}

#[tokio::test]
async fn privacy_settings_default_off_and_round_trip() {
    let Some(app) = test_app().await else {
        eprintln!("skipping privacy_settings_default_off_and_round_trip: no DATABASE_URL");
        return;
    };
    let (token, _user) = register(&app).await;

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/privacy/settings")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::OK);
    // Nobody may wipe your history unless you opted in.
    assert_eq!(json_body(response).await["allow_peer_chat_delete"], false);

    set_allow_peer_chat_delete(&app, &token, true).await;

    let after = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/privacy/settings")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(json_body(after).await["allow_peer_chat_delete"], true);
}

#[tokio::test]
async fn delete_chat_for_me_leaves_peer_untouched() {
    let Some(app) = test_app().await else {
        eprintln!("skipping delete_chat_for_me_leaves_peer_untouched: no DATABASE_URL");
        return;
    };
    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    assert_eq!(
        send_text(&app, &token_a, &user_b, "from-a").await,
        StatusCode::CREATED
    );
    assert_eq!(
        send_text(&app, &token_b, &user_a, "from-b").await,
        StatusCode::CREATED
    );

    let (status, body) = delete_chat(&app, &token_a, &user_b, "me").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["cleared_for_me"], true);
    assert_eq!(body["cleared_for_peer"], false);
    assert_eq!(body["contact_removed"], false);

    assert!(history(&app, &token_a, &user_b).await.is_empty());
    assert!(conversations(&app, &token_a).await.is_empty());

    // Peer is untouched, and "delete for me" never breaks the connection.
    assert_eq!(history(&app, &token_b, &user_a).await.len(), 2);
    assert_eq!(conversations(&app, &token_b).await.len(), 1);
    assert_eq!(
        send_text(&app, &token_a, &user_b, "still-contacts").await,
        StatusCode::CREATED
    );
}

#[tokio::test]
async fn delete_chat_for_everyone_clears_consenting_peer() {
    let Some(app) = test_app().await else {
        eprintln!("skipping delete_chat_for_everyone_clears_consenting_peer: no DATABASE_URL");
        return;
    };
    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;
    set_allow_peer_chat_delete(&app, &token_b, true).await;

    assert_eq!(
        send_text(&app, &token_a, &user_b, "from-a").await,
        StatusCode::CREATED
    );
    assert_eq!(
        send_text(&app, &token_b, &user_a, "from-b").await,
        StatusCode::CREATED
    );

    let (status, body) = delete_chat(&app, &token_a, &user_b, "everyone").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["cleared_for_peer"], true);
    // Consent means the copy goes away outright — no tombstones needed.
    assert_eq!(body["tombstoned"], 0);
    assert_eq!(body["contact_removed"], false);
    assert!(still_contacts(&app, &token_a, &user_b).await);
    assert!(still_contacts(&app, &token_b, &user_a).await);

    assert!(history(&app, &token_a, &user_b).await.is_empty());
    assert!(history(&app, &token_b, &user_a).await.is_empty());
    assert!(conversations(&app, &token_a).await.is_empty());
    assert!(conversations(&app, &token_b).await.is_empty());
}

#[tokio::test]
async fn delete_chat_for_everyone_tombstones_when_peer_withholds_consent() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping delete_chat_for_everyone_tombstones_when_peer_withholds_consent: no DATABASE_URL"
        );
        return;
    };
    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    assert_eq!(
        send_text(&app, &token_a, &user_b, "from-a-1").await,
        StatusCode::CREATED
    );
    assert_eq!(
        send_text(&app, &token_a, &user_b, "from-a-2").await,
        StatusCode::CREATED
    );
    assert_eq!(
        send_text(&app, &token_b, &user_a, "from-b").await,
        StatusCode::CREATED
    );

    let (status, body) = delete_chat(&app, &token_a, &user_b, "everyone").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["cleared_for_peer"], false);
    assert_eq!(body["tombstoned"], 2);
    assert_eq!(body["contact_removed"], false);
    assert!(still_contacts(&app, &token_a, &user_b).await);
    assert!(still_contacts(&app, &token_b, &user_a).await);

    // Initiator's whole chat is gone regardless of what the peer allowed.
    assert!(history(&app, &token_a, &user_b).await.is_empty());
    assert!(conversations(&app, &token_a).await.is_empty());

    // Peer keeps their own message; ours are "message deleted" placeholders with no ciphertext.
    let peer_history = history(&app, &token_b, &user_a).await;
    assert_eq!(peer_history.len(), 3);
    let mine: Vec<&Value> = peer_history
        .iter()
        .filter(|m| m["sender_user_id"] == json!(user_a))
        .collect();
    assert_eq!(mine.len(), 2);
    for message in mine {
        assert_eq!(message["deleted_for_everyone"], true);
        assert!(message["ciphertext"].is_null());
    }
    let theirs: Vec<&Value> = peer_history
        .iter()
        .filter(|m| m["sender_user_id"] == json!(user_b))
        .collect();
    assert_eq!(theirs.len(), 1);
    assert_eq!(theirs[0]["deleted_for_everyone"], false);
    assert_eq!(theirs[0]["ciphertext"], json!(BASE64.encode(b"from-b")));
    assert_eq!(conversations(&app, &token_b).await.len(), 1);
}

#[tokio::test]
async fn delete_chat_for_everyone_keeps_the_contact() {
    let Some(app) = test_app().await else {
        eprintln!("skipping delete_chat_for_everyone_keeps_the_contact: no DATABASE_URL");
        return;
    };
    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;
    assert_eq!(
        send_text(&app, &token_a, &user_b, "old").await,
        StatusCode::CREATED
    );

    let (status, body) = delete_chat(&app, &token_a, &user_b, "everyone").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["contact_removed"], false);
    assert!(still_contacts(&app, &token_a, &user_b).await);
    assert!(still_contacts(&app, &token_b, &user_a).await);

    // The chat is gone for the person who deleted it, and they can still message.
    assert_eq!(
        send_text(&app, &token_a, &user_b, "fresh").await,
        StatusCode::CREATED
    );
    let restarted = history(&app, &token_a, &user_b).await;
    assert_eq!(restarted.len(), 1);
    assert_eq!(restarted[0]["ciphertext"], json!(BASE64.encode(b"fresh")));
    assert_eq!(conversations(&app, &token_a).await.len(), 1);

    // Peer still has the tombstone, the new message, and can reply.
    assert_eq!(
        send_text(&app, &token_b, &user_a, "still-here").await,
        StatusCode::CREATED
    );
    assert_eq!(history(&app, &token_b, &user_a).await.len(), 3);
}

#[tokio::test]
async fn delete_chat_rejects_bad_scope_and_self_everyone() {
    let Some(app) = test_app().await else {
        eprintln!("skipping delete_chat_rejects_bad_scope_and_self_everyone: no DATABASE_URL");
        return;
    };
    let (token_a, user_a) = register(&app).await;
    let (_token_b, user_b) = register(&app).await;

    let (status, _) = delete_chat(&app, &token_a, &user_b, "sideways").await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // Saved Messages has no second party to delete for.
    let (status, _) = delete_chat(&app, &token_a, &user_a, "everyone").await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    let (status, _) = delete_chat(&app, &token_a, &Uuid::new_v4().to_string(), "me").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn delete_saved_messages_for_me_clears_notes() {
    let Some(app) = test_app().await else {
        eprintln!("skipping delete_saved_messages_for_me_clears_notes: no DATABASE_URL");
        return;
    };
    let (token, user) = register(&app).await;
    // Notes are a self-conversation: peer_user_id == own id, no contact gate.
    assert_eq!(
        send_text(&app, &token, &user, "note").await,
        StatusCode::CREATED
    );
    assert_eq!(history(&app, &token, &user).await.len(), 1);

    let (status, body) = delete_chat(&app, &token, &user, "me").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["cleared_for_me"], true);
    assert!(history(&app, &token, &user).await.is_empty());
}
