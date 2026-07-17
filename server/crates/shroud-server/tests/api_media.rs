//! Integration tests for media upload authorization and linking.

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
    (format!("md_{id}"), "correct-horse-battery".into())
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
async fn upload_link_download_for_peer() {
    let Some(app) = test_app().await else {
        eprintln!("skipping upload_link_download_for_peer: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let upload = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/media/uploads")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "size_bytes": 1024, "content_type": "application/octet-stream" })
                        .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(upload.status(), StatusCode::CREATED);
    let up = json_body(upload).await;
    let media_id = up["media_object_id"].as_str().unwrap().to_string();
    assert!(up["upload_url"].as_str().unwrap().starts_with("media/"));

    // Peer cannot download unlinked media.
    let denied = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/media/{media_id}/download"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(denied.status(), StatusCode::FORBIDDEN);

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
                        "content_type": "media",
                        "ciphertext": BASE64.encode(b"envelope"),
                        "media_object_id": media_id
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(send.status(), StatusCode::CREATED);
    let msg = json_body(send).await;
    assert_eq!(msg["media_object_id"], media_id);
    let message_id = msg["id"].as_str().unwrap().to_string();

    let download = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/media/{media_id}/download"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(download.status(), StatusCode::OK);
    let dl = json_body(download).await;
    assert!(dl["download_url"].as_str().unwrap().starts_with("media/"));

    // Hide-for-me: hider loses download access; peer still has it.
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

    let hidden_denied = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/media/{media_id}/download"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(hidden_denied.status(), StatusCode::FORBIDDEN);

    let sender_still_ok = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/media/{media_id}/download"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(sender_still_ok.status(), StatusCode::OK);

    // Delete for everyone unlinks media; peer and sender (non-uploader path for linked)
    // — after unlink only uploader may fetch; sender is uploader so still ok for unlinked.
    let del = app
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
    assert_eq!(del.status(), StatusCode::NO_CONTENT);

    // Peer is not uploader and media is unlinked → forbidden.
    let peer_after_unsend = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/media/{media_id}/download"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(peer_after_unsend.status(), StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn purge_orphan_media_deletes_stale_unlinked_rows() {
    let Some(app) = test_app().await else {
        eprintln!("skipping purge_orphan_media_deletes_stale_unlinked_rows: no DATABASE_URL");
        return;
    };

    let (token, _) = register(&app).await;
    let upload = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/media/uploads")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "size_bytes": 16 }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(upload.status(), StatusCode::CREATED);
    let media_id = json_body(upload).await["media_object_id"]
        .as_str()
        .unwrap()
        .to_string();

    // Backdate so orphan GC considers it stale (TTL = 60 minutes).
    let pool = sqlx::postgres::PgPoolOptions::new()
        .max_connections(2)
        .connect(&std::env::var("DATABASE_URL").unwrap())
        .await
        .unwrap();
    sqlx::query(
        r#"
        UPDATE media_objects
        SET created_at = now() - interval '2 hours'
        WHERE id = $1
        "#,
    )
    .bind(Uuid::parse_str(&media_id).unwrap())
    .execute(&pool)
    .await
    .unwrap();

    let purged = shroud_server::routes::media::purge_orphan_media(&pool)
        .await
        .expect("purge");
    assert!(purged >= 1);

    let gone: bool =
        sqlx::query_scalar(r#"SELECT NOT EXISTS(SELECT 1 FROM media_objects WHERE id = $1)"#)
            .bind(Uuid::parse_str(&media_id).unwrap())
            .fetch_one(&pool)
            .await
            .unwrap();
    assert!(gone);
}
