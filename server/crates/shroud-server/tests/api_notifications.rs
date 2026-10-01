//! Integration tests for notifications: settings, mutes, unread markers, and what gets pushed
//! to whom (`PushService::recording` captures pushes instead of sending them).

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use base64::{
    Engine as _,
    engine::general_purpose::{STANDARD as BASE64, URL_SAFE_NO_PAD as B64URL},
};
use http_body_util::BodyExt;
use ring::agreement::{ECDH_P256, EphemeralPrivateKey};
use ring::rand::SystemRandom;
use serde_json::{Value, json};
use shroud_server::push::web_push::Urgency;
use shroud_server::push::{ApnsPushType, PushChannel, SentPush, push_topic};
use shroud_server::realtime::SocketMode;
use shroud_server::routes;
use shroud_server::state::AppState;
use sqlx::postgres::PgPoolOptions;
use tower::ServiceExt;
use uuid::Uuid;

/// None without `DATABASE_URL` (the tests skip). Set but unusable fails loudly instead.
async fn test_state() -> Option<(axum::Router, AppState)> {
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
    let state = AppState::for_integration_tests(pool);
    let app = axum::Router::new()
        .merge(routes::router())
        .with_state(state.clone());
    Some((app, state))
}

#[derive(Clone, Debug)]
struct Account {
    token: String,
    user_id: String,
    device_id: Uuid,
    username: String,
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

async fn auth(app: &axum::Router, path: &str, body: Value) -> Value {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(path)
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(body.to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    assert!(
        response.status().is_success(),
        "{path}: {}",
        response.status()
    );
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    serde_json::from_slice(&bytes).expect("json")
}

fn account(body: &Value) -> Account {
    Account {
        token: body["token"].as_str().unwrap().to_string(),
        user_id: body["user"]["id"].as_str().unwrap().to_string(),
        device_id: body["device"]["id"].as_str().unwrap().parse().unwrap(),
        username: body["user"]["username"].as_str().unwrap().to_string(),
    }
}

async fn register(app: &axum::Router) -> Account {
    let id = &Uuid::new_v4().simple().to_string()[..12];
    let body = auth(
        app,
        "/api/v1/auth/register",
        json!({ "username": format!("n_{id}"), "password": "correct-horse-battery" }),
    )
    .await;
    account(&body)
}

/// The same account on a second device.
async fn login_again(app: &axum::Router, who: &Account) -> Account {
    let body = auth(
        app,
        "/api/v1/auth/login",
        json!({ "username": who.username, "password": "correct-horse-battery" }),
    )
    .await;
    account(&body)
}

async fn become_contacts(app: &axum::Router, a: &Account, b: &Account) {
    let (status, _) = call(
        app,
        "POST",
        "/api/v1/contacts/requests",
        &a.token,
        Some(json!({ "user_id": b.user_id })),
    )
    .await;
    assert!(status.is_success());
    let (status, _) = call(
        app,
        "POST",
        "/api/v1/contacts/requests",
        &b.token,
        Some(json!({ "user_id": a.user_id })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
}

async fn send(app: &axum::Router, from: &Account, to: &str, content_type: &str) -> String {
    let (status, body) = call(
        app,
        "POST",
        "/api/v1/messages",
        &from.token,
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

async fn register_apns(app: &axum::Router, who: &Account, token: &str) -> [u8; 32] {
    let key = [42u8; 32];
    let (status, body) = call(
        app,
        "PUT",
        "/api/v1/push/token",
        &who.token,
        Some(json!({
            "token": token,
            "environment": "sandbox",
            "kind": "alert",
            "payload_key": BASE64.encode(key),
        })),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT, "{body}");
    key
}

fn browser_keys() -> (String, String) {
    let rng = SystemRandom::new();
    let key = EphemeralPrivateKey::generate(&ECDH_P256, &rng).unwrap();
    let public = key.compute_public_key().unwrap();
    (B64URL.encode(public.as_ref()), B64URL.encode([5u8; 16]))
}

async fn register_web(app: &axum::Router, who: &Account, endpoint: &str) -> StatusCode {
    let (p256dh, auth) = browser_keys();
    call(
        app,
        "PUT",
        "/api/v1/push/web/subscription",
        &who.token,
        Some(json!({ "endpoint": endpoint, "keys": { "p256dh": p256dh, "auth": auth } })),
    )
    .await
    .0
}

async fn chat(app: &axum::Router, who: &Account, peer: &str) -> Value {
    let (status, body) = call(app, "GET", "/api/v1/conversations", &who.token, None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body["conversations"]
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["peer"]["id"] == peer)
        .cloned()
        .unwrap_or(Value::Null)
}

/// The next event of `kind` a hub subscription receives (other events are skipped).
async fn next_event(events: &mut tokio::sync::mpsc::Receiver<String>, kind: &str) -> Value {
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        loop {
            let text = events.recv().await.expect("hub still open");
            let event: Value = serde_json::from_str(&text).expect("json event");
            if event["type"] == kind {
                return event;
            }
        }
    })
    .await
    .unwrap_or_else(|_| panic!("no {kind} event"))
}

fn pushes_to(state: &AppState, device: Uuid) -> Vec<(PushChannel, Value)> {
    state
        .push
        .recorded()
        .into_iter()
        .filter(|p| p.device_id == device)
        .map(|p| (p.channel, p.payload))
        .collect()
}

#[tokio::test]
async fn settings_default_then_save_partially() {
    let Some((app, _state)) = test_state().await else {
        eprintln!("skipping settings_default_then_save_partially: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;

    let (status, body) = call(
        &app,
        "GET",
        "/api/v1/notifications/settings",
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["enabled"], true);
    assert_eq!(body["show_sender"], true);
    assert_eq!(body["sound"], "default");
    assert_eq!(body["badge_includes_muted"], false);

    let (status, body) = call(
        &app,
        "PUT",
        "/api/v1/notifications/settings",
        &a.token,
        Some(json!({ "show_sender": false, "sound": "chime" })),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["show_sender"], false);
    assert_eq!(body["sound"], "chime");
    // Untouched fields keep their values.
    assert_eq!(body["enabled"], true);
    assert_eq!(body["reactions"], true);

    let (status, body) = call(
        &app,
        "GET",
        "/api/v1/notifications/settings",
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["sound"], "chime");

    let (status, _) = call(
        &app,
        "PUT",
        "/api/v1/notifications/settings",
        &a.token,
        Some(json!({ "sound": "../etc" })),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // A second device of the same account has its own settings.
    let a2 = login_again(&app, &a).await;
    let (_, body) = call(
        &app,
        "GET",
        "/api/v1/notifications/settings",
        &a2.token,
        None,
    )
    .await;
    assert_eq!(body["show_sender"], true);
}

#[tokio::test]
async fn mute_shows_in_the_list_and_syncs() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping mute_shows_in_the_list_and_syncs: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    send(&app, &b, &a.user_id, "text").await;
    // A's other device, open: it hears every change.
    let a_laptop = login_again(&app, &a).await;
    let mut laptop = state
        .realtime
        .subscribe(
            a.user_id.parse().unwrap(),
            a_laptop.device_id,
            Uuid::new_v4(),
        )
        .await
        .expect("subscribe")
        .events;

    assert!(chat(&app, &a, &b.user_id).await["mute"].is_null());

    let (status, body) = call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", b.user_id),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert!(body["mute"]["until"].is_null());
    let event = next_event(&mut laptop, "conversation.mute").await;
    assert_eq!(event["peer_user_id"], b.user_id);
    assert!(event["mute"].is_object() && event["mute"]["until"].is_null());
    let row = chat(&app, &a, &b.user_id).await;
    assert!(row["mute"].is_object());
    assert!(row["mute"]["until"].is_null());
    // The other side knows nothing about it.
    assert!(chat(&app, &b, &a.user_id).await["mute"].is_null());

    let (status, body) = call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", b.user_id),
        &a.token,
        Some(json!({ "seconds": 3600 })),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert!(chat(&app, &a, &b.user_id).await["mute"]["until"].is_string());
    assert!(next_event(&mut laptop, "conversation.mute").await["mute"]["until"].is_string());

    let (status, _) = call(
        &app,
        "DELETE",
        &format!("/api/v1/conversations/{}/mute", b.user_id),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert!(chat(&app, &a, &b.user_id).await["mute"].is_null());
    assert!(next_event(&mut laptop, "conversation.mute").await["mute"].is_null());

    for (seconds, expected) in [
        (json!(10), StatusCode::BAD_REQUEST),
        (json!(40_000_000), StatusCode::BAD_REQUEST),
    ] {
        let (status, _) = call(
            &app,
            "PUT",
            &format!("/api/v1/conversations/{}/mute", b.user_id),
            &a.token,
            Some(json!({ "seconds": seconds })),
        )
        .await;
        assert_eq!(status, expected);
    }
    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", a.user_id),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "Saved Messages");
    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", Uuid::new_v4()),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn unread_counts_follow_the_read_marker() {
    let Some((app, _state)) = test_state().await else {
        eprintln!("skipping unread_counts_follow_the_read_marker: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;

    for _ in 0..3 {
        send(&app, &a, &b.user_id, "text").await;
    }
    // A transcript is not a message.
    send(&app, &a, &b.user_id, "annotation").await;
    assert_eq!(chat(&app, &b, &a.user_id).await["unread_count"], 3);
    // The sender has nothing unread.
    assert_eq!(chat(&app, &a, &b.user_id).await["unread_count"], 0);

    let (status, body) = call(
        &app,
        "POST",
        &format!("/api/v1/conversations/{}/read", a.user_id),
        &b.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["unread_count"], 0);
    // Contacts get receipts for what was read.
    assert_eq!(body["receipts"], 3);
    assert_eq!(chat(&app, &b, &a.user_id).await["unread_count"], 0);

    // Reading again changes nothing.
    let (_, body) = call(
        &app,
        "POST",
        &format!("/api/v1/conversations/{}/read", a.user_id),
        &b.token,
        None,
    )
    .await;
    assert_eq!(body["receipts"], 0);

    let first = send(&app, &a, &b.user_id, "text").await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(chat(&app, &b, &a.user_id).await["unread_count"], 2);

    // Reading up to a given message leaves what came after.
    let (_, body) = call(
        &app,
        "POST",
        &format!("/api/v1/conversations/{}/read", a.user_id),
        &b.token,
        Some(json!({ "up_to_message_id": first })),
    )
    .await;
    assert_eq!(body["unread_count"], 1);

    // Replying means the chat was read.
    send(&app, &b, &a.user_id, "text").await;
    assert_eq!(chat(&app, &b, &a.user_id).await["unread_count"], 0);
    assert_eq!(chat(&app, &a, &b.user_id).await["unread_count"], 1);

    // The iPhone's receipts path moves the marker too.
    let reply = send(&app, &b, &a.user_id, "text").await;
    let (status, _) = call(
        &app,
        "POST",
        "/api/v1/messages/read",
        &a.token,
        Some(json!({ "peer_user_id": b.user_id, "up_to_message_id": reply })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(chat(&app, &a, &b.user_id).await["unread_count"], 0);

    // A message deleted for everyone stops counting.
    let gone = send(&app, &a, &b.user_id, "text").await;
    assert_eq!(chat(&app, &b, &a.user_id).await["unread_count"], 1);
    let (status, _) = call(
        &app,
        "DELETE",
        &format!("/api/v1/messages/{gone}?scope=everyone"),
        &a.token,
        None,
    )
    .await;
    assert!(status.is_success());
    assert_eq!(chat(&app, &b, &a.user_id).await["unread_count"], 0);

    // A read for a chat without messages is not an error.
    let c = register(&app).await;
    let (status, body) = call(
        &app,
        "POST",
        &format!("/api/v1/conversations/{}/read", c.user_id),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["read_at"].is_null());
}

#[tokio::test]
async fn a_message_pushes_to_the_recipients_closed_devices() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_message_pushes_to_the_recipients_closed_devices: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let token = format!("{:064x}", Uuid::new_v4().as_u128());
    register_apns(&app, &b, &token).await;
    let b_web = login_again(&app, &b).await;
    assert_eq!(
        register_web(
            &app,
            &b_web,
            &format!("https://fcm.googleapis.com/fcm/send/{}", Uuid::new_v4())
        )
        .await,
        StatusCode::NO_CONTENT
    );

    // The sender's own devices are registered too, and closed: still nothing for them.
    register_apns(&app, &a, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    let a_web = login_again(&app, &a).await;
    register_web(
        &app,
        &a_web,
        &format!("https://fcm.googleapis.com/fcm/send/{}", Uuid::new_v4()),
    )
    .await;

    let message = send(&app, &a, &b.user_id, "text").await;

    let phone = pushes_to(&state, b.device_id);
    assert_eq!(phone.len(), 1, "{phone:?}");
    let (channel, payload) = &phone[0];
    assert_eq!(*channel, PushChannel::Apns);
    assert_eq!(payload["aps"]["alert"]["body"], "New message");
    assert_eq!(payload["aps"]["badge"], 1);
    assert_eq!(payload["aps"]["mutable-content"], 1);
    assert_eq!(payload["shroud"]["k"], "message");
    assert_eq!(payload["shroud"]["m"], message);
    assert_eq!(payload["shroud"]["p"], a.user_id);
    assert_eq!(payload["aps"]["thread-id"], payload["shroud"]["c"]);
    // The name is sealed for the phone; Apple never sees it.
    assert!(payload["shroud"]["e"].is_string());
    assert!(!payload.to_string().contains(&a.username));

    let browser = pushes_to(&state, b_web.device_id);
    assert_eq!(browser.len(), 1, "{browser:?}");
    let (channel, payload) = &browser[0];
    assert_eq!(*channel, PushChannel::Web);
    assert_eq!(payload["kind"], "message");
    // Web Push is encrypted to the browser; the name travels inside.
    assert_eq!(payload["sender"], a.username);
    assert_eq!(payload["badge"], 1);

    assert_eq!(payload["tag"], payload["conversation_id"]);

    // The sender's own devices hear nothing.
    assert!(pushes_to(&state, a.device_id).is_empty());
    assert!(pushes_to(&state, a_web.device_id).is_empty());
}

#[tokio::test]
async fn no_push_when_open_muted_disabled_or_not_a_message() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping no_push_when_open_muted_disabled_or_not_a_message: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    register_apns(&app, &b, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    let count = || pushes_to(&state, b.device_id).len();

    // Annotations and Saved Messages never push.
    send(&app, &a, &b.user_id, "annotation").await;
    send(&app, &b, &b.user_id, "text").await;
    assert_eq!(count(), 0);

    // An app in front (live socket, and it has not said otherwise) shows its own notification.
    let user_id = b.user_id.parse().unwrap();
    let socket = state
        .realtime
        .subscribe(user_id, b.device_id, Uuid::new_v4())
        .await
        .expect("subscribe");
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(count(), 0);
    // The same socket, once the app has left the foreground, still needs a push: iOS keeps
    // the TCP connection after suspending, and a hidden browser tab stops running.
    state
        .realtime
        .set_focus(user_id, b.device_id, socket.id, false)
        .await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(count(), 1);
    state
        .realtime
        .set_focus(user_id, b.device_id, socket.id, true)
        .await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(count(), 1);
    state
        .realtime
        .unsubscribe(user_id, b.device_id, socket.id)
        .await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(count(), 2);

    // A muted chat stays silent; unmuted it pushes again.
    call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", a.user_id),
        &b.token,
        None,
    )
    .await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(count(), 2);
    call(
        &app,
        "DELETE",
        &format!("/api/v1/conversations/{}/mute", a.user_id),
        &b.token,
        None,
    )
    .await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(count(), 3);

    // Names off: no sealed name. Everything off: nothing.
    call(
        &app,
        "PUT",
        "/api/v1/notifications/settings",
        &b.token,
        Some(json!({ "show_sender": false, "sound": "none", "badge": false })),
    )
    .await;
    send(&app, &a, &b.user_id, "text").await;
    let last = pushes_to(&state, b.device_id).pop().unwrap().1;
    assert!(last["shroud"].get("e").is_none());
    assert!(last["aps"].get("sound").is_none());
    assert!(last["aps"].get("badge").is_none());
    call(
        &app,
        "PUT",
        "/api/v1/notifications/settings",
        &b.token,
        Some(json!({ "enabled": false })),
    )
    .await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(count(), 4);
}

#[tokio::test]
async fn reactions_and_contact_requests_notify() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping reactions_and_contact_requests_notify: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    register_apns(&app, &a, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    register_apns(&app, &b, &format!("{:064x}", Uuid::new_v4().as_u128())).await;

    // B asks A: a contact request notification.
    call(
        &app,
        "POST",
        "/api/v1/contacts/requests",
        &b.token,
        Some(json!({ "user_id": a.user_id })),
    )
    .await;
    let pushes = pushes_to(&state, a.device_id);
    assert_eq!(pushes.len(), 1);
    assert_eq!(pushes[0].1["shroud"]["k"], "contact_request");
    assert_eq!(pushes[0].1["aps"]["thread-id"], "contacts");
    call(
        &app,
        "POST",
        "/api/v1/contacts/requests",
        &a.token,
        Some(json!({ "user_id": b.user_id })),
    )
    .await;

    let mine = send(&app, &a, &b.user_id, "text").await;
    let theirs = send(&app, &b, &a.user_id, "text").await;
    let before = pushes_to(&state, a.device_id).len();
    let b_before = pushes_to(&state, b.device_id).len();

    // B reacts to A's message: A hears about it.
    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/messages/{mine}/reaction"),
        &b.token,
        Some(json!({ "ciphertext": BASE64.encode(b"sealed-emoji"), "added": true })),
    )
    .await;
    assert!(status.is_success());
    let pushes = pushes_to(&state, a.device_id);
    assert_eq!(pushes.len(), before + 1);
    let reaction = &pushes.last().unwrap().1;
    assert_eq!(reaction["shroud"]["k"], "reaction");
    assert_eq!(reaction["shroud"]["m"], mine);

    // A reacting to B's message is news for B, not for A.
    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/messages/{theirs}/reaction"),
        &a.token,
        Some(json!({ "ciphertext": BASE64.encode(b"sealed-emoji"), "added": true })),
    )
    .await;
    assert!(status.is_success());
    assert_eq!(pushes_to(&state, a.device_id).len(), before + 1);
    let b_pushes = pushes_to(&state, b.device_id);
    assert_eq!(b_pushes.len(), b_before + 1);
    assert_eq!(b_pushes.last().unwrap().1["shroud"]["m"], theirs);

    // Reacting to your own message, or taking an emoji back, tells no one.
    call(
        &app,
        "PUT",
        &format!("/api/v1/messages/{mine}/reaction"),
        &a.token,
        Some(json!({ "ciphertext": BASE64.encode(b"own"), "added": true })),
    )
    .await;
    call(
        &app,
        "DELETE",
        &format!("/api/v1/messages/{theirs}/reaction"),
        &a.token,
        None,
    )
    .await;
    assert_eq!(pushes_to(&state, a.device_id).len(), before + 1);
    assert_eq!(pushes_to(&state, b.device_id).len(), b_before + 1);

    // Reactions off: silent.
    call(
        &app,
        "PUT",
        "/api/v1/notifications/settings",
        &a.token,
        Some(json!({ "reactions": false })),
    )
    .await;
    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/messages/{mine}/reaction"),
        &b.token,
        Some(json!({ "ciphertext": BASE64.encode(b"another"), "added": true })),
    )
    .await;
    assert!(status.is_success());
    assert_eq!(pushes_to(&state, a.device_id).len(), before + 1);
}

#[tokio::test]
async fn reading_on_one_device_updates_the_other_phones_badge() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping reading_on_one_device_updates_the_other_phones_badge: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    register_apns(&app, &b, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    let b_laptop = login_again(&app, &b).await;

    send(&app, &a, &b.user_id, "text").await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(
        pushes_to(&state, b.device_id).last().unwrap().1["aps"]["badge"],
        2
    );

    call(
        &app,
        "POST",
        &format!("/api/v1/conversations/{}/read", a.user_id),
        &b_laptop.token,
        None,
    )
    .await;
    let sync = pushes_to(&state, b.device_id).pop().unwrap().1;
    assert_eq!(sync, json!({ "aps": { "badge": 0 } }));

    // Muting leaves a muted chat's messages out of the badge.
    send(&app, &a, &b.user_id, "text").await;
    call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", a.user_id),
        &b_laptop.token,
        None,
    )
    .await;
    assert_eq!(
        pushes_to(&state, b.device_id).pop().unwrap().1["aps"]["badge"],
        0
    );
}

#[tokio::test]
async fn tokens_and_endpoints_move_with_their_device() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping tokens_and_endpoints_move_with_their_device: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    let token = format!("{:064x}", Uuid::new_v4().as_u128());
    register_apns(&app, &a, &token).await;
    register_apns(&app, &b, &token).await;
    let owners: Vec<Uuid> =
        sqlx::query_scalar("SELECT device_id FROM push_tokens WHERE apns_token = $1")
            .bind(&token)
            .fetch_all(&state.pool)
            .await
            .unwrap();
    assert_eq!(owners, vec![b.device_id]);

    // A VoIP token from an older build (prefix) is stored beside the alert token.
    let (status, _) = call(
        &app,
        "PUT",
        "/api/v1/push/token",
        &b.token,
        Some(json!({ "token": format!("voip:{token}"), "environment": "sandbox" })),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let kinds: Vec<String> =
        sqlx::query_scalar("SELECT kind FROM push_tokens WHERE device_id = $1 ORDER BY kind")
            .bind(b.device_id)
            .fetch_all(&state.pool)
            .await
            .unwrap();
    assert_eq!(kinds, vec!["alert".to_string(), "voip".to_string()]);

    let endpoint = format!(
        "https://updates.push.services.mozilla.com/wpush/v2/{}",
        Uuid::new_v4()
    );
    assert_eq!(
        register_web(&app, &a, &endpoint).await,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        register_web(&app, &b, &endpoint).await,
        StatusCode::NO_CONTENT
    );
    let owners: Vec<Uuid> =
        sqlx::query_scalar("SELECT device_id FROM web_push_subscriptions WHERE endpoint = $1")
            .bind(&endpoint)
            .fetch_all(&state.pool)
            .await
            .unwrap();
    assert_eq!(owners, vec![b.device_id]);

    // Only browser push services.
    for bad in [
        "http://fcm.googleapis.com/fcm/send/x",
        "https://127.0.0.1/push",
        "https://evil.example/push",
    ] {
        assert_eq!(
            register_web(&app, &a, bad).await,
            StatusCode::BAD_REQUEST,
            "{bad}"
        );
    }
    let (status, _) = call(
        &app,
        "PUT",
        "/api/v1/push/token",
        &a.token,
        Some(json!({ "token": "abc", "environment": "sandbox", "payload_key": BASE64.encode([1u8; 8]) })),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "short payload key");

    // Logout forgets every registration and the settings.
    call(
        &app,
        "PUT",
        "/api/v1/notifications/settings",
        &b.token,
        Some(json!({ "reactions": false })),
    )
    .await;
    let (status, _) = call(&app, "POST", "/api/v1/auth/logout", &b.token, None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    for table in [
        "push_tokens",
        "web_push_subscriptions",
        "device_notification_settings",
    ] {
        let left: i64 = sqlx::query_scalar(&format!(
            "SELECT count(*) FROM {table} WHERE device_id = $1"
        ))
        .bind(b.device_id)
        .fetch_one(&state.pool)
        .await
        .unwrap();
        assert_eq!(left, 0, "{table}");
    }
}

#[tokio::test]
async fn a_test_push_reaches_the_calling_device() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_test_push_reaches_the_calling_device: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let (status, body) = call(&app, "POST", "/api/v1/push/test", &a.token, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "not_registered");
    assert!(body["channel"].is_null());

    let (status, key) = call(&app, "GET", "/api/v1/push/web/key", &a.token, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        B64URL
            .decode(key["public_key"].as_str().unwrap())
            .unwrap()
            .len(),
        65
    );
    register_web(&app, &a, "https://fcm.googleapis.com/fcm/send/test-device").await;
    // Sent even though the app is open: the point is to see one.
    let _socket = state
        .realtime
        .subscribe(a.user_id.parse().unwrap(), a.device_id, Uuid::new_v4())
        .await
        .expect("subscribe");
    let (status, body) = call(&app, "POST", "/api/v1/push/test", &a.token, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "sent");
    assert_eq!(body["channel"], "web");
    let pushes = pushes_to(&state, a.device_id);
    assert_eq!(pushes.len(), 1);
    assert_eq!(pushes[0].1["kind"], "test");
}

#[tokio::test]
async fn account_deletion_clears_mutes_and_markers() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping account_deletion_clears_mutes_and_markers: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    send(&app, &a, &b.user_id, "text").await;
    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", b.user_id),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", a.user_id),
        &b.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let a_id: Uuid = a.user_id.parse().unwrap();
    let count_rows = |sql: &'static str| {
        let pool = state.pool.clone();
        async move {
            sqlx::query_scalar::<_, i64>(sql)
                .bind(a_id)
                .fetch_one(&pool)
                .await
                .unwrap()
        }
    };
    const MUTES: &str = "SELECT count(*) FROM chat_mutes WHERE user_id = $1 OR peer_user_id = $1";
    const MARKERS: &str = "SELECT count(*) FROM conversation_reads WHERE user_id = $1";
    assert_eq!(count_rows(MUTES).await, 2);
    assert_eq!(count_rows(MARKERS).await, 1);

    let (status, body) = call(
        &app,
        "DELETE",
        "/api/v1/auth/account",
        &a.token,
        Some(json!({ "password": "correct-horse-battery" })),
    )
    .await;
    assert!(status.is_success(), "{status} {body}");
    assert_eq!(count_rows(MUTES).await, 0);
    assert_eq!(count_rows(MARKERS).await, 0);
}

#[tokio::test]
async fn a_call_rings_by_pushkit_and_notifies_closed_devices() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_call_rings_by_pushkit_and_notifies_closed_devices: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    // An older iPhone build: alerts only.
    register_apns(&app, &b, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    // A browser.
    let b_web = login_again(&app, &b).await;
    register_web(
        &app,
        &b_web,
        "https://fcm.googleapis.com/fcm/send/call-test",
    )
    .await;
    // A current iPhone: alerts and PushKit, and its app is open right now.
    let b_phone = login_again(&app, &b).await;
    let key = register_apns(
        &app,
        &b_phone,
        &format!("{:064x}", Uuid::new_v4().as_u128()),
    )
    .await;
    let (status, _) = call(
        &app,
        "PUT",
        "/api/v1/push/token",
        &b_phone.token,
        Some(json!({
            "token": format!("{:064x}", Uuid::new_v4().as_u128()),
            "environment": "sandbox",
            "kind": "voip",
        })),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let _open_app = state
        .realtime
        .subscribe(
            b.user_id.parse().unwrap(),
            b_phone.device_id,
            Uuid::new_v4(),
        )
        .await
        .expect("subscribe");
    // A muted chat still rings.
    call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", a.user_id),
        &b.token,
        None,
    )
    .await;

    let (status, body) = call(
        &app,
        "POST",
        "/api/v1/calls",
        &a.token,
        Some(json!({ "peer_user_id": b.user_id, "modality": "video", "protocol": 2 })),
    )
    .await;
    assert!(status.is_success(), "{status} {body}");

    // The older iPhone: an "Incoming video call" alert.
    let old_phone = pushes_to(&state, b.device_id);
    assert_eq!(old_phone.len(), 1, "{old_phone:?}");
    let payload = &old_phone[0].1;
    assert_eq!(payload["aps"]["alert"]["body"], "Incoming video call");
    assert_eq!(payload["aps"]["thread-id"], "calls");
    assert_eq!(payload["shroud"]["call"], body["id"]);
    assert!(payload["shroud"]["e"].is_string());

    // The browser: a Web Push.
    let browser = pushes_to(&state, b_web.device_id);
    assert_eq!(browser.len(), 1, "{browser:?}");
    assert_eq!(browser[0].0, PushChannel::Web);
    assert_eq!(browser[0].1["kind"], "video_call");
    assert_eq!(browser[0].1["tag"], "calls");
    assert_eq!(browser[0].1["call_id"], body["id"]);

    // The current iPhone rings by PushKit although its app is open, with its name sealed.
    let rings: Vec<_> = state
        .push
        .recorded()
        .into_iter()
        .filter(|p| p.device_id == b_phone.device_id)
        .collect();
    assert_eq!(rings.len(), 1, "{rings:?}");
    assert_eq!(rings[0].apns_push_type, Some(ApnsPushType::Voip));
    let voip = &rings[0].payload;
    assert!(voip["aps"]["alert"].is_null());
    assert_eq!(voip["shroud"]["k"], "video_call");
    assert_eq!(voip["shroud"]["call"], body["id"]);
    assert_eq!(voip["shroud"]["p"], a.user_id);
    assert!(!voip.to_string().contains(&a.username));
    assert!(voip["shroud"]["e"].is_string());
    let _ = key;

    // Tapping a notification opens the app, which connects: it gets the ring then.
    let b_id: Uuid = b.user_id.parse().unwrap();
    let ring = routes::calls::ring_to_replay(&state, b_id)
        .await
        .expect("a ringing call is kept for the callee");
    let ring: Value = serde_json::from_str(&ring).unwrap();
    assert_eq!(ring["type"], "call.ring");
    assert_eq!(ring["call"]["id"], body["id"]);
    assert!(
        routes::calls::ring_to_replay(&state, a.user_id.parse().unwrap())
            .await
            .is_none(),
        "only for the callee"
    );

    // The caller gives up: "Missed call" replaces the alert and the Web Push. The PushKit
    // phone gets a second VoIP push so CallKit stops — it cannot see the socket event.
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{}/hangup", body["id"].as_str().unwrap()),
        &a.token,
        None,
    )
    .await;
    assert!(status.is_success());
    let old_phone = pushes_to(&state, b.device_id);
    assert_eq!(old_phone.len(), 2, "{old_phone:?}");
    assert_eq!(old_phone[1].1["aps"]["alert"]["body"], "Missed call");
    assert_eq!(old_phone[1].1["shroud"]["k"], "missed_call");
    let browser = pushes_to(&state, b_web.device_id);
    assert_eq!(browser.len(), 2, "{browser:?}");
    assert_eq!(browser[1].1["kind"], "missed_call");
    assert_eq!(browser[1].1["tag"], "calls");
    let phone = pushes_to(&state, b_phone.device_id);
    assert_eq!(phone.len(), 2, "{phone:?}");
    assert_eq!(phone[1].1["shroud"]["k"], "call_ended");
    assert_eq!(phone[1].1["shroud"]["call"], body["id"]);
    assert!(phone[1].1["aps"]["alert"].is_null());
    assert!(
        routes::calls::ring_to_replay(&state, b_id).await.is_none(),
        "a call given up no longer rings"
    );

    // A declined call is not "missed".
    let (_, second) = call(
        &app,
        "POST",
        "/api/v1/calls",
        &a.token,
        Some(json!({ "peer_user_id": b.user_id, "modality": "voice", "protocol": 2 })),
    )
    .await;
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{}/reject", second["id"].as_str().unwrap()),
        &b_web.token,
        None,
    )
    .await;
    assert!(status.is_success());
    assert!(
        pushes_to(&state, b.device_id)
            .iter()
            .all(|(_, p)| p["shroud"]["call"] != second["id"] || p["shroud"]["k"] == "call"),
        "no missed-call push for a declined call"
    );
    let ended = pushes_to(&state, b_phone.device_id);
    assert!(
        ended
            .iter()
            .any(|(_, p)| p["shroud"]["k"] == "call_ended" && p["shroud"]["call"] == second["id"]),
        "the ringing iPhone is told the declined call is over: {ended:?}"
    );

    // The iPhone that answers must not be told to drop the call it just took.
    let (_, third) = call(
        &app,
        "POST",
        "/api/v1/calls",
        &a.token,
        Some(json!({ "peer_user_id": b.user_id, "modality": "voice", "protocol": 2 })),
    )
    .await;
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{}/accept", third["id"].as_str().unwrap()),
        &b_phone.token,
        Some(json!({})),
    )
    .await;
    assert!(status.is_success(), "{status}");
    assert!(
        pushes_to(&state, b_phone.device_id).iter().all(|(_, p)| {
            p["shroud"]["k"] != "call_ended" || p["shroud"]["call"] != third["id"]
        }),
        "answering does not end the call on that iPhone"
    );
}

