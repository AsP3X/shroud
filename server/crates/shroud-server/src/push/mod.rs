//! Push notifications: which of a user's devices get one, and what it says.
//!
//! Human: A device in the foreground notifies its user itself — it can read the message.
//! One that is signed in but not in front (the app backgrounded, the tab unfocused) gets a
//! push even if its socket has not dropped yet: a suspended app keeps that socket open and
//! would otherwise swallow the notice. The relay is APNs for the iPhone and Web Push for a
//! browser and for the Android app (through the UnifiedPush distributor its user chose). A
//! push carries ids and a kind, plus the sender's name when the device asked for it — sealed
//! so the relay cannot read it. Never message content: the server has none.
//! An Android app, unlike a browser, need not show what it is sent, so it also gets what an
//! iPhone gets by PushKit: call rings while it looks in front and `call_ended`, plus `read`
//! when a chat is read elsewhere.
//! Agent: `dispatch` spawns (a recording service delivers inline); READS devices,
//! device_notification_settings, push_tokens, web_push_subscriptions, chat_mutes; DELETES
//! tokens and subscriptions their relay reports gone.

mod check;
mod client;
mod payload;
pub mod web_push;

use std::sync::{Arc, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sqlx::PgPool;
use uuid::Uuid;

pub use check::PushCheck;
pub use client::{
    ApnsClient, ApnsConfig, ApnsEnvironment, ApnsPushType, ApnsRequest, ApnsSendOutcome,
    apns_config_from_env,
};
pub use payload::{
    Notification, NotificationKind, apns_collapse_id, apns_thread_id, extension_aad,
};
pub use web_push::{
    SubscriptionClient, UnifiedPushPolicy, VapidKey, WebPushClient, WebPushOptions,
    WebSubscription, push_topic,
};

use crate::realtime::RealtimeHub;
use web_push::{Urgency, WebPushOutcome};

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
    /// `recipient` read chats on `reader_device`, or changed a mute there: their other iPhones'
    /// icon badges follow. With `conversation_id` (a chat was read), their other Android apps
    /// also close that chat's notifications.
    BadgeSync {
        recipient: Uuid,
        reader_device: Uuid,
        conversation_id: Option<Uuid>,
    },
    /// `caller` is ringing `recipient`: every iPhone with a VoIP token rings through PushKit,
    /// every Android app through UnifiedPush; older iPhones and browsers that are not in the
    /// foreground get a notification.
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
    /// The call is over for `recipient`'s iPhones that rang through PushKit and Android apps
    /// that rang through UnifiedPush. `except_device` is the one that answered or ended it
    /// here, and must not be told to drop its own call.
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
    /// Web Push to a browser's push service.
    Web,
    /// Web Push to an Android app's UnifiedPush distributor.
    #[serde(rename = "unifiedpush")]
    UnifiedPush,
}

/// A push as handed to its relay (tests read these from a recording service).
#[derive(Debug, Clone)]
pub struct SentPush {
    pub device_id: Uuid,
    pub channel: PushChannel,
    /// `apns-push-type` for APNs (a PushKit ring is `Voip`); `None` for Web Push.
    pub apns_push_type: Option<ApnsPushType>,
    /// `apns-collapse-id` for APNs, when the push has one.
    pub apns_collapse_id: Option<String>,
    /// `TTL`, `Urgency` and `Topic` of a Web Push; `None` for APNs.
    pub web_options: Option<WebPushOptions>,
    /// APNs JSON, or the Web Push JSON before encryption.
    pub payload: Value,
}

/// How to reach a device the account is removing, read before its registrations are purged.
///
/// Human: Removal deletes the device's push token and subscription with everything else, but
/// the device still holds the account's messages. One last push, sent once the removal has
/// committed, wakes it so it wipes itself now instead of whenever it is next opened.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct RemovedDeviceWake {
    pub device_id: Uuid,
    apns_token: Option<String>,
    apns_environment: Option<String>,
    endpoint: Option<String>,
    p256dh: Option<Vec<u8>>,
    auth: Option<Vec<u8>>,
    web_client: Option<String>,
}

