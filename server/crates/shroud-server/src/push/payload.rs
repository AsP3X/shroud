//! What a notification says: its kind, ids, and (when the device wants it) who it is from.
//!
//! Human: Nothing here is message content — the server has none. The ids (chat, sender,
//! message, call) and the sender's name travel only where the relay cannot read them: sealed to
//! the iPhone's notification extension for APNs, inside the RFC 8291 ciphertext for Web Push.
//! Apple sees the kind of push and, per iPhone, a thread and collapse id that no other iPhone
//! shares, so it cannot tell that two people's pushes are about the same chat or call.
//! Agent: `shroud` object = the app's part of an APNs payload; `seal_for_extension`,
//! `extension_aad`, `apns_thread_id` and `apns_collapse_id` must stay in step with
//! `ios/ShroudShared/NotificationPayload.swift` (shared test vectors on both sides).

use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use ring::aead::{AES_256_GCM, Aad, LessSafeKey, NONCE_LEN, Nonce, UnboundKey};
use ring::hmac;
use ring::rand::{SecureRandom, SystemRandom};
use serde_json::{Value, json};
use uuid::Uuid;

/// Why the device is told something.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NotificationKind {
    Message,
    Reaction,
    ContactRequest,
    /// Someone is ringing: a PushKit push to iPhones that registered for one, an alert to
    /// older builds, a Web Push to browsers (see `PushService::notify_call`).
    Call,
    VideoCall,
    /// A call rang out, or its caller hung up, before anyone answered.
    MissedCall,
    /// The ring is over: a PushKit iPhone ends the CallKit call, the Android app stops its
    /// ring. Not an alert, and never sent to a browser.
    CallEnded,
    Test,
}

impl NotificationKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Message => "message",
            Self::Reaction => "reaction",
            Self::ContactRequest => "contact_request",
            Self::Call => "call",
            Self::VideoCall => "video_call",
            Self::MissedCall => "missed_call",
            Self::CallEnded => "call_ended",
            Self::Test => "test",
        }
    }

    /// A notification's text. The iPhone's extension and the browser's service worker write
    /// the same words (`NotificationPayload.body(for:)`, `sw.js`); this is what shows where
    /// neither runs, e.g. an older app build.
    pub fn fallback_body(self) -> &'static str {
        match self {
            Self::Message => "New message",
            Self::Reaction => "Reacted to your message",
            Self::ContactRequest => "Wants to add you as a contact",
            Self::Call => "Incoming call",
            Self::VideoCall => "Incoming video call",
            Self::MissedCall => "Missed call",
            Self::CallEnded => "Call ended",
            Self::Test => "Notifications are working",
        }
    }
}

/// One notification for one device.
#[derive(Debug, Clone)]
pub struct Notification {
    pub kind: NotificationKind,
    pub conversation_id: Option<Uuid>,
    pub peer_user_id: Option<Uuid>,
    pub message_id: Option<Uuid>,
    /// The ringing call, for a call notification.
    pub call_id: Option<Uuid>,
    /// Who it is from, when the device shows names.
    pub sender_name: Option<String>,
    /// Unread messages to put on the app icon, when the device shows a badge.
    pub badge: Option<i64>,
}

impl Notification {
    /// Groups a chat's notifications on iOS (`thread-id`) and in the browser (`tag`).
    pub fn thread(&self) -> String {
        match (self.kind, self.conversation_id) {
            (NotificationKind::ContactRequest, _) => "contacts".into(),
            (
                NotificationKind::Call
                | NotificationKind::VideoCall
                | NotificationKind::MissedCall
                | NotificationKind::CallEnded,
                _,
            ) => "calls".into(),
            (NotificationKind::Test, _) => "test".into(),
            (_, Some(conversation_id)) => conversation_id.to_string(),
            (_, None) => "shroud".into(),
        }
    }

