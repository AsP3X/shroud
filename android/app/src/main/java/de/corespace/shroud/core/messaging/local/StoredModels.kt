@file:UseSerializers(UuidSerializer::class, LocalInstantSerializer::class)

package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.messaging.CachedConversation
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MessageReaction
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.UserCardDto
import de.corespace.shroud.core.net.wire.ApiTime
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

// The JSON inside the sealed message files (messaging-core §22.2; iOS
// `ios/shroud/Services/Messaging/LocalMessageStore.swift:23-189, 482-541`). Local-only, so byte
// compatibility with iOS is not needed, but the keys are iOS's (`peerUserID`, `userID`, …): rows an
// iOS test writes by hand decode here unchanged (`MessageReplyTests.swift:262-273`). Every optional
// decodes when missing (older files); an unknown kind reads as text, an unknown receipt as sent.
// No toString here prints a name, a text or a key.

/**
 * The private format of every message file: unknown keys are ignored, nulls are left out, defaults
 * (`version`) are written (messaging-core §22.2).
 */
val LocalStoreJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Dates in the message files: iOS writes ISO-8601 with milliseconds
 * (`LocalMessageStore.swift:614-631`). Android writes at least the milliseconds and every digit
 * beyond them the instant has — a server `created_at` keeps its microseconds, so a row read back
 * equals the row written and an unchanged save stays unchanged. Reads take 0–9 fraction digits,
 * `Z` or an offset ([ApiTime.parse]), like iOS's two formatters (`:593-611`).
 */
object LocalInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("de.corespace.shroud.LocalInstant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant =
        ApiTime.parse(decoder.decodeString()) ?: throw SerializationException("Invalid date")

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(format(value))

    /** `2026-09-19T10:00:00.000Z`; `…00.123456Z` when the instant has microseconds. */
    fun format(value: Instant): String {
        val text = DateTimeFormatter.ISO_INSTANT.format(value)
        return if (value.nano == 0) text.dropLast(1) + ".000Z" else text
    }
}

/**
 * One reaction entry as stored (`MessageReaction` with iOS's `CodingKeys` `userID, emojis, seq`,
 * `MessageReactions.swift:12-21`): `pending` is never written. The controller hands over settled
 * reactions (messaging-core §19.5 `settledReactions`); a pending entry that still arrives is stored
 * as it shows, like iOS.
 */
@Serializable
data class StoredReaction(
    @SerialName("userID") val userId: UUID,
    val emojis: List<String>,
    val seq: Long,
) {
    override fun toString(): String = "StoredReaction(emoji=${emojis.size}, seq=$seq)"
}

/**
 * A message row without media bytes (`LocalMessageStore.StoredMessage`,
 * `LocalMessageStore.swift:80-189`). The decrypted media lives in the media cache, the payload
 * (preview `th`, size `s`, blob key) in the plaintext cache; both are re-attached on hydrate
 * (`MessagingLocalRepository.swift:87-103`).
 *
 * [replyTo] and [linkPreview] keep their sealed wire objects (`{id,u,k,x}`, `{u,n,ti,d,th,w,h,vd,ab}`)
 * and are parsed leniently on the way back: a broken quote or preview drops itself, never the
 * thread (iOS would fail the whole file).
 */
