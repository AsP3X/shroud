package de.corespace.shroud.core.model

import androidx.compose.runtime.Immutable
import de.corespace.shroud.core.media.files.FileCopy
import de.corespace.shroud.core.media.files.FileType
import de.corespace.shroud.core.media.files.FileTypes
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MessageReplyReference
import java.time.Instant
import java.util.UUID

// The bubble model every engine and screen shares (plan §1.7.5, messaging-core §2.1 with C1 and C8).
// Seam published by W1-INT; W2-MSG-CORE builds and merges these, W3 draws them.

/** What a bubble shows (`MessagingController.swift:188-195`). [Todo] exists only in Notes. */
enum class ChatMessageKind(val storageKey: String) {
    Text("text"),
    Image("image"),
    Voice("voice"),
    Video("video"),
    Todo("todo"),

    /** A document sent as it is (docs/file-sharing.md): `t: "file"`, its name in [ChatMessage.fileName]. */
    File("file"),
    ;

    companion object {
        fun fromStorageKey(key: String?): ChatMessageKind? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Delivery state of an outgoing bubble (`MessageBubbleView.swift:12-20`). [rank] orders them: server
 * data never lowers a receipt (messaging-core §15). Inbound messages are always [Sent].
 */
enum class ReceiptStatus(val rank: Int, val storageKey: String, val spokenLabel: String) {
    Failed(-1, "failed", "Failed"),
    Sending(0, "sending", "Sending"),
    Sent(1, "sent", "Sent"),
    Delivered(2, "delivered", "Delivered"),
    Read(3, "read", "Read"),
    ;

    companion object {
        fun fromStorageKey(key: String?): ReceiptStatus? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * One bubble (`MessagingController.ChatMessage`, `MessagingController.swift:197-374`). Threads are
 * `Map<storePeer, List<ChatMessage>>`, oldest first; Notes live under [NOTES_PEER_ID].
 *
 * Unlike iOS, full media bytes never live in the model (plan C8, messaging-core D1): [hasFullMedia]
 * says the decrypted image, voice or video (or a text message's large link image) is on this
 * device, and the bytes come on demand from `MessagingController.mediaBytes(id)` /
 * `LocalMediaCache.openReader(id)`. Only small JPEGs ride along ([posterJpeg], [previewJpeg]), so
 * value equality stays cheap (plan C9).
 *
 * Builders keep the iOS initializer's rule that inbound messages carry [ReceiptStatus.Sent]
 * (`MessagingController.swift:287`). [toString] never prints content.
 */
@Immutable
data class ChatMessage(
    /** Server id once sent; a client UUID while optimistic ([pendingSync]) or local-only. */
    val id: UUID,
    /** The thread key (Notes: [NOTES_PEER_ID]). */
    val peerUserId: UUID,
    val senderUserId: UUID,
    /**
     * Body, caption or stand-in label: "Photo", "Video", "Voice message", the transcript of a voice
     * message, "Message deleted", "[Unable to decrypt]", "Media", "[Binary message]". A file's is its
     * caption only ("" without one): the bubble names it by [fileName].
     */
    val text: String,
    /** Server time (local time while optimistic). */
    val createdAt: Instant,
    /** `created_at` exactly as the server sent it (microseconds): the history cursor. Null when optimistic. */
    val createdAtWire: String? = null,
    val isMine: Boolean,
    /** Tombstone: deleted for everyone. */
    val deleted: Boolean = false,
    val receipt: ReceiptStatus = ReceiptStatus.Sent,
    val kind: ChatMessageKind = ChatMessageKind.Text,
    /** Server media blob (image, voice, video, or a text message's large link image). */
    val mediaObjectId: UUID? = null,
    /** Pixel size of the image, video or large link image. */
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    /** iOS `imageData` / `voiceData` / `videoData` != nil: the full decrypted bytes are on this device. */
    val hasFullMedia: Boolean = false,
    /** Video poster JPEG (iOS `imageData` of a video). */
    val posterJpeg: Bytes? = null,
    /** Envelope `th` JPEG or blurred link placeholder, ≤ 6 KiB (iOS `previewData`). */
    val previewJpeg: Bytes? = null,
    /** Plaintext size from the payload `s`, for the download chip (a file's size, required on the wire). */
    val mediaByteCount: Long? = null,
    /** A file's name, cleaned (docs/file-sharing.md §5); null for every other kind. */
    val fileName: String? = null,
    /** Voice and video duration (iOS `voiceDurationMs`). */
    val durationMs: Int? = null,
    /** 0…255 per bar. */
    val voiceWaveform: Bytes? = null,
    val transcript: String? = null,
    /** Outbound failure text; the bubble stays for a retry. */
    val sendError: String? = null,
    /** Notes todo only. */
    val todoDone: Boolean? = null,
    /** Outbound and waiting for the network (the offline queue's source). */
    val pendingSync: Boolean = false,
    val replyTo: MessageReplyReference? = null,
    val linkPreview: LinkPreview? = null,
    /** Everyone's reactions; removals stay as entries with no emoji. */
    val reactions: List<MessageReaction> = emptyList(),
) {
    override fun toString(): String =
        "ChatMessage(id=$id, kind=$kind, mine=$isMine, deleted=$deleted, receipt=$receipt, pendingSync=$pendingSync)"
}

/** The bubble to draw: a deleted photo, video or voice message draws the text tombstone (`MessagingController.swift:5889-5893`). */
val ChatMessage.presentedKind: ChatMessageKind
    get() = if (deleted) ChatMessageKind.Text else kind

/**
 * A text message whose link preview carries a large image (Telegram's big layout,
 * `MessagingController.swift:313`). The sender's copy has the bytes before the upload lands; everyone
 * else knows it by the media id until it is downloaded.
 */
val ChatMessage.hasLargeLinkImage: Boolean
    get() = kind == ChatMessageKind.Text && linkPreview != null && (mediaObjectId != null || hasFullMedia)

/** The large link image is on the server but not decrypted here yet (`MessagingController.swift:318`). */
val ChatMessage.needsLinkImageDownload: Boolean
    get() = hasLargeLinkImage && !hasFullMedia && !deleted

/**
 * A photo, video or file whose full bytes are not on this device: preview + download
 * (`MessagingController.swift:323-330`). A file of an unsupported type is never downloaded
 * (docs/file-sharing.md §4).
 */
val ChatMessage.needsMediaDownload: Boolean
    get() {
        if (mediaObjectId == null || deleted) return false
        return when (kind) {
            ChatMessageKind.Image, ChatMessageKind.Video -> !hasFullMedia
            ChatMessageKind.File -> !hasFullMedia && fileType != null
            else -> false
        }
    }

/** A file message's type from its cleaned name (docs/file-sharing.md §4); null for other kinds and unsupported files. */
val ChatMessage.fileType: FileType?
    get() = if (kind == ChatMessageKind.File) fileName?.let(FileTypes::forName) else null

/**
 * What the chat list and a notification say for this message (docs/file-sharing.md §7
 * "Elsewhere"): a file's caption, else its name; every other kind's [ChatMessage.text].
 */
val ChatMessage.previewText: String
    get() = if (kind == ChatMessageKind.File) text.trim().ifEmpty { fileName ?: FileCopy.FILE } else text

/**
 * The small JPEG a bubble can draw at once (`displayPreviewData`, `MessagingController.swift:333-336`):
 * the payload thumbnail, else a video's poster. iOS returns a photo's full bytes once they are
 * loaded; here those come from `DecodedImageCache` / `mediaBytes(id)` (plan C8), so a photo
 * answers with its thumbnail only.
 */
val ChatMessage.displayPreview: Bytes?
    get() = if (kind == ChatMessageKind.Image) previewJpeg else previewJpeg ?: posterJpeg

/**
 * Whether the bubble can be quoted (`MessagingController.swift:343`): never before it reached the
 * server (the peer could not resolve a client id) and never a tombstone. Notes keep [ReceiptStatus.Sent],
 * so replying in Notes works.
 */
val ChatMessage.canBeQuoted: Boolean
    get() = !deleted && !pendingSync && receipt != ReceiptStatus.Failed && receipt != ReceiptStatus.Sending

/**
 * The quote a reply to this message carries (`MessagingController.swift:351-373`), null unless
 * [canBeQuoted]. Media bubbles keep a stand-in label in [ChatMessage.text]; the quote derives those
 * from the kind, so only a real caption is sealed. The snippet is clamped by [MessageReplyReference.invoke].
 */
val ChatMessage.replyReference: MessageReplyReference?
    get() {
        if (!canBeQuoted) return null
        val quotedKind = when (kind) {
            ChatMessageKind.Image -> MessageReplyReference.Kind.Image
            ChatMessageKind.Video -> MessageReplyReference.Kind.Video
            ChatMessageKind.Voice -> MessageReplyReference.Kind.Voice
            ChatMessageKind.File -> MessageReplyReference.Kind.File
            ChatMessageKind.Text, ChatMessageKind.Todo -> MessageReplyReference.Kind.Text
        }
        val snippet = when (kind) {
            ChatMessageKind.Image -> if (text == "Photo" || text == "Media") "" else text
            ChatMessageKind.Video -> if (text == "Video" || text == "Media") "" else text
            ChatMessageKind.Voice -> ""
            // `x` = the file name (docs/file-sharing.md §1): old builds show it as a text quote.
            ChatMessageKind.File -> fileName.orEmpty()
            ChatMessageKind.Text, ChatMessageKind.Todo -> text
        }
        return MessageReplyReference(messageId = id, senderUserId = senderUserId, kind = quotedKind, snippet = snippet)
    }
