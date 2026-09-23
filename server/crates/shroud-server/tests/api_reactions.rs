//! Integration tests for message reactions (`/messages/:id/reaction`, catch-up, history).

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use shroud_server::routes;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

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
            .with_state(shroud_server::state::AppState::for_integration_tests(pool)),
    )
}

async fn call(
    app: &axum::Router,
    method: &str,
    uri: &str,
    token: &str,
    body: Option<Value>,
) -> (StatusCode, Value) {
    let mut builder = Request::builder()
        .method(method)
        .uri(uri)
        .header(header::AUTHORIZATION, format!("Bearer {token}"));
    let body = match body {
        Some(value) => {
            builder = builder.header(header::CONTENT_TYPE, "application/json");
            Body::from(value.to_string())
        }
        None => Body::empty(),
    };
    let response = app
        .clone()
        .oneshot(builder.body(body).expect("request"))
        .await
        .expect("response");
    let status = response.status();
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    let value = if bytes.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&bytes).expect("json")
    };
    (status, value)
}

async fn register(app: &axum::Router) -> (String, String) {
    let id = &Uuid::new_v4().simple().to_string()[..12];
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/register")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    json!({ "username": format!("r_{id}"), "password": "correct-horse-battery" })
                        .to_string(),
                ))
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::CREATED);
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    let body: Value = serde_json::from_slice(&bytes).expect("json");
    (
        body["token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
    )
}

