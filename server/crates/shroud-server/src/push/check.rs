//! The admin console's push check (`GET /operator/push/check`).
//!
//! Human: Each line asks the relay itself whether this server's setup works, without
//! notifying anyone. Apple is sent a request for a made-up device token: it checks the
//! provider token and the topic before the device, so `BadDeviceToken` means the key, team
//! and topic were accepted, while a setup error names what Apple refused. Browser push
//! services and UnifiedPush distributors get a `HEAD` on their origin only, so they see no
//! subscription and deliver nothing. The answer names hosts and counts, never an endpoint.
//! Agent: READS push_tokens (environments), web_push_subscriptions (one endpoint per host).
//! Every network step is bounded by [`STEP_TIMEOUT`] and they run concurrently, so the
//! whole check answers inside the console's 10-second upstream limit.

use std::collections::BTreeSet;
use std::future::Future;
use std::time::Duration;

use futures_util::future::join_all;
use serde::Serialize;
use serde_json::json;
use sqlx::Row;

use super::client::{self, ApnsClient};
use super::{
    ApnsEnvironment, ApnsPushType, ApnsRequest, ApnsSendOutcome, PushService, SubscriptionClient,
    WebPushClient, apns_config_from_env,
};

/// Upper bound for one probe. Several run at once; the console gives up after 10 s.
const STEP_TIMEOUT: Duration = Duration::from_secs(6);
/// A well-formed device token no device has. Apple answers `BadDeviceToken` for it once the
/// provider token and topic pass.
const PROBE_DEVICE_TOKEN: &str = "0000000000000000000000000000000000000000000000000000000000000000";
/// Distinct push hosts probed per kind of subscription; the busiest come first.
const MAX_HOSTS: i64 = 12;

/// One line of the check, in the console's order.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct PushCheck {
    pub item: &'static str,
    /// `ok`, `failed`, or `off` (not set up, or nothing to check).
    pub state: &'static str,
    pub detail: String,
}

impl PushCheck {
    fn ok(item: &'static str, detail: impl Into<String>) -> Self {
        Self {
            item,
            state: "ok",
            detail: detail.into(),
        }
    }

    fn failed(item: &'static str, detail: impl Into<String>) -> Self {
        Self {
            item,
            state: "failed",
            detail: detail.into(),
        }
    }

    fn off(item: &'static str, detail: impl Into<String>) -> Self {
        Self {
            item,
            state: "off",
            detail: detail.into(),
        }
    }
}

const APNS_KEY: &str = "APNs key";
const APNS_ALERTS: &str = "Apple accepts alerts";
const APNS_CALLS: &str = "Apple accepts calls (VoIP)";
const WEB_KEY: &str = "Web Push key";
const BROWSER_HOSTS: &str = "Browser push services answer";
const ANDROID_HOSTS: &str = "UnifiedPush distributors answer";

/// A push host and how many subscriptions point at it, with one of their endpoints to route by.
struct Host {
    client: SubscriptionClient,
    name: String,
    endpoint: String,
    subscriptions: i64,
}

impl PushService {
    /// Runs every line of the console's push check.
    pub async fn check(&self) -> Vec<PushCheck> {
        let (apns, web) = tokio::join!(self.check_apns(), self.check_web());
        let mut checks = apns;
        checks.extend(web);
        checks
    }

    async fn check_apns(&self) -> Vec<PushCheck> {
        let Some(client) = &self.inner.apns else {
            let key = if apns_config_from_env().is_some() {
                PushCheck::failed(
                    APNS_KEY,
                    "APNS_* is set, but the key couldn't be read when the API started. The API log says why; restart it once the key is fixed.",
                )
            } else {
                PushCheck::off(
                    APNS_KEY,
                    "APNS_* isn't set. iPhones hear about messages only while the app is open.",
                )
            };
            return vec![
                key,
                PushCheck::off(APNS_ALERTS, "Needs the APNs key."),
                PushCheck::off(APNS_CALLS, "Needs the APNs key."),
            ];
        };
        let key = match client.signing_check() {
            Ok(()) => PushCheck::ok(
                APNS_KEY,
                format!("Key {} signs Apple's provider token.", client.key_id()),
            ),
            Err(reason) => PushCheck::failed(APNS_KEY, reason),
        };
        let (alerts, calls) = tokio::join!(
            self.check_apple(client, "alert", ApnsPushType::Background, APNS_ALERTS),
            self.check_apple(client, "voip", ApnsPushType::Voip, APNS_CALLS),
        );
        vec![key, alerts, calls]
    }

