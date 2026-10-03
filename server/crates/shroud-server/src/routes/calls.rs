//! 1:1 call signaling: ring, answer, relay sealed signals between the two devices, end.
//!
//! Human: The server never sees call media, nor what the signals say — `payload` is sealed
//! between the two people (docs/calls.md). It rings devices, remembers which two devices are
//! in a call, and ends calls whose devices went quiet, so nobody stays "busy" after a crash.
//! Switching a call between voice and video is the devices' business (a sealed `media_state`);
//! the server keeps each device's latest one so the other can catch up after a gap.
//! Agent: Contacts-only; protocol 2 only; signals go to the other device in the call
//! (RealtimeHub::publish_to_device); pushes via PushService; `spawn_call_gc` every 10 s.

use axum::{
    Json,
    extract::{Path, Query, State},
    http::StatusCode,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::config::IceServer;
use crate::error::AppError;
use crate::push::PushEvent;
use crate::rate_limit::budgets;
use crate::routes::contacts::{are_contacts, is_blocked_either_way};
use crate::state::AppState;

const MAX_SIGNAL_BYTES: usize = 64 * 1024;
/// A `media_state` is a few flags; the server keeps the latest one per device, so it stays small.
const MAX_MEDIA_STATE_BYTES: usize = 4 * 1024;
/// A call nobody answers stops ringing after this long (`missed`, `timeout`).
pub const RINGING_TIMEOUT_SECS: i64 = 60;
/// A call ends when one of its devices has not been heard from for this long. Devices report
/// in every 10 s (heartbeat or signal), so this is several missed reports.
pub const PARTICIPANT_TIMEOUT_SECS: i64 = 45;
const CALL_GC_INTERVAL_SECS: u64 = 10;
/// Media is negotiated only after the answer, over sealed signals (docs/calls.md).
pub const CALL_PROTOCOL: i16 = 2;
const SIGNAL_TYPES: [&str; 5] = [
    "sdp_offer",
    "sdp_answer",
    "ice_candidate",
    "renegotiate",
    "media_state",
];
const HISTORY_DEFAULT_LIMIT: i64 = 50;
const HISTORY_MAX_LIMIT: i64 = 100;

/// A call row plus whether each account is deleted. `c` is `calls` or a CTE over it.
/// Names are not selected.
const CALL_FIELDS: &str = r#"
    c.id, c.caller_user_id, c.caller_device_id, c.callee_user_id, c.callee_device_id,
    c.modality, c.status, c.ended_reason, c.created_at, c.answered_at, c.ended_at, c.protocol,
    c.caller_media_state, c.callee_media_state,
    COALESCE(cu.deleted_at IS NOT NULL, false) AS caller_deleted,
    COALESCE(pu.deleted_at IS NOT NULL, false) AS callee_deleted
"#;
const CALL_JOINS: &str = r#"
    LEFT JOIN users cu ON cu.id = c.caller_user_id
    LEFT JOIN users pu ON pu.id = c.callee_user_id
"#;
/// Part of every `SET` that ends a call: the kept media states go with it.
const CLEAR_MEDIA_STATES: &str = "caller_media_state = NULL, callee_media_state = NULL";

#[derive(Debug, Deserialize)]
pub struct CreateCallRequest {
    pub peer_user_id: Uuid,
    /// `voice` (default) or `video`.
    pub modality: Option<String>,
    /// Must be [`CALL_PROTOCOL`]; builds that send none placed calls that could not connect.
    pub protocol: Option<i16>,
}

/// `POST /calls/:id/accept` takes `{}`; older builds sent an SDP answer, which is ignored.
#[derive(Debug, Default, Deserialize)]
pub struct AcceptCallRequest {}

#[derive(Debug, Deserialize)]
pub struct SignalRequest {
    /// One of [`SIGNAL_TYPES`]; sealed into the payload's additional data by the clients.
    pub signal_type: String,
    /// Sealed signal (`c1.` + base64); the server does not read it.
    pub payload: String,
}

#[derive(Debug, Deserialize)]
pub struct CallHistoryQuery {
    pub limit: Option<i64>,
    /// Calls placed before this time (the `created_at` of the last one already shown).
    pub before: Option<DateTime<Utc>>,
}

#[derive(Debug, Serialize)]
pub struct CallResponse {
    pub id: Uuid,
    pub caller_user_id: Uuid,
    pub caller_device_id: Uuid,
    /// Kept absent. A name is not on this server.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub caller_username: Option<String>,
    /// The caller's account was deleted. This is not a name.
    pub caller_deleted: bool,
    pub callee_user_id: Uuid,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub callee_device_id: Option<Uuid>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub callee_username: Option<String>,
    /// The callee's account was deleted. This is not a name.
    pub callee_deleted: bool,
    pub modality: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ended_reason: Option<String>,
    pub protocol: i16,
    pub created_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub answered_at: Option<DateTime<Utc>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ended_at: Option<DateTime<Utc>>,
    /// Only for one of the two devices in a live call (`GET /calls/:id`, heartbeat): the latest
    /// `media_state` the other device sent, so a missed camera switch is caught up.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub peer_media_state: Option<PeerMediaState>,
}

/// A sealed `media_state` signal as the other device sent it (see `call.signal`).
#[derive(Debug, Serialize)]
pub struct PeerMediaState {
    pub from_device_id: Uuid,
    pub payload: String,
}

#[derive(Debug, Serialize)]
pub struct CallListResponse {
    pub calls: Vec<CallResponse>,
}

#[derive(Debug, Serialize)]
pub struct IceServersResponse {
    pub ice_servers: Vec<IceServer>,
}

#[derive(Debug, Clone, FromRow)]
struct CallRow {
    id: Uuid,
    caller_user_id: Uuid,
    caller_device_id: Uuid,
    callee_user_id: Uuid,
    callee_device_id: Option<Uuid>,
    modality: String,
    status: String,
    ended_reason: Option<String>,
    created_at: DateTime<Utc>,
    answered_at: Option<DateTime<Utc>>,
    ended_at: Option<DateTime<Utc>>,
    protocol: i16,
    caller_media_state: Option<String>,
    callee_media_state: Option<String>,
    caller_deleted: bool,
    callee_deleted: bool,
}

impl CallRow {
    fn is_live(&self) -> bool {
        self.status == "ringing" || self.status == "active"
    }

    /// The latest media state the other device in the call sent, when `device_id` is one of
    /// the two devices in this live call; `None` for anybody else.
    fn media_state_for(&self, device_id: Uuid) -> Option<PeerMediaState> {
        if self.status != "active" {
            return None;
        }
        let callee_device_id = self.callee_device_id?;
        let (from_device_id, payload) = if device_id == self.caller_device_id {
            (callee_device_id, self.callee_media_state.as_ref()?)
        } else if device_id == callee_device_id {
            (self.caller_device_id, self.caller_media_state.as_ref()?)
        } else {
            return None;
        };
        Some(PeerMediaState {
            from_device_id,
            payload: payload.clone(),
        })
    }
}

/// `GET /calls/ice-servers` — STUN/TURN for WebRTC, with a fresh TURN login for a signed-in
/// caller. The login is random, so it does not name the account.
pub async fn ice_servers(
    State(state): State<AppState>,
    _auth: AuthContext,
) -> Result<Json<IceServersResponse>, AppError> {
    let mut ice_servers = state.ice_servers.clone();
    if let Some(turn) = &state.turn {
        ice_servers.push(turn.credential_for(Utc::now().timestamp().unsigned_abs()));
    }
    Ok(Json(IceServersResponse { ice_servers }))
}

/// `POST /calls` — ring a contact.
pub async fn create_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<CreateCallRequest>,
) -> Result<(StatusCode, Json<CallResponse>), AppError> {
    state
        .rate_limiter
        .check_budget("call_user", &auth.user_id.to_string(), budgets::CALL_USER)
        .await?;

    if body.protocol != Some(CALL_PROTOCOL) {
        // Human: What older builds say when they try; their calls never connected.
        return Err(AppError::validation(
            "This version of Shroud can't place calls. Update the app to call.",
        ));
    }
    if body.peer_user_id == auth.user_id {
        return Err(AppError::validation("Cannot call yourself."));
    }
    let modality = body.modality.as_deref().unwrap_or("voice");
    if modality != "voice" && modality != "video" {
        return Err(AppError::validation("modality must be 'voice' or 'video'."));
    }

    if !are_contacts(&state.pool, auth.user_id, body.peer_user_id).await? {
        return Err(AppError::forbidden("You can only call accepted contacts."));
    }
    if is_blocked_either_way(&state.pool, auth.user_id, body.peer_user_id).await? {
        return Err(AppError::forbidden("Cannot call this user."));
    }

    // A call whose app crashed or lost its network must not leave either person busy.
    end_stale_calls(&state, Some(&[auth.user_id, body.peer_user_id])).await?;

    let call_id = Uuid::new_v4();
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin call failed: {err}")))?;
    // Human: Two people calling each other at the same moment would both pass the busy check;
    // the second waits for the first here and then finds the other busy.
    let mut keys = [
        busy_lock_key(auth.user_id),
        busy_lock_key(body.peer_user_id),
    ];
    keys.sort_unstable();
    for key in keys {
        sqlx::query("SELECT pg_advisory_xact_lock($1)")
            .bind(key)
            .execute(&mut *tx)
            .await
            .map_err(|err| AppError::Internal(format!("call lock failed: {err}")))?;
    }
    // The other person is on a call with someone else. A call they are already on with
    // the caller is replaced below, so a leftover ring cannot block every later attempt.
    if peer_is_on_another_call(&mut tx, body.peer_user_id, auth.user_id).await? {
        return Err(AppError::call_busy());
    }
    let replaced = end_live_calls_of(&mut tx, auth.user_id).await?;
    sqlx::query(
        r#"
        INSERT INTO calls (
            id, caller_user_id, caller_device_id, callee_user_id,
            modality, status, created_at, caller_seen_at, protocol
        )
        VALUES ($1, $2, $3, $4, $5, 'ringing', now(), now(), $6)
        "#,
    )
    .bind(call_id)
    .bind(auth.user_id)
    .bind(auth.device_id)
    .bind(body.peer_user_id)
    .bind(modality)
    .bind(CALL_PROTOCOL)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("insert call failed: {err}")))?;
    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit call failed: {err}")))?;

    for call in &replaced {
        announce_end(&state, call, Some(auth.device_id)).await;
    }

    let call = load_call(&state.pool, call_id)
        .await?
        .ok_or_else(|| AppError::Internal("call missing after insert".into()))?;
    let response = call_to_response(&call);

    let ring_event = serde_json::json!({ "type": "call.ring", "call": &response });
    if let Ok(payload) = serde_json::to_string(&ring_event) {
        state
            .realtime
            .publish_to_users(
                [auth.user_id, body.peer_user_id],
                Some(auth.device_id),
                &payload,
            )
            .await;
        // For the callee's devices that connect while it rings: their user tapped a
        // notification, or PushKit woke the app.
        state
            .realtime
            .remember_ring(
                body.peer_user_id,
                &payload,
                RINGING_TIMEOUT_SECS.unsigned_abs(),
            )
            .await;
    }

    state
        .push
        .dispatch(PushEvent::IncomingCall {
            recipient: body.peer_user_id,
            caller: auth.user_id,
            call_id,
            modality: modality.to_string(),
        })
        .await;

    state
        .metrics
        .calls_created_total
        .fetch_add(1, std::sync::atomic::Ordering::Relaxed);

    tracing::info!(
        call_id = %call_id,
        caller = %auth.user_id,
        callee = %body.peer_user_id,
        modality,
        "calls.create ok"
    );

    Ok((StatusCode::CREATED, Json(response)))
}