@Serializable
data class StoredMessage(
    val id: UUID,
    @SerialName("peerUserID") val peerUserId: UUID,
    @SerialName("senderUserID") val senderUserId: UUID,
    val text: String,
    val createdAt: Instant,
    val isMine: Boolean,
    val deleted: Boolean,
    val receipt: String,
    val kind: String,
    val mediaObjectId: UUID? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val voiceDurationMs: Int? = null,
    /** 0…255 per bar (`[UInt8]`). */
    val voiceWaveform: List<Int>? = null,
    val transcript: String? = null,
    val sendError: String? = null,
    val todoDone: Boolean? = null,
    /** `true` or left out (`:131`). */
    val pendingSync: Boolean? = null,
    val replyTo: JsonObject? = null,
    val linkPreview: JsonObject? = null,
    /** Left out when there are none (`:134`). */
    val reactions: List<StoredReaction>? = null,
    /** Server `created_at` as sent; absent on rows written before history cursors. */
    val createdAtWire: String? = null,
) {
    override fun toString(): String = "StoredMessage(id=$id, kind=$kind, deleted=$deleted, receipt=$receipt)"

    /**
     * The bubble this row stores (`toChatMessage`, `LocalMessageStore.swift:139-188`).
     * [hasFullMedia] answers whether the media cache holds the decrypted bytes of a message id; it
     * is asked only for kinds that carry media: photos, voice, video, and a text message whose link
     * preview has a large image (`:145-160`).
     */
    fun toChatMessage(hasFullMedia: (UUID) -> Boolean): ChatMessage {
        val kind = ChatMessageKind.fromStorageKey(kind) ?: ChatMessageKind.Text
        val preview = linkPreview?.let(LinkPreview::parse)
        val media = when (kind) {
            ChatMessageKind.Image, ChatMessageKind.Voice, ChatMessageKind.Video -> hasFullMedia(id)
            // A link preview's large image is stored like a photo.
            ChatMessageKind.Text -> preview != null && mediaObjectId != null && hasFullMedia(id)
            ChatMessageKind.Todo -> false
        }
        return ChatMessage(
            id = id,
            peerUserId = peerUserId,
            senderUserId = senderUserId,
            text = text,
            createdAt = createdAt,
            createdAtWire = createdAtWire,
            isMine = isMine,
            deleted = deleted,
            receipt = ReceiptStatus.fromStorageKey(receipt) ?: ReceiptStatus.Sent,
            kind = kind,
            mediaObjectId = mediaObjectId,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            hasFullMedia = media,
            durationMs = voiceDurationMs,
            voiceWaveform = voiceWaveform?.let(::waveformBytes),
            transcript = transcript,
            sendError = sendError,
            todoDone = todoDone,
            pendingSync = pendingSync == true,
            replyTo = replyTo?.let(MessageReplyReference::parse),
            linkPreview = preview,
            reactions = reactions.orEmpty().map { MessageReaction(it.userId, it.emojis, it.seq) },
        )
    }

    companion object {
        /**
         * The row of [message] (`StoredMessage.from`, `LocalMessageStore.swift:111-137`): no media
         * bytes, previews or posters; `pendingSync` only when true; reactions only when there are
         * any, without the `pending` flag.
         */
        fun from(message: ChatMessage): StoredMessage = StoredMessage(
            id = message.id,
            peerUserId = message.peerUserId,
            senderUserId = message.senderUserId,
            text = message.text,
            createdAt = message.createdAt,
            isMine = message.isMine,
            deleted = message.deleted,
            receipt = message.receipt.storageKey,
            kind = message.kind.storageKey,
            mediaObjectId = message.mediaObjectId,
            imageWidth = message.imageWidth,
            imageHeight = message.imageHeight,
            voiceDurationMs = message.durationMs,
            voiceWaveform = message.voiceWaveform?.toByteArray()?.map { it.toInt() and 0xFF },
            transcript = message.transcript,
            sendError = message.sendError,
            todoDone = message.todoDone,
            pendingSync = if (message.pendingSync) true else null,
            replyTo = message.replyTo?.wireObject(),
            linkPreview = message.linkPreview?.wire(),
            reactions = message.reactions.takeIf { it.isNotEmpty() }?.map { StoredReaction(it.userId, it.emojis, it.seq) },
            createdAtWire = message.createdAtWire,
        )

        /** A stored waveform outside 0…255 (iOS would refuse the row) is dropped, the message kept. */
        private fun waveformBytes(levels: List<Int>): Bytes? {
            if (levels.any { it !in 0..255 }) return null
            return Bytes.adopt(ByteArray(levels.size) { levels[it].toByte() })
        }
    }
}

/** A chat as the roster keeps it (iOS `CachedConversation`, `LocalMessageStore.swift:53-62, 484-505`). */
@Serializable
data class StoredConversation(
    val id: UUID,
    @SerialName("peerID") val peerId: UUID,
    val peerUsername: String,
    val createdAt: Instant,
    val lastMessageAt: Instant? = null,
    /** Optional so rosters written before reactions still decode. */
    val reactionSeq: Long? = null,
    val unseenReactions: Int? = null,
) {
    fun toCached(): CachedConversation =
        CachedConversation(id, peerId, peerUsername, createdAt, lastMessageAt, reactionSeq, unseenReactions)

    override fun toString(): String = "StoredConversation(id=$id, peer=$peerId)"

    companion object {
        fun from(c: CachedConversation): StoredConversation =
            StoredConversation(c.id, c.peerId, c.peerUsername, c.createdAt, c.lastMessageAt, c.reactionSeq, c.unseenReactions)
    }
}

