//! Integration tests for `GET /client-version`.
//!
//! Human: The route never touches Postgres, so these run without `DATABASE_URL`: the pool is
//! lazy and never connects.
//! Agent: GET /client-version for ios/android/web, signed out; 400 on bad input.

use std::sync::Arc;

use axum::http::{Request, StatusCode};
use axum::{Router, body::Body};
use http_body_util::BodyExt;
use shroud_server::client_version::{AppRelease, ClientVersions, Version, WebBuild};
use shroud_server::routes;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;

fn app() -> Router {
    let pool = PgPoolOptions::new()
        .connect_lazy("postgres://unused@127.0.0.1:1/unused")
        .expect("lazy pool");
    let mut state = shroud_server::state::AppState::for_integration_tests(pool);
    state.client_versions = Arc::new(ClientVersions {
        ios: AppRelease {
            latest: Version::parse_strict("1.4.0"),
            minimum: Version::parse_strict("1.2"),
            update_url: Some("https://testflight.apple.com/join/abc".into()),
        },
        android: AppRelease::default(),
        web_build: WebBuild::Fixed("0a1b2c3d4e5f".into()),
    });
    Router::new().merge(routes::router()).with_state(state)
}

async fn get(uri: &str) -> (StatusCode, serde_json::Value) {
    let response = app()
        .oneshot(Request::get(uri).body(Body::empty()).unwrap())
        .await
        .unwrap();
    let status = response.status();
    if status == StatusCode::OK {
        assert_eq!(
            response.headers().get("cache-control").unwrap(),
            "no-store",
            "{uri}"
        );
    }
    let body = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&body).unwrap_or_default())
}

#[tokio::test]
async fn ios_below_minimum_must_update_without_signing_in() {
    let (status, body) = get("/api/v1/client-version?platform=ios&version=1.1.9").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        body,
        serde_json::json!({
            "status": "update_required",
            "latest_version": "1.4.0",
            "update_url": "https://testflight.apple.com/join/abc",
            "server_version": env!("CARGO_PKG_VERSION"),
        })
    );
}

#[tokio::test]
async fn ios_between_minimum_and_latest_may_update() {
    let (_, body) = get("/api/v1/client-version?platform=ios&version=1.3").await;
    assert_eq!(body["status"], "update_available");
    let (_, body) = get("/api/v1/client-version?platform=ios&version=1.4.0").await;
    assert_eq!(body["status"], "current");
}

#[tokio::test]
async fn android_with_nothing_published_is_current() {
    let (status, body) = get("/api/v1/client-version?platform=android&version=0.1.0").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        body,
        serde_json::json!({
            "status": "current",
            "latest_version": null,
            "update_url": null,
            "server_version": env!("CARGO_PKG_VERSION"),
        })
    );
}

#[tokio::test]
async fn web_tab_from_another_build_is_offered_a_reload() {
    let (_, body) = get("/api/v1/client-version?platform=web&version=ffffffffffff").await;
    assert_eq!(body["status"], "update_available");
    assert_eq!(body["latest_version"], "0a1b2c3d4e5f");
    let (_, body) = get("/api/v1/client-version?platform=web&version=0a1b2c3d4e5f").await;
    assert_eq!(body["status"], "current");
}

#[tokio::test]
async fn bad_input_is_a_validation_error() {
    for uri in [
        "/api/v1/client-version?platform=windows&version=1.0",
        "/api/v1/client-version?platform=ios&version=beta",
        "/api/v1/client-version?platform=ios&version=",
    ] {
        let (status, body) = get(uri).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{uri}");
        assert_eq!(body["error"]["code"], "VALIDATION_ERROR", "{uri}");
    }
    for uri in [
        "/api/v1/client-version?platform=ios",
        "/api/v1/client-version?version=1.0",
        "/api/v1/client-version",
    ] {
        let (status, body) = get(uri).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{uri}");
        assert_eq!(body["error"]["code"], "VALIDATION_ERROR", "{uri}");
    }
}