/// The ring a device of `user_id` that just connected should get: a call to them that still
/// rings. `None` when there is none, or it was answered, declined or given up meanwhile.
pub async fn ring_to_replay(state: &AppState, user_id: Uuid) -> Option<String> {
    let payload = state.realtime.pending_ring(user_id).await?;
    let call_id = serde_json::from_str::<serde_json::Value>(&payload)
        .ok()?
        .pointer("/call/id")?
        .as_str()?
        .parse::<Uuid>()
        .ok()?;
    let ringing: bool = sqlx::query_scalar(
        r#"
        SELECT EXISTS(
            SELECT 1 FROM calls
            WHERE id = $1 AND callee_user_id = $2 AND status = 'ringing'
              AND created_at > now() - make_interval(secs => $3)
        )
        "#,
    )
    .bind(call_id)
    .bind(user_id)
    .bind(RINGING_TIMEOUT_SECS)
    .fetch_one(&state.pool)
    .await
    .map_err(|err| tracing::warn!(error = %err, %call_id, "ring replay check failed"))
    .ok()?;
    ringing.then_some(payload)
}

/// `GET /calls` — the user's calls, newest first.
pub async fn list_calls(
    State(state): State<AppState>,
    auth: AuthContext,
    Query(query): Query<CallHistoryQuery>,
) -> Result<Json<CallListResponse>, AppError> {
    let limit = query
        .limit
        .unwrap_or(HISTORY_DEFAULT_LIMIT)
        .clamp(1, HISTORY_MAX_LIMIT);
    let rows = sqlx::query_as::<_, CallRow>(&format!(
        r#"
        SELECT {CALL_FIELDS}
        FROM calls c {CALL_JOINS}
        WHERE (c.caller_user_id = $1 OR c.callee_user_id = $1)
          AND ($2::timestamptz IS NULL OR c.created_at < $2)
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT $3
        "#
    ))
    .bind(auth.user_id)
    .bind(query.before)
    .bind(limit)
    .fetch_all(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("list calls failed: {err}")))?;
    Ok(Json(CallListResponse {
        calls: rows.iter().map(call_to_response).collect(),
    }))
}

