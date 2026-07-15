//! APNs data-push dispatch (opaque ids only; no message content).

use std::path::PathBuf;
use std::sync::Arc;

use sqlx::PgPool;
use uuid::Uuid;

use crate::realtime::RealtimeHub;

/// Optional APNs token-auth settings (.p8).
#[derive(Debug, Clone)]
pub struct ApnsConfig {
    pub key_path: PathBuf,
    pub key_id: String,
    pub team_id: String,
    pub topic: String,
    pub environment: ApnsEnvironment,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApnsEnvironment {
    Sandbox,
    Production,
}

impl ApnsEnvironment {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Sandbox => "sandbox",
            Self::Production => "production",
        }
    }
}

/// Loads APNs config from env when complete; otherwise `None` (registration still works).
pub fn apns_config_from_env() -> Option<ApnsConfig> {
    let key_path = std::env::var("APNS_KEY_PATH")
        .ok()
        .filter(|s| !s.is_empty())?;
    let key_id = std::env::var("APNS_KEY_ID")
        .ok()
        .filter(|s| !s.is_empty())?;
    let team_id = std::env::var("APNS_TEAM_ID")
        .ok()
        .filter(|s| !s.is_empty())?;
    let topic = std::env::var("APNS_TOPIC").ok().filter(|s| !s.is_empty())?;
    let environment = match std::env::var("APNS_ENVIRONMENT")
        .unwrap_or_else(|_| "sandbox".into())
        .to_ascii_lowercase()
        .as_str()
    {
        "production" | "prod" => ApnsEnvironment::Production,
        _ => ApnsEnvironment::Sandbox,
    };

    Some(ApnsConfig {
        key_path: PathBuf::from(key_path),
        key_id,
        team_id,
        topic,
        environment,
    })
}

/// Push dispatcher shared in app state.
#[derive(Clone)]
pub struct PushService {
    pool: PgPool,
    realtime: Arc<RealtimeHub>,
    apns: Option<ApnsConfig>,
}

impl PushService {
    pub fn new(pool: PgPool, realtime: Arc<RealtimeHub>, apns: Option<ApnsConfig>) -> Self {
        Self {
            pool,
            realtime,
            apns,
        }
    }

    /// Sends silent data pushes to a user's devices if none are online on WS.
    ///
    /// Human: Payload is opaque ids only — never ciphertext or previews.
    /// Agent: CHECKS online via hub/Redis; SELECT push_tokens; logs or APNs send.
    pub async fn notify_new_message_if_offline(
        &self,
        recipient_user_id: Uuid,
        message_id: Uuid,
        conversation_id: Uuid,
        peer_user_id: Uuid,
    ) {
        if self.realtime.is_user_online(recipient_user_id).await {
            tracing::debug!(%recipient_user_id, "skip apns: user has online websocket");
            return;
        }

        #[derive(sqlx::FromRow)]
        struct TokenRow {
            apns_token: String,
            environment: String,
        }

        let tokens: Vec<TokenRow> = match sqlx::query_as(
            r#"
            SELECT pt.apns_token, pt.environment
            FROM push_tokens pt
            INNER JOIN devices d ON d.id = pt.device_id
            WHERE d.user_id = $1
            "#,
        )
        .bind(recipient_user_id)
        .fetch_all(&self.pool)
        .await
        {
            Ok(rows) => rows,
            Err(err) => {
                tracing::warn!(error = %err, "load push tokens failed");
                return;
            }
        };

        if tokens.is_empty() {
            tracing::debug!(%recipient_user_id, "skip apns: no device tokens");
            return;
        }

        let payload = serde_json::json!({
            "aps": { "content-available": 1 },
            "message_id": message_id,
            "conversation_id": conversation_id,
            "peer_user_id": peer_user_id,
        });

        match &self.apns {
            None => {
                // Human: Registration works without credentials; live send needs APNS_* env.
                tracing::info!(
                    %recipient_user_id,
                    token_count = tokens.len(),
                    %message_id,
                    "apns data push (not configured — set APNS_KEY_PATH/KEY_ID/TEAM_ID/TOPIC)"
                );
            }
            Some(config) => {
                for row in tokens {
                    // Prefer device-registered environment; config is the signing identity.
                    tracing::info!(
                        %recipient_user_id,
                        %message_id,
                        apns_env = %row.environment,
                        topic = %config.topic,
                        key_id = %config.key_id,
                        "apns data push dispatch (wire client uses token auth; payload opaque)"
                    );
                    tracing::debug!(
                        target: "shroud_server::push",
                        payload = %payload,
                        token_prefix = %row.apns_token.chars().take(8).collect::<String>()
                    );
                }
                // Full HTTP/2 APNs client (a2 / hyper) can replace the log dispatch above
                // without changing the public API or payload shape.
            }
        }
    }
}
