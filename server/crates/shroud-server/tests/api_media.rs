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
use shroud_server::media_store::{MediaStore, MediaStoreError, NebularConfig};
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

    // Delete for everyone removes the blob. Neither side can fetch it.
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
    assert_eq!(peer_after_unsend.status(), StatusCode::NOT_FOUND);
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
async fn media_deleted_for_everyone_frees_the_store() {
    let Some((app, state)) = test_setup().await else {
        eprintln!("skipping media_deleted_for_everyone_frees_the_store: no DATABASE_URL");
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

    // Hidden for one person: the other person still has the message, so the blob stays.
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
    assert!(!row_is_gone(&state, &media_id).await);
    assert!(!blob_is_gone(&state, &bucket, &key).await);

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
        get_content(&app, &token_a, &media_id).await.status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        get_content(&app, &token_b, &media_id).await.status(),
        StatusCode::NOT_FOUND
    );
    assert!(row_is_gone(&state, &media_id).await);
    assert!(blob_is_gone(&state, &bucket, &key).await);
}

#[tokio::test]
async fn chat_deleted_for_everyone_frees_the_senders_blobs() {
    let Some((app, state)) = test_setup().await else {
        eprintln!("skipping chat_deleted_for_everyone_frees_the_senders_blobs: no DATABASE_URL");
        return;
    };
    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;
    become_contacts(&app, &token_a, &user_a, &token_b, &user_b).await;

    let bytes_a = blob_bytes(4096);
    let media_a = create_upload(&app, &token_a, bytes_a.len()).await;
    assert_eq!(
        put_content(&app, &token_a, &media_a, &bytes_a).await,
        StatusCode::NO_CONTENT
    );
    send_media(&app, &token_a, &user_b, &media_a).await;
    let (bucket_a, key_a) = blob_location(&state, &media_a).await;

    let bytes_b = blob_bytes(2048);
    let media_b = create_upload(&app, &token_b, bytes_b.len()).await;
    assert_eq!(
        put_content(&app, &token_b, &media_b, &bytes_b).await,
        StatusCode::NO_CONTENT
    );
    send_media(&app, &token_b, &user_a, &media_b).await;
    let (bucket_b, key_b) = blob_location(&state, &media_b).await;

    // Peer has not allowed a full clear, so only A's messages are deleted for everyone.
    let del = app
        .clone()
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri(format!("/api/v1/conversations/{user_b}?scope=everyone"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(del.status(), StatusCode::OK);
    assert!(row_is_gone(&state, &media_a).await);
    assert!(blob_is_gone(&state, &bucket_a, &key_a).await);
    assert!(!row_is_gone(&state, &media_b).await);
    assert!(!blob_is_gone(&state, &bucket_b, &key_b).await);
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

/// Uploads are open to every account and linked media stays as long as its message, so each
/// account has a quota (`MEDIA_QUOTA_MIB`; security audit M4).
#[tokio::test]
async fn media_quota_refuses_an_upload_past_it() {
    let Some((_, state)) = test_setup().await else {
        eprintln!("skipping media_quota_refuses_an_upload_past_it: DATABASE_URL unavailable");
        return;
    };
    let mut state = state;
    state.media_quota_bytes = 1000;
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state);
    let (token, _) = register(&app).await;

    create_upload(&app, &token, 600).await;
    let over = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/media/uploads")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "size_bytes": 600 }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(over.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(
        json_body(over).await["error"]["code"],
        "MEDIA_QUOTA_EXCEEDED"
    );
    create_upload(&app, &token, 400).await;
}

/// The quota lock and the contact pair lock (`contacts::lock_pair`) once ran the same SQL text,
/// and sqlx caches a prepared statement per connection by its text alone. After a message, call
/// or contact accept had prepared it with a `text` parameter, the quota lock reused it and sent
/// the account id's 16 raw bytes as text, which Postgres refuses unless they happen to be valid
/// UTF-8 (`invalid byte sequence for encoding "UTF8": 0x8a`), so the upload failed with a 500
/// (SHRO-43).
#[tokio::test]
async fn media_quota_lock_works_after_a_contact_pair_lock_on_the_same_connection() {
    let Some(_) = test_setup().await else {
        eprintln!(
            "skipping media_quota_lock_works_after_a_contact_pair_lock_on_the_same_connection: DATABASE_URL unavailable"
        );
        return;
    };
    // Human: one pooled connection, so the contact pair lock and the quota lock share its cache.
    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect(&std::env::var("DATABASE_URL").expect("DATABASE_URL"))
        .await
        .expect("pool");
    let mut state = test_state(pool.clone());
    state.media_quota_bytes = 1000;
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state);

    // Agent: register always draws a random id, so the account is seeded with one that starts 0x8a.
    let mut id_bytes = *Uuid::new_v4().as_bytes();
    id_bytes[0] = 0x8a;
    let user_id = Uuid::from_bytes(id_bytes);
    assert!(std::str::from_utf8(user_id.as_bytes()).is_err());
    let (username, password) = unique_user();
    sqlx::query(
        "INSERT INTO users (id, username_hash, password_hash, share_code) VALUES ($1, $2, $3, $4)",
    )
    .bind(user_id)
    .bind(
        BASE64
            .decode(shroud_server::auth::username_hash_b64(&username))
            .expect("username hash"),
    )
    .bind(
        shroud_server::auth::hash_password(&password)
            .await
            .expect("password hash"),
    )
    .bind(shroud_server::auth::generate_share_code())
    .execute(&pool)
    .await
    .expect("user");
    let device_id: Uuid =
        sqlx::query_scalar("INSERT INTO devices (user_id) VALUES ($1) RETURNING id")
            .bind(user_id)
            .fetch_one(&pool)
            .await
            .expect("device");
    let (token, token_hash) = shroud_server::auth::issue_session_token().expect("token");
    sqlx::query("INSERT INTO sessions (device_id, token_hash) VALUES ($1, $2)")
        .bind(device_id)
        .bind(token_hash)
        .execute(&pool)
        .await
        .expect("session");

    // The mutual requests auto-accept, which takes the contact pair lock.
    let (peer_token, peer) = register(&app).await;
    become_contacts(&app, &token, &user_id.to_string(), &peer_token, &peer).await;
    create_upload(&app, &token, 600).await;
}

