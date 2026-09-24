//! HTTP message send, history, conversations list, delivery acks.

use std::collections::HashMap;

use axum::{
    Json,
    extract::{Path, Query, State},
    http::StatusCode,
};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::push::PushEvent;
use crate::rate_limit::budgets;
use crate::routes::contacts::{are_contacts, is_blocked_either_way};
use crate::routes::conversations::{
    advance_read_marker, announce_chat_read, clear_watermark, unread_in,
};
use crate::routes::reactions::{self, ReactionEntry};
use crate::state::AppState;

const MAX_CIPHERTEXT_BYTES: usize = 64 * 1024;
const DEFAULT_LIMIT: i64 = 50;
/// Page size cap for history; clients walk `before_*` cursors for the 90-day window.
const MAX_LIMIT: i64 = 100;

#[derive(Debug, Deserialize)]
pub struct SendMessageRequest {
    pub peer_user_id: Uuid,
    pub client_message_id: Uuid,
    pub content_type: String,
    pub ciphertext: String,
    /// Required when `content_type` is `media`.
    pub media_object_id: Option<Uuid>,
}

#[derive(Debug, Deserialize)]
pub struct ListMessagesQuery {
    pub peer_user_id: Uuid,
    pub limit: Option<i64>,
    pub before_created_at: Option<DateTime<Utc>>,
    pub before_id: Option<Uuid>,
}

#[derive(Debug, Serialize)]
pub struct MessageResponse {
    pub id: Uuid,
    pub conversation_id: Uuid,
    pub sender_user_id: Uuid,
    pub sender_device_id: Uuid,
    pub client_message_id: Uuid,
    pub content_type: String,
    /// Null when deleted for everyone.
    pub ciphertext: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub media_object_id: Option<Uuid>,
    pub deleted_for_everyone: bool,
    pub created_at: DateTime<Utc>,
    /// Outbound only: at least one of the peer's devices acknowledged delivery.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub delivered: Option<bool>,
    /// Outbound only: peer user has marked this message read.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub read: Option<bool>,
    /// History only: live sealed reactions, oldest change first.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub reactions: Vec<ReactionEntry>,
}

#[derive(Debug, Deserialize)]
pub struct DeleteMessageQuery {
    /// `me` (default) or `everyone`.
    pub scope: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct ListMessagesResponse {
    pub conversation_id: Option<Uuid>,
    pub messages: Vec<MessageResponse>,
    /// True when another page may exist (caller got a full page).
    pub has_more: bool,
    /// The conversation's highest reaction `seq`, read before the page. The page's `reactions`
    /// are the complete live set as of this value: a client keeps its own newer changes and
    /// drops older ones the page no longer lists. Also the first catch-up cursor.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reaction_seq: Option<i64>,
}

#[derive(Debug, FromRow)]
struct MessageRow {
    id: Uuid,
    conversation_id: Uuid,
    sender_user_id: Uuid,
    sender_device_id: Uuid,
    client_message_id: Uuid,
    content_type: String,
    ciphertext: Option<Vec<u8>>,
    media_object_id: Option<Uuid>,
    deleted_for_everyone_at: Option<DateTime<Utc>>,
    created_at: DateTime<Utc>,
}

/// `POST /messages`
pub async fn send_message(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<SendMessageRequest>,
) -> Result<(StatusCode, Json<MessageResponse>), AppError> {
    state
        .rate_limiter
        .check_budget(
            "message_send_user",
            &auth.user_id.to_string(),
            budgets::MESSAGE_SEND_USER,
        )
        .await?;

    let is_notes = body.peer_user_id == auth.user_id;

    let content_type = body.content_type.as_str();
    if !matches!(content_type, "text" | "media" | "annotation") {
        return Err(AppError::validation(
            "content_type must be 'text', 'media' or 'annotation'.",
        ));
    }
    if content_type == "media" && body.media_object_id.is_none() {
        return Err(AppError::validation(
            "media_object_id is required when content_type is media.",
        ));
    }
    if content_type != "media" && body.media_object_id.is_some() {
        return Err(AppError::validation(
            "media_object_id is only allowed when content_type is media.",
        ));
    }
    // Human: An annotation attaches data to an earlier message (e.g. a shared voice
    // transcript). It is delivered like any message but is not a new message to the user:
    // it must not reorder the chat list or wake the peer's phone.
    let is_annotation = content_type == "annotation";

    let ciphertext = BASE64
        .decode(body.ciphertext.trim().as_bytes())
        .map_err(|_| AppError::validation("ciphertext must be valid standard Base64."))?;
    if ciphertext.is_empty() || ciphertext.len() > MAX_CIPHERTEXT_BYTES {
        return Err(AppError::validation(format!(
            "ciphertext must decode to 1–{MAX_CIPHERTEXT_BYTES} bytes."
        )));
    }

    // Idempotent replay.
    if let Some(existing) =
        load_by_client_id(&state.pool, auth.user_id, body.client_message_id).await?
    {
        return Ok((StatusCode::OK, Json(message_to_response(existing))));
    }

    // Saved Messages (Notes): peer_user_id == self — no contact/block gate.
    if !is_notes {
        if !are_contacts(&state.pool, auth.user_id, body.peer_user_id).await? {
            return Err(AppError::forbidden(
                "You can only message accepted contacts.",
            ));
        }
        if is_blocked_either_way(&state.pool, auth.user_id, body.peer_user_id).await? {
            return Err(AppError::forbidden("Cannot message while blocked."));
        }
    }

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    // Human: Account deletion revokes the account's devices before it tombstones what they
    // sent, and the users row outlives it (migration 021), so the foreign keys no longer stop a
    // late send. Holding the device row until commit does: a racing send either commits first
    // and is tombstoned with the rest, or finds the device revoked.
    // Agent: SELECT devices FOR SHARE; waits behind devices::revoke_device's UPDATE.
    let device_live: Option<Uuid> = sqlx::query_scalar(
        r#"SELECT id FROM devices WHERE id = $1 AND revoked_at IS NULL FOR SHARE"#,
    )
    .bind(auth.device_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("lock sender device failed: {err}")))?;
    if device_live.is_none() {
        return Err(AppError::unauthorized());
    }

