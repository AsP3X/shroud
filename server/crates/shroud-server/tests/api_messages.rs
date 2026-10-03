//! Integration tests for HTTP message relay.

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
    (format!("m_{id}"), "correct-horse-battery".into())
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
                    json!({ "username_hash": shroud_server::auth::username_hash_b64(&username), "password": password }).to_string(),
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
    let r2 = app
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
    assert_eq!(r2.status(), StatusCode::OK);
}

#[tokio::test]
async fn send_list_idempotent_and_delivered() {
    let Some(app) = test_app().await else {
        eprintln!("skipping send_list_idempotent_and_delivered: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let client_message_id = Uuid::new_v4();
    let ciphertext = BASE64.encode(b"sealed-hello");

    let send = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/messages")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "peer_user_id": user_b,
                        "client_message_id": client_message_id,
                        "content_type": "text",
                        "ciphertext": ciphertext
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(send.status(), StatusCode::CREATED);
    let msg = json_body(send).await;
    let message_id = msg["id"].as_str().unwrap().to_string();
    assert_eq!(msg["ciphertext"], ciphertext);
    assert_eq!(msg["deleted_for_everyone"], false);

    // Idempotent replay
    let send2 = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/messages")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "peer_user_id": user_b,
                        "client_message_id": client_message_id,
                        "content_type": "text",
                        "ciphertext": ciphertext
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(send2.status(), StatusCode::OK);
    let msg2 = json_body(send2).await;
    assert_eq!(msg2["id"], message_id);

    let list = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/messages?peer_user_id={user_a}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(list.status(), StatusCode::OK);
    let history = json_body(list).await;
    assert_eq!(history["messages"].as_array().unwrap().len(), 1);

    let delivered = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/messages/{message_id}/delivered"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(delivered.status(), StatusCode::NO_CONTENT);

    let convos = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/conversations")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(convos.status(), StatusCode::OK);
    let c = json_body(convos).await;
    assert_eq!(c["conversations"].as_array().unwrap().len(), 1);
    assert_eq!(c["conversations"][0]["peer"]["id"], user_b);
}

#[tokio::test]
async fn delete_for_me_and_everyone() {
    let Some(app) = test_app().await else {
        eprintln!("skipping delete_for_me_and_everyone: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let send = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/messages")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "peer_user_id": user_b,
                        "client_message_id": Uuid::new_v4(),
                        "content_type": "text",
                        "ciphertext": BASE64.encode(b"secret")
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    let msg = json_body(send).await;
    let message_id = msg["id"].as_str().unwrap().to_string();

    let hide = app
        .clone()
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri(format!("/api/v1/messages/{message_id}?scope=me"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(hide.status(), StatusCode::NO_CONTENT);

    let list_b = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/messages?peer_user_id={user_a}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let history_b = json_body(list_b).await;
    assert_eq!(history_b["messages"].as_array().unwrap().len(), 0);

    let unsend = app
        .clone()
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri(format!("/api/v1/messages/{message_id}?scope=everyone"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(unsend.status(), StatusCode::NO_CONTENT);

    let list_a = app
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/messages?peer_user_id={user_b}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let history_a = json_body(list_a).await;
    assert_eq!(history_a["messages"][0]["deleted_for_everyone"], true);
    assert!(history_a["messages"][0]["ciphertext"].is_null());
}

#[tokio::test]
async fn cannot_message_non_contact() {
    let Some(app) = test_app().await else {
        eprintln!("skipping cannot_message_non_contact: no DATABASE_URL");
        return;
    };

    let (token_a, _) = register(&app).await;
    let (_, user_b) = register(&app).await;

    let send = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/messages")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "peer_user_id": user_b,
                        "client_message_id": Uuid::new_v4(),
                        "content_type": "text",
                        "ciphertext": BASE64.encode(b"nope")
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(send.status(), StatusCode::FORBIDDEN);
}

async fn post_message(app: &axum::Router, token: &str, body: Value) -> axum::response::Response {
    app.clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/messages")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(body.to_string()))
                .expect("request"),
        )
        .await
        .expect("response")
}

async fn last_message_at(app: &axum::Router, token: &str) -> Value {
    let convos = app
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
    assert_eq!(convos.status(), StatusCode::OK);
    json_body(convos).await["conversations"][0]["last_message_at"].clone()
}

#[tokio::test]
async fn annotation_is_delivered_without_bumping_the_chat() {
    let Some(app) = test_app().await else {
        eprintln!("skipping annotation_is_delivered_without_bumping_the_chat: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let voice = post_message(
        &app,
        &token_a,
        json!({
            "peer_user_id": user_b,
            "client_message_id": Uuid::new_v4(),
            "content_type": "text",
            "ciphertext": BASE64.encode(b"sealed-voice-stand-in")
        }),
    )
    .await;
    assert_eq!(voice.status(), StatusCode::CREATED);
    let before = last_message_at(&app, &token_a).await;
    assert!(before.is_string(), "sending a message sets last_message_at");

    // The recipient shares a transcript back.
    let annotation = post_message(
        &app,
        &token_b,
        json!({
            "peer_user_id": user_a,
            "client_message_id": Uuid::new_v4(),
            "content_type": "annotation",
            "ciphertext": BASE64.encode(b"sealed-transcript")
        }),
    )
    .await;
    assert_eq!(annotation.status(), StatusCode::CREATED);
    let annotation = json_body(annotation).await;
    assert_eq!(annotation["content_type"], "annotation");

    assert_eq!(
        last_message_at(&app, &token_a).await,
        before,
        "an annotation must not reorder the chat list"
    );

    let list = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/messages?peer_user_id={user_b}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(list.status(), StatusCode::OK);
    let history = json_body(list).await;
    let messages = history["messages"].as_array().unwrap();
    assert_eq!(
        messages.len(),
        2,
        "annotations stay in history so every device can apply them"
    );
    assert_eq!(messages[0]["content_type"], "annotation");

    let with_media = post_message(
        &app,
        &token_b,
        json!({
            "peer_user_id": user_a,
            "client_message_id": Uuid::new_v4(),
            "content_type": "annotation",
            "ciphertext": BASE64.encode(b"sealed"),
            "media_object_id": Uuid::new_v4()
        }),
    )
    .await;
    assert_eq!(with_media.status(), StatusCode::BAD_REQUEST);

    let unknown = post_message(
        &app,
        &token_b,
        json!({
            "peer_user_id": user_a,
            "client_message_id": Uuid::new_v4(),
            "content_type": "reaction",
            "ciphertext": BASE64.encode(b"sealed")
        }),
    )
    .await;
    assert_eq!(unknown.status(), StatusCode::BAD_REQUEST);
}
