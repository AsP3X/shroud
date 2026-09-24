//! Conversation list and whole-chat deletion.
//!
//! Human: "Delete chat" is two different promises depending on scope. `me` only moves a
//! per-user watermark so the chat disappears here; `everyone` additionally asks the peer's
//! account whether it consented to being cleared, tombstones the requester's own messages
//! when it did not, and always drops the contact link so the next chat starts from scratch.
//! Agent: DB conversations/conversation_clears/messages/contacts/contact_requests;
//! READS users.allow_peer_chat_delete; PUBLISHES `conversation.deleted` over realtime.

use std::collections::BTreeMap;

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
use crate::push::PushEvent;
use crate::routes::notifications::MuteState;
use crate::state::AppState;

/// `peer.username` for a chat whose peer deleted their account (migration 021 clears it).
const DELETED_ACCOUNT_NAME: &str = "Deleted account";

/// Unread counts stop here: a badge reads "999+" long before anyone counts further, and the
/// count walks the unread messages themselves.
pub const UNREAD_COUNT_CAP: i64 = 999;

/// The unread messages of chat `c` for user `$1` (with `cr` = their read marker and `cc` =
/// their clear watermark joined in): the other participant's, after both, still visible.
/// Human: Walks the chat's `(conversation_id, created_at)` index from the marker on, so it
/// costs what is unread, not the chat's history.
const UNREAD_MESSAGES_SQL: &str = r#"
    SELECT 1
    FROM messages m
    WHERE m.conversation_id = c.id
      AND m.sender_user_id <> $1
      AND m.content_type <> 'annotation'
      AND m.deleted_for_everyone_at IS NULL
      AND m.created_at > COALESCE(GREATEST(cr.read_at, cc.cleared_at), '-infinity'::timestamptz)
      AND NOT EXISTS (
          SELECT 1 FROM message_hides h WHERE h.message_id = m.id AND h.user_id = $1
      )
    LIMIT 999
"#;

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
    /// The chat's latest reaction change (0 when none): a client whose catch-up cursor is
    /// behind knows without opening the chat.
    pub reaction_seq: i64,
    /// Live reactions by the other participant to the caller's messages with an emoji added
    /// since the caller last marked them seen (`POST /conversations/{peer}/reactions/seen`):
    /// the heart badge.
    pub unseen_reactions: i64,
    /// The other participant's messages after the caller's read marker (at most
    /// [`UNREAD_COUNT_CAP`]). Reading on any device clears it on all of them.
    pub unread_count: i64,
    /// Present while the caller has this chat muted.
    pub mute: Option<MuteState>,
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
        reaction_seq: i64,
        unseen_reactions: i64,
        unread_count: i64,
        muted: bool,
        muted_until: Option<DateTime<Utc>>,
    }

    // Human: Join peer username; use denormalized last_message_at (no correlated subquery).
    // The unseen-reaction count is one range of the partial unseen index per chat: the other
    // person's live reactions to the caller's messages with `added_seq > seen_seq`, so it only
    // walks what the caller has not seen (a clear marks everything seen). The unread count is
    // the same kind of range, from the caller's read marker on.
    // A chat the caller cleared stays hidden until something newer than their watermark
    // arrives, which is what makes the next message read as a brand-new chat. A deleted
    // account has no username left; both apps require one, so it is named here.
    // Agent: SELECT conversations JOIN users LEFT JOIN conversation_clears, conversation_reads,
    // chat_mutes; RETURNS ConversationItem list.
    let sql = format!(
        r#"
        SELECT
            c.id,
            CASE WHEN c.user_a_id = $1 THEN c.user_b_id ELSE c.user_a_id END AS peer_id,
            COALESCE(
                CASE WHEN c.user_a_id = $1 THEN ub.username ELSE ua.username END,
                $2
            ) AS peer_username,
            c.created_at,
            c.last_message_at,
            COALESCE(rs.seq, 0) AS reaction_seq,
            (
                SELECT COUNT(*)
                FROM message_reactions r
                INNER JOIN messages m ON m.id = r.message_id
                WHERE r.conversation_id = c.id
                  AND r.message_sender_id = $1
                  AND r.user_id <> r.message_sender_id
                  AND r.ciphertext IS NOT NULL
                  AND r.added_seq > COALESCE(rr.seen_seq, 0)
                  AND m.deleted_for_everyone_at IS NULL
                  AND (cc.cleared_at IS NULL OR m.created_at > cc.cleared_at)
                  AND NOT EXISTS (
                      SELECT 1 FROM message_hides h
                      WHERE h.message_id = m.id AND h.user_id = $1
                  )
            ) AS unseen_reactions,
            (SELECT COUNT(*) FROM ({UNREAD_MESSAGES_SQL}) unread) AS unread_count,
            mu.user_id IS NOT NULL AS muted,
            mu.muted_until
        FROM conversations c
        INNER JOIN users ua ON ua.id = c.user_a_id
        INNER JOIN users ub ON ub.id = c.user_b_id
        LEFT JOIN conversation_clears cc
            ON cc.conversation_id = c.id AND cc.user_id = $1
        LEFT JOIN conversation_reads cr
            ON cr.conversation_id = c.id AND cr.user_id = $1
        LEFT JOIN conversation_reaction_seqs rs ON rs.conversation_id = c.id
        LEFT JOIN reaction_reads rr ON rr.conversation_id = c.id AND rr.user_id = $1
        LEFT JOIN chat_mutes mu
            ON mu.user_id = $1
           AND mu.peer_user_id = CASE WHEN c.user_a_id = $1 THEN c.user_b_id ELSE c.user_a_id END
           AND (mu.muted_until IS NULL OR mu.muted_until > now())
        WHERE (c.user_a_id = $1 OR c.user_b_id = $1)
          AND c.user_a_id <> c.user_b_id
          AND (
            cc.cleared_at IS NULL
            OR COALESCE(c.last_message_at, c.created_at) > cc.cleared_at
          )
        ORDER BY c.last_message_at DESC NULLS LAST, c.created_at DESC
        "#
    );
    let rows = sqlx::query_as::<_, Row>(&sql)
        .bind(auth.user_id)
        .bind(DELETED_ACCOUNT_NAME)
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
            reaction_seq: row.reaction_seq,
            unseen_reactions: row.unseen_reactions,
            unread_count: row.unread_count,
            mute: row.muted.then_some(MuteState {
                until: row.muted_until,
            }),
        })
        .collect();

    Ok(Json(ConversationsResponse { conversations }))
}