async fn become_contacts(app: &axum::Router, a: &(String, String), b: &(String, String)) {
    let (status, _) = call(
        app,
        "POST",
        "/api/v1/contacts/requests",
        &a.0,
        Some(json!({ "user_id": b.1 })),
    )
    .await;
    assert!(status.is_success());
    let (status, _) = call(
        app,
        "POST",
        "/api/v1/contacts/requests",
        &b.0,
        Some(json!({ "user_id": a.1 })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
}

async fn send(app: &axum::Router, from: &(String, String), to: &str, content_type: &str) -> String {
    let (status, body) = call(
        app,
        "POST",
        "/api/v1/messages",
        &from.0,
        Some(json!({
            "peer_user_id": to,
            "client_message_id": Uuid::new_v4(),
            "content_type": content_type,
            "ciphertext": BASE64.encode(b"sealed"),
        })),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    body["id"].as_str().unwrap().to_string()
}

async fn react(
    app: &axum::Router,
    who: &(String, String),
    message_id: &str,
    blob: &[u8],
) -> (StatusCode, Value) {
    call(
        app,
        "PUT",
        &format!("/api/v1/messages/{message_id}/reaction"),
        &who.0,
        Some(json!({ "ciphertext": BASE64.encode(blob) })),
    )
    .await
}

async fn changes(app: &axum::Router, who: &(String, String), peer: &str, after: i64) -> Value {
    let (status, body) = call(
        app,
        "GET",
        &format!("/api/v1/conversations/{peer}/reactions?after_seq={after}"),
        &who.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body
}

async fn history(app: &axum::Router, who: &(String, String), peer: &str) -> Value {
    let (status, body) = call(
        app,
        "GET",
        &format!("/api/v1/messages?peer_user_id={peer}"),
        &who.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body
}

fn find_message<'a>(page: &'a Value, id: &str) -> &'a Value {
    page["messages"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["id"] == id)
        .expect("message in page")
}

#[tokio::test]
async fn set_replace_remove_and_catch_up() {
    let Some(app) = test_app().await else {
        eprintln!("skipping set_replace_remove_and_catch_up: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let message = send(&app, &a, &b.1, "text").await;

    let start = history(&app, &b, &a.1).await["reaction_seq"]
        .as_i64()
        .expect("newest page carries reaction_seq");

    // B reacts, then replaces the reaction: one row, a newer seq.
    let (status, first) = react(&app, &b, &message, b"heart").await;
    assert_eq!(status, StatusCode::OK, "{first}");
    assert_eq!(first["user_id"], b.1);
    let (status, second) = react(&app, &b, &message, b"fire").await;
    assert_eq!(status, StatusCode::OK);
    assert!(second["seq"].as_i64() > first["seq"].as_i64());

    // A reacts too.
    let (status, _) = react(&app, &a, &message, b"thumbs").await;
    assert_eq!(status, StatusCode::OK);

    let page = history(&app, &a, &b.1).await;
    let reactions = find_message(&page, &message)["reactions"]
        .as_array()
        .expect("reactions embedded");
    assert_eq!(reactions.len(), 2);
    assert_eq!(reactions[0]["ciphertext"], BASE64.encode(b"fire"));
    assert_eq!(reactions[1]["user_id"], a.1);

    // B takes it back; a second removal is a no-op.
    let (status, removed) = call(
        &app,
        "DELETE",
        &format!("/api/v1/messages/{message}/reaction"),
        &b.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(removed["ciphertext"].is_null());
    let (status, _) = call(
        &app,
        "DELETE",
        &format!("/api/v1/messages/{message}/reaction"),
        &b.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    let page = history(&app, &a, &b.1).await;
    assert_eq!(
        page["reaction_seq"], removed["seq"],
        "snapshot covers the removal"
    );
    assert_eq!(
        find_message(&page, &message)["reactions"]
            .as_array()
            .unwrap()
            .len(),
        1
    );

    // Catch-up from before any of it: one row per user, the latest state, oldest seq first.
    let caught_up = changes(&app, &a, &b.1, start).await;
    let rows = caught_up["reactions"].as_array().unwrap();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0]["user_id"], a.1);
    assert_eq!(rows[1]["user_id"], b.1);
    assert!(rows[1]["ciphertext"].is_null(), "removal is reported");
    assert_eq!(caught_up["next_seq"], rows[1]["seq"]);

    // Nothing new after the cursor.
    let next = caught_up["next_seq"].as_i64().unwrap();
    let empty = changes(&app, &a, &b.1, next).await;
    assert!(empty["reactions"].as_array().unwrap().is_empty());
    assert_eq!(empty["next_seq"], next);
}

#[tokio::test]
async fn reaction_access_rules() {
    let Some(app) = test_app().await else {
        eprintln!("skipping reaction_access_rules: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    let outsider = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let message = send(&app, &a, &b.1, "text").await;

    let (status, _) = react(&app, &outsider, &message, b"heart").await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "outsiders cannot tell it exists"
    );
    let (status, _) = react(&app, &b, &Uuid::new_v4().to_string(), b"heart").await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/messages/{message}/reaction"),
        &b.0,
        Some(json!({ "ciphertext": "not base64!" })),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _) = react(&app, &b, &message, &[7u8; 4 * 1024 + 1]).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _) = react(&app, &b, &message, b"").await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    let annotation = send(&app, &a, &b.1, "annotation").await;
    let (status, _) = react(&app, &b, &annotation, b"heart").await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // Saved Messages: reacting to your own note works.
    let note = send(&app, &a, &a.1, "text").await;
    let (status, _) = react(&app, &a, &note, b"star").await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn delete_for_everyone_clears_reactions() {
    let Some(app) = test_app().await else {
        eprintln!("skipping delete_for_everyone_clears_reactions: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let message = send(&app, &a, &b.1, "text").await;

    let (status, set) = react(&app, &b, &message, b"heart").await;
    assert_eq!(status, StatusCode::OK);
    let cursor = set["seq"].as_i64().unwrap();

    let (status, _) = call(
        &app,
        "DELETE",
        &format!("/api/v1/messages/{message}?scope=everyone"),
        &a.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    // The removal reaches devices that only knew the reaction.
    let caught_up = changes(&app, &b, &a.1, cursor).await;
    let rows = caught_up["reactions"].as_array().unwrap();
    assert_eq!(rows.len(), 1);
    assert!(rows[0]["ciphertext"].is_null());

    let page = history(&app, &b, &a.1).await;
    assert!(find_message(&page, &message).get("reactions").is_none());

    let (status, _) = react(&app, &b, &message, b"heart").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

async fn conversation_entry(app: &axum::Router, who: &(String, String), peer: &str) -> Value {
    let (status, body) = call(app, "GET", "/api/v1/conversations", &who.0, None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body["conversations"]
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["peer"]["id"] == peer)
        .cloned()
        .expect("conversation listed")
}

async fn mark_seen(app: &axum::Router, who: &(String, String), peer: &str, up_to: i64) -> Value {
    let (status, body) = call(
        app,
        "POST",
        &format!("/api/v1/conversations/{peer}/reactions/seen"),
        &who.0,
        Some(json!({ "up_to_seq": up_to })),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body
}

#[tokio::test]
async fn concurrent_writes_get_ordered_unique_seqs() {
    let Some(app) = test_app().await else {
        eprintln!("skipping concurrent_writes_get_ordered_unique_seqs: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let mut messages = Vec::new();
    for _ in 0..3 {
        messages.push(send(&app, &a, &b.1, "text").await);
    }

    // Both people tapping away on the same chat at once: sets, replaces and removals interleave.
    let mut tasks = Vec::new();
    for i in 0..30usize {
        let app = app.clone();
        let who = if i % 2 == 0 { a.clone() } else { b.clone() };
        let message = messages[i % messages.len()].clone();
        tasks.push(tokio::spawn(async move {
            if i % 5 == 4 {
                call(
                    &app,
                    "DELETE",
                    &format!("/api/v1/messages/{message}/reaction"),
                    &who.0,
                    None,
                )
                .await
            } else {
                react(&app, &who, &message, format!("emoji-{i}").as_bytes()).await
            }
        }));
    }
    let mut seqs = Vec::new();
    for task in tasks {
        let (status, body) = task.await.expect("task");
        match status {
            StatusCode::OK => seqs.push(body["seq"].as_i64().unwrap()),
            StatusCode::NO_CONTENT => {}
            other => panic!("unexpected {other}: {body}"),
        }
    }
    let unique: std::collections::HashSet<_> = seqs.iter().copied().collect();
    assert_eq!(unique.len(), seqs.len(), "every write gets its own number");

    // The snapshot is the last write, and catch-up from zero ends exactly there, in order.
    let snapshot = history(&app, &a, &b.1).await["reaction_seq"]
        .as_i64()
        .unwrap();
    assert_eq!(snapshot, *seqs.iter().max().unwrap());
    let caught_up = changes(&app, &a, &b.1, 0).await;
    let rows: Vec<i64> = caught_up["reactions"]
        .as_array()
        .unwrap()
        .iter()
        .map(|row| row["seq"].as_i64().unwrap())
        .collect();
    assert!(rows.windows(2).all(|w| w[0] < w[1]), "ascending: {rows:?}");
    assert_eq!(caught_up["next_seq"].as_i64(), Some(snapshot));
}

#[tokio::test]
async fn unseen_reactions_count_and_clear() {
    let Some(app) = test_app().await else {
        eprintln!("skipping unseen_reactions_count_and_clear: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let mine = send(&app, &a, &b.1, "text").await;

    let (_, set) = react(&app, &b, &mine, b"heart").await;
    let entry = conversation_entry(&app, &a, &b.1).await;
    assert_eq!(entry["unseen_reactions"], 1, "B reacted to A's message");
    assert_eq!(entry["reaction_seq"], set["seq"]);
    assert_eq!(
        conversation_entry(&app, &b, &a.1).await["unseen_reactions"],
        0
    );

    // A opens the chat: seen, clamped to what exists.
    let seen = mark_seen(&app, &a, &b.1, 9_999).await;
    assert_eq!(seen["seen_seq"], set["seq"]);
    assert_eq!(
        conversation_entry(&app, &a, &b.1).await["unseen_reactions"],
        0
    );
    // Never backwards.
    assert_eq!(mark_seen(&app, &a, &b.1, 0).await["seen_seq"], set["seq"]);

    // A changed reaction is new again; a removed one is not there to see.
    react(&app, &b, &mine, b"fire").await;
    assert_eq!(
        conversation_entry(&app, &a, &b.1).await["unseen_reactions"],
        1
    );
    call(
        &app,
        "DELETE",
        &format!("/api/v1/messages/{mine}/reaction"),
        &b.0,
        None,
    )
    .await;
    assert_eq!(
        conversation_entry(&app, &a, &b.1).await["unseen_reactions"],
        0
    );

    // Your own reactions, and reactions to the other side's messages, never count for you.
    react(&app, &a, &mine, b"star").await;
    assert_eq!(
        conversation_entry(&app, &a, &b.1).await["unseen_reactions"],
        0
    );
    let theirs = send(&app, &b, &a.1, "text").await;
    react(&app, &a, &theirs, b"thumbs").await;
    assert_eq!(
        conversation_entry(&app, &a, &b.1).await["unseen_reactions"],
        0
    );
    assert_eq!(
        conversation_entry(&app, &b, &a.1).await["unseen_reactions"],
        1
    );
}

#[tokio::test]
async fn reacting_while_the_message_is_deleted_never_errors() {
    let Some(app) = test_app().await else {
        eprintln!("skipping reacting_while_the_message_is_deleted_never_errors: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;

    for round in 0..5 {
        let message = send(&app, &a, &b.1, "text").await;
        let mut tasks = Vec::new();
        for i in 0..8usize {
            let app = app.clone();
            let who = if i % 2 == 0 { a.clone() } else { b.clone() };
            let message = message.clone();
            tasks.push(tokio::spawn(async move {
                react(&app, &who, &message, format!("r{round}-{i}").as_bytes()).await
            }));
        }
        let deleter = {
            let app = app.clone();
            let a = a.clone();
            let message = message.clone();
            tokio::spawn(async move {
                call(
                    &app,
                    "DELETE",
                    &format!("/api/v1/messages/{message}?scope=everyone"),
                    &a.0,
                    None,
                )
                .await
            })
        };
        for task in tasks {
            let (status, body) = task.await.expect("task");
            assert!(
                status == StatusCode::OK || status == StatusCode::NOT_FOUND,
                "a lock-order deadlock would surface as a 500: {status} {body}"
            );
        }
        assert_eq!(deleter.await.expect("task").0, StatusCode::NO_CONTENT);

        // Whatever won the race, nothing live is left on the deleted message.
        let page = history(&app, &b, &a.1).await;
        assert!(find_message(&page, &message).get("reactions").is_none());
        let rows = changes(&app, &b, &a.1, 0).await;
        assert!(
            rows["reactions"]
                .as_array()
                .unwrap()
                .iter()
                .filter(|row| row["message_id"] == message.as_str())
                .all(|row| row["ciphertext"].is_null())
        );
    }
}

#[tokio::test]
async fn deleting_the_chat_for_everyone_while_reacting_never_errors() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping deleting_the_chat_for_everyone_while_reacting_never_errors: no DATABASE_URL"
        );
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let mut messages = Vec::new();
    for _ in 0..4 {
        messages.push(send(&app, &a, &b.1, "text").await);
    }
    // One reaction already there before the race: it must not outlive the tombstone.
    let (status, _) = react(&app, &b, &messages[0], b"heart").await;
    assert_eq!(status, StatusCode::OK);

    let mut tasks = Vec::new();
    for i in 0..12usize {
        let app = app.clone();
        let b = b.clone();
        let message = messages[i % messages.len()].clone();
        tasks.push(tokio::spawn(async move {
            react(&app, &b, &message, format!("r{i}").as_bytes()).await
        }));
    }
    let deleter = {
        let app = app.clone();
        let a = a.clone();
        let peer = b.1.clone();
        tokio::spawn(async move {
            call(
                &app,
                "DELETE",
                &format!("/api/v1/conversations/{peer}?scope=everyone"),
                &a.0,
                None,
            )
            .await
        })
    };
    for task in tasks {
        let (status, body) = task.await.expect("task");
        // Before the delete: fine. After it: the message is gone or the contact link is.
        assert!(
            matches!(
                status,
                StatusCode::OK | StatusCode::NOT_FOUND | StatusCode::FORBIDDEN
            ),
            "a lock-order deadlock would surface as a 500: {status} {body}"
        );
    }
    let (status, body) = deleter.await.expect("task");
    assert_eq!(status, StatusCode::OK, "{body}");

    // B keeps A's messages as tombstones (no consent was given): no sealed reaction survives.
    let caught_up = changes(&app, &b, &a.1, 0).await;
    for row in caught_up["reactions"].as_array().unwrap() {
        if messages.iter().any(|m| row["message_id"] == m.as_str()) {
            assert!(
                row["ciphertext"].is_null(),
                "live reaction on a tombstone: {row}"
            );
        }
    }
}