#[tokio::test]
async fn a_password_change_stops_pushes_to_signed_out_devices() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_password_change_stops_pushes_to_signed_out_devices: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    // B's old phone, closed; B signs everything else out from the laptop.
    register_apns(&app, &b, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    let b_laptop = login_again(&app, &b).await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(pushes_to(&state, b.device_id).len(), 1);

    let (status, body) = call(
        &app,
        "POST",
        "/api/v1/auth/password",
        &b_laptop.token,
        Some(json!({
            "current_password": "correct-horse-battery",
            "new_password": "another-horse-battery",
        })),
    )
    .await;
    assert!(status.is_success(), "{status} {body}");
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(
        pushes_to(&state, b.device_id).len(),
        1,
        "a signed-out phone gets none of the account's pushes"
    );
}

#[tokio::test]
async fn a_muted_chat_still_counts_where_the_icon_counts_it() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_muted_chat_still_counts_where_the_icon_counts_it: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    register_apns(&app, &b, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", a.user_id),
        &b.token,
        None,
    )
    .await;
    let before = pushes_to(&state, b.device_id).len();

    // Muted chats not counted: nothing at all.
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(pushes_to(&state, b.device_id).len(), before);

    // Counted: the icon goes up, silently.
    call(
        &app,
        "PUT",
        "/api/v1/notifications/settings",
        &b.token,
        Some(json!({ "badge_includes_muted": true })),
    )
    .await;
    send(&app, &a, &b.user_id, "text").await;
    let pushes = pushes_to(&state, b.device_id);
    assert_eq!(pushes.len(), before + 1);
    assert_eq!(pushes.last().unwrap().1, json!({ "aps": { "badge": 2 } }));
}