    if let Some(media_id) = body.media_object_id {
        #[derive(FromRow)]
        struct MediaLock {
            uploader_user_id: Uuid,
            message_id: Option<Uuid>,
        }
        let media = sqlx::query_as::<_, MediaLock>(
            r#"
            SELECT uploader_user_id, message_id
            FROM media_objects
            WHERE id = $1
            FOR UPDATE
            "#,
        )
        .bind(media_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("load media for message failed: {err}")))?
        .ok_or_else(|| AppError::not_found("Media not found."))?;

        if media.uploader_user_id != auth.user_id {
            return Err(AppError::forbidden(
                "You can only attach media you uploaded.",
            ));
        }
        if media.message_id.is_some() {
            return Err(AppError::already_exists(
                "Media is already linked to a message.",
            ));
        }
    }

    let conversation_id = ensure_conversation(&mut tx, auth.user_id, body.peer_user_id).await?;
    let message_id = Uuid::new_v4();
    let now = Utc::now();

    let insert = sqlx::query(
        r#"
        INSERT INTO messages (
            id, conversation_id, sender_user_id, sender_device_id,
            client_message_id, content_type, ciphertext, media_object_id, created_at
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
        "#,
    )
    .bind(message_id)
    .bind(conversation_id)
    .bind(auth.user_id)
    .bind(auth.device_id)
    .bind(body.client_message_id)
    .bind(content_type)
    .bind(&ciphertext)
    .bind(body.media_object_id)
    .bind(now)
    .execute(&mut *tx)
    .await;

    match insert {
        Ok(_) => {}
        Err(sqlx::Error::Database(db))
            if db.constraint() == Some("messages_sender_client_unique") =>
        {
            // Race: load existing outside tx.
            drop(tx);
            let existing = load_by_client_id(&state.pool, auth.user_id, body.client_message_id)
                .await?
                .ok_or_else(|| {
                    AppError::Internal("idempotent message missing after conflict".into())
                })?;
            return Ok((StatusCode::OK, Json(message_to_response(existing))));
        }
        Err(err) => {
            return Err(AppError::Internal(format!("insert message failed: {err}")));
        }
    }

    if let Some(media_id) = body.media_object_id {
        sqlx::query(
            r#"
            UPDATE media_objects
            SET message_id = $1
            WHERE id = $2 AND message_id IS NULL
            "#,
        )
        .bind(message_id)
        .bind(media_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("link media to message failed: {err}")))?;
    }

    // Delivery rows: all linked devices of both users; sender device already delivered.
    let device_ids: Vec<Uuid> = sqlx::query_scalar(
        r#"
        SELECT id FROM devices
        WHERE (user_id = $1 OR user_id = $2) AND revoked_at IS NULL
        "#,
    )
    .bind(auth.user_id)
    .bind(body.peer_user_id)
    .fetch_all(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("list devices for delivery failed: {err}")))?;

    for device_id in device_ids {
        let delivered_at = if device_id == auth.device_id {
            Some(now)
        } else {
            None
        };
        sqlx::query(
            r#"
            INSERT INTO message_deliveries (message_id, device_id, delivered_at)
            VALUES ($1, $2, $3)
            ON CONFLICT DO NOTHING
            "#,
        )
        .bind(message_id)
        .bind(device_id)
        .bind(delivered_at)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("insert delivery failed: {err}")))?;
    }

    // A reply means the chat was read: nothing before it stays unread for the sender. When
    // something was, the sender's other devices hear so, as after any read.
    let read_unread = if !is_annotation && !is_notes {
        let unread = unread_in(&mut *tx, auth.user_id, conversation_id).await?;
        advance_read_marker(&mut *tx, auth.user_id, conversation_id, now).await?;
        unread > 0
    } else {
        false
    };

    // Human: Keep conversation list sort cheap — denormalized last_message_at (migration 014).
    // Agent: UPDATE conversations.last_message_at = now in same transaction as insert.
    // Annotations are skipped so a shared transcript never moves the chat to the top.
    if !is_annotation {
        sqlx::query(
            r#"
            UPDATE conversations
            SET last_message_at = $1
            WHERE id = $2
            "#,
        )
        .bind(now)
        .bind(conversation_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| {
            AppError::Internal(format!("touch conversation last_message_at failed: {err}"))
        })?;
    }

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit message failed: {err}")))?;

    let response = MessageResponse {
        id: message_id,
        conversation_id,
        sender_user_id: auth.user_id,
        sender_device_id: auth.device_id,
        client_message_id: body.client_message_id,
        content_type: content_type.into(),
        ciphertext: Some(BASE64.encode(&ciphertext)),
        media_object_id: body.media_object_id,
        deleted_for_everyone: false,
        created_at: now,
        delivered: Some(false),
        read: Some(false),
        reactions: vec![],
    };

    // Human: Notify online peer devices and sender's other devices (not this sender device).
    let event = serde_json::json!({
        "type": "message.new",
        "message": &response,
    });
    if let Ok(payload) = serde_json::to_string(&event) {
        state
            .realtime
            .publish_to_users(
                [auth.user_id, body.peer_user_id],
                Some(auth.device_id),
                &payload,
            )
            .await;
    }

    if read_unread {
        announce_chat_read(
            &state,
            auth.user_id,
            auth.device_id,
            conversation_id,
            body.peer_user_id,
            now,
        )
        .await;
    }

    // A push for the peer's devices without a live socket. Annotations wait for the next sync
    // instead — they are not worth waking a phone for — and Saved Messages never notify.
    if !is_annotation && !is_notes {
        state
            .push
            .dispatch(PushEvent::Message {
                recipient: body.peer_user_id,
                sender: auth.user_id,
                conversation_id,
                message_id,
            })
            .await;
    }

    tracing::info!(
        message_id = %message_id,
        conversation_id = %conversation_id,
        sender_user_id = %auth.user_id,
        sender_device_id = %auth.device_id,
        peer_user_id = %body.peer_user_id,
        content_type,
        media_object_id = ?body.media_object_id,
        ciphertext_bytes = ciphertext.len(),
        "messages.send ok"
    );

    Ok((StatusCode::CREATED, Json(response)))
}

