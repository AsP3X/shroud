//! Conversation list and whole-chat deletion.
//!
//! Human: "Delete chat" is two different promises depending on scope. `me` only moves a
//! per-user watermark so the chat disappears here; `everyone` additionally asks the peer's
//! account whether it consented to being cleared, tombstones the requester's own messages
//! when it did not, and always drops the contact link so the next chat starts from scratch.
//! Agent: DB conversations/conversation_clears/messages/contacts/contact_requests;
//! READS users.allow_peer_chat_delete; PUBLISHES `conversation.deleted` over realtime.

use axum::{
    Json,
    extract::{Path, Query, State},
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

use crate::auth::session::AuthContext;
use crate::error::AppError;
use crate::state::AppState;

#[derive(Debug, Serialize)]
pub struct ConversationsResponse {
    pub conversations: Vec<ConversationItem>,
}

#[derive(Debug, Serialize)]
pub struct ConversationItem {
    pub id: Uuid,
    pub peer: PeerCard,
    pub created_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_message_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Serialize)]
pub struct PeerCard {
    pub id: Uuid,
    pub username: String,
}

#[derive(Debug, Deserialize)]
pub struct DeleteConversationQuery {
    /// `me` (default) or `everyone`.
    pub scope: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct DeleteConversationResponse {
    /// The requester's copy is gone (false only when there was no conversation to clear).
    pub cleared_for_me: bool,
    /// `everyone` scope: the peer had `allow_peer_chat_delete` on, so their copy went too.
    pub cleared_for_peer: bool,
    /// `everyone` scope: requester messages tombstoned for a peer that withheld consent.
    pub tombstoned: u64,
    /// `everyone` scope: a contact link existed and was dropped in both directions.
    pub contact_removed: bool,
}

/// `GET /conversations`
pub async fn list_conversations(
    State(state): State<AppState>,
    auth: AuthContext,
) -> Result<Json<ConversationsResponse>, AppError> {
    #[derive(FromRow)]
    struct Row {
        id: Uuid,
        peer_id: Uuid,
        peer_username: String,
        created_at: DateTime<Utc>,
        last_message_at: Option<DateTime<Utc>>,
    }

    // Human: Join peer username; use denormalized last_message_at (no correlated subquery).
    // A chat the caller cleared stays hidden until something newer than their watermark
    // arrives, which is what makes the next message read as a brand-new chat.
    // Agent: SELECT conversations JOIN users LEFT JOIN conversation_clears; RETURNS ConversationItem list.
    let rows = sqlx::query_as::<_, Row>(
        r#"
        SELECT
            c.id,
            CASE WHEN c.user_a_id = $1 THEN c.user_b_id ELSE c.user_a_id END AS peer_id,
            CASE WHEN c.user_a_id = $1 THEN ub.username ELSE ua.username END AS peer_username,
            c.created_at,
            c.last_message_at
        FROM conversations c
        INNER JOIN users ua ON ua.id = c.user_a_id
        INNER JOIN users ub ON ub.id = c.user_b_id
        LEFT JOIN conversation_clears cc
            ON cc.conversation_id = c.id AND cc.user_id = $1
        WHERE (c.user_a_id = $1 OR c.user_b_id = $1)
          AND c.user_a_id <> c.user_b_id
          AND (
            cc.cleared_at IS NULL
            OR COALESCE(c.last_message_at, c.created_at) > cc.cleared_at
          )
        ORDER BY c.last_message_at DESC NULLS LAST, c.created_at DESC
        "#,
    )
    .bind(auth.user_id)
    .fetch_all(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("list conversations failed: {err}")))?;

    let conversations = rows
        .into_iter()
        .map(|row| ConversationItem {
            id: row.id,
            peer: PeerCard {
                id: row.peer_id,
                username: row.peer_username,
            },
            created_at: row.created_at,
            last_message_at: row.last_message_at,
        })
        .collect();

    Ok(Json(ConversationsResponse { conversations }))
}