/// Parallel PUTs to one media object would each spool their own copy (security audit M4).
#[tokio::test]
async fn a_second_put_to_an_upload_in_progress_is_refused() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping a_second_put_to_an_upload_in_progress_is_refused: DATABASE_URL unavailable"
        );
        return;
    };
    let (token, _) = register(&app).await;
    let bytes = blob_bytes(64);
    let media_id = create_upload(&app, &token, bytes.len()).await;

    let (chunks, receiver) = tokio::sync::mpsc::channel::<Result<Vec<u8>, std::io::Error>>(1);
    let body = futures_util::stream::unfold(receiver, |mut receiver| async move {
        receiver.recv().await.map(|chunk| (chunk, receiver))
    });
    let first = tokio::spawn({
        let app = app.clone();
        let token = token.clone();
        let media_id = media_id.clone();
        async move {
            app.oneshot(
                Request::builder()
                    .method("PUT")
                    .uri(format!("/api/v1/media/{media_id}/content"))
                    .header(header::AUTHORIZATION, format!("Bearer {token}"))
                    .header(header::CONTENT_TYPE, "application/octet-stream")
                    .body(Body::from_stream(body))
                    .expect("request"),
            )
            .await
            .expect("response")
            .status()
        }
    });
    chunks
        .send(Ok(bytes[..32].to_vec()))
        .await
        .expect("first half");
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    assert_eq!(
        put_content(&app, &token, &media_id, &bytes).await,
        StatusCode::CONFLICT
    );
    chunks
        .send(Ok(bytes[32..].to_vec()))
        .await
        .expect("second half");
    drop(chunks);
    assert_eq!(first.await.expect("first put"), StatusCode::NO_CONTENT);
}

/// An upload checks the row again right before it publishes: one linked to a message while its
/// bytes were still arriving must not replace the blob the message names (security audit L10).
#[tokio::test]
async fn an_upload_linked_before_it_finishes_does_not_publish() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping an_upload_linked_before_it_finishes_does_not_publish: DATABASE_URL unavailable"
        );
        return;
    };
    let (token, me) = register(&app).await;
    let bytes = blob_bytes(64);
    let media_id = create_upload(&app, &token, bytes.len()).await;

    let (chunks, receiver) = tokio::sync::mpsc::channel::<Result<Vec<u8>, std::io::Error>>(1);
    let body = futures_util::stream::unfold(receiver, |mut receiver| async move {
        receiver.recv().await.map(|chunk| (chunk, receiver))
    });
    let upload = tokio::spawn({
        let app = app.clone();
        let token = token.clone();
        let media_id = media_id.clone();
        async move {
            app.oneshot(
                Request::builder()
                    .method("PUT")
                    .uri(format!("/api/v1/media/{media_id}/content"))
                    .header(header::AUTHORIZATION, format!("Bearer {token}"))
                    .header(header::CONTENT_TYPE, "application/octet-stream")
                    .body(Body::from_stream(body))
                    .expect("request"),
            )
            .await
            .expect("response")
            .status()
        }
    });
    chunks
        .send(Ok(bytes[..32].to_vec()))
        .await
        .expect("first half");
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    // Notes to self link it while the upload is still going.
    send_media(&app, &token, &me, &media_id).await;
    chunks
        .send(Ok(bytes[32..].to_vec()))
        .await
        .expect("second half");
    drop(chunks);
    assert_eq!(upload.await.expect("upload"), StatusCode::CONFLICT);
}