    /// `thread-id` of an APNs alert for one iPhone: a chat's id becomes [`apns_thread_id`]
    /// under that iPhone's key, so the same chat has a different thread on every phone.
    /// Without a key (an older build) it is the chat's id, as before.
    pub fn apns_thread(&self, payload_key: Option<&[u8]>) -> String {
        match (self.kind, self.conversation_id, payload_key) {
            (
                NotificationKind::Message | NotificationKind::Reaction,
                Some(conversation_id),
                Some(key),
            ) => apns_thread_id(key, conversation_id),
            _ => self.thread(),
        }
    }

    /// `apns-collapse-id` for one iPhone: a call's "Missed call" replaces its "Incoming call".
    /// Under the iPhone's key, like the thread; the call's id without one.
    pub fn apns_collapse(&self, payload_key: Option<&[u8]>) -> Option<String> {
        let call_id = self.call_id?;
        Some(match payload_key {
            Some(key) => apns_collapse_id(key, call_id),
            None => call_id.to_string(),
        })
    }

    /// Groups notifications in the browser (`tag`): a chat's messages share one that its
    /// reactions do not, so a reaction never replaces or adds to a message count.
    pub fn web_tag(&self) -> String {
        match self.kind {
            NotificationKind::Reaction => format!("{}:reaction", self.thread()),
            _ => self.thread(),
        }
    }
}

/// `aps.sound` for a device's sound setting: none, the system sound, or one the app bundles.
pub fn apns_sound(setting: &str) -> Option<String> {
    match setting {
        "none" => None,
        "default" | "" => Some("default".into()),
        name => Some(format!("{name}.wav")),
    }
}

/// An APNs alert. `payload_key` (the device's, 32 bytes) seals the ids and the sender's name
/// for the notification extension; without it (an older build) the ids are sent as they are and
/// the name is left out.
pub fn apns_alert(
    notification: &Notification,
    sound: Option<&str>,
    payload_key: Option<&[u8]>,
) -> Value {
    let thread = notification.apns_thread(payload_key);
    let mut aps = json!({
        "alert": { "body": notification.kind.fallback_body() },
        "thread-id": thread,
        "mutable-content": 1,
        "category": notification.kind.as_str(),
    });
    if let Some(sound) = sound {
        aps["sound"] = json!(sound);
    }
    if let Some(badge) = notification.badge {
        aps["badge"] = json!(badge);
    }
    json!({ "aps": aps, "shroud": shroud_object(notification, payload_key) })
}

/// A PushKit push for a ringing call. The app reads the same `shroud` object as from an
/// alert, and must report the call to CallKit before its handler returns.
pub fn apns_voip(notification: &Notification, payload_key: Option<&[u8]>) -> Value {
    json!({ "aps": {}, "shroud": shroud_object(notification, payload_key) })
}

/// The app's part of an APNs payload.
///
/// With the device's key: `{"v": 2, "k": kind, "e": sealed}`, where `e` opens to the ids
/// (`c`, `p`, `m`, `call`) and the sender's name (`n`), each only when there is one. Should
/// sealing ever fail, the push goes out with its kind alone rather than readable ids. Without
/// a key (an older build): `{"v": 1, "k", "c", "p", "m", "call"}` in the clear, no name.
fn shroud_object(notification: &Notification, payload_key: Option<&[u8]>) -> Value {
    let kind = notification.kind.as_str();
    let ids = app_ids(notification);
    let Some(key) = payload_key else {
        let mut app = json!({ "v": 1, "k": kind });
        if let (Value::Object(app), Value::Object(ids)) = (&mut app, ids) {
            app.extend(ids);
        }
        return app;
    };
    let mut sealed = ids;
    if let Some(name) = &notification.sender_name {
        sealed["n"] = json!(name);
    }
    let mut app = json!({ "v": 2, "k": kind });
    if let Ok(e) = seal_for_extension(key, &extension_aad(kind), sealed.to_string().as_bytes()) {
        app["e"] = json!(e);
    }
    app
}

