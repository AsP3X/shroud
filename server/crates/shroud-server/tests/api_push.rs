//! Integration tests for APNs token registration.

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
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

#[tokio::test]
async fn register_push_token() {
    let Some(app) = test_app().await else {
        eprintln!("skipping register_push_token: no DATABASE_URL");
        return;
    };

    let username = format!("p_{}", &Uuid::new_v4().simple().to_string()[..12]);
    let reg = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "username": username,
                        "password": "correct-horse-battery"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(reg.status(), StatusCode::CREATED);
    let body = json_body(reg).await;
    let token = body["token"].as_str().unwrap();

    let put = app
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/push/token")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({
                        "token": "abcdef0123456789",
                        "environment": "sandbox"
                    })
                    .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(put.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn logout_forgets_the_devices_push_token() {
    let Ok(database_url) = std::env::var("DATABASE_URL") else {
        eprintln!("skipping logout_forgets_the_devices_push_token: no DATABASE_URL");
        return;
    };
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("connect");
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .expect("migrate");
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(test_state(pool.clone()));

    let username = format!("p_{}", &Uuid::new_v4().simple().to_string()[..12]);
    let reg = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username": username, "password": "correct-horse-battery" })
                        .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(reg.status(), StatusCode::CREATED);
    let body = json_body(reg).await;
    let token = body["token"].as_str().unwrap().to_owned();
    let device_id: Uuid = body["device"]["id"].as_str().unwrap().parse().unwrap();

    let put = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/push/token")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "token": "abcdef0123456789", "environment": "sandbox" }).to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(put.status(), StatusCode::NO_CONTENT);

    let tokens = || async {
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM push_tokens WHERE device_id = $1")
            .bind(device_id)
            .fetch_one(&pool)
            .await
            .expect("count tokens")
    };
    assert_eq!(tokens().await, 1);

    let logout = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/logout")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);

    // A logged-out phone must not keep receiving this account's pushes and calls.
    assert_eq!(tokens().await, 0);
    // The device itself stays: its messages and uploads cascade from it.
    let devices: i64 = sqlx::query_scalar("SELECT count(*) FROM devices WHERE id = $1")
        .bind(device_id)
        .fetch_one(&pool)
        .await
        .expect("count devices");
    assert_eq!(devices, 1);
}
