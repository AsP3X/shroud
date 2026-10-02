package de.corespace.shroud.ui.conversation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.util.UUID

/**
 * Decoded thumbnails, photos and video posters by message id, shared by bubbles, the menu hero, the
 * viewer and the composer — iOS `DecodedImageCache` (`MessageActionMenu.swift:855-904`;
 * conversation-thread §19; plan §1.7.13, C8). The long-press hero and a reply quote read it and never
 * decode on their own (`ConversationView.swift:1974-1977`).
 *
 * One entry per message, remembering what it was decoded from ([Source] and the source's byte count),
 * so a small envelope preview never stands in for the full photo (iOS `dataCounts`, `:858-876`).
 * Sized by bytes, least recently used first out: about an eighth of the heap, at most
 * [MAX_BUDGET_BYTES] (iOS keeps every decoded image; Android's heap is smaller).
 *
 * Decrypted pixels never outlive the chat lock: `BubbleMemory` empties it when chats lock and drops a
 * message's entry when the message is purged (plan §1.7.7 `MessageArtifactSinks`). Thread-safe.
 *
 * Owner: W3-THREAD-BUBBLES (the W2-INT seam's four members keep their signatures).
 */
object DecodedImageCache {
    /** What a cached bitmap was decoded from. */
    enum class Source {
        /** The envelope thumbnail (`th`, ≤ 6 KiB) or a link placeholder. */
        Preview,

        /** The full photo or the video poster. */
        Full,
    }

    private class Entry(val image: ImageBitmap, val source: Source, val sourceBytes: Int?)

    /** At most 48 MiB of decoded pixels, whatever the heap. */
    const val MAX_BUDGET_BYTES = 48L * 1024 * 1024

    private val entries = SizedLruMap<UUID, Entry>(
        maxBytes = minOf(MAX_BUDGET_BYTES, Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(4L * 1024 * 1024),
        sizeOf = { entry -> byteCount(entry.image) },
    )

    /** The best image held for [id], whatever it was decoded from (iOS `image(for:)`, `:868-870`). */
    @Synchronized
    fun image(id: UUID): ImageBitmap? = entries[id]?.image

    /**
     * The image held for [id] only if it was decoded from [source] — and, when [sourceBytes] is given,
     * from data of that byte count (iOS `image(for:decodedFrom:)`, `:872-876`). A full image also
     * answers a request for the preview: it is the better picture of the same thing.
     */
    @Synchronized
    fun image(id: UUID, source: Source, sourceBytes: Int? = null): ImageBitmap? {
        val entry = entries[id] ?: return null
        return when {
            entry.source == source && (sourceBytes == null || entry.sourceBytes == sourceBytes) -> entry.image
            source == Source.Preview && entry.source == Source.Full -> entry.image
            else -> null
        }
    }

    /** Stores an image of unknown origin (the viewer, the composer): counts as [Source.Full]. */
    fun store(id: UUID, image: ImageBitmap) = store(id, image, Source.Full, null)

    /**
     * Stores [image] for [id], decoded from [source] of [sourceBytes] bytes. A preview never replaces
     * a full image already held (`:863-866`).
     */
    @Synchronized
    fun store(id: UUID, image: ImageBitmap, source: Source, sourceBytes: Int?) {
        val held = entries.peek(id)
        if (held != null && held.source == Source.Full && source == Source.Preview) return
        entries[id] = Entry(image, source, sourceBytes)
    }

    /** Drops the bitmaps of deleted messages so nothing stays in memory (`:886-892`). */
    @Synchronized
    fun remove(ids: Collection<UUID>) {
        ids.forEach(entries::remove)
    }

    /** Chats locked: every decoded picture leaves memory with the history it came from (`:879-884`). */
    @Synchronized
    fun clear() = entries.clear()

    /**
     * A sent message took its server id: its picture moves along, so the bubble does not decode it
     * again (iOS stores the image under the new id, `MessagingController.swift:3125`).
     */
    @Synchronized
    fun rekey(from: UUID, to: UUID) {
        if (from == to) return
        val entry = entries.remove(from) ?: return
        if (entries.peek(to) == null) entries[to] = entry
    }

    /** For tests and the wipe overlay: how many images are held. */
    @Synchronized
    fun count(): Int = entries.size

    private fun byteCount(image: ImageBitmap): Long =
        try {
            image.asAndroidBitmap().allocationByteCount.toLong()
        } catch (_: Exception) {
            image.width.toLong() * image.height * 4
        }
}

/**
 * A least-recently-used map bounded by the summed [sizeOf] of its values (a pure stand-in for
 * `android.util.LruCache`, which the JVM tests cannot run). Not thread-safe: callers lock.
 */
internal class SizedLruMap<K : Any, V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var bytes = 0L

    val size: Int get() = map.size
    val totalBytes: Long get() = bytes

    /** Reads and marks as recently used. */
    operator fun get(key: K): V? = map[key]

    /** Reads without touching the order. */
    fun peek(key: K): V? = map.entries.firstOrNull { it.key == key }?.value

    operator fun set(key: K, value: V) {
        map.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(value)
        trim()
    }

    fun remove(key: K): V? = map.remove(key)?.also { bytes -= sizeOf(it) }

    fun clear() {
        map.clear()
        bytes = 0
    }

    fun keys(): List<K> = map.keys.toList()

    private fun trim() {
        val iterator = map.entries.iterator()
        // An entry bigger than the whole budget still stays on its own: the bubble showing it needs it.
        while (bytes > maxBytes && map.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            bytes -= sizeOf(eldest.value)
            iterator.remove()
        }
    }
}