/// The ids a push is about, under their payload names.
fn app_ids(notification: &Notification) -> Value {
    let mut ids = json!({});
    if let Some(conversation_id) = notification.conversation_id {
        ids["c"] = json!(conversation_id);
    }
    if let Some(peer_user_id) = notification.peer_user_id {
        ids["p"] = json!(peer_user_id);
    }
    if let Some(message_id) = notification.message_id {
        ids["m"] = json!(message_id);
    }
    if let Some(call_id) = notification.call_id {
        ids["call"] = json!(call_id);
    }
    ids
}

/// A silent badge update (the user read chats on another device).
pub fn apns_badge(badge: i64) -> Value {
    json!({ "aps": { "badge": badge } })
}

/// Wakes an iPhone the account just removed, so it wipes itself without being opened. It says
/// nothing else: the app asks the server before it deletes anything.
pub fn apns_device_removed() -> Value {
    json!({ "aps": { "content-available": 1 }, "type": "device_removed" })
}

/// Tells a browser or the Android app the account just removed it; the service worker, or the
/// app after asking `GET /auth/me`, starts the wipe.
pub fn web_device_removed() -> Value {
    json!({ "v": 1, "kind": "device_removed" })
}

/// Tells the Android app a chat was read on another device: it closes that chat's
/// notifications (the socket's `conversation.read`, for an app that is not running). `badge`
/// is the new unread total when the device shows one. Encrypted like every Web Push, and
/// never sent to a browser, which would have to show it.
pub fn web_read(conversation_id: Uuid, badge: Option<i64>) -> Value {
    let mut payload = json!({
        "v": 1,
        "kind": "read",
        "tag": conversation_id,
        "conversation_id": conversation_id,
    });
    if let Some(badge) = badge {
        payload["badge"] = json!(badge);
    }
    payload
}

/// The Web Push payload, before RFC 8291 encryption. The service worker writes the text;
/// `silent` is the device's "no sound" (a service worker cannot read the page's settings).
pub fn web(notification: &Notification, silent: bool) -> Value {
    let mut payload = json!({
        "v": 1,
        "kind": notification.kind.as_str(),
        "tag": notification.web_tag(),
        "silent": silent,
    });
    if let Some(conversation_id) = notification.conversation_id {
        payload["conversation_id"] = json!(conversation_id);
    }
    if let Some(peer_user_id) = notification.peer_user_id {
        payload["peer_user_id"] = json!(peer_user_id);
    }
    if let Some(message_id) = notification.message_id {
        payload["message_id"] = json!(message_id);
    }
    if let Some(call_id) = notification.call_id {
        payload["call_id"] = json!(call_id);
    }
    if let Some(name) = &notification.sender_name {
        payload["sender"] = json!(name);
    }
    if let Some(badge) = notification.badge {
        payload["badge"] = json!(badge);
    }
    payload
}

/// Binds a seal to the kind it came with, which Apple sees (`k`, `category`): a relay cannot
/// pass a message's ids off as a call's. The ids and name are all inside the one seal, so none
/// can be swapped for another push's.
pub fn extension_aad(kind: &str) -> Vec<u8> {
    format!("shroud-push-v2|{kind}").into_bytes()
}

/// A chat's `thread-id` on one iPhone: HMAC-SHA256 under its payload key, the first 16 bytes
/// in lowercase hex. The phone works out the same value to find a chat's notifications.
pub fn apns_thread_id(key: &[u8], conversation_id: Uuid) -> String {
    keyed_id(key, "shroud-push-thread-v1", conversation_id)
}

/// A call's `apns-collapse-id` on one iPhone, made like [`apns_thread_id`].
pub fn apns_collapse_id(key: &[u8], call_id: Uuid) -> String {
    keyed_id(key, "shroud-push-collapse-v1", call_id)
}