impl RemovedDeviceWake {
    /// The device's alert token and Web Push subscription (a browser's, or an Android app's
    /// through UnifiedPush); `None` when it registered neither.
    ///
    /// Agent: DB SELECT push_tokens (kind 'alert') + web_push_subscriptions for one device,
    /// inside the removing transaction.
    pub async fn load(
        tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        device_id: Uuid,
    ) -> Result<Option<Self>, sqlx::Error> {
        sqlx::query_as::<_, Self>(
            r#"
            SELECT
                d.id AS device_id,
                pt.apns_token, pt.environment AS apns_environment,
                w.endpoint, w.p256dh, w.auth, w.client AS web_client
            FROM devices d
            LEFT JOIN push_tokens pt ON pt.device_id = d.id AND pt.kind = 'alert'
            LEFT JOIN web_push_subscriptions w ON w.device_id = d.id
            WHERE d.id = $1 AND (pt.device_id IS NOT NULL OR w.device_id IS NOT NULL)
            "#,
        )
        .bind(device_id)
        .fetch_optional(&mut **tx)
        .await
    }
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
    /// `apns`, `web` (a browser) or `unifiedpush` (an Android app); null when this device
    /// registered for none.
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
    web_client: Option<String>,
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
            client: SubscriptionClient::from_column(self.web_client.as_deref()),
        })
    }

    /// An Android app reached through UnifiedPush: its Web Push subscription is the relay (no
    /// APNs token comes first), and it is the app's.
    fn is_android(&self) -> bool {
        self.apns_token.is_none()
            && self
                .web_subscription()
                .is_some_and(|subscription| subscription.client == SubscriptionClient::Android)
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
        Self::recording_with_unifiedpush(pool, realtime, UnifiedPushPolicy::default())
    }

    /// Like [`Self::recording`], with Android subscriptions checked against `policy` instead
    /// of the built-in distributors alone.
    pub fn recording_with_unifiedpush(
        pool: PgPool,
        realtime: Arc<RealtimeHub>,
        policy: UnifiedPushPolicy,
    ) -> Self {
        let web = OnceLock::new();
        if let Some(client) = VapidKey::generate().ok().and_then(|(key, _)| {
            WebPushClient::new(key, "mailto:test@shroud.invalid".into(), vec![], policy).ok()
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

    /// `endpoint` as this server would contact it, when it sends to it for `client`: a
    /// browser push service, or for Android a UnifiedPush server its policy allows.
    pub async fn accept_web_endpoint(
        &self,
        endpoint: &str,
        client: SubscriptionClient,
    ) -> Option<reqwest::Url> {
        self.inner
            .web
            .get()?
            .accept_endpoint(endpoint, client)
            .await
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

    /// Wakes devices the account just removed (see [`RemovedDeviceWake`]). Their notification
    /// settings no longer apply: this is not a notification, and the browser's is neutral.
    ///
    /// Agent: spawns (a recording service sends inline); APNs `background` priority 5 with
    /// `apns_device_removed`, Web Push `web_device_removed`; nothing is read from the DB.
    pub async fn wake_removed_devices(&self, wakes: Vec<RemovedDeviceWake>) {
        if wakes.is_empty() {
            return;
        }
        if self.inner.recorder.is_some() {
            self.send_removal_wakes(wakes).await;
            return;
        }
        let service = self.clone();
        tokio::spawn(async move { service.send_removal_wakes(wakes).await });
    }

    async fn send_removal_wakes(&self, wakes: Vec<RemovedDeviceWake>) {
        for wake in wakes {
            if let (Some(token), Some(environment)) = (&wake.apns_token, &wake.apns_environment) {
                let payload = payload::apns_device_removed();
                self.send_apns(
                    wake.device_id,
                    token,
                    ApnsEnvironment::parse(environment),
                    PushChannel::Apns,
                    ApnsRequest {
                        // Apple takes background pushes only at priority 5.
                        push_type: ApnsPushType::Background,
                        priority: 5,
                        expiration: Some(unix_now() + PUSH_LIFETIME_SECS),
                        collapse_id: None,
                        payload: &payload,
                    },
                )
                .await;
            }
            if let (Some(endpoint), Some(p256dh), Some(auth)) =
                (&wake.endpoint, &wake.p256dh, &wake.auth)
            {
                let subscription = WebSubscription {
                    endpoint: endpoint.clone(),
                    p256dh: p256dh.clone(),
                    auth: auth.clone(),
                    client: SubscriptionClient::from_column(wake.web_client.as_deref()),
                };
                let options = WebPushOptions {
                    ttl_secs: u32::try_from(PUSH_LIFETIME_SECS).unwrap_or(u32::MAX),
                    urgency: Urgency::High,
                    topic: None,
                };
                self.send_web(
                    wake.device_id,
                    &subscription,
                    &payload::web_device_removed(),
                    &options,
                )
                .await;
            }
        }
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
                conversation_id,
            } => {
                self.sync_badges(recipient, reader_device, conversation_id)
                    .await
            }
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
            // The sender's name is not on this server. The phone fills it in from the
            // seal it already opened, once the two people have added each other.
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
                sender_name: None,
                badge,
            };
            self.send_notification(&target, &settings, &notification)
                .await;
        }
    }

    /// After the recipient read on `reader_device` (or changed a mute there): the icon badge
    /// on their other iPhones, and — when a chat was read — a `read` push that closes that
    /// chat's notifications on their other Android apps. A device in front heard
    /// `conversation.read` on its socket.
    async fn sync_badges(
        &self,
        recipient: Uuid,
        reader_device: Uuid,
        conversation_id: Option<Uuid>,
    ) {
        let mut badges = BadgeCache::default();
        for target in self.targets(recipient, None, false).await {
            if target.device_id == reader_device {
                continue;
            }
            let settings = target.settings();
            if !settings.enabled {
                continue;
            }
            let read_chat = if target.apns_token.is_some() {
                if !settings.badge {
                    continue;
                }
                None
            } else if target.is_android()
                && let Some(conversation_id) = conversation_id
            {
                Some(conversation_id)
            } else {
                // Browsers get none: every Web Push to them must show a notification.
                continue;
            };
            if self
                .inner
                .realtime
                .is_device_foreground(recipient, target.device_id)
                .await
            {
                continue;
            }
            let badge = if settings.badge {
                badges
                    .get(&self.inner.pool, recipient, settings.badge_includes_muted)
                    .await
            } else {
                None
            };
            match (read_chat, badge) {
                (Some(conversation_id), badge) => {
                    self.push_read(&target, conversation_id, badge).await;
                }
                (None, Some(badge)) => self.push_badge(&target, badge).await,
                // Not counted: a wrong 0 would clear the icon.
                (None, None) => {}
            }
        }
    }

    /// Closes a chat's notifications on an Android app that did not read it (N13): `read`
    /// with the new unread total, at `normal` urgency — nothing is shown. Its topic is the
    /// chat's ([`push_topic`] of the same id as a message push), so it replaces a message push
    /// for that chat still queued at the distributor.
    async fn push_read(&self, target: &Target, conversation_id: Uuid, badge: Option<i64>) {
        let Some(subscription) = target.web_subscription() else {
            return;
        };
        let options = WebPushOptions {
            ttl_secs: u32::try_from(PUSH_LIFETIME_SECS).unwrap_or(u32::MAX),
            urgency: Urgency::Normal,
            topic: Some(push_topic(&subscription.auth, &conversation_id.to_string())),
        };
        self.send_web(
            target.device_id,
            &subscription,
            &payload::web_read(conversation_id, badge),
            &options,
        )
        .await;
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
    /// connects and checks the call still rings. An Android app rings the same way through
    /// UnifiedPush, in front or not (`Urgency: high`, `TTL` = the ring time); it de-duplicates
    /// with the socket's `call.ring` by call id. Older iPhones get an "Incoming call" alert
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
        for target in self.targets(recipient, None, true).await {
            let settings = target.settings();
            if !settings.enabled {
                continue;
            }
            let notification = Notification {
                kind,
                conversation_id: None,
                peer_user_id: Some(caller),
                message_id: None,
                call_id: Some(call_id),
                sender_name: None,
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
            // A phone the OS just froze still looks connected: an Android app rings like a
            // PushKit iPhone, in front or not.
            if !target.is_android()
                && self
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

    /// Tells PushKit iPhones and Android apps the call is over, so they stop ringing after a
    /// hang-up the socket never delivered. The device that answered or ended it is skipped:
    /// that push would drop the call it is in. Browsers never rang, and get nothing.
    async fn notify_call_ended(
        &self,
        recipient: Uuid,
        caller: Uuid,
        call_id: Uuid,
        except_device: Option<Uuid>,
    ) {
        for target in self.targets(recipient, None, true).await {
            if except_device == Some(target.device_id) {
                continue;
            }
            let voip = target
                .voip_token
                .as_ref()
                .zip(target.voip_environment.as_ref());
            if voip.is_none() && !target.is_android() {
                continue;
            }
            let settings = target.settings();
            if !settings.enabled {
                continue;
            }
            let notification = Notification {
                kind: NotificationKind::CallEnded,
                conversation_id: None,
                peer_user_id: Some(caller),
                message_id: None,
                call_id: Some(call_id),
                sender_name: None,
                badge: None,
            };
            let Some((token, environment)) = voip else {
                // Android: `Urgency: high`, `TTL` = the ring time, in front or not.
                self.send_notification(&target, &settings, &notification)
                    .await;
                continue;
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
            let notification = Notification {
                kind: NotificationKind::MissedCall,
                conversation_id: None,
                peer_user_id: Some(caller),
                message_id: None,
                call_id: Some(call_id),
                sender_name: None,
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
            // "Missed call" takes the place of the call's "Incoming call". Keyed per iPhone, so
            // Apple can't match the caller's and callee's pushes by it.
            let collapse_id = notification.apns_collapse(target.payload_key.as_deref());
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
        let options = web_options(notification, &subscription);
        let delivery = self
            .send_web(target.device_id, &subscription, &payload, &options)
            .await;
        (web_channel(subscription.client), delivery)
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
                    apns_collapse_id: request.collapse_id.map(str::to_owned),
                    web_options: None,
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
            tracing::debug!(
                %device_id,
                channel = ?channel,
                "apns push not sent (set APNS_KEY_PATH or APNS_KEY_PEM + KEY_ID/TEAM_ID/TOPIC)"
            );
            return Delivery::NotConfigured;
        };
        match client.send(token, environment, request).await {
            ApnsSendOutcome::Accepted { apns_id } => {
                tracing::debug!(%device_id, channel = ?channel, ?apns_id, "apns push accepted");
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
        let channel = web_channel(subscription.client);
        if let Some(recorder) = &self.inner.recorder {
            if let Ok(mut list) = recorder.lock() {
                list.push(SentPush {
                    device_id,
                    channel,
                    apns_push_type: None,
                    apns_collapse_id: None,
                    web_options: Some(options.clone()),
                    payload: payload.clone(),
                });
            }
            return Delivery::Sent;
        }
        let Some(client) = self.inner.web.get() else {
            tracing::debug!(%device_id, ?channel, "web push not sent (no VAPID key loaded)");
            return Delivery::NotConfigured;
        };
        match client
            .send(subscription, payload.to_string().as_bytes(), options)
            .await
        {
            WebPushOutcome::Accepted => {
                tracing::debug!(%device_id, ?channel, "web push accepted");
                Delivery::Sent
            }
            WebPushOutcome::Gone { status } => {
                tracing::debug!(%device_id, ?channel, status, "web push subscription gone — removing");
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
                tracing::error!(%device_id, ?channel, status, %reason, "web push refused this server's VAPID key");
                Delivery::Misconfigured(format!("{status} {reason}").trim().to_string())
            }
            WebPushOutcome::Failed { status, reason } => {
                tracing::warn!(%device_id, ?channel, status, %reason, "web push failed");
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
                w.endpoint, w.p256dh, w.auth, w.client AS web_client
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

/// The relay a Web Push subscription stands for, as tests and `POST /push/test` name it.
fn web_channel(client: SubscriptionClient) -> PushChannel {
    match client {
        SubscriptionClient::Browser => PushChannel::Web,
        SubscriptionClient::Android => PushChannel::UnifiedPush,
    }
}

/// `TTL`, `Urgency` and `Topic` of a notification's Web Push.
///
/// Human: A ring, and the end of one, is worth delivering only while the call rings (60 s); a
/// test while someone looks at the screen (60 s); anything else for a day. Only reactions wait
/// for the phone to wake on its own (`normal`). Queued pushes of one chat collapse into its
/// newest one; reactions get no topic, since one must not replace a message not shown yet. An
/// Android app's call pushes collapse per call instead, like APNs' collapse id: "Missed call"
/// takes the place of its queued ring, one call never replaces another's, and `call_ended`
/// (no topic) never replaces a "Missed call". Every topic is a [`push_topic`] keyed with the
/// subscription's auth secret: the push service never sees a conversation or call id.
fn web_options(notification: &Notification, subscription: &WebSubscription) -> WebPushOptions {
    let client = subscription.client;
    let kind = notification.kind;
    let ttl_secs = match kind {
        NotificationKind::Test => 60,
        NotificationKind::Call | NotificationKind::VideoCall | NotificationKind::CallEnded => {
            CALL_PUSH_LIFETIME_SECS
        }
        _ => PUSH_LIFETIME_SECS,
    };
    let topic = match (client, kind) {
        (_, NotificationKind::Reaction)
        | (SubscriptionClient::Android, NotificationKind::CallEnded) => None,
        (
            SubscriptionClient::Android,
            NotificationKind::Call | NotificationKind::VideoCall | NotificationKind::MissedCall,
        ) => notification.call_id.map(|id| id.to_string()),
        _ => Some(notification.thread()),
    }
    .map(|id| push_topic(&subscription.auth, &id));
    WebPushOptions {
        ttl_secs: u32::try_from(ttl_secs).unwrap_or(u32::MAX),
        urgency: if kind == NotificationKind::Reaction {
            Urgency::Normal
        } else {
            Urgency::High
        },
        topic,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn subscription(client: SubscriptionClient, auth: [u8; 16]) -> WebSubscription {
        WebSubscription {
            endpoint: "https://ntfy.sh/up123?up=1".into(),
            p256dh: vec![4; 65],
            auth: auth.to_vec(),
            client,
        }
    }

    const AUTH: [u8; 16] = [5; 16];

    fn notification(kind: NotificationKind) -> Notification {
        Notification {
            kind,
            conversation_id: Some(Uuid::from_u128(0xc0)),
            peer_user_id: Some(Uuid::from_u128(0xa1)),
            message_id: None,
            call_id: Some(Uuid::from_u128(0xca11)),
            sender_name: None,
            badge: None,
        }
    }

    #[test]
    fn rings_live_as_long_as_the_call_rings_and_wake_the_phone() {
        assert_eq!(CALL_PUSH_LIFETIME_SECS, 60);
        for client in [SubscriptionClient::Browser, SubscriptionClient::Android] {
            for kind in [
                NotificationKind::Call,
                NotificationKind::VideoCall,
                NotificationKind::CallEnded,
            ] {
                let options = web_options(&notification(kind), &subscription(client, AUTH));
                assert_eq!(options.ttl_secs, 60, "{kind:?}");
                assert_eq!(options.urgency, Urgency::High, "{kind:?}");
            }
            let missed = web_options(
                &notification(NotificationKind::MissedCall),
                &subscription(client, AUTH),
            );
            assert_eq!(missed.ttl_secs, 24 * 60 * 60);
            assert_eq!(missed.urgency, Urgency::High);
            let test = web_options(
                &notification(NotificationKind::Test),
                &subscription(client, AUTH),
            );
            assert_eq!((test.ttl_secs, test.urgency), (60, Urgency::High));
            let reaction = web_options(
                &notification(NotificationKind::Reaction),
                &subscription(client, AUTH),
            );
            assert_eq!(reaction.urgency, Urgency::Normal);
            assert_eq!(reaction.topic, None);
            let message = web_options(
                &notification(NotificationKind::Message),
                &subscription(client, AUTH),
            );
            assert_eq!(
                (message.ttl_secs, message.urgency),
                (24 * 60 * 60, Urgency::High)
            );
            assert_eq!(
                message.topic,
                Some(push_topic(&AUTH, &Uuid::from_u128(0xc0).to_string()))
            );
        }
    }

    #[test]
    fn android_call_pushes_collapse_per_call_browsers_per_thread() {
        let call = push_topic(&AUTH, &Uuid::from_u128(0xca11).to_string());
        for kind in [
            NotificationKind::Call,
            NotificationKind::VideoCall,
            NotificationKind::MissedCall,
        ] {
            let android = web_options(
                &notification(kind),
                &subscription(SubscriptionClient::Android, AUTH),
            );
            assert_eq!(android.topic.as_deref(), Some(call.as_str()), "{kind:?}");
            let browser = web_options(
                &notification(kind),
                &subscription(SubscriptionClient::Browser, AUTH),
            );
            assert_eq!(browser.topic, Some(push_topic(&AUTH, "calls")), "{kind:?}");
        }
        let ended = web_options(
            &notification(NotificationKind::CallEnded),
            &subscription(SubscriptionClient::Android, AUTH),
        );
        assert_eq!(ended.topic, None);
        // A topic is at most 32 base64url characters (RFC 8030 §5.4).
        assert_eq!(call.len(), 32);
        assert!(
            call.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        );
    }

    /// Plan §1.1 rule 9: the distributor sees ciphertext and timing only. The same chat (or
    /// call) gives every subscription its own topic, and no topic shows the id it stands for.
    #[test]
    fn topics_never_show_an_id_and_differ_per_subscription() {
        let conversation = Uuid::from_u128(0xc0);
        let call = Uuid::from_u128(0xca11);
        for kind in [
            NotificationKind::Message,
            NotificationKind::Call,
            NotificationKind::MissedCall,
        ] {
            let mine = web_options(
                &notification(kind),
                &subscription(SubscriptionClient::Android, AUTH),
            )
            .topic
            .expect("a topic");
            let theirs = web_options(
                &notification(kind),
                &subscription(SubscriptionClient::Android, [6; 16]),
            )
            .topic
            .expect("a topic");
            assert_ne!(mine, theirs, "{kind:?}");
            for id in [conversation, call] {
                for shown in [id.to_string(), id.simple().to_string()] {
                    assert!(!mine.contains(&shown[..8]), "{kind:?} shows {shown}");
                }
            }
        }
        // The read push replaces the chat's queued message push: the same topic.
        assert_eq!(
            web_options(
                &notification(NotificationKind::Message),
                &subscription(SubscriptionClient::Android, AUTH)
            )
            .topic,
            Some(push_topic(&AUTH, &conversation.to_string()))
        );
    }

    #[test]
    fn the_test_channel_names_the_relay() {
        assert_eq!(
            serde_json::to_value(web_channel(SubscriptionClient::Android)).unwrap(),
            "unifiedpush"
        );
        assert_eq!(
            serde_json::to_value(web_channel(SubscriptionClient::Browser)).unwrap(),
            "web"
        );
        assert_eq!(serde_json::to_value(PushChannel::Apns).unwrap(), "apns");
    }
}
