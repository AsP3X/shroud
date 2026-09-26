//! Push notifications: which of a user's devices get one, and what it says.
//!
//! Human: A device in the foreground notifies its user itself — it can read the message.
//! One that is signed in but not in front (the app backgrounded, the tab unfocused) gets a
//! push even if its socket has not dropped yet: a suspended app keeps that socket open and
//! would otherwise swallow the notice. The relay is APNs for the iPhone and Web Push for a
//! browser. A push carries ids and a kind, plus the sender's name when the device asked for
//! it — sealed so the relay cannot read it. Never message content: the server has none.
//! Agent: `dispatch` spawns (a recording service delivers inline); READS devices,
//! device_notification_settings, push_tokens, web_push_subscriptions, chat_mutes; DELETES
//! tokens and subscriptions their relay reports gone.

mod client;
mod payload;
pub mod web_push;

use std::sync::{Arc, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sqlx::PgPool;
use uuid::Uuid;

pub use client::{
    ApnsClient, ApnsConfig, ApnsEnvironment, ApnsPushType, ApnsRequest, ApnsSendOutcome,
    apns_config_from_env,
};
pub use payload::{Notification, NotificationKind};
pub use web_push::{VapidKey, WebPushClient, WebSubscription};

use crate::realtime::RealtimeHub;
use web_push::{Urgency, WebPushOptions, WebPushOutcome};

/// How long an undelivered notification stays worth delivering (device off, no signal).
const PUSH_LIFETIME_SECS: u64 = 24 * 60 * 60;
/// A call stops ringing after this long (`routes::calls`); a later "incoming call" is noise.
const CALL_PUSH_LIFETIME_SECS: u64 = crate::routes::calls::RINGING_TIMEOUT_SECS.unsigned_abs();

/// Something that may deserve a notification.
#[derive(Debug, Clone)]
pub enum PushEvent {
    /// `sender` sent `recipient` a message (never an annotation, never to themselves).
    Message {
        recipient: Uuid,
        sender: Uuid,
        conversation_id: Uuid,
        message_id: Uuid,
    },
    /// `reactor` added an emoji to one of `recipient`'s messages.
    Reaction {
        recipient: Uuid,
        reactor: Uuid,
        conversation_id: Uuid,
        message_id: Uuid,
    },
    /// `requester` asked `recipient` to become contacts.
    ContactRequest { recipient: Uuid, requester: Uuid },
    /// `recipient` read chats on `reader_device`: their other iPhones' icon badges go down.
    BadgeSync {
        recipient: Uuid,
        reader_device: Uuid,
    },
    /// `caller` is ringing `recipient`: every iPhone with a VoIP token rings through PushKit;
    /// older iPhones and browsers that are not in the foreground get a notification.
    IncomingCall {
        recipient: Uuid,
        caller: Uuid,
        call_id: Uuid,
        modality: String,
    },
    /// `caller`'s call to `recipient` ended before anyone answered.
    MissedCall {
        recipient: Uuid,
        caller: Uuid,
        call_id: Uuid,
    },
    /// The call is over for `recipient`'s iPhones that rang through PushKit. `except_device`
    /// is the one that answered or ended it here, and must not be told to drop its own call.
    CallEnded {
        recipient: Uuid,
        caller: Uuid,
        call_id: Uuid,
        except_device: Option<Uuid>,
    },
}

/// Which relay carried a push.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PushChannel {
    Apns,
    Web,
}

/// A push as handed to its relay (tests read these from a recording service).
#[derive(Debug, Clone)]
pub struct SentPush {
    pub device_id: Uuid,
    pub channel: PushChannel,
    /// `apns-push-type` for APNs (a PushKit ring is `Voip`); `None` for Web Push.
    pub apns_push_type: Option<ApnsPushType>,
    /// APNs JSON, or the Web Push JSON before encryption.
    pub payload: Value,
}

/// What one device wants pushed. A device that never saved any gets [`Default`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, sqlx::FromRow)]
pub struct NotificationSettings {
    pub enabled: bool,
    pub show_sender: bool,
    pub reactions: bool,
    pub contact_requests: bool,
    pub sound: String,
    pub badge: bool,
    pub badge_includes_muted: bool,
}