#[tokio::test]
async fn a_reply_clears_the_chat_on_the_senders_other_devices() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_reply_clears_the_chat_on_the_senders_other_devices: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    register_apns(&app, &b, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    let b_laptop = login_again(&app, &b).await;
    let b_tablet = login_again(&app, &b).await;
    let mut tablet = state
        .realtime
        .subscribe(
            b.user_id.parse().unwrap(),
            b_tablet.device_id,
            Uuid::new_v4(),
        )
        .await
        .expect("subscribe")
        .events;

    send(&app, &a, &b.user_id, "text").await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(
        pushes_to(&state, b.device_id).last().unwrap().1["aps"]["badge"],
        2
    );

    // B answers from the laptop: the phone's icon and the tablet's list drop the chat.
    send(&app, &b_laptop, &a.user_id, "text").await;
    let read = next_event(&mut tablet, "conversation.read").await;
    assert_eq!(read["peer_user_id"], a.user_id);
    assert_eq!(read["unread_count"], 0);
    assert_eq!(
        pushes_to(&state, b.device_id).last().unwrap().1,
        json!({ "aps": { "badge": 0 } })
    );

    // With nothing unread, a message is just a message: no read to announce.
    let pushes = pushes_to(&state, b.device_id).len();
    send(&app, &b_laptop, &a.user_id, "text").await;
    assert_eq!(pushes_to(&state, b.device_id).len(), pushes);
}