/// `GET /messages`
///
/// Peer may be **self** (Saved Messages / Notes). Cursor: pass both `before_created_at` and
/// `before_id` from the oldest item of the previous page.
pub async fn list_messages(
    State(state): State<AppState>,
    auth: AuthContext,
    Query(query): Query<ListMessagesQuery>,
) -> Result<Json<ListMessagesResponse>, AppError> {
    // Self peer is allowed (Notes). Contacts gate only applies to other users (send path).

    let limit = query.limit.unwrap_or(DEFAULT_LIMIT).clamp(1, MAX_LIMIT);

    let conversation_id = find_conversation(&state.pool, auth.user_id, query.peer_user_id).await?;
    let Some(conversation_id) = conversation_id else {
        return Ok(Json(ListMessagesResponse {
            conversation_id: None,
            messages: vec![],
            has_more: false,
            reaction_seq: None,
        }));
    };

    // Read before the page: a reaction committed later has a higher seq and reaches the client
    // through catch-up, so the page plus the cursor never miss one.
    let reaction_seq = Some(reactions::latest_reaction_seq(&state.pool, conversation_id).await?);

    // Human: A cleared chat stays empty for this caller until newer messages arrive; the peer
    // keeps whatever their own watermark still allows.
    // Agent: READS conversation_clears via conversations::clear_watermark; NULL = never cleared.
    let cleared_at = clear_watermark(&state.pool, auth.user_id, conversation_id).await?;

    // Ensure requester is a participant (always true if find matched).
    let rows =
        if let (Some(before_at), Some(before_id)) = (query.before_created_at, query.before_id) {
            sqlx::query_as::<_, MessageRow>(
                r#"
            SELECT m.id, m.conversation_id, m.sender_user_id, m.sender_device_id,
                   m.client_message_id, m.content_type, m.ciphertext, m.media_object_id,
                   m.deleted_for_everyone_at, m.created_at
            FROM messages m
            WHERE m.conversation_id = $1
              AND (m.created_at, m.id) < ($2, $3)
              AND ($6::timestamptz IS NULL OR m.created_at > $6::timestamptz)
              AND NOT EXISTS (
                SELECT 1 FROM message_hides h
                WHERE h.message_id = m.id AND h.user_id = $5
              )
            ORDER BY m.created_at DESC, m.id DESC
            LIMIT $4
            "#,
            )
            .bind(conversation_id)
            .bind(before_at)
            .bind(before_id)
            .bind(limit)
            .bind(auth.user_id)
            .bind(cleared_at)
            .fetch_all(&state.pool)
            .await
        } else {
            sqlx::query_as::<_, MessageRow>(
                r#"
            SELECT m.id, m.conversation_id, m.sender_user_id, m.sender_device_id,
                   m.client_message_id, m.content_type, m.ciphertext, m.media_object_id,
                   m.deleted_for_everyone_at, m.created_at
            FROM messages m
            WHERE m.conversation_id = $1
              AND ($4::timestamptz IS NULL OR m.created_at > $4::timestamptz)
              AND NOT EXISTS (
                SELECT 1 FROM message_hides h
                WHERE h.message_id = m.id AND h.user_id = $3
              )
            ORDER BY m.created_at DESC, m.id DESC
            LIMIT $2
            "#,
            )
            .bind(conversation_id)
            .bind(limit)
            .bind(auth.user_id)
            .bind(cleared_at)
            .fetch_all(&state.pool)
            .await
        }
        .map_err(|err| AppError::Internal(format!("list messages failed: {err}")))?;

    // Human: Batch receipt lookups so history pages are O(1) queries, not O(n).
    // Agent: CALLS peer_receipt_status_batch for outbound ids; WRITES delivered/read on responses.
    let outbound_ids: Vec<Uuid> = rows
        .iter()
        .filter(|row| row.sender_user_id == auth.user_id)
        .map(|row| row.id)
        .collect();
    // Notes (self peer): receipts are meaningless; skip batch lookup.
    let is_notes = query.peer_user_id == auth.user_id;
    let receipt_map = if is_notes || outbound_ids.is_empty() {
        HashMap::new()
    } else {
        peer_receipt_status_batch(&state.pool, &outbound_ids, query.peer_user_id).await?
    };

    let live_ids: Vec<Uuid> = rows
        .iter()
        .filter(|row| row.deleted_for_everyone_at.is_none() && row.content_type != "annotation")
        .map(|row| row.id)
        .collect();
    let mut reaction_map = reactions::live_reactions_batch(&state.pool, &live_ids).await?;

    let page_len = rows.len() as i64;
    let mut messages = Vec::with_capacity(rows.len());
    for row in rows {
        let mut response = message_to_response(row);
        response.reactions = reaction_map.remove(&response.id).unwrap_or_default();
        if !is_notes && response.sender_user_id == auth.user_id {
            let (delivered, read) = receipt_map
                .get(&response.id)
                .copied()
                .unwrap_or((false, false));
            response.delivered = Some(delivered);
            response.read = Some(read);
        }
        messages.push(response);
    }

    Ok(Json(ListMessagesResponse {
        conversation_id: Some(conversation_id),
        messages,
        has_more: page_len >= limit,
        reaction_seq,
    }))
}