impl Default for NotificationSettings {
    fn default() -> Self {
        Self {
            enabled: true,
            show_sender: true,
            reactions: true,
            contact_requests: true,
            sound: "default".into(),
            badge: true,
            badge_includes_muted: false,
        }
    }
}

/// `POST /push/test`'s answer: what happened to the test notification.
#[derive(Debug, Clone, Serialize)]
pub struct TestPushOutcome {
    /// `apns` or `web`; null when this device registered for neither.
    pub channel: Option<PushChannel>,
    /// `sent`, `not_registered`, `not_configured` (this server cannot send to that relay),
    /// `misconfigured` (the relay refused this server's key or topic), `rejected` (the relay
    /// refused the token; it was removed) or `failed`.
    pub status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

enum Delivery {
    Sent,
    NotConfigured,
    /// The relay refused this server's credentials or topic: nothing on the device can fix it.
    Misconfigured(String),
    Rejected(String),
    Failed(String),
}

/// Push dispatcher shared in app state.
#[derive(Clone)]
pub struct PushService {
    inner: Arc<PushInner>,
}

struct PushInner {
    pool: PgPool,
    realtime: Arc<RealtimeHub>,
    apns: Option<ApnsClient>,
    /// Set once the VAPID key is loaded (at start, or by a retry when the table was missing).
    web: OnceLock<WebPushClient>,
    /// Test double: pushes land here instead of on the network, delivered before `dispatch`
    /// returns.
    recorder: Option<Mutex<Vec<SentPush>>>,
}

#[derive(sqlx::FromRow)]
struct Target {
    device_id: Uuid,
    enabled: Option<bool>,
    show_sender: Option<bool>,
    reactions: Option<bool>,
    contact_requests: Option<bool>,
    sound: Option<String>,
    badge: Option<bool>,
    badge_includes_muted: Option<bool>,
    apns_token: Option<String>,
    apns_environment: Option<String>,
    payload_key: Option<Vec<u8>>,
    /// PushKit token: only loaded for calls (`targets(.., with_voip: true)`).
    voip_token: Option<String>,
    voip_environment: Option<String>,
    endpoint: Option<String>,
    p256dh: Option<Vec<u8>>,
    auth: Option<Vec<u8>>,
}

impl Target {
    fn settings(&self) -> NotificationSettings {
        let defaults = NotificationSettings::default();
        NotificationSettings {
            enabled: self.enabled.unwrap_or(defaults.enabled),
            show_sender: self.show_sender.unwrap_or(defaults.show_sender),
            reactions: self.reactions.unwrap_or(defaults.reactions),
            contact_requests: self.contact_requests.unwrap_or(defaults.contact_requests),
            sound: self.sound.clone().unwrap_or(defaults.sound),
            badge: self.badge.unwrap_or(defaults.badge),
            badge_includes_muted: self
                .badge_includes_muted
                .unwrap_or(defaults.badge_includes_muted),
        }
    }

    fn web_subscription(&self) -> Option<WebSubscription> {
        Some(WebSubscription {
            endpoint: self.endpoint.clone()?,
            p256dh: self.p256dh.clone()?,
            auth: self.auth.clone()?,
        })
    }
}

impl PushService {
    pub fn new(
        pool: PgPool,
        realtime: Arc<RealtimeHub>,
        apns: Option<ApnsClient>,
        web: Option<WebPushClient>,
    ) -> Self {
        let cell = OnceLock::new();
        if let Some(web) = web {
            let _ = cell.set(web);
        }
        Self {
            inner: Arc::new(PushInner {
                pool,
                realtime,
                apns,
                web: cell,
                recorder: None,
            }),
        }
    }

    /// Web Push becomes available (the VAPID key loaded late). A second call is ignored.
    pub fn set_web(&self, web: WebPushClient) {
        let _ = self.inner.web.set(web);
    }