/// Standard Base64 of SHA-256 over a session token: what a locked browser keeps readable.
fn token_hash(token: &str) -> String {
    BASE64.encode(ring::digest::digest(
        &ring::digest::SHA256,
        token.as_bytes(),
    ))
}

async fn session_status(app: &axum::Router, token_hash: &str) -> (StatusCode, Value) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/session-status")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(json!({ "token_hash": token_hash }).to_string()))
                .expect("request"),
        )
        .await
        .expect("response");
    let status = response.status();
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

#[tokio::test]
async fn a_removed_device_is_woken_and_told_to_wipe() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_removed_device_is_woken_and_told_to_wipe: no DATABASE_URL");
        return;
    };
    let phone = register(&app).await;
    let iphone = login_again(&app, &phone).await;
    let browser = login_again(&app, &phone).await;
    register_apns(&app, &iphone, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    let endpoint = format!(
        "https://updates.push.services.mozilla.com/wpush/v2/{}",
        Uuid::new_v4()
    );
    assert_eq!(
        register_web(&app, &browser, &endpoint).await,
        StatusCode::NO_CONTENT
    );
    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/devices/{}/name", iphone.device_id),
        &iphone.token,
        Some(json!({ "sealed_name": BASE64.encode([7u8; 60]) })),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    for removed in [&iphone, &browser] {
        let (status, _) = call(
            &app,
            "DELETE",
            &format!("/api/v1/devices/{}", removed.device_id),
            &phone.token,
            None,
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
    }

    // One silent push each, sent after the registrations were purged.
    let iphone_pushes: Vec<_> = state
        .push
        .recorded()
        .into_iter()
        .filter(|p| p.device_id == iphone.device_id)
        .collect();
    assert_eq!(iphone_pushes.len(), 1);
    assert_eq!(iphone_pushes[0].channel, PushChannel::Apns);
    assert_eq!(
        iphone_pushes[0].apns_push_type,
        Some(ApnsPushType::Background)
    );
    assert_eq!(
        iphone_pushes[0].payload,
        json!({ "aps": { "content-available": 1 }, "type": "device_removed" })
    );
    assert_eq!(
        pushes_to(&state, browser.device_id),
        vec![(
            PushChannel::Web,
            json!({ "v": 1, "kind": "device_removed" })
        )]
    );
    assert!(pushes_to(&state, phone.device_id).is_empty());

    // Every request it makes now says why, and so does its socket's heartbeat check.
    for removed in [&iphone, &browser] {
        let (status, body) = call(&app, "GET", "/api/v1/auth/me", &removed.token, None).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(body["error"]["code"], "DEVICE_REMOVED");
        let (status, body) = call(&app, "GET", "/api/v1/conversations", &removed.token, None).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(body["error"]["code"], "DEVICE_REMOVED");
    }
    let session_id: Uuid =
        sqlx::query_scalar("SELECT id FROM sessions WHERE device_id = $1 ORDER BY created_at DESC")
            .bind(iphone.device_id)
            .fetch_one(&state.pool)
            .await
            .unwrap();
    assert_eq!(
        shroud_server::auth::session::session_state(&state.pool, session_id)
            .await
            .unwrap(),
        shroud_server::auth::session::SessionState::Removed
    );

    // The wake went to registrations the removal had already deleted.
    for removed in [&iphone, &browser] {
        let left: i64 = sqlx::query_scalar(
            r#"
            SELECT (SELECT count(*) FROM push_tokens WHERE device_id = $1)
                 + (SELECT count(*) FROM web_push_subscriptions WHERE device_id = $1)
            "#,
        )
        .bind(removed.device_id)
        .fetch_one(&state.pool)
        .await
        .unwrap();
        assert_eq!(left, 0);
    }

    // Nothing about it outlives the removal except the row its messages point at.
    let name: Option<Vec<u8>> = sqlx::query_scalar("SELECT sealed_name FROM devices WHERE id = $1")
        .bind(iphone.device_id)
        .fetch_one(&state.pool)
        .await
        .unwrap();
    assert!(name.is_none());

    // A locked browser asks with its token's hash.
    for (hash, removed) in [
        (token_hash(&browser.token), true),
        (token_hash(&iphone.token), true),
        (token_hash(&phone.token), false),
        // Only a removal the server can point to wipes anything.
        (token_hash("never-issued"), false),
    ] {
        let (status, body) = session_status(&app, &hash).await;
        assert_eq!(status, StatusCode::OK, "{body}");
        assert_eq!(body["removed"], removed);
    }
    for bad in ["", "not base64!", &BASE64.encode([1u8; 16])] {
        let (status, _) = session_status(&app, bad).await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
    }
}

#[tokio::test]
async fn a_signed_out_device_is_not_told_to_wipe() {
    let Some((app, _state)) = test_state().await else {
        eprintln!("skipping a_signed_out_device_is_not_told_to_wipe: no DATABASE_URL");
        return;
    };
    let phone = register(&app).await;
    let laptop = login_again(&app, &phone).await;
    let (status, body) = call(
        &app,
        "POST",
        "/api/v1/auth/password",
        &laptop.token,
        Some(json!({
            "current_password": "correct-horse-battery",
            "new_password": "another-horse-battery",
        })),
    )
    .await;
    assert!(status.is_success(), "{status} {body}");

    // Signed out, not removed: a plain 401, and the locked-browser check says keep.
    let (status, body) = call(&app, "GET", "/api/v1/auth/me", &phone.token, None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body["error"]["code"], "UNAUTHORIZED");
    let (status, body) = session_status(&app, &token_hash(&phone.token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["removed"], false);
    let (status, body) = call(&app, "GET", "/api/v1/auth/me", "garbage", None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body["error"]["code"], "UNAUTHORIZED");

    // A logout is not a removal either.
    let (status, _) = call(&app, "POST", "/api/v1/auth/logout", &laptop.token, None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (_, body) = call(&app, "GET", "/api/v1/auth/me", &laptop.token, None).await;
    assert_eq!(body["error"]["code"], "UNAUTHORIZED");
    let (_, body) = session_status(&app, &token_hash(&laptop.token)).await;
    assert_eq!(body["removed"], false);
}

#[tokio::test]
async fn a_removed_device_is_still_told_after_the_session_purge() {
    let Some((app, state)) = test_state().await else {
        eprintln!(
            "skipping a_removed_device_is_still_told_after_the_session_purge: no DATABASE_URL"
        );
        return;
    };
    let phone = register(&app).await;
    let removed = login_again(&app, &phone).await;
    let signed_out = login_again(&app, &phone).await;
    let (status, _) = call(
        &app,
        "DELETE",
        &format!("/api/v1/devices/{}", removed.device_id),
        &phone.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = call(&app, "POST", "/api/v1/auth/logout", &signed_out.token, None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    // Both came back after months offline.
    sqlx::query(
        r#"
        UPDATE sessions SET revoked_at = now() - interval '400 days'
        WHERE device_id = ANY($1) AND revoked_at IS NOT NULL
        "#,
    )
    .bind(vec![removed.device_id, signed_out.device_id])
    .execute(&state.pool)
    .await
    .unwrap();
    shroud_server::auth::session::purge_revoked_sessions(&state.pool)
        .await
        .expect("purge");

    let kept: i64 = sqlx::query_scalar("SELECT count(*) FROM sessions WHERE device_id = $1")
        .bind(removed.device_id)
        .fetch_one(&state.pool)
        .await
        .unwrap();
    assert_eq!(kept, 1, "a removed device's session outlives the purge");
    let (_, body) = call(&app, "GET", "/api/v1/auth/me", &removed.token, None).await;
    assert_eq!(body["error"]["code"], "DEVICE_REMOVED");
    let (_, body) = session_status(&app, &token_hash(&removed.token)).await;
    assert_eq!(body["removed"], true);

    // The signed-out one's session is gone, and an unknown token wipes nothing.
    let purged: i64 = sqlx::query_scalar("SELECT count(*) FROM sessions WHERE device_id = $1")
        .bind(signed_out.device_id)
        .fetch_one(&state.pool)
        .await
        .unwrap();
    assert_eq!(purged, 0);
    let (_, body) = call(&app, "GET", "/api/v1/auth/me", &signed_out.token, None).await;
    assert_eq!(body["error"]["code"], "UNAUTHORIZED");
    let (_, body) = session_status(&app, &token_hash(&signed_out.token)).await;
    assert_eq!(body["removed"], false);
}

#[tokio::test]
async fn a_device_without_an_alert_registration_gets_no_wake() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_device_without_an_alert_registration_gets_no_wake: no DATABASE_URL");
        return;
    };
    let phone = register(&app).await;
    let voip_only = login_again(&app, &phone).await;
    let bare = login_again(&app, &phone).await;
    let (status, _) = call(
        &app,
        "PUT",
        "/api/v1/push/token",
        &voip_only.token,
        Some(json!({
            "token": format!("{:064x}", Uuid::new_v4().as_u128()),
            "environment": "sandbox",
            "kind": "voip",
        })),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    for device in [&voip_only, &bare] {
        let (status, _) = call(
            &app,
            "DELETE",
            &format!("/api/v1/devices/{}", device.device_id),
            &phone.token,
            None,
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        // A PushKit push must report a call, so it cannot carry a wipe; the socket, the next
        // request and the next launch still do.
        assert!(pushes_to(&state, device.device_id).is_empty());
    }
}

#[tokio::test]
async fn deleting_the_account_wakes_its_other_devices() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping deleting_the_account_wakes_its_other_devices: no DATABASE_URL");
        return;
    };
    let phone = register(&app).await;
    let other = login_again(&app, &phone).await;
    let browser = login_again(&app, &phone).await;
    register_apns(&app, &phone, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    register_apns(&app, &other, &format!("{:064x}", Uuid::new_v4().as_u128())).await;
    let endpoint = format!(
        "https://updates.push.services.mozilla.com/wpush/v2/{}",
        Uuid::new_v4()
    );
    assert_eq!(
        register_web(&app, &browser, &endpoint).await,
        StatusCode::NO_CONTENT
    );
    // The iPhone signs the others out first: they keep their data and registrations until they
    // open again, and must still be woken when the account goes.
    let (status, body) = call(
        &app,
        "POST",
        "/api/v1/auth/password",
        &phone.token,
        Some(json!({
            "current_password": "correct-horse-battery",
            "new_password": "another-horse-battery",
        })),
    )
    .await;
    assert!(status.is_success(), "{status} {body}");
    let (status, body) = call(
        &app,
        "DELETE",
        "/api/v1/auth/account",
        &phone.token,
        Some(json!({ "password": "another-horse-battery" })),
    )
    .await;
    assert!(status.is_success(), "{status} {body}");
    assert_eq!(
        pushes_to(&state, browser.device_id),
        vec![(
            PushChannel::Web,
            json!({ "v": 1, "kind": "device_removed" })
        )]
    );
    for device in [&other, &browser] {
        let (_, body) = session_status(&app, &token_hash(&device.token)).await;
        assert_eq!(body["removed"], true);
    }
    assert_eq!(
        pushes_to(&state, other.device_id),
        vec![(
            PushChannel::Apns,
            json!({ "aps": { "content-available": 1 }, "type": "device_removed" })
        )]
    );
    // The device that deleted it wipes itself already.
    assert!(pushes_to(&state, phone.device_id).is_empty());
    let (_, body) = call(&app, "GET", "/api/v1/auth/me", &other.token, None).await;
    assert_eq!(body["error"]["code"], "DEVICE_REMOVED");
}

// MARK: Android (UnifiedPush)

/// Subscribes `who` as the Android app does: its distributor's endpoint, `client: "android"`.
async fn register_android(app: &axum::Router, who: &Account) -> StatusCode {
    let (p256dh, auth) = browser_keys();
    let endpoint = format!("https://ntfy.sh/up{}?up=1", Uuid::new_v4().simple());
    call(
        app,
        "PUT",
        "/api/v1/push/web/subscription",
        &who.token,
        Some(json!({
            "endpoint": endpoint,
            "keys": { "p256dh": p256dh, "auth": auth },
            "client": "android",
        })),
    )
    .await
    .0
}

/// Everything recorded for `device`, with its Web Push options.
fn sent_to(state: &AppState, device: Uuid) -> Vec<SentPush> {
    state
        .push
        .recorded()
        .into_iter()
        .filter(|p| p.device_id == device)
        .collect()
}

/// (TTL, urgency, topic) of a recorded Web Push.
fn options_of(push: &SentPush) -> (u32, Urgency, Option<String>) {
    let options = push.web_options.clone().expect("a Web Push");
    (options.ttl_secs, options.urgency, options.topic)
}

/// The RFC 8030 topic of a push about `id` to a subscription made by `browser_keys` (auth secret
/// 16 × 5): keyed with that secret, never the id itself.
fn topic(id: &Value) -> Option<String> {
    let topic = push_topic(&[5u8; 16], id.as_str().unwrap());
    assert!(!topic.contains(&id.as_str().unwrap().replace('-', "")[..8]));
    Some(topic)
}

const DAY: u32 = 24 * 60 * 60;

#[tokio::test]
async fn an_android_app_gets_pushes_through_its_distributor() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping an_android_app_gets_pushes_through_its_distributor: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    assert_eq!(register_android(&app, &b).await, StatusCode::NO_CONTENT);

    // A message: the browser's JSON, on the UnifiedPush channel, high urgency for a day,
    // collapsing per chat.
    let message = send(&app, &a, &b.user_id, "text").await;
    let pushes = sent_to(&state, b.device_id);
    assert_eq!(pushes.len(), 1, "{pushes:?}");
    let push = &pushes[0];
    assert_eq!(push.channel, PushChannel::UnifiedPush);
    assert_eq!(push.payload["v"], 1);
    assert_eq!(push.payload["kind"], "message");
    assert_eq!(push.payload["message_id"], message);
    assert_eq!(push.payload["peer_user_id"], a.user_id);
    assert_eq!(push.payload["sender"], a.username);
    assert_eq!(push.payload["badge"], 1);
    assert_eq!(push.payload["tag"], push.payload["conversation_id"]);
    assert_eq!(
        options_of(push),
        (DAY, Urgency::High, topic(&push.payload["conversation_id"]))
    );

    // A reaction waits for the phone to wake (normal) and replaces nothing (no topic).
    let theirs = send(&app, &b, &a.user_id, "text").await;
    let (status, _) = call(
        &app,
        "PUT",
        &format!("/api/v1/messages/{theirs}/reaction"),
        &a.token,
        Some(json!({ "ciphertext": BASE64.encode(b"sealed-emoji"), "added": true })),
    )
    .await;
    assert!(status.is_success());
    let reaction = sent_to(&state, b.device_id).pop().unwrap();
    assert_eq!(reaction.payload["kind"], "reaction");
    assert_eq!(options_of(&reaction), (DAY, Urgency::Normal, None));

    // A contact request.
    let c = register(&app).await;
    call(
        &app,
        "POST",
        "/api/v1/contacts/requests",
        &c.token,
        Some(json!({ "user_id": b.user_id })),
    )
    .await;
    let request = sent_to(&state, b.device_id).pop().unwrap();
    assert_eq!(request.payload["kind"], "contact_request");
    assert_eq!(request.channel, PushChannel::UnifiedPush);
    assert_eq!(options_of(&request).1, Urgency::High);

    // The app in front shows messages itself.
    let b_id: Uuid = b.user_id.parse().unwrap();
    let before = sent_to(&state, b.device_id).len();
    let open = state
        .realtime
        .subscribe(b_id, b.device_id, Uuid::new_v4())
        .await
        .expect("subscribe");
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(sent_to(&state, b.device_id).len(), before);
    state.realtime.unsubscribe(b_id, b.device_id, open.id).await;

    // A background socket (the background connection) gets the event and the push: it is
    // never in front for pushes until its app is.
    let mut background = state
        .realtime
        .subscribe_with(b_id, b.device_id, Uuid::new_v4(), SocketMode::Background)
        .await
        .expect("background socket");
    send(&app, &a, &b.user_id, "text").await;
    next_event(&mut background.events, "message.new").await;
    assert_eq!(sent_to(&state, b.device_id).len(), before + 1);
    state
        .realtime
        .set_focus(b_id, b.device_id, background.id, true)
        .await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(sent_to(&state, b.device_id).len(), before + 1);
    state
        .realtime
        .set_focus(b_id, b.device_id, background.id, false)
        .await;
    send(&app, &a, &b.user_id, "text").await;
    assert_eq!(sent_to(&state, b.device_id).len(), before + 2);

    // The sender's name stays out when the phone asked for that, as for a browser.
    call(
        &app,
        "PUT",
        "/api/v1/notifications/settings",
        &b.token,
        Some(json!({ "show_sender": false })),
    )
    .await;
    send(&app, &a, &b.user_id, "text").await;
    let anonymous = sent_to(&state, b.device_id).pop().unwrap();
    assert!(anonymous.payload.get("sender").is_none(), "{anonymous:?}");
}

#[tokio::test]
async fn an_android_app_rings_even_in_front_and_hears_the_ring_end() {
    let Some((app, state)) = test_state().await else {
        eprintln!(
            "skipping an_android_app_rings_even_in_front_and_hears_the_ring_end: no DATABASE_URL"
        );
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let b_id: Uuid = b.user_id.parse().unwrap();
    // Two Android phones and a browser. The first phone and the browser are open right now.
    assert_eq!(register_android(&app, &b).await, StatusCode::NO_CONTENT);
    let b_other = login_again(&app, &b).await;
    assert_eq!(
        register_android(&app, &b_other).await,
        StatusCode::NO_CONTENT
    );
    let b_web = login_again(&app, &b).await;
    register_web(
        &app,
        &b_web,
        &format!("https://fcm.googleapis.com/fcm/send/{}", Uuid::new_v4()),
    )
    .await;
    let _phone_open = state
        .realtime
        .subscribe(b_id, b.device_id, Uuid::new_v4())
        .await
        .expect("subscribe");
    let _web_open = state
        .realtime
        .subscribe(b_id, b_web.device_id, Uuid::new_v4())
        .await
        .expect("subscribe");
    // A muted chat still rings.
    call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", a.user_id),
        &b.token,
        None,
    )
    .await;

    let (status, ring) = call(
        &app,
        "POST",
        "/api/v1/calls",
        &a.token,
        Some(json!({ "peer_user_id": b.user_id, "modality": "video", "protocol": 2 })),
    )
    .await;
    assert!(status.is_success(), "{status} {ring}");

    // Both phones ring, the one in front too: high urgency, alive as long as the call rings,
    // collapsing per call.
    for phone in [b.device_id, b_other.device_id] {
        let pushes = sent_to(&state, phone);
        assert_eq!(pushes.len(), 1, "{pushes:?}");
        let push = &pushes[0];
        assert_eq!(push.channel, PushChannel::UnifiedPush);
        assert_eq!(push.payload["kind"], "video_call");
        assert_eq!(push.payload["call_id"], ring["id"]);
        assert_eq!(push.payload["peer_user_id"], a.user_id);
        assert_eq!(push.payload["sender"], a.username);
        assert_eq!(push.payload["tag"], "calls");
        assert_eq!(options_of(push), (60, Urgency::High, topic(&ring["id"])));
    }
    // The browser in front rings from its socket only.
    assert!(sent_to(&state, b_web.device_id).is_empty());

    // The caller gives up: both phones are told the ring is over (high, 60 s, no topic, so it
    // never replaces a "Missed call"); the closed one also gets "Missed call" in place of its
    // queued ring.
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{}/hangup", ring["id"].as_str().unwrap()),
        &a.token,
        None,
    )
    .await;
    assert!(status.is_success());
    let in_front = sent_to(&state, b.device_id);
    assert_eq!(in_front.len(), 2, "{in_front:?}");
    assert_eq!(in_front[1].payload["kind"], "call_ended");
    assert_eq!(in_front[1].payload["call_id"], ring["id"]);
    assert_eq!(options_of(&in_front[1]), (60, Urgency::High, None));
    let closed = sent_to(&state, b_other.device_id);
    let kinds: Vec<&str> = closed
        .iter()
        .map(|p| p.payload["kind"].as_str().unwrap())
        .collect();
    assert_eq!(kinds, ["video_call", "call_ended", "missed_call"]);
    assert_eq!(
        options_of(&closed[2]),
        (DAY, Urgency::High, topic(&ring["id"]))
    );
    assert!(sent_to(&state, b_web.device_id).is_empty());

    // Answered on one phone: the other one stops ringing, the one that answered is not told
    // to drop the call it just took.
    let (_, second) = call(
        &app,
        "POST",
        "/api/v1/calls",
        &a.token,
        Some(json!({ "peer_user_id": b.user_id, "modality": "voice", "protocol": 2 })),
    )
    .await;
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{}/accept", second["id"].as_str().unwrap()),
        &b_other.token,
        Some(json!({})),
    )
    .await;
    assert!(status.is_success(), "{status}");
    let for_second = |device: Uuid| -> Vec<String> {
        sent_to(&state, device)
            .into_iter()
            .filter(|p| p.payload["call_id"] == second["id"])
            .map(|p| p.payload["kind"].as_str().unwrap().to_string())
            .collect()
    };
    assert_eq!(for_second(b.device_id), ["call", "call_ended"]);
    assert_eq!(for_second(b_other.device_id), ["call"]);
    // Hanging up an answered call pushes nothing more: the phones heard about it already.
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{}/hangup", second["id"].as_str().unwrap()),
        &a.token,
        None,
    )
    .await;
    assert!(status.is_success());
    assert_eq!(for_second(b.device_id), ["call", "call_ended"]);
    assert_eq!(for_second(b_other.device_id), ["call"]);

    // A phone with notifications off does not ring.
    call(
        &app,
        "PUT",
        "/api/v1/notifications/settings",
        &b.token,
        Some(json!({ "enabled": false })),
    )
    .await;
    let before = sent_to(&state, b.device_id).len();
    let (_, third) = call(
        &app,
        "POST",
        "/api/v1/calls",
        &a.token,
        Some(json!({ "peer_user_id": b.user_id, "modality": "voice", "protocol": 2 })),
    )
    .await;
    assert!(third["id"].is_string(), "{third}");
    assert_eq!(sent_to(&state, b.device_id).len(), before);
}