/// `GET /calls/:id` — for a device in the call, with the other device's latest media state.
pub async fn get_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallResponse>, AppError> {
    let call = load_call(&state.pool, call_id)
        .await?
        .ok_or_else(|| AppError::not_found("Call not found."))?;
    ensure_participant(&call, auth.user_id)?;
    Ok(Json(call_to_device_response(&call, auth.device_id)))
}

/// `POST /calls/:id/accept` — this device answers; the others stop ringing.
pub async fn accept_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(call_id): Path<Uuid>,
    Json(_body): Json<AcceptCallRequest>,
) -> Result<Json<CallResponse>, AppError> {
    let call = load_call(&state.pool, call_id)
        .await?
        .ok_or_else(|| AppError::not_found("Call not found."))?;
    if call.callee_user_id != auth.user_id {
        ensure_participant(&call, auth.user_id)?;
        return Err(AppError::forbidden("Only the callee can accept this call."));
    }

    let updated = sqlx::query_as::<_, CallRow>(&format!(
        r#"
        WITH c AS (
            UPDATE calls
            SET status = 'active',
                callee_device_id = $1,
                answered_at = now(),
                callee_seen_at = now()
            WHERE id = $2 AND status = 'ringing'
              AND created_at > now() - make_interval(secs => $3)
            RETURNING *
        )
        SELECT {CALL_FIELDS} FROM c {CALL_JOINS}
        "#
    ))
    .bind(auth.device_id)
    .bind(call_id)
    .bind(RINGING_TIMEOUT_SECS)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("accept call failed: {err}")))?
    .ok_or_else(|| AppError::validation("The call is no longer ringing."))?;

    let response = call_to_response(&updated);
    let event = serde_json::json!({ "type": "call.accepted", "call": &response });
    publish_call_event(&state, &updated, Some(auth.device_id), &event).await;
    // The other iPhones rang through PushKit and may have no socket to hear `call.accepted`.
    state
        .push
        .dispatch(PushEvent::CallEnded {
            recipient: auth.user_id,
            caller: updated.caller_user_id,
            call_id,
            except_device: Some(auth.device_id),
        })
        .await;

    tracing::info!(call_id = %call_id, callee = %auth.user_id, "calls.accept ok");
    Ok(Json(response))
}

