package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.ContactsHooks
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.contacts.Privacy
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.files.PickedFile
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.ListStatus
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.previewText
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.DeleteConversationResponse
import de.corespace.shroud.core.net.ListMessagesResponse
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.net.MuteChatResponse
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.net.wire.MessageAnnotation
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.notifications.MessageNotifier
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.realtime.MessagingForeground
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** The REST calls of the messaging core: the [de.corespace.shroud.core.net.ShroudApi] subset it uses, as a seam for tests. */
interface MessagingBackend {
    suspend fun conversations(token: String): List<ConversationItemDto>
    suspend fun messages(token: String, peer: UUID, limit: Int, beforeCreatedAt: String?, beforeId: UUID?): ListMessagesResponse
    suspend fun markDelivered(token: String, messageId: UUID)
    suspend fun markChatRead(token: String, peer: UUID)
    suspend fun markReadBulk(token: String, peer: UUID, upToMessageId: UUID)
    suspend fun deleteMessage(token: String, messageId: UUID, scope: MessageDeleteScope)
    suspend fun deleteConversation(token: String, peer: UUID, scope: ConversationDeleteScope): DeleteConversationResponse
    suspend fun muteChat(token: String, peer: UUID, seconds: Long?): MuteChatResponse
    suspend fun unmuteChat(token: String, peer: UUID)
}

/** The device's one socket as messaging holds it (`RealtimeClient`, holder `Messaging`). */
interface MessagingSocket {
    /** Every event, in order (plan §1.7.3). */
    val events: SharedFlow<RealtimeEvent>

    /** `auth.ok` seen on the current socket. */
    val isConnected: StateFlow<Boolean>
    fun hold(token: String)
    fun release()
    fun noteFocus(focused: Boolean)
    suspend fun deliverFocus(): Boolean
    fun sendTyping(peer: UUID, isTyping: Boolean)
    fun sendRecording(peer: UUID, isRecording: Boolean)
}

/**
 * What [MessagingController] is built from (`di/MessagingModule.kt` wires it; tests pass fakes).
 *
 * @param scope `Dispatchers.Main.immediate` in the app (plan §1.1 rule 3).
 * @param hasMedia the decrypted media of a message is in the local media cache (`LocalMediaStore.has`).
 * @param isResumed one of our activities is resumed (`AppPhaseMonitor.isResumed`).
 * @param wipeKeyRecords deletes every ratchet session, and the sender-tag watermarks older builds left (sign-out).
 * @param refreshCallSecrets after a start, as iOS (`refreshCallSecrets`, plan C29: the calls package's).
 * @param pushCovers a UnifiedPush distributor is registered, so a server that heard `focus:false`
 *   pushes to this phone ([de.corespace.shroud.core.push.PushDelivery.suppressesLocalAnnouncements]).
 *   iOS can assume APNs; Android may have no push path at all, and then nothing replaces the local
 *   announcements of events still arriving over a kept socket (plan §1.7.10).
 */
class MessagingDependencies(
    val scope: CoroutineScope,
    val session: StateFlow<Session?>,
    val backend: MessagingBackend,
    val socket: MessagingSocket,
    val keys: OwnKeys,
    val opener: EnvelopeOpener,
    val peerLocks: PeerLocks,
    val store: MessagingStore,
    val hasMedia: (UUID) -> Boolean,
    val contacts: Contacts,
    val peerIdentities: PeerIdentities,
    val privacy: Privacy,
    val notifier: MessageNotifier,
    val sendEngine: (ThreadState) -> SendEngine,
    val reactionsEngine: (ThreadState) -> ReactionsEngine,
    val mediaLoader: (ThreadState) -> MediaLoader,
    val isOnline: () -> Boolean,
    val isResumed: () -> Boolean,
    val wipeKeyRecords: () -> Unit,
    val refreshCallSecrets: suspend () -> Unit,
    val clock: AppClock,
    val pushCovers: () -> Boolean = { false },
    val io: CoroutineDispatcher = Dispatchers.IO,
    val compute: CoroutineDispatcher = Dispatchers.Default,
)

/**
 * The one façade every screen reads chats through — iOS `MessagingController`
 * (`ios/shroud/Services/Messaging/MessagingController.swift`; messaging-core §6–§17, §25.1; plan §1.7.7).
 *
 * It owns the lifecycle (prepare, start, stop, foreground, background, lock), the chat list, event
 * routing and the polling fallback, and delegates: threads and history to [HistoryPager], decryption to
 * [MessageDecoder], deletes to [DeleteEngine], receipts / unread / mutes to [ReadStateEngine], typing to
 * [TypingSignals], sends to the [SendEngine], media to the [MediaLoader], reactions to the
 * [ReactionsEngine] (W2-MSG-SEND), the sealed store to [MessagingStore] (W2-MSG-STORE). Contacts,
 * presence, blocks, privacy and peer keys are the contacts package's (plan C6); this controller drives
 * their lifecycle and implements [ContactsHooks] for them.
 *
 * **Threading** (messaging-core §23): main-confined like iOS's `@MainActor`; every state write is
 * equality-guarded; no read-modify-write spans a suspension; CPU crypto runs on
 * [MessagingDependencies.compute] under the peer's lock, disk on one serial writer.
 *
 * Ids are UUIDs; "peer" in this API is the thread key — [NOTES_PEER_ID] for Notes.
 */
class MessagingController(private val deps: MessagingDependencies) : MessagingForeground, ContactsHooks {
    private val scope = deps.scope
    private val socket = deps.socket
    private val contacts = deps.contacts
    private val privacy = deps.privacy
    private val notifier = deps.notifier
    private val keys = deps.keys

