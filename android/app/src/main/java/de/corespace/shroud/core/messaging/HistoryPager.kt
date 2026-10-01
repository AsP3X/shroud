package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.messaging.reactions.ReactionMerge
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.net.ReactionDto
import de.corespace.shroud.core.net.wire.MessageAnnotation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A history cursor: the server's `created_at` **text** plus the id (`HistoryCursor`,
 * `MessagingController.swift:1150-1155`; messaging-core §8.3). An `Instant` re-formatted to
 * milliseconds would skip every message in the rest of that millisecond (`HistoryCursorTests`).
 */
data class HistoryCursor(val createdAt: Instant, val createdAtWire: String, val id: UUID)

/**
 * Thread loading and history paging (`MessagingController.swift:1063-1529`; messaging-core §8):
 * the newest page on open and on each refresh, older pages as the reader scrolls up or the
 * background prefetch walks in, delivery acks for what arrived, and the hand-off of every page's
 * reactions to the reaction engine.
 *
 * **Trap** (memory: *Server re-keys sent messages*): every publish re-reads the thread as it is
 * then, never a copy taken before the page's network wait — a flush that re-keyed a bubble
 * meanwhile would be undone and the optimistic bubble re-appended.
 *
 * One load per chat at a time (`threadLoadTasks`); a caller that wants a reconcile while a plain
 * refresh runs waits for it and then runs its own. Main-confined.
 *
 * @param decodeContext the decoder's view of the controller for one decode (built per message, so
 *   it sees what earlier pages published).
 * @param refreshPresence the peer's presence after a load (`MessagingController.swift:1404-1410`;
 *   presence lives in the contacts controller, plan C6).
 */