fn keyed_id(key: &[u8], label: &str, id: Uuid) -> String {
    let tag = hmac::sign(
        &hmac::Key::new(hmac::HMAC_SHA256, key),
        format!("{label}|{id}").as_bytes(),
    );
    tag.as_ref()[..16]
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

/// AES-256-GCM under the device's payload key: base64(nonce ‖ ciphertext ‖ tag).
pub fn seal_for_extension(key: &[u8], aad: &[u8], plaintext: &[u8]) -> Result<String, ()> {
    let key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).map_err(|_| ())?);
    let mut nonce = [0u8; NONCE_LEN];
    SystemRandom::new().fill(&mut nonce).map_err(|_| ())?;
    let mut sealed = plaintext.to_vec();
    key.seal_in_place_append_tag(
        Nonce::assume_unique_for_key(nonce),
        Aad::from(aad),
        &mut sealed,
    )
    .map_err(|_| ())?;
    let mut out = nonce.to_vec();
    out.extend_from_slice(&sealed);
    Ok(BASE64.encode(out))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn message(name: Option<&str>, badge: Option<i64>) -> Notification {
        Notification {
            kind: NotificationKind::Message,
            conversation_id: Some(Uuid::nil()),
            peer_user_id: Some(Uuid::from_u128(2)),
            message_id: Some(Uuid::from_u128(3)),
            call_id: None,
            sender_name: name.map(str::to_string),
            badge,
        }
    }

    fn open(key: &[u8], aad: &[u8], sealed: &str) -> Vec<u8> {
        let bytes = BASE64.decode(sealed).unwrap();
        let (nonce, body) = bytes.split_at(NONCE_LEN);
        let key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).unwrap());
        let mut body = body.to_vec();
        key.open_in_place(
            Nonce::try_assume_unique_for_key(nonce).unwrap(),
            Aad::from(aad),
            &mut body,
        )
        .expect("open")
        .to_vec()
    }

    /// The app object a keyed push carries, opened as the notification extension opens it.
    fn opened(key: &[u8], payload: &Value) -> Value {
        let app = &payload["shroud"];
        assert_eq!(app["v"], 2);
        let kind = app["k"].as_str().expect("kind");
        let sealed = app["e"].as_str().expect("sealed ids");
        serde_json::from_slice(&open(key, &extension_aad(kind), sealed)).expect("json")
    }

    #[test]
    fn a_keyed_alert_shows_apple_no_id() {
        let key = [7u8; 32];
        let payload = apns_alert(
            &message(Some("alice"), Some(4)),
            Some("default"),
            Some(&key),
        );
        assert_eq!(payload["aps"]["alert"]["body"], "New message");
        assert_eq!(payload["aps"]["badge"], 4);
        assert_eq!(payload["aps"]["sound"], "default");
        assert_eq!(payload["aps"]["mutable-content"], 1);
        assert_eq!(payload["aps"]["category"], "message");
        assert_eq!(
            payload["aps"]["thread-id"],
            apns_thread_id(&key, Uuid::nil())
        );
        assert_eq!(payload["shroud"]["k"], "message");
        // Apple sees the kind and nothing else of ours.
        let app = payload["shroud"].as_object().unwrap();
        assert_eq!(
            app.keys().map(String::as_str).collect::<Vec<_>>(),
            ["e", "k", "v"]
        );
        let text = payload.to_string();
        for id in [Uuid::nil(), Uuid::from_u128(2), Uuid::from_u128(3)] {
            assert!(!text.contains(&id.to_string()), "{id} in {text}");
        }
        assert!(!text.contains("alice"));
        assert_eq!(
            opened(&key, &payload),
            json!({
                "c": Uuid::nil(),
                "p": Uuid::from_u128(2),
                "m": Uuid::from_u128(3),
                "n": "alice",
            })
        );
    }

    #[test]
    fn the_same_chat_has_another_thread_on_every_iphone() {
        let chat = message(None, None);
        let one = apns_alert(&chat, None, Some(&[1u8; 32]));
        let two = apns_alert(&chat, None, Some(&[2u8; 32]));
        assert_ne!(one["aps"]["thread-id"], two["aps"]["thread-id"]);
        assert_eq!(
            one["aps"]["thread-id"],
            apns_alert(&chat, None, Some(&[1u8; 32]))["aps"]["thread-id"],
            "stable on one iPhone, so its notifications group"
        );
        let mut reaction = chat.clone();
        reaction.kind = NotificationKind::Reaction;
        assert_eq!(
            apns_alert(&reaction, None, Some(&[1u8; 32]))["aps"]["thread-id"],
            one["aps"]["thread-id"],
            "a chat's reactions group with its messages"
        );
    }

    #[test]
    fn a_keyed_call_hides_the_call_and_the_caller() {
        let key = [3u8; 32];
        let call = Notification {
            kind: NotificationKind::VideoCall,
            conversation_id: None,
            peer_user_id: Some(Uuid::from_u128(12)),
            message_id: None,
            call_id: Some(Uuid::from_u128(11)),
            sender_name: None,
            badge: None,
        };
        let payload = apns_alert(&call, Some("default"), Some(&key));
        assert_eq!(payload["aps"]["alert"]["body"], "Incoming video call");
        assert_eq!(payload["aps"]["thread-id"], "calls");
        assert!(payload["shroud"].get("call").is_none());
        assert!(
            !payload
                .to_string()
                .contains(&Uuid::from_u128(11).to_string())
        );
        assert_eq!(
            opened(&key, &payload),
            json!({ "p": Uuid::from_u128(12), "call": Uuid::from_u128(11) })
        );
        let collapse = call.apns_collapse(Some(&key)).unwrap();
        assert_eq!(collapse, apns_collapse_id(&key, Uuid::from_u128(11)));
        assert_ne!(collapse, call.apns_collapse(Some(&[4u8; 32])).unwrap());
        let mut missed = call.clone();
        missed.kind = NotificationKind::MissedCall;
        assert_eq!(
            missed.apns_collapse(Some(&key)).unwrap(),
            collapse,
            "\"Missed call\" still takes the place of \"Incoming call\""
        );
    }

    #[test]
    fn a_voip_push_carries_the_same_app_object_and_no_alert() {
        let key = [5u8; 32];
        let call = Notification {
            kind: NotificationKind::Call,
            conversation_id: None,
            peer_user_id: Some(Uuid::from_u128(22)),
            message_id: None,
            call_id: Some(Uuid::from_u128(21)),
            sender_name: Some("dave".into()),
            badge: None,
        };
        let payload = apns_voip(&call, Some(&key));
        assert_eq!(payload["aps"], json!({}));
        assert_eq!(payload["shroud"]["k"], "call");
        assert!(!payload.to_string().contains("dave"));
        assert!(
            !payload
                .to_string()
                .contains(&Uuid::from_u128(21).to_string())
        );
        assert_eq!(
            opened(&key, &payload),
            json!({ "p": Uuid::from_u128(22), "call": Uuid::from_u128(21), "n": "dave" })
        );
    }

    /// The same bytes and ids `ios/shroudTests` checks: both sides agree on nonce ‖ ciphertext
    /// ‖ tag, on the associated data, and on the keyed thread and collapse ids.
    #[test]
    fn matches_the_shared_test_vectors() {
        let key = [42u8; 32];
        let opened = open(
            &key,
            &extension_aad("message"),
            "AAECAwQFBgcICQoLNw83aqYBFVinBTfStNqou0/SpKKtf4lhBa3wMFNUwZ8m4GDt4J+mUCP0sEGhc2LF+GaWQNTrj2GddhUmvcgr7R5PrsSI5FckdDdV7tEv3Uln3FZzMr6L0A9qe7AcXq4YYIRyNsxd/8TO+UXY6DZmfMav6cjaKD+V50Y8Bg8kTczJ3oAypImUmpyPTau9ebND3youNvLvD9YlsvTVQHY=",
        );
        assert_eq!(
            serde_json::from_slice::<Value>(&opened).unwrap(),
            json!({
                "c": "6f9619ff-8b86-4d01-b42d-00c04fc964ff",
                "m": "0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a",
                "n": "alice",
                "p": "5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b",
            })
        );
        assert_eq!(
            apns_thread_id(
                &key,
                Uuid::parse_str("6f9619ff-8b86-4d01-b42d-00c04fc964ff").unwrap()
            ),
            "3c9ba47195443675c89152bc77587c65"
        );
        assert_eq!(
            apns_collapse_id(
                &key,
                Uuid::parse_str("3c7d1e9a-2b4f-4a6c-8d0e-1f2a3b4c5d6e").unwrap()
            ),
            "7ea6d1da93077abd7d59d5004985ff0b"
        );
    }

    #[test]
    fn without_a_key_the_ids_go_as_before_and_the_name_is_left_out() {
        let payload = apns_alert(&message(Some("alice"), None), None, None);
        assert_eq!(payload["shroud"]["v"], 1);
        assert_eq!(payload["shroud"]["c"], Uuid::nil().to_string());
        assert_eq!(payload["shroud"]["p"], Uuid::from_u128(2).to_string());
        assert_eq!(payload["shroud"]["m"], Uuid::from_u128(3).to_string());
        assert_eq!(payload["aps"]["thread-id"], Uuid::nil().to_string());
        assert!(payload["shroud"].get("e").is_none());
        assert!(payload["aps"].get("sound").is_none());
        assert!(payload["aps"].get("badge").is_none());
        assert!(!payload.to_string().contains("alice"));
        let call = Notification {
            call_id: Some(Uuid::from_u128(9)),
            ..message(None, None)
        };
        assert_eq!(
            call.apns_collapse(None).as_deref(),
            Some(Uuid::from_u128(9).to_string().as_str())
        );
    }

    /// Contact requests, calls, the test push and the badge carry no id Apple can read either.
    /// The badge has no app object. The others seal whatever ids they have.
    #[test]
    fn keyed_pushes_of_every_kind_keep_ids_out_of_the_relayed_payload() {
        let key = [8u8; 32];
        let ids = [
            Uuid::from_u128(0x1111),
            Uuid::from_u128(0x2222),
            Uuid::from_u128(0x3333),
            Uuid::from_u128(0x4444),
        ];
        let kinds = [
            NotificationKind::ContactRequest,
            NotificationKind::Call,
            NotificationKind::VideoCall,
            NotificationKind::MissedCall,
            NotificationKind::CallEnded,
            NotificationKind::Test,
            NotificationKind::Reaction,
        ];
        for kind in kinds {
            let notification = Notification {
                kind,
                conversation_id: Some(ids[0]),
                peer_user_id: Some(ids[1]),
                message_id: Some(ids[2]),
                call_id: Some(ids[3]),
                sender_name: Some("alice".into()),
                badge: Some(2),
            };
            for payload in [
                apns_alert(&notification, Some("default"), Some(&key)),
                apns_voip(&notification, Some(&key)),
            ] {
                let text = payload.to_string();
                for id in ids {
                    assert!(
                        !text.contains(&id.to_string()),
                        "{kind:?} leaked {id} in {text}"
                    );
                }
                assert!(
                    !text.contains("alice"),
                    "{kind:?} leaked the name in {text}"
                );
                let app = payload["shroud"].as_object().unwrap();
                assert!(app.get("c").is_none(), "{kind:?}");
                assert!(app.get("p").is_none(), "{kind:?}");
                assert!(app.get("m").is_none(), "{kind:?}");
                assert!(app.get("call").is_none(), "{kind:?}");
                let opened = opened(&key, &payload);
                assert_eq!(opened["c"], ids[0].to_string());
                assert_eq!(opened["p"], ids[1].to_string());
                assert_eq!(opened["call"], ids[3].to_string());
            }
            if let Some(collapse) = notification.apns_collapse(Some(&key)) {
                assert_eq!(collapse, apns_collapse_id(&key, ids[3]));
                assert!(!collapse.contains('-'), "{collapse}");
            }
            let thread = notification.apns_thread(Some(&key));
            assert!(
                !thread.contains(&ids[0].to_string()),
                "{kind:?} thread {thread}"
            );
        }
        let badge = apns_badge(4);
        assert!(badge.get("shroud").is_none());
        let text = badge.to_string();
        for id in ids {
            assert!(
                !text.contains(&id.to_string()),
                "badge leaked {id} in {text}"
            );
        }
    }

    #[test]
    fn a_seal_does_not_open_as_another_kind() {
        let key = [9u8; 32];
        let payload = apns_alert(&message(None, None), None, Some(&key));
        let sealed = payload["shroud"]["e"].as_str().unwrap();
        let opens_with = |aad: Vec<u8>| {
            let bytes = BASE64.decode(sealed).unwrap();
            let (nonce, body) = bytes.split_at(NONCE_LEN);
            let key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, &key).unwrap());
            let mut body = body.to_vec();
            key.open_in_place(
                Nonce::try_assume_unique_for_key(nonce).unwrap(),
                Aad::from(&aad[..]),
                &mut body,
            )
            .is_ok()
        };
        assert!(opens_with(extension_aad("message")));
        assert!(!opens_with(extension_aad("call")));
        assert!(!opens_with(b"shroud-push-v1|message".to_vec()));
    }

    #[test]
    fn sounds_map_to_bundled_files() {
        assert_eq!(apns_sound("none"), None);
        assert_eq!(apns_sound("default").as_deref(), Some("default"));
        assert_eq!(apns_sound("chime").as_deref(), Some("chime.wav"));
    }

    #[test]
    fn web_payload_names_the_sender_when_asked() {
        let payload = web(&message(Some("alice"), Some(2)), false);
        assert_eq!(payload["kind"], "message");
        assert_eq!(payload["silent"], false);
        assert_eq!(payload["sender"], "alice");
        assert_eq!(payload["badge"], 2);
        assert_eq!(payload["tag"], Uuid::nil().to_string());
        let mut reaction = message(None, None);
        reaction.kind = NotificationKind::Reaction;
        assert_eq!(
            web(&reaction, false)["tag"],
            format!("{}:reaction", Uuid::nil())
        );
        let anonymous = web(&message(None, None), true);
        assert_eq!(anonymous["silent"], true);
        assert!(anonymous.get("sender").is_none());
        assert!(anonymous.get("badge").is_none());
    }

    #[test]
    fn a_read_names_the_chat_and_nothing_else() {
        let chat = Uuid::from_u128(0x0123);
        assert_eq!(
            web_read(chat, Some(3)),
            json!({
                "v": 1,
                "kind": "read",
                "tag": chat.to_string(),
                "conversation_id": chat.to_string(),
                "badge": 3,
            })
        );
        assert!(web_read(chat, None).get("badge").is_none());
    }

    #[test]
    fn a_call_end_is_the_same_web_shape_as_its_ring() {
        let ended = Notification {
            kind: NotificationKind::CallEnded,
            conversation_id: None,
            peer_user_id: Some(Uuid::from_u128(32)),
            message_id: None,
            call_id: Some(Uuid::from_u128(31)),
            sender_name: Some("erin".into()),
            badge: None,
        };
        let payload = web(&ended, false);
        assert_eq!(payload["kind"], "call_ended");
        assert_eq!(payload["tag"], "calls");
        assert_eq!(payload["call_id"], Uuid::from_u128(31).to_string());
        assert_eq!(payload["peer_user_id"], Uuid::from_u128(32).to_string());
        assert_eq!(payload["sender"], "erin");
    }

    #[test]
    fn contact_requests_share_one_thread() {
        let request = Notification {
            kind: NotificationKind::ContactRequest,
            conversation_id: None,
            peer_user_id: Some(Uuid::from_u128(5)),
            message_id: None,
            call_id: None,
            sender_name: None,
            badge: None,
        };
        assert_eq!(request.thread(), "contacts");
        assert_eq!(
            apns_alert(&request, None, None)["aps"]["alert"]["body"],
            "Wants to add you as a contact"
        );
    }
}
