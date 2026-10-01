package de.corespace.shroud.ui.conversation

import androidx.compose.ui.graphics.ImageBitmap
import java.util.UUID

/**
 * Decoded thumbnails and photos by message id, shared by bubbles, the menu hero, the viewer and the
 * composer (iOS `DecodedImageCache`; plan §1.7.13, C8). Cleared on lock and when messages are purged:
 * decrypted pixels never outlive the chat lock.
 *
 * **Stub (W2-INT seam), owner W3-THREAD-BUBBLES** — which replaces this small bounded map with the
 * sized LRU of conversation-thread §8 and registers the purge / lock sink with messaging. Thread-safe.
 */
object DecodedImageCache {
    private const val MAX_ENTRIES = 64
    private val images = object : LinkedHashMap<UUID, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, ImageBitmap>?): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun image(id: UUID): ImageBitmap? = images[id]

    @Synchronized
    fun store(id: UUID, image: ImageBitmap) {
        images[id] = image
    }

    @Synchronized
    fun remove(ids: Collection<UUID>) {
        ids.forEach(images::remove)
    }

    @Synchronized
    fun clear() = images.clear()
}