#[derive(Debug, Deserialize)]
pub struct BulkReadRequest {
    pub peer_user_id: Uuid,
    /// Marks all messages from the peer in this conversation with
    /// `(created_at, id) <=` this message as read.
    pub up_to_message_id: Uuid,
}

#[derive(Debug, Serialize)]
pub struct BulkReadResponse {
    pub marked: u64,
    pub read_at: DateTime<Utc>,
}

/// `POST /messages/:id/read` — user-level read receipt (recipient only).
pub async fn mark_read(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(message_id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let meta = load_readable_message(&state.pool, message_id, auth.user_id).await?;
    let read_at = insert_read(&state.pool, message_id, auth.user_id).await?;
    fanout_message_read(
        &state,
        message_id,
        meta.conversation_id,
        auth.user_id,
        auth.device_id,
        meta.user_a_id,
        meta.user_b_id,
        read_at,
    )
    .await;
    if advance_read_marker(
        &state.pool,
        auth.user_id,
        meta.conversation_id,
        meta.created_at,
    )
    .await?
    {
        announce_chat_read(
            &state,
            auth.user_id,
            auth.device_id,
            meta.conversation_id,
            meta.sender_user_id,
            meta.created_at,
        )
        .await;
    }
    Ok(StatusCode::NO_CONTENT)
}

/// `POST /messages/read` — mark all messages from peer up to `up_to_message_id` as read.
pub async fn mark_read_bulk(
    State(state): State<AppState>,
    auth: AuthContext,
    Json(body): Json<BulkReadRequest>,
) -> Result<Json<BulkReadResponse>, AppError> {
    if body.peer_user_id == auth.user_id {
        return Err(AppError::validation("peer_user_id cannot be yourself."));
    }
    if !are_contacts(&state.pool, auth.user_id, body.peer_user_id).await? {
        return Err(AppError::forbidden(
            "You can only mark messages from accepted contacts as read.",
        ));
    }

    let meta = load_readable_message(&state.pool, body.up_to_message_id, auth.user_id).await?;
    // Cursor message must be in the conversation with this peer.
    let peer_in_conv = meta.user_a_id == body.peer_user_id || meta.user_b_id == body.peer_user_id;
    if !peer_in_conv {
        return Err(AppError::validation(
            "up_to_message_id is not in a conversation with peer_user_id.",
        ));
    }

    let now = Utc::now();
    let marked = insert_reads_up_to(
        &state,
        &auth,
        meta.conversation_id,
        body.peer_user_id,
        meta.created_at,
        body.up_to_message_id,
        now,
    )
    .await?;
    if marked > 0 {
        publish_bulk_read(
            &state,
            &auth,
            meta.conversation_id,
            body.peer_user_id,
            body.up_to_message_id,
            now,
            marked,
        )
        .await;
    }
    if advance_read_marker(
        &state.pool,
        auth.user_id,
        meta.conversation_id,
        meta.created_at,
    )
    .await?
    {
        announce_chat_read(
            &state,
            auth.user_id,
            auth.device_id,
            meta.conversation_id,
            body.peer_user_id,
            meta.created_at,
        )
        .await;
    }

    tracing::info!(
        user_id = %auth.user_id,
        peer = %body.peer_user_id,
        marked,
        "messages.read_bulk ok"
    );

    Ok(Json(BulkReadResponse {
        marked,
        read_at: now,
    }))
}

/// Read receipts for the peer's messages in a chat up to `(created_at, id)`, when the peer is
/// still a contact nobody blocked. Returns how many messages got one.
pub(crate) async fn send_read_receipts(
    state: &AppState,
    auth: &AuthContext,
    conversation_id: Uuid,
    peer_user_id: Uuid,
    up_to_created_at: DateTime<Utc>,
    up_to_id: Uuid,
) -> Result<u64, AppError> {
    if !are_contacts(&state.pool, auth.user_id, peer_user_id).await?
        || is_blocked_either_way(&state.pool, auth.user_id, peer_user_id).await?
    {
        return Ok(0);
    }
    // The peer's newest message the receipt covers: their app finds it in the thread.
    let newest_theirs: Option<Uuid> = sqlx::query_scalar(
        r#"
        SELECT id FROM messages
        WHERE conversation_id = $1 AND sender_user_id = $2 AND (created_at, id) <= ($3, $4)
          AND content_type <> 'annotation'
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        "#,
    )
    .bind(conversation_id)
    .bind(peer_user_id)
    .bind(up_to_created_at)
    .bind(up_to_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("load receipt anchor failed: {err}")))?;
    let Some(newest_theirs) = newest_theirs else {
        return Ok(0);
    };
    let now = Utc::now();
    let marked = insert_reads_up_to(
        state,
        auth,
        conversation_id,
        peer_user_id,
        up_to_created_at,
        up_to_id,
        now,
    )
    .await?;
    if marked > 0 {
        publish_bulk_read(
            state,
            auth,
            conversation_id,
            peer_user_id,
            newest_theirs,
            now,
            marked,
        )
        .await;
    }
    Ok(marked)
}

