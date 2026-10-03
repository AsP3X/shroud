//! Moving the local volume earlier releases wrote into Nebular, against a live Nebular OS.
//!
//! Needs `DATABASE_URL` plus `SHROUD_TEST_NEBULAR_URL`, `SHROUD_TEST_NEBULAR_ACCESS_KEY_ID` and
//! `SHROUD_TEST_NEBULAR_SECRET_ACCESS_KEY` (a Nebular set up like `docker-compose.yml`); skips
//! without them.

use axum::body::{Body, Bytes};
use axum::http::{Request, StatusCode, header};
use futures_util::StreamExt;
use http_body_util::BodyExt;
use serde_json::{Value, json};
use shroud_server::media_store::{
    BlobSource, MediaBlob, MediaStore, MediaStoreError, NebularConfig,
};
use shroud_server::metrics::Metrics;
use shroud_server::routes;
use shroud_server::state::AppState;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

fn nebular_config() -> Option<NebularConfig> {
    let env = |name: &str| std::env::var(name).ok().filter(|v| !v.trim().is_empty());
    Some(NebularConfig {
        url: env("SHROUD_TEST_NEBULAR_URL")?,
        access_key_id: env("SHROUD_TEST_NEBULAR_ACCESS_KEY_ID")?,
        secret_access_key: env("SHROUD_TEST_NEBULAR_SECRET_ACCESS_KEY")?,
        region: "us-east-1".into(),
    })
}

async fn test_setup() -> Option<(axum::Router, AppState, NebularConfig)> {
    let nebular = nebular_config()?;
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&std::env::var("DATABASE_URL").ok()?)
        .await
        .ok()?;
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .ok()?;
    let state = AppState::for_integration_tests(pool);
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state.clone());
    Some((app, state, nebular))
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

async fn read_all(blob: MediaBlob) -> Vec<u8> {
    let mut out = Vec::new();
    let mut body = blob.body;
    while let Some(chunk) = body.next().await {
        out.extend_from_slice(&chunk.expect("chunk"));
    }
    out
}

/// A user with one upload row, as the API makes them.
async fn user_with_upload(app: &axum::Router, size: usize) -> (String, String) {
    let id = &Uuid::new_v4().simple().to_string()[..12];
    let registered = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username_hash": shroud_server::auth::username_hash_b64(format!("mg_{id}")), "password": "correct-horse-battery" })
                        .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(registered.status(), StatusCode::CREATED);
    let body = json_body(registered).await;
    let token = body["token"].as_str().unwrap().to_string();
    let user_id = body["user"]["id"].as_str().unwrap().to_string();

    let upload = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/media/uploads")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "size_bytes": size }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(upload.status(), StatusCode::CREATED);
    let media_id = json_body(upload).await["media_object_id"]
        .as_str()
        .unwrap()
        .to_string();
    (user_id, media_id)
}

#[tokio::test]
async fn legacy_volume_moves_into_nebular() {
    let Some((app, state, nebular)) = test_setup().await else {
        eprintln!(
            "skipping legacy_volume_moves_into_nebular: needs DATABASE_URL and SHROUD_TEST_NEBULAR_*"
        );
        return;
    };

    // An upload from before Nebular: the row names `{user}/{media}` and the blob is a file.
    let bytes: Vec<u8> = (0..70_000_u32).map(|i| (i % 253) as u8).collect();
    let (user_id, media_id) = user_with_upload(&app, bytes.len()).await;
    let legacy_key = format!("{user_id}/{media_id}");
    sqlx::query("UPDATE media_objects SET object_key = $1 WHERE id = $2")
        .bind(&legacy_key)
        .bind(Uuid::parse_str(&media_id).unwrap())
        .execute(&state.pool)
        .await
        .unwrap();
    let legacy_root = std::env::temp_dir().join(format!("shroud-legacy-volume-{}", Uuid::new_v4()));
    tokio::fs::create_dir_all(legacy_root.join(&user_id))
        .await
        .unwrap();
    tokio::fs::write(legacy_root.join(&legacy_key), &bytes)
        .await
        .unwrap();
    // A blob whose row a failed delete removed long ago: nothing names it.
    let stray = legacy_root.join(&user_id).join(Uuid::new_v4().to_string());
    tokio::fs::write(&stray, b"deleted media").await.unwrap();

    let store =
        MediaStore::nebular(nebular, "shroud-media", Some(legacy_root.clone())).expect("store");

    // Until it moves, the blob is served from the volume.
    let (blob, source) = store.get("shroud-media", &legacy_key).await.expect("get");
    assert_eq!(source, BlobSource::Legacy);
    assert_eq!(read_all(blob).await, bytes);

    let metrics = Metrics::new();
    let report = store
        .migrate_legacy(&state.pool, &metrics)
        .await
        .expect("migration");
    assert_eq!(report.moved, 1);
    assert_eq!(report.removed_unreferenced, 1);

    let (blob, source) = store.get("shroud-media", &legacy_key).await.expect("get");
    assert_eq!(source, BlobSource::Primary, "Nebular serves it now");
    assert_eq!(read_all(blob).await, bytes);
    assert!(
        !legacy_root.join(&legacy_key).exists(),
        "the local copy is gone"
    );
    assert!(!stray.exists(), "unreferenced blobs are deleted");
    assert!(
        !legacy_root.join(&user_id).exists(),
        "emptied user directories are removed"
    );

    // Nothing left to do on the next start.
    let again = store
        .migrate_legacy(&state.pool, &metrics)
        .await
        .expect("second pass");
    assert_eq!((again.moved, again.removed_unreferenced), (0, 0));

    store
        .delete("shroud-media", &legacy_key)
        .await
        .expect("cleanup");
    assert!(matches!(
        store.get("shroud-media", &legacy_key).await,
        Err(MediaStoreError::NotFound)
    ));
    let _ = tokio::fs::remove_dir_all(&legacy_root).await;
}

#[tokio::test]
async fn a_wrong_secret_is_refused_not_mistaken_for_a_missing_blob() {
    let Some((_app, _state, mut nebular)) = test_setup().await else {
        eprintln!(
            "skipping a_wrong_secret_is_refused_not_mistaken_for_a_missing_blob: needs DATABASE_URL and SHROUD_TEST_NEBULAR_*"
        );
        return;
    };
    nebular.secret_access_key = "not-the-secret-0123456789abcdef".into();
    let store = MediaStore::nebular(nebular, "shroud-media", None).expect("store");
    assert!(matches!(
        store.check().await,
        Err(MediaStoreError::Unavailable(detail)) if detail.contains("401")
    ));
    assert!(matches!(
        store.get("shroud-media", "media/aa/anything").await,
        Err(MediaStoreError::Unavailable(_))
    ));
}

#[tokio::test]
async fn buckets_outside_the_policy_are_refused() {
    let Some((_app, _state, nebular)) = test_setup().await else {
        eprintln!(
            "skipping buckets_outside_the_policy_are_refused: needs DATABASE_URL and SHROUD_TEST_NEBULAR_*"
        );
        return;
    };
    let store = MediaStore::nebular(nebular, "shroud-media", None).expect("store");
    store.check().await.expect("its own bucket is fine");
    assert!(matches!(
        store.put("other-bucket", "media/aa/x", Bytes::from_static(b"x")).await,
        Err(MediaStoreError::Unavailable(detail)) if detail.contains("403")
    ));
}
