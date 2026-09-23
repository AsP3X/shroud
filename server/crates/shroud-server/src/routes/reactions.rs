//! Message reactions: one sealed record per (message, user).
//!
//! Human: The server stores and relays an opaque blob per reaction; the emoji lives inside it.
//! Setting, replacing and removing all take a new `seq` from one global sequence, and a removal
//! keeps its row (ciphertext NULL) so an offline device can catch up with
//! `GET /conversations/{peer}/reactions?after_seq=`.
//! Agent: WRITES message_reactions (migration 020); READS messages, conversations,
//! conversation_clears; PUBLISHES WS `message.reaction` to both participants except the acting
//! device. No push and no `last_message_at` bump, like annotations.

use std::collections::HashMap;

use axum::{
    Json,
    extract::{Path, Query, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::rate_limit::budgets;
use crate::routes::contacts::{are_contacts, is_blocked_either_way};
use crate::routes::conversations::clear_watermark;
use crate::routes::messages::find_conversation;
use crate::state::AppState;

/// A sealed reaction is one emoji plus the target id inside a two-box envelope (~500 bytes);
/// the cap leaves room for envelope changes without letting reactions carry payloads.
pub const MAX_REACTION_CIPHERTEXT_BYTES: usize = 4 * 1024;
const DEFAULT_CHANGES_LIMIT: i64 = 200;
const MAX_CHANGES_LIMIT: i64 = 500;

#[derive(Debug, Deserialize)]
pub struct PutReactionRequest {
    pub ciphertext: String,
}

/// One user's reaction on one message, as embedded in history pages and returned by the
/// catch-up endpoint. `ciphertext` is null for a removed reaction (catch-up only).
#[derive(Debug, Clone, Serialize)]
pub struct ReactionEntry {
    pub message_id: Uuid,
    pub user_id: Uuid,
    pub ciphertext: Option<String>,
    pub seq: i64,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct ReactionChangesQuery {
    pub after_seq: Option<i64>,
    pub limit: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct ReactionChangesResponse {
    pub reactions: Vec<ReactionEntry>,
    /// Pass back as `after_seq`; unchanged when nothing new happened.
    pub next_seq: i64,
    pub has_more: bool,
}

#[derive(Debug, FromRow)]
struct ReactionRow {
    message_id: Uuid,
    user_id: Uuid,
    ciphertext: Option<Vec<u8>>,
    seq: i64,
    updated_at: DateTime<Utc>,
}

impl From<ReactionRow> for ReactionEntry {
    fn from(row: ReactionRow) -> Self {
        Self {
            message_id: row.message_id,
            user_id: row.user_id,
            ciphertext: row.ciphertext.map(|bytes| BASE64.encode(bytes)),
            seq: row.seq,
            updated_at: row.updated_at,
        }
    }
}

#[derive(Debug, FromRow)]
struct ReactionTarget {
    conversation_id: Uuid,
    content_type: String,
    deleted_for_everyone_at: Option<DateTime<Utc>>,
    user_a_id: Uuid,
    user_b_id: Uuid,
}

impl ReactionTarget {
    fn peer_of(&self, user_id: Uuid) -> Uuid {
        if self.user_a_id == user_id {
            self.user_b_id
        } else {
            self.user_a_id
        }
    }
}

/// `PUT /messages/:id/reaction` — set or replace the caller's reaction.
pub async fn put_reaction(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(message_id): Path<Uuid>,
    Json(body): Json<PutReactionRequest>,
) -> Result<Json<ReactionEntry>, AppError> {
    check_budget(&state, &auth).await?;

    let ciphertext = BASE64
        .decode(body.ciphertext.trim().as_bytes())
        .map_err(|_| AppError::validation("ciphertext must be valid standard Base64."))?;
    if ciphertext.is_empty() || ciphertext.len() > MAX_REACTION_CIPHERTEXT_BYTES {
        return Err(AppError::validation(format!(
            "ciphertext must decode to 1–{MAX_REACTION_CIPHERTEXT_BYTES} bytes."
        )));
    }

    let target = load_target(&state, &auth, message_id).await?;

    // Human: The `deleted_for_everyone_at IS NULL` guard sits in the statement itself so a
    // reaction cannot land on a message that was deleted after `load_target` looked.
    let row = sqlx::query_as::<_, ReactionRow>(
        r#"
        INSERT INTO message_reactions (message_id, user_id, conversation_id, ciphertext)
        SELECT m.id, $2, m.conversation_id, $3
        FROM messages m
        WHERE m.id = $1 AND m.deleted_for_everyone_at IS NULL
        ON CONFLICT (message_id, user_id) DO UPDATE
        SET ciphertext = EXCLUDED.ciphertext,
            seq = EXCLUDED.seq,
            updated_at = EXCLUDED.updated_at
        RETURNING message_id, user_id, ciphertext, seq, updated_at
        "#,
    )
    .bind(message_id)
    .bind(auth.user_id)
    .bind(&ciphertext)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("upsert reaction failed: {err}")))?
    .ok_or_else(|| AppError::not_found("Message not found."))?;

    let entry = ReactionEntry::from(row);
    publish(&state, &auth, &target, &entry).await;

    tracing::info!(
        message_id = %message_id,
        user_id = %auth.user_id,
        seq = entry.seq,
        ciphertext_bytes = ciphertext.len(),
        "reactions.put ok"
    );
    Ok(Json(entry))
}

/// `DELETE /messages/:id/reaction` — remove the caller's reaction. `204` when there was none.
pub async fn delete_reaction(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(message_id): Path<Uuid>,
) -> Result<Response, AppError> {
    check_budget(&state, &auth).await?;
    let target = load_target_for_removal(&state, &auth, message_id).await?;

    let row = sqlx::query_as::<_, ReactionRow>(
        r#"
        UPDATE message_reactions
        SET ciphertext = NULL,
            seq = nextval('message_reaction_seq'),
            updated_at = now()
        WHERE message_id = $1 AND user_id = $2 AND ciphertext IS NOT NULL
        RETURNING message_id, user_id, ciphertext, seq, updated_at
        "#,
    )
    .bind(message_id)
    .bind(auth.user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("remove reaction failed: {err}")))?;

    let Some(row) = row else {
        return Ok(StatusCode::NO_CONTENT.into_response());
    };
    let entry = ReactionEntry::from(row);
    publish(&state, &auth, &target, &entry).await;

    tracing::info!(
        message_id = %message_id,
        user_id = %auth.user_id,
        seq = entry.seq,
        "reactions.delete ok"
    );
    Ok(Json(entry).into_response())
}

/// `GET /conversations/:peer_user_id/reactions?after_seq=&limit=` — every reaction change in
/// the conversation after `after_seq`, oldest first, removals included.
pub async fn list_reaction_changes(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(peer_user_id): Path<Uuid>,
    Query(query): Query<ReactionChangesQuery>,
) -> Result<Json<ReactionChangesResponse>, AppError> {
    let after_seq = query.after_seq.unwrap_or(0).max(0);
    let limit = query
        .limit
        .unwrap_or(DEFAULT_CHANGES_LIMIT)
        .clamp(1, MAX_CHANGES_LIMIT);

    let Some(conversation_id) = find_conversation(&state.pool, auth.user_id, peer_user_id).await?
    else {
        return Ok(Json(ReactionChangesResponse {
            reactions: vec![],
            next_seq: after_seq,
            has_more: false,
        }));
    };
    let cleared_at = clear_watermark(&state.pool, auth.user_id, conversation_id).await?;

    // Human: Rows for messages the caller can no longer see (cleared chat, deleted for me)
    // still advance the cursor but come back without their ciphertext, so a client that
    // somehow holds the message drops the reaction instead of keeping a stale one.
    // Agent: READS message_reactions via (conversation_id, seq) index; one query per page.
    let rows = sqlx::query_as::<_, ReactionRow>(
        r#"
        SELECT r.message_id, r.user_id,
               CASE
                   WHEN m.deleted_for_everyone_at IS NOT NULL THEN NULL
                   WHEN $4::timestamptz IS NOT NULL AND m.created_at <= $4::timestamptz THEN NULL
                   WHEN EXISTS (
                       SELECT 1 FROM message_hides h
                       WHERE h.message_id = r.message_id AND h.user_id = $5
                   ) THEN NULL
                   ELSE r.ciphertext
               END AS ciphertext,
               r.seq, r.updated_at
        FROM message_reactions r
        INNER JOIN messages m ON m.id = r.message_id
        WHERE r.conversation_id = $1 AND r.seq > $2
        ORDER BY r.seq ASC
        LIMIT $3
        "#,
    )
    .bind(conversation_id)
    .bind(after_seq)
    .bind(limit)
    .bind(cleared_at)
    .bind(auth.user_id)
    .fetch_all(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("list reaction changes failed: {err}")))?;

    let has_more = rows.len() as i64 >= limit;
    let next_seq = rows.last().map(|row| row.seq).unwrap_or(after_seq);
    Ok(Json(ReactionChangesResponse {
        reactions: rows.into_iter().map(ReactionEntry::from).collect(),
        next_seq,
        has_more,
    }))
}