    private val state = ThreadStore(
        sessionFlow = deps.session,
        store = deps.store,
        scope = scope,
        io = deps.io,
        roster = { contacts.contacts.value to contacts.incomingRequests.value },
        onlineNow = deps.isOnline,
        realtimeConnectedNow = { socket.isConnected.value },
    )
    private val send: SendEngine = deps.sendEngine(state)
    private val reactions: ReactionsEngine = deps.reactionsEngine(state)
    private val media: MediaLoader = deps.mediaLoader(state)
    private val typing = TypingSignals(
        scope = scope,
        sender = object : TypingSignals.Sender {
            override fun sendTyping(peer: UUID, isTyping: Boolean) = socket.sendTyping(peer, isTyping)
            override fun sendRecording(peer: UUID, isRecording: Boolean) = socket.sendRecording(peer, isRecording)
        },
        allowed = { privacy.settings.value.sendTyping },
        elapsedMillis = deps.clock::elapsedMillis,
    )
    private val readState = ReadStateEngine(state, deps.backend, { notifier }, deps.isResumed, scope, deps.clock)
    private val decoder = MessageDecoder(deps.store, keys, deps.opener, deps.peerLocks, deps.hasMedia, deps.io, deps.compute)
    private val pager = HistoryPager(
        state = state,
        backend = deps.backend,
        decoder = decoder,
        decodeContext = ::decodeContext,
        isUnlocked = { keys.isUnlocked },
        reactions = { reactions },
        readState = readState,
        refreshPresence = { peer -> contacts.refreshPresence(listOf(peer)) },
        scope = scope,
        clock = deps.clock,
    )
    private val deletes = DeleteEngine(state, deps.backend, pager, typing, deps.isOnline, ::refreshConversations, scope)
    private val polling = PollingLoop(scope, PollHost())

    /** The chats use the socket; a call may keep it open after that, and then only `call.*` matters (`MessagingController.swift:106-108`). */
    private var realtimeActive = false

    /** `prepareCachedState` loaded the sealed cache; the next [start] skips it (`hydratedAheadOfStart`, `:102-103`). */
    private var hydratedAheadOfStart = false

    /** The chats' memory was locked: the next return to the app reads the sealed cache again (`MessagingController.swift:602-606`). */
    private var needsHydrate = false

    /** Bumped on every return to the app, so a background close still in flight leaves the new socket alone (`:594-596`). */
    private var foregroundEpoch = 0

    /** Bumped by every stop: work that started before publishes nothing. */
    private var activityGeneration = 0L

    @Volatile private var conversationsRefresh: Job? = null

    /**
     * The next successful chat list is the first since the chats were unlocked: notifications of
     * chats read or seen elsewhere meanwhile close then (web-parity §7.6; web `AppShell.tsx:802-812`).
     */
    private var settleNotificationsOnNextList = false

    /** The catch-up sequences of start, foreground and reconnect (iOS's outbound queue cancel stops their flush). */
    private val sessionJobs = ArrayList<Job>()

    init {
        // `activePeerID` `didSet` (`MessagingController.swift:62-64`): banners for the open chat are suppressed.
        state.onActivePeerChanged = { notifier.activePeerId = it }
        state.settle = reactions::settled
        state.onPurge = { ids -> ids.forEach(media::cancel) }
        // Engines that hold message state of their own hear about purges, locks and re-keys too.
        for (engine in listOf<Any>(send, reactions, media)) {
            if (engine is MessageArtifactSinks) state.registerSink(engine)
        }
        contacts.bind(this)
        scope.launch {
            // `bind`, `MessagingController.swift:435-442`: one listener for the controller's life,
            // acting only while the chats use the socket.
            socket.events.collect { if (realtimeActive) handleRealtime(it) }
        }
        scope.launch {
            var previous = privacy.settings.value
            privacy.settings.collect { settings ->
                adoptPrivacySettings(previous, settings)
                previous = settings
            }
        }
    }

    // ---- Identity and state (plan §1.7.7) --------------------------------------------------------

    /** The signed-in account, for views that tell "You" from the peer (`MessagingController.swift:423-427`). */
    val myUserId: UUID? get() = state.myUserId
    val myUsername: String? get() = deps.session.value?.username

    fun isNotesChat(peer: UUID): Boolean = NotesLocal.isNotes(peer)

    /** Server order (`last_message_at` desc); Notes not included — the UI pins that row itself. */
    val conversations: StateFlow<List<ConversationItemDto>> = state.conversationsState
    val listStatus: StateFlow<ListStatus> = state.listStatus
    val lastError: StateFlow<String?> = state.lastError
    val isOffline: StateFlow<Boolean> = state.offline
    override val isRealtimeConnected: StateFlow<Boolean> = socket.isConnected

    /** Thread key → messages, oldest first; Notes under [NOTES_PEER_ID]. */
    val threads: StateFlow<Map<UUID, List<ChatMessage>>> = state.threads

    /** One chat's messages, emitted only when that chat changed. */
    fun thread(peer: UUID): Flow<List<ChatMessage>> = state.threads.map { it[peer].orEmpty() }.distinctUntilChanged()

    val activePeerId: StateFlow<UUID?> = state.activePeer
    val olderHistoryExhausted: StateFlow<Set<UUID>> = pager.olderHistoryExhausted
    val loadingOlderPeerIds: StateFlow<Set<UUID>> = pager.loadingOlderPeerIds
    val mediaTransfers: StateFlow<Map<UUID, MediaTransfer>> = state.transfers.transfers

    /** The list subtitle of a chat (`preview(forPeer:)`, `MessagingController.swift:4112-4118`). */
    fun preview(peer: UUID): String = ChatListFormatting.preview(peer, state.threads.value, isNotesChat(peer))

