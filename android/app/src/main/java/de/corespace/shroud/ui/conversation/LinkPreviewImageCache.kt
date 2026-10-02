package de.corespace.shroud.ui.conversation

import androidx.compose.ui.graphics.ImageBitmap
import java.util.UUID

/**
 * Decoded link-preview pictures, so scrolling never re-decodes a JPEG per frame — iOS
 * `LinkPreviewImageCache` (`LinkPreviewView.swift:361-399`; conversation-thread §6.2, §19).
 *
 * Keyed by message id + [Variant], invalidated by the source's byte count (the blurred placeholder
 * gives way to the full picture once its blob is decrypted). At most [LIMIT] entries; when full it is
 * emptied wholesale, as iOS does. RAM only: `BubbleMemory` empties it when chats lock and drops a
 * deleted message's pictures. Thread-safe.
 */
object LinkPreviewImageCache {
    enum class Variant { Full, Placeholder, Thumbnail }

    /** `LinkPreviewView.swift:375`. */
    const val LIMIT = 120

    private class Entry(val bytes: Int, val image: ImageBitmap)

    private val storage = HashMap<Pair<UUID, Variant>, Entry>()

    /** The picture decoded from [sourceBytes] bytes for this message and variant, if held. */
    @Synchronized
    fun image(messageId: UUID, variant: Variant, sourceBytes: Int): ImageBitmap? =
        storage[messageId to variant]?.takeIf { it.bytes == sourceBytes }?.image

    /** The picture held for this message and variant whatever its source size (a downloaded blob never changes). */
    @Synchronized
    fun imageAnySize(messageId: UUID, variant: Variant): ImageBitmap? = storage[messageId to variant]?.image

    /** Holds [image], decoded from [sourceBytes] bytes; empties the cache first when it is full (`:382`). */
    @Synchronized
    fun store(messageId: UUID, variant: Variant, sourceBytes: Int, image: ImageBitmap) {
        if (storage.size >= LIMIT && (messageId to variant) !in storage) storage.clear()
        storage[messageId to variant] = Entry(sourceBytes, image)
    }

    /** Chats locked: preview pictures leave memory with the messages they belong to (`:387-390`). */
    @Synchronized
    fun clear() = storage.clear()

    /** A message was deleted: its pictures go (`:392-398`). */
    @Synchronized
    fun remove(ids: Collection<UUID>) {
        for (id in ids) for (variant in Variant.entries) storage.remove(id to variant)
    }

    /** For tests: how many pictures are held. */
    @Synchronized
    fun count(): Int = storage.size
}
