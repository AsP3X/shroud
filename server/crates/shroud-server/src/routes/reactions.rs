//! Message reactions: one sealed record per (message, user).
//!
//! Human: The server stores and relays an opaque blob per reaction; the emoji lives inside it.
//! Setting, replacing and removing all take the conversation's next `seq`, and a removal keeps
//! its row (ciphertext NULL) so an offline device can catch up with
//! `GET /conversations/{peer}/reactions?after_seq=`.
//!
//! A record holds the person's whole set, so a write names the `seq` it was built on
//! (`base_seq`): a device that missed its other device's change gets `409 REACTION_CHANGED`
//! with the record as it is now, merges onto it and retries, instead of overwriting it.
//!
//! Everything that writes a chat's messages, reaction counter, reaction rows or seen marks takes
//! its conversation row first: reaction writes and deleting a message for everyone
//! `FOR KEY SHARE` (then the message row, the counter, reaction rows), marking reactions seen
//! `FOR KEY SHARE` (then its seen row); deleting a chat `FOR UPDATE`; deleting an account every
//! chat of it `FOR UPDATE`, in id order, before its messages. So chat and account deletes run
//! alone, the rest meet at the message row or the counter in that order, and a reaction never
//! lands on a message deleted a moment earlier. A new reaction holds its device row before all
//! of that, as a send does, so one racing its account's deletion is either cleared with the
//! rest or refused.
//! Agent: WRITES message_reactions, conversation_reaction_seqs, reaction_reads (migration 020);
//! READS messages, conversations, conversation_clears, message_hides; PUBLISHES WS
//! `message.reaction` (the reactor's other devices, and the other participant while they can
//! see the message) and `reactions.seen` (the caller's other devices). No push and no
//! `last_message_at` bump, like annotations.

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
use crate::error::{AppError, ErrorDetail};
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
    /// `seq` of the caller's record this set was built on; 0 when they had none or had taken
    /// it back. Omitted: overwrite whatever is there.
    pub base_seq: Option<i64>,
    /// Whether the set has an emoji the base lacked. Only such a change is new for the
    /// message's author (the chat list's heart badge); taking one back is not. Default true.
    pub added: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct DeleteReactionQuery {
    /// As for `PUT`: the `seq` of the record being taken back.
    pub base_seq: Option<i64>,
}

/// `409 REACTION_CHANGED`: the caller's record moved past `base_seq` (another of their
/// devices wrote it). `current` is the record now, a removal included; merge onto it and retry
/// with its `seq`.
#[derive(Debug, Serialize)]
pub struct ReactionConflict {
    pub error: ErrorDetail,
    pub current: ReactionEntry,
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

#[derive(Debug, Deserialize)]
pub struct MarkReactionsSeenRequest {
    /// Highest reaction `seq` the caller has shown; clamped to the conversation's latest.
    pub up_to_seq: i64,
}

#[derive(Debug, Serialize)]
pub struct MarkReactionsSeenResponse {
    pub seen_seq: i64,
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

/// A written record plus whether the write counts as new for the message's author.
#[derive(Debug, FromRow)]
struct WrittenRow {
    message_id: Uuid,
    user_id: Uuid,
    ciphertext: Option<Vec<u8>>,
    seq: i64,
    updated_at: DateTime<Utc>,
    added: bool,
}

impl WrittenRow {
    fn into_parts(self) -> (ReactionEntry, bool) {
        let added = self.added;
        let entry = ReactionEntry::from(ReactionRow {
            message_id: self.message_id,
            user_id: self.user_id,
            ciphertext: self.ciphertext,
            seq: self.seq,
            updated_at: self.updated_at,
        });
        (entry, added)
    }
}

#[derive(Debug, FromRow)]
struct ReactionTarget {
    conversation_id: Uuid,
    sender_user_id: Uuid,
    content_type: String,
    deleted_for_everyone_at: Option<DateTime<Utc>>,
    user_a_id: Uuid,
    user_b_id: Uuid,
}

/// Whether each participant can still see the message: not deleted for them, not before their
/// chat clear.
#[derive(Debug, FromRow)]
struct Visibility {
    caller: bool,
    /// While false the other participant gets no events for it (catch-up masks it too).
    peer: bool,
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
) -> Result<Response, AppError> {
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

    let mut tx = begin(&state).await?;
    lock_device(&mut tx, auth.device_id).await?;
    lock_conversation(&mut tx, target.conversation_id).await?;
    // Re-checked under the lock: a message deleted after `load_target` looked is not found.
    if !lock_message(&mut tx, message_id, true).await? {
        return Err(AppError::not_found("Message not found."));
    }
    // A stale tab must not leave a reaction its owner can neither see nor take back.
    let visible = visibility(
        &mut tx,
        message_id,
        auth.user_id,
        target.peer_of(auth.user_id),
    )
    .await?;
    if !visible.caller {
        return Err(AppError::not_found("Message not found."));
    }
    let seq = next_seq(&mut tx, target.conversation_id).await?;
    // Human: `base_seq` 0 also matches a removal: the caller had nothing, and nothing is there.
    // Replacing a removal always adds; otherwise the client says whether the set grew.
    // Agent: a failed `ON CONFLICT … WHERE` returns no row but still locks the existing one.
    let written = sqlx::query_as::<_, WrittenRow>(
        r#"
        INSERT INTO message_reactions
            (message_id, user_id, conversation_id, message_sender_id, ciphertext, seq, added_seq)
        VALUES ($1, $2, $3, $4, $5, $6, $6)
        ON CONFLICT (message_id, user_id) DO UPDATE
        SET ciphertext = EXCLUDED.ciphertext,
            seq = EXCLUDED.seq,
            added_seq = CASE
                WHEN $8 OR message_reactions.ciphertext IS NULL THEN EXCLUDED.seq
                ELSE message_reactions.added_seq
            END,
            updated_at = now()
        WHERE $7::bigint IS NULL
           OR message_reactions.seq = $7
           OR ($7 = 0 AND message_reactions.ciphertext IS NULL)
        RETURNING message_id, user_id, ciphertext, seq, updated_at, added_seq = seq AS added
        "#,
    )
    .bind(message_id)
    .bind(auth.user_id)
    .bind(target.conversation_id)
    .bind(target.sender_user_id)
    .bind(&ciphertext)
    .bind(seq)
    .bind(body.base_seq)
    .bind(body.added.unwrap_or(true))
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("upsert reaction failed: {err}")))?;