/// Live reactions for a history page, keyed by message id, oldest change first.
/// Agent: CALLED by messages::list_messages; one query per page.
pub(crate) async fn live_reactions_batch(
    pool: &sqlx::PgPool,
    message_ids: &[Uuid],
) -> Result<HashMap<Uuid, Vec<ReactionEntry>>, AppError> {
    let mut map: HashMap<Uuid, Vec<ReactionEntry>> = HashMap::new();
    if message_ids.is_empty() {
        return Ok(map);
    }
    let rows = sqlx::query_as::<_, ReactionRow>(
        r#"
        SELECT message_id, user_id, ciphertext, seq, updated_at
        FROM message_reactions
        WHERE message_id = ANY($1) AND ciphertext IS NOT NULL
        ORDER BY seq ASC
        "#,
    )
    .bind(message_ids)
    .fetch_all(pool)
    .await
    .map_err(|err| AppError::Internal(format!("reactions batch failed: {err}")))?;
    for row in rows {
        map.entry(row.message_id)
            .or_default()
            .push(ReactionEntry::from(row));
    }
    Ok(map)
}

/// Highest reaction `seq` in a conversation, 0 when there is none. Clients without a catch-up
/// cursor start from the value read *before* their first history page.
pub(crate) async fn latest_reaction_seq(
    pool: &sqlx::PgPool,
    conversation_id: Uuid,
) -> Result<i64, AppError> {
    let seq: Option<i64> =
        sqlx::query_scalar(r#"SELECT MAX(seq) FROM message_reactions WHERE conversation_id = $1"#)
            .bind(conversation_id)
            .fetch_one(pool)
            .await
            .map_err(|err| AppError::Internal(format!("latest reaction seq failed: {err}")))?;
    Ok(seq.unwrap_or(0))
}

/// Clears every live reaction on a message deleted for everyone, bumping `seq` so devices
/// catching up drop them too.
/// Agent: CALLED inside messages::delete_for_everyone's transaction.
pub(crate) async fn clear_for_deleted_message(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    message_id: Uuid,
) -> Result<(), AppError> {
    sqlx::query(
        r#"
        UPDATE message_reactions
        SET ciphertext = NULL,
            seq = nextval('message_reaction_seq'),
            updated_at = now()
        WHERE message_id = $1 AND ciphertext IS NOT NULL
        "#,
    )
    .bind(message_id)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("clear reactions on delete failed: {err}")))?;
    Ok(())
}

