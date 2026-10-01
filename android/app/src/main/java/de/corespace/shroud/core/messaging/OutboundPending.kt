package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.NOTES_PEER_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One message waiting for the network (`OutboundPendingItem`, `OutboundPending.swift:4-9`;
 * messaging-core §11.9). The queue is derived from the threads, never stored: deleting a queued
 * bubble takes it out of the queue (`MessagingController.swift:1866-1871`).
 */
sealed interface OutboundPendingItem {
    val messageId: UUID
    val peerId: UUID

    data class Text(override val messageId: UUID, override val peerId: UUID, val text: String) : OutboundPendingItem {
        override fun toString(): String = "OutboundPendingItem.Text(id=$messageId)"
    }

    /** [caption] is empty for a captionless photo (its bubble says "Photo"). */
    data class Image(override val messageId: UUID, override val peerId: UUID, val caption: String) : OutboundPendingItem {
        override fun toString(): String = "OutboundPendingItem.Image(id=$messageId)"
    }

    /** [caption] is empty for a captionless video (its bubble says "Video"). */
    data class Video(override val messageId: UUID, override val peerId: UUID, val caption: String) : OutboundPendingItem {
        override fun toString(): String = "OutboundPendingItem.Video(id=$messageId)"
    }

    data class Voice(override val messageId: UUID, override val peerId: UUID) : OutboundPendingItem
}

/** Pure extraction of the offline outbound work from the threads (`OutboundPending.swift:12-60`). */
object OutboundPending {
    /**
     * Every `pendingSync` message of ours outside Notes, oldest first so a thread flushes in send
     * order (`OutboundPending.swift:14-59`). Notes never queue (they stay local when a sync fails,
     * messaging-core §11.4, D6); todos are never sent.
     */
    fun items(threads: Map<UUID, List<ChatMessage>>, notesPeerId: UUID = NOTES_PEER_ID): List<OutboundPendingItem> {
        val collected = ArrayList<Pair<ChatMessage, OutboundPendingItem>>()
        for ((peerId, messages) in threads) {
            if (peerId == notesPeerId) continue
            for (message in messages) {
                if (!message.pendingSync || !message.isMine) continue
                val item = when (message.kind) {
                    ChatMessageKind.Text -> OutboundPendingItem.Text(message.id, peerId, message.text)
                    ChatMessageKind.Image -> OutboundPendingItem.Image(
                        message.id,
                        peerId,
                        if (message.text == "Photo" || message.text.isEmpty()) "" else message.text,
                    )
                    ChatMessageKind.Video -> OutboundPendingItem.Video(
                        message.id,
                        peerId,
                        if (message.text == "Video" || message.text.isEmpty()) "" else message.text,
                    )
                    ChatMessageKind.Voice -> OutboundPendingItem.Voice(message.id, peerId)
                    ChatMessageKind.Todo -> null
                }
                if (item != null) collected += message to item
            }
        }
        // Stable: equal times keep their thread order.
        return collected.sortedBy { it.first.createdAt }.map { it.second }
    }
}

/**
 * Serialises reconnect flushes so the poll, a connectivity change and the return to the app do not
 * send the same queue twice (`OutboundSendQueue`, `OutboundPending.swift:62-85`). Main-confined like
 * the controller that owns it (plan §1.1 rule 3).
 *
 * The flush runs as a job in [scope], so a caller that is cancelled while waiting does not cancel
 * the flush other callers wait for (an iOS `Task` outlives its awaiter the same way); [cancel]
 * stops it (sign-out, lock).
 */
class OutboundSendQueue(private val scope: CoroutineScope) {
    private var flushJob: Job? = null

    /** Runs [work] once at a time; overlapping callers wait for the flush in flight and return. */
    suspend fun flush(work: suspend () -> Unit) {
        flushJob?.let { existing ->
            existing.join()
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { work() }
        flushJob = job
        job.start()
        job.join()
        if (flushJob === job) flushJob = null
    }

    /** Stops the flush in flight; its waiters return. */
    fun cancel() {
        flushJob?.cancel()
        flushJob = null
    }

    /** A flush is running (tests). */
    internal val isFlushing: Boolean get() = flushJob?.isActive == true
}