    /// Integration tests: nothing leaves the process, and `recorded()` lists what would have.
    pub fn recording(pool: PgPool, realtime: Arc<RealtimeHub>) -> Self {
        let web = OnceLock::new();
        if let Some(client) = VapidKey::generate().ok().and_then(|(key, _)| {
            WebPushClient::new(key, "mailto:test@shroud.invalid".into(), vec![]).ok()
        }) {
            let _ = web.set(client);
        }
        Self {
            inner: Arc::new(PushInner {
                pool,
                realtime,
                apns: None,
                web,
                recorder: Some(Mutex::new(Vec::new())),
            }),
        }
    }

    /// Pushes a recording service has taken so far.
    pub fn recorded(&self) -> Vec<SentPush> {
        self.inner
            .recorder
            .as_ref()
            .map(|recorder| recorder.lock().map(|list| list.clone()).unwrap_or_default())
            .unwrap_or_default()
    }

    /// True when a live APNs client is configured.
    pub fn is_configured(&self) -> bool {
        self.inner.apns.is_some()
    }

    /// The VAPID public key browsers subscribe with; `None` when Web Push is unavailable.
    pub fn web_public_key(&self) -> Option<String> {
        self.inner.web.get().map(WebPushClient::public_key_b64url)
    }

    /// `endpoint` as this server would contact it, when it is a push service it sends to.
    pub fn allowed_web_endpoint(&self, endpoint: &str) -> Option<reqwest::Url> {
        self.inner.web.get()?.allowed_endpoint(endpoint)
    }

    /// Delivers `event` to the devices that should hear about it.
    ///
    /// Human: Relays can take seconds; the request that caused the event does not wait.
    pub async fn dispatch(&self, event: PushEvent) {
        if self.inner.recorder.is_some() {
            self.deliver(event).await;
            return;
        }
        let service = self.clone();
        tokio::spawn(async move { service.deliver(event).await });
    }

    async fn deliver(&self, event: PushEvent) {
        match event {
            PushEvent::Message {
                recipient,
                sender,
                conversation_id,
                message_id,
            } => {
                self.notify(
                    recipient,
                    sender,
                    NotificationKind::Message,
                    Some(conversation_id),
                    Some(message_id),
                )
                .await;
            }
            PushEvent::Reaction {
                recipient,
                reactor,
                conversation_id,
                message_id,
            } => {
                self.notify(
                    recipient,
                    reactor,
                    NotificationKind::Reaction,
                    Some(conversation_id),
                    Some(message_id),
                )
                .await;
            }
            PushEvent::ContactRequest {
                recipient,
                requester,
            } => {
                self.notify(
                    recipient,
                    requester,
                    NotificationKind::ContactRequest,
                    None,
                    None,
                )
                .await;
            }
            PushEvent::BadgeSync {
                recipient,
                reader_device,
            } => self.sync_badges(recipient, reader_device).await,
            PushEvent::IncomingCall {
                recipient,
                caller,
                call_id,
                modality,
            } => {
                self.notify_call(recipient, caller, call_id, &modality)
                    .await
            }
            PushEvent::MissedCall {
                recipient,
                caller,
                call_id,
            } => self.notify_missed_call(recipient, caller, call_id).await,
            PushEvent::CallEnded {
                recipient,
                caller,
                call_id,
                except_device,
            } => {
                self.notify_call_ended(recipient, caller, call_id, except_device)
                    .await
            }
        }
    }