/// Marks every message of the peer's in the chat up to the cursor (inclusive) read by the
/// caller — annotations aside, which no one reads as a message. Returns the number newly marked.
async fn insert_reads_up_to(
    state: &AppState,
    auth: &AuthContext,
    conversation_id: Uuid,
    peer_user_id: Uuid,
    up_to_created_at: DateTime<Utc>,
    up_to_id: Uuid,
    now: DateTime<Utc>,
) -> Result<u64, AppError> {
    let result = sqlx::query(
        r#"
        INSERT INTO message_reads (message_id, user_id, read_at)
        SELECT m.id, $1, $2
        FROM messages m
        WHERE m.conversation_id = $3
          AND m.sender_user_id = $4
          AND m.sender_user_id <> $1
          AND m.content_type <> 'annotation'
          AND (m.created_at, m.id) <= ($5, $6)
          -- A single-message read can receipt the newest one first. A lower bound of "after
          -- the newest receipt" would then skip every earlier message forever. Already-read
          -- rows are left as they are.
          AND NOT EXISTS (
              SELECT 1 FROM message_reads r
              WHERE r.message_id = m.id AND r.user_id = $1
          )
        ON CONFLICT (message_id, user_id) DO NOTHING
        "#,
    )
    .bind(auth.user_id)
    .bind(now)
    .bind(conversation_id)
    .bind(peer_user_id)
    .bind(up_to_created_at)
    .bind(up_to_id)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("bulk read insert failed: {err}")))?;
    Ok(result.rows_affected())
}

async fn publish_bulk_read(
    state: &AppState,
    auth: &AuthContext,
    conversation_id: Uuid,
    peer_user_id: Uuid,
    up_to_message_id: Uuid,
    read_at: DateTime<Utc>,
    marked: u64,
) {
    let event = serde_json::json!({
        "type": "message.read",
        "message_id": up_to_message_id,
        "conversation_id": conversation_id,
        "user_id": auth.user_id,
        "device_id": auth.device_id,
        "read_at": read_at,
        "up_to_message_id": up_to_message_id,
        "marked": marked,
    });
    if let Ok(payload) = serde_json::to_string(&event) {
        state
            .realtime
            .publish_to_users([auth.user_id, peer_user_id], Some(auth.device_id), &payload)
            .await;
    }
}

#[derive(Debug, FromRow)]
struct ReadableMessage {
    conversation_id: Uuid,
    sender_user_id: Uuid,
    user_a_id: Uuid,
    user_b_id: Uuid,
    created_at: DateTime<Utc>,
}

/// Load message if caller is a conversation participant and is not the sender.
async fn load_readable_message(
    pool: &sqlx::PgPool,
    message_id: Uuid,
    reader_user_id: Uuid,
) -> Result<ReadableMessage, AppError> {
    let row = sqlx::query_as::<_, ReadableMessage>(
        r#"
        SELECT m.conversation_id, m.sender_user_id, c.user_a_id, c.user_b_id, m.created_at
        FROM messages m
        INNER JOIN conversations c ON c.id = m.conversation_id
        WHERE m.id = $1
        "#,
    )
    .bind(message_id)
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("load message for read failed: {err}")))?
    .ok_or_else(|| AppError::not_found("Message not found."))?;

    let is_participant = row.user_a_id == reader_user_id || row.user_b_id == reader_user_id;
    if !is_participant {
        return Err(AppError::not_found("Message not found."));
    }
    if row.sender_user_id == reader_user_id {
        return Err(AppError::validation(
            "Cannot mark your own messages as read.",
        ));
    }
    Ok(row)
}