/// `POST /calls/:id/reject`
pub async fn reject_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallResponse>, AppError> {
    end_call_as(
        &state,
        auth.user_id,
        auth.device_id,
        call_id,
        EndAction::Reject,
    )
    .await
}

/// `POST /calls/:id/hangup` — cancel while ringing (caller), decline (callee), or end.
pub async fn hangup_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallResponse>, AppError> {
    end_call_as(
        &state,
        auth.user_id,
        auth.device_id,
        call_id,
        EndAction::Hangup,
    )
    .await
}

/// `POST /calls/:id/signal` — relay a sealed signal to the other device in the call.
pub async fn signal_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(call_id): Path<Uuid>,
    Json(body): Json<SignalRequest>,
) -> Result<StatusCode, AppError> {
    state
        .rate_limiter
        .check_budget(
            "call_signal_user",
            &auth.user_id.to_string(),
            budgets::CALL_SIGNAL_USER,
        )
        .await?;

    let signal_type = body.signal_type.trim();
    if !SIGNAL_TYPES.contains(&signal_type) {
        return Err(AppError::validation(format!(
            "signal_type must be one of {}.",
            SIGNAL_TYPES.join(", ")
        )));
    }
    if body.payload.is_empty() || body.payload.len() > MAX_SIGNAL_BYTES {
        return Err(AppError::validation(format!(
            "signal payload must be 1–{MAX_SIGNAL_BYTES} bytes."
        )));
    }
    let media_state = signal_type == "media_state";
    if media_state && body.payload.len() > MAX_MEDIA_STATE_BYTES {
        return Err(AppError::validation(format!(
            "media_state payload must be at most {MAX_MEDIA_STATE_BYTES} bytes."
        )));
    }

    let call = load_call(&state.pool, call_id)
        .await?
        .ok_or_else(|| AppError::not_found("Call not found."))?;
    ensure_participant(&call, auth.user_id)?;
    if !call.is_live() {
        return Err(AppError::conflict("CALL_ENDED", "The call has ended."));
    }
    let Some(callee_device_id) = call.callee_device_id else {
        return Err(AppError::conflict(
            "CALL_NOT_ANSWERED",
            "The call has not been answered yet.",
        ));
    };

    // The two devices in the call talk to each other; nothing else of either person does.
    let (to_user, to_device) = if auth.user_id == call.caller_user_id {
        if auth.device_id != call.caller_device_id {
            return Err(AppError::forbidden(
                "Only the device that placed the call can signal.",
            ));
        }
        (call.callee_user_id, callee_device_id)
    } else {
        if auth.device_id != callee_device_id {
            return Err(AppError::forbidden(
                "Only the device that answered the call can signal.",
            ));
        }
        (call.caller_user_id, call.caller_device_id)
    };

    // Human: A camera switched on or off must reach the other device even if its socket was
    // down just then: it reads this back on its next heartbeat, or when it reconnects.
    touch_participant(
        &state.pool,
        call_id,
        auth.device_id,
        media_state.then_some(body.payload.as_str()),
    )
    .await?;

    let event = serde_json::json!({
        "type": "call.signal",
        "call_id": call_id,
        "from_user_id": auth.user_id,
        "from_device_id": auth.device_id,
        "signal_type": signal_type,
        "payload": body.payload,
    });
    if let Ok(payload) = serde_json::to_string(&event) {
        state
            .realtime
            .publish_to_device(to_user, to_device, &payload)
            .await;
    }
    Ok(StatusCode::NO_CONTENT)
}