    async fn notify(
        &self,
        recipient: Uuid,
        from: Uuid,
        kind: NotificationKind,
        conversation_id: Option<Uuid>,
        message_id: Option<Uuid>,
    ) {
        // Saved Messages, and our own reactions to our own messages.
        if recipient == from {
            return;
        }
        let muted = matches!(kind, NotificationKind::Message | NotificationKind::Reaction)
            && self.is_muted(recipient, from).await;
        let targets = self.targets(recipient, None, false).await;
        let mut sender_name: Option<Option<String>> = None;
        let mut badges = BadgeCache::default();
        for target in targets {
            if self
                .inner
                .realtime
                .is_device_foreground(recipient, target.device_id)
                .await
            {
                // The app is in front there and shows this itself.
                continue;
            }
            let settings = target.settings();
            if muted {
                // Silent — but an icon that counts muted chats still goes up.
                if kind == NotificationKind::Message
                    && settings.enabled
                    && settings.badge
                    && settings.badge_includes_muted
                    && let Some(badge) = badges.get(&self.inner.pool, recipient, true).await
                {
                    self.push_badge(&target, badge).await;
                }
                tracing::debug!(%recipient, kind = kind.as_str(), "push skipped: chat muted");
                continue;
            }
            let wanted = settings.enabled
                && match kind {
                    NotificationKind::Reaction => settings.reactions,
                    NotificationKind::ContactRequest => settings.contact_requests,
                    NotificationKind::Message
                    | NotificationKind::Call
                    | NotificationKind::VideoCall
                    | NotificationKind::MissedCall
                    | NotificationKind::CallEnded
                    | NotificationKind::Test => true,
                };
            if !wanted {
                continue;
            }
            let name = if settings.show_sender {
                if sender_name.is_none() {
                    sender_name = Some(self.username(from).await);
                }
                sender_name.clone().flatten()
            } else {
                None
            };
            // Left out when it cannot be counted: a wrong 0 would clear the icon.
            let badge = if settings.badge {
                badges
                    .get(&self.inner.pool, recipient, settings.badge_includes_muted)
                    .await
            } else {
                None
            };
            let notification = Notification {
                kind,
                conversation_id,
                peer_user_id: Some(from),
                message_id,
                call_id: None,
                sender_name: name,
                badge,
            };
            self.send_notification(&target, &settings, &notification)
                .await;
        }
    }

    /// The icon badge on the recipient's offline iPhones after they read on `reader_device`.
    async fn sync_badges(&self, recipient: Uuid, reader_device: Uuid) {
        let mut badges = BadgeCache::default();
        for target in self.targets(recipient, None, false).await {
            if target.device_id == reader_device {
                continue;
            }
            if target.apns_token.is_none() {
                continue;
            }
            let settings = target.settings();
            if !settings.enabled || !settings.badge {
                continue;
            }
            if self
                .inner
                .realtime
                .is_device_foreground(recipient, target.device_id)
                .await
            {
                continue;
            }
            let Some(badge) = badges
                .get(&self.inner.pool, recipient, settings.badge_includes_muted)
                .await
            else {
                continue;
            };
            self.push_badge(&target, badge).await;
        }
    }

    /// A badge-only push to an iPhone: the icon count changes, nothing is shown or heard.
    /// Browsers get none — every Web Push must show a notification.
    async fn push_badge(&self, target: &Target, badge: i64) {
        let (Some(token), Some(environment)) = (&target.apns_token, &target.apns_environment)
        else {
            return;
        };
        let payload = payload::apns_badge(badge);
        self.send_apns(
            target.device_id,
            token,
            ApnsEnvironment::parse(environment),
            PushChannel::Apns,
            ApnsRequest {
                push_type: ApnsPushType::Alert,
                priority: 5,
                expiration: Some(unix_now() + PUSH_LIFETIME_SECS),
                collapse_id: Some("badge"),
                payload: &payload,
            },
        )
        .await;
    }