    let Some(written) = written else {
        let current = current_record(&mut tx, message_id, auth.user_id).await?;
        rollback(tx).await?;
        return Ok(changed_elsewhere(current));
    };
    commit(tx).await?;

    let (entry, added) = written.into_parts();
    publish(&state, &auth, &target, &entry, added, visible.peer).await;
    // Someone else's emoji on the peer's own message is news for the peer. Taking one back,
    // or reacting to our own message, is not.
    let peer = target.peer_of(auth.user_id);
    if added && visible.peer && target.sender_user_id == peer && peer != auth.user_id {
        state
            .push
            .dispatch(crate::push::PushEvent::Reaction {
                recipient: peer,
                reactor: auth.user_id,
                conversation_id: target.conversation_id,
                message_id,
            })
            .await;
    }

    tracing::debug!(
        message_id = %message_id,
        user_id = %auth.user_id,
        seq = entry.seq,
        added,
        ciphertext_bytes = ciphertext.len(),
        "reactions.put ok"
    );
    Ok(Json(entry).into_response())
}

/// `DELETE /messages/:id/reaction?base_seq=` — remove the caller's reaction. `204` when there
/// is none (any more).
pub async fn delete_reaction(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(message_id): Path<Uuid>,
    Query(query): Query<DeleteReactionQuery>,
) -> Result<Response, AppError> {
    check_budget(&state, &auth).await?;
    let target = load_target_for_removal(&state, &auth, message_id).await?;

    let mut tx = begin(&state).await?;
    lock_conversation(&mut tx, target.conversation_id).await?;
    if !lock_message(&mut tx, message_id, false).await? {
        return Ok(StatusCode::NO_CONTENT.into_response());
    }
    let visible = visibility(
        &mut tx,
        message_id,
        auth.user_id,
        target.peer_of(auth.user_id),
    )
    .await?;
    let seq = next_seq(&mut tx, target.conversation_id).await?;
    let row = sqlx::query_as::<_, ReactionRow>(
        r#"
        UPDATE message_reactions
        SET ciphertext = NULL,
            seq = $3,
            updated_at = now()
        WHERE message_id = $1 AND user_id = $2 AND ciphertext IS NOT NULL
          AND ($4::bigint IS NULL OR seq = $4)
        RETURNING message_id, user_id, ciphertext, seq, updated_at
        "#,
    )
    .bind(message_id)
    .bind(auth.user_id)
    .bind(seq)
    .bind(query.base_seq)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("remove reaction failed: {err}")))?;

    // Rolling back also returns the counter's number. Nothing there (or only a removal) is
    // what the caller asked for; a live record they did not build on is not theirs to drop.
    let Some(row) = row else {
        let current = current_record(&mut tx, message_id, auth.user_id).await?;
        rollback(tx).await?;
        return Ok(match current {
            Some(current) if current.ciphertext.is_some() => changed_elsewhere(Some(current)),
            _ => StatusCode::NO_CONTENT.into_response(),
        });
    };
    commit(tx).await?;
    let entry = ReactionEntry::from(row);
    publish(&state, &auth, &target, &entry, false, visible.peer).await;

    tracing::debug!(
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

/// The conversation's latest reaction `seq`, 0 when there is none. Every change up to it is
/// committed, so a client without a catch-up cursor can start from the value read *before*
/// its first history page.
pub(crate) async fn latest_reaction_seq<'e>(
    executor: impl sqlx::PgExecutor<'e>,
    conversation_id: Uuid,
) -> Result<i64, AppError> {
    let seq: Option<i64> = sqlx::query_scalar(
        r#"SELECT seq FROM conversation_reaction_seqs WHERE conversation_id = $1"#,
    )
    .bind(conversation_id)
    .fetch_optional(executor)
    .await
    .map_err(|err| AppError::Internal(format!("latest reaction seq failed: {err}")))?;
    Ok(seq.unwrap_or(0))
}