/// Unread messages across `user_id`'s chats (the app icon badge). Muted chats count only
/// with `include_muted`.
pub async fn unread_total(
    pool: &sqlx::PgPool,
    user_id: Uuid,
    include_muted: bool,
) -> Result<i64, sqlx::Error> {
    let sql = format!(
        r#"
        SELECT COALESCE(SUM(per_chat.unread), 0)::bigint
        FROM (
            SELECT (SELECT COUNT(*) FROM ({UNREAD_MESSAGES_SQL}) unread) AS unread
            FROM conversations c
            LEFT JOIN conversation_clears cc
                ON cc.conversation_id = c.id AND cc.user_id = $1
            LEFT JOIN conversation_reads cr
                ON cr.conversation_id = c.id AND cr.user_id = $1
            WHERE (c.user_a_id = $1 OR c.user_b_id = $1)
              AND c.user_a_id <> c.user_b_id
              AND (
                $2
                OR NOT EXISTS (
                    SELECT 1 FROM chat_mutes mu
                    WHERE mu.user_id = $1
                      AND mu.peer_user_id =
                          CASE WHEN c.user_a_id = $1 THEN c.user_b_id ELSE c.user_a_id END
                      AND (mu.muted_until IS NULL OR mu.muted_until > now())
                )
              )
        ) per_chat
        "#
    );
    sqlx::query_scalar(&sql)
        .bind(user_id)
        .bind(include_muted)
        .fetch_one(pool)
        .await
}

/// Unread messages in one chat for `user_id`.
pub(crate) async fn unread_in<'e, E>(
    executor: E,
    user_id: Uuid,
    conversation_id: Uuid,
) -> Result<i64, AppError>
where
    E: sqlx::Executor<'e, Database = sqlx::Postgres>,
{
    let sql = format!(
        r#"
        SELECT (SELECT COUNT(*) FROM ({UNREAD_MESSAGES_SQL}) unread)
        FROM conversations c
        LEFT JOIN conversation_clears cc ON cc.conversation_id = c.id AND cc.user_id = $1
        LEFT JOIN conversation_reads cr ON cr.conversation_id = c.id AND cr.user_id = $1
        WHERE c.id = $2
        "#
    );
    let count: Option<i64> = sqlx::query_scalar(&sql)
        .bind(user_id)
        .bind(conversation_id)
        .fetch_optional(executor)
        .await
        .map_err(|err| AppError::Internal(format!("count unread failed: {err}")))?;
    Ok(count.unwrap_or(0))
}