    /// Rings the callee's devices that the socket's `call.ring` may not reach.
    ///
    /// Human: An iPhone with a PushKit token always gets a VoIP push, connected or not: a
    /// phone the OS just suspended still looks connected for a while, and would miss the ring.
    /// The app reports it to CallKit (which shows a call it already shows only once), then
    /// connects and checks the call still rings. Older iPhones get an "Incoming call" alert
    /// and browsers a Web Push, each while that device is not in the foreground — a socket
    /// left open by a suspended app or an unfocused tab does not count. Mutes do not silence
    /// a call; a device with notifications off gets nothing.
    async fn notify_call(&self, recipient: Uuid, caller: Uuid, call_id: Uuid, modality: &str) {
        if recipient == caller {
            return;
        }
        let kind = if modality == "video" {
            NotificationKind::VideoCall
        } else {
            NotificationKind::Call
        };
        let mut caller_name: Option<Option<String>> = None;
        for target in self.targets(recipient, None, true).await {
            let settings = target.settings();
            if !settings.enabled {
                continue;
            }
            let name = if settings.show_sender {
                if caller_name.is_none() {
                    caller_name = Some(self.username(caller).await);
                }
                caller_name.clone().flatten()
            } else {
                None
            };
            let notification = Notification {
                kind,
                conversation_id: None,
                peer_user_id: Some(caller),
                message_id: None,
                call_id: Some(call_id),
                sender_name: name,
                badge: None,
            };
            if let (Some(token), Some(environment)) = (&target.voip_token, &target.voip_environment)
            {
                let payload = payload::apns_voip(&notification, target.payload_key.as_deref());
                self.send_apns(
                    target.device_id,
                    token,
                    ApnsEnvironment::parse(environment),
                    PushChannel::Apns,
                    ApnsRequest {
                        push_type: ApnsPushType::Voip,
                        priority: 10,
                        expiration: Some(unix_now() + CALL_PUSH_LIFETIME_SECS),
                        collapse_id: None,
                        payload: &payload,
                    },
                )
                .await;
                continue;
            }
            if target.apns_token.is_none() && target.web_subscription().is_none() {
                continue;
            }
            if self
                .inner
                .realtime
                .is_device_foreground(recipient, target.device_id)
                .await
            {
                continue;
            }
            self.send_notification(&target, &settings, &notification)
                .await;
        }
    }

    /// Tells PushKit iPhones the call is over, so CallKit stops ringing after a hang-up the
    /// socket never delivered. The device that answered or ended it is skipped: that push
    /// would drop the call it is in.
    async fn notify_call_ended(
        &self,
        recipient: Uuid,
        caller: Uuid,
        call_id: Uuid,
        except_device: Option<Uuid>,
    ) {
        let mut caller_name: Option<Option<String>> = None;
        for target in self.targets(recipient, None, true).await {
            if except_device == Some(target.device_id) {
                continue;
            }
            let (Some(token), Some(environment)) = (&target.voip_token, &target.voip_environment)
            else {
                continue;
            };
            if !target.settings().enabled {
                continue;
            }
            let name = if target.settings().show_sender {
                if caller_name.is_none() {
                    caller_name = Some(self.username(caller).await);
                }
                caller_name.clone().flatten()
            } else {
                None
            };
            let notification = Notification {
                kind: NotificationKind::CallEnded,
                conversation_id: None,
                peer_user_id: Some(caller),
                message_id: None,
                call_id: Some(call_id),
                sender_name: name,
                badge: None,
            };
            let payload = payload::apns_voip(&notification, target.payload_key.as_deref());
            self.send_apns(
                target.device_id,
                token,
                ApnsEnvironment::parse(environment),
                PushChannel::Apns,
                ApnsRequest {
                    push_type: ApnsPushType::Voip,
                    priority: 10,
                    expiration: Some(unix_now() + CALL_PUSH_LIFETIME_SECS),
                    collapse_id: None,
                    payload: &payload,
                },
            )
            .await;
        }
    }

    /// "Missed call" where an "Incoming call" notification may still show: it replaces that
    /// one (same APNs collapse id, same Web Push tag). iPhones that rang through PushKit get
    /// a `call_ended` VoIP push instead and dismiss CallKit themselves.
    async fn notify_missed_call(&self, recipient: Uuid, caller: Uuid, call_id: Uuid) {
        let mut caller_name: Option<Option<String>> = None;
        for target in self.targets(recipient, None, true).await {
            if target.voip_token.is_some() {
                continue;
            }
            if target.apns_token.is_none() && target.web_subscription().is_none() {
                continue;
            }
            let settings = target.settings();
            if !settings.enabled {
                continue;
            }
            if self
                .inner
                .realtime
                .is_device_foreground(recipient, target.device_id)
                .await
            {
                continue;
            }
            let name = if settings.show_sender {
                if caller_name.is_none() {
                    caller_name = Some(self.username(caller).await);
                }
                caller_name.clone().flatten()
            } else {
                None
            };
            let notification = Notification {
                kind: NotificationKind::MissedCall,
                conversation_id: None,
                peer_user_id: Some(caller),
                message_id: None,
                call_id: Some(call_id),
                sender_name: name,
                badge: None,
            };
            self.send_notification(&target, &settings, &notification)
                .await;
        }
    }