/// Clears every live reaction on messages deleted for everyone, each with its own new `seq` so
/// devices catching up drop them too — and so no sealed reaction outlives its message.
/// Agent: CALLED inside messages::delete_for_everyone (message row held `FOR UPDATE`),
/// conversations::tombstone_own_messages (conversation row held `FOR UPDATE`) and
/// conversations::delete_chats_for_both (every chat of the account held `FOR UPDATE`): a
/// reaction write waits on any of those locks, so the rows counted here can't change before
/// they are updated.
pub(crate) async fn clear_reactions_on(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    conversation_id: Uuid,
    message_ids: &[Uuid],
) -> Result<(), AppError> {
    if message_ids.is_empty() {
        return Ok(());
    }
    let live: i64 = sqlx::query_scalar(
        r#"
        SELECT COUNT(*) FROM message_reactions
        WHERE message_id = ANY($1) AND ciphertext IS NOT NULL
        "#,
    )
    .bind(message_ids)
    .fetch_one(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("count reactions on delete failed: {err}")))?;
    if live == 0 {
        return Ok(());
    }
    let base = reserve_seqs(tx, conversation_id, live).await?;
    sqlx::query(
        r#"
        WITH live AS (
            SELECT message_id, user_id,
                   row_number() OVER (ORDER BY message_id, user_id) AS n
            FROM message_reactions
            WHERE message_id = ANY($1) AND ciphertext IS NOT NULL
        )
        UPDATE message_reactions r
        SET ciphertext = NULL, seq = $2 + live.n, updated_at = now()
        FROM live
        WHERE r.message_id = live.message_id AND r.user_id = live.user_id
        "#,
    )
    .bind(message_ids)
    .bind(base)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("clear reactions on delete failed: {err}")))?;
    Ok(())
}

/// Clears every live reaction `user_id` left in `conversation_ids`, each with its own new `seq`
/// so the other person's devices drop them too. For account deletion (the users row outlives
/// the account, migration 021, so its reactions no longer cascade away with it) and for
/// "delete chat for both" where the peer keeps the chat (nothing of ours stays readable).
/// Agent: CALLED by conversations::delete_chats_for_both and conversations::delete_conversation,
/// which hold every one of `conversation_ids` `FOR UPDATE`; finds the rows by
/// message_reactions_user_idx.
pub(crate) async fn clear_reactions_by(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
    conversation_ids: &[Uuid],
) -> Result<(), AppError> {
    #[derive(FromRow)]
    struct Live {
        conversation_id: Uuid,
        count: i64,
    }
    let chats = sqlx::query_as::<_, Live>(
        r#"
        SELECT conversation_id, COUNT(*) AS count
        FROM message_reactions
        WHERE user_id = $1 AND conversation_id = ANY($2) AND ciphertext IS NOT NULL
        GROUP BY conversation_id
        ORDER BY conversation_id
        "#,
    )
    .bind(user_id)
    .bind(conversation_ids)
    .fetch_all(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("count account reactions failed: {err}")))?;
    for chat in chats {
        let base = reserve_seqs(tx, chat.conversation_id, chat.count).await?;
        sqlx::query(
            r#"
            WITH live AS (
                SELECT message_id, row_number() OVER (ORDER BY message_id) AS n
                FROM message_reactions
                WHERE conversation_id = $1 AND user_id = $2 AND ciphertext IS NOT NULL
            )
            UPDATE message_reactions r
            SET ciphertext = NULL, seq = $3 + live.n, updated_at = now()
            FROM live
            WHERE r.message_id = live.message_id AND r.user_id = $2
            "#,
        )
        .bind(chat.conversation_id)
        .bind(user_id)
        .bind(base)
        .execute(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("clear account reactions failed: {err}")))?;
    }
    Ok(())
}