#[tokio::test]
async fn reading_elsewhere_closes_the_chat_on_android() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping reading_elsewhere_closes_the_chat_on_android: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let b_id: Uuid = b.user_id.parse().unwrap();
    assert_eq!(register_android(&app, &b).await, StatusCode::NO_CONTENT);
    let b_laptop = login_again(&app, &b).await;
    let b_web = login_again(&app, &b).await;
    register_web(
        &app,
        &b_web,
        &format!("https://fcm.googleapis.com/fcm/send/{}", Uuid::new_v4()),
    )
    .await;
    let read_on = |who: &Account| {
        let (app, token, peer) = (app.clone(), who.token.clone(), a.user_id.clone());
        async move {
            let (status, body) = call(
                &app,
                "POST",
                &format!("/api/v1/conversations/{peer}/read"),
                &token,
                None,
            )
            .await;
            assert_eq!(status, StatusCode::OK, "{body}");
        }
    };

    send(&app, &a, &b.user_id, "text").await;
    send(&app, &a, &b.user_id, "text").await;
    let conversation =
        sent_to(&state, b.device_id).pop().unwrap().payload["conversation_id"].clone();
    let browser_before = sent_to(&state, b_web.device_id).len();

    // Read on the laptop: the phone closes the chat's notifications, nothing shown, and its
    // topic replaces a message push for that chat still queued at the distributor.
    read_on(&b_laptop).await;
    let read = sent_to(&state, b.device_id).pop().unwrap();
    assert_eq!(read.channel, PushChannel::UnifiedPush);
    assert_eq!(
        read.payload,
        json!({
            "v": 1,
            "kind": "read",
            "tag": conversation,
            "conversation_id": conversation,
            "badge": 0,
        })
    );
    assert_eq!(
        options_of(&read),
        (DAY, Urgency::Normal, topic(&conversation))
    );
    // Browsers would have to show a push: they get none.
    assert_eq!(sent_to(&state, b_web.device_id).len(), browser_before);

    // Read on the phone itself: nothing for it.
    send(&app, &a, &b.user_id, "text").await;
    let before = sent_to(&state, b.device_id).len();
    read_on(&b).await;
    assert_eq!(sent_to(&state, b.device_id).len(), before);

    // A mute change moves no chat's notifications: nothing either.
    call(
        &app,
        "PUT",
        &format!("/api/v1/conversations/{}/mute", a.user_id),
        &b_laptop.token,
        None,
    )
    .await;
    assert_eq!(sent_to(&state, b.device_id).len(), before);
    call(
        &app,
        "DELETE",
        &format!("/api/v1/conversations/{}/mute", a.user_id),
        &b_laptop.token,
        None,
    )
    .await;

    // In front, the socket's `conversation.read` does it.
    send(&app, &a, &b.user_id, "text").await;
    let before = sent_to(&state, b.device_id).len();
    let open = state
        .realtime
        .subscribe(b_id, b.device_id, Uuid::new_v4())
        .await
        .expect("subscribe");
    read_on(&b_laptop).await;
    assert_eq!(sent_to(&state, b.device_id).len(), before);
    state.realtime.unsubscribe(b_id, b.device_id, open.id).await;

    // Without a badge the push still closes the chat, with no count.
    call(
        &app,
        "PUT",
        "/api/v1/notifications/settings",
        &b.token,
        Some(json!({ "badge": false })),
    )
    .await;
    send(&app, &a, &b.user_id, "text").await;
    read_on(&b_laptop).await;
    let read = sent_to(&state, b.device_id).pop().unwrap();
    assert_eq!(read.payload["kind"], "read");
    assert!(read.payload.get("badge").is_none(), "{read:?}");
}

