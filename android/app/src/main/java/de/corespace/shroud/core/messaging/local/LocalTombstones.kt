package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.messaging.ThreadMessageMerge
import de.corespace.shroud.core.model.ChatMessage

/**
 * The two merge rules the store needs (iOS `ThreadMessageMerge`,
 * `ios/shroud/Services/Messaging/ThreadMessageMerge.swift`). Since the W2 merge they delegate to
 * W2-MSG-CORE's [ThreadMessageMerge], so there is one copy; `LocalTombstonesTest` still pins them
 * with the iOS vectors from the store's side.
 */
object LocalTombstones {
    /** The stand-in texts of a message this device could not read (`ThreadMessageMerge.swift:5-7`). */
    fun isFailedDecryptText(text: String): Boolean = ThreadMessageMerge.isFailedDecryptText(text)

    /**
     * What a message deleted for everyone leaves (`tombstone(of:)`, `ThreadMessageMerge.swift:114-133`):
     * who sent it, when, its receipt and which kind it was (photo, video and voice keep their kind,
     * anything else becomes text). Nothing it said remains — no caption, quote, preview, media,
     * transcript or reactions. The row draws it as text (`presentedKind`).
     */
    fun tombstone(message: ChatMessage): ChatMessage = ThreadMessageMerge.tombstone(message)

    /** `ThreadMessageMerge.swift:123`. */
    const val DELETED_TEXT = ThreadMessageMerge.MESSAGE_DELETED
}