/// `POST /conversations/:peer_user_id/reactions/seen` — the caller has seen reactions to their
/// messages up to `up_to_seq`. Never moves backwards; the caller's other devices are told so
/// their chat-list badge clears too.
pub async fn mark_reactions_seen(
    State(state): State<AppState>,
    auth: AuthContext,
    Path(peer_user_id): Path<Uuid>,
    Json(body): Json<MarkReactionsSeenRequest>,
) -> Result<Json<MarkReactionsSeenResponse>, AppError> {
    let Some(conversation_id) = find_conversation(&state.pool, auth.user_id, peer_user_id).await?
    else {
        return Ok(Json(MarkReactionsSeenResponse { seen_seq: 0 }));
    };
    // Human: The conversation row first, like every reaction writer: a first "seen" inserts its
    // row, whose key check would otherwise wait on a chat delete that is itself waiting on that
    // row (`mark_all_seen`) — a deadlock.
    let mut tx = begin(&state).await?;
    let exists: Option<i32> =
        sqlx::query_scalar(r#"SELECT 1 FROM conversations WHERE id = $1 FOR KEY SHARE"#)
            .bind(conversation_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(|err| AppError::Internal(format!("lock seen conversation failed: {err}")))?;
    if exists.is_none() {
        return Ok(Json(MarkReactionsSeenResponse { seen_seq: 0 }));
    }
    let latest = latest_reaction_seq(&mut *tx, conversation_id).await?;
    let up_to = body.up_to_seq.clamp(0, latest);

    #[derive(FromRow)]
    struct Seen {
        seen_seq: i64,
        moved: bool,
    }
    // `moved` compares with the row as it was before this statement (a CTE snapshot).
    let seen = sqlx::query_as::<_, Seen>(
        r#"
        WITH before AS (
            SELECT seen_seq FROM reaction_reads WHERE user_id = $1 AND conversation_id = $2
        ), upsert AS (
            INSERT INTO reaction_reads (user_id, conversation_id, seen_seq)
            VALUES ($1, $2, $3)
            ON CONFLICT (user_id, conversation_id)
            DO UPDATE SET seen_seq = GREATEST(reaction_reads.seen_seq, EXCLUDED.seen_seq)
            RETURNING seen_seq
        )
        SELECT upsert.seen_seq,
               upsert.seen_seq > COALESCE((SELECT seen_seq FROM before), 0) AS moved
        FROM upsert
        "#,
    )
    .bind(auth.user_id)
    .bind(conversation_id)
    .bind(up_to)
    .fetch_one(&mut *tx)
    .await
    .map_err(|err| AppError::Internal(format!("mark reactions seen failed: {err}")))?;
    commit(tx).await?;

    if seen.moved {
        let event = serde_json::json!({
            "type": "reactions.seen",
            "conversation_id": conversation_id,
            "peer_user_id": peer_user_id,
            "seen_seq": seen.seen_seq,
        });
        if let Ok(payload) = serde_json::to_string(&event) {
            state
                .realtime
                .publish_to_users([auth.user_id], Some(auth.device_id), &payload)
                .await;
        }
    }
    Ok(Json(MarkReactionsSeenResponse {
        seen_seq: seen.seen_seq,
    }))
}

/// First lock of a new reaction, as of a send (`messages::send_message`): account deletion
/// revokes the account's devices before it clears the reactions in each chat, and the users row
/// outlives it (migration 021), so no foreign key stops a late write any more. Holding the
/// device row until commit does: a racing reaction either commits first and is cleared with the
/// rest, or finds the device revoked (`401`).
/// Agent: SELECT devices FOR SHARE; waits behind devices::revoke_device's UPDATE.
async fn lock_device(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    device_id: Uuid,
) -> Result<(), AppError> {
    let live: Option<Uuid> = sqlx::query_scalar(
        r#"SELECT id FROM devices WHERE id = $1 AND revoked_at IS NULL FOR SHARE"#,
    )
    .bind(device_id)
    .fetch_optional(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("lock reaction device failed: {err}")))?;
    live.map(|_| ()).ok_or_else(AppError::unauthorized)
}

/// First lock in the chat of every reaction write and of deleting a message for everyone: waits
/// out a whole-chat delete in flight (it holds the row `FOR UPDATE`) without blocking sends,
/// which only take `FOR NO KEY UPDATE`.
pub(crate) async fn lock_conversation(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    conversation_id: Uuid,
) -> Result<(), AppError> {
    sqlx::query(r#"SELECT 1 FROM conversations WHERE id = $1 FOR KEY SHARE"#)
        .bind(conversation_id)
        .execute(&mut **tx)
        .await
        .map_err(|err| AppError::Internal(format!("lock reaction conversation failed: {err}")))?;
    Ok(())
}

async fn begin(state: &AppState) -> Result<sqlx::Transaction<'static, sqlx::Postgres>, AppError> {
    state
        .pool
        .begin()
        .await
        .map_err(|err| AppError::Internal(format!("begin reaction transaction failed: {err}")))
}

async fn commit(tx: sqlx::Transaction<'_, sqlx::Postgres>) -> Result<(), AppError> {
    tx.commit()
        .await
        .map_err(|err| AppError::Internal(format!("commit reaction failed: {err}")))
}

async fn rollback(tx: sqlx::Transaction<'_, sqlx::Postgres>) -> Result<(), AppError> {
    tx.rollback()
        .await
        .map_err(|err| AppError::Internal(format!("rollback reaction failed: {err}")))
}

/// The caller's record as it is now, removal included; `None` when they never had one.
async fn current_record(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    message_id: Uuid,
    user_id: Uuid,
) -> Result<Option<ReactionEntry>, AppError> {
    let row = sqlx::query_as::<_, ReactionRow>(
        r#"
        SELECT message_id, user_id, ciphertext, seq, updated_at
        FROM message_reactions
        WHERE message_id = $1 AND user_id = $2
        "#,
    )
    .bind(message_id)
    .bind(user_id)
    .fetch_optional(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("load current reaction failed: {err}")))?;
    Ok(row.map(ReactionEntry::from))
}

