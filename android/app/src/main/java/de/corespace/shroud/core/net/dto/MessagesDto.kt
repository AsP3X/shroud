@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.ApiTime
import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

// Messages — iOS `Services/API/MessageModels.swift:3-171`; server `routes/messages.rs`.
// api-realtime §5.6.

/** The values of `content_type` (`SendMessageRequest.contentType`, `MessageModels.swift:21`). */
object ContentType {
    /** A sealed text message (the envelope holds a `MessageTextPayload` or plain text). */
    const val TEXT = "text"

    /** A sealed `MediaMessagePayload`; `media_object_id` names the uploaded blob. */
    const val MEDIA = "media"

    /** A sealed `MessageAnnotation` (a transcript) about another message; never a bubble. */
    const val ANNOTATION = "annotation"
}

/** `POST /messages` (`MessageModels.swift:3-31`). */
@Serializable
data class SendMessageRequest(
    @SerialName("peer_user_id") val peerUserId: UUID,
    /**
     * Idempotency key: the same one on every retry of one send, so a retry after a lost answer
     * does not post the message twice (`MessageModels.swift:18-30`). Never the message's id —
     * the server mints that (memory: *Server re-keys sent messages*).
     */
    @SerialName("client_message_id") val clientMessageId: UUID,
    /** One of [ContentType]. */
    @SerialName("content_type") val contentType: String = ContentType.TEXT,
    /** Standard Base64 of the sealed envelope JSON. */
    val ciphertext: String,
    @SerialName("media_object_id") val mediaObjectId: UUID? = null,
)

/**
 * One message as the server returns it — `POST /messages`, history pages and the `message.new`
 * socket event (`MessageModels.swift:33-70`, decoder `:145-171`).
 *
 * [createdAtWire] keeps the server's text verbatim: history cursors must echo its microseconds,
 * a re-formatted date skips the rest of that millisecond (`:44-47`, `HistoryCursorTests`).
 * [createdAt] is parsed from it once; an unparsable date fails the whole decode as on iOS
 * (`:157-164`).
 */
@Serializable
data class MessageDto(
    /** Server-minted; never the client id. */
    val id: UUID,
    @SerialName("conversation_id") val conversationId: UUID,
    @SerialName("sender_user_id") val senderUserId: UUID,
    @SerialName("sender_device_id") val senderDeviceId: UUID,
    @SerialName("client_message_id") val clientMessageId: UUID,
    /** One of [ContentType]; unknown values are kept as sent. */
    @SerialName("content_type") val contentType: String,
    /** Null once the message was deleted for everyone. */
    val ciphertext: String? = null,
    @SerialName("media_object_id") val mediaObjectId: UUID? = null,
    @SerialName("deleted_for_everyone") val deletedForEveryone: Boolean,
    /** The server's `created_at` text, verbatim: the history cursor. */
    @SerialName("created_at") val createdAtWire: String,
    /** Our own messages only: a device of the peer has it. */
    val delivered: Boolean? = null,
    /** Our own messages only: the peer read it. */
    val read: Boolean? = null,
    /** History pages only: the live sealed reactions (absent when there are none). */
    val reactions: List<ReactionDto>? = null,
) {
    /** [createdAtWire] parsed once (all its digits kept). */
    @Transient
    val createdAt: Instant = ApiTime.parse(createdAtWire)
        ?: throw SerializationException("Invalid ISO-8601 date: $createdAtWire")
}

/** `GET /messages?peer_user_id=…` (`MessageModels.swift:128-143`). */
@Serializable
data class ListMessagesResponse(
    @SerialName("conversation_id") val conversationId: UUID? = null,
    /** Newest first, as the server pages them. */
    val messages: List<MessageDto>,
    /** False (or absent, which means the same) when no older page exists. */
    @SerialName("has_more") val hasMore: Boolean? = null,
    /** The chat's highest reaction `seq` as the page was read; null from servers without reactions. */
    @SerialName("reaction_seq") val reactionSeq: Long? = null,
)

/** `scope` on `DELETE /messages/{id}` (`MessageModels.swift:72-78`). */
enum class MessageDeleteScope(val wire: String) {
    /** Hides the message for this account only; the peer keeps their copy. */
    Me("me"),

    /** Tombstones it for both sides; the server refuses this from anyone but the sender. */
    Everyone("everyone"),
}

/** `scope` on `DELETE /conversations/{peer_user_id}` — the whole chat (`MessageModels.swift:80-87`). */
enum class ConversationDeleteScope(val wire: String) {
    /** Clears the chat for this account only. */
    Me("me"),

    /** Clears it here and unsends our messages on the peer's side; the contact stays. */
    Everyone("everyone"),
}

/** `DELETE /conversations/{peer_user_id}` (`MessageModels.swift:89-106`). */
@Serializable
data class DeleteConversationResponse(
    /** False only when there was no server-side conversation to clear. */
    @SerialName("cleared_for_me") val clearedForMe: Boolean,
    /** `everyone` scope: the peer consented, so their copy is gone as well. */
    @SerialName("cleared_for_peer") val clearedForPeer: Boolean,
    /** `everyone` scope: how many of our messages became "Message deleted" for the peer. */
    val tombstoned: Long,
    /** Always false today (deleting a chat keeps the contact); kept so older answers decode. */
    @SerialName("contact_removed") val contactRemoved: Boolean = false,
)

/** `POST /messages/read` — everything up to one message (`MessageModels.swift:108-116`). */
@Serializable
data class MarkReadBulkBody(
    @SerialName("peer_user_id") val peerUserId: UUID,
    @SerialName("up_to_message_id") val upToMessageId: UUID,
)

/** `MessageModels.swift:118-126`. */
@Serializable
data class MarkReadBulkResponse(
    val marked: Long,
    @SerialName("read_at") val readAt: Instant,
)
