//! What a notification says: its kind, ids, and (when the device wants it) who it is from.
//!
//! Human: Nothing here is message content — the server has none. The sender's name travels
//! only where the relay cannot read it: sealed to the iPhone's notification extension for
//! APNs, inside the RFC 8291 ciphertext for Web Push.
//! Agent: `shroud` object = the app's part of an APNs payload; `seal_for_extension` must stay
//! in step with `ios/ShroudShared/NotificationPayload.swift`.

use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use ring::aead::{AES_256_GCM, Aad, LessSafeKey, NONCE_LEN, Nonce, UnboundKey};
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
    /// PushKit only: the ring is over, so the iPhone ends the CallKit call. Not an alert.
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

    /// Groups notifications in the browser (`tag`): a chat's messages share one that its
    /// reactions do not, so a reaction never replaces or adds to a message count.
    pub fn web_tag(&self) -> String {
        match self.kind {
            NotificationKind::Reaction => format!("{}:reaction", self.thread()),
            _ => self.thread(),
        }
    }

    /// Who the notification is about, as it appears in the payload (`p`), or empty.
    fn peer(&self) -> String {
        self.peer_user_id
            .map(|peer| peer.to_string())
            .unwrap_or_default()
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

/// An APNs alert. `payload_key` (the device's, 32 bytes) seals the sender's name for the
/// notification extension; without it the name is left out rather than sent readable.
pub fn apns_alert(
    notification: &Notification,
    sound: Option<&str>,
    payload_key: Option<&[u8]>,
) -> Value {
    let thread = notification.thread();
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

/// The app's part of an APNs payload: kind, ids, and the sender's name sealed for the device.
fn shroud_object(notification: &Notification, payload_key: Option<&[u8]>) -> Value {
    let mut app = json!({ "v": 1, "k": notification.kind.as_str() });
    if let Some(conversation_id) = notification.conversation_id {
        app["c"] = json!(conversation_id);
    }
    if let Some(peer_user_id) = notification.peer_user_id {
        app["p"] = json!(peer_user_id);
    }
    if let Some(message_id) = notification.message_id {
        app["m"] = json!(message_id);
    }
    if let Some(call_id) = notification.call_id {
        app["call"] = json!(call_id);
    }
    if let (Some(name), Some(key)) = (&notification.sender_name, payload_key)
        && let Some(sealed) = seal_name(
            key,
            notification.kind.as_str(),
            &notification.thread(),
            &notification.peer(),
            name,
        )
    {
        app["e"] = json!(sealed);
    }
    app
}

/// `{"n": name}` sealed for the device, bound to the kind, thread and person it belongs to.
fn seal_name(key: &[u8], kind: &str, thread: &str, peer: &str, name: &str) -> Option<String> {
    seal_for_extension(
        key,
        &extension_aad(kind, thread, peer),
        json!({ "n": name }).to_string().as_bytes(),
    )
    .ok()
}

/// A silent badge update (the user read chats on another device).
pub fn apns_badge(badge: i64) -> Value {
    json!({ "aps": { "badge": badge } })
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

/// Binds a sealed name to its notification: a relay cannot move it onto another chat's push,
/// or onto another person's (all contact requests share the thread `contacts`, all calls
/// `calls`). `peer` is `p` exactly as sent (a lowercase UUID), or empty.
pub fn extension_aad(kind: &str, thread: &str, peer: &str) -> Vec<u8> {
    format!("shroud-push-v1|{kind}|{thread}|{peer}").into_bytes()
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

    #[test]
    fn alert_carries_ids_and_a_sealed_name_only() {
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
        assert_eq!(payload["aps"]["thread-id"], Uuid::nil().to_string());
        assert_eq!(payload["shroud"]["k"], "message");
        // The relay never sees the name.
        assert!(!payload.to_string().contains("alice"));
        let sealed = payload["shroud"]["e"].as_str().expect("sealed name");
        let opened = open(
            &key,
            &extension_aad(
                "message",
                &Uuid::nil().to_string(),
                &Uuid::from_u128(2).to_string(),
            ),
            sealed,
        );
        assert_eq!(
            serde_json::from_slice::<Value>(&opened).unwrap()["n"],
            "alice"
        );
    }

    #[test]
    fn a_call_is_an_alert_with_the_callers_name_sealed() {
        let key = [3u8; 32];
        let call = Notification {
            kind: NotificationKind::VideoCall,
            conversation_id: None,
            peer_user_id: Some(Uuid::from_u128(12)),
            message_id: None,
            call_id: Some(Uuid::from_u128(11)),
            sender_name: Some("carol".into()),
            badge: None,
        };
        let payload = apns_alert(&call, Some("default"), Some(&key));
        assert_eq!(payload["aps"]["alert"]["body"], "Incoming video call");
        assert_eq!(payload["aps"]["thread-id"], "calls");
        assert_eq!(payload["shroud"]["call"], Uuid::from_u128(11).to_string());
        assert!(!payload.to_string().contains("carol"));
        let opened = open(
            &key,
            &extension_aad("video_call", "calls", &Uuid::from_u128(12).to_string()),
            payload["shroud"]["e"].as_str().unwrap(),
        );
        assert_eq!(
            serde_json::from_slice::<Value>(&opened).unwrap()["n"],
            "carol"
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
        assert_eq!(payload["shroud"]["call"], Uuid::from_u128(21).to_string());
        assert_eq!(payload["shroud"]["p"], Uuid::from_u128(22).to_string());
        assert!(!payload.to_string().contains("dave"));
        let opened = open(
            &key,
            &extension_aad("call", "calls", &Uuid::from_u128(22).to_string()),
            payload["shroud"]["e"].as_str().unwrap(),
        );
        assert_eq!(
            serde_json::from_slice::<Value>(&opened).unwrap()["n"],
            "dave"
        );
    }

    /// The same bytes `ios/shroudTests` opens: both sides agree on nonce ‖ ciphertext ‖ tag
    /// and on the associated data.
    #[test]
    fn opens_the_shared_test_vector() {
        let opened = open(
            &[42u8; 32],
            &extension_aad(
                "message",
                "6f9619ff-8b86-4d01-b42d-00c04fc964ff",
                "5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b",
            ),
            "AAECAwQFBgcICQoLNw86aqYBQlL3UGPJr5ruy3y0NJPwNYGMO+xknak=",
        );
        assert_eq!(opened, br#"{"n":"alice"}"#);
    }

    #[test]
    fn a_name_without_a_key_is_left_out() {
        let payload = apns_alert(&message(Some("alice"), None), None, None);
        assert!(payload["shroud"].get("e").is_none());
        assert!(payload["aps"].get("sound").is_none());
        assert!(payload["aps"].get("badge").is_none());
        assert!(!payload.to_string().contains("alice"));
    }

    #[test]
    fn a_sealed_name_does_not_open_on_another_push() {
        let key = [9u8; 32];
        let payload = apns_alert(&message(Some("bob"), None), None, Some(&key));
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
        let chat = Uuid::nil().to_string();
        let peer = Uuid::from_u128(2).to_string();
        assert!(opens_with(extension_aad("message", &chat, &peer)));
        assert!(!opens_with(extension_aad("message", "another-chat", &peer)));
        assert!(!opens_with(extension_aad("reaction", &chat, &peer)));
        assert!(!opens_with(extension_aad(
            "message",
            &chat,
            &Uuid::from_u128(99).to_string()
        )));
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
