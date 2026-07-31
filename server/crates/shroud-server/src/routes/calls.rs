//! 1:1 call signaling (ring / accept / reject / hangup / WebRTC signal relay).
//!
//! Human: Server never sees call media or E2E call keys — only opaque SDP/ICE blobs
//! relayed between devices and minimal call metadata.
//! Agent: Contacts-only; no media; fan-out via RealtimeHub + optional APNs data push.

use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::config::IceServer;
use crate::error::AppError;
use crate::rate_limit::budgets;
use crate::routes::contacts::{are_contacts, is_blocked_either_way};
use crate::state::AppState;

const MAX_SIGNAL_BYTES: usize = 64 * 1024;
/// Unanswered ringing calls become `missed` after this many seconds.
pub const RINGING_TIMEOUT_SECS: i64 = 90;
const RINGING_GC_INTERVAL_SECS: u64 = 30;

#[derive(Debug, Deserialize)]
pub struct CreateCallRequest {
    pub peer_user_id: Uuid,
    /// `voice` (default) or `video`.
    pub modality: Option<String>,
    /// Optional initial SDP offer (opaque string; not validated).
    pub sdp_offer: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct AcceptCallRequest {
    pub sdp_answer: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct SignalRequest {
    /// `sdp_offer` | `sdp_answer` | `ice_candidate` | `renegotiate`.
    pub signal_type: String,
    /// Opaque client payload (SDP text or ICE JSON string).
    pub payload: String,
}

#[derive(Debug, Serialize)]
pub struct CallResponse {
    pub id: Uuid,
    pub caller_user_id: Uuid,
    pub caller_device_id: Uuid,
    pub callee_user_id: Uuid,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub callee_device_id: Option<Uuid>,
    pub modality: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ended_reason: Option<String>,
    pub created_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub answered_at: Option<DateTime<Utc>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ended_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Serialize)]
pub struct IceServersResponse {
    pub ice_servers: Vec<IceServer>,
}

#[derive(Debug, FromRow)]
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
}

/// `GET /calls/ice-servers` — STUN/TURN config for WebRTC (from env).
pub async fn ice_servers(
    State(state): State<AppState>,
    _auth: AuthContext,
) -> Result<Json<IceServersResponse>, AppError> {
    Ok(Json(IceServersResponse {
        ice_servers: state.ice_servers.clone(),
    }))
}

/// `POST /calls` — place a 1:1 call (contacts only).
pub async fn create_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<CreateCallRequest>,
) -> Result<(StatusCode, Json<CallResponse>), AppError> {
    state
        .rate_limiter
        .check_budget("call_user", &auth.user_id.to_string(), budgets::CALL_USER)
        .await?;

    if body.peer_user_id == auth.user_id {
        return Err(AppError::validation("Cannot call yourself."));
    }

    let modality = body.modality.as_deref().unwrap_or("voice");
    if modality != "voice" && modality != "video" {
        return Err(AppError::validation("modality must be 'voice' or 'video'."));
    }
    if let Some(ref offer) = body.sdp_offer {
        validate_signal_payload(offer)?;
    }

    if !are_contacts(&state.pool, auth.user_id, body.peer_user_id).await? {
        return Err(AppError::forbidden("You can only call accepted contacts."));
    }
    if is_blocked_either_way(&state.pool, auth.user_id, body.peer_user_id).await? {
        return Err(AppError::forbidden("Cannot call this user."));
    }

    if user_in_active_call(&state.pool, auth.user_id).await? {
        return Err(AppError::conflict(
            "CALL_BUSY",
            "You already have an active or ringing call.",
        ));
    }
    if user_in_active_call(&state.pool, body.peer_user_id).await? {
        return Err(AppError::call_busy());
    }

    let call_id = Uuid::new_v4();
    let now = Utc::now();
    sqlx::query(
        r#"
        INSERT INTO calls (
            id, caller_user_id, caller_device_id, callee_user_id,
            modality, status, created_at
        )
        VALUES ($1, $2, $3, $4, $5, 'ringing', $6)
        "#,
    )
    .bind(call_id)
    .bind(auth.user_id)
    .bind(auth.device_id)
    .bind(body.peer_user_id)
    .bind(modality)
    .bind(now)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("insert call failed: {err}")))?;

    let call = load_call(&state.pool, call_id)
        .await?
        .ok_or_else(|| AppError::Internal("call missing after insert".into()))?;
    let response = call_to_response(&call);

    // Notify callee devices (and caller's other devices).
    let mut ring_event = serde_json::json!({
        "type": "call.ring",
        "call": &response,
    });
    if let Some(offer) = body.sdp_offer.as_ref() {
        ring_event["sdp_offer"] = serde_json::Value::String(offer.clone());
    }
    if let Ok(payload) = serde_json::to_string(&ring_event) {
        state
            .realtime
            .publish_to_users(
                [auth.user_id, body.peer_user_id],
                Some(auth.device_id),
                &payload,
            )
            .await;
    }

    state
        .push
        .notify_incoming_call_if_offline(body.peer_user_id, call_id, auth.user_id, modality)
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