fn changed_elsewhere(current: Option<ReactionEntry>) -> Response {
    // A failed compare always has a row to compare with; `None` would mean it vanished
    // under our locks, which a retry sorts out.
    let Some(current) = current else {
        return AppError::conflict("REACTION_CHANGED", "Try again.").into_response();
    };
    let body = ReactionConflict {
        error: ErrorDetail {
            code: "REACTION_CHANGED".into(),
            message: "Your reaction changed on another device.".into(),
        },
        current,
    };
    (StatusCode::CONFLICT, Json(body)).into_response()
}

/// Everything in the chat so far counts as seen by `user_id`: what they cleared is gone, so
/// nothing there is left to see, and their unseen count starts from an empty range again.
/// Agent: CALLED by conversations::upsert_clear with the conversation row held `FOR UPDATE`,
/// so no reaction write lands between the clear and this read of the counter.
pub(crate) async fn mark_all_seen(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
    conversation_id: Uuid,
) -> Result<(), AppError> {
    sqlx::query(
        r#"
        INSERT INTO reaction_reads (user_id, conversation_id, seen_seq)
        SELECT $1, conversation_id, seq FROM conversation_reaction_seqs
        WHERE conversation_id = $2
        ON CONFLICT (user_id, conversation_id)
        DO UPDATE SET seen_seq = GREATEST(reaction_reads.seen_seq, EXCLUDED.seen_seq)
        "#,
    )
    .bind(user_id)
    .bind(conversation_id)
    .execute(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("mark cleared reactions seen failed: {err}")))?;
    Ok(())
}

/// Second lock of every reaction write. `FOR KEY SHARE` waits for a `delete_for_everyone` in
/// flight (it holds the row `FOR UPDATE`) and then re-reads the row, so `live_only` sees the
/// delete. False when the message is gone (or deleted, with `live_only`).
async fn lock_message(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    message_id: Uuid,
    live_only: bool,
) -> Result<bool, AppError> {
    let found: Option<Uuid> = sqlx::query_scalar(
        r#"
        SELECT id FROM messages
        WHERE id = $1 AND (NOT $2 OR deleted_for_everyone_at IS NULL)
        FOR KEY SHARE
        "#,
    )
    .bind(message_id)
    .bind(live_only)
    .fetch_optional(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("lock reaction message failed: {err}")))?;
    Ok(found.is_some())
}