    /**
     * The text of one message, decrypted on this phone, for a notification. Null when the chats
     * are locked, the message is gone, or it cannot be opened. Nothing here leaves the phone.
     *
     * A copy already in a thread is used as it is. Otherwise one newest page is fetched and opened
     * through the same decoder as the socket, so the ratchet key is spent once and the later
     * history sync reads the cache.
     */
    suspend fun notificationText(peerUserId: UUID, messageId: UUID): String? {
        val held = state.threads.value.values.asSequence().flatten().firstOrNull { it.id == messageId }
        if (held != null) {
            if (held.deleted || ThreadMessageMerge.isFailedDecryptText(held.text)) return null
            return held.previewText
        }
        if (!keys.isUnlocked) return null
        val token = deps.session.value?.token ?: return null
        val me = state.myUserId ?: return null
        val generation = state.lockGeneration
        val page = try {
            deps.backend.messages(token, peerUserId, NOTIFICATION_PAGE, beforeCreatedAt = null, beforeId = null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (generation != state.lockGeneration || !keys.isUnlocked) return null
        val dto = page.messages.firstOrNull { it.id == messageId } ?: return null
        if (dto.contentType == ContentType.ANNOTATION || dto.deletedForEveryone) return null
        val decoded = try {
            decoder.decode(dto, decodeContext(me), forcePeer = peerUserId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (generation != state.lockGeneration || decoded.deleted || ThreadMessageMerge.isFailedDecryptText(decoded.text)) return null
        return decoded.previewText
    }

    /** The last Notes activity, for the pinned row (`notesLastActivity`, `:4121-4123`). */
    fun notesLastActivity(): Instant? = state.threads.value[NOTES_PEER_ID]?.lastOrNull()?.createdAt

    val peerActivities: StateFlow<Map<UUID, ChatPeerActivity>> = typing.activities
    fun peerActivity(peer: UUID): ChatPeerActivity? = typing.peerActivity(peer)

    val unreadCounts: StateFlow<Map<UUID, Int>> = state.unread
    fun unreadCount(peer: UUID): Int = readState.unreadCount(peer)
    fun unreadTotal(includeMuted: Boolean): Int = readState.unreadTotal(includeMuted)

    // ---- Lifecycle (messaging-core §6) -------------------------------------------------------------

    /**
     * Loads the sealed cache ahead of [start] (`prepareCachedState`, `MessagingController.swift:450-455`):
     * the lock screen calls it while it still says "Checking…", so the unlock reveal is not frozen by
     * disk and crypto.
     */
    suspend fun prepareCachedState() {
        if (hydratedAheadOfStart || !keys.isUnlocked) return
        hydrateFromDisk()
        hydratedAheadOfStart = true
    }

    /** The unlock it was prepared for did not go through (`discardPreparedCachedState`, `:458-462`). */
    fun discardPreparedCachedState() {
        if (!hydratedAheadOfStart) return
        scope.launch { state.onDiskAwait { deps.store.lockSensitiveMemory() } }
        lockMemoryNow()
        clearInMemoryState()
        // iOS clears contacts and incoming requests in `clearInMemoryState` (`:566-591`); here those
        // lists live in the contacts engine, which only `stop` clears (W2-CONTACTS change request).
        contacts.stop(false)
    }

    /**
     * The chats were unlocked (`start`, `MessagingController.swift:464-488`): cached state first, then
     * the socket, both polls, and the catch-up — contacts, chats, privacy, server config, the outbox,
     * call secrets.
     */
    fun start() {
        val token = deps.session.value?.token ?: return
        state.setOffline(!deps.isOnline())
        realtimeActive = true
        settleNotificationsOnNextList = true
        socket.hold(token)
        polling.start()
        val generation = activityGeneration
        launchSession {
            // Paint cached chats and contacts at once, so a cold or offline start feels instant.
            if (hydratedAheadOfStart) hydratedAheadOfStart = false else hydrateFromDisk()
            if (generation != activityGeneration) return@launchSession
            contacts.start()
            contacts.refresh()
            refreshConversations()
            privacy.refresh()
            reactions.refreshServerConfig()
            send.flushOutbox()
            deps.refreshCallSecrets()
        }
    }

    /**
     * Stops realtime work and drops memory (`stop`, `MessagingController.swift:494-507`). [wipeDisk]
     * (sign-out not handled by the wipe overlay) also deletes the user's sealed store, the caches, peer
     * pins and ratchet sessions; otherwise the snapshot is written first and kept for an
     * offline reopen.
     */
    suspend fun stop(wipeDisk: Boolean) {
        if (!wipeDisk) {
            state.persistSnapshot()
        } else {
            reactions.reset()
            // Android addition (invisible): a download finishing after the sign-out must not write
            // media for an account that is gone, as `haltForDeviceWipe` already ensures.
            media.cancelAll()
        }
        stopActivity(contactsWipe = wipeDisk)
        if (wipeDisk) {
            clearLocalData()
        } else {
            clearInMemoryState()
            // `local.setHistoryKey(nil)` (`:553`): the snapshot above reaches disk first (one serial
            // writer), then the store forgets the plaintext it holds in memory.
            state.onDiskAwait { deps.store.lockSensitiveMemory() }
        }
    }

    /**
     * Log Out / removal wipe, before anything is deleted (`haltForDeviceWipe`, `MessagingController.swift:514-518`):
     * every writer stops and memory goes — no snapshot, which would only refill the stores being wiped.
     */
    fun haltForDeviceWipe() {
        reactions.reset()
        media.cancelAll()
        stopActivity(contactsWipe = false)
        clearInMemoryState()
    }

    /** `stopActivity`, `MessagingController.swift:520-554`. */
    private fun stopActivity(contactsWipe: Boolean) {
        state.writable = false
        polling.stop()
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        realtimeActive = false
        socket.release()
        // `outboundQueue.cancel()` (`:526`): sends in flight stop calling the server; their bubbles
        // stay queued for the next start.
        send.cancelAll()
        state.setActivePeer(null)
        // Drop (don't cancel) an in-flight list refresh: cancelling surfaced a spurious network
        // error on sign-out. The generation keeps it from publishing.
        conversationsRefresh = null
        pager.cancelThreadLoads()
        pager.cancelHistoryPaging()
        // The next sign-in is a first load again: the skeleton may come back.
        state.editListStatus { ListStatus() }
        contacts.stop(contactsWipe)
        privacy.reset()
        peerIdentitiesClearMemory()
        state.setLastError(null)
        state.setOffline(false)
        activityGeneration++
        state.bumpLockGeneration()
    }

    /** Sign-out: the user's sealed store, the caches, pins and ratchets (`clearLocalData`, `:558-564`). */
    private suspend fun clearLocalData() {
        val userId = state.myUserId
        state.onDiskAwait { deps.store.clear(userId) }
        clearInMemoryState()
        deps.peerIdentities.wipe()
        deps.wipeKeyRecords()
    }

    /** `clearInMemoryState`, `MessagingController.swift:566-591`. The contacts' lists clear in `contacts.stop`. */
    private fun clearInMemoryState() {
        hydratedAheadOfStart = false
        state.writable = false
        state.setConversations(emptyList())
        state.setThreads(emptyMap())
        typing.clearAll()
        state.editUnread { emptyMap() }
        readState.clear()
        state.clearTranscriptState()
        state.editListStatus { it.copy(isLoadingChats = false) }
        state.bumpLockGeneration()
    }

    /**
     * The app came to the front, chats unlocked (`handleAppBecameActive`, `MessagingController.swift:598-626`):
     * the server hears `focus:true` at once, the polls restart, and everything catches up — the open
     * chat with a reconcile.
     */
    override fun handleAppBecameActive() {
        val token = deps.session.value?.token ?: return
        foregroundEpoch++
        state.setOffline(!deps.isOnline())
        realtimeActive = true
        notifier.setPushCoversBackground(false)
        socket.noteFocus(true)
        socket.hold(token)
        scope.launch { socket.deliverFocus() }
        if (!polling.isRunning) polling.start()
        contacts.onForeground()
        val generation = activityGeneration
        launchSession {
            // The memory was locked meanwhile: read the sealed cache again (`:602-606`).
            if (needsHydrate && keys.isUnlocked) hydrateFromDisk()
            if (generation != activityGeneration) return@launchSession
            contacts.refresh()
            refreshConversations()
            privacy.refresh()
            reactions.refreshServerConfig()
            state.activePeerId?.takeUnless(::isNotesChat)?.let { pager.loadThread(it, reconcile = true) }
            send.flushOutbox()
        }
    }

    /**
     * The app left the front (`leaveForeground`, `MessagingController.swift:633-655`). The server is
     * told first, so a push goes out even if the socket's close never leaves the phone; a return
     * during the 200 ms waits cancels the rest. [keepSocket] (a call, the background connection)
     * keeps the socket. Keeps threads, caches and keys — that is [lockSensitiveMemory]'s job.
     */
    override suspend fun leaveForeground(keepSocket: Boolean) {
        val epoch = foregroundEpoch
        socket.noteFocus(false)
        val told = socket.deliverFocus()
        delay(RADIO_GRACE_MS)
        if (epoch != foregroundEpoch) return
        // Push covers the background only if the server was told and a push path exists (review W2).
        notifier.setPushCoversBackground(told && deps.pushCovers())
        if (keepSocket) return
        polling.stop()
        contacts.onBackground()
        realtimeActive = false
        socket.release()
        readState.updateBadge()
        // The close is queued; give it a moment to leave the radio.
        delay(RADIO_GRACE_MS)
    }

    /**
     * Drops decrypted threads from memory; the sealed files stay (`lockSensitiveMemory`,
     * `MessagingController.swift:657-675`). Batched reaction saves and every queued write reach disk
     * before the store forgets its key. The lists stay for a less jarring re-unlock.
     */
    suspend fun lockSensitiveMemory() {
        reactions.flushPendingSaves()
        // From here nothing new reaches the writer, and work in flight publishes nothing: what
        // memory holds is about to go, and an event landing during the flush must not be saved
        // after the store forgot its key.
        state.writable = false
        state.bumpLockGeneration()
        state.onDiskAwait { deps.store.lockSensitiveMemory() }
        lockMemoryNow()
    }

    private fun lockMemoryNow() {
        hydratedAheadOfStart = false
        needsHydrate = true
        state.writable = false
        state.bumpLockGeneration()
        state.setThreads(emptyMap())
        typing.clearAll()
        state.editUnread { emptyMap() }
        state.setActivePeer(null)
        pager.cancelThreadLoads()
        pager.cancelHistoryPaging()
        state.clearTranscriptState()
        state.notifySensitiveMemoryLocked()
    }

    /**
     * A chat screen appeared ([peer]) or went (null) (`setActivePeer`, `MessagingController.swift:692-703`).
     * Opening clears its count — saved at once, or a restart would bring the badge back — and reads it.
     */
    fun setActivePeer(peer: UUID?) {
        state.setActivePeer(peer)
        if (peer == null) return
        if (state.unread.value[peer] != 0) {
            state.editUnread { it + (peer to 0) }
            state.persistThread(peer)
        }
        readState.didReadChat(peer)
    }

    /**
     * The network came or went (`handleConnectivityChanged`, `MessagingController.swift:677-690`); on
     * its return everything catches up, the open chat with a reconcile.
     */
    private fun handleConnectivityChanged(isOnline: Boolean) {
        val wasOffline = state.isOffline
        state.setOffline(!isOnline)
        if (!isOnline || !wasOffline) return
        if (deps.session.value?.token == null) return
        contacts.onConnectivityRegained()
        launchSession {
            refreshConversations(force = true)
            state.activePeerId?.takeUnless(::isNotesChat)?.let { pager.loadThread(it, reconcile = true) }
            send.flushOutbox()
        }
    }

    /**
     * The sealed cache into memory (`hydrateFromDisk`, `MessagingController.swift:4656-4686`). A chat
     * that already holds messages (an event that arrived while the disk was read) merges with the
     * cached copy instead of replacing it — iOS hydrates synchronously, so it never meets that case.
     */
    private suspend fun hydrateFromDisk() {
        val userId = state.myUserId
        if (userId == null || !keys.isUnlocked) {
            // No key, nothing readable (iOS: the repository has no key and returns only Notes). Memory
            // stays unwritable — an empty map saved now would delete every thread file — and the
            // next return to the app with the key reads the cache.
            if (state.messages(NOTES_PEER_ID) == null) state.setThread(NOTES_PEER_ID, emptyList())
            if (userId != null) needsHydrate = true
            return
        }
        val generation = state.lockGeneration
        val hydrated = state.onDiskAwait { deps.store.hydrate(userId) }
        if (generation != state.lockGeneration) return
        contacts.hydrate(hydrated.roster.contacts, hydrated.roster.incomingRequests)
        if (state.conversations.isEmpty() && hydrated.roster.conversations.isNotEmpty()) {
            state.setConversations(hydrated.roster.conversations.map(ThreadStore::restored))
            state.editListStatus { it.copy(hasLoadedChats = true) }
        }
        val restored = state.threads.value.toMutableMap()
        for ((peer, messages) in hydrated.threads) {
            val inMemory = restored[peer]
            restored[peer] = if (inMemory.isNullOrEmpty()) messages else ThreadMessageMerge.mergeThread(inMemory, messages, emptyList())
        }
        restored.putIfAbsent(NOTES_PEER_ID, emptyList())
        state.setThreads(restored)
        state.editUnread { it + hydrated.roster.unreadByPeer }
        // Memory now holds what the disk had: writes may go out again.
        needsHydrate = false
        state.writable = true
    }

    // ---- Chat list (messaging-core §7.3) -----------------------------------------------------------

    /**
     * Reloads the chat list (`refreshConversations`, `MessagingController.swift:1002-1014`). Overlapping
     * callers share one fetch; [force] (after a write) waits out the running one and fetches again, so
     * the caller sees its own change.
     *
     * The fetch runs in the controller's scope and clears its own entry when it ends, not the caller:
     * iOS awaits an unstructured `Task` whose cleanup always runs, while a cancelled Kotlin caller
     * (a poll stopped by `leaveForeground`, a screen that went away) never gets past its `join`. A
     * finished job left behind would turn every later non-forced refresh into a no-op.
     */
    suspend fun refreshConversations(force: Boolean = false) {
        conversationsRefresh?.takeIf { it.isActive }?.let { existing ->
            existing.join()
            if (!force) return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { performConversationsRefresh() }
        conversationsRefresh = job
        job.invokeOnCompletion { if (conversationsRefresh === job) conversationsRefresh = null }
        job.start()
        job.join()
    }

    /** `performConversationsRefresh`, `MessagingController.swift:1016-1061`. Every write is equality-guarded. */
    private suspend fun performConversationsRefresh() {
        val token = deps.session.value?.token
        if (token == null) {
            // Settle the flag, so the skeleton cannot outlive the load (`:1018-1022`).
            state.editListStatus { it.copy(hasLoadedChats = true) }
            return
        }
        val generation = activityGeneration
        val showsLoading = !state.listStatus.value.hasLoadedChats
        if (showsLoading) state.editListStatus { it.copy(isLoadingChats = true) }
        try {
            val fetched = deps.backend.conversations(token)
            if (generation != activityGeneration) return
            val list = readState.applyingLocalChatState(reactions.applyingLocalSeen(fetched))
            val changed = state.conversations != list
            state.setConversations(list)
            list.firstOrNull()?.let { readState.serverKeepsReadMarkers = it.unreadCount != null }
            readState.adoptServerUnreadCounts(list)
            state.editListStatus { it.copy(hasLoadedServerChats = true, chatsError = null) }
            readState.retryChatReads()
            state.setLastError(null)
            state.setOffline(false)
            // Every tab switch lands here; an unchanged list has nothing to save.
            if (changed) state.persistSnapshot()
            if (settleNotificationsOnNextList) {
                settleNotificationsOnNextList = false
                notifier.closeSettledChats(
                    readChats = list.filter { (it.unreadCount ?: 0) == 0 }.map { it.id },
                    reactionsSeenChats = list.filter { (it.unseenReactions ?: 0) == 0 }.map { it.id },
                )
            }
            // Something reacted to our messages while this chat is open: it is being seen (`:1047-1049`).
            state.activePeerId?.takeIf(state::hasPendingUnseenReactions)?.let { reactions.markSeen(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != activityGeneration) return
            if (state.conversations.isNotEmpty() || state.messages(NOTES_PEER_ID)?.isNotEmpty() == true) {
                state.editListStatus { it.copy(chatsError = null) }
                state.setOffline(true)
            } else {
                val message = messagingUserMessage(e)
                state.editListStatus { it.copy(chatsError = message) }
                state.setLastError(message)
            }
        } finally {
            if (generation == activityGeneration) {
                state.editListStatus { status ->
                    status.copy(isLoadingChats = if (showsLoading) false else status.isLoadingChats, hasLoadedChats = true)
                }
            }
        }
    }

    /**
     * Rewrites the chat list; published only when it changed. The reaction engine's heart badges
     * use it (`MessagingController.swift:5537-5542, 5557, 5582-5583`; W2-MSG-SEND's `SendHost`).
     */
    fun editConversations(transform: (List<ConversationItemDto>) -> List<ConversationItemDto>) = state.editConversations(transform)

    /** Who a peer is, for a banner: the chat list, then contacts (`username(for:)`, `MessagingController.swift:5850-5854`). */
    fun username(peer: UUID): String? = state.username(peer)

    /**
     * Applies the transcripts shared as annotations whose voice note is in [thread] and forgets them
     * (`foldSharedTranscripts`, `MessagingController.swift:3564-3571`); a sent voice note folds through
     * it (`:3776`). The pending map lives here, with the ingest and paging paths.
     */
    fun foldSharedTranscripts(thread: List<ChatMessage>): List<ChatMessage> = state.foldSharedTranscripts(thread)

    // ---- Threads (messaging-core §8) ---------------------------------------------------------------

    /** See [HistoryPager.loadThread]. */
    suspend fun loadThread(peer: UUID, activate: Boolean = true, reconcile: Boolean = false) =
        pager.loadThread(peer, activate, reconcile)

    /** See [HistoryPager.loadOlderMessages]. */
    suspend fun loadOlderMessages(peer: UUID) = pager.loadOlderMessages(peer)

    // ---- Sends: the SendEngine (W2-MSG-SEND) -------------------------------------------------------

    suspend fun sendText(text: String, peer: UUID, replyTo: MessageReplyReference? = null, linkPreview: LinkPreviewAttachment? = null) =
        send.sendText(text, peer, replyTo, linkPreview)

    fun sendTodo(text: String) = send.sendTodo(text)
    fun toggleTodo(messageId: UUID) = send.toggleTodo(messageId)
    fun deleteLocalNote(messageId: UUID) = send.deleteLocalNote(messageId)

    suspend fun sendImage(
        source: MediaImageSource,
        peer: UUID,
        caption: String = "",
        quality: MediaComposeQuality = MediaComposeQuality.Original,
        edits: MediaEdits = MediaEdits.Identity,
        replyTo: MessageReplyReference? = null,
    ): String? = send.sendImage(source, peer, caption, quality, edits, replyTo)

    suspend fun sendVideo(plan: VideoSendPlan, peer: UUID, replyTo: MessageReplyReference? = null): String? =
        send.sendVideo(plan, peer, replyTo)

    suspend fun sendVoice(
        audio: ByteArray,
        durationMs: Int,
        peer: UUID,
        waveform: ByteArray? = null,
        transcript: String? = null,
        replyTo: MessageReplyReference? = null,
        transcriptProvider: (suspend (messageId: UUID) -> String?)? = null,
    ): String? = send.sendVoice(audio, durationMs, peer, waveform, transcript, replyTo, transcriptProvider)

    /**
     * One picked file, sent as it is (docs/file-sharing.md). [file] comes from
     * `media.fileIntake.inspect`; null when it went out or waits for the network, else the user's sentence.
     */
    suspend fun sendFile(file: PickedFile, peer: UUID, caption: String = "", replyTo: MessageReplyReference? = null): String? =
        send.sendFile(file, peer, caption, replyTo)

    suspend fun retryFailedImage(messageId: UUID, peer: UUID): String? = send.retryFailedImage(messageId, peer)
    suspend fun retryFailedVideo(messageId: UUID, peer: UUID): String? = send.retryFailedVideo(messageId, peer)
    suspend fun retryFailedFile(messageId: UUID, peer: UUID): String? = send.retryFailedFile(messageId, peer)
    suspend fun shareTranscript(transcript: String, voiceMessageId: UUID, peer: UUID) = send.shareTranscript(transcript, voiceMessageId, peer)

    // ---- Media on demand: the MediaLoader (W2-MSG-SEND) --------------------------------------------

    suspend fun ensureImageLoaded(message: ChatMessage) = media.ensureImageLoaded(message)
    suspend fun ensureVideoLoaded(message: ChatMessage) = media.ensureVideoLoaded(message)
    suspend fun ensureVoiceLoaded(message: ChatMessage) = media.ensureVoiceLoaded(message)
    suspend fun ensureLinkImageLoaded(message: ChatMessage) = media.ensureLinkImageLoaded(message)

    /** A tap on a file that is not on this phone: downloads it with the ring ([cancelMediaDownload] stops it). */
    suspend fun ensureFileLoaded(message: ChatMessage) = media.ensureFileLoaded(message)

    /** The ring's X: cancels a download; uploads cannot be cancelled (`cancelMediaDownload`, `:2947-2952`). */
    fun cancelMediaDownload(messageId: UUID) = media.cancel(messageId)
    suspend fun mediaBytes(messageId: UUID): ByteArray? = media.mediaBytes(messageId)

    // ---- Deletes, typing, reads, mutes, badge -------------------------------------------------------

    suspend fun deleteMessage(message: ChatMessage, scope: MessageDeleteScope): String? = deletes.deleteMessage(message, scope)
    suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome = deletes.deleteConversation(peer, scope)

    /** The composer's draft is (not) empty (`setTyping`, `MessagingController.swift:2274-2292`). */
    fun setTyping(peer: UUID, isTyping: Boolean) = typing.setTyping(peer, isTyping)

    /** A voice-note take runs or stopped (`setRecording`, `:2295-2321`). */
    fun setRecording(peer: UUID, isRecording: Boolean) = typing.setRecording(peer, isRecording)

    fun markChatRead(peer: UUID) = readState.markChatRead(peer)
    fun mute(peer: UUID): ChatMuteDto? = readState.mute(peer)
    fun isMuted(peer: UUID): Boolean = readState.isMuted(peer)
    fun canMute(peer: UUID): Boolean = readState.canMute(peer)

    /** Mutes a chat on every device of ours; the user-facing error or null (`muteChat`, `:5768-5770`). */
    suspend fun muteChat(peer: UUID, duration: MuteDuration): String? = readState.changeMute(peer, duration, ::refreshConversations)

    suspend fun unmuteChat(peer: UUID): String? = readState.changeMute(peer, null, ::refreshConversations)

    fun updateBadge() = readState.updateBadge()

    // ---- Reactions: the ReactionsEngine (W2-MSG-SEND) ----------------------------------------------

    val reactionLimit: StateFlow<Int> get() = reactions.limit
    val reactionRevisions: StateFlow<Map<UUID, Int>> get() = reactions.revisions
    val reactionFailures: SharedFlow<ReactionFailure> get() = reactions.failures
    fun canReact(message: ChatMessage): Boolean = reactions.canReact(message)
    fun myReactions(message: ChatMessage): List<String> = reactions.myReactions(message)
    fun toggleReaction(emoji: String, messageId: UUID, peer: UUID) = reactions.toggle(emoji, messageId, peer)
    fun setMyReactions(emojis: List<String>, messageId: UUID, peer: UUID) = reactions.set(emojis, messageId, peer)
    fun hasUnseenReactions(peer: UUID): Boolean = reactions.hasUnseen(peer)
    suspend fun refreshServerConfig() = reactions.refreshServerConfig()

    /** Thread caches, voice playback and transcript disclosure register here (plan §1.7.7). */
    fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable = state.registerSink(sink)

    // ---- Events (`handleRealtime`, `MessagingController.swift:4137-4176`; api-realtime §11.15) -------

    private fun handleRealtime(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.MessageNew -> {
                val dto = event.message
                if (dto != null) {
                    scope.launch { ingestIncoming(dto) }
                } else {
                    // The DTO did not decode: refresh the list and the open chat (`:4140-4147`).
                    scope.launch {
                        refreshConversations()
                        state.activePeerId?.let { pager.loadThread(it) }
                    }
                }
            }
            is RealtimeEvent.MessageDelivered -> readState.updateReceipt(event.messageId, ReceiptStatus.Delivered)
            is RealtimeEvent.MessageRead -> readState.onMessageRead(event, state.myUserId)
            is RealtimeEvent.MessageDeleted -> {
                // Unsent for everyone: whatever this phone posted about the chat goes too, open chat
                // or not (web-parity §7.6; web `AppShell.tsx:1549-1553`; iOS keeps them).
                event.conversationId?.let(notifier::clearDelivered)
                deletes.onMessageDeleted(event)
            }
            is RealtimeEvent.MessageReaction -> reactions.apply(event)
            is RealtimeEvent.ReactionsSeen -> reactions.apply(event)
            is RealtimeEvent.ConversationDeleted -> deletes.onConversationDeleted(event)
            is RealtimeEvent.ConversationRead -> readState.onConversationRead(event)
            is RealtimeEvent.ConversationMute -> readState.onConversationMute(event)
            is RealtimeEvent.Typing -> typing.setPeerTyping(event.userId, event.isTyping)
            is RealtimeEvent.Recording -> typing.setPeerRecording(event.userId, event.isRecording)
            // Presence itself is the contacts controller's (plan C6); an offline peer types no more (`:4348-4363`).
            is RealtimeEvent.PresenceUpdate -> if (!event.online) typing.clearPeer(event.userId)
            // contact.* is the contacts controller's, call.* the call controller's (plan C4).
            is RealtimeEvent.ContactChanged, is RealtimeEvent.Connected, is RealtimeEvent.CallRing,
            is RealtimeEvent.CallAccepted, is RealtimeEvent.CallEnded, is RealtimeEvent.CallSignal,
            is RealtimeEvent.Unknown,
            -> Unit
        }
    }

    /**
     * `message.new` (`ingestIncoming`, `MessagingController.swift:4365-4445`; messaging-core §12): ack the
     * peer's message, decrypt it, fold an annotation into its voice note, or append the bubble — the
     * peer's indicator goes, the count rises or the chat is read, a banner is announced; ours from
     * another device marks the chat read.
     *
     * Our own message from another device lands in the thread the conversation names, else the one
     * already holding it, else under our own id (`:4369-4372, 4385-4395`) — as on iOS, a note written
     * on another device shows in Notes at the next Notes load (the self conversation is not in the list).
     */
    private suspend fun ingestIncoming(dto: MessageDto) {
        val me = state.myUserId ?: return
        val token = deps.session.value?.token ?: return
        if (!keys.isUnlocked) return
        val generation = state.lockGeneration
        val fromPeer = dto.senderUserId != me
        // Annotations included (`:4378-4380`).
        if (fromPeer) ignoringErrors { deps.backend.markDelivered(token, dto.id) }
        val threadPeer = if (fromPeer) {
            dto.senderUserId
        } else {
            state.conversations.firstOrNull { it.id == dto.conversationId }?.peer?.id
                ?: state.peerFor(dto.id)
                ?: dto.senderUserId
        }
        val isNotes = state.isNotes(threadPeer)
        val decoded = decoder.decode(dto, decodeContext(me), forcePeer = threadPeer)
        if (generation != state.lockGeneration) return

        if (dto.contentType == ContentType.ANNOTATION) {
            // A transcript shared by the other side or our other device: no bubble, no count.
            if (dto.deletedForEveryone) {
                state.purgeAnnotation(dto.id)
            } else {
                MessageAnnotation.parseTranscript(decoded.text)?.let { shared ->
                    state.noteSharedTranscript(shared.text, shared.messageId)
                    state.noteAnnotation(shared.messageId, dto.id)
                }
            }
            state.messages(threadPeer)?.let { current ->
                val updated = state.foldSharedTranscripts(current)
                if (updated != current) {
                    state.setThread(threadPeer, updated)
                    state.persistThread(threadPeer)
                }
            }
            return
        }

        val chat = if (isNotes) NotesLocal.fromServer(decoded) else decoded
        val current = state.messages(threadPeer).orEmpty()
        if (current.none { it.id == chat.id }) {
            state.setThread(threadPeer, state.foldSharedTranscripts(current + chat))
            if (!chat.isMine) {
                // Their message is what the indicator was for: it takes its place.
                typing.clearPeer(dto.senderUserId)
                if (readState.isReading(threadPeer)) readState.didReadChat(threadPeer) else readState.countArrival(threadPeer)
                notifier.announce(
                    NotificationKind.Message,
                    threadPeer,
                    state.username(threadPeer),
                    dto.conversationId,
                    // A file says its caption, else its name (docs/file-sharing.md §7).
                    if (chat.deleted) null else chat.previewText,
                    readState.isMuted(threadPeer),
                )
            } else {
                // Written on our other device: the chat has been read there.
                readState.markReadLocally(threadPeer, dto.conversationId, dto.createdAt)
            }
        }
        refreshConversations(force = true)
        state.persistSnapshot()
    }

    /**
     * The decoder's view for one message (`decodeMessage`, `MessagingController.swift:4447-4475`): our own
     * key when the sender is us (Notes), the peer's pinned key otherwise (TOFU, `PeerIdentities.resolvePublicKey`).
     */
    private fun decodeContext(me: UUID): MessageDecoder.Context = MessageDecoder.Context(
        me = me,
        conversations = state.conversations,
        threads = state.threads.value,
        resolveSenderKey = { peer ->
            if (peer == me) {
                keys.withKeys { _, ourPublic -> ourPublic.copyOf() } ?: throw CryptoError.Locked
            } else {
                deps.peerIdentities.resolvePublicKey(peer)
            }
        },
    )

    /**
     * Privacy switches as the server confirmed them (`adoptPrivacySettings`, `MessagingController.swift:2156-2181`):
     * read receipts off → ticks step back; typing off → ours stop, theirs go. Presence is the contacts
     * controller's.
     */
    private fun adoptPrivacySettings(previous: PrivacySettingsDto, settings: PrivacySettingsDto) {
        if (previous.sendReadReceipts && !settings.sendReadReceipts) readState.hideReadTicks()
        if (previous.sendTyping && !settings.sendTyping) {
            typing.stopOutgoing()
            typing.clearIncoming()
        }
    }

    private fun peerIdentitiesClearMemory() = deps.peerIdentities.clearMemory()

    private fun launchSession(block: suspend CoroutineScope.() -> Unit) {
        sessionJobs.removeAll { it.isCompleted }
        sessionJobs += scope.launch(block = block)
    }

    // ---- ContactsHooks (contacts §7.1) ---------------------------------------------------------------

    override fun persistRoster() = state.persistSnapshot()

    override fun onPeerOffline(userId: UUID) = typing.clearPeer(userId)

    /** `blockUser`'s messaging side (`MessagingController.swift:2222-2229`). */
    override suspend fun onBlocked(userId: UUID) {
        typing.setPeerTyping(userId, false)
        refreshConversations(force = true)
    }

    /** A pending request to us; requests ignore chat mutes (`handleContactRealtime`, `MessagingController.swift:4178-4201`). */
    override fun announceContactRequest(request: ContactRequestDto) {
        notifier.announce(NotificationKind.ContactRequest, request.fromUserId, request.user?.username, null, null, false)
    }

    override fun setLastError(message: String?) = state.setLastError(message)

    override fun setOffline(offline: Boolean) = state.setOffline(offline)

    // ---- Glue ------------------------------------------------------------------------------------------

    private inner class PollHost : PollingLoop.Host {
        override val isOnline: Boolean get() = deps.isOnline()
        override val isRealtimeConnected: Boolean get() = socket.isConnected.value
        override fun onConnectivityChanged(online: Boolean) = handleConnectivityChanged(online)
        override fun syncOffline(online: Boolean) = state.setOffline(!online)

        override suspend fun pollWithoutSocket() {
            refreshConversations()
            state.activePeerId?.takeUnless(::isNotesChat)?.let { pager.loadThread(it, reconcile = true) }
            send.flushOutbox()
        }

        override suspend fun safetyPoll() {
            refreshConversations()
            state.activePeerId?.takeUnless(::isNotesChat)?.let { pager.loadThread(it) }
        }
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
        /** The focus frame and the close each get this long to leave the radio (`MessagingController.swift:639, 652`). */
        const val RADIO_GRACE_MS = 200L

        /** Newest page a notification may open to find the message the push named. */
        private const val NOTIFICATION_PAGE = 20
    }
}

/**
 * The user-facing text of a messaging failure (`SessionController.userMessage(for:)`,
 * `ios/shroud/Services/Auth/SessionController.swift:210-225`): the key-change text for a
 * [PeerIdentityChangedException], else the API's own mapping.
 */
internal fun messagingUserMessage(error: Throwable): String = when (error) {
    is PeerIdentityChangedException -> PeerIdentityChangedException.MESSAGE
    else -> SessionController.userMessage(error)
}