/// `GET /calls/:id`
pub async fn get_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallResponse>, AppError> {
    let call = load_call(&state.pool, call_id)
        .await?
        .ok_or_else(|| AppError::not_found("Call not found."))?;
    ensure_participant(&call, auth.user_id)?;
    Ok(Json(call_to_response(&call)))
}

/// `POST /calls/:id/accept`
pub async fn accept_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(call_id): Path<Uuid>,
    Json(body): Json<AcceptCallRequest>,
) -> Result<Json<CallResponse>, AppError> {
    if let Some(ref answer) = body.sdp_answer {
        validate_signal_payload(answer)?;
    }

    let call = load_call(&state.pool, call_id)
        .await?
        .ok_or_else(|| AppError::not_found("Call not found."))?;

    if call.callee_user_id != auth.user_id {
        return Err(AppError::forbidden("Only the callee can accept this call."));
    }
    if call.status != "ringing" {
        return Err(AppError::validation(format!(
            "Call is not ringing (status={}).",
            call.status
        )));
    }

    let now = Utc::now();
    let updated = sqlx::query_as::<_, CallRow>(
        r#"
        UPDATE calls
        SET status = 'active',
            callee_device_id = $1,
            answered_at = $2
        WHERE id = $3 AND status = 'ringing'
        RETURNING id, caller_user_id, caller_device_id, callee_user_id, callee_device_id,
                  modality, status, ended_reason, created_at, answered_at, ended_at
        "#,
    )
    .bind(auth.device_id)
    .bind(now)
    .bind(call_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("accept call failed: {err}")))?
    .ok_or_else(|| AppError::validation("Call is no longer ringing."))?;

    let response = call_to_response(&updated);
    let mut event = serde_json::json!({
        "type": "call.accepted",
        "call": &response,
    });
    if let Some(answer) = body.sdp_answer.as_ref() {
        event["sdp_answer"] = serde_json::Value::String(answer.clone());
    }
    publish_call_event(&state, &updated, Some(auth.device_id), &event).await;

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

/// `POST /calls/:id/hangup` — cancel while ringing (caller) or end active (either).
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

/// `POST /calls/:id/signal` — relay opaque SDP/ICE to the peer (live calls).
pub async fn signal_call(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(call_id): Path<Uuid>,
    Json(body): Json<SignalRequest>,
) -> Result<StatusCode, AppError> {
    state
        .rate_limiter
        .check_budget("call_user", &auth.user_id.to_string(), budgets::CALL_USER)
        .await?;

    let signal_type = body.signal_type.trim();
    if !matches!(
        signal_type,
        "sdp_offer" | "sdp_answer" | "ice_candidate" | "renegotiate"
    ) {
        return Err(AppError::validation(
            "signal_type must be sdp_offer, sdp_answer, ice_candidate, or renegotiate.",
        ));
    }
    validate_signal_payload(&body.payload)?;

    let call = load_call(&state.pool, call_id)
        .await?
        .ok_or_else(|| AppError::not_found("Call not found."))?;
    ensure_participant(&call, auth.user_id)?;

    if call.status != "ringing" && call.status != "active" {
        return Err(AppError::validation("Call is not live; cannot signal."));
    }

    let peer_user_id = if call.caller_user_id == auth.user_id {
        call.callee_user_id
    } else {
        call.caller_user_id
    };

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
            .publish_to_users([peer_user_id], None, &payload)
            .await;
    }

    Ok(StatusCode::NO_CONTENT)
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

    if matches!(
        call.status.as_str(),
        "ended" | "rejected" | "busy" | "missed" | "cancelled"
    ) {
        return Ok(Json(call_to_response(&call)));
    }

    let (new_status, reason) = match (action, call.status.as_str(), call.caller_user_id == user_id)
    {
        (EndAction::Reject, "ringing", false) => ("rejected", Some("rejected")),
        (EndAction::Reject, _, _) => {
            return Err(AppError::forbidden(
                "Only the callee can reject a ringing call.",
            ));
        }
        (EndAction::Hangup, "ringing", true) => ("cancelled", Some("cancelled")),
        (EndAction::Hangup, "ringing", false) => ("missed", Some("declined")),
        (EndAction::Hangup, "active", _) => ("ended", Some("hangup")),
        (EndAction::Hangup, _, _) => {
            return Err(AppError::validation(
                "Call cannot be hung up in this state.",
            ));
        }
    };

    let now = Utc::now();
    let updated = sqlx::query_as::<_, CallRow>(
        r#"
        UPDATE calls
        SET status = $1,
            ended_reason = $2,
            ended_at = $3
        WHERE id = $4 AND status IN ('ringing', 'active')
        RETURNING id, caller_user_id, caller_device_id, callee_user_id, callee_device_id,
                  modality, status, ended_reason, created_at, answered_at, ended_at
        "#,
    )
    .bind(new_status)
    .bind(reason)
    .bind(now)
    .bind(call_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("end call failed: {err}")))?
    .ok_or_else(|| AppError::validation("Call already ended."))?;

    let response = call_to_response(&updated);
    let event = serde_json::json!({
        "type": "call.ended",
        "call": &response,
    });
    publish_call_event(state, &updated, Some(device_id), &event).await;

    tracing::info!(
        call_id = %call_id,
        user_id = %user_id,
        status = new_status,
        "calls.end ok"
    );
    Ok(Json(response))
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

