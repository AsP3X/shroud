package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ListStatus
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.userUuid
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.ConversationPeerDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The messaging state every engine shares — iOS keeps it as `@MainActor @Observable` properties of
 * `MessagingController` (`MessagingController.swift:19-165`); here one main-confined object implements
 * the [ThreadState] seam (plan §1.7.7) and holds the chat list, threads, unread counts, the active
 * chat, the list status and the transfers.
 *
 * **Threading.** Every member but the [transfers] board is main-only (plan §1.1 rule 3). Every write
 * is equality-guarded, so a poll that brings the same data recomposes nothing
 * (`MessagingController.swift:785-787`).
 *
 * **Disk.** [MessagingStore] calls are blocking; they run one at a time, in call order, on a serial
 * view of [io] — so a snapshot written later never lands before one written earlier, and [flush]
 * waits for everything queued before the store's own flush (messaging-core §23.3). Every thread
 * written goes through [settle] first (`settledReactions`, `MessagingController.swift:4690-4718`).
 *
 * **Lock generation.** [lockGeneration] moves on every lock, stop and wipe halt; an engine that
 * started before compares it before publishing (messaging-core §23 item 5).
 *
 * @param roster contacts and incoming requests for the sealed roster (the contacts controller's lists).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadStore(
    private val sessionFlow: StateFlow<Session?>,
    private val store: MessagingStore,
    private val scope: CoroutineScope,
    io: CoroutineDispatcher,
    private val roster: () -> Pair<List<ContactItemDto>, List<ContactRequestDto>>,
    private val onlineNow: () -> Boolean,
    private val realtimeConnectedNow: () -> Boolean,
) : ThreadState {
    /** What the store does for the controller that iOS's controller does in place. Set once by [MessagingController]. */
    internal interface Host {
        suspend fun refreshConversations(force: Boolean)
        fun refreshConversationsSoon()
        fun isMuted(storePeer: UUID): Boolean
        fun announceReaction(storePeer: UUID)
        fun activePeerChanged(peer: UUID?)
    }

    internal var host: Host = object : Host {
        override suspend fun refreshConversations(force: Boolean) = Unit
        override fun refreshConversationsSoon() = Unit
        override fun isMuted(storePeer: UUID): Boolean = false
        override fun announceReaction(storePeer: UUID) = Unit
        override fun activePeerChanged(peer: UUID?) = Unit
    }

    /** Our thread before it is written (the reaction engine's `settled`); identity until the controller sets it. */
    internal var settle: (List<ChatMessage>) -> List<ChatMessage> = { it }

    /** Drops what a purge leaves behind outside the store: the hydrate job of each id (the media loader). */
    internal var onPurge: (Collection<UUID>) -> Unit = {}

    /**
     * Whether what memory holds may be written: false until the sealed cache was read
     * (hydrate) and again once memory was dropped (lock, stop) — an empty thread map written
     * then would delete every thread file. iOS gets this from the repository losing its key
     * (`local.setHistoryKey(nil)`, `MessagingController.swift:553, 660`).
     */
    var writable: Boolean = false

    /** Serial writer of every store call (messaging-core §23.3). */
    private val disk: CoroutineDispatcher = io.limitedParallelism(1)

    private val threadsFlow = MutableStateFlow<Map<UUID, List<ChatMessage>>>(emptyMap())
    private val conversationsFlow = MutableStateFlow<List<ConversationItemDto>>(emptyList())
    private val unreadFlow = MutableStateFlow<Map<UUID, Int>>(emptyMap())
    private val activePeerFlow = MutableStateFlow<UUID?>(null)
    private val lastErrorFlow = MutableStateFlow<String?>(null)
    private val offlineFlow = MutableStateFlow(false)
    private val listStatusFlow = MutableStateFlow(ListStatus())
    private val sinks = CopyOnWriteArrayList<MessageArtifactSinks>()

    /** Shared transcripts whose voice note is not in a thread yet (`pendingSharedTranscripts`, `MessagingController.swift:139`). */
    private val pendingSharedTranscripts = LinkedHashMap<UUID, String>()

    /** Annotation ids purged this session, so a reloaded deleted annotation is not purged again (Android D5). */
    private val purgedAnnotations = HashSet<UUID>()

    override val transfers: TransferBoard = TransferBoard()

    // ---- ThreadState ---------------------------------------------------------------------------

    override val session: Session? get() = sessionFlow.value
    override val myUserId: UUID? get() = sessionFlow.value?.userUuid

    override var lockGeneration: Long = 0L
        private set

    override val threads: StateFlow<Map<UUID, List<ChatMessage>>> = threadsFlow.asStateFlow()

    override fun messages(storePeer: UUID): List<ChatMessage>? = threadsFlow.value[storePeer]

    override fun edit(storePeer: UUID, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        val current = threadsFlow.value[storePeer]
        val next = transform(current ?: emptyList())
        if (current == null && next.isEmpty()) return
        if (next != current) setThread(storePeer, next)
    }

    override fun update(messageId: UUID, transform: (ChatMessage) -> ChatMessage): Boolean {
        for ((peer, thread) in threadsFlow.value) {
            val index = thread.indexOfFirst { it.id == messageId }
            if (index < 0) continue
            val updated = transform(thread[index])
            if (updated != thread[index]) setThread(peer, thread.toMutableList().also { it[index] = updated })
            return true
        }
        return false
    }

    override fun rekey(storePeer: UUID, optimisticId: UUID, sent: ChatMessage) {
        // `MessagingController.swift:4866-4885` (text), `:2452-2470` / `:4801-4815` (Notes).
        val list = threadsFlow.value[storePeer].orEmpty()
        val index = list.indexOfFirst { it.id == optimisticId }
        val next = when {
            index >= 0 -> list.mapIndexedNotNull { i, message ->
                when {
                    i == index -> sent
                    message.id == sent.id -> null
                    else -> message
                }
            }
            list.any { it.id == sent.id } -> list.map { if (it.id == sent.id) sent else it }
            else -> (list + sent).sortedBy { it.createdAt }
        }
        setThread(storePeer, foldSharedTranscripts(next))
        if (isNotes(storePeer)) {
            // The shared send helpers may have parked a copy under our own id (`:4812-4815`).
            myUserId?.takeIf { it != NOTES_PEER_ID }?.let { me ->
                threadsFlow.value[me]?.let { stray ->
                    val kept = stray.filterNot { it.id == optimisticId || it.id == sent.id }
                    if (kept.size != stray.size) setThread(me, kept.ifEmpty { null })
                }
            }
        }
        if (optimisticId != sent.id) {
            onDisk { store.removeCaches(listOf(optimisticId)) }
            transfers.rekey(optimisticId, sent.id)
            for (sink in sinks) sink.onMessageRekeyed(optimisticId, sent.id)
        }
        persistSnapshot()
    }

    override fun peerFor(messageId: UUID): UUID? =
        threadsFlow.value.entries.firstOrNull { (_, thread) -> thread.any { it.id == messageId } }?.key

    override fun storePeer(apiPeer: UUID): UUID = if (apiPeer == myUserId) NOTES_PEER_ID else apiPeer

    override fun apiPeer(storePeer: UUID): UUID = if (storePeer == NOTES_PEER_ID) myUserId ?: NOTES_PEER_ID else storePeer

    override fun isNotes(storePeer: UUID): Boolean = storePeer == NOTES_PEER_ID

    /**
     * Writes one thread and the roster (`persistThread`, `MessagingController.swift:4708-4722`); the
     * whole snapshot when the thread is gone. Messages the retention cap dropped leave memory too.
     */
    override fun persistThread(storePeer: UUID) {
        if (!writable) return
        val userId = myUserId ?: return
        val messages = threadsFlow.value[storePeer] ?: return persistSnapshot()
        val settled = settle(messages)
        val roster = rosterSnapshot()
        onDisk {
            val dropped = store.persistThread(userId, storePeer, settled, roster)
            if (dropped.isNotEmpty()) scope.launch { dropFromMemory(dropped) }
        }
    }

    /** Writes everything (`persistSnapshot`, `MessagingController.swift:4688-4706`). */
    override fun persistSnapshot() {
        if (!writable) return
        val userId = myUserId ?: return
        val snapshot = MessagingSnapshot(rosterSnapshot(), threadsFlow.value.mapValues { settle(it.value) })
        onDisk {
            val dropped = store.persist(userId, snapshot)
            if (dropped.isNotEmpty()) scope.launch { dropFromMemory(dropped) }
        }
    }

    /**
     * Scrubs every on-device artifact of [messageIds] (`purgeLocalMessageArtifacts`,
     * `MessagingController.swift:1962-1975`): sealed plaintext and media, the annotations about them
     * and their pending shared transcripts (Android D5, messaging-core §14.6), the UI caches through
     * the sinks, the hydrate jobs and the transfer rings.
     */
    override fun purge(messageIds: Collection<UUID>) {
        if (messageIds.isEmpty()) return
        val targets = messageIds.toSet()
        for (id in targets) pendingSharedTranscripts.remove(id)
        onDisk {
            val annotations = targets.flatMap { store.annotationsFor(it) }
            store.removeCaches(targets + annotations)
        }
        for (sink in sinks) sink.onPurged(targets)
        onPurge(targets)
        for (id in targets) transfers.end(id)
    }

    override fun setLastError(message: String?) {
        if (lastErrorFlow.value != message) lastErrorFlow.value = message
    }

    override val isOffline: Boolean get() = offlineFlow.value
    override val isRealtimeConnected: Boolean get() = realtimeConnectedNow()
    override val isOnline: Boolean get() = onlineNow()

    override fun setOffline(offline: Boolean) {
        if (offlineFlow.value != offline) offlineFlow.value = offline
    }

    override suspend fun refreshConversations(force: Boolean) = host.refreshConversations(force)

    override fun refreshConversationsSoon() = host.refreshConversationsSoon()

    override val conversations: List<ConversationItemDto> get() = conversationsFlow.value

    override fun editConversations(transform: (List<ConversationItemDto>) -> List<ConversationItemDto>) {
        setConversations(transform(conversationsFlow.value))
    }

    override val activePeerId: UUID? get() = activePeerFlow.value

    /** The chat list, then contacts (`username(for:)`, `MessagingController.swift:5850-5854`). */
    override fun username(storePeer: UUID): String? =
        conversationsFlow.value.firstOrNull { it.peer.id == storePeer }?.peer?.username
            ?: roster().first.firstOrNull { it.userId == storePeer }?.username

    override fun isMuted(storePeer: UUID): Boolean = host.isMuted(storePeer)

    override fun announceReaction(storePeer: UUID) = host.announceReaction(storePeer)

    // ---- Controller state ----------------------------------------------------------------------

    val conversationsState: StateFlow<List<ConversationItemDto>> = conversationsFlow.asStateFlow()
    val unread: StateFlow<Map<UUID, Int>> = unreadFlow.asStateFlow()
    val activePeer: StateFlow<UUID?> = activePeerFlow.asStateFlow()
    val lastError: StateFlow<String?> = lastErrorFlow.asStateFlow()
    val offline: StateFlow<Boolean> = offlineFlow.asStateFlow()
    val listStatus: StateFlow<ListStatus> = listStatusFlow.asStateFlow()

    /** Replaces one thread; null removes the key. */
    fun setThread(storePeer: UUID, messages: List<ChatMessage>?) {
        val current = threadsFlow.value
        if (current[storePeer] == messages && (messages != null || storePeer !in current)) return
        threadsFlow.value = if (messages == null) current - storePeer else current + (storePeer to messages)
    }

    fun setThreads(threads: Map<UUID, List<ChatMessage>>) {
        if (threadsFlow.value != threads) threadsFlow.value = threads
    }

    fun setConversations(list: List<ConversationItemDto>) {
        if (conversationsFlow.value != list) conversationsFlow.value = list
    }

    fun editUnread(transform: (Map<UUID, Int>) -> Map<UUID, Int>) {
        val next = transform(unreadFlow.value)
        if (next != unreadFlow.value) unreadFlow.value = next
    }

    /** The chat on screen; the notifier mirrors it (`activePeerID` `didSet`, `MessagingController.swift:62-64`). */
    fun setActivePeer(peer: UUID?) {
        if (activePeerFlow.value == peer) return
        activePeerFlow.value = peer
        host.activePeerChanged(peer)
    }

    fun editListStatus(transform: (ListStatus) -> ListStatus) {
        val next = transform(listStatusFlow.value)
        if (next != listStatusFlow.value) listStatusFlow.value = next
    }

    /** Moves the generation: work that started before must not publish (messaging-core §23 item 5). */
    fun bumpLockGeneration() {
        lockGeneration++
    }

    fun registerSink(sink: MessageArtifactSinks): AutoCloseable {
        sinks += sink
        return AutoCloseable { sinks -= sink }
    }

    /** Chats locked: every sink drops what it holds from memory. */
    fun notifySensitiveMemoryLocked() {
        for (sink in sinks) sink.onSensitiveMemoryLocked()
    }

    /** A transcript shared as an annotation, first writer wins (`noteSharedTranscript`, `MessagingController.swift:3557-3561`). */
    fun noteSharedTranscript(text: String, voiceMessageId: UUID) {
        pendingSharedTranscripts.putIfAbsent(voiceMessageId, text)
    }

    /**
     * Applies the shared transcripts whose voice note is in [thread] and forgets them
     * (`foldSharedTranscripts`, `MessagingController.swift:3564-3571`).
     */
    fun foldSharedTranscripts(thread: List<ChatMessage>): List<ChatMessage> {
        if (pendingSharedTranscripts.isEmpty()) return thread
        val updated = ThreadMessageMerge.applySharedTranscripts(pendingSharedTranscripts, thread)
        for (message in updated) if (message.kind == ChatMessageKind.Voice) pendingSharedTranscripts.remove(message.id)
        return updated
    }

    /**
     * An annotation came back deleted for everyone: purge its cached plaintext once per session
     * (Android D5, messaging-core §14.6 — iOS never purges annotation caches).
     */
    fun purgeAnnotation(annotationId: UUID) {
        if (!purgedAnnotations.add(annotationId)) return
        onDisk { store.removeCaches(listOf(annotationId)) }
    }

    /** Records which voice note an annotation is about, for the purge of its target (Android D5). */
    fun noteAnnotation(targetId: UUID, annotationId: UUID) {
        onDisk { store.noteAnnotation(targetId, annotationId) }
    }

    /** Clears the in-memory transcript and annotation bookkeeping (memory clear, lock). */
    fun clearTranscriptState() {
        pendingSharedTranscripts.clear()
        purgedAnnotations.clear()
    }

    /** Runs [block] on the serial disk writer and returns its result (hydrate, cursor reads). */
    suspend fun <T> onDiskAwait(block: () -> T): T = withContext(disk) { block() }

    /** Waits for every write queued here, then for the store's own writer (messaging-core §23.3). */
    suspend fun flush() {
        withContext(disk) { }
        store.flush()
    }

    /** The sealed roster as of now (`LocalMessageStore.Roster`, messaging-core §22.2). */
    fun rosterSnapshot(): RosterSnapshot {
        val (contacts, requests) = roster()
        return RosterSnapshot(
            conversations = conversationsFlow.value.map(::cached),
            contacts = contacts,
            incomingRequests = requests,
            unreadByPeer = unreadFlow.value.filterValues { it > 0 },
        )
    }

    private fun onDisk(block: () -> Unit) {
        scope.launch(disk) { block() }
    }

    /** Messages the retention cap dropped from disk leave memory too (`MessagingController.swift:4697-4705`). */
    private fun dropFromMemory(dropped: Set<UUID>) {
        val current = threadsFlow.value
        var changed = false
        val next = current.mapValues { (_, messages) ->
            val kept = messages.filterNot { it.id in dropped }
            if (kept.size != messages.size) changed = true
            kept
        }
        if (changed) threadsFlow.value = next
    }

    companion object {
        /** `LocalMessageStore.CachedConversation(dto)` (`LocalMessageStore.swift:484-493`). */
        fun cached(dto: ConversationItemDto) = CachedConversation(
            id = dto.id,
            peerId = dto.peer.id,
            peerUsername = dto.peer.username,
            createdAt = dto.createdAt,
            lastMessageAt = dto.lastMessageAt,
            reactionSeq = dto.reactionSeq,
            unseenReactions = dto.unseenReactions,
        )

        /** `CachedConversation.toDTO()` (`LocalMessageStore.swift:495-504`): no unread count, no mute — the server's. */
        fun restored(cached: CachedConversation) = ConversationItemDto(
            id = cached.id,
            peer = ConversationPeerDto(cached.peerId, cached.peerUsername),
            createdAt = cached.createdAt,
            lastMessageAt = cached.lastMessageAt,
            reactionSeq = cached.reactionSeq,
            unseenReactions = cached.unseenReactions,
        )
    }
}