    /// Asks Apple, in every environment `kind` tokens use (production when there are none),
    /// whether it accepts this server's key and topic.
    async fn check_apple(
        &self,
        client: &ApnsClient,
        kind: &str,
        push_type: ApnsPushType,
        item: &'static str,
    ) -> PushCheck {
        let environments = match self.apns_environments(kind).await {
            Ok(found) if found.is_empty() => vec![ApnsEnvironment::Production],
            Ok(found) => found,
            Err(reason) => return PushCheck::failed(item, reason),
        };
        let topic = match push_type {
            ApnsPushType::Voip => format!("{}.voip", client.topic()),
            _ => client.topic().to_string(),
        };
        let payload = match push_type {
            ApnsPushType::Voip => json!({}),
            _ => json!({ "aps": { "content-available": 1 } }),
        };
        let answers = join_all(environments.iter().map(|environment| {
            let payload = &payload;
            async move {
                let request = ApnsRequest {
                    push_type,
                    priority: if push_type == ApnsPushType::Voip {
                        10
                    } else {
                        5
                    },
                    expiration: Some(0),
                    collapse_id: None,
                    payload,
                };
                let outcome = bounded(client.send(PROBE_DEVICE_TOKEN, *environment, request)).await;
                (*environment, outcome)
            }
        }))
        .await;
        apple_verdict(item, &topic, &answers)
    }

    /// The APNs environments stored `kind` tokens are for.
    async fn apns_environments(&self, kind: &str) -> Result<Vec<ApnsEnvironment>, String> {
        let rows: Vec<String> = sqlx::query_scalar(
            "SELECT DISTINCT environment FROM push_tokens WHERE kind = $1 AND environment IS NOT NULL",
        )
        .bind(kind)
        .fetch_all(&self.inner.pool)
        .await
        .map_err(|_| "Couldn't read the registered iPhones from the database.".to_string())?;
        let mut environments: Vec<ApnsEnvironment> = rows
            .iter()
            .map(|value| ApnsEnvironment::parse(value))
            .collect();
        environments.sort_by_key(|environment| environment.as_str());
        environments.dedup();
        Ok(environments)
    }

    async fn check_web(&self) -> Vec<PushCheck> {
        let Some(web) = self.inner.web.get() else {
            let reason = "The VAPID key isn't loaded; the API log says why. Browsers and Android can't subscribe until it is.";
            return vec![
                PushCheck::failed(WEB_KEY, reason),
                PushCheck::off(BROWSER_HOSTS, "Needs the Web Push key."),
                PushCheck::off(ANDROID_HOSTS, "Needs the Web Push key."),
            ];
        };
        let key = match web.signing_check() {
            Ok(()) => {
                let public = web.public_key_b64url();
                let tail = &public[public.len().saturating_sub(8)..];
                PushCheck::ok(WEB_KEY, format!("Signs VAPID tokens · public key …{tail}"))
            }
            Err(reason) => PushCheck::failed(WEB_KEY, reason),
        };
        let hosts = match self.push_hosts().await {
            Ok(hosts) => hosts,
            Err(reason) => {
                return vec![
                    key,
                    PushCheck::failed(BROWSER_HOSTS, reason.clone()),
                    PushCheck::failed(ANDROID_HOSTS, reason),
                ];
            }
        };
        let (browsers, android): (Vec<Host>, Vec<Host>) = hosts
            .into_iter()
            .partition(|host| host.client == SubscriptionClient::Browser);
        let (browsers, android) = tokio::join!(
            check_hosts(
                web,
                BROWSER_HOSTS,
                &browsers,
                "No browser has subscribed yet."
            ),
            check_hosts(
                web,
                ANDROID_HOSTS,
                &android,
                "No Android device has subscribed yet."
            ),
        );
        vec![key, browsers, android]
    }

