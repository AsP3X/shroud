//! Integration tests for call signaling (docs/calls.md): ringing, answering on one of several
//! devices, signals between the two devices in the call, heartbeats and the stale-call sweep,
//! history, and minted TURN logins.

use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use shroud_server::routes;
use shroud_server::state::AppState;
use sqlx::postgres::PgPoolOptions;
use tokio::sync::mpsc::Receiver;
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
    Some((app_for(&state), state))
}

fn app_for(state: &AppState) -> axum::Router {
    axum::Router::new()
        .merge(routes::router())
        .with_state(state.clone())
}

#[derive(Clone, Debug)]
struct Account {
    token: String,
    user_id: String,
    device_id: Uuid,
    username: String,
}

impl Account {
    fn uid(&self) -> Uuid {
        self.user_id.parse().unwrap()
    }
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

async fn auth(app: &axum::Router, path: &str, body: Value) -> Account {
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
    let body: Value = serde_json::from_slice(&bytes).expect("json");
    Account {
        token: body["token"].as_str().unwrap().to_string(),
        user_id: body["user"]["id"].as_str().unwrap().to_string(),
        device_id: body["device"]["id"].as_str().unwrap().parse().unwrap(),
        username: body["user"]["username"].as_str().unwrap().to_string(),
    }
}

async fn register(app: &axum::Router) -> Account {
    let id = &Uuid::new_v4().simple().to_string()[..12];
    auth(
        app,
        "/api/v1/auth/register",
        json!({ "username": format!("c_{id}"), "password": "correct-horse-battery" }),
    )
    .await
}

/// The same account on another device.
async fn login_again(app: &axum::Router, who: &Account) -> Account {
    auth(
        app,
        "/api/v1/auth/login",
        json!({ "username": who.username, "password": "correct-horse-battery" }),
    )
    .await
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

async fn place_call(app: &axum::Router, from: &Account, to: &Account, modality: &str) -> Value {
    let (status, body) = call(
        app,
        "POST",
        "/api/v1/calls",
        &from.token,
        Some(json!({ "peer_user_id": to.user_id, "modality": modality, "protocol": 2 })),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    body
}

async fn socket(state: &AppState, who: &Account) -> Receiver<String> {
    state
        .realtime
        .subscribe(who.uid(), who.device_id, Uuid::new_v4())
        .await
        .expect("subscribe")
        .events
}

/// The next event of `kind` (others are skipped); panics after 2 s.
async fn next_event(events: &mut Receiver<String>, kind: &str) -> Value {
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

/// Everything queued for a socket right now.
fn drain(events: &mut Receiver<String>) -> Vec<Value> {
    let mut out = Vec::new();
    while let Ok(text) = events.try_recv() {
        out.push(serde_json::from_str(&text).expect("json event"));
    }
    out
}

async fn signal(
    app: &axum::Router,
    who: &Account,
    call_id: &str,
    signal_type: &str,
    payload: &str,
) -> (StatusCode, Value) {
    call(
        app,
        "POST",
        &format!("/api/v1/calls/{call_id}/signal"),
        &who.token,
        Some(json!({ "signal_type": signal_type, "payload": payload })),
    )
    .await
}

async fn status_of(state: &AppState, call_id: &str) -> (String, Option<String>) {
    sqlx::query_as(r#"SELECT status, ended_reason FROM calls WHERE id = $1"#)
        .bind(call_id.parse::<Uuid>().unwrap())
        .fetch_one(&state.pool)
        .await
        .expect("call row")
}

#[tokio::test]
async fn ice_servers_require_auth_and_mint_turn_logins() {
    let Some((_, mut state)) = test_state().await else {
        eprintln!("skipping ice_servers_require_auth_and_mint_turn_logins: no DATABASE_URL");
        return;
    };
    state.turn = Some(shroud_server::turn::TurnConfig::new(
        vec!["turn:turn.example.com:3478?transport=udp".into()],
        "integration-turn-secret-0123".into(),
        3600,
    ));
    let app = app_for(&state);
    let a = register(&app).await;

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/calls/ice-servers")
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    let (status, body) = call(&app, "GET", "/api/v1/calls/ice-servers", &a.token, None).await;
    assert_eq!(status, StatusCode::OK);
    let turn = body["ice_servers"]
        .as_array()
        .unwrap()
        .iter()
        .find(|s| s["urls"][0].as_str().unwrap().starts_with("turn:"))
        .expect("a TURN server")
        .clone();
    let username = turn["username"].as_str().unwrap();
    let (expiry, user) = username.split_once(':').expect("<expiry>:<user>");
    assert_eq!(user, a.user_id);
    let expiry: i64 = expiry.parse().unwrap();
    let now = chrono::Utc::now().timestamp();
    assert!(
        (now + 3500..=now + 3700).contains(&expiry),
        "{expiry} vs {now}"
    );
    let key = ring::hmac::Key::new(
        ring::hmac::HMAC_SHA1_FOR_LEGACY_USE_ONLY,
        b"integration-turn-secret-0123",
    );
    let expected = BASE64.encode(ring::hmac::sign(&key, username.as_bytes()).as_ref());
    assert_eq!(turn["credential"], expected);
}

#[tokio::test]
async fn builds_without_protocol_2_cannot_place_calls() {
    let Some((app, _)) = test_state().await else {
        eprintln!("skipping builds_without_protocol_2_cannot_place_calls: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let (status, body) = call(
        &app,
        "POST",
        "/api/v1/calls",
        &a.token,
        Some(json!({ "peer_user_id": b.user_id, "sdp_offer": "v=0\r\n" })),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert!(
        body["error"]["message"]
            .as_str()
            .unwrap()
            .contains("Update the app"),
        "{body}"
    );
}

#[tokio::test]
async fn contacts_only_and_busy_codes() {
    let Some((app, _)) = test_state().await else {
        eprintln!("skipping contacts_only_and_busy_codes: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    let c = register(&app).await;

    let (status, _) = call(
        &app,
        "POST",
        "/api/v1/calls",
        &a.token,
        Some(json!({ "peer_user_id": c.user_id, "protocol": 2 })),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN, "strangers cannot call");

    become_contacts(&app, &a, &b).await;
    become_contacts(&app, &a, &c).await;
    become_contacts(&app, &b, &c).await;
    let ringing = place_call(&app, &a, &b, "voice").await;
    assert_eq!(ringing["status"], "ringing");
    assert_eq!(ringing["protocol"], 2);
    assert_eq!(ringing["caller_username"], a.username);
    assert_eq!(ringing["callee_username"], b.username);

    // B is ringing: C gets "busy".
    let (status, body) = call(
        &app,
        "POST",
        "/api/v1/calls",
        &c.token,
        Some(json!({ "peer_user_id": b.user_id, "protocol": 2 })),
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body["error"]["code"], "CALL_BUSY");

    // A calling someone else ends the ring with B, instead of staying busy forever.
    let second = place_call(&app, &a, &c, "voice").await;
    assert_eq!(second["status"], "ringing");
    assert_eq!(second["callee_user_id"], c.user_id);
    let id = ringing["id"].as_str().unwrap();
    let (status, body) = call(&app, "GET", &format!("/api/v1/calls/{id}"), &a.token, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "cancelled");

    // A's new ring is still up, so C is busy. Hanging it up frees everyone.
    let second_id = second["id"].as_str().unwrap();
    let (status, body) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{second_id}/hangup"),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "cancelled");
    place_call(&app, &c, &b, "video").await;
}

#[tokio::test]
async fn answering_on_one_device_and_signals_between_the_two_in_the_call() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping answering_on_one_device_and_signals_between_the_two_in_the_call");
        return;
    };
    let a = register(&app).await;
    let a_laptop = login_again(&app, &a).await;
    let b = register(&app).await;
    let b_laptop = login_again(&app, &b).await;
    become_contacts(&app, &a, &b).await;

    let mut a_phone_events = socket(&state, &a).await;
    let mut a_laptop_events = socket(&state, &a_laptop).await;
    let mut b_phone_events = socket(&state, &b).await;
    let mut b_laptop_events = socket(&state, &b_laptop).await;

    let placed = place_call(&app, &a, &b, "video").await;
    let id = placed["id"].as_str().unwrap().to_string();

    // Both of B's devices ring; A's other device hears of it too; the caller's own does not.
    for events in [
        &mut b_phone_events,
        &mut b_laptop_events,
        &mut a_laptop_events,
    ] {
        let ring = next_event(events, "call.ring").await;
        assert_eq!(ring["call"]["id"], id.as_str());
        assert_eq!(ring["call"]["caller_username"], a.username);
        assert!(ring.get("sdp_offer").is_none());
    }
    assert!(
        drain(&mut a_phone_events)
            .iter()
            .all(|e| e["type"] != "call.ring")
    );

    // Nobody signals before the answer.
    let (status, body) = signal(&app, &a, &id, "sdp_offer", "c1.AAAA").await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body["error"]["code"], "CALL_NOT_ANSWERED");

    // B answers on the laptop: the phone stops ringing, A hears which device answered.
    let (status, accepted) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{id}/accept"),
        &b_laptop.token,
        Some(json!({})),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["status"], "active");
    assert_eq!(accepted["callee_device_id"], b_laptop.device_id.to_string());
    let on_phone = next_event(&mut b_phone_events, "call.accepted").await;
    assert_eq!(
        on_phone["call"]["callee_device_id"],
        b_laptop.device_id.to_string()
    );
    next_event(&mut a_phone_events, "call.accepted").await;

    // A second answer is refused.
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{id}/accept"),
        &b.token,
        Some(json!({})),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // Signals go from the caller's device to the answering one, and back — nowhere else.
    let (status, _) = signal(&app, &a, &id, "sdp_offer", "c1.offer").await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let got = next_event(&mut b_laptop_events, "call.signal").await;
    assert_eq!(got["payload"], "c1.offer");
    assert_eq!(got["signal_type"], "sdp_offer");
    assert_eq!(got["from_device_id"], a.device_id.to_string());

    let (status, _) = signal(&app, &b_laptop, &id, "sdp_answer", "c1.answer").await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let got = next_event(&mut a_phone_events, "call.signal").await;
    assert_eq!(got["payload"], "c1.answer");

    let (status, _) = signal(&app, &b_laptop, &id, "media_state", "c1.media").await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    next_event(&mut a_phone_events, "call.signal").await;

    for events in [&mut b_phone_events, &mut a_laptop_events] {
        assert!(
            drain(events).iter().all(|e| e["type"] != "call.signal"),
            "signals reached a device outside the call"
        );
    }

    // The other devices of the two people cannot join in.
    let (status, _) = signal(&app, &b, &id, "ice_candidate", "c1.x").await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, _) = signal(&app, &a_laptop, &id, "ice_candidate", "c1.x").await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, _) = signal(&app, &a, &id, "sdp_rumor", "c1.x").await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // Hang up: every other device of both hears it, and signalling stops.
    let (status, ended) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{id}/hangup"),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(ended["status"], "ended");
    assert_eq!(ended["ended_reason"], "hangup");
    for events in [
        &mut b_laptop_events,
        &mut b_phone_events,
        &mut a_laptop_events,
    ] {
        next_event(events, "call.ended").await;
    }
    let (status, body) = signal(&app, &b_laptop, &id, "ice_candidate", "c1.x").await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body["error"]["code"], "CALL_ENDED");
    // Hanging up twice is harmless.
    let (status, again) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{id}/hangup"),
        &b_laptop.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(again["status"], "ended");
}

#[tokio::test]
async fn a_quiet_device_ends_its_call_and_nobody_stays_busy() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping a_quiet_device_ends_its_call_and_nobody_stays_busy: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let mut a_events = socket(&state, &a).await;

    let placed = place_call(&app, &a, &b, "voice").await;
    let id = placed["id"].as_str().unwrap().to_string();
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{id}/accept"),
        &b.token,
        Some(json!({})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);

    // Heartbeats answer with the call.
    let (status, beat) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{id}/heartbeat"),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(beat["status"], "active");
    let outsider = register(&app).await;
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{id}/heartbeat"),
        &outsider.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    // B's app dies: nothing from it for longer than the limit.
    sqlx::query(r#"UPDATE calls SET callee_seen_at = now() - interval '2 minutes' WHERE id = $1"#)
        .bind(id.parse::<Uuid>().unwrap())
        .execute(&state.pool)
        .await
        .unwrap();

    // Calling again ends the dead call first instead of answering "busy".
    let again = place_call(&app, &a, &b, "voice").await;
    assert_eq!(
        status_of(&state, &id).await,
        ("ended".into(), Some("connection_lost".into()))
    );
    let ended = next_event(&mut a_events, "call.ended").await;
    assert_eq!(ended["call"]["id"], id.as_str());
    assert_eq!(ended["call"]["ended_reason"], "connection_lost");

    // A heartbeat on an ended call says so.
    let (status, beat) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{id}/heartbeat"),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(beat["status"], "ended");

    // The sweep: the new call's caller goes quiet while it rings.
    let again_id = again["id"].as_str().unwrap().to_string();
    sqlx::query(r#"UPDATE calls SET caller_seen_at = now() - interval '2 minutes' WHERE id = $1"#)
        .bind(again_id.parse::<Uuid>().unwrap())
        .execute(&state.pool)
        .await
        .unwrap();
    let ended = routes::calls::end_stale_calls(&state, Some(&[a.uid()]))
        .await
        .expect("sweep");
    assert!(ended >= 1);
    assert_eq!(
        status_of(&state, &again_id).await,
        ("cancelled".into(), Some("connection_lost".into()))
    );
}

#[tokio::test]
async fn unanswered_calls_stop_ringing() {
    let Some((app, state)) = test_state().await else {
        eprintln!("skipping unanswered_calls_stop_ringing: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;
    let mut b_events = socket(&state, &b).await;
    let placed = place_call(&app, &a, &b, "voice").await;
    let id = placed["id"].as_str().unwrap().to_string();
    next_event(&mut b_events, "call.ring").await;

    sqlx::query(
        r#"
        UPDATE calls
        SET created_at = now() - interval '2 minutes', caller_seen_at = now()
        WHERE id = $1
        "#,
    )
    .bind(id.parse::<Uuid>().unwrap())
    .execute(&state.pool)
    .await
    .unwrap();

    // Too late to answer, even before the sweep ran.
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/calls/{id}/accept"),
        &b.token,
        Some(json!({})),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    routes::calls::end_stale_calls(&state, Some(&[b.uid()]))
        .await
        .expect("sweep");
    assert_eq!(
        status_of(&state, &id).await,
        ("missed".into(), Some("timeout".into()))
    );
    let ended = next_event(&mut b_events, "call.ended").await;
    assert_eq!(ended["call"]["status"], "missed");
    assert!(
        routes::calls::ring_to_replay(&state, b.uid())
            .await
            .is_none(),
        "a call that stopped ringing is not replayed"
    );
}

#[tokio::test]
async fn history_lists_both_directions_newest_first() {
    let Some((app, _)) = test_state().await else {
        eprintln!("skipping history_lists_both_directions_newest_first: no DATABASE_URL");
        return;
    };
    let a = register(&app).await;
    let b = register(&app).await;
    become_contacts(&app, &a, &b).await;

    let first = place_call(&app, &a, &b, "voice").await;
    let first_id = first["id"].as_str().unwrap();
    call(
        &app,
        "POST",
        &format!("/api/v1/calls/{first_id}/reject"),
        &b.token,
        None,
    )
    .await;
    let second = place_call(&app, &b, &a, "video").await;
    let second_id = second["id"].as_str().unwrap();
    call(
        &app,
        "POST",
        &format!("/api/v1/calls/{second_id}/hangup"),
        &b.token,
        None,
    )
    .await;

    let (status, body) = call(&app, "GET", "/api/v1/calls", &a.token, None).await;
    assert_eq!(status, StatusCode::OK);
    let calls = body["calls"].as_array().unwrap();
    assert_eq!(calls.len(), 2);
    assert_eq!(calls[0]["id"], second_id);
    assert_eq!(calls[0]["status"], "cancelled");
    assert_eq!(calls[0]["caller_username"], b.username);
    assert_eq!(calls[1]["id"], first_id);
    assert_eq!(calls[1]["status"], "rejected");

    let before = calls[0]["created_at"].as_str().unwrap();
    let (status, body) = call(
        &app,
        "GET",
        &format!(
            "/api/v1/calls?limit=5&before={}",
            before.replace(':', "%3A").replace('+', "%2B")
        ),
        &a.token,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let older = body["calls"].as_array().unwrap();
    assert_eq!(older.len(), 1);
    assert_eq!(older[0]["id"], first_id);

    let (_, body) = call(&app, "GET", "/api/v1/calls?limit=1", &b.token, None).await;
    assert_eq!(body["calls"].as_array().unwrap().len(), 1);
}
