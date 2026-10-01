@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

// Reactions — iOS `Services/API/MessageReaction.swift:62-122`, `MessageModels.swift:204-218`,
// `MessagesService.swift:76-129`; server `routes/reactions.rs`. api-realtime §5.8.

/**
 * One account's reaction record on one message, as the server returns it — history pages, the
 * `message.reaction` socket event and the catch-up feed (`MessageReaction.swift:62-77`).
 */
@Serializable
data class ReactionDto(
    @SerialName("message_id") val messageId: UUID,
    @SerialName("user_id") val userId: UUID,
    /** Standard Base64 of the sealed `MessageReactionPayload`; null for a removed reaction. */
    val ciphertext: String? = null,
    /** Per-chat change counter; records apply in `seq` order. */
    val seq: Long,
    @SerialName("updated_at") val updatedAt: Instant,
)

/** `PUT /messages/{id}/reaction` (`MessageReaction.swift:93-106`). */
@Serializable
data class PutReactionBody(
    /** Standard Base64 of the sealed whole set. */
    val ciphertext: String,
    /** `seq` of our record the set was built on; 0 when we had none. */
    @SerialName("base_seq") val baseSeq: Long,
    /** The set has an emoji the base lacked (news for the message's author). */
    val added: Boolean,
)

/**
 * `409 REACTION_CHANGED`: another device of ours wrote the record first; [current] is it now
 * (`MessageReaction.swift:108-110`). The error envelope beside it is ignored.
 */
@Serializable
data class ReactionConflictDto(val current: ReactionDto)

/** `GET /conversations/{peer}/reactions?after_seq=` — the catch-up feed (`MessageReaction.swift:112-122`). */
@Serializable
data class ReactionChangesResponse(
    val reactions: List<ReactionDto>,
    @SerialName("next_seq") val nextSeq: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

/** `POST /conversations/{peer}/reactions/seen` (`MessageModels.swift:204-210`). */
@Serializable
data class MarkReactionsSeenBody(@SerialName("up_to_seq") val upToSeq: Long)

/** `MessageModels.swift:212-218`. */
@Serializable
data class MarkReactionsSeenResponse(@SerialName("seen_seq") val seenSeq: Long)

/**
 * What a reaction write did (`MessagesService.ReactionWriteResult`, `MessagesService.swift:76-83`);
 * built from the HTTP answer by [from].
 */
sealed interface ReactionWriteResult {
    /** Written; null for a removal that found nothing to remove (204). */
    data class Saved(val reaction: ReactionDto?) : ReactionWriteResult

    /**
     * Our record moved past `base_seq` on another device of ours: nothing was written, and this
     * is the record now (a removal included).
     */
    data class ChangedElsewhere(val current: ReactionDto) : ReactionWriteResult

    companion object {
        /**
         * A reaction write's answer (`MessagesService.reactionWrite`, `MessagesService.swift:119-129`;
         * `MessageReactionTests.reactionWriteAnswersDecode`): a `409` whose body carries a decodable
         * `current` → [ChangedElsewhere]; any other non-2xx — a `409` without `current` included —
         * throws [ApiError.from]; a `204` or empty body → [Saved] with null; otherwise the decoded
         * record, or [ApiError.Decoding] when it does not decode. [json] must ignore unknown keys:
         * the conflict body carries the error envelope beside `current`.
         */
        fun from(status: Int, body: String, json: Json): ReactionWriteResult {
            if (status == 409) {
                val conflict = try {
                    json.decodeFromString(ReactionConflictDto.serializer(), body)
                } catch (_: IllegalArgumentException) {
                    // SerializationException is an IllegalArgumentException.
                    null
                }
                if (conflict != null) return ChangedElsewhere(conflict.current)
            }
            if (status !in 200..299) throw ApiError.from(status, body)
            if (status == 204 || body.isBlank()) return Saved(null)
            return try {
                Saved(json.decodeFromString(ReactionDto.serializer(), body))
            } catch (e: IllegalArgumentException) {
                throw ApiError.Decoding(e.message ?: "decode")
            }
        }
    }
}