/// `POST /calls/:id/heartbeat` — "this device is still in the call". Answers with the call,
/// so a device that missed `call.ended` learns it here, and with the other device's latest
/// media state, so it also learns a camera switch it missed.
pub async fn heartbeat_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallResponse>, AppError> {
    state
        .rate_limiter
        .check_budget(
            "call_signal_user",
            &auth.user_id.to_string(),
            budgets::CALL_SIGNAL_USER,
        )
        .await?;
    let touched = sqlx::query_as::<_, CallRow>(&format!(
        r#"
        WITH c AS (
            UPDATE calls
            SET caller_seen_at = CASE
                    WHEN caller_user_id = $2 AND caller_device_id = $3 THEN now()
                    ELSE caller_seen_at
                END,
                callee_seen_at = CASE
                    WHEN callee_user_id = $2 AND callee_device_id = $3 THEN now()
                    ELSE callee_seen_at
                END
            WHERE id = $1 AND status IN ('ringing', 'active')
              AND (caller_user_id = $2 OR callee_user_id = $2)
            RETURNING *
        )
        SELECT {CALL_FIELDS} FROM c {CALL_JOINS}
        "#
    ))
    .bind(call_id)
    .bind(auth.user_id)
    .bind(auth.device_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("call heartbeat failed: {err}")))?;
    let call = match touched {
        Some(call) => call,
        None => {
            let call = load_call(&state.pool, call_id)
                .await?
                .ok_or_else(|| AppError::not_found("Call not found."))?;
            ensure_participant(&call, auth.user_id)?;
            call
        }
    };
    Ok(Json(call_to_device_response(&call, auth.device_id)))
}

#[derive(Debug, Clone, Copy)]
enum EndAction {
    Reject,
    Hangup,
}

async fn end_call_as(
    state: &AppState,
    user_id: Uuid,
    device_id: Uuid,
    call_id: Uuid,
    action: EndAction,
) -> Result<Json<CallResponse>, AppError> {
    let call = load_call(&state.pool, call_id)
        .await?
        .ok_or_else(|| AppError::not_found("Call not found."))?;
    ensure_participant(&call, user_id)?;

    if !call.is_live() {
        return Ok(Json(call_to_response(&call)));
    }

    let (new_status, reason) = match (action, call.status.as_str(), call.caller_user_id == user_id)
    {
        (EndAction::Reject, "ringing", false) => ("rejected", "rejected"),
        (EndAction::Reject, _, _) => {
            return Err(AppError::forbidden(
                "Only the callee can reject a ringing call.",
            ));
        }
        (EndAction::Hangup, "ringing", true) => ("cancelled", "cancelled"),
        (EndAction::Hangup, "ringing", false) => ("missed", "declined"),
        (EndAction::Hangup, "active", _) => ("ended", "hangup"),
        (EndAction::Hangup, _, _) => {
            return Err(AppError::validation(
                "Call cannot be hung up in this state.",
            ));
        }
    };

    let updated = sqlx::query_as::<_, CallRow>(&format!(
        r#"
        WITH c AS (
            UPDATE calls
            SET status = $1,
                ended_reason = $2,
                ended_at = now(),
                {CLEAR_MEDIA_STATES}
            WHERE id = $3 AND status IN ('ringing', 'active')
            RETURNING *
        )
        SELECT {CALL_FIELDS} FROM c {CALL_JOINS}
        "#
    ))
    .bind(new_status)
    .bind(reason)
    .bind(call_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("end call failed: {err}")))?;
    // Ended by the other side (or the sweep) since it was loaded: answer with how it ended.
    let Some(updated) = updated else {
        let call = load_call(&state.pool, call_id)
            .await?
            .ok_or_else(|| AppError::not_found("Call not found."))?;
        return Ok(Json(call_to_response(&call)));
    };

    announce_end(state, &updated, Some(device_id)).await;

    tracing::info!(
        call_id = %call_id,
        user_id = %user_id,
        status = new_status,
        "calls.end ok"
    );
    Ok(Json(call_to_response(&updated)))
}

