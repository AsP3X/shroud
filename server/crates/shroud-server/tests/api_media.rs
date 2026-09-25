//! Integration tests for media upload authorization and linking.

//!
//! Blobs go to a temp directory, or to a live Nebular OS when `SHROUD_TEST_NEBULAR_URL`,
//! `SHROUD_TEST_NEBULAR_ACCESS_KEY_ID` and `SHROUD_TEST_NEBULAR_SECRET_ACCESS_KEY` are set
//! (see `MediaStore::for_integration_tests`).

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use shroud_server::media_store::MediaStoreError;
use shroud_server::routes;
use shroud_server::state::AppState;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

fn test_state(pool: sqlx::PgPool) -> AppState {
    AppState::for_integration_tests(pool)
}

/// The router and the state behind it, so a test can look into the media store.
async fn test_setup() -> Option<(axum::Router, AppState)> {
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
    Some((
        axum::Router::new()
            .merge(routes::router())
            .with_state(state.clone()),
        state,
    ))
}

async fn test_app() -> Option<axum::Router> {
    test_setup().await.map(|(app, _)| app)
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

/// Ciphertext-like test bytes: every byte value, so a store that re-encodes or truncates shows.
fn blob_bytes(len: usize) -> Vec<u8> {
    (0..len).map(|i| (i * 7 + i / 251) as u8).collect()
}

async fn create_upload(app: &axum::Router, token: &str, size: usize) -> String {
    let upload = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/media/uploads")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "size_bytes": size, "content_type": "application/octet-stream" })
                        .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(upload.status(), StatusCode::CREATED);
    json_body(upload).await["media_object_id"]
        .as_str()
        .unwrap()
        .to_string()
}

async fn put_content(app: &axum::Router, token: &str, media_id: &str, bytes: &[u8]) -> StatusCode {
    app.clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri(format!("/api/v1/media/{media_id}/content"))
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/octet-stream")
                .body(Body::from(bytes.to_vec()))
                .expect("request"),
        )
        .await
        .expect("response")
        .status()
}

async fn get_content(app: &axum::Router, token: &str, media_id: &str) -> axum::response::Response {
    app.clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/api/v1/media/{media_id}/content"))
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response")
}

async fn send_media(app: &axum::Router, token: &str, peer: &str, media_id: &str) -> String {
    let send = app
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
    json_body(send).await["id"].as_str().unwrap().to_string()
}

/// Where the store keeps a media row's blob.
async fn blob_location(state: &AppState, media_id: &str) -> (String, String) {
    sqlx::query_as("SELECT bucket, object_key FROM media_objects WHERE id = $1")
        .bind(Uuid::parse_str(media_id).unwrap())
        .fetch_one(&state.pool)
        .await
        .expect("media row")
}

async fn blob_is_gone(state: &AppState, bucket: &str, key: &str) -> bool {
    matches!(
        state.media.get(bucket, key).await,
        Err(MediaStoreError::NotFound)
    )
}