async fn insert_read(
    pool: &sqlx::PgPool,
    message_id: Uuid,
    user_id: Uuid,
) -> Result<DateTime<Utc>, AppError> {
    let now = Utc::now();
    sqlx::query(
        r#"
        INSERT INTO message_reads (message_id, user_id, read_at)
        VALUES ($1, $2, $3)
        ON CONFLICT (message_id, user_id) DO NOTHING
        "#,
    )
    .bind(message_id)
    .bind(user_id)
    .bind(now)
    .execute(pool)
    .await
    .map_err(|err| AppError::Internal(format!("insert read failed: {err}")))?;

    // Prefer stored read_at when already present (idempotent).
    let stored: DateTime<Utc> = sqlx::query_scalar(
        r#"
        SELECT read_at FROM message_reads WHERE message_id = $1 AND user_id = $2
        "#,
    )
    .bind(message_id)
    .bind(user_id)
    .fetch_one(pool)
    .await
    .map_err(|err| AppError::Internal(format!("fetch read_at failed: {err}")))?;
    Ok(stored)
}

#[allow(clippy::too_many_arguments)]
async fn fanout_message_read(
    state: &AppState,
    message_id: Uuid,
    conversation_id: Uuid,
    reader_user_id: Uuid,
    reader_device_id: Uuid,
    user_a_id: Uuid,
    user_b_id: Uuid,
    read_at: DateTime<Utc>,
) {
    let event = serde_json::json!({
        "type": "message.read",
        "message_id": message_id,
        "conversation_id": conversation_id,
        "user_id": reader_user_id,
        "device_id": reader_device_id,
        "read_at": read_at,
    });
    if let Ok(payload) = serde_json::to_string(&event) {
        state
            .realtime
            .publish_to_users([user_a_id, user_b_id], Some(reader_device_id), &payload)
            .await;
    }
}

/// `POST /messages/:id/delivered`
pub async fn mark_delivered(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(message_id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let result = sqlx::query(
        r#"
        UPDATE message_deliveries d
        SET delivered_at = now()
        FROM messages m
        WHERE d.message_id = m.id
          AND d.message_id = $1
          AND d.device_id = $2
          AND d.delivered_at IS NULL
        "#,
    )
    .bind(message_id)
    .bind(auth.device_id)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("mark delivered failed: {err}")))?;

    if result.rows_affected() == 0 {
        // Already delivered or not for this device.
        let exists: bool = sqlx::query_scalar(
            r#"
            SELECT EXISTS(
                SELECT 1 FROM message_deliveries
                WHERE message_id = $1 AND device_id = $2
            )
            "#,
        )
        .bind(message_id)
        .bind(auth.device_id)
        .fetch_one(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("delivery exists check failed: {err}")))?;

        if !exists {
            return Err(AppError::not_found("Message delivery not found."));
        }
        return Ok(StatusCode::NO_CONTENT);
    }

    // Notify both conversation participants' online devices except the acking device.
    #[derive(FromRow)]
    struct Pair {
        user_a_id: Uuid,
        user_b_id: Uuid,
    }
    if let Ok(Some(pair)) = sqlx::query_as::<_, Pair>(
        r#"
        SELECT c.user_a_id, c.user_b_id
        FROM messages m
        INNER JOIN conversations c ON c.id = m.conversation_id
        WHERE m.id = $1
        "#,
    )
    .bind(message_id)
    .fetch_optional(&state.pool)
    .await
    {
        let delivered_at = Utc::now();
        let event = serde_json::json!({
            "type": "message.delivered",
            "message_id": message_id,
            "device_id": auth.device_id,
            "delivered_at": delivered_at,
        });
        if let Ok(payload) = serde_json::to_string(&event) {
            state
                .realtime
                .publish_to_users(
                    [pair.user_a_id, pair.user_b_id],
                    Some(auth.device_id),
                    &payload,
                )
                .await;
        }
    }

    Ok(StatusCode::NO_CONTENT)
}

async fn ensure_conversation(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_x: Uuid,
    user_y: Uuid,
) -> Result<Uuid, AppError> {
    // Notes: equal ids (self conversation). Otherwise ordered pair a < b.
    let (user_a, user_b) = if user_x <= user_y {
        (user_x, user_y)
    } else {
        (user_y, user_x)
    };

    let existing: Option<Uuid> = sqlx::query_scalar(
        r#"
        SELECT id FROM conversations
        WHERE user_a_id = $1 AND user_b_id = $2
        "#,
    )
    .bind(user_a)
    .bind(user_b)
    .fetch_optional(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("find conversation failed: {err}")))?;

    if let Some(id) = existing {
        return Ok(id);
    }

    let id = Uuid::new_v4();
    sqlx::query(
        r#"
        INSERT INTO conversations (id, user_a_id, user_b_id)
        VALUES ($1, $2, $3)
        "#,
    )
    .bind(id)
    .bind(user_a)
    .bind(user_b)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("insert conversation failed: {err}")))?;

    Ok(id)
}

