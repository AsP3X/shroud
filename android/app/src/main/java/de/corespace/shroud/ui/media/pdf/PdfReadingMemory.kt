package de.corespace.shroud.ui.media.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/**
 * The PDF viewer's session memory (docs/file-sharing.md §10.2): the page each message's PDF was
 * left on, in memory only. `BubbleMemory` drops it when chats lock and when a message is purged, and
 * moves it when a sent message takes its server id. Thread-safe.
 */
object PdfReadingMemory {
    private val pages = HashMap<UUID, Int>()

    /**
     * The reader's choice of the pages sidebar on wide windows for the rest of the session (null:
     * the default, open for more than one page). A layout preference, not content: the lock keeps it.
     */
    var sidebarOpen: Boolean? by mutableStateOf(null)

    @Synchronized
    fun page(id: UUID): Int? = pages[id]

    @Synchronized
    fun remember(id: UUID, page: Int) {
        pages[id] = page
    }

    @Synchronized
    fun forget(ids: Collection<UUID>) {
        ids.forEach(pages::remove)
    }

    @Synchronized
    fun rekey(from: UUID, to: UUID) {
        if (from == to) return
        pages.remove(from)?.let { pages.putIfAbsent(to, it) }
    }

    @Synchronized
    fun clear() = pages.clear()
}