    /// The hosts subscriptions point at, busiest first, with one endpoint each to route by.
    async fn push_hosts(&self) -> Result<Vec<Host>, String> {
        let rows = sqlx::query(
            r#"
            SELECT client, host, endpoint, subscriptions FROM (
                SELECT client, host, endpoint,
                       count(*) OVER (PARTITION BY client, host) AS subscriptions,
                       row_number() OVER (PARTITION BY client, host ORDER BY endpoint) AS nth
                FROM (
                    SELECT coalesce(client, 'browser') AS client, endpoint,
                           lower(substring(endpoint from '^[A-Za-z]+://([^/:?#]+)')) AS host
                    FROM web_push_subscriptions
                ) AS subscription
                WHERE host IS NOT NULL
            ) AS counted
            WHERE nth = 1
            ORDER BY subscriptions DESC, host
            LIMIT $1
            "#,
        )
        .bind(MAX_HOSTS * 2)
        .fetch_all(&self.inner.pool)
        .await
        .map_err(|_| "Couldn't read the subscriptions from the database.".to_string())?;
        let mut hosts = Vec::with_capacity(rows.len());
        for row in rows {
            let client: String = row.try_get("client").map_err(|err| err.to_string())?;
            hosts.push(Host {
                client: SubscriptionClient::from_column(Some(&client)),
                name: row.try_get("host").map_err(|err| err.to_string())?,
                endpoint: row.try_get("endpoint").map_err(|err| err.to_string())?,
                subscriptions: row
                    .try_get("subscriptions")
                    .map_err(|err| err.to_string())?,
            });
        }
        Ok(hosts)
    }
}

/// Probes each host's origin and sums it up in one line.
async fn check_hosts(
    web: &WebPushClient,
    item: &'static str,
    hosts: &[Host],
    none: &str,
) -> PushCheck {
    if hosts.is_empty() {
        return PushCheck::off(item, none);
    }
    let hosts = &hosts[..hosts.len().min(MAX_HOSTS as usize)];
    let answers = join_all(hosts.iter().map(|host| async move {
        let answer = match bounded(web.probe_host(&host.endpoint, host.client)).await {
            Some(answer) => answer,
            None => Err(format!("no answer in {} s", STEP_TIMEOUT.as_secs())),
        };
        (host, answer)
    }))
    .await;
    hosts_verdict(item, &answers)
}

fn hosts_verdict(item: &'static str, answers: &[(&Host, Result<(), String>)]) -> PushCheck {
    let named = |host: &Host| {
        let unit = if host.subscriptions == 1 {
            "subscription"
        } else {
            "subscriptions"
        };
        format!("{} ({} {unit})", host.name, host.subscriptions)
    };
    let failures: Vec<String> = answers
        .iter()
        .filter_map(|(host, answer)| {
            answer
                .as_ref()
                .err()
                .map(|reason| format!("{}: {reason}", named(host)))
        })
        .collect();
    let fine: Vec<String> = answers
        .iter()
        .filter(|(_, answer)| answer.is_ok())
        .map(|(host, _)| named(host))
        .collect();
    if failures.is_empty() {
        PushCheck::ok(item, format!("{} answered.", fine.join(", ")))
    } else if fine.is_empty() {
        PushCheck::failed(item, failures.join(" · "))
    } else {
        PushCheck::failed(
            item,
            format!("{} · {} answered.", failures.join(" · "), fine.join(", ")),
        )
    }
}

/// What Apple's answers in each environment say about this server's setup.
fn apple_verdict(
    item: &'static str,
    topic: &str,
    answers: &[(ApnsEnvironment, Option<ApnsSendOutcome>)],
) -> PushCheck {
    let mut accepted = BTreeSet::new();
    let mut refused = Vec::new();
    for (environment, outcome) in answers {
        let place = environment_name(*environment);
        match outcome {
            // A made-up token can't be delivered: Apple got past the key and topic to say so.
            Some(ApnsSendOutcome::InvalidToken { .. } | ApnsSendOutcome::Accepted { .. }) => {
                accepted.insert(place);
            }
            Some(ApnsSendOutcome::Failed { reason, .. })
                if client::is_provider_config_reason(reason) =>
            {
                refused.push(format!(
                    "{place}: Apple refused the key or topic ({reason}). Check APNS_KEY_ID, APNS_TEAM_ID, APNS_TOPIC and that the key is enabled for {place}."
                ));
            }
            Some(ApnsSendOutcome::Failed { reason, .. }) => {
                refused.push(format!("{place}: {reason}"))
            }
            None => refused.push(format!(
                "{place}: Apple didn't answer in {} s",
                STEP_TIMEOUT.as_secs()
            )),
        }
    }
    let accepted: Vec<&str> = accepted.into_iter().collect();
    if refused.is_empty() {
        PushCheck::ok(
            item,
            format!(
                "{} accept the key, team and topic {topic}.",
                accepted.join(" and ")
            ),
        )
    } else {
        PushCheck::failed(item, refused.join(" · "))
    }
}

