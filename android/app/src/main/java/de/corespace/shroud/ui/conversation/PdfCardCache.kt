package de.corespace.shroud.ui.conversation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.util.UUID

/**
 * The PDF cards' local renders by message id (docs/file-sharing.md §10.1): page 1's top at the
 * card's pixel width, **in memory only**, least recently used out past [MAX_BYTES] of pixels (a card
 * at ~2.6× density is ~1.2 MB). Also what those renders learnt: the page count (kept apart from the
 * pixels, so an evicted render still lends the meta line its count) and which files did not render
 * (password, damage, a failed §4 check), so a bubble does not try again on every appearance.
 *
 * Decrypted pixels never outlive the chat lock: `BubbleMemory` empties it when chats lock, drops a
 * message's entry when the message is purged, and moves it when a sent message takes its server id.
 * Thread-safe.
 */
object PdfCardCache {
    /** About 24 MB of bitmap bytes (`Bitmap.allocationByteCount`), whatever the count. */
    const val MAX_BYTES = 24L * 1024 * 1024

    class Entry(val image: ImageBitmap, val widthPx: Int)

    private val renders = SizedLruMap<UUID, Entry>(MAX_BYTES) { entry -> byteCount(entry.image) }
    private val pages = HashMap<UUID, Int>()
    private val failed = HashSet<UUID>()

    /** The render held for [id] at exactly [widthPx], or null. */
    @Synchronized
    fun image(id: UUID, widthPx: Int): ImageBitmap? = renders[id]?.takeIf { it.widthPx == widthPx }?.image

    /** The page count a render of [id] read, or null. */
    @Synchronized
    fun pageCount(id: UUID): Int? = pages[id]

    /** Whether [id]'s PDF did not render here (it keeps its `th`). */
    @Synchronized
    fun hasFailed(id: UUID): Boolean = id in failed

    @Synchronized
    fun store(id: UUID, widthPx: Int, image: ImageBitmap, pageCount: Int) {
        renders[id] = Entry(image, widthPx)
        if (pageCount >= 1) pages[id] = pageCount
        failed -= id
    }

    @Synchronized
    fun markFailed(id: UUID) {
        failed += id
    }

    /** Purged messages: their renders and what was read from them go. */
    @Synchronized
    fun remove(ids: Collection<UUID>) {
        ids.forEach { id ->
            renders.remove(id)
            pages.remove(id)
            failed.remove(id)
        }
    }

    /** Chats locked: every render leaves memory. */
    @Synchronized
    fun clear() {
        renders.clear()
        pages.clear()
        failed.clear()
    }

    /** A sent PDF took its server id: its render moves along, so the bubble does not draw it again. */
    @Synchronized
    fun rekey(from: UUID, to: UUID) {
        if (from == to) return
        renders.remove(from)?.let { if (renders.peek(to) == null) renders[to] = it }
        pages.remove(from)?.let { pages.putIfAbsent(to, it) }
        if (failed.remove(from)) failed += to
    }

    /** For tests: how many renders are held, and their bytes. */
    @Synchronized
    fun count(): Int = renders.size

    @Synchronized
    fun totalBytes(): Long = renders.totalBytes

    private fun byteCount(image: ImageBitmap): Long =
        try {
            image.asAndroidBitmap().allocationByteCount.toLong()
        } catch (_: Exception) {
            image.width.toLong() * image.height * 4
        }
}