/// Moves `user_id`'s read marker in a chat forward to `read_at` — never back. True when it
/// moved (a first marker counts).
pub(crate) async fn advance_read_marker<'e, E>(
    executor: E,
    user_id: Uuid,
    conversation_id: Uuid,
    read_at: DateTime<Utc>,
) -> Result<bool, AppError>
where
    E: sqlx::Executor<'e, Database = sqlx::Postgres>,
{
    let moved: Option<DateTime<Utc>> = sqlx::query_scalar(
        r#"
        INSERT INTO conversation_reads (user_id, conversation_id, read_at)
        VALUES ($1, $2, $3)
        ON CONFLICT (user_id, conversation_id) DO UPDATE SET read_at = EXCLUDED.read_at
        WHERE conversation_reads.read_at < EXCLUDED.read_at
        RETURNING read_at
        "#,
    )
    .bind(user_id)
    .bind(conversation_id)
    .bind(read_at)
    .fetch_optional(executor)
    .await
    .map_err(|err| AppError::Internal(format!("advance read marker failed: {err}")))?;
    Ok(moved.is_some())
}

/// After `reader` read a chat on `device`: their other devices drop its unread count and its
/// delivered notifications, and their offline iPhones' icon badges come down.
pub(crate) async fn announce_chat_read(
    state: &AppState,
    reader: Uuid,
    device: Uuid,
    conversation_id: Uuid,
    peer_user_id: Uuid,
    read_at: DateTime<Utc>,
) {
    // Not guessed: a wrong 0 would clear a chat the other devices still count.
    let unread_count = match unread_in(&state.pool, reader, conversation_id).await {
        Ok(count) => count,
        Err(err) => {
            tracing::warn!(error = %err, %reader, "conversation.read not sent");
            return;
        }
    };
    let event = serde_json::json!({
        "type": "conversation.read",
        "conversation_id": conversation_id,
        "peer_user_id": peer_user_id,
        "read_at": read_at,
        "unread_count": unread_count,
    });
    if let Ok(payload) = serde_json::to_string(&event) {
        state
            .realtime
            .publish_to_users([reader], Some(device), &payload)
            .await;
    }
    state
        .push
        .dispatch(PushEvent::BadgeSync {
            recipient: reader,
            reader_device: device,
        })
        .await;
}

#[derive(Debug, Default, Deserialize)]
pub struct MarkChatReadRequest {
    /// Read up to this message (inclusive). Omitted: everything in the chat.
    pub up_to_message_id: Option<Uuid>,
}

#[derive(Debug, Serialize)]
pub struct MarkChatReadResponse {
    /// The caller's read marker after the call; null when the chat has no messages yet.
    pub read_at: Option<DateTime<Utc>>,
    pub unread_count: i64,
    /// How many of the peer's messages got a read receipt with this call.
    pub receipts: u64,
}