fn environment_name(environment: ApnsEnvironment) -> &'static str {
    match environment {
        ApnsEnvironment::Production => "Production",
        ApnsEnvironment::Sandbox => "Sandbox",
    }
}

/// `step`, or `None` when it takes longer than [`STEP_TIMEOUT`].
async fn bounded<T>(step: impl Future<Output = T>) -> Option<T> {
    tokio::time::timeout(STEP_TIMEOUT, step).await.ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn host(name: &str, subscriptions: i64) -> Host {
        Host {
            client: SubscriptionClient::Browser,
            name: name.into(),
            endpoint: format!("https://{name}/x"),
            subscriptions,
        }
    }

    #[test]
    fn bad_device_token_means_apple_took_the_setup() {
        let answers = [
            (
                ApnsEnvironment::Production,
                Some(ApnsSendOutcome::InvalidToken {
                    reason: "BadDeviceToken".into(),
                    status: 400,
                }),
            ),
            (
                ApnsEnvironment::Sandbox,
                Some(ApnsSendOutcome::InvalidToken {
                    reason: "BadDeviceToken".into(),
                    status: 400,
                }),
            ),
        ];
        let verdict = apple_verdict(APNS_ALERTS, "de.example.app", &answers);
        assert_eq!(verdict.state, "ok");
        assert_eq!(
            verdict.detail,
            "Production and Sandbox accept the key, team and topic de.example.app."
        );
    }

    #[test]
    fn a_refused_key_names_the_environment_and_the_reason() {
        let answers = [
            (
                ApnsEnvironment::Production,
                Some(ApnsSendOutcome::InvalidToken {
                    reason: "BadDeviceToken".into(),
                    status: 400,
                }),
            ),
            (
                ApnsEnvironment::Sandbox,
                Some(ApnsSendOutcome::Failed {
                    reason: "BadEnvironmentKeyInToken".into(),
                    status: 403,
                }),
            ),
        ];
        let verdict = apple_verdict(APNS_CALLS, "de.example.app.voip", &answers);
        assert_eq!(verdict.state, "failed");
        assert!(
            verdict
                .detail
                .starts_with("Sandbox: Apple refused the key or topic (BadEnvironmentKeyInToken).")
        );
    }

    #[test]
    fn no_answer_and_transport_errors_fail() {
        let answers = [
            (ApnsEnvironment::Production, None),
            (
                ApnsEnvironment::Sandbox,
                Some(ApnsSendOutcome::Failed {
                    reason: "transport: connection refused".into(),
                    status: 0,
                }),
            ),
        ];
        let verdict = apple_verdict(APNS_ALERTS, "t", &answers);
        assert_eq!(verdict.state, "failed");
        assert_eq!(
            verdict.detail,
            "Production: Apple didn't answer in 6 s · Sandbox: transport: connection refused"
        );
    }

    #[test]
    fn hosts_are_named_with_their_subscription_counts() {
        let fcm = host("fcm.googleapis.com", 212);
        let mozilla = host("updates.push.services.mozilla.com", 1);
        let all_fine = [(&fcm, Ok(())), (&mozilla, Ok(()))];
        let verdict = hosts_verdict(BROWSER_HOSTS, &all_fine);
        assert_eq!(verdict.state, "ok");
        assert_eq!(
            verdict.detail,
            "fcm.googleapis.com (212 subscriptions), updates.push.services.mozilla.com (1 subscription) answered."
        );

        let one_down = [
            (&fcm, Ok(())),
            (&mozilla, Err("no answer in 6 s".to_string())),
        ];
        let verdict = hosts_verdict(BROWSER_HOSTS, &one_down);
        assert_eq!(verdict.state, "failed");
        assert_eq!(
            verdict.detail,
            "updates.push.services.mozilla.com (1 subscription): no answer in 6 s · fcm.googleapis.com (212 subscriptions) answered."
        );
    }
}