pub(crate) async fn find_conversation(
    pool: &sqlx::PgPool,
    user_x: Uuid,
    user_y: Uuid,
) -> Result<Option<Uuid>, AppError> {
    let (user_a, user_b) = if user_x <= user_y {
        (user_x, user_y)
    } else {
        (user_y, user_x)
    };
    sqlx::query_scalar(
        r#"
        SELECT id FROM conversations
        WHERE user_a_id = $1 AND user_b_id = $2
        "#,
    )
    .bind(user_a)
    .bind(user_b)
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("find conversation failed: {err}")))
}

async fn load_by_client_id(
    pool: &sqlx::PgPool,
    sender_user_id: Uuid,
    client_message_id: Uuid,
) -> Result<Option<MessageRow>, AppError> {
    sqlx::query_as::<_, MessageRow>(
        r#"
        SELECT id, conversation_id, sender_user_id, sender_device_id,
               client_message_id, content_type, ciphertext, media_object_id,
               deleted_for_everyone_at, created_at
        FROM messages
        WHERE sender_user_id = $1 AND client_message_id = $2
        "#,
    )
    .bind(sender_user_id)
    .bind(client_message_id)
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("load by client_message_id failed: {err}")))
}

fn message_to_response(row: MessageRow) -> MessageResponse {
    let deleted = row.deleted_for_everyone_at.is_some();
    MessageResponse {
        id: row.id,
        conversation_id: row.conversation_id,
        sender_user_id: row.sender_user_id,
        sender_device_id: row.sender_device_id,
        client_message_id: row.client_message_id,
        content_type: row.content_type,
        ciphertext: row
            .ciphertext
            .as_ref()
            .filter(|_| !deleted)
            .map(|bytes| BASE64.encode(bytes)),
        media_object_id: if deleted { None } else { row.media_object_id },
        deleted_for_everyone: deleted,
        created_at: row.created_at,
        delivered: None,
        read: None,
        reactions: vec![],
    }
}

/// Delivery / read status of `peer_user_id` for many outbound messages (batched).
async fn peer_receipt_status_batch(
    pool: &sqlx::PgPool,
    message_ids: &[Uuid],
    peer_user_id: Uuid,
) -> Result<HashMap<Uuid, (bool, bool)>, AppError> {
    let mut map: HashMap<Uuid, (bool, bool)> =
        message_ids.iter().map(|id| (*id, (false, false))).collect();
    if message_ids.is_empty() {
        return Ok(map);
    }

    #[derive(FromRow)]
    struct DeliveredRow {
        message_id: Uuid,
    }

    let delivered_rows = sqlx::query_as::<_, DeliveredRow>(
        r#"
        SELECT DISTINCT d.message_id
        FROM message_deliveries d
        INNER JOIN devices dev ON dev.id = d.device_id
        WHERE d.message_id = ANY($1)
          AND dev.user_id = $2
          AND d.delivered_at IS NOT NULL
        "#,
    )
    .bind(message_ids)
    .bind(peer_user_id)
    .fetch_all(pool)
    .await
    .map_err(|err| AppError::Internal(format!("peer delivered batch failed: {err}")))?;

    for row in delivered_rows {
        if let Some(entry) = map.get_mut(&row.message_id) {
            entry.0 = true;
        }
    }

    #[derive(FromRow)]
    struct ReadRow {
        message_id: Uuid,
    }

    let read_rows = sqlx::query_as::<_, ReadRow>(
        r#"
        SELECT message_id
        FROM message_reads
        WHERE message_id = ANY($1) AND user_id = $2
        "#,
    )
    .bind(message_ids)
    .bind(peer_user_id)
    .fetch_all(pool)
    .await
    .map_err(|err| AppError::Internal(format!("peer read batch failed: {err}")))?;

    for row in read_rows {
        if let Some(entry) = map.get_mut(&row.message_id) {
            entry.1 = true;
        }
    }

    Ok(map)
}

/// `DELETE /messages/:id?scope=me|everyone`
pub async fn delete_message(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(message_id): Path<Uuid>,
    Query(query): Query<DeleteMessageQuery>,
) -> Result<StatusCode, AppError> {
    let scope = query.scope.as_deref().unwrap_or("me");
    match scope {
        "me" => delete_for_me(&state, auth.user_id, message_id).await,
        "everyone" => delete_for_everyone(&state, &auth, message_id).await,
        _ => Err(AppError::validation("scope must be 'me' or 'everyone'.")),
    }
}

async fn delete_for_me(
    state: &AppState,
    user_id: Uuid,
    message_id: Uuid,
) -> Result<StatusCode, AppError> {
    #[derive(FromRow)]
    struct DeleteTarget {
        user_a_id: Uuid,
        user_b_id: Uuid,
        media_object_id: Option<Uuid>,
    }

    let target = sqlx::query_as::<_, DeleteTarget>(
        r#"
        SELECT c.user_a_id, c.user_b_id, m.media_object_id
        FROM messages m
        INNER JOIN conversations c ON c.id = m.conversation_id
        WHERE m.id = $1
          AND (c.user_a_id = $2 OR c.user_b_id = $2)
        "#,
    )
    .bind(message_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("delete-for-me ACL failed: {err}")))?
    .ok_or_else(|| AppError::not_found("Message not found."))?;

    // Saved Messages (self conversation): hide is not enough — hard-delete the row and
    // its media so nothing can resurface on another device or via orphaned blobs.
    let is_notes = target.user_a_id == target.user_b_id && target.user_a_id == user_id;
    if is_notes {
        return hard_delete_notes_message(state, message_id, target.media_object_id).await;
    }

    sqlx::query(
        r#"
        INSERT INTO message_hides (user_id, message_id)
        VALUES ($1, $2)
        ON CONFLICT DO NOTHING
        "#,
    )
    .bind(user_id)
    .bind(message_id)
    .execute(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("hide message failed: {err}")))?;

    Ok(StatusCode::NO_CONTENT)
}