/// A stand-in Nebular whose uploads hang until the test lets them through, so a publish can be
/// held open for as long as a slow store would hold it.
struct HeldNebular {
    url: String,
    /// The path of each upload as it reaches the store, before it is answered.
    puts: tokio::sync::mpsc::UnboundedReceiver<String>,
    /// `true` answers every held upload (and any later one) with 200.
    release: tokio::sync::watch::Sender<bool>,
    /// Paths the store was asked to delete; each is answered with 204.
    deletes: std::sync::Arc<std::sync::Mutex<Vec<String>>>,
    /// The path of each delete as it reaches the store, before it is answered.
    delete_requests: tokio::sync::mpsc::UnboundedReceiver<String>,
    /// `false` holds every delete until it is set back to `true` (the default).
    release_deletes: tokio::sync::watch::Sender<bool>,
}

impl HeldNebular {
    async fn start() -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind");
        let url = format!("http://{}", listener.local_addr().expect("addr"));
        let (put_tx, puts) = tokio::sync::mpsc::unbounded_channel();
        let (release, released) = tokio::sync::watch::channel(false);
        let deletes = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let seen_deletes = deletes.clone();
        let (delete_tx, delete_requests) = tokio::sync::mpsc::unbounded_channel();
        let (release_deletes, deletes_released) = tokio::sync::watch::channel(true);
        tokio::spawn(async move {
            while let Ok((socket, _)) = listener.accept().await {
                tokio::spawn(serve_held(
                    socket,
                    (put_tx.clone(), released.clone()),
                    (delete_tx.clone(), deletes_released.clone()),
                    seen_deletes.clone(),
                ));
            }
        });
        Self {
            url,
            puts,
            release,
            deletes,
            delete_requests,
            release_deletes,
        }
    }

    fn store(&self) -> std::sync::Arc<MediaStore> {
        let spool = std::env::temp_dir().join(format!("shroud-held-nebular-{}", Uuid::new_v4()));
        std::sync::Arc::new(
            MediaStore::nebular(
                NebularConfig {
                    url: self.url.clone(),
                    access_key_id: "test".into(),
                    secret_access_key: "test".into(),
                    region: "us-east-1".into(),
                },
                "shroud-media",
                Some(spool),
            )
            .expect("held store"),
        )
    }

    /// Waits for the next upload to reach the store and returns its path.
    async fn next_put(&mut self) -> String {
        tokio::time::timeout(std::time::Duration::from_secs(10), self.puts.recv())
            .await
            .expect("an upload reached the store")
            .expect("store running")
    }

    /// Waits for the next delete to reach the store and returns its path.
    async fn next_delete(&mut self) -> String {
        tokio::time::timeout(
            std::time::Duration::from_secs(10),
            self.delete_requests.recv(),
        )
        .await
        .expect("a delete reached the store")
        .expect("store running")
    }

    fn deleted(&self, key: &str) -> bool {
        self.deletes
            .lock()
            .unwrap()
            .iter()
            .any(|path| path.ends_with(key))
    }
}