    /// Sends a test notification to one device, online or not: the settings screen's
    /// "Send a test notification".
    pub async fn send_test(&self, user_id: Uuid, device_id: Uuid) -> TestPushOutcome {
        let Some(target) = self
            .targets(user_id, Some(device_id), false)
            .await
            .into_iter()
            .next()
        else {
            return TestPushOutcome {
                channel: None,
                status: "not_registered",
                detail: None,
            };
        };
        let settings = target.settings();
        let notification = Notification {
            kind: NotificationKind::Test,
            conversation_id: None,
            peer_user_id: None,
            message_id: None,
            call_id: None,
            sender_name: None,
            badge: None,
        };
        let (channel, delivery) = self
            .send_notification(&target, &settings, &notification)
            .await;
        let (status, detail) = match delivery {
            Delivery::Sent => ("sent", None),
            Delivery::NotConfigured => ("not_configured", None),
            Delivery::Misconfigured(reason) => ("misconfigured", Some(reason)),
            Delivery::Rejected(reason) => ("rejected", Some(reason)),
            Delivery::Failed(reason) => ("failed", Some(reason)),
        };
        TestPushOutcome {
            channel: Some(channel),
            status,
            detail,
        }
    }

    async fn send_notification(
        &self,
        target: &Target,
        settings: &NotificationSettings,
        notification: &Notification,
    ) -> (PushChannel, Delivery) {
        let ringing = matches!(
            notification.kind,
            NotificationKind::Call | NotificationKind::VideoCall
        );
        if let (Some(token), Some(environment)) = (&target.apns_token, &target.apns_environment) {
            let sound = payload::apns_sound(&settings.sound);
            let payload = payload::apns_alert(
                notification,
                sound.as_deref(),
                target.payload_key.as_deref(),
            );
            // "Missed call" takes the place of the call's "Incoming call".
            let collapse_id = notification.call_id.map(|id| id.to_string());
            let delivery = self
                .send_apns(
                    target.device_id,
                    token,
                    ApnsEnvironment::parse(environment),
                    PushChannel::Apns,
                    ApnsRequest {
                        push_type: ApnsPushType::Alert,
                        priority: 10,
                        expiration: Some(
                            unix_now()
                                + if ringing {
                                    CALL_PUSH_LIFETIME_SECS
                                } else {
                                    PUSH_LIFETIME_SECS
                                },
                        ),
                        collapse_id: collapse_id.as_deref(),
                        payload: &payload,
                    },
                )
                .await;
            return (PushChannel::Apns, delivery);
        }
        let Some(subscription) = target.web_subscription() else {
            return (PushChannel::Web, Delivery::Failed("no subscription".into()));
        };
        let payload = payload::web(notification, settings.sound == "none");
        let options = WebPushOptions {
            ttl_secs: if notification.kind == NotificationKind::Test {
                60
            } else if ringing {
                u32::try_from(CALL_PUSH_LIFETIME_SECS).unwrap_or(u32::MAX)
            } else {
                u32::try_from(PUSH_LIFETIME_SECS).unwrap_or(u32::MAX)
            },
            urgency: if notification.kind == NotificationKind::Reaction {
                Urgency::Normal
            } else {
                Urgency::High
            },
            // Queued message pushes of one chat collapse into its newest one. Reactions get no
            // topic: one must not replace a message the browser has not been shown yet.
            topic: (notification.kind != NotificationKind::Reaction)
                .then(|| notification.thread().replace('-', "")),
        };
        let delivery = self
            .send_web(target.device_id, &subscription, &payload, &options)
            .await;
        (PushChannel::Web, delivery)
    }