/**
 * In-flight media transfers by message id (`mediaTransfers`, `MessagingController.swift:2943-3002`;
 * messaging-core §2.2). Progress arrives from I/O threads; every write is one atomic `update`, so
 * it needs no hop to main.
 */
class TransferBoard : MediaTransferBoard {
    private val state = MutableStateFlow<Map<UUID, MediaTransfer>>(emptyMap())

    override val transfers: StateFlow<Map<UUID, MediaTransfer>> = state.asStateFlow()

    /**
     * Starts a ring at [MediaTransfer.Phase.Transferring] with no fraction yet (`beginTransfer`,
     * `:2954-2966`); a video upload that compresses first calls [advance] to `Preparing` at once.
     */
    override fun begin(id: UUID, isUpload: Boolean) {
        state.update { it + (id to MediaTransfer(MediaTransfer.Phase.Transferring, isUpload)) }
    }

    /** `advanceTransfer` (`:2968-2978`): a new phase forgets the fraction; unknown ids are ignored. */
    override fun advance(id: UUID, phase: MediaTransfer.Phase, totalBytes: Long?) {
        state.update { current ->
            val transfer = current[id] ?: return@update current
            current + (id to transfer.copy(phase = phase, fraction = null, totalBytes = totalBytes ?: transfer.totalBytes))
        }
    }

    /** `updateTransfer` (`:2980-2984`): clamped to 0…1. */
    override fun update(id: UUID, fraction: Double) {
        state.update { current ->
            val transfer = current[id] ?: return@update current
            val clamped = fraction.coerceIn(0.0, 1.0)
            if (transfer.fraction == clamped) current else current + (id to transfer.copy(fraction = clamped))
        }
    }

    /** `endTransfer` (`:2986-2988`). */
    override fun end(id: UUID) {
        state.update { if (id in it) it - id else it }
    }

    /** An optimistic message became the server's: its ring follows it. */
    override fun rekey(from: UUID, to: UUID) {
        if (from == to) return
        state.update { current ->
            val transfer = current[from] ?: return@update current
            current - from + (to to transfer)
        }
    }
}