/** `CachedContact` (`LocalMessageStore.swift:64-68, 507-517`). */
@Serializable
data class CachedContact(val userId: UUID, val username: String, val createdAt: Instant) {
    fun toDto(): ContactItemDto = ContactItemDto(userId = userId, username = username, createdAt = createdAt)

    override fun toString(): String = "CachedContact(userId=$userId)"

    companion object {
        fun from(dto: ContactItemDto): CachedContact = CachedContact(dto.userId, dto.username, dto.createdAt)
    }
}

/**
 * `CachedContactRequest` (`LocalMessageStore.swift:70-78, 519-541`): the requester's card is kept
 * as its username and restored as `user = (fromUserId, username)`.
 */
@Serializable
data class CachedContactRequest(
    val id: UUID,
    val fromUserId: UUID,
    val toUserId: UUID,
    val status: String,
    val createdAt: Instant,
    val respondedAt: Instant? = null,
    val username: String? = null,
) {
    fun toDto(): ContactRequestDto = ContactRequestDto(
        id = id,
        fromUserId = fromUserId,
        toUserId = toUserId,
        status = status,
        createdAt = createdAt,
        respondedAt = respondedAt,
        user = username?.let { UserCardDto(id = fromUserId, username = it, shareCode = null) },
    )

    override fun toString(): String = "CachedContactRequest(id=$id, status=$status)"

    companion object {
        fun from(dto: ContactRequestDto): CachedContactRequest = CachedContactRequest(
            id = dto.id,
            fromUserId = dto.fromUserId,
            toUserId = dto.toUserId,
            status = dto.status,
            createdAt = dto.createdAt,
            respondedAt = dto.respondedAt,
            username = dto.user?.username,
        )
    }
}

/**
 * `roster.sealed` (`LocalMessageStore.Roster`, `LocalMessageStore.swift:37-44`): conversations,
 * contacts, incoming requests and the unread counts (only > 0), keyed by lower-case peer id.
 * [updatedAt] is stamped on every write and is not part of what the roster holds ([content]).
 */
@Serializable
data class Roster(
    val version: Int = 1,
    val conversations: List<StoredConversation> = emptyList(),
    val contacts: List<CachedContact> = emptyList(),
    val incomingRequests: List<CachedContactRequest> = emptyList(),
    val unreadByPeer: Map<String, Int> = emptyMap(),
    val updatedAt: Instant = Instant.EPOCH,
) {
    /** The roster without its write stamp (`comparable`, `MessagingLocalRepository.swift:302-307`). */
    fun content(): Roster = copy(updatedAt = Instant.EPOCH)

    override fun toString(): String =
        "Roster(conversations=${conversations.size}, contacts=${contacts.size}, requests=${incomingRequests.size}, unread=${unreadByPeer.size})"
}

/** `threads/<name>.sealed` (`LocalMessageStore.ThreadFile`, `:46-51`): the peer id lives inside, the name is keyed. */
@Serializable
data class ThreadFile(
    val version: Int = 1,
    @SerialName("peerID") val peerId: UUID,
    val messages: List<StoredMessage> = emptyList(),
    val updatedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String = "ThreadFile(messages=${messages.size})"
}

/** `reactions.sealed` (`LocalMessageStore.ReactionCursors`, `:268-275`): lower-case peer id → highest applied `seq`. */
@Serializable
data class ReactionCursorsFile(val version: Int = 1, val byPeer: Map<String, Long> = emptyMap())

/**
 * `annotations.sealed` — Android addition (messaging-core D5, §14.6): voice message id → the ids of
 * the transcript annotations that point at it, so purging a voice note also purges its shared
 * transcripts. Lower-case ids.
 */
@Serializable
data class AnnotationIndexFile(val version: Int = 1, val byTarget: Map<String, List<String>> = emptyMap())