/// Tells both people's devices a call ended (all but `except_device`, which ended it), and
/// the callee's closed ones that they missed it when nobody answered.
async fn announce_end(state: &AppState, call: &CallRow, except_device: Option<Uuid>) {
    let response = call_to_response(call);
    let event = serde_json::json!({ "type": "call.ended", "call": &response });
    publish_call_event(state, call, except_device, &event).await;
    // A ring PushKit already showed has to be ended by a push: the phone closed its socket.
    // An answered call's other phones were told when it was accepted; telling them again
    // would flash a new call as this one hangs up.
    if call.answered_at.is_none() {
        state
            .push
            .dispatch(PushEvent::CallEnded {
                recipient: call.callee_user_id,
                caller: call.caller_user_id,
                call_id: call.id,
                except_device,
            })
            .await;
    }
    if call.answered_at.is_none()
        && (call.status == "missed" || call.status == "cancelled")
        && call.ended_reason.as_deref() != Some("declined")
    {
        state
            .push
            .dispatch(PushEvent::MissedCall {
                recipient: call.callee_user_id,
                caller: call.caller_user_id,
                call_id: call.id,
            })
            .await;
    }
}

async fn publish_call_event(
    state: &AppState,
    call: &CallRow,
    except_device: Option<Uuid>,
    event: &serde_json::Value,
) {
    if let Ok(payload) = serde_json::to_string(event) {
        state
            .realtime
            .publish_to_users(
                [call.caller_user_id, call.callee_user_id],
                except_device,
                &payload,
            )
            .await;
    }
}

fn ensure_participant(call: &CallRow, user_id: Uuid) -> Result<(), AppError> {
    if call.caller_user_id == user_id || call.callee_user_id == user_id {
        Ok(())
    } else {
        Err(AppError::not_found("Call not found."))
    }
}

/// Records that `device_id` was heard from in a live call it is part of, and keeps `media_state`
/// (a sealed `media_state` signal) as that device's latest when there is one.
async fn touch_participant(
    pool: &sqlx::PgPool,
    call_id: Uuid,
    device_id: Uuid,
    media_state: Option<&str>,
) -> Result<(), AppError> {
    sqlx::query(
        r#"
        UPDATE calls
        SET caller_seen_at = CASE WHEN caller_device_id = $2 THEN now() ELSE caller_seen_at END,
            callee_seen_at = CASE WHEN callee_device_id = $2 THEN now() ELSE callee_seen_at END,
            caller_media_state = CASE
                WHEN $3::text IS NOT NULL AND caller_device_id = $2 THEN $3
                ELSE caller_media_state
            END,
            callee_media_state = CASE
                WHEN $3::text IS NOT NULL AND callee_device_id = $2 THEN $3
                ELSE callee_media_state
            END
        WHERE id = $1 AND status IN ('ringing', 'active')
        "#,
    )
    .bind(call_id)
    .bind(device_id)
    .bind(media_state)
    .execute(pool)
    .await
    .map_err(|err| AppError::Internal(format!("touch call failed: {err}")))?;
    Ok(())
}

/// Advisory-lock key for "is this person free to call": the user id's first 8 bytes.
fn busy_lock_key(user_id: Uuid) -> i64 {
    let bytes = user_id.as_bytes();
    i64::from_be_bytes([
        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
    ])
}

/// True when `peer` is ringing or talking with anybody except `caller`.
async fn peer_is_on_another_call(
    conn: &mut sqlx::PgConnection,
    peer: Uuid,
    caller: Uuid,
) -> Result<bool, AppError> {
    sqlx::query_scalar(
        r#"
        SELECT EXISTS(
            SELECT 1 FROM calls
            WHERE status IN ('ringing', 'active')
              AND (caller_user_id = $1 OR callee_user_id = $1)
              AND caller_user_id <> $2
              AND callee_user_id <> $2
        )
        "#,
    )
    .bind(peer)
    .bind(caller)
    .fetch_one(&mut *conn)
    .await
    .map_err(|err| AppError::Internal(format!("peer call check failed: {err}")))
}