/// Fully erases a Saved Messages row + linked media (DB + blob). No hide tombstone left.
async fn hard_delete_notes_message(
    state: &AppState,
    message_id: Uuid,
    media_object_id: Option<Uuid>,
) -> Result<StatusCode, AppError> {
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin notes delete failed: {err}")))?;

    // Collect every media id that still points at this message (plus the message pointer).
    let mut media_ids: Vec<Uuid> = sqlx::query_scalar(
        r#"
        SELECT id FROM media_objects
        WHERE message_id = $1
        "#,
    )
    .bind(message_id)
    .fetch_all(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("list notes media failed: {err}")))?;

    if let Some(mid) = media_object_id
        && !media_ids.contains(&mid)
    {
        media_ids.push(mid);
    }

    // Break FKs before deleting the message row.
    sqlx::query(
        r#"
        UPDATE messages
        SET media_object_id = NULL, ciphertext = NULL
        WHERE id = $1
        "#,
    )
    .bind(message_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("unlink notes message media failed: {err}")))?;

    sqlx::query(
        r#"
        UPDATE media_objects
        SET message_id = NULL
        WHERE message_id = $1
        "#,
    )
    .bind(message_id)
    .execute(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("unlink notes media row failed: {err}")))?;

    // Cascades message_hides + message_deliveries.
    sqlx::query(r#"DELETE FROM messages WHERE id = $1"#)
        .bind(message_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("delete notes message failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit notes delete failed: {err}")))?;

    let purged = crate::routes::media::purge_media_ids(state, &media_ids).await?;
    tracing::info!(
        message_id = %message_id,
        media_purged = purged,
        "messages.notes_hard_delete ok"
    );

    Ok(StatusCode::NO_CONTENT)
}

async fn delete_for_everyone(
    state: &AppState,
    auth: &AuthContext,
    message_id: Uuid,
) -> Result<StatusCode, AppError> {
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    #[derive(FromRow)]
    struct MsgMeta {
        sender_user_id: Uuid,
        conversation_id: Uuid,
        deleted_for_everyone_at: Option<DateTime<Utc>>,
    }

    // Human: The conversation row first, like reaction writes and chat and account deletes
    // (reactions.rs): taking the message first could deadlock against a chat delete clearing
    // reactions.
    // Agent: a message's conversation never changes, so reading it before the lock is safe.
    let conversation_id: Uuid =
        sqlx::query_scalar(r#"SELECT conversation_id FROM messages WHERE id = $1"#)
            .bind(message_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(|err| AppError::Internal(format!("load message for delete failed: {err}")))?
            .ok_or_else(|| AppError::not_found("Message not found."))?;
    reactions::lock_conversation(&mut tx, conversation_id).await?;

    let meta = sqlx::query_as::<_, MsgMeta>(
        r#"
        SELECT sender_user_id, conversation_id, deleted_for_everyone_at
        FROM messages
        WHERE id = $1
        FOR UPDATE
        "#,
    )
    .bind(message_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("load message for delete failed: {err}")))?
    .ok_or_else(|| AppError::not_found("Message not found."))?;

    if meta.sender_user_id != auth.user_id {
        return Err(AppError::forbidden(
            "Only the sender can delete a message for everyone.",
        ));
    }

    let now = Utc::now();
    if meta.deleted_for_everyone_at.is_none() {
        sqlx::query(
            r#"
            UPDATE messages
            SET ciphertext = NULL,
                media_object_id = NULL,
                deleted_for_everyone_at = $1
            WHERE id = $2
            "#,
        )
        .bind(now)
        .bind(message_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("tombstone message failed: {err}")))?;

        sqlx::query(
            r#"
            UPDATE media_objects
            SET message_id = NULL
            WHERE message_id = $1
            "#,
        )
        .bind(message_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| AppError::Internal(format!("unlink media on delete failed: {err}")))?;

        reactions::clear_reactions_on(&mut tx, meta.conversation_id, &[message_id]).await?;
    }

    #[derive(FromRow)]
    struct Pair {
        user_a_id: Uuid,
        user_b_id: Uuid,
    }
    let pair = sqlx::query_as::<_, Pair>(
        r#"SELECT user_a_id, user_b_id FROM conversations WHERE id = $1"#,
    )
    .bind(meta.conversation_id)
    .fetch_one(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("load conversation for delete failed: {err}")))?;

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit delete everyone failed: {err}")))?;

    let event = serde_json::json!({
        "type": "message.deleted",
        "message_id": message_id,
        "conversation_id": meta.conversation_id,
        "scope": "everyone",
    });
    if let Ok(payload) = serde_json::to_string(&event) {
        state
            .realtime
            .publish_to_users([pair.user_a_id, pair.user_b_id], None, &payload)
            .await;
    }

    Ok(StatusCode::NO_CONTENT)
}
