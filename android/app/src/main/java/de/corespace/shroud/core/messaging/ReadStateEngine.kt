package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.notifications.MessageNotifier
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Receipts, unread counts, read markers, the badge and mutes (`MessagingController.swift:4262-4327,
 * 2184-2195, 5611-5848`; messaging-core §15, §16). Main-confined; its timers are jobs in [scope].
 *
 * A receipt only ever rises. Unread counts are the server's (`unread_count`, the same on every
 * device), raised by arrivals between two lists and cleared when a chat is read; reads and mutes this
 * device made that a racing list does not show yet are laid over every list ([applyingLocalChatState]).
 *
 * @param isResumed one of our activities is resumed (iOS `applicationState == .active`): a chat left
 *   open behind the lock screen or another app is not being read.
 */
class ReadStateEngine(
    private val state: ThreadStore,
    private val backend: MessagingBackend,
    private val notifier: () -> MessageNotifier,
    private val isResumed: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: AppClock,
) {
    /** Chats read here or on another device, up to their last message then (`readThrough`, `:66-69`). */
    private val readThrough = HashMap<UUID, Instant>()

    /** Mutes being saved; a present key with null value is "unmuted, pending" (`pendingMutes`, `:70-71`). */
    private val pendingMutes = HashMap<UUID, ChatMuteDto?>()
    private val pendingMuteHolds = HashMap<UUID, UUID>()

    /** One read-marker request per chat at a time (`chatReadTasks`, `:72-77`). */
    private val chatReadJobs = HashMap<UUID, Job>()
    private val chatReadsQueued = HashSet<UUID>()
    private val chatReadRetries = HashSet<UUID>()

    /** The server keeps read markers (its list carries `unread_count`); an older one takes per-message receipts (`:78-80`). */
    var serverKeepsReadMarkers: Boolean = true

    // ---- Receipts (messaging-core §15) ----------------------------------------------------------

    /** Raises a receipt of ours, never lowers it (`updateReceipt`, `MessagingController.swift:4291-4305`). */
    fun updateReceipt(messageId: UUID, atLeast: ReceiptStatus) {
        for ((peer, thread) in state.threads.value) {
            val index = thread.indexOfFirst { it.id == messageId && it.isMine }
            if (index < 0) continue
            if (atLeast.rank > thread[index].receipt.rank) {
                state.setThread(peer, thread.toMutableList().also { it[index] = it[index].copy(receipt = atLeast) })
                state.persistThread(peer)
            }
            return
        }
    }

    /**
     * `message.read` (`handleReadEvent`, `MessagingController.swift:4262-4278`): the bulk anchor, else
     * the single message — a single read implies every earlier one. Our own reads on another device
     * are ignored (decision D8b; `MessageReadEventTests`): the unread badge follows `conversation.read`.
     */
    fun onMessageRead(event: RealtimeEvent.MessageRead, me: UUID?) {
        if (!isPeerRead(event.userId, me)) return
        val anchor = event.upToMessageId ?: event.messageId ?: return
        markOwnMessagesRead(anchor)
    }

    /** Every message of ours at or before [anchorId] (by time) is read (`markOwnMessagesRead`, `:4308-4326`). */
    fun markOwnMessagesRead(anchorId: UUID) {
        for ((peer, thread) in state.threads.value) {
            val anchor = thread.firstOrNull { it.id == anchorId } ?: continue
            var changed = false
            val next = thread.map { message ->
                if (message.isMine && (message.createdAt <= anchor.createdAt || message.id == anchorId) && message.receipt != ReceiptStatus.Read) {
                    changed = true
                    message.copy(receipt = ReceiptStatus.Read)
                } else {
                    message
                }
            }
            if (changed) {
                state.setThread(peer, next)
                state.persistThread(peer)
            }
        }
    }

    /**
     * Read receipts were turned off: ticks already drawn as read step back to delivered, since the
     * server stops reporting reads both ways (`hideReadTicks`, `MessagingController.swift:2184-2195`).
     */
    fun hideReadTicks() {
        for ((peer, thread) in state.threads.value) {
            if (thread.none { it.isMine && it.receipt == ReceiptStatus.Read }) continue
            state.setThread(peer, thread.map { if (it.isMine && it.receipt == ReceiptStatus.Read) it.copy(receipt = ReceiptStatus.Delivered) else it })
            state.persistThread(peer)
        }
    }

    // ---- Reading a chat (messaging-core §16.2) ----------------------------------------------------

    /** The chat is on screen and the app in front (`isReading`, `MessagingController.swift:5633-5635`). */
    fun isReading(peer: UUID): Boolean = peer == state.activePeerId && isResumed()

    /**
     * The chat was read here: its notifications close and the server moves the read marker (clears it
     * on our other devices, sends the peer receipts) (`didReadChat`, `:5659-5668`). Never for Notes.
     */
    fun didReadChat(peer: UUID) {
        if (state.isNotes(peer)) return
        val conversation = state.conversations.firstOrNull { it.peer.id == peer }
        markReadLocally(peer, conversation?.id, conversation?.lastMessageAt)
        if (chatReadJobs.containsKey(peer)) {
            chatReadsQueued += peer
            return
        }
        sendChatRead(peer)
    }

    /**
     * One read-marker request after a 400 ms pause that coalesces the burst a chat opening makes
     * (list, thread load, arrivals); a read while it is out queues one more (`sendChatRead`, `:5670-5692`).
     */
    private fun sendChatRead(peer: UUID) {
        val token = state.session?.token ?: return
        chatReadJobs[peer] = scope.launch {
            delay(CHAT_READ_DELAY_MS)
            // This request covers everything so far; only a read while it is out needs another.
            chatReadsQueued -= peer
            val delivered = postChatRead(peer, token)
            chatReadJobs.remove(peer)
            // Kept read here meanwhile (readThrough): the next list sends it again.
            if (delivered) chatReadRetries -= peer else chatReadRetries += peer
            if (chatReadsQueued.remove(peer)) sendChatRead(peer)
        }
    }

    /** Tells the server; false when the request did not get through (`postChatRead`, `:5694-5707`). */
    private suspend fun postChatRead(peer: UUID, token: String): Boolean {
        if (!serverKeepsReadMarkers) {
            // An older server: receipts up to the peer's newest message here.
            val last = state.messages(peer)?.lastOrNull { !it.isMine } ?: return true
            return succeeded { backend.markReadBulk(token, peer, last.id) }
        }
        return succeeded { backend.markChatRead(token, peer) }
    }

    /**
     * Zeroes a chat's count and closes its notifications without asking the server — the read or
     * the reply happened elsewhere, or is on its way (`markReadLocally`, `:5709-5723`).
     */
    fun markReadLocally(peer: UUID, conversationId: UUID?, through: Instant?) {
        val at = through ?: clock.now()
        readThrough[peer] = readThrough[peer]?.let { maxOf(it, at) } ?: at
        state.editUnread { if (it[peer] != 0) it + (peer to 0) else it }
        state.editConversations { list ->
            list.map { if (it.peer.id == peer && it.unreadCount != null && it.unreadCount != 0) it.copy(unreadCount = 0) else it }
        }
        conversationId?.let { notifier().clearDelivered(it) }
        updateBadge()
    }

    /** `markChatRead(peerUserID:)` (`:5829-5831`): the row's menu, without opening the chat. */
    fun markChatRead(peer: UUID) = didReadChat(peer)

    /** Reads the server did not get are sent again after a list (`MessagingController.swift:1044-1046`). */
    fun retryChatReads() {
        for (peer in chatReadRetries.toList()) if (!chatReadJobs.containsKey(peer)) sendChatRead(peer)
    }

    // ---- The server's counts (messaging-core §16.3) ------------------------------------------------

    /**
     * Reads and mutes of this device a racing list may not show yet (`applyingLocalChatState`,
     * `MessagingController.swift:5614-5631`).
     */
    fun applyingLocalChatState(list: List<ConversationItemDto>): List<ConversationItemDto> {
        if (readThrough.isEmpty() && pendingMutes.isEmpty()) return list
        return list.map { item ->
            var copy = item
            val readTo = readThrough[item.peer.id]
            if (readTo != null && (item.unreadCount ?: 0) > 0 && (item.lastMessageAt ?: item.createdAt) <= readTo) {
                copy = copy.copy(unreadCount = 0)
            }
            if (pendingMutes.containsKey(item.peer.id)) copy = copy.copy(mute = pendingMutes[item.peer.id])
            copy
        }
    }

    /**
     * The server's counts win (they include reads on our other devices); the chat being read has
     * none, and one the server still counts is read now; a chat the list lost counts nothing
     * (`adoptServerUnreadCounts`, `MessagingController.swift:5640-5657`). An older server sends no
     * counts: the local ones stay.
     */
    fun adoptServerUnreadCounts(list: List<ConversationItemDto>) {
        if (list.isNotEmpty() && list.none { it.unreadCount != null }) return
        val next = state.unread.value.filterKeys { state.isNotes(it) }.toMutableMap()
        for (item in list) {
            val count = item.unreadCount ?: 0
            if (isReading(item.peer.id)) {
                next[item.peer.id] = 0
                if (count > 0) didReadChat(item.peer.id)
            } else {
                next[item.peer.id] = count
            }
        }
        state.editUnread { next }
        updateBadge()
    }

    /** `conversation.read`: our other device read a chat (`handleConversationReadEvent`, `:5725-5737`). */
    fun onConversationRead(event: RealtimeEvent.ConversationRead) {
        markReadLocally(event.peerUserId, event.conversationId, event.readAt)
        if (event.unreadCount > 0 && event.peerUserId != state.activePeerId) {
            state.editUnread { it + (event.peerUserId to event.unreadCount) }
            updateBadge()
        }
    }

    /** A message from the peer while the chat is not being read (`MessagingController.swift:4431-4434`). */
    fun countArrival(peer: UUID) {
        state.editUnread { it + (peer to (it[peer] ?: 0) + 1) }
        updateBadge()
    }

    // ---- Totals and badge (messaging-core §16.4) -------------------------------------------------

    fun unreadCount(peer: UUID): Int = state.unread.value[peer] ?: 0

    /** Unread across chats; muted ones only with [includeMuted] (`unreadTotal`, `:5835-5841`). */
    fun unreadTotal(includeMuted: Boolean): Int =
        state.unread.value.entries.sumOf { (peer, count) ->
            if (state.isNotes(peer) || (!includeMuted && isMuted(peer))) 0 else maxOf(0, count)
        }

    /** The launcher count, once the chats are loaded — a locked or empty state says nothing (`updateBadge`, `:5843-5848`). */
    fun updateBadge() {
        if (!state.listStatus.value.hasLoadedChats) return
        val notifier = notifier()
        notifier.setBadge(unreadTotal(notifier.badgeIncludesMuted))
    }

    // ---- Mutes (messaging-core §16.5) -------------------------------------------------------------

    /** The chat's mute while it lasts; a pending change wins, even an unmute (`mute(for:)`, `:5757-5761`). */
    fun mute(peer: UUID): ChatMuteDto? {
        val mute = if (pendingMutes.containsKey(peer)) pendingMutes[peer] else state.conversations.firstOrNull { it.peer.id == peer }?.mute
        return mute?.takeIf { it.isActive(clock.now()) }
    }

    fun isMuted(peer: UUID): Boolean = mute(peer) != null

    /** A chat has to exist to be muted: the lists are where mutes show (`canMute`, `:5777-5779`). */
    fun canMute(peer: UUID): Boolean = !state.isNotes(peer) && state.conversations.any { it.peer.id == peer }

    /**
     * Mutes ([duration] non-null) or unmutes a chat on every device of ours (`changeMute`,
     * `MessagingController.swift:5781-5816`): optimistic, rolled back on failure, and held for 6 s
     * after success so a racing list cannot undo it. Returns the user-facing error, or null.
     */
    suspend fun changeMute(peer: UUID, duration: MuteDuration?, refreshConversations: suspend (Boolean) -> Unit): String? {
        val token = state.session?.token ?: return "Not signed in."
        if (!canMute(peer)) return "A chat can be muted once it has messages."
        val previous = state.conversations.firstOrNull { it.peer.id == peer }?.mute
        val optimistic = duration?.let { choice -> ChatMuteDto(until = choice.seconds?.let { clock.now().plusSeconds(it) }) }
        setPendingMute(peer, optimistic)
        try {
            if (duration != null) {
                val saved = backend.muteChat(token, peer, duration.seconds)
                setPendingMute(peer, saved.mute)
            } else {
                backend.unmuteChat(token, peer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Back to what it was: offline, the refresh below cannot say.
            pendingMutes.remove(peer)
            state.editConversations { list -> list.map { if (it.peer.id == peer) it.copy(mute = previous) else it } }
            updateBadge()
            refreshConversations(true)
            return messagingUserMessage(e)
        }
        // Held until a list that includes it has surely landed — unless a newer change took over.
        val hold = UUID.randomUUID()
        pendingMuteHolds[peer] = hold
        scope.launch {
            delay(PENDING_MUTE_HOLD_MS)
            if (pendingMuteHolds[peer] != hold) return@launch
            pendingMutes.remove(peer)
            pendingMuteHolds.remove(peer)
        }
        return null
    }

    /** `setPendingMute`, `:5818-5827`. */
    private fun setPendingMute(peer: UUID, mute: ChatMuteDto?) {
        pendingMutes[peer] = mute
        state.editConversations { list -> list.map { if (it.peer.id == peer && it.mute != mute) it.copy(mute = mute) else it } }
        updateBadge()
    }

    /** `conversation.mute`: our other device muted or unmuted a chat (`handleConversationMuteEvent`, `:5740-5755`). */
    fun onConversationMute(event: RealtimeEvent.ConversationMute) {
        if (state.conversations.none { it.peer.id == event.peerUserId }) return
        state.editConversations { list -> list.map { if (it.peer.id == event.peerUserId) it.copy(mute = event.mute) else it } }
        updateBadge()
    }

    /** Memory clear (sign-out, lock of the chats): `clearInMemoryState`, `MessagingController.swift:574-584`. */
    fun clear() {
        readThrough.clear()
        pendingMutes.clear()
        chatReadJobs.values.forEach { it.cancel() }
        chatReadJobs.clear()
        chatReadsQueued.clear()
        chatReadRetries.clear()
        serverKeepsReadMarkers = true
        pendingMuteHolds.clear()
    }

    private suspend fun succeeded(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    companion object {
        /** `MessagingController.swift:5674`. */
        const val CHAT_READ_DELAY_MS = 400L

        /** `MessagingController.swift:5810`. */
        const val PENDING_MUTE_HOLD_MS = 6_000L

        /**
         * `message.read` from the peer, not from one of our own devices (`isPeerRead`,
         * `MessagingController.swift:4282-4289`). No reader or no session: the peer's, as before.
         */
        fun isPeerRead(readerId: UUID?, me: UUID?): Boolean = me == null || readerId == null || readerId != me
    }
}

