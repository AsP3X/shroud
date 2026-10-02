package de.corespace.shroud.ui.conversation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.corespace.shroud.core.model.ChatMessage
import java.time.Instant
import java.util.UUID

/**
 * A message the thread remembers by id, with its date to find the spot again once it is gone
 * (iOS `RenderAnchor`, `ConversationView.swift:2451-2455`).
 */
data class RenderAnchor(val id: UUID, val createdAt: Instant)

/**
 * The jump-to-latest control's state: whether the reader is up in the history, and what has landed
 * under them since they left the bottom (`JumpToLatestState`, `ConversationView.swift:2491-2527`;
 * conversation-thread §3.8). A pure port.
 *
 * Human: Observable, but only the jump control reads it, so leaving the bottom or a message arriving
 * under the reader redraws the control and not the thread.
 *
 * Agent: written from the scroll callbacks through [setAway]; the count is derived from the thread on
 * every read ([unseenCount]), so deletes and re-keyed sends can't leave it stale.
 */
@Stable
class JumpToLatestState {
    /** The reader has scrolled off the newest message (`:2503`). */
    var isAway: Boolean by mutableStateOf(false)
        private set

    /** The newest message when they left; what lands after it is counted (`:2505`). */
    private var leftAt: RenderAnchor? = null

    /** The reader left the bottom ([newest] being the newest message then) or got back to it (`:2508-2512`). */
    fun setAway(away: Boolean, newest: ChatMessage?) {
        if (away == isAway) return
        leftAt = if (away) newest?.let { RenderAnchor(it.id, it.createdAt) } else null
        isAway = away
    }

    /**
     * Messages from the other side that arrived after the reader left the bottom (`:2517-2526`). The
     * newest message back then is found again by its date if it has been deleted since. Tombstones
     * don't count, nor do our own sends, which take the reader to the bottom anyway.
     */
    fun unseenCount(messages: List<ChatMessage>): Int {
        if (!isAway) return 0
        var start = 0
        leftAt?.let { anchor ->
            start = messages.indexOfLast { it.id == anchor.id }.takeIf { it >= 0 }?.plus(1)
                ?: messages.indexOfFirst { it.createdAt > anchor.createdAt }.takeIf { it >= 0 }
                ?: messages.size
        }
        var count = 0
        for (index in start until messages.size) {
            val message = messages[index]
            if (!message.isMine && !message.deleted) count++
        }
        return count
    }
}