    async fn send_apns(
        &self,
        device_id: Uuid,
        token: &str,
        environment: ApnsEnvironment,
        channel: PushChannel,
        request: ApnsRequest<'_>,
    ) -> Delivery {
        if let Some(recorder) = &self.inner.recorder {
            if let Ok(mut list) = recorder.lock() {
                list.push(SentPush {
                    device_id,
                    channel,
                    apns_push_type: Some(request.push_type),
                    payload: request.payload.clone(),
                });
            }
            return Delivery::Sent;
        }
        let token_kind = if request.push_type == ApnsPushType::Voip {
            "voip"
        } else {
            "alert"
        };
        let Some(client) = &self.inner.apns else {
            tracing::info!(
                %device_id,
                channel = ?channel,
                "apns push not sent (set APNS_KEY_PATH or APNS_KEY_PEM + KEY_ID/TEAM_ID/TOPIC)"
            );
            return Delivery::NotConfigured;
        };
        match client.send(token, environment, request).await {
            ApnsSendOutcome::Accepted { apns_id } => {
                tracing::info!(%device_id, channel = ?channel, ?apns_id, "apns push accepted");
                Delivery::Sent
            }
            ApnsSendOutcome::InvalidToken { reason, status } => {
                tracing::warn!(%device_id, %reason, status, "apns token rejected — removing");
                let deleted = sqlx::query(
                    r#"DELETE FROM push_tokens WHERE device_id = $1 AND kind = $2 AND apns_token = $3"#,
                )
                .bind(device_id)
                .bind(token_kind)
                .bind(token)
                .execute(&self.inner.pool)
                .await;
                if let Err(err) = deleted {
                    tracing::warn!(error = %err, %device_id, "delete push token failed");
                }
                Delivery::Rejected(reason)
            }
            ApnsSendOutcome::Failed { reason, status }
                if client::is_provider_config_reason(&reason) =>
            {
                tracing::error!(
                    %device_id,
                    %reason,
                    status,
                    "apns refused this server's key or topic: check APNS_KEY_ID / APNS_TEAM_ID / \
                     APNS_TOPIC and the key's environment (Sandbox & Production)"
                );
                Delivery::Misconfigured(reason)
            }
            ApnsSendOutcome::Failed { reason, status } => {
                tracing::warn!(%device_id, %reason, status, "apns push failed");
                Delivery::Failed(reason)
            }
        }
    }

    async fn send_web(
        &self,
        device_id: Uuid,
        subscription: &WebSubscription,
        payload: &Value,
        options: &WebPushOptions,
    ) -> Delivery {
        if let Some(recorder) = &self.inner.recorder {
            if let Ok(mut list) = recorder.lock() {
                list.push(SentPush {
                    device_id,
                    channel: PushChannel::Web,
                    apns_push_type: None,
                    payload: payload.clone(),
                });
            }
            return Delivery::Sent;
        }
        let Some(client) = self.inner.web.get() else {
            tracing::info!(%device_id, "web push not sent (no VAPID key loaded)");
            return Delivery::NotConfigured;
        };
        match client
            .send(subscription, payload.to_string().as_bytes(), options)
            .await
        {
            WebPushOutcome::Accepted => {
                tracing::info!(%device_id, "web push accepted");
                Delivery::Sent
            }
            WebPushOutcome::Gone { status } => {
                tracing::info!(%device_id, status, "web push subscription gone — removing");
                let deleted = sqlx::query(
                    r#"DELETE FROM web_push_subscriptions WHERE device_id = $1 AND endpoint = $2"#,
                )
                .bind(device_id)
                .bind(&subscription.endpoint)
                .execute(&self.inner.pool)
                .await;
                if let Err(err) = deleted {
                    tracing::warn!(error = %err, %device_id, "delete web push subscription failed");
                }
                Delivery::Rejected(format!("subscription gone ({status})"))
            }
            // The push service refused the VAPID signature or key: the server's, not the browser's.
            WebPushOutcome::Failed {
                status: status @ (401 | 403),
                reason,
            } => {
                tracing::error!(%device_id, status, %reason, "web push refused this server's VAPID key");
                Delivery::Misconfigured(format!("{status} {reason}").trim().to_string())
            }
            WebPushOutcome::Failed { status, reason } => {
                tracing::warn!(%device_id, status, %reason, "web push failed");
                Delivery::Failed(reason)
            }
        }
    }