class HistoryPager(
    private val state: ThreadStore,
    private val backend: MessagingBackend,
    private val decoder: MessageDecoder,
    private val decodeContext: (me: UUID) -> MessageDecoder.Context,
    private val isUnlocked: () -> Boolean,
    private val reactions: () -> ReactionsEngine,
    private val readState: ReadStateEngine,
    private val refreshPresence: suspend (UUID) -> Unit,
    private val scope: CoroutineScope,
    private val clock: AppClock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val threadLoads = HashMap<UUID, Job>()
    private val threadLoadReconciles = HashMap<UUID, Boolean>()
    private val olderLoads = HashMap<UUID, Job>()
    private val prefetches = HashMap<UUID, Job>()
    private val exhaustedFlow = MutableStateFlow<Set<UUID>>(emptySet())
    private val loadingOlderFlow = MutableStateFlow<Set<UUID>>(emptySet())

    /** Chats whose whole history is in the thread (`olderHistoryExhausted`, `MessagingController.swift:134-135`). */
    val olderHistoryExhausted: StateFlow<Set<UUID>> = exhaustedFlow.asStateFlow()

    /** Chats with an older page in flight: the top shows "Loading earlier messages" (`loadingOlderPeerIDs`, `:136-137`). */
    val loadingOlderPeerIds: StateFlow<Set<UUID>> = loadingOlderFlow.asStateFlow()

    /**
     * Loads [peer]'s thread, Notes included (`loadThread`, `MessagingController.swift:1074-1116`).
     *
     * @param activate the chat is on screen: it becomes the active chat, its count clears and it is
     *   read. False reloads a chat the user may not be in (an event about it).
     * @param reconcile walk back through held messages until the oldest is covered, so a delete this
     *   device missed while the socket was down turns into "Message deleted".
     */
    suspend fun loadThread(peer: UUID, activate: Boolean = true, reconcile: Boolean = false) {
        val me = state.myUserId
        if (state.session == null || me == null || !isUnlocked()) {
            if (state.isNotes(peer) && state.messages(peer) == null) state.setThread(peer, emptyList())
            return
        }
        val isNotes = state.isNotes(peer)
        if (activate) {
            state.setActivePeer(peer)
            if (!isNotes) {
                state.editUnread { if (it[peer] != 0) it + (peer to 0) else it }
                readState.didReadChat(peer)
            }
        }
        // Notes: the thread key is the sentinel, the API peer the signed-in user (Saved Messages).
        val apiPeer = if (isNotes) me else peer

        // A load whose caller was cancelled still clears its own entry (below); one that already
        // ended is no load to share.
        threadLoads[peer]?.takeIf { it.isActive }?.let { existing ->
            val existingReconciles = threadLoadReconciles[peer] == true
            existing.join()
            if (!reconcile || existingReconciles) return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { performThreadLoad(apiPeer, peer, reconcile) }
        threadLoads[peer] = job
        threadLoadReconciles[peer] = reconcile
        // The job clears its entry, not the waiter: iOS awaits an unstructured Task whose cleanup
        // always runs, while a cancelled Kotlin caller (a stopped poll, a chat the user left) never
        // gets past its join and would leave every later load of this chat returning at once.
        job.invokeOnCompletion {
            if (threadLoads[peer] === job) {
                threadLoads.remove(peer)
                threadLoadReconciles.remove(peer)
            }
        }
        job.start()
        job.join()
        if (state.activePeerId == peer) startOlderPrefetch(peer)
    }

    /** One decoded history page (`HistoryPage`, `MessagingController.swift:1131-1148`). */
    private class HistoryPage {
        /** Chronological, annotations left out (they fold into their targets). */
        val messages = ArrayList<ChatMessage>()

        /** Inbound ids, annotations included, the server may still want a delivery ack for. */
        val inboundIds = ArrayList<UUID>()

        /** The page before this one; null at the start or the retention limit. */
        var older: HistoryCursor? = null

        /** Every id the server returned: has a refresh met what we hold? */
        val serverIds = HashSet<UUID>()

        /** The trusted records of the page's messages (`ReactionMerge.pageRecords`), for the reaction engine. */
        val reactionRecords = ArrayList<ReactionDto>()

        /** Messages the page returned with at least one trusted record. */
        val withRecords = HashSet<UUID>()

        /** The server's reaction `seq` as it read the page; null from a server without reactions. */
        var reactionSnapshot: Long? = null
    }

    /** Now − 90 calendar days (`retentionCutoff`, `MessagingController.swift:1157-1163`). */
    private fun retentionCutoff(): Instant = clock.now().atZone(zone()).minusDays(RETENTION_DAYS).toInstant()

    /**
     * Fetches and decodes the page just older than [before] — the newest page when null
     * (`fetchHistoryPage`, `MessagingController.swift:1166-1258`). Annotations are not bubbles: a
     * transcript is noted for its voice note, and indexed for the purge of its target (Android D5).
     */
    private suspend fun fetchHistoryPage(apiPeer: UUID, storePeer: UUID, before: HistoryCursor?, limit: Int, token: String, me: UUID): HistoryPage {
        val isNotes = state.isNotes(storePeer)
        val cutoff = retentionCutoff()
        val response = backend.messages(token, apiPeer, limit, before?.createdAtWire, before?.id)
        val page = HistoryPage()
        // The server sends newest first.
        for (dto in response.messages.asReversed()) {
            page.serverIds += dto.id
            var message = decoder.decode(dto, decodeContext(me), forcePeer = storePeer)
            if (dto.contentType == ContentType.ANNOTATION) {
                noteAnnotation(dto, message)
                if (!isNotes && dto.senderUserId != me) page.inboundIds += dto.id
                continue
            }
            if (isNotes) message = NotesLocal.fromServer(message)
            // Over retention: drop now rather than publish and strip.
            if (!isNotes && message.createdAt < cutoff && !message.pendingSync) continue
            page.messages += message
            if (!isNotes && dto.senderUserId != me) page.inboundIds += dto.id
            val records = dto.reactions
            if (!records.isNullOrEmpty() && !message.deleted) {
                val trusted = ReactionMerge.pageRecords(dto.id, records, setOf(me, apiPeer))
                if (trusted.isNotEmpty()) {
                    page.reactionRecords += trusted
                    page.withRecords += dto.id
                }
            }
        }
        page.reactionSnapshot = response.reactionSeq

        val oldest = response.messages.lastOrNull()
        val mayHaveMore = response.hasMore == true || response.messages.size >= limit
        if (mayHaveMore && oldest != null && oldest.createdAt >= cutoff) {
            page.older = HistoryCursor(oldest.createdAt, oldest.createdAtWire, oldest.id)
        }
        return page
    }

    /**
     * A transcript shared as an annotation (`MessagingController.swift:1196-1205`), and the
     * annotation index of Android D5: an annotation deleted for everyone is purged.
     */
    private fun noteAnnotation(dto: MessageDto, decoded: ChatMessage) {
        if (dto.deletedForEveryone) {
            state.purgeAnnotation(dto.id)
            return
        }
        val shared = MessageAnnotation.parseTranscript(decoded.text) ?: return
        state.noteSharedTranscript(shared.text, shared.messageId)
        state.noteAnnotation(shared.messageId, dto.id)
    }

    /**
     * Merges a page into the thread as it is now (`publishHistoryPage`, `MessagingController.swift:1261-1321`).
     * Messages the page returned without a trusted record lose the held reactions the snapshot
     * says were taken back (`ReactionMerge.reconcile` with an empty page); the page's records go to
     * the reaction engine, which opens them, reconciles the rest and moves its cursor
     * ([ReactionsEngine.applyPage]). Tombstones this device may still hold content for are purged.
     */
    private fun publishHistoryPage(page: HistoryPage, storePeer: UUID) {
        val current = state.messages(storePeer).orEmpty()
        var merged = state.foldSharedTranscripts(
            ThreadMessageMerge.mergeThread(page.messages, current, current.filter { it.pendingSync }),
        )
        val snapshot = page.reactionSnapshot
        if (snapshot != null) {
            merged = merged.map { message ->
                if (message.id !in page.serverIds || message.id in page.withRecords || message.deleted || message.reactions.isEmpty()) {
                    message
                } else {
                    val reconciled = ReactionMerge.reconcile(message.reactions, emptyList(), snapshot)
                    if (reconciled != message.reactions) message.copy(reactions = reconciled) else message
                }
            }
        }
        state.setThread(storePeer, merged)
        if (snapshot != null || page.reactionRecords.isNotEmpty()) {
            reactions().applyPage(storePeer, page.reactionRecords, snapshot)
        }
        val deleted = ThreadMessageMerge.tombstonesToPurge(page.messages, current)
        if (deleted.isNotEmpty()) {
            state.purge(deleted)
            state.persistThread(storePeer)
        }
    }

    /**
     * The newest page and whatever is newer than what we hold (`performThreadLoad`,
     * `MessagingController.swift:1324-1423`; messaging-core §8.5). A first open stops after the
     * newest page; a refresh stops where it meets a held message; a reconcile walks until it has
     * covered the oldest held message; nothing goes past 4 000 messages.
     */
    private suspend fun performThreadLoad(apiPeer: UUID, storePeer: UUID, reconcile: Boolean) {
        val token = state.session?.token ?: return
        val me = state.myUserId ?: return
        if (!isUnlocked()) return
        val generation = state.lockGeneration
        val isNotes = state.isNotes(storePeer)
        val held = state.messages(storePeer).orEmpty().filter { !it.pendingSync }
        val known = held.mapTo(HashSet()) { it.id }
        // The oldest message on this device: a reconcile walks until a page covers it.
        val heldTail = held.minByOrNull { it.createdAt }

        try {
            var before: HistoryCursor? = null
            var fetched = 0
            val pendingDelivery = ArrayList<UUID>()
            var newestSnapshot: Long? = null
            while (true) {
                val limit = if (fetched == 0) FIRST_PAGE_SIZE else HISTORY_PAGE_SIZE
                val page = fetchHistoryPage(apiPeer, storePeer, before, limit, token, me)
                if (generation != state.lockGeneration) return
                if (fetched == 0) newestSnapshot = page.reactionSnapshot
                fetched += limit
                // Each page shows as it lands, so the newest messages appear at once.
                publishHistoryPage(page, storePeer)
                pendingDelivery += page.inboundIds.filter { it !in known }

                val older = page.older
                if (older == null) {
                    exhaustedFlow.value += storePeer
                    break
                }
                if (known.isEmpty()) exhaustedFlow.value -= storePeer
                val coveredHeldTail = heldTail == null || heldTail.id in page.serverIds || older.createdAt < heldTail.createdAt
                val caughtUp = if (reconcile) coveredHeldTail else page.serverIds.any { it in known }
                if (known.isEmpty() || caughtUp || fetched >= HISTORY_MAX_MESSAGES) break
                before = older
            }

            if (!isNotes) {
                // After the visible thread is populated: never stall the first paint.
                for (id in pendingDelivery) ignoringErrors { backend.markDelivered(token, id) }
                // Receipts only for the chat on screen; the read marker sends them.
                if (state.activePeerId == storePeer) readState.didReadChat(storePeer)
                ignoringErrors { refreshPresence(apiPeer) }
            }
            if (generation != state.lockGeneration) return
            if (newestSnapshot != null) reactions().catchUp(storePeer)
            // Reactions to our messages in the chat on screen are being seen (`:1408-1410`).
            if (state.activePeerId == storePeer && state.hasPendingUnseenReactions(storePeer)) reactions().markSeen(storePeer)
            state.setLastError(null)
            state.setOffline(false)
            state.persistThread(storePeer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != state.lockGeneration) return
            if (state.messages(storePeer).isNullOrEmpty()) {
                state.setLastError(messagingUserMessage(e))
            } else {
                state.setOffline(true)
                state.setLastError(null)
            }
        }
    }

    fun hasOlderHistory(peer: UUID): Boolean = peer !in exhaustedFlow.value

    fun isLoadingOlderHistory(peer: UUID): Boolean = peer in loadingOlderFlow.value

    /**
     * Fetches the page older than the thread's oldest message and merges it in (`loadOlderMessages`,
     * `MessagingController.swift:1437-1454`). One per chat; the prefetch and the reader's scrolling share it.
     */
    suspend fun loadOlderMessages(peer: UUID) {
        if (peer in exhaustedFlow.value) return
        olderLoads[peer]?.takeIf { it.isActive }?.let { running ->
            running.join()
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { performOlderLoad(peer) }
        olderLoads[peer] = job
        loadingOlderFlow.value += peer
        // As in [loadThread]: the job ends the "Loading earlier messages" state, whoever waits.
        job.invokeOnCompletion {
            if (olderLoads[peer] === job) {
                olderLoads.remove(peer)
                loadingOlderFlow.value -= peer
            }
        }
        job.start()
        job.join()
    }

    /** `performOlderLoad`, `MessagingController.swift:1456-1497`. Errors are swallowed: the next scroll to the top retries. */
    private suspend fun performOlderLoad(storePeer: UUID) {
        // The newest page decides where older paging starts; let it land first.
        threadLoads[storePeer]?.join()
        val token = state.session?.token ?: return
        val me = state.myUserId ?: return
        if (!isUnlocked()) return
        val thread = state.messages(storePeer) ?: return
        val oldest = thread.firstOrNull { !it.pendingSync } ?: return
        val isNotes = state.isNotes(storePeer)
        val apiPeer = if (isNotes) me else storePeer
        val known = thread.mapTo(HashSet()) { it.id }
        val generation = state.lockGeneration
        try {
            val cursor = HistoryCursor(oldest.createdAt, oldest.createdAtWire ?: cursorFallback(oldest.createdAt), oldest.id)
            val page = fetchHistoryPage(apiPeer, storePeer, cursor, HISTORY_PAGE_SIZE, token, me)
            currentCoroutineContext().ensureActive()
            // Locked meanwhile (the thread is gone, `:1480`).
            if (generation != state.lockGeneration || state.messages(storePeer) == null) return
            publishHistoryPage(page, storePeer)
            if (page.older == null || (state.messages(storePeer)?.size ?: 0) >= HISTORY_MAX_MESSAGES) {
                exhaustedFlow.value += storePeer
            }
            if (!isNotes) {
                for (id in page.inboundIds) if (id !in known) ignoringErrors { backend.markDelivered(token, id) }
            }
            state.persistThread(storePeer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The cursor is the thread itself: the next scroll to the top tries again.
        }
    }

    /**
     * Walks a few older pages in behind the newest one while the chat stays open, one at a time
     * with a pause, until [OLDER_PREFETCH_TARGET] messages (`startOlderPrefetch`, `MessagingController.swift:1501-1518`).
     */
    private fun startOlderPrefetch(peer: UUID) {
        if (prefetches.containsKey(peer)) return
        prefetches[peer] = scope.launch {
            try {
                while (isActive) {
                    delay(OLDER_PREFETCH_PAUSE_MS)
                    if (state.activePeerId != peer || !hasOlderHistory(peer)) break
                    val before = state.messages(peer)?.size ?: 0
                    if (before >= OLDER_PREFETCH_TARGET) break
                    loadOlderMessages(peer)
                    // Offline or nothing new: stop rather than spin; scrolling retries.
                    if ((state.messages(peer)?.size ?: 0) <= before) break
                }
            } finally {
                val self = currentCoroutineContext().job
                if (prefetches[peer] === self) prefetches.remove(peer)
            }
        }
    }

    /** A chat is being cleared: its load stops (`clearChatLocally`, `MessagingController.swift:2065-2067`). */
    fun cancelLoad(peer: UUID) {
        threadLoads.remove(peer)?.cancel()
        threadLoadReconciles.remove(peer)
    }

    /** Lock, stop: every thread load stops. */
    fun cancelThreadLoads() {
        // Forget first: a cancelled job's completion handler must not edit the map mid-iteration.
        val running = threadLoads.values.toList()
        threadLoads.clear()
        threadLoadReconciles.clear()
        running.forEach { it.cancel() }
    }

    /** Stops all history paging and forgets what was learned (`cancelHistoryPaging`, `MessagingController.swift:1521-1528`). */
    fun cancelHistoryPaging() {
        val older = olderLoads.values.toList()
        olderLoads.clear()
        older.forEach { it.cancel() }
        prefetches.values.forEach { it.cancel() }
        prefetches.clear()
        exhaustedFlow.value = emptySet()
        loadingOlderFlow.value = emptySet()
    }

    private suspend fun ignoringErrors(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort (iOS `try?`).
        }
    }

    companion object {
        /** Newest page on open and on each refresh (`firstPageSize`, `MessagingController.swift:1120`). */
        const val FIRST_PAGE_SIZE = 40

        /** Older pages; the server's maximum (`historyPageSize`, `:1122`). */
        const val HISTORY_PAGE_SIZE = 100

        /** Hard stop per thread (`historyMaxMessages`, `:1124`). */
        const val HISTORY_MAX_MESSAGES = 4_000

        /** Messages walked in behind the newest page before older ones wait for the reader (`:1126`). */
        const val OLDER_PREFETCH_TARGET = 300

        /** Pause between background pages (`:1128`). */
        const val OLDER_PREFETCH_PAUSE_MS = 600L

        /** Local store and history paging keep 90 calendar days (`LocalMessageStore.retentionDays`). */
        const val RETENTION_DAYS = 90L

        private val MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /**
         * The cursor of a held message without the server's text (a thread saved by an older build):
         * its time with milliseconds, as iOS formats it (`ISO8601DateFormatter.string(fromAPI:)`,
         * `MessagingController.swift:1474-1475`).
         */
        fun cursorFallback(createdAt: Instant): String = MILLIS.format(createdAt)
    }
}
