package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.DeleteConversationResponse
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Deletes of one message, a note, a whole chat, and the matching socket events
 * (`MessagingController.swift:1853-2091, 4211-4260`; messaging-core §14). Every path purges what the
 * device held for the messages it drops ([ThreadState.purge]). Main-confined.
 *
 * Known gap shared with iOS (messaging-core §14.6, memory: *Tombstones reach the newest page only*):
 * a device that missed `message.deleted` / `conversation.deleted` and never reconciles keeps old
 * content; nothing here claims otherwise.
 */
class DeleteEngine(
    private val state: ThreadStore,
    private val backend: MessagingBackend,
    private val pager: HistoryPager,
    private val typing: TypingSignals,
    private val isOnline: () -> Boolean,
    private val refreshConversations: suspend (force: Boolean) -> Unit,
    private val scope: CoroutineScope,
) {
    /**
     * Deletes a message here and on the server; returns the user-facing error, or null
     * (`deleteMessage`, `MessagingController.swift:1856-1893`). The server goes first: a `me` delete
     * is durable as a hide row, and applying it locally first would let the next page resurrect it.
     */
    suspend fun deleteMessage(message: ChatMessage, scope: MessageDeleteScope): String? {
        if (state.isNotes(message.peerUserId)) return deleteNote(message)

        // Never reached the server: dropping it from the thread also takes it out of the queue.
        if (message.pendingSync || message.receipt == ReceiptStatus.Failed) {
            removeMessageLocally(message.id, message.peerUserId)
            refreshConversations(true)
            return null
        }
        val token = state.session?.token ?: return "Sign in to delete messages."
        try {
            backend.deleteMessage(token, message.id, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = messagingUserMessage(e)
            state.setLastError(text)
            return text
        }
        when (scope) {
            MessageDeleteScope.Me -> removeMessageLocally(message.id, message.peerUserId)
            // The server keeps a tombstone: match it, or the next load pops "Message deleted" back in.
            MessageDeleteScope.Everyone -> tombstoneMessage(message.id, message.peerUserId)
        }
        state.setLastError(null)
        refreshConversations(true)
        return null
    }

    /**
     * A note: hard-deleted on the server (media included) when online; a 404 is a note that never
     * reached it (`deleteNote`, `MessagingController.swift:1900-1940`). Offline, only the local copy
     * goes — the server's stays (iOS behaviour).
     */
    private suspend fun deleteNote(message: ChatMessage): String? {
        val token = state.session?.token
        if (token != null && isOnline()) {
            try {
                backend.deleteMessage(token, message.id, MessageDeleteScope.Me)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!(e is ApiError && e.isNotFound)) {
                    val text = messagingUserMessage(e)
                    state.setLastError(text)
                    return text
                }
            }
        }
        // The Notes thread, and a copy a half-finished send parked under our own id.
        val me = state.myUserId?.takeIf { it != NOTES_PEER_ID }
        for (peer in listOfNotNull(NOTES_PEER_ID, me)) {
            val list = state.messages(peer) ?: continue
            if (list.none { it.id == message.id }) continue
            val kept = list.filterNot { it.id == message.id }
            // No empty ghost thread under our own id.
            state.setThread(peer, if (peer == NOTES_PEER_ID) kept else kept.ifEmpty { null })
        }
        state.purge(listOf(message.id))
        state.persistThread(NOTES_PEER_ID)
        if (me != null && state.messages(me) != null) state.persistThread(me)
        refreshConversations(true)
        return null
    }

    /** Drops a message and purges its caches (`removeMessageLocally`, `MessagingController.swift:1942-1957`). */
    fun removeMessageLocally(messageId: UUID, peer: UUID) {
        val list = state.messages(peer)
        val kept = list?.filterNot { it.id == messageId }
        state.purge(listOf(messageId))
        if (list == null || kept == null || kept.size == list.size) return
        state.setThread(peer, kept)
        state.persistThread(peer)
    }

    /** The same tombstone a page would merge in (`tombstoneMessage`, `MessagingController.swift:1977-1988`). */
    fun tombstoneMessage(messageId: UUID, peer: UUID) {
        val list = state.messages(peer) ?: return
        val index = list.indexOfFirst { it.id == messageId }
        if (index < 0 || list[index].deleted) return
        state.setThread(peer, list.toMutableList().also { it[index] = ThreadMessageMerge.tombstone(it[index]) })
        // Media and payload keys must not survive an unsend.
        state.purge(listOf(messageId))
        state.persistThread(peer)
    }

    /** `message.deleted`: the peer or our other device deleted for everyone (`handleDeletedEvent`, `:4211-4218`). */
    fun onMessageDeleted(event: RealtimeEvent.MessageDeleted) {
        val peer = state.peerFor(event.messageId) ?: return
        tombstoneMessage(event.messageId, peer)
        scope.launch { refreshConversations(true) }
    }

    /**
     * Deletes a whole chat here and on the server (`deleteConversation`, `MessagingController.swift:2014-2060`).
     * The contact stays either way; Saved Messages only accept `me`.
     */
    suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome {
        val isNotes = state.isNotes(peer)
        if (isNotes && scope == ConversationDeleteScope.Everyone) {
            return ChatDeleteOutcome.Failed("Saved Messages can only be deleted for you.")
        }
        val token = state.session?.token
        val me = state.myUserId
        if (token == null || me == null) return ChatDeleteOutcome.Failed("Sign in to delete chats.")
        // Notes are the self conversation on the wire.
        val apiPeer = if (isNotes) me else peer

        val response: DeleteConversationResponse
        try {
            response = backend.deleteConversation(token, apiPeer, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ApiError && e.isNotFound) {
                // The peer's account is gone, or the chat never reached the server.
                clearChatLocally(peer)
                return ChatDeleteOutcome.ClearedForMe
            }
            val text = messagingUserMessage(e)
            state.setLastError(text)
            return ChatDeleteOutcome.Failed(text)
        }
        clearChatLocally(peer)
        state.setLastError(null)
        refreshConversations(true)
        return when (scope) {
            ConversationDeleteScope.Me -> ChatDeleteOutcome.ClearedForMe
            ConversationDeleteScope.Everyone -> if (response.clearedForPeer) ChatDeleteOutcome.ClearedForBoth else ChatDeleteOutcome.UnsentForPeer
        }
    }

    /**
     * Drops a whole thread from memory, disk and the chat list, purging its caches
     * (`clearChatLocally`, `MessagingController.swift:2064-2091`). Notes keep their list row.
     */
    fun clearChatLocally(peer: UUID) {
        pager.cancelLoad(peer)
        val ids = state.messages(peer).orEmpty().map { it.id }
        state.setThread(peer, emptyList())
        if (ids.isNotEmpty()) state.purge(ids)
        if (state.isNotes(peer)) {
            // Notes may also have been mirrored under our own id during a send.
            val me = state.myUserId
            if (me != null && me != peer) {
                val stray = state.messages(me).orEmpty().map { it.id }
                state.setThread(me, null)
                if (stray.isNotEmpty()) state.purge(stray)
                state.persistThread(me)
            }
        }
        state.editUnread { if (peer in it) it - peer else it }
        if (!state.isNotes(peer)) {
            state.editConversations { list -> list.filterNot { it.peer.id == peer } }
            typing.setPeerTyping(peer, false)
        }
        state.persistThread(peer)
    }

    /**
     * `conversation.deleted` (`handleConversationDeletedEvent`, `MessagingController.swift:4227-4260`):
     * our other device deleted it, or the peer did and we had consented → drop it here too; the peer
     * did without our consent → reload it so their messages turn into tombstones (the active chat
     * stays, and this one keeps counting unread).
     */
    fun onConversationDeleted(event: RealtimeEvent.ConversationDeleted) {
        val me = state.myUserId ?: return
        val initiatedHere = event.initiatorUserId == me
        // The chat is always "the participant that isn't me".
        val peer = if (initiatedHere) event.otherUserId else event.initiatorUserId
        // Saved Messages arrive as the self conversation; they live under the sentinel.
        val threadPeer = if (peer == me) NOTES_PEER_ID else peer
        if (initiatedHere || event.clearedForPeer) {
            clearChatLocally(threadPeer)
        } else {
            scope.launch { pager.loadThread(threadPeer, activate = false, reconcile = true) }
        }
        scope.launch { refreshConversations(true) }
    }
}