async fn check_budget(state: &AppState, auth: &AuthContext) -> Result<(), AppError> {
    state
        .rate_limiter
        .check_budget(
            "reaction_user",
            &auth.user_id.to_string(),
            budgets::REACTION_USER,
        )
        .await
}

async fn load_target_row(
    state: &AppState,
    auth: &AuthContext,
    message_id: Uuid,
) -> Result<ReactionTarget, AppError> {
    let target = sqlx::query_as::<_, ReactionTarget>(
        r#"
        SELECT m.conversation_id, m.content_type, m.deleted_for_everyone_at,
               c.user_a_id, c.user_b_id
        FROM messages m
        INNER JOIN conversations c ON c.id = m.conversation_id
        WHERE m.id = $1
        "#,
    )
    .bind(message_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("load reaction target failed: {err}")))?
    .ok_or_else(|| AppError::not_found("Message not found."))?;

    // Same answer for "no such message" and "not your conversation".
    if target.user_a_id != auth.user_id && target.user_b_id != auth.user_id {
        return Err(AppError::not_found("Message not found."));
    }
    Ok(target)
}

/// Target for a new reaction: a visible, non-annotation message in a conversation the caller
/// may still message.
async fn load_target(
    state: &AppState,
    auth: &AuthContext,
    message_id: Uuid,
) -> Result<ReactionTarget, AppError> {
    let target = load_target_row(state, auth, message_id).await?;
    if target.deleted_for_everyone_at.is_some() {
        return Err(AppError::not_found("Message not found."));
    }
    if target.content_type == "annotation" {
        return Err(AppError::validation("Annotations cannot be reacted to."));
    }
    let peer = target.peer_of(auth.user_id);
    if peer != auth.user_id {
        if !are_contacts(&state.pool, auth.user_id, peer).await? {
            return Err(AppError::forbidden(
                "You can only react in chats with accepted contacts.",
            ));
        }
        if is_blocked_either_way(&state.pool, auth.user_id, peer).await? {
            return Err(AppError::forbidden("Cannot react while blocked."));
        }
    }
    Ok(target)
}

/// Target for a removal: taking back your own reaction is always allowed, even after the
/// contact was removed or blocked.
async fn load_target_for_removal(
    state: &AppState,
    auth: &AuthContext,
    message_id: Uuid,
) -> Result<ReactionTarget, AppError> {
    load_target_row(state, auth, message_id).await
}

async fn publish(
    state: &AppState,
    auth: &AuthContext,
    target: &ReactionTarget,
    entry: &ReactionEntry,
) {
    let event = serde_json::json!({
        "type": "message.reaction",
        "conversation_id": target.conversation_id,
        "device_id": auth.device_id,
        "reaction": entry,
    });
    if let Ok(payload) = serde_json::to_string(&event) {
        state
            .realtime
            .publish_to_users(
                [target.user_a_id, target.user_b_id],
                Some(auth.device_id),
                &payload,
            )
            .await;
    }
}