/// `DELETE /conversations/{peer_user_id}?scope=me|everyone`
///
/// The path segment is the **peer's user id**, matching how `GET /messages` addresses a
/// thread — a chat may need deleting before it has any server rows of its own.
///
/// - `me`: hides every current message from the caller only.
/// - `everyone`: same for the caller, plus (a) the peer's copy is cleared when they set
///   `allow_peer_chat_delete`, otherwise the caller's own messages become tombstones the
///   peer still sees while the peer's messages survive for them, and (b) the contact link
///   is dropped both ways regardless, so messaging again needs a fresh contact request.
pub async fn delete_conversation(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(peer_user_id): Path<Uuid>,
    Query(query): Query<DeleteConversationQuery>,
) -> Result<Json<DeleteConversationResponse>, AppError> {
    let scope = query.scope.as_deref().unwrap_or("me");
    let for_everyone = match scope {
        "me" => false,
        "everyone" => true,
        _ => return Err(AppError::validation("scope must be 'me' or 'everyone'.")),
    };

    // Saved Messages (peer == self): there is no second party to delete for.
    let is_notes = peer_user_id == auth.user_id;
    if for_everyone && is_notes {
        return Err(AppError::validation(
            "Saved Messages can only be deleted for you.",
        ));
    }

    let peer_exists: bool =
        sqlx::query_scalar(r#"SELECT EXISTS(SELECT 1 FROM users WHERE id = $1)"#)
            .bind(peer_user_id)
            .fetch_one(&state.pool)
            .await
            .map_err(|err| AppError::Internal(format!("delete chat peer check failed: {err}")))?;
    if !peer_exists {
        return Err(AppError::not_found("User not found."));
    }

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin transaction failed: {err}")))?;

    let conversation_id = find_conversation_tx(&mut tx, auth.user_id, peer_user_id).await?;
    let now = Utc::now();
    let mut tombstoned = 0_u64;
    let mut cleared_for_peer = false;

    if let Some(conversation_id) = conversation_id {
        upsert_clear(&mut tx, auth.user_id, conversation_id, now).await?;

        if for_everyone {
            cleared_for_peer = peer_allows_chat_delete(&mut tx, peer_user_id).await?;
            if cleared_for_peer {
                upsert_clear(&mut tx, peer_user_id, conversation_id, now).await?;
            } else {
                // Peer keeps their own history, but nothing of ours stays readable there.
                tombstoned =
                    tombstone_own_messages(&mut tx, conversation_id, auth.user_id, now).await?;
            }
        }

        // Ciphertext neither side can reach any more has no reason to exist on the server.
        purge_fully_cleared(&mut tx, conversation_id).await?;
    }

    let contact_removed = if for_everyone {
        drop_connection(&mut tx, auth.user_id, peer_user_id, now).await?
    } else {
        false
    };

    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit delete conversation failed: {err}")))?;

    // Human: Tell the other participant (and our own other devices) to drop or reload the
    // thread instead of waiting for the next poll to notice it changed.
    // Agent: PUBLISHES conversation.deleted; `me` scope stays inside the requester's devices.
    let event = serde_json::json!({
        "type": "conversation.deleted",
        "conversation_id": conversation_id,
        "user_id": auth.user_id,
        "peer_user_id": peer_user_id,
        "scope": scope,
        "cleared_for_peer": cleared_for_peer,
    });
    if let Ok(payload) = serde_json::to_string(&event) {
        let audience: Vec<Uuid> = if for_everyone {
            vec![auth.user_id, peer_user_id]
        } else {
            vec![auth.user_id]
        };
        state
            .realtime
            .publish_to_users(audience, Some(auth.device_id), &payload)
            .await;
    }

    tracing::info!(
        user_id = %auth.user_id,
        peer_user_id = %peer_user_id,
        conversation_id = ?conversation_id,
        scope,
        cleared_for_peer,
        tombstoned,
        contact_removed,
        "conversations.delete ok"
    );

    Ok(Json(DeleteConversationResponse {
        cleared_for_me: conversation_id.is_some(),
        cleared_for_peer,
        tombstoned,
        contact_removed,
    }))
}

/// Timestamp at or before which `user_id` has hidden this conversation, if ever.
///
/// Agent: READS conversation_clears; callers bind the result into history filters.
pub(crate) async fn clear_watermark(
    pool: &sqlx::PgPool,
    user_id: Uuid,
    conversation_id: Uuid,
) -> Result<Option<DateTime<Utc>>, AppError> {
    let watermark: Option<DateTime<Utc>> = sqlx::query_scalar(
        r#"
        SELECT cleared_at FROM conversation_clears
        WHERE user_id = $1 AND conversation_id = $2
        "#,
    )
    .bind(user_id)
    .bind(conversation_id)
    .fetch_optional(pool)
    .await
    .map_err(|err| AppError::Internal(format!("load clear watermark failed: {err}")))?;
    Ok(watermark)
}

async fn find_conversation_tx(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
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
        FOR UPDATE
        "#,
    )
    .bind(user_a)
    .bind(user_b)
    .fetch_optional(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("find conversation for delete failed: {err}")))
}

