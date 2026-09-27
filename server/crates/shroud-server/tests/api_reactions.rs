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

/// None without `DATABASE_URL` (the tests skip). Set but unusable — the database down, or a
/// migration it already applied has changed since — fails loudly instead of skipping.
async fn test_app() -> Option<axum::Router> {
    let database_url = std::env::var("DATABASE_URL").ok()?;
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("connect to DATABASE_URL");
    sqlx::migrate!("../../migrations/postgres")
        .run(&pool)
        .await
        .expect("apply migrations (recreate a throwaway database whose migrations changed)");
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
        // Before the delete: fine. After it: the message is gone.
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

#[tokio::test]
async fn config_hands_out_the_reaction_limit() {
    let Some(app) = test_app().await else {
        eprintln!("skipping config_hands_out_the_reaction_limit: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let (status, body) = call(&app, "GET", "/api/v1/config", &a.0, None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["reactions"]["max_per_user"], 5);
}

async fn react_on(
    app: &axum::Router,
    who: &(String, String),
    message_id: &str,
    blob: &[u8],
    base_seq: Option<i64>,
    added: Option<bool>,
) -> (StatusCode, Value) {
    let mut body = json!({ "ciphertext": BASE64.encode(blob) });
    if let Some(base) = base_seq {
        body["base_seq"] = json!(base);
    }
    if let Some(added) = added {
        body["added"] = json!(added);
    }
    call(
        app,
        "PUT",
        &format!("/api/v1/messages/{message_id}/reaction"),
        &who.0,
        Some(body),
    )
    .await
}

async fn unreact(
    app: &axum::Router,
    who: &(String, String),
    message_id: &str,
    base_seq: Option<i64>,
) -> (StatusCode, Value) {
    let query = base_seq
        .map(|base| format!("?base_seq={base}"))
        .unwrap_or_default();
    call(
        app,
        "DELETE",
        &format!("/api/v1/messages/{message_id}/reaction{query}"),
        &who.0,
        None,
    )
    .await
}

fn seq_of(entry: &Value) -> i64 {
    entry["seq"].as_i64().expect("seq")
}

async fn unseen(app: &axum::Router, who: &(String, String), peer: &str) -> i64 {
    conversation_entry(app, who, peer).await["unseen_reactions"]
        .as_i64()
        .unwrap()
}

async fn hide(app: &axum::Router, who: &(String, String), message_id: &str) {
    let (status, body) = call(
        app,
        "DELETE",
        &format!("/api/v1/messages/{message_id}?scope=me"),
        &who.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT, "{body}");
}

async fn clear_chat(app: &axum::Router, who: &(String, String), peer: &str, scope: &str) -> Value {
    let (status, body) = call(
        app,
        "DELETE",
        &format!("/api/v1/conversations/{peer}?scope={scope}"),
        &who.0,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body
}

#[tokio::test]
async fn stale_writes_get_the_current_record_back() {
    let Some(app) = test_app().await else {
        eprintln!("skipping stale_writes_get_the_current_record_back: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let message = send(&app, &a, &b.1, "text").await;

    // B's phone and B's laptop both start from nothing; the phone is first.
    let (status, phone) = react_on(&app, &b, &message, b"heart", Some(0), None).await;
    assert_eq!(status, StatusCode::OK, "{phone}");
    let (status, conflict) = react_on(&app, &b, &message, b"fire", Some(0), None).await;
    assert_eq!(status, StatusCode::CONFLICT, "{conflict}");
    assert_eq!(conflict["error"]["code"], "REACTION_CHANGED");
    assert_eq!(conflict["current"]["seq"], phone["seq"]);
    assert_eq!(conflict["current"]["ciphertext"], BASE64.encode(b"heart"));

    // The laptop merges onto the phone's set and retries from it.
    let (status, merged) = react_on(
        &app,
        &b,
        &message,
        b"heart+fire",
        Some(seq_of(&phone)),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{merged}");

    // A removal built on the old set is refused as well, with the set as it is now.
    let (status, conflict) = unreact(&app, &b, &message, Some(seq_of(&phone))).await;
    assert_eq!(status, StatusCode::CONFLICT, "{conflict}");
    assert_eq!(conflict["current"]["seq"], merged["seq"]);
    let (status, removed) = unreact(&app, &b, &message, Some(seq_of(&merged))).await;
    assert_eq!(status, StatusCode::OK, "{removed}");
    assert!(removed["ciphertext"].is_null());

    // Nothing left to take back from a stale removal; "I had none" matches the removal.
    let (status, _) = unreact(&app, &b, &message, Some(seq_of(&phone))).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, again) = react_on(&app, &b, &message, b"thumbs", Some(0), None).await;
    assert_eq!(status, StatusCode::OK, "{again}");
    let (status, conflict) = react_on(&app, &b, &message, b"x", Some(seq_of(&removed)), None).await;
    assert_eq!(
        status,
        StatusCode::CONFLICT,
        "the removal is no longer current: {conflict}"
    );
    let (status, conflict) = react_on(&app, &b, &message, b"x", Some(0), None).await;
    assert_eq!(
        status,
        StatusCode::CONFLICT,
        "a live set is not nothing: {conflict}"
    );

    // Without base_seq a write simply wins.
    let (status, forced) = react_on(&app, &b, &message, b"forced", None, None).await;
    assert_eq!(status, StatusCode::OK, "{forced}");

    // Refused writes left no trace: the page holds what was written, and no number was lost.
    let page = history(&app, &a, &b.1).await;
    let reactions = find_message(&page, &message)["reactions"]
        .as_array()
        .unwrap()
        .clone();
    assert_eq!(reactions.len(), 1);
    assert_eq!(reactions[0]["ciphertext"], BASE64.encode(b"forced"));
    assert_eq!(page["reaction_seq"], forced["seq"]);
    let seqs: Vec<i64> = [&phone, &merged, &removed, &again, &forced]
        .iter()
        .map(|entry| seq_of(entry))
        .collect();
    assert!(
        seqs.windows(2).all(|w| w[1] == w[0] + 1),
        "refusals return their number: {seqs:?}"
    );
}

#[tokio::test]
async fn only_added_emoji_count_as_unseen() {
    let Some(app) = test_app().await else {
        eprintln!("skipping only_added_emoji_count_as_unseen: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let mine = send(&app, &a, &b.1, "text").await;

    let (_, first) = react_on(&app, &b, &mine, b"heart", Some(0), Some(true)).await;
    assert_eq!(unseen(&app, &a, &b.1).await, 1);
    mark_seen(&app, &a, &b.1, seq_of(&first)).await;

    let (_, grown) = react_on(
        &app,
        &b,
        &mine,
        b"heart+fire",
        Some(seq_of(&first)),
        Some(true),
    )
    .await;
    assert_eq!(unseen(&app, &a, &b.1).await, 1, "an added emoji is news");
    mark_seen(&app, &a, &b.1, seq_of(&grown)).await;

    // Taking one back is a change every device learns about, but nothing new to see.
    let (status, shrunk) =
        react_on(&app, &b, &mine, b"fire", Some(seq_of(&grown)), Some(false)).await;
    assert_eq!(status, StatusCode::OK, "{shrunk}");
    assert_eq!(unseen(&app, &a, &b.1).await, 0);
    let caught_up = changes(&app, &a, &b.1, seq_of(&grown)).await;
    assert_eq!(
        caught_up["reactions"][0]["ciphertext"],
        BASE64.encode(b"fire")
    );

    // After a removal any set is new again, whatever the flag says.
    let (_, removed) = unreact(&app, &b, &mine, Some(seq_of(&shrunk))).await;
    let (status, back) = react_on(
        &app,
        &b,
        &mine,
        b"heart",
        Some(seq_of(&removed)),
        Some(false),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{back}");
    assert_eq!(unseen(&app, &a, &b.1).await, 1);
}

#[tokio::test]
async fn hidden_or_cleared_messages_take_no_new_reactions() {
    let Some(app) = test_app().await else {
        eprintln!("skipping hidden_or_cleared_messages_take_no_new_reactions: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let from_a = send(&app, &a, &b.1, "text").await;
    let from_b = send(&app, &b, &a.1, "text").await;

    // B deletes A's message for themselves: no new reaction there, but B can take theirs back.
    let (_, set) = react_on(&app, &b, &from_a, b"heart", Some(0), None).await;
    hide(&app, &b, &from_a).await;
    let (status, _) = react_on(&app, &b, &from_a, b"fire", Some(seq_of(&set)), None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, removed) = unreact(&app, &b, &from_a, Some(seq_of(&set))).await;
    assert_eq!(status, StatusCode::OK, "{removed}");

    // A can still react to it; B's catch-up moves past the change without the ciphertext.
    let (status, _) = react_on(&app, &a, &from_a, b"star", Some(0), None).await;
    assert_eq!(status, StatusCode::OK);
    let caught_up = changes(&app, &b, &a.1, seq_of(&removed)).await;
    let rows = caught_up["reactions"].as_array().unwrap();
    assert_eq!(rows.len(), 1);
    assert!(rows[0]["ciphertext"].is_null());

    // A reaction to B's message counts for B until B deletes the message for themselves.
    react_on(&app, &a, &from_b, b"thumbs", Some(0), None).await;
    assert_eq!(unseen(&app, &b, &a.1).await, 1);
    hide(&app, &b, &from_b).await;
    assert_eq!(unseen(&app, &b, &a.1).await, 0);

    // B clears the chat: what was there takes no reaction from B and is not news any more.
    let older = send(&app, &b, &a.1, "text").await;
    react_on(&app, &a, &older, b"heart", Some(0), None).await;
    assert_eq!(unseen(&app, &b, &a.1).await, 1);
    clear_chat(&app, &b, &a.1, "me").await;
    let (status, _) = react_on(&app, &b, &older, b"fire", Some(0), None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let newer = send(&app, &b, &a.1, "text").await;
    assert_eq!(unseen(&app, &b, &a.1).await, 0);
    react_on(&app, &a, &newer, b"fire", Some(0), None).await;
    assert_eq!(unseen(&app, &b, &a.1).await, 1);
}

#[tokio::test]
async fn taking_back_still_works_after_the_contact_is_gone() {
    let Some(app) = test_app().await else {
        eprintln!("skipping taking_back_still_works_after_the_contact_is_gone: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let message = send(&app, &a, &b.1, "text").await;

    let (_, set) = react_on(&app, &b, &message, b"heart", Some(0), None).await;
    let (status, body) = call(
        &app,
        "DELETE",
        &format!("/api/v1/contacts/{}", b.1),
        &a.0,
        None,
    )
    .await;
    assert!(status.is_success(), "{status} {body}");
    let (status, _) = react_on(&app, &b, &message, b"fire", Some(seq_of(&set)), None).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, removed) = unreact(&app, &b, &message, Some(seq_of(&set))).await;
    assert_eq!(status, StatusCode::OK, "{removed}");

    become_contacts(&app, &a, &b).await;
    let (_, set) = react_on(&app, &b, &message, b"heart", Some(0), None).await;
    let (status, body) = call(
        &app,
        "POST",
        "/api/v1/blocks",
        &a.0,
        Some(json!({ "user_id": b.1 })),
    )
    .await;
    assert!(status.is_success(), "{status} {body}");
    let (status, _) = react_on(&app, &b, &message, b"fire", Some(seq_of(&set)), None).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, _) = unreact(&app, &b, &message, Some(seq_of(&set))).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn purged_and_note_messages_take_their_reactions_along() {
    let Some(app) = test_app().await else {
        eprintln!("skipping purged_and_note_messages_take_their_reactions_along: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let message = send(&app, &a, &b.1, "text").await;
    react_on(&app, &b, &message, b"heart", Some(0), None).await;
    react_on(&app, &a, &message, b"star", Some(0), None).await;

    // Both sides cleared past it: the message is purged, its reactions with it.
    clear_chat(&app, &a, &b.1, "me").await;
    clear_chat(&app, &b, &a.1, "me").await;
    let caught_up = changes(&app, &a, &b.1, 0).await;
    assert!(
        caught_up["reactions"]
            .as_array()
            .unwrap()
            .iter()
            .all(|row| row["message_id"] != message.as_str())
    );

    // A deleted note goes for good, reactions included.
    let note = send(&app, &a, &a.1, "text").await;
    let (status, _) = react_on(&app, &a, &note, b"star", Some(0), None).await;
    assert_eq!(status, StatusCode::OK);
    hide(&app, &a, &note).await;
    let caught_up = changes(&app, &a, &a.1, 0).await;
    assert!(caught_up["reactions"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn deleting_a_message_while_the_chat_is_deleted_never_errors() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping deleting_a_message_while_the_chat_is_deleted_never_errors: no DATABASE_URL"
        );
        return;
    };
    for _ in 0..6 {
        let a = register(&app).await;
        let b = register(&app).await;
        become_contacts(&app, &a, &b).await;
        // A's message with a reaction: A's chat delete tombstones it and clears the reaction.
        let from_a = send(&app, &a, &b.1, "text").await;
        react_on(&app, &b, &from_a, b"heart", Some(0), None).await;
        // B's messages with reactions, cleared by B earlier: the chat delete purges them.
        let mut from_b = Vec::new();
        for _ in 0..6 {
            let message = send(&app, &b, &a.1, "text").await;
            react_on(&app, &a, &message, b"fire", Some(0), None).await;
            from_b.push(message);
        }
        clear_chat(&app, &b, &a.1, "me").await;

        let chat_delete = {
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
        let deletes: Vec<_> = from_b
            .into_iter()
            .map(|message| {
                let app = app.clone();
                let b = b.clone();
                tokio::spawn(async move {
                    call(
                        &app,
                        "DELETE",
                        &format!("/api/v1/messages/{message}?scope=everyone"),
                        &b.0,
                        None,
                    )
                    .await
                })
            })
            .collect();
        for task in deletes {
            let (status, body) = task.await.expect("task");
            assert!(
                matches!(status, StatusCode::NO_CONTENT | StatusCode::NOT_FOUND),
                "a lock-order deadlock would surface as a 500: {status} {body}"
            );
        }
        let (status, body) = chat_delete.await.expect("task");
        assert_eq!(status, StatusCode::OK, "{body}");
    }
}

#[tokio::test]
async fn deleting_an_account_while_its_chats_are_busy_never_errors() {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};

    let Some(app) = test_app().await else {
        eprintln!(
            "skipping deleting_an_account_while_its_chats_are_busy_never_errors: no DATABASE_URL"
        );
        return;
    };
    for _ in 0..4 {
        let a = register(&app).await;
        let b = register(&app).await;
        become_contacts(&app, &a, &b).await;
        let mut from_a = Vec::new();
        for _ in 0..6 {
            let message = send(&app, &a, &b.1, "text").await;
            react_on(&app, &b, &message, b"heart", Some(0), None).await;
            from_a.push(message);
        }

        // A deletes the account on one device while deleting its messages for everyone on
        // another, and B keeps reacting to them, until the account is gone (the password check
        // takes a while, so the busy requests keep coming until the delete's transaction runs).
        let done = Arc::new(AtomicBool::new(false));
        let account = {
            let app = app.clone();
            let a = a.clone();
            let done = done.clone();
            tokio::spawn(async move {
                let result = call(
                    &app,
                    "DELETE",
                    "/api/v1/auth/account",
                    &a.0,
                    Some(json!({ "password": "correct-horse-battery" })),
                )
                .await;
                done.store(true, Ordering::SeqCst);
                result
            })
        };
        let mut busy = Vec::new();
        for message in &from_a {
            for (who, method, uri, body) in [
                (
                    a.clone(),
                    "DELETE",
                    format!("/api/v1/messages/{message}?scope=everyone"),
                    None,
                ),
                (
                    b.clone(),
                    "PUT",
                    format!("/api/v1/messages/{message}/reaction"),
                    Some(json!({ "ciphertext": BASE64.encode(b"fire") })),
                ),
            ] {
                let app = app.clone();
                let done = done.clone();
                busy.push(tokio::spawn(async move {
                    while !done.load(Ordering::SeqCst) {
                        let (status, body) = call(&app, method, &uri, &who.0, body.clone()).await;
                        // Done, or after the delete: the token, message or contact is gone.
                        assert!(
                            matches!(
                                status,
                                StatusCode::OK
                                    | StatusCode::NO_CONTENT
                                    | StatusCode::UNAUTHORIZED
                                    | StatusCode::FORBIDDEN
                                    | StatusCode::NOT_FOUND
                                    | StatusCode::TOO_MANY_REQUESTS
                            ),
                            "a lock-order deadlock would surface as a 500: {status} {body}"
                        );
                    }
                }));
            }
        }
        let (status, body) = account.await.expect("task");
        assert_eq!(status, StatusCode::NO_CONTENT, "{body}");
        for task in busy {
            task.await.expect("busy task");
        }
    }
}

#[tokio::test]
async fn deleting_the_chat_for_both_takes_back_our_reactions_on_their_messages() {
    let Some(app) = test_app().await else {
        eprintln!(
            "skipping deleting_the_chat_for_both_takes_back_our_reactions_on_their_messages: no DATABASE_URL"
        );
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let theirs = send(&app, &b, &a.1, "text").await;
    let (status, set) = react_on(&app, &a, &theirs, b"heart", Some(0), None).await;
    assert_eq!(status, StatusCode::OK, "{set}");
    let (status, own) = react_on(&app, &b, &theirs, b"star", Some(0), None).await;
    assert_eq!(status, StatusCode::OK, "{own}");

    // B keeps the chat (no consent given): A's messages become tombstones, and A's reaction on
    // B's message must not stay readable there either. B's own reaction stays.
    let body = clear_chat(&app, &a, &b.1, "everyone").await;
    assert_eq!(body["cleared_for_peer"], false);

    let page = history(&app, &b, &a.1).await;
    let reactions = find_message(&page, &theirs)["reactions"]
        .as_array()
        .cloned()
        .unwrap_or_default();
    assert_eq!(reactions.len(), 1, "{reactions:?}");
    assert_eq!(reactions[0]["user_id"], b.1.as_str());

    let caught_up = changes(&app, &b, &a.1, seq_of(&own)).await;
    let rows = caught_up["reactions"].as_array().unwrap();
    assert!(
        rows.iter().any(|row| row["message_id"] == theirs.as_str()
            && row["user_id"] == a.1.as_str()
            && row["ciphertext"].is_null()),
        "B's devices learn A's reaction went: {rows:?}"
    );
}