/// Ends every live call `user_id` is in, so placing a new call cannot be stuck behind one
/// that already finished on the devices. Returns the rows as they stand after the update.
async fn end_live_calls_of(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
) -> Result<Vec<CallRow>, AppError> {
    sqlx::query_as::<_, CallRow>(&format!(
        r#"
        WITH c AS (
            UPDATE calls
            SET status = CASE
                    WHEN status = 'ringing' AND caller_user_id = $1 THEN 'cancelled'
                    WHEN status = 'ringing' THEN 'missed'
                    ELSE 'ended'
                END,
                ended_reason = CASE
                    WHEN status = 'ringing' AND caller_user_id = $1 THEN 'cancelled'
                    WHEN status = 'ringing' THEN 'declined'
                    ELSE 'hangup'
                END,
                ended_at = now(),
                {CLEAR_MEDIA_STATES}
            WHERE status IN ('ringing', 'active')
              AND (caller_user_id = $1 OR callee_user_id = $1)
            RETURNING *
        )
        SELECT {CALL_FIELDS} FROM c {CALL_JOINS}
        "#
    ))
    .bind(user_id)
    .fetch_all(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("end live calls failed: {err}")))
}

async fn load_call(pool: &sqlx::PgPool, call_id: Uuid) -> Result<Option<CallRow>, AppError> {
    sqlx::query_as::<_, CallRow>(&format!(
        "SELECT {CALL_FIELDS} FROM calls c {CALL_JOINS} WHERE c.id = $1"
    ))
    .bind(call_id)
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("load call failed: {err}")))
}

fn call_to_response(row: &CallRow) -> CallResponse {
    CallResponse {
        id: row.id,
        caller_user_id: row.caller_user_id,
        caller_device_id: row.caller_device_id,
        caller_username: None,
        caller_deleted: row.caller_deleted,
        callee_user_id: row.callee_user_id,
        callee_device_id: row.callee_device_id,
        callee_username: None,
        callee_deleted: row.callee_deleted,
        modality: row.modality.clone(),
        status: row.status.clone(),
        ended_reason: row.ended_reason.clone(),
        protocol: row.protocol,
        created_at: row.created_at,
        answered_at: row.answered_at,
        ended_at: row.ended_at,
        peer_media_state: None,
    }
}

/// The call as `device_id` reads it: with the other device's latest media state when it is
/// one of the two devices in the live call.
fn call_to_device_response(row: &CallRow, device_id: Uuid) -> CallResponse {
    CallResponse {
        peer_media_state: row.media_state_for(device_id),
        ..call_to_response(row)
    }
}

/// Deletes every call an account took part in, for `DELETE /auth/account`.
///
/// Human: A ringing or active call must end with the account, or the other person stays busy
/// and their app keeps waiting on it. It ends the way the account's own hangup would (see
/// `end_call_as`); the rows then go, as the old `ON DELETE CASCADE` removed them.
/// Agent: DELETE FROM calls WHERE caller or callee; RETURNS (other participant, `call.ended`
/// event) for calls that were still ringing or active, to publish after commit.
pub(crate) async fn delete_calls_of_account(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
    at: DateTime<Utc>,
) -> Result<Vec<(Uuid, serde_json::Value)>, AppError> {
    let rows = sqlx::query_as::<_, CallRow>(&format!(
        r#"
        WITH c AS (
            DELETE FROM calls
            WHERE caller_user_id = $1 OR callee_user_id = $1
            RETURNING *
        )
        SELECT {CALL_FIELDS} FROM c {CALL_JOINS}
        "#
    ))
    .bind(user_id)
    .fetch_all(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("delete account calls failed: {err}")))?;

    let mut ended = Vec::new();
    for mut call in rows {
        let by_caller = call.caller_user_id == user_id;
        let (status, reason) = match (call.status.as_str(), by_caller) {
            ("ringing", true) => ("cancelled", "cancelled"),
            ("ringing", false) => ("missed", "declined"),
            ("active", _) => ("ended", "hangup"),
            _ => continue,
        };
        call.status = status.into();
        call.ended_reason = Some(reason.into());
        call.ended_at = Some(at);
        let peer = if by_caller {
            call.callee_user_id
        } else {
            call.caller_user_id
        };
        ended.push((
            peer,
            serde_json::json!({ "type": "call.ended", "call": call_to_response(&call) }),
        ));
    }
    Ok(ended)
}

