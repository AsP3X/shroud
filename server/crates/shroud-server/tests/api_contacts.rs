//! Integration tests for contacts, requests, and blocks.

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

/// Returns (token, user_id, username, share_code).
async fn register_full(app: &axum::Router) -> (String, String, String, String) {
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
        username.to_string(),
        body["user"]["share_code"].as_str().unwrap().to_string(),
    )
}

#[tokio::test]
async fn lookup_user_by_username_and_share_code() {
    let Some(app) = test_app().await else {
        eprintln!("skipping lookup_user_by_username_and_share_code: no DATABASE_URL");
        return;
    };

    let (token_a, _id_a, _name_a, _code_a) = register_full(&app).await;
    let (_token_b, id_b, name_b, code_b) = register_full(&app).await;

    let by_name = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/users/by-username/{name_b}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(by_name.status(), StatusCode::NOT_FOUND);

    let by_code = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/users/by-code/{code_b}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(by_code.status(), StatusCode::OK);
    let code_json = json_body(by_code).await;
    assert_eq!(code_json["id"], id_b);
    assert_eq!(code_json["share_code"], code_b);
    assert!(code_json.get("username").is_none());

    let missing = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/users/by-code/ZZZZZZZZZZ")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn a_name_is_sealed_only_for_a_contact() {
    let Some(app) = test_app().await else {
        eprintln!("skipping a_name_is_sealed_only_for_a_contact: no DATABASE_URL");
        return;
    };

    let (token_a, id_a, _, _) = register_full(&app).await;
    let (token_b, id_b, _, _) = register_full(&app).await;
    let (token_c, _, _, _) = register_full(&app).await;
    let sealed = "ek.ct.t.opaque";

    let stranger = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri(format!("/api/v1/contacts/{id_b}/sealed-name"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "sealed": sealed }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(stranger.status(), StatusCode::NOT_FOUND);

    for (token, peer) in [(&token_a, &id_b), (&token_b, &id_a)] {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/contacts/requests")
                    .header(header::AUTHORIZATION, format!("Bearer {token}"))
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(json!({ "user_id": peer }).to_string()))
                    .expect("request"),
            )
            .await
            .expect("response");
        assert!(response.status().is_success(), "{}", response.status());
    }

    let saved = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri(format!("/api/v1/contacts/{id_b}/sealed-name"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "sealed": sealed }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(saved.status(), StatusCode::NO_CONTENT);

    let listed = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/contacts")
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(listed.status(), StatusCode::OK);
    let body = json_body(listed).await;
    let row = body["contacts"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["user_id"] == id_a.as_str())
        .expect("B lists A");
    assert!(row.get("username").is_none());
    assert_eq!(row["sealed_name"], sealed);

    let outsider = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/contacts")
                .header(header::AUTHORIZATION, format!("Bearer {token_c}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let outsider = json_body(outsider).await;
    assert!(outsider["contacts"].as_array().unwrap().is_empty());

    let removed = app
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri(format!("/api/v1/contacts/{id_a}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(removed.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn request_accept_lists_contacts() {
    let Some(app) = test_app().await else {
        eprintln!("skipping request_accept_lists_contacts: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;

    let card = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/users/{user_b}"))
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(card.status(), StatusCode::OK);

    let create = app
        .clone()
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
    assert_eq!(create.status(), StatusCode::CREATED);
    let req = json_body(create).await;
    let request_id = req["id"].as_str().unwrap();

    let accept = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/contacts/requests/{request_id}/accept"))
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(accept.status(), StatusCode::OK);

    let contacts_a = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/contacts")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let list_a = json_body(contacts_a).await;
    assert_eq!(list_a["contacts"].as_array().unwrap().len(), 1);
    assert_eq!(list_a["contacts"][0]["user_id"], user_b);

    let contacts_b = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/contacts")
                .header(header::AUTHORIZATION, format!("Bearer {token_b}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let list_b = json_body(contacts_b).await;
    assert_eq!(list_b["contacts"][0]["user_id"], user_a);
}

#[tokio::test]
async fn mutual_requests_auto_accept() {
    let Some(app) = test_app().await else {
        eprintln!("skipping mutual_requests_auto_accept: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;

    let r1 = app
        .clone()
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
    assert_eq!(r1.status(), StatusCode::CREATED);

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
    let body = json_body(r2).await;
    assert_eq!(body["status"], "accepted");

    let contacts = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/contacts")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let list = json_body(contacts).await;
    assert_eq!(list["contacts"].as_array().unwrap().len(), 1);
}

#[tokio::test]
async fn block_removes_contact_and_blocks_request() {
    let Some(app) = test_app().await else {
        eprintln!("skipping block_removes_contact_and_blocks_request: no DATABASE_URL");
        return;
    };

    let (token_a, user_a) = register(&app).await;
    let (token_b, user_b) = register(&app).await;

    // Become contacts via mutual auto-accept.
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
    app.clone()
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

    let block = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/blocks")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "user_id": user_b }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(block.status(), StatusCode::NO_CONTENT);

    let contacts = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/contacts")
                .header(header::AUTHORIZATION, format!("Bearer {token_a}"))
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    let list = json_body(contacts).await;
    assert_eq!(list["contacts"].as_array().unwrap().len(), 0);

    let request_after = app
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
    assert_eq!(request_after.status(), StatusCode::FORBIDDEN);
}