#[tokio::test]
async fn a_test_push_reaches_an_android_app() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_test_push_reaches_an_android_app: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    assert_eq!(register_android(&app, &a).await, StatusCode::NO_CONTENT);
    let (status, body) = call(&app, "POST", "/api/v1/push/test", &a.token, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "sent");
    assert_eq!(body["channel"], "unifiedpush");
    let pushes = sent_to(&state, a.device_id);
    assert_eq!(pushes.len(), 1);
    assert_eq!(pushes[0].payload["kind"], "test");
    assert_eq!(options_of(&pushes[0]).0, 60);
}

#[tokio::test]
async fn a_removed_android_app_is_woken_and_told_to_wipe() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_removed_android_app_is_woken_and_told_to_wipe: no DATABASE_URL");
        return;
    };
    let phone = register(&app).await;
    let android = login_again(&app, &phone).await;
    assert_eq!(
        register_android(&app, &android).await,
        StatusCode::NO_CONTENT
    );
    let signed_out = login_again(&app, &phone).await;
    assert_eq!(
        register_android(&app, &signed_out).await,
        StatusCode::NO_CONTENT
    );

    let (status, _) = call(
        &app,
        "DELETE",
        &format!("/api/v1/devices/{}", android.device_id),
        &phone.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let wake = sent_to(&state, android.device_id);
    assert_eq!(wake.len(), 1, "{wake:?}");
    assert_eq!(wake[0].channel, PushChannel::UnifiedPush);
    assert_eq!(wake[0].payload, json!({ "v": 1, "kind": "device_removed" }));
    assert_eq!(options_of(&wake[0]), (DAY, Urgency::High, None));
    let left: i64 =
        sqlx::query_scalar("SELECT count(*) FROM web_push_subscriptions WHERE device_id = $1")
            .bind(android.device_id)
            .fetch_one(&state.pool)
            .await
            .unwrap();
    assert_eq!(left, 0, "the wake went to a subscription already purged");

    // A plain sign-out is no removal: no wake, and the subscription goes with the session.
    let (status, _) = call(&app, "POST", "/api/v1/auth/logout", &signed_out.token, None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert!(sent_to(&state, signed_out.device_id).is_empty());
    let left: i64 =
        sqlx::query_scalar("SELECT count(*) FROM web_push_subscriptions WHERE device_id = $1")
            .bind(signed_out.device_id)
            .fetch_one(&state.pool)
            .await
            .unwrap();
    assert_eq!(left, 0);
}