/// The conversation's next change number. The counter row stays locked until the transaction
/// ends, which is what makes seq order commit order within a conversation (migration 020).
async fn next_seq(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    conversation_id: Uuid,
) -> Result<i64, AppError> {
    sqlx::query_scalar(
        r#"
        INSERT INTO conversation_reaction_seqs (conversation_id, seq)
        VALUES ($1, 1)
        ON CONFLICT (conversation_id)
        DO UPDATE SET seq = conversation_reaction_seqs.seq + 1
        RETURNING seq
        "#,
    )
    .bind(conversation_id)
    .fetch_one(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("next reaction seq failed: {err}")))
}

/// Takes `count` numbers from the conversation's counter in one step and returns the one just
/// before them: rows cleared together get `base + 1 ..= base + count`, since catch-up pages by
/// seq and two rows must never share one.
async fn reserve_seqs(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    conversation_id: Uuid,
    count: i64,
) -> Result<i64, AppError> {
    let end: i64 = sqlx::query_scalar(
        r#"
        INSERT INTO conversation_reaction_seqs (conversation_id, seq)
        VALUES ($1, $2)
        ON CONFLICT (conversation_id)
        DO UPDATE SET seq = conversation_reaction_seqs.seq + EXCLUDED.seq
        RETURNING seq
        "#,
    )
    .bind(conversation_id)
    .bind(count)
    .fetch_one(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("reserve reaction seqs failed: {err}")))?;
    Ok(end - count)
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
        SELECT m.conversation_id, m.sender_user_id, m.content_type, m.deleted_for_everyone_at,
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

/// Target for a new reaction: a live, non-annotation message in a conversation the caller may
/// still message. Whether the caller can still see it is checked under the locks (`visibility`).
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

/// Who can still see the message, read under the conversation and message locks: a chat clear
/// (which holds the conversation `FOR UPDATE`) can't land between this and the write. A hide
/// still can, which is no worse than reacting and then deleting the message for yourself.
async fn visibility(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    message_id: Uuid,
    caller: Uuid,
    peer: Uuid,
) -> Result<Visibility, AppError> {
    sqlx::query_as::<_, Visibility>(
        r#"
        SELECT
            NOT EXISTS (
                SELECT 1 FROM message_hides h WHERE h.message_id = m.id AND h.user_id = $2
            ) AND NOT EXISTS (
                SELECT 1 FROM conversation_clears cc
                WHERE cc.conversation_id = m.conversation_id AND cc.user_id = $2
                  AND m.created_at <= cc.cleared_at
            ) AS caller,
            NOT EXISTS (
                SELECT 1 FROM message_hides h WHERE h.message_id = m.id AND h.user_id = $3
            ) AND NOT EXISTS (
                SELECT 1 FROM conversation_clears cc
                WHERE cc.conversation_id = m.conversation_id AND cc.user_id = $3
                  AND m.created_at <= cc.cleared_at
            ) AS peer
        FROM messages m
        WHERE m.id = $1
        "#,
    )
    .bind(message_id)
    .bind(caller)
    .bind(peer)
    .fetch_one(&mut **tx)
    .await
    .map_err(|err| AppError::Internal(format!("load reaction visibility failed: {err}")))
}

async fn publish(
    state: &AppState,
    auth: &AuthContext,
    target: &ReactionTarget,
    entry: &ReactionEntry,
    added: bool,
    peer_sees_message: bool,
) {
    // `message_sender_id` lets a client tell a reaction to its own message (a chat-list badge)
    // from one to the other side's, without holding the message; `added` whether it is news.
    let event = serde_json::json!({
        "type": "message.reaction",
        "conversation_id": target.conversation_id,
        "message_sender_id": target.sender_user_id,
        "device_id": auth.device_id,
        "added": added,
        "reaction": entry,
    });
    // Catch-up hides a message's reactions from someone who hid or cleared it; so does this.
    let peer = target.peer_of(auth.user_id);
    let mut audience = vec![auth.user_id];
    if peer != auth.user_id && peer_sees_message {
        audience.push(peer);
    }
    if let Ok(payload) = serde_json::to_string(&event) {
        state
            .realtime
            .publish_to_users(audience, Some(auth.device_id), &payload)
            .await;
    }
}
