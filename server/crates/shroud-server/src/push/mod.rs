//! APNs data-push dispatch (opaque ids only; no message content).

mod client;

pub use client::{ApnsClient, ApnsConfig, ApnsEnvironment, ApnsSendOutcome, apns_config_from_env};

use std::sync::Arc;

use sqlx::PgPool;
use uuid::Uuid;

use crate::realtime::RealtimeHub;

/// Push dispatcher shared in app state.
#[derive(Clone)]
pub struct PushService {
    pool: PgPool,
    realtime: Arc<RealtimeHub>,
    apns: Option<ApnsClient>,
}

impl PushService {
    pub fn new(pool: PgPool, realtime: Arc<RealtimeHub>, apns: Option<ApnsClient>) -> Self {
        Self {
            pool,
            realtime,
            apns,
        }
    }

    /// True when a live APNs client is configured.
    pub fn is_configured(&self) -> bool {
        self.apns.is_some()
    }

    /// Sends silent data pushes to a user's devices if none are online on WS.
    ///
    /// Human: Payload is opaque ids only — never ciphertext or previews.
    /// Agent: CHECKS online via hub/Redis; SELECT push_tokens; HTTP/2 APNs or log-only.
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
            device_id: Uuid,
            apns_token: String,
            environment: String,
        }

        let tokens: Vec<TokenRow> = match sqlx::query_as(
            r#"
            SELECT pt.device_id, pt.apns_token, pt.environment
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

        let Some(client) = &self.apns else {
            // Human: Registration works without credentials; live send needs APNS_* env.
            tracing::info!(
                %recipient_user_id,
                token_count = tokens.len(),
                %message_id,
                "apns data push (not configured — set APNS_KEY_PATH or APNS_KEY_PEM + KEY_ID/TEAM_ID/TOPIC)"
            );
            return;
        };

        for row in tokens {
            let env = ApnsEnvironment::parse(&row.environment);
            let outcome = client
                .send_data_push(&row.apns_token, env, &payload)
                .await;

            match outcome {
                ApnsSendOutcome::Accepted { apns_id } => {
                    tracing::info!(
                        %recipient_user_id,
                        %message_id,
                        device_id = %row.device_id,
                        apns_env = env.as_str(),
                        topic = %client.topic(),
                        ?apns_id,
                        "apns data push accepted"
                    );
                }
                ApnsSendOutcome::InvalidToken { reason, status } => {
                    tracing::warn!(
                        %recipient_user_id,
                        device_id = %row.device_id,
                        %reason,
                        status,
                        "apns invalid token — removing"
                    );
                    if let Err(err) = delete_push_token(&self.pool, row.device_id).await {
                        tracing::warn!(error = %err, device_id = %row.device_id, "delete push token failed");
                    }
                }
                ApnsSendOutcome::Failed { reason, status } => {
                    tracing::warn!(
                        %recipient_user_id,
                        %message_id,
                        device_id = %row.device_id,
                        apns_env = env.as_str(),
                        %reason,
                        status,
                        "apns data push failed"
                    );
                }
            }
        }
    }
}

async fn delete_push_token(pool: &PgPool, device_id: Uuid) -> Result<(), sqlx::Error> {
    sqlx::query(
        r#"
        DELETE FROM push_tokens WHERE device_id = $1
        "#,
    )
    .bind(device_id)
    .execute(pool)
    .await?;
    Ok(())
}
