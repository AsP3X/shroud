//! Integration tests for `GET /client-version`.
//!
//! Human: The route never touches Postgres, so these run without `DATABASE_URL`: the pool is
//! lazy and never connects.
//! Agent: GET /client-version for ios/android/web, signed out; 400 on bad input; the
//! `X-Shroud-Client` gate (426 below the minimum or without the header), through the same
//! middleware stack `run` serves (`shroud_server::app`).

use std::sync::Arc;

use axum::http::{Request, StatusCode};
use axum::{Router, body::Body};
use http_body_util::BodyExt;
use shroud_server::client_version::{AppRelease, ClientVersions, Version, WebBuild};
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;

fn app() -> Router {
    let pool = PgPoolOptions::new()
        .acquire_timeout(std::time::Duration::from_millis(200))
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
    shroud_server::app(state, &["https://web.example".to_string()])
}

async fn get(uri: &str) -> (StatusCode, serde_json::Value) {
    get_as(None, uri).await
}

async fn get_as(client_header: Option<&str>, uri: &str) -> (StatusCode, serde_json::Value) {
    let mut request = Request::get(uri);
    if let Some(client) = client_header {
        request = request.header("x-shroud-client", client);
    }
    let response = app()
        .oneshot(request.body(Body::empty()).unwrap())
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
    let (_, body) = get_as(
        Some("ios/1.3"),
        "/api/v1/client-version?platform=ios&version=1.3",
    )
    .await;
    assert_eq!(body["status"], "update_available");
    let (_, body) = get_as(
        Some("ios/1.4.0"),
        "/api/v1/client-version?platform=ios&version=1.4.0",
    )
    .await;
    assert_eq!(body["status"], "current");
}

#[tokio::test]
async fn android_with_nothing_published_is_current() {
    let (status, body) = get_as(
        Some("android/0.1.0"),
        "/api/v1/client-version?platform=android&version=0.1.0",
    )
    .await;
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
async fn an_app_too_old_to_name_itself_is_told_to_update_even_without_its_own_minimum() {
    // Only iOS has a minimum, but an unnamed Android build is refused everywhere else: its check
    // must say so, or the app would fail every request without the blocking screen.
    let (status, body) = get("/api/v1/client-version?platform=android&version=0.1.0").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "update_required");
    let (_, body) = get("/api/v1/client-version?platform=ios&version=1.4.0").await;
    assert_eq!(body["status"], "update_required");
    let (_, body) = get_as(
        Some("ios/1.4.0"),
        "/api/v1/client-version?platform=ios&version=1.4.0",
    )
    .await;
    assert_eq!(body["status"], "current");
    // Web tabs are only ever offered a reload.
    let (_, body) = get("/api/v1/client-version?platform=web&version=0a1b2c3d4e5f").await;
    assert_eq!(body["status"], "current");
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

/// `GET /auth/me` signed out: 401 once the gate lets it through, without touching Postgres.
async fn me_as(client_header: Option<&str>, query: &str) -> (StatusCode, serde_json::Value) {
    let mut request = Request::get(format!("/api/v1/auth/me{query}"));
    if let Some(client) = client_header {
        request = request.header("x-shroud-client", client);
    }
    let response = app()
        .oneshot(request.body(Body::empty()).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let body = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&body).unwrap_or_default())
}

#[tokio::test]
async fn apps_below_the_minimum_or_unnamed_are_refused() {
    for (header, query) in [(None, ""), (Some("ios/1.1.9"), ""), (Some("ios"), "")] {
        let (status, body) = me_as(header, query).await;
        assert_eq!(status, StatusCode::UPGRADE_REQUIRED, "{header:?}");
        assert_eq!(body["error"]["code"], "UPDATE_REQUIRED", "{header:?}");
    }
}

#[tokio::test]
async fn named_supported_apps_reach_the_route() {
    for (header, query) in [
        (Some("ios/1.2.0"), ""),
        (Some("android/0.1.0"), ""),
        (Some("web/0a1b2c3d4e5f"), ""),
        (None, "?client=web%2F0a1b2c3d4e5f"),
    ] {
        let (status, _) = me_as(header, query).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "{header:?} {query}");
    }
}

#[tokio::test]
async fn the_version_check_stays_open_to_unnamed_builds() {
    let (status, body) = get("/api/v1/client-version?platform=ios&version=1.0").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "update_required");
}

#[tokio::test]
async fn health_stays_open_to_unnamed_builds() {
    // `/health` and `/health/ready` ask Postgres, which these tests don't have; they share the
    // open list with this one.
    let response = app()
        .oneshot(
            Request::get("/api/v1/health/live")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn the_header_wins_over_the_query_parameter() {
    let (status, _) = me_as(Some("ios/1.0"), "?client=web%2F0a1b2c3d4e5f").await;
    assert_eq!(status, StatusCode::UPGRADE_REQUIRED);
    let (status, _) = me_as(Some("ios/1.2"), "?client=ios%2F1.0").await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn websocket_upgrades_are_gated_and_named_by_query() {
    for path in ["/api/v1/ws", "/api/v1/link-relay"] {
        let (status, body) = get(path).await;
        assert_eq!(status, StatusCode::UPGRADE_REQUIRED, "{path}");
        assert_eq!(body["error"]["code"], "UPDATE_REQUIRED", "{path}");
        // A plain GET is no upgrade, so the route refuses it, but only after the gate let it in.
        let (status, _) = get(&format!("{path}?client=web%2F0a1b2c3d4e5f")).await;
        assert_ne!(status, StatusCode::UPGRADE_REQUIRED, "{path}");
    }
}

#[tokio::test]
async fn cors_preflight_allows_the_header_before_the_gate() {
    let response = app()
        .oneshot(
            Request::options("/api/v1/auth/me")
                .header("origin", "https://web.example")
                .header("access-control-request-method", "GET")
                .header("access-control-request-headers", "x-shroud-client")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let allowed = response
        .headers()
        .get("access-control-allow-headers")
        .unwrap()
        .to_str()
        .unwrap();
    assert!(allowed.contains("x-shroud-client"), "{allowed}");
}

#[tokio::test]
async fn a_refused_app_with_a_token_keeps_the_426_when_the_session_cannot_be_checked() {
    // The pool here never connects: the session lookup fails, and the 426 stands.
    let response = app()
        .oneshot(
            Request::get("/api/v1/auth/me")
                .header("x-shroud-client", "ios/1.0")
                .header("authorization", "Bearer some-token")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UPGRADE_REQUIRED);
}
