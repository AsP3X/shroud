package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind

/**
 * The two merge rules the store needs on its own (iOS `ThreadMessageMerge`,
 * `ios/shroud/Services/Messaging/ThreadMessageMerge.swift`), kept here so the store does not depend
 * on the engine package (W2-MSG-CORE owns `core/messaging/ThreadMessageMerge.kt`). Both copies must
 * stay equal: `LocalTombstonesTest` pins these rules with the iOS vectors.
 */
object LocalTombstones {
    /** The stand-in texts of a message this device could not read (`ThreadMessageMerge.swift:5-7`). */
    fun isFailedDecryptText(text: String): Boolean =
        text == "[Unable to decrypt]" || text == "Media" || text == "[Binary message]"

    /**
     * What a message deleted for everyone leaves (`tombstone(of:)`, `ThreadMessageMerge.swift:114-133`):
     * who sent it, when, its receipt and which kind it was (photo, video and voice keep their kind,
     * anything else becomes text). Nothing it said remains — no caption, quote, preview, media,
     * transcript or reactions. The row draws it as text (`presentedKind`).
     */
    fun tombstone(message: ChatMessage): ChatMessage = ChatMessage(
        id = message.id,
        peerUserId = message.peerUserId,
        senderUserId = message.senderUserId,
        text = DELETED_TEXT,
        createdAt = message.createdAt,
        createdAtWire = message.createdAtWire,
        isMine = message.isMine,
        deleted = true,
        receipt = message.receipt,
        kind = when (message.kind) {
            ChatMessageKind.Image, ChatMessageKind.Video, ChatMessageKind.Voice -> message.kind
            ChatMessageKind.Text, ChatMessageKind.Todo -> ChatMessageKind.Text
        },
    )

    /** `ThreadMessageMerge.swift:123`. */
    const val DELETED_TEXT = "Message deleted"
}