fn validate_signal_payload(payload: &str) -> Result<(), AppError> {
    if payload.is_empty() || payload.len() > MAX_SIGNAL_BYTES {
        return Err(AppError::validation(format!(
            "signal payload must be 1–{MAX_SIGNAL_BYTES} bytes."
        )));
    }
    Ok(())
}

async fn user_in_active_call(pool: &sqlx::PgPool, user_id: Uuid) -> Result<bool, AppError> {
    sqlx::query_scalar(
        r#"
        SELECT EXISTS(
            SELECT 1 FROM calls
            WHERE status IN ('ringing', 'active')
              AND (caller_user_id = $1 OR callee_user_id = $1)
        )
        "#,
    )
    .bind(user_id)
    .fetch_one(pool)
    .await
    .map_err(|err| AppError::Internal(format!("active call check failed: {err}")))
}

async fn load_call(pool: &sqlx::PgPool, call_id: Uuid) -> Result<Option<CallRow>, AppError> {
    sqlx::query_as::<_, CallRow>(
        r#"
        SELECT id, caller_user_id, caller_device_id, callee_user_id, callee_device_id,
               modality, status, ended_reason, created_at, answered_at, ended_at
        FROM calls
        WHERE id = $1
        "#,
    )
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
        callee_user_id: row.callee_user_id,
        callee_device_id: row.callee_device_id,
        modality: row.modality.clone(),
        status: row.status.clone(),
        ended_reason: row.ended_reason.clone(),
        created_at: row.created_at,
        answered_at: row.answered_at,
        ended_at: row.ended_at,
    }
}

/// Mark unanswered `ringing` calls as `missed` after [`RINGING_TIMEOUT_SECS`].
///
/// Human: Prevents stuck busy state when the callee never answers and clients disconnect.
/// Agent: UPDATE calls SET status=missed WHERE ringing AND created_at older than timeout.
pub async fn expire_stale_ringing_calls(pool: &sqlx::PgPool) -> Result<u64, AppError> {
    let result = sqlx::query(
        r#"
        UPDATE calls
        SET status = 'missed',
            ended_at = now(),
            ended_reason = 'timeout'
        WHERE status = 'ringing'
          AND created_at < now() - make_interval(secs => $1)
        "#,
    )
    .bind(RINGING_TIMEOUT_SECS)
    .execute(pool)
    .await
    .map_err(|err| AppError::Internal(format!("expire ringing calls failed: {err}")))?;

    Ok(result.rows_affected())
}

/// Background loop: expire stale ringing calls.
pub fn spawn_ringing_call_gc(pool: sqlx::PgPool) {
    tokio::spawn(async move {
        let mut interval =
            tokio::time::interval(std::time::Duration::from_secs(RINGING_GC_INTERVAL_SECS));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        interval.tick().await;
        loop {
            interval.tick().await;
            match expire_stale_ringing_calls(&pool).await {
                Ok(0) => {
                    tracing::debug!("calls.ringing_gc: nothing to expire");
                }
                Ok(n) => {
                    tracing::info!(expired = n, "calls.ringing_gc ok");
                }
                Err(err) => {
                    tracing::warn!(error = %err, "calls.ringing_gc failed");
                }
            }
        }
    });
}