/// `POST /conversations/{peer_user_id}/read` — the caller looked at the chat.
///
/// Human: Moves the read marker (the unread badge on every device), and sends the peer read
/// receipts for what it covers when they are still a contact — the same receipts as
/// `POST /messages/read`, which the iPhone app sends as it reads.
/// Agent: WRITES conversation_reads, message_reads; PUBLISHES message.read (peer) and
/// conversation.read (own devices); DISPATCHES BadgeSync.
pub async fn mark_chat_read(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(peer_user_id): Path<Uuid>,
    body: Option<Json<MarkChatReadRequest>>,
) -> Result<Json<MarkChatReadResponse>, AppError> {
    let Json(body) = body.unwrap_or_default();
    if peer_user_id == auth.user_id {
        return Err(AppError::validation("Saved Messages have nothing unread."));
    }
    let Some(conversation_id) =
        crate::routes::messages::find_conversation(&state.pool, auth.user_id, peer_user_id).await?
    else {
        return Ok(Json(MarkChatReadResponse {
            read_at: None,
            unread_count: 0,
            receipts: 0,
        }));
    };

    #[derive(FromRow)]
    struct Anchor {
        id: Uuid,
        created_at: DateTime<Utc>,
    }
    let anchor = match body.up_to_message_id {
        Some(message_id) => sqlx::query_as::<_, Anchor>(
            r#"SELECT id, created_at FROM messages WHERE id = $1 AND conversation_id = $2"#,
        )
        .bind(message_id)
        .bind(conversation_id)
        .fetch_optional(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("load read anchor failed: {err}")))?
        .ok_or_else(|| AppError::not_found("Message not found."))
        .map(Some)?,
        None => sqlx::query_as::<_, Anchor>(
            r#"
            SELECT id, created_at FROM messages
            WHERE conversation_id = $1
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            "#,
        )
        .bind(conversation_id)
        .fetch_optional(&state.pool)
        .await
        .map_err(|err| AppError::Internal(format!("load newest message failed: {err}")))?,
    };
    let Some(anchor) = anchor else {
        return Ok(Json(MarkChatReadResponse {
            read_at: None,
            unread_count: 0,
            receipts: 0,
        }));
    };

    let moved = advance_read_marker(
        &state.pool,
        auth.user_id,
        conversation_id,
        anchor.created_at,
    )
    .await?;
    let receipts = crate::routes::messages::send_read_receipts(
        &state,
        &auth,
        conversation_id,
        peer_user_id,
        anchor.created_at,
        anchor.id,
    )
    .await?;
    if moved {
        announce_chat_read(
            &state,
            auth.user_id,
            auth.device_id,
            conversation_id,
            peer_user_id,
            anchor.created_at,
        )
        .await;
    }

    let read_at: Option<DateTime<Utc>> = sqlx::query_scalar(
        r#"SELECT read_at FROM conversation_reads WHERE user_id = $1 AND conversation_id = $2"#,
    )
    .bind(auth.user_id)
    .bind(conversation_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|err| AppError::Internal(format!("load read marker failed: {err}")))?;
    let unread_count = unread_in(&state.pool, auth.user_id, conversation_id).await?;
    Ok(Json(MarkChatReadResponse {
        read_at,
        unread_count,
        receipts,
    }))
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
                // Peer keeps their own history, but nothing of ours stays readable there: our
                // messages become tombstones and our reactions on theirs are taken back.
                tombstoned =
                    tombstone_own_messages(&mut tx, conversation_id, auth.user_id, now).await?;
                crate::routes::reactions::clear_reactions_by(
                    &mut tx,
                    auth.user_id,
                    &[conversation_id],
                )
                .await?;
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
    let event = conversation_deleted_event(
        conversation_id,
        auth.user_id,
        peer_user_id,
        scope,
        cleared_for_peer,
    );
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

/// `conversation.deleted` WS payload. `user_id` deleted the chat; `peer_user_id` is the other
/// participant. Clients drop their copy when `cleared_for_peer` is true (or they deleted it),
/// and reload the thread otherwise.
pub(crate) fn conversation_deleted_event(
    conversation_id: Option<Uuid>,
    user_id: Uuid,
    peer_user_id: Uuid,
    scope: &str,
    cleared_for_peer: bool,
) -> serde_json::Value {
    serde_json::json!({
        "type": "conversation.deleted",
        "conversation_id": conversation_id,
        "user_id": user_id,
        "peer_user_id": peer_user_id,
        "scope": scope,
        "cleared_for_peer": cleared_for_peer,
    })
}

/// One chat of an account being deleted, as [`delete_chats_for_both`] left it.
pub(crate) struct DeletedChat {
    pub conversation_id: Uuid,
    pub peer_user_id: Uuid,
    /// The peer had `allow_peer_chat_delete` on, so their copy was cleared as well.
    pub cleared_for_peer: bool,
}