/// Ends calls nobody answered in time, and calls whose devices went quiet; tells both
/// people's devices. `only_users` limits it to calls of those people.
///
/// Human: Before this a call stayed `active` forever when an app crashed mid-call, and both
/// people were "busy" from then on.
/// Agent: three UPDATE … RETURNING (one replica wins each row); publishes `call.ended`, and a
/// `MissedCall` push for unanswered ones.
pub async fn end_stale_calls(
    state: &AppState,
    only_users: Option<&[Uuid]>,
) -> Result<usize, AppError> {
    let only_users: Option<Vec<Uuid>> = only_users.map(<[Uuid]>::to_vec);
    let sweeps: [(&str, &str, &str, i64); 3] = [
        (
            "missed",
            "timeout",
            "status = 'ringing' AND created_at < now() - make_interval(secs => $1)",
            RINGING_TIMEOUT_SECS,
        ),
        (
            "cancelled",
            "connection_lost",
            "status = 'ringing' \
             AND COALESCE(caller_seen_at, created_at) < now() - make_interval(secs => $1)",
            PARTICIPANT_TIMEOUT_SECS,
        ),
        (
            "ended",
            "connection_lost",
            "status = 'active' AND ( \
                COALESCE(caller_seen_at, answered_at, created_at) \
                    < now() - make_interval(secs => $1) \
                OR COALESCE(callee_seen_at, answered_at, created_at) \
                    < now() - make_interval(secs => $1))",
            PARTICIPANT_TIMEOUT_SECS,
        ),
    ];

    let mut ended = 0;
    for (status, reason, condition, secs) in sweeps {
        let rows = sqlx::query_as::<_, CallRow>(&format!(
            r#"
            WITH c AS (
                UPDATE calls
                SET status = $3, ended_reason = $4, ended_at = now(), {CLEAR_MEDIA_STATES}
                WHERE {condition}
                  AND ($2::uuid[] IS NULL
                       OR caller_user_id = ANY($2) OR callee_user_id = ANY($2))
                RETURNING *
            )
            SELECT {CALL_FIELDS} FROM c {CALL_JOINS}
            "#
        ))
        .bind(secs)
        .bind(only_users.as_deref())
        .bind(status)
        .bind(reason)
        .fetch_all(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("end stale calls failed: {err}")))?;
        for call in &rows {
            tracing::info!(call_id = %call.id, status, reason, "calls.stale ended");
            announce_end(state, call, None).await;
        }
        ended += rows.len();
    }
    Ok(ended)
}

/// Background loop: end stale calls (see [`end_stale_calls`]).
pub fn spawn_call_gc(state: AppState) {
    tokio::spawn(async move {
        let mut interval =
            tokio::time::interval(std::time::Duration::from_secs(CALL_GC_INTERVAL_SECS));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        interval.tick().await;
        loop {
            interval.tick().await;
            match end_stale_calls(&state, None).await {
                Ok(0) => tracing::debug!("calls.gc: nothing to end"),
                Ok(n) => tracing::info!(ended = n, "calls.gc ok"),
                Err(err) => tracing::warn!(error = %err, "calls.gc failed"),
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn active_call(caller_device: Uuid, callee_device: Uuid) -> CallRow {
        CallRow {
            id: Uuid::new_v4(),
            caller_user_id: Uuid::new_v4(),
            caller_device_id: caller_device,
            callee_user_id: Uuid::new_v4(),
            callee_device_id: Some(callee_device),
            modality: "voice".into(),
            status: "active".into(),
            ended_reason: None,
            created_at: Utc::now(),
            answered_at: Some(Utc::now()),
            ended_at: None,
            protocol: CALL_PROTOCOL,
            caller_media_state: Some("c1.caller".into()),
            callee_media_state: Some("c1.callee".into()),
            caller_deleted: false,
            callee_deleted: false,
        }
    }

    #[test]
    fn each_device_in_a_call_reads_only_the_other_ones_media_state() {
        let (caller, callee) = (Uuid::new_v4(), Uuid::new_v4());
        let call = active_call(caller, callee);

        let seen_by_caller = call
            .media_state_for(caller)
            .expect("caller reads the callee's");
        assert_eq!(seen_by_caller.from_device_id, callee);
        assert_eq!(seen_by_caller.payload, "c1.callee");
        let seen_by_callee = call
            .media_state_for(callee)
            .expect("callee reads the caller's");
        assert_eq!(seen_by_callee.from_device_id, caller);
        assert_eq!(seen_by_callee.payload, "c1.caller");
        // Another device of either person, or anybody else, reads nothing.
        assert!(call.media_state_for(Uuid::new_v4()).is_none());

        // Nothing sent yet by the other side: nothing to read.
        let quiet = CallRow {
            callee_media_state: None,
            ..active_call(caller, callee)
        };
        assert!(quiet.media_state_for(caller).is_none());
        assert!(quiet.media_state_for(callee).is_some());

        // Only while the call runs.
        for status in ["ringing", "ended", "missed"] {
            let over = CallRow {
                status: status.into(),
                ..active_call(caller, callee)
            };
            assert!(over.media_state_for(caller).is_none(), "{status}");
        }
    }

    #[test]
    fn busy_lock_keys_differ_per_user_and_are_stable() {
        let a = Uuid::parse_str("0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b").unwrap();
        let b = Uuid::parse_str("0290a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b").unwrap();
        assert_eq!(busy_lock_key(a), busy_lock_key(a));
        assert_ne!(busy_lock_key(a), busy_lock_key(b));
    }
}