/// One keep-alive connection to [`HeldNebular`]: reads each request whole, then answers it.
async fn serve_held(
    mut socket: tokio::net::TcpStream,
    (puts, mut released): (
        tokio::sync::mpsc::UnboundedSender<String>,
        tokio::sync::watch::Receiver<bool>,
    ),
    (delete_requests, mut deletes_released): (
        tokio::sync::mpsc::UnboundedSender<String>,
        tokio::sync::watch::Receiver<bool>,
    ),
    deletes: std::sync::Arc<std::sync::Mutex<Vec<String>>>,
) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let mut buf = Vec::new();
    let mut chunk = vec![0_u8; 8192];
    loop {
        let head_end = loop {
            if let Some(at) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                break at + 4;
            }
            match socket.read(&mut chunk).await {
                Ok(0) | Err(_) => return,
                Ok(n) => buf.extend_from_slice(&chunk[..n]),
            }
        };
        let head = String::from_utf8_lossy(&buf[..head_end]).to_string();
        let mut words = head.split_whitespace();
        let method = words.next().unwrap_or_default().to_string();
        let path = words.next().unwrap_or_default().to_string();
        let body_len: usize = head
            .lines()
            .filter_map(|line| line.split_once(':'))
            .find(|(name, _)| name.eq_ignore_ascii_case("content-length"))
            .and_then(|(_, value)| value.trim().parse().ok())
            .unwrap_or(0);
        while buf.len() < head_end + body_len {
            match socket.read(&mut chunk).await {
                Ok(0) | Err(_) => return,
                Ok(n) => buf.extend_from_slice(&chunk[..n]),
            }
        }
        buf.drain(..head_end + body_len);
        let answer: &[u8] = match method.as_str() {
            "PUT" => {
                let _ = puts.send(path);
                if released.wait_for(|go| *go).await.is_err() {
                    return;
                }
                b"HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n"
            }
            "DELETE" => {
                let _ = delete_requests.send(path.clone());
                if deletes_released.wait_for(|go| *go).await.is_err() {
                    return;
                }
                deletes.lock().unwrap().push(path);
                b"HTTP/1.1 204 No Content\r\n\r\n"
            }
            _ => b"HTTP/1.1 404 Not Found\r\ncontent-length: 0\r\n\r\n",
        };
        if socket.write_all(answer).await.is_err() {
            return;
        }
    }
}

/// A router over a pool of `pool_size` connections whose media store holds every publish open.
async fn held_publish_setup(pool_size: u32) -> Option<(axum::Router, AppState, HeldNebular)> {
    let database_url = std::env::var("DATABASE_URL").ok()?;
    let pool = PgPoolOptions::new()
        .max_connections(pool_size)
        .connect(&database_url)
        .await
        .ok()?;
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .ok()?;
    let nebular = HeldNebular::start().await;
    let mut state = test_state(pool);
    state.media = nebular.store();
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state.clone());
    Some((app, state, nebular))
}

fn spawn_put(
    app: &axum::Router,
    token: &str,
    media_id: &str,
    bytes: &[u8],
) -> tokio::task::JoinHandle<StatusCode> {
    let (app, token, media_id, bytes) = (
        app.clone(),
        token.to_string(),
        media_id.to_string(),
        bytes.to_vec(),
    );
    tokio::spawn(async move { put_content(&app, &token, &media_id, &bytes).await })
}

/// A publish waits on the store, not on the database: two of them hanging must leave a
/// two-connection pool to everyone else (security audit 2026-11 S1).
#[tokio::test]
async fn publishes_waiting_on_the_store_leave_the_pool_free() {
    let Some((app, _state, mut nebular)) = held_publish_setup(2).await else {
        eprintln!(
            "skipping publishes_waiting_on_the_store_leave_the_pool_free: DATABASE_URL unavailable"
        );
        return;
    };
    let (token, _) = register(&app).await;
    let bytes = blob_bytes(64);
    let first = create_upload(&app, &token, bytes.len()).await;
    let second = create_upload(&app, &token, bytes.len()).await;
    let _uploads = [
        spawn_put(&app, &token, &first, &bytes),
        spawn_put(&app, &token, &second, &bytes),
    ];
    nebular.next_put().await;
    nebular.next_put().await;

    let me = tokio::time::timeout(
        std::time::Duration::from_secs(2),
        app.clone().oneshot(
            Request::builder()
                .method("GET")
                .uri("/api/v1/auth/me")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        ),
    )
    .await
    .expect("GET /auth/me answered while two publishes hang")
    .expect("response");
    assert_eq!(me.status(), StatusCode::OK);
}

/// While a publish is under way the row can't be linked, and a delete leaves it alone. Once the
/// publish is done the row is an ordinary unlinked upload, so its blob is still collected.
#[tokio::test]
async fn a_delete_racing_the_publish_leaves_nothing_uncollected() {
    let Some((app, state, mut nebular)) = held_publish_setup(5).await else {
        eprintln!(
            "skipping a_delete_racing_the_publish_leaves_nothing_uncollected: DATABASE_URL unavailable"
        );
        return;
    };
    let (token, me) = register(&app).await;
    let bytes = blob_bytes(64);
    let media_id = create_upload(&app, &token, bytes.len()).await;
    let (_, key) = blob_location(&state, &media_id).await;
    let upload = spawn_put(&app, &token, &media_id, &bytes);
    nebular.next_put().await;

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
                        "peer_user_id": me,
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
    assert_eq!(send.status(), StatusCode::CONFLICT);
    assert_eq!(json_body(send).await["error"]["code"], "UPLOAD_IN_PROGRESS");

    let id = Uuid::parse_str(&media_id).unwrap();
    assert_eq!(
        routes::media::purge_media_ids(&state, &[id])
            .await
            .expect("purge"),
        0
    );
    assert!(!row_is_gone(&state, &media_id).await);
    assert!(!nebular.deleted(&key));

    nebular.release.send_replace(true);
    assert_eq!(upload.await.expect("upload"), StatusCode::NO_CONTENT);
    assert_eq!(
        routes::media::purge_media_ids(&state, &[id])
            .await
            .expect("purge"),
        1
    );
    assert!(row_is_gone(&state, &media_id).await);
    assert!(nebular.deleted(&key));
}