async fn row_is_gone(state: &AppState, media_id: &str) -> bool {
    sqlx::query_scalar(r#"SELECT NOT EXISTS(SELECT 1 FROM media_objects WHERE id = $1)"#)
        .bind(Uuid::parse_str(media_id).unwrap())
        .fetch_one(&state.pool)
        .await
        .unwrap()
}

async fn backdate(state: &AppState, media_id: &str) {
    // Past the orphan GC's 60-minute grace.
    sqlx::query("UPDATE media_objects SET created_at = now() - interval '2 hours' WHERE id = $1")
        .bind(Uuid::parse_str(media_id).unwrap())
        .execute(&state.pool)
        .await
        .unwrap();
}

#[tokio::test]
async fn content_round_trips_to_the_peer_as_private_ciphertext() {
    let Some((app, state)) = test_setup().await else {
        eprintln!(
            "skipping content_round_trips_to_the_peer_as_private_ciphertext: no DATABASE_URL"
        );
        return;
    };
    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    // Larger than one chunk of any stream in between, and not a multiple of a block size.
    let bytes = blob_bytes(300_007);
    let media_id = create_upload(&app, &token_a, bytes.len()).await;
    let (bucket, key) = blob_location(&state, &media_id).await;
    assert!(key.starts_with("media/"), "{key}");
    assert!(
        !key.contains(&user_a),
        "the key must not name the uploader: {key}"
    );
    assert_eq!(bucket, state.media.bucket());

    assert_eq!(
        put_content(&app, &token_a, &media_id, &bytes).await,
        StatusCode::NO_CONTENT
    );
    // Not sent yet: only the uploader can read it back.
    assert_eq!(
        get_content(&app, &token_b, &media_id).await.status(),
        StatusCode::FORBIDDEN
    );
    let own = get_content(&app, &token_a, &media_id).await;
    assert_eq!(own.status(), StatusCode::OK);
    assert_eq!(
        own.into_body().collect().await.unwrap().to_bytes().as_ref(),
        bytes.as_slice()
    );

    send_media(&app, &token_a, &user_b, &media_id).await;
    let peer = get_content(&app, &token_b, &media_id).await;
    assert_eq!(peer.status(), StatusCode::OK);
    let headers = peer.headers().clone();
    assert_eq!(headers[header::CONTENT_TYPE], "application/octet-stream");
    assert_eq!(
        headers[header::CONTENT_LENGTH],
        bytes.len().to_string().as_str()
    );
    assert_eq!(headers[header::CACHE_CONTROL], "private, no-store");
    assert_eq!(headers[header::X_CONTENT_TYPE_OPTIONS], "nosniff");
    assert_eq!(
        peer.into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes()
            .as_ref(),
        bytes.as_slice()
    );
}

#[tokio::test]
async fn only_the_uploader_writes_content_and_only_before_it_is_sent() {
    let Some((app, _state)) = test_setup().await else {
        eprintln!(
            "skipping only_the_uploader_writes_content_and_only_before_it_is_sent: no DATABASE_URL"
        );
        return;
    };
    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let bytes = blob_bytes(2048);
    let media_id = create_upload(&app, &token_a, bytes.len()).await;
    assert_eq!(
        put_content(&app, &token_b, &media_id, &bytes).await,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        put_content(&app, &token_a, &media_id, &bytes[..100]).await,
        StatusCode::BAD_REQUEST,
        "a body of another size than declared is refused"
    );
    // Nothing stored yet: the uploader gets a 404, not a 503.
    assert_eq!(
        get_content(&app, &token_a, &media_id).await.status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        put_content(&app, &token_a, &media_id, &bytes).await,
        StatusCode::NO_CONTENT
    );
    // A retried upload before sending replaces the blob.
    assert_eq!(
        put_content(&app, &token_a, &media_id, &bytes).await,
        StatusCode::NO_CONTENT
    );
    send_media(&app, &token_a, &user_b, &media_id).await;
    assert_eq!(
        put_content(&app, &token_a, &media_id, &blob_bytes(2048)).await,
        StatusCode::CONFLICT,
        "sent media can't be overwritten"
    );
}

#[tokio::test]
async fn purge_orphan_media_deletes_stale_unlinked_rows() {
    let Some((app, state)) = test_setup().await else {
        eprintln!("skipping purge_orphan_media_deletes_stale_unlinked_rows: no DATABASE_URL");
        return;
    };

    let (token, _) = register(&app).await;
    let bytes = blob_bytes(16);
    let media_id = create_upload(&app, &token, bytes.len()).await;
    assert_eq!(
        put_content(&app, &token, &media_id, &bytes).await,
        StatusCode::NO_CONTENT
    );
    let (bucket, key) = blob_location(&state, &media_id).await;
    backdate(&state, &media_id).await;

    let purged =
        shroud_server::routes::media::purge_orphan_media(&state.pool, &state.media, &state.metrics)
            .await
            .expect("purge");
    assert!(purged >= 1);
    assert!(row_is_gone(&state, &media_id).await);
    assert!(blob_is_gone(&state, &bucket, &key).await);
}

#[tokio::test]
async fn media_deleted_for_everyone_leaves_the_store() {
    let Some((app, state)) = test_setup().await else {
        eprintln!("skipping media_deleted_for_everyone_leaves_the_store: no DATABASE_URL");
        return;
    };
    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let bytes = blob_bytes(8192);
    let media_id = create_upload(&app, &token_a, bytes.len()).await;
    assert_eq!(
        put_content(&app, &token_a, &media_id, &bytes).await,
        StatusCode::NO_CONTENT
    );
    let message_id = send_media(&app, &token_a, &user_b, &media_id).await;
    let (bucket, key) = blob_location(&state, &media_id).await;

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
    assert_eq!(
        get_content(&app, &token_b, &media_id).await.status(),
        StatusCode::FORBIDDEN
    );

    // The unlinked row is the orphan GC's; its next pass removes the ciphertext for good.
    backdate(&state, &media_id).await;
    shroud_server::routes::media::purge_orphan_media(&state.pool, &state.media, &state.metrics)
        .await
        .expect("purge");
    assert!(row_is_gone(&state, &media_id).await);
    assert!(blob_is_gone(&state, &bucket, &key).await);
}

#[tokio::test]
async fn purge_media_ids_removes_blob_and_row() {
    let Some((app, state)) = test_setup().await else {
        eprintln!("skipping purge_media_ids_removes_blob_and_row: no DATABASE_URL");
        return;
    };
    let (token, _) = register(&app).await;
    let bytes = blob_bytes(4096);
    let media_id = create_upload(&app, &token, bytes.len()).await;
    assert_eq!(
        put_content(&app, &token, &media_id, &bytes).await,
        StatusCode::NO_CONTENT
    );
    let (bucket, key) = blob_location(&state, &media_id).await;

    let purged = shroud_server::routes::media::purge_media_ids(
        &state,
        &[Uuid::parse_str(&media_id).unwrap()],
    )
    .await
    .expect("purge");
    assert_eq!(purged, 1);
    assert!(row_is_gone(&state, &media_id).await);
    assert!(blob_is_gone(&state, &bucket, &key).await);
    // Purging again is a no-op, not an error.
    assert_eq!(
        shroud_server::routes::media::purge_media_ids(
            &state,
            &[Uuid::parse_str(&media_id).unwrap()]
        )
        .await
        .expect("purge again"),
        0
    );
}