/// "Delete chat for both" on every chat `user_id` has with someone else, for
/// `DELETE /auth/account`.
///
/// Human: The same promise as `DELETE /conversations/{peer}?scope=everyone`: what the account
/// sent becomes "Message deleted", a peer who allowed `allow_peer_chat_delete` loses the chat,
/// and everyone else keeps their own messages. No reaction of the account's, or on what it
/// sent, stays sealed. The caller revokes the account's devices first and drops its contacts
/// itself.
/// Agent: SELECT conversations FOR UPDATE (id order) JOIN users; UPDATE messages/media_objects
/// (tombstones); upsert conversation_clears; purge fully cleared messages; clear reactions on
/// the tombstones and the account's own (a new seq each); RETURNS one DeletedChat per
/// conversation.
pub(crate) async fn delete_chats_for_both(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
    at: DateTime<Utc>,
) -> Result<Vec<DeletedChat>, AppError> {
    #[derive(FromRow)]
    struct Row {
        id: Uuid,
        peer_id: Uuid,
        allow_peer_chat_delete: bool,
    }

    let rows = sqlx::query_as::<_, Row>(
        r#"
        SELECT c.id, p.id AS peer_id, p.allow_peer_chat_delete
        FROM conversations c
        INNER JOIN users p
            ON p.id = CASE WHEN c.user_a_id = $1 THEN c.user_b_id ELSE c.user_a_id END
        WHERE (c.user_a_id = $1 OR c.user_b_id = $1)
          AND c.user_a_id <> c.user_b_id
        ORDER BY c.id
        FOR UPDATE OF c
        "#,
    )
    .bind(user_id)
    .fetch_all(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("list chats for account delete failed: {err}")))?;
    let ids: Vec<Uuid> = rows.iter().map(|row| row.id).collect();

    // `tombstone_own_messages` without its time bound: a send already under way when the
    // deletion began stamps its own clock, which can be later than `at`.
    sqlx::query(
        r#"
        UPDATE media_objects
        SET message_id = NULL
        WHERE message_id IN (
            SELECT id FROM messages
            WHERE conversation_id = ANY($1) AND sender_user_id = $2
        )
        "#,
    )
    .bind(&ids)
    .bind(user_id)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("unlink media on account delete failed: {err}")))?;
    let tombstoned: Vec<(Uuid, Uuid)> = sqlx::query_as(
        r#"
        UPDATE messages
        SET ciphertext = NULL,
            media_object_id = NULL,
            deleted_for_everyone_at = $3
        WHERE conversation_id = ANY($1)
          AND sender_user_id = $2
          AND deleted_for_everyone_at IS NULL
        RETURNING conversation_id, id
        "#,
    )
    .bind(&ids)
    .bind(user_id)
    .bind(at)
    .fetch_all(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("tombstone sent messages failed: {err}")))?;
    let mut tombstoned_by_chat: BTreeMap<Uuid, Vec<Uuid>> = BTreeMap::new();
    for (conversation_id, message_id) in tombstoned {
        tombstoned_by_chat
            .entry(conversation_id)
            .or_default()
            .push(message_id);
    }

    let mut chats = Vec::with_capacity(rows.len());
    for row in rows {
        upsert_clear(tx, user_id, row.id, at).await?;
        if row.allow_peer_chat_delete {
            upsert_clear(tx, row.peer_id, row.id, at).await?;
        }
        purge_fully_cleared(tx, row.id).await?;
        chats.push(DeletedChat {
            conversation_id: row.id,
            peer_user_id: row.peer_id,
            cleared_for_peer: row.allow_peer_chat_delete,
        });
    }

    // Human: The users row outlives the account (migration 021), so its reactions no longer
    // cascade away. A tombstone carries none, as in `tombstone_own_messages`, and none the
    // account left stays sealed; each clear takes a new seq, so the other person's devices catch
    // up with it. After the purges, which took the reactions on what they deleted with them.
    for (conversation_id, message_ids) in &tombstoned_by_chat {
        crate::routes::reactions::clear_reactions_on(tx, *conversation_id, message_ids).await?;
    }
    crate::routes::reactions::clear_reactions_by(tx, user_id, &ids).await?;
    Ok(chats)
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
/// request must not un-hide messages the user already deleted. Reactions up to now count as
/// seen too — they sit on messages the participant can no longer see.
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
    crate::routes::reactions::mark_all_seen(tx, user_id, conversation_id).await
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

    let tombstoned: Vec<Uuid> = sqlx::query_scalar(
        r#"
        UPDATE messages
        SET ciphertext = NULL,
            media_object_id = NULL,
            deleted_for_everyone_at = COALESCE(deleted_for_everyone_at, $3)
        WHERE conversation_id = $1
          AND sender_user_id = $2
          AND created_at <= $3
          AND deleted_for_everyone_at IS NULL
        RETURNING id
        "#,
    )
    .bind(conversation_id)
    .bind(sender_user_id)
    .bind(at)
    .fetch_all(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("tombstone chat messages failed: {err}")))?;

    // A tombstone carries no reactions, sealed ones included.
    crate::routes::reactions::clear_reactions_on(tx, conversation_id, &tombstoned).await?;

    Ok(tombstoned.len() as u64)
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_unread_count_stops_at_the_cap() {
        assert!(UNREAD_MESSAGES_SQL.contains(&format!("LIMIT {UNREAD_COUNT_CAP}")));
    }
}