/// A purge claims the row before it deletes the blob, so an upload that reaches its publish while
/// the purge is deleting is refused instead of publishing a blob the purge has just removed (and
/// answering 204 for a row that is about to go).
#[tokio::test]
async fn an_upload_reaching_its_publish_during_a_purge_is_refused() {
    let Some((app, state, mut nebular)) = held_publish_setup(5).await else {
        eprintln!(
            "skipping an_upload_reaching_its_publish_during_a_purge_is_refused: DATABASE_URL unavailable"
        );
        return;
    };
    let (token, _) = register(&app).await;
    let bytes = blob_bytes(64);
    let media_id = create_upload(&app, &token, bytes.len()).await;
    let id = Uuid::parse_str(&media_id).unwrap();

    nebular.release_deletes.send_replace(false);
    let purge = tokio::spawn({
        let state = state.clone();
        async move { routes::media::purge_media_ids(&state, &[id]).await }
    });
    nebular.next_delete().await;

    let upload = tokio::time::timeout(
        std::time::Duration::from_secs(10),
        put_content(&app, &token, &media_id, &bytes),
    )
    .await
    .expect("the upload was refused, not published");
    assert_eq!(upload, StatusCode::CONFLICT);
    assert!(nebular.puts.try_recv().is_err());

    nebular.release_deletes.send_replace(true);
    assert_eq!(purge.await.expect("purge task").expect("purge"), 1);
    assert!(row_is_gone(&state, &media_id).await);
    assert_eq!(
        put_content(&app, &token, &media_id, &bytes).await,
        StatusCode::NOT_FOUND
    );
}

/// A request dropped while its blob is being published (the client went away) still finishes the
/// publish and clears its marker, so the media can be sent at once rather than once it goes stale.
#[tokio::test]
async fn an_upload_dropped_mid_publish_still_clears_its_marker() {
    let Some((app, state, mut nebular)) = held_publish_setup(5).await else {
        eprintln!(
            "skipping an_upload_dropped_mid_publish_still_clears_its_marker: DATABASE_URL unavailable"
        );
        return;
    };
    let (token, me) = register(&app).await;
    let bytes = blob_bytes(64);
    let media_id = create_upload(&app, &token, bytes.len()).await;
    let upload = spawn_put(&app, &token, &media_id, &bytes);
    nebular.next_put().await;
    upload.abort();
    assert!(upload.await.expect_err("aborted").is_cancelled());

    nebular.release.send_replace(true);
    let id = Uuid::parse_str(&media_id).unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        loop {
            let marked: bool = sqlx::query_scalar(
                "SELECT publishing_at IS NOT NULL FROM media_objects WHERE id = $1",
            )
            .bind(id)
            .fetch_one(&state.pool)
            .await
            .expect("media row");
            if !marked {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("publishing marker cleared");
    send_media(&app, &token, &me, &media_id).await;
}

/// A row removed while its blob was being published (by a delete that took a dead upload's
/// marker for stale) gets that blob taken back, so nothing is left that no row names.
#[tokio::test]
async fn a_row_removed_during_the_publish_takes_its_blob_back() {
    let Some((app, state, mut nebular)) = held_publish_setup(5).await else {
        eprintln!(
            "skipping a_row_removed_during_the_publish_takes_its_blob_back: DATABASE_URL unavailable"
        );
        return;
    };
    let (token, _) = register(&app).await;
    let bytes = blob_bytes(64);
    let media_id = create_upload(&app, &token, bytes.len()).await;
    let (_, key) = blob_location(&state, &media_id).await;
    let upload = spawn_put(&app, &token, &media_id, &bytes);
    nebular.next_put().await;

    sqlx::query("DELETE FROM media_objects WHERE id = $1")
        .bind(Uuid::parse_str(&media_id).unwrap())
        .execute(&state.pool)
        .await
        .expect("remove row");
    nebular.release.send_replace(true);
    assert_eq!(upload.await.expect("upload"), StatusCode::NOT_FOUND);
    assert!(nebular.deleted(&key));
}