/// Moves a participant's watermark forward. Never backwards: a replayed or out-of-order
/// request must not un-hide messages the user already deleted.
async fn upsert_clear(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
    conversation_id: Uuid,
    at: DateTime<Utc>,
) -> Result<(), AppError> {
    sqlx::query(
        r#"
        INSERT INTO conversation_clears (user_id, conversation_id, cleared_at)
        VALUES ($1, $2, $3)
        ON CONFLICT (user_id, conversation_id)
        DO UPDATE SET cleared_at = GREATEST(conversation_clears.cleared_at, EXCLUDED.cleared_at)
        "#,
    )
    .bind(user_id)
    .bind(conversation_id)
    .bind(at)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("upsert conversation clear failed: {err}")))?;
    Ok(())
}

async fn peer_allows_chat_delete(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    peer_user_id: Uuid,
) -> Result<bool, AppError> {
    sqlx::query_scalar(r#"SELECT allow_peer_chat_delete FROM users WHERE id = $1"#)
        .bind(peer_user_id)
        .fetch_optional(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("load peer delete consent failed: {err}")))?
        .ok_or_else(|| AppError::not_found("User not found."))
}

/// Unsends every message the requester sent in this chat: same tombstone shape as
/// `DELETE /messages/{id}?scope=everyone`, so the peer sees "message deleted" rows.
async fn tombstone_own_messages(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    conversation_id: Uuid,
    sender_user_id: Uuid,
    at: DateTime<Utc>,
) -> Result<u64, AppError> {
    // Unlink first: the media row points at the message, and the UPDATE below only clears
    // the pointer in the other direction. Orphan media is reclaimed by the media GC.
    sqlx::query(
        r#"
        UPDATE media_objects
        SET message_id = NULL
        WHERE message_id IN (
            SELECT id FROM messages
            WHERE conversation_id = $1
              AND sender_user_id = $2
              AND created_at <= $3
        )
        "#,
    )
    .bind(conversation_id)
    .bind(sender_user_id)
    .bind(at)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("unlink media on chat delete failed: {err}")))?;

    let result = sqlx::query(
        r#"
        UPDATE messages
        SET ciphertext = NULL,
            media_object_id = NULL,
            deleted_for_everyone_at = COALESCE(deleted_for_everyone_at, $3)
        WHERE conversation_id = $1
          AND sender_user_id = $2
          AND created_at <= $3
          AND deleted_for_everyone_at IS NULL
        "#,
    )
    .bind(conversation_id)
    .bind(sender_user_id)
    .bind(at)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("tombstone chat messages failed: {err}")))?;

    Ok(result.rows_affected())
}

/// Deletes messages both participants have cleared past. `LEAST` alone would be wrong here
/// (it skips NULLs), so a one-sided clear is filtered out explicitly.
async fn purge_fully_cleared(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    conversation_id: Uuid,
) -> Result<u64, AppError> {
    let result = sqlx::query(
        r#"
        WITH bounds AS (
            SELECT
                (
                    SELECT cc.cleared_at FROM conversation_clears cc
                    WHERE cc.conversation_id = c.id AND cc.user_id = c.user_a_id
                ) AS a_at,
                (
                    SELECT cc.cleared_at FROM conversation_clears cc
                    WHERE cc.conversation_id = c.id AND cc.user_id = c.user_b_id
                ) AS b_at
            FROM conversations c
            WHERE c.id = $1
        )
        DELETE FROM messages m
        USING bounds b
        WHERE m.conversation_id = $1
          AND b.a_at IS NOT NULL
          AND b.b_at IS NOT NULL
          AND m.created_at <= LEAST(b.a_at, b.b_at)
        "#,
    )
    .bind(conversation_id)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("purge cleared messages failed: {err}")))?;

    Ok(result.rows_affected())
}

/// Drops the contact edge both ways and cancels anything pending, so the pair has to
/// reconnect before a new chat can exist. Blocking stays a separate, explicit action.
async fn drop_connection(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
    peer_user_id: Uuid,
    now: DateTime<Utc>,
) -> Result<bool, AppError> {
    let removed = sqlx::query(
        r#"
        DELETE FROM contacts
        WHERE (user_id = $1 AND contact_user_id = $2)
           OR (user_id = $2 AND contact_user_id = $1)
        "#,
    )
    .bind(user_id)
    .bind(peer_user_id)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("delete contacts on chat delete failed: {err}")))?
    .rows_affected()
        > 0;

    sqlx::query(
        r#"
        UPDATE contact_requests
        SET status = 'cancelled', responded_at = $1
        WHERE status = 'pending'
          AND (
            (from_user_id = $2 AND to_user_id = $3)
            OR (from_user_id = $3 AND to_user_id = $2)
          )
        "#,
    )
    .bind(now)
    .bind(user_id)
    .bind(peer_user_id)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("cancel requests on chat delete failed: {err}")))?;

    Ok(removed)
}