    /// The user's signed-in devices that registered for alerts, with their settings; with
    /// `with_voip`, also those that registered only for PushKit, and their PushKit tokens.
    async fn targets(
        &self,
        user_id: Uuid,
        only_device: Option<Uuid>,
        with_voip: bool,
    ) -> Vec<Target> {
        let rows = sqlx::query_as::<_, Target>(
            r#"
            SELECT
                d.id AS device_id,
                s.enabled, s.show_sender, s.reactions, s.contact_requests, s.sound, s.badge,
                s.badge_includes_muted,
                pt.apns_token, pt.environment AS apns_environment, pt.payload_key,
                CASE WHEN $3 THEN vt.apns_token END AS voip_token,
                CASE WHEN $3 THEN vt.environment END AS voip_environment,
                w.endpoint, w.p256dh, w.auth
            FROM devices d
            LEFT JOIN device_notification_settings s ON s.device_id = d.id
            LEFT JOIN push_tokens pt ON pt.device_id = d.id AND pt.kind = 'alert'
            LEFT JOIN push_tokens vt ON vt.device_id = d.id AND vt.kind = 'voip'
            LEFT JOIN web_push_subscriptions w ON w.device_id = d.id
            WHERE d.user_id = $1
              AND d.revoked_at IS NULL
              -- Signed out elsewhere (a password change) but not wiped yet: it keeps its
              -- registration until it opens again, and must not get the account's pushes.
              AND EXISTS (
                  SELECT 1 FROM sessions s WHERE s.device_id = d.id AND s.revoked_at IS NULL
              )
              AND ($2::uuid IS NULL OR d.id = $2)
              AND (
                  pt.device_id IS NOT NULL
                  OR w.device_id IS NOT NULL
                  OR ($3 AND vt.device_id IS NOT NULL)
              )
            "#,
        )
        .bind(user_id)
        .bind(only_device)
        .bind(with_voip)
        .fetch_all(&self.inner.pool)
        .await;
        match rows {
            Ok(rows) => rows,
            Err(err) => {
                tracing::warn!(error = %err, %user_id, "load push targets failed");
                Vec::new()
            }
        }
    }

    async fn is_muted(&self, user_id: Uuid, peer_user_id: Uuid) -> bool {
        sqlx::query_scalar(
            r#"
            SELECT EXISTS(
                SELECT 1 FROM chat_mutes
                WHERE user_id = $1 AND peer_user_id = $2
                  AND (muted_until IS NULL OR muted_until > now())
            )
            "#,
        )
        .bind(user_id)
        .bind(peer_user_id)
        .fetch_one(&self.inner.pool)
        .await
        .unwrap_or(false)
    }

    async fn username(&self, user_id: Uuid) -> Option<String> {
        sqlx::query_scalar::<_, Option<String>>(r#"SELECT username FROM users WHERE id = $1"#)
            .bind(user_id)
            .fetch_optional(&self.inner.pool)
            .await
            .ok()
            .flatten()
            .flatten()
    }
}

/// Unread totals for one dispatch, computed at most once per "muted chats count" choice.
/// `None` when the count failed: the push then carries no badge rather than a wrong one.
#[derive(Default)]
struct BadgeCache {
    without_muted: Option<Option<i64>>,
    with_muted: Option<Option<i64>>,
}

impl BadgeCache {
    async fn get(&mut self, pool: &PgPool, user_id: Uuid, include_muted: bool) -> Option<i64> {
        let slot = if include_muted {
            &mut self.with_muted
        } else {
            &mut self.without_muted
        };
        if let Some(value) = *slot {
            return value;
        }
        let value = crate::routes::conversations::unread_total(pool, user_id, include_muted)
            .await
            .map_err(|err| {
                tracing::warn!(error = %err, %user_id, "unread total for badge failed");
            })
            .ok();
        *slot = Some(value);
        value
    }
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
