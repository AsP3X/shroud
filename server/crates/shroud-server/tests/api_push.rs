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
    let realtime = std::sync::Arc::new(shroud_server::realtime::RealtimeHub::new());
    let push = shroud_server::push::PushService::new(pool.clone(), realtime.clone(), None);
    shroud_server::state::AppState {
        pool,
        nebular_url: None,
        media_bucket: "shroud-media".into(),
        realtime,
        push,
        ice_servers: vec![],
        rate_limiter: shroud_server::rate_limit::RateLimiter::disabled(),
        redis_required: false,
    }
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
