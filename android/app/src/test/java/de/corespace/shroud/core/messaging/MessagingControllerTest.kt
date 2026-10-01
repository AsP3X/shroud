package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.ListMessagesResponse
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.realtime.RealtimeEvent
import de.corespace.shroud.testing.FakeAppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The façade's lifecycle, chat list and event routing (`MessagingController.swift:435-758, 1002-1061,
 * 4137-4445`; messaging-core §6, §7.3, §12) on fakes of every port.
 *
 * The poll loops forever once started, so these tests move virtual time with [runCurrent] and
 * `advanceTimeBy`, never `advanceUntilIdle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagingControllerTest {
    private val scopes = EngineScopes()
    private var engineScope: CoroutineScope? = null

    private fun scopeFor(scheduler: TestCoroutineScheduler) = engineScope ?: scopes.create(scheduler).also { engineScope = it }

    @After
    fun tearDown() = scopes.cancelAll()

    /**
     * `runTest` drains the scheduler after the body (`advanceUntilIdleOr { false }`): an engine loop
     * still running then (the poll, a prefetch) would spin forever, so the engine scopes end with
     * the body.
     */
    private fun engineTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            scopes.cancelAll()
        }
    }

    private val me = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    private val peer = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private val conversation = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")
    private val session = MutableStateFlow<Session?>(testSession(me))
    private val backend = FakeBackend()
    private val socket = FakeSocket()
    private val keys = FakeKeys()
    private val opener = FakeOpener()
    private val store = FakeMessagingStore()
    private val contacts = FakeContacts()
    private val peerKeys = FakePeerIdentities()
    private val privacy = FakePrivacy()
    private val notifier = FakeNotifier()
    private val send = FakeSendEngine()
    private val reactions = FakeReactionsEngine()
    private val media = FakeMediaLoader()
    private val clock = FakeAppClock()
    private var online = true
    private var resumed = true
    private var keyRecordWipes = 0
    private val callSecretRefreshes = ArrayList<Long>()

    private fun TestScope.controller(): MessagingController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MessagingController(
            MessagingDependencies(
                scope = scopeFor(testScheduler),
                session = session,
                backend = backend,
                socket = socket,
                keys = keys,
                opener = opener,
                peerLocks = PeerLocks(),
                store = store,
                hasMedia = { false },
                contacts = contacts,
                peerIdentities = peerKeys,
                privacy = privacy,
                notifier = notifier,
                sendEngine = { send },
                reactionsEngine = { reactions },
                mediaLoader = { media },
                isOnline = { online },
                isResumed = { resumed },
                wipeKeyRecords = { keyRecordWipes++ },
                refreshCallSecrets = { callSecretRefreshes += testScheduler.currentTime },
                clock = clock,
                io = dispatcher,
                compute = dispatcher,
            ),
        )
    }

    private fun cachedThread(vararg texts: String) = texts.mapIndexed { index, text ->
        ChatMessage(UUID.randomUUID(), peer, peer, text, Instant.parse("2026-09-20T10:00:00Z").plusSeconds(index.toLong()), isMine = false)
    }

    private fun hydrateWith(threads: Map<UUID, List<ChatMessage>>, conversations: List<CachedConversation> = emptyList(), unread: Map<UUID, Int> = emptyMap()) {
        store.hydrated = HydratedMessages(RosterSnapshot(conversations, emptyList(), emptyList(), unread), threads + (NOTES_PEER_ID to emptyList()))
    }

    private fun cachedConversation() = CachedConversation(conversation, peer, "bob", Instant.parse("2026-09-01T10:00:00Z"), null, null, null)

    // --- Start and stop (messaging-core §6) ---------------------------------------------------------

    @Test
    fun startPaintsTheCacheThenCatchesUpInIosOrder() = engineTest { // MessagingController.swift:464-487
        val cached = cachedThread("hello")
        hydrateWith(mapOf(peer to cached), listOf(cachedConversation()), mapOf(peer to 2))
        backend.conversationList = listOf(Dtos.conversation(peer, conversation, unread = 2))
        val gate = CompletableDeferred<Unit>()
        backend.conversationsGate = gate
        val controller = controller()
        controller.start()
        runCurrent()

        assertEquals(1, socket.holds)
        assertEquals("hydrate", store.events.first())
        assertEquals(cached, controller.threads.value[peer])
        assertEquals(emptyList<ChatMessage>(), controller.threads.value[NOTES_PEER_ID])
        assertEquals(listOf(peer), controller.conversations.value.map { it.peer.id })
        assertTrue(controller.listStatus.value.hasLoadedChats)
        assertEquals(2, controller.unreadCount(peer))
        assertEquals(listOf("hydrate:0", "start", "refresh:false"), contacts.log)
        // The chat list is in flight: what follows it waits.
        assertEquals(0, privacy.refreshes)
        assertTrue(send.log.isEmpty())

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, privacy.refreshes)
        assertEquals(listOf("refreshServerConfig"), reactions.log)
        assertEquals(listOf("flushOutbox"), send.log)
        assertEquals(1, callSecretRefreshes.size)
        assertTrue(controller.listStatus.value.hasLoadedServerChats)
        controller.stop(wipeDisk = false)
    }

    @Test
    fun withoutASessionNothingStarts() = engineTest {
        session.value = null
        val controller = controller()
        controller.start()
        controller.handleAppBecameActive()
        runCurrent()
        assertEquals(0, socket.holds)
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun aCachePreparedAheadOfStartIsReadOnce() = engineTest { // MessagingController.swift:450-455, 470-474
        hydrateWith(mapOf(peer to cachedThread("hi")))
        val controller = controller()
        controller.prepareCachedState()
        assertEquals(1, store.events.count { it == "hydrate" })
        controller.start()
        runCurrent()
        assertEquals(1, store.events.count { it == "hydrate" })
        assertEquals("hi", controller.threads.value[peer]?.single()?.text)
        controller.stop(wipeDisk = false)
    }

    @Test
    fun aDiscardedPreparationLeavesNothingInMemory() = engineTest { // MessagingController.swift:458-462
        hydrateWith(mapOf(peer to cachedThread("hi")))
        val controller = controller()
        controller.prepareCachedState()
        controller.discardPreparedCachedState()
        runCurrent()
        assertTrue(controller.threads.value.isEmpty())
        assertEquals(1, store.lockCount)
        // Prepared no more: the next start reads the cache again.
        controller.start()
        runCurrent()
        assertEquals(2, store.events.count { it == "hydrate" })
        controller.stop(wipeDisk = false)
    }

    @Test
    fun stoppingWithTheCacheKeptWritesASnapshotFirst() = engineTest { // MessagingController.swift:494-507
        val cached = cachedThread("keep me")
        hydrateWith(mapOf(peer to cached))
        val controller = controller()
        controller.start()
        runCurrent()
        controller.stop(wipeDisk = false)
        runCurrent()

        assertEquals(cached, store.persisted.last().threads[peer])
        // The snapshot reaches the writer before the store forgets what it holds in memory.
        assertTrue(store.events.lastIndexOf("persist") < store.events.lastIndexOf("lockSensitiveMemory"))
        assertTrue(controller.threads.value.isEmpty())
        assertEquals(1, socket.releases)
        assertTrue(store.cleared.isEmpty())
        assertEquals(0, peerKeys.wiped)
        assertTrue("stop:false" in contacts.log)
        // Nothing reaches the writer once stopped.
        val writes = store.persisted.size
        controller.persistRoster()
        runCurrent()
        assertEquals(writes, store.persisted.size)
    }

    @Test
    fun signingOutWipesTheStorePinsAndKeyRecords() = engineTest { // MessagingController.swift:556-564
        hydrateWith(mapOf(peer to cachedThread("bye")))
        val controller = controller()
        controller.start()
        runCurrent()
        val writes = store.persisted.size
        controller.stop(wipeDisk = true)
        runCurrent()

        assertEquals(listOf<UUID?>(me), store.cleared)
        assertEquals(1, peerKeys.wiped)
        assertEquals(1, keyRecordWipes)
        assertTrue("reset" in reactions.log)
        assertTrue("stop:true" in contacts.log)
        assertEquals(writes, store.persisted.size)
        assertTrue(controller.threads.value.isEmpty())
    }

    @Test
    fun theWipeHaltWritesNothingAndStopsEveryWriter() = engineTest { // MessagingController.swift:514-518
        hydrateWith(mapOf(peer to cachedThread("x")))
        val controller = controller()
        controller.start()
        runCurrent()
        val writes = store.persisted.size + store.persistedThreads.size
        controller.haltForDeviceWipe()
        controller.persistRoster()
        socket.eventsFlow.tryEmit(RealtimeEvent.MessageNew(Dtos.message(sender = peer, conversation = conversation)))
        runCurrent()

        assertEquals(writes, store.persisted.size + store.persistedThreads.size)
        assertTrue("reset" in reactions.log)
        assertEquals(1, media.cancelledAll)
        assertEquals(1, socket.releases)
        assertTrue(controller.threads.value.isEmpty())
        assertTrue(backend.delivered.isEmpty())
    }

    // --- Lock (messaging-core §6, §22.5; card trap "lockSensitiveMemory leaves no plaintext") ---------

    @Test
    fun lockingTheMemoryLeavesNoPlaintextAndKeepsTheLists() = engineTest { // MessagingController.swift:657-674
        hydrateWith(mapOf(peer to cachedThread("secret")), listOf(cachedConversation()), mapOf(peer to 1))
        backend.conversationList = listOf(Dtos.conversation(peer, conversation, unread = 1))
        val controller = controller()
        val sink = RecordingSink()
        controller.registerArtifactSink(sink)
        controller.start()
        runCurrent()
        controller.setActivePeer(peer)
        socket.eventsFlow.tryEmit(RealtimeEvent.Typing(peer, true))
        // A transcript shared for a voice note this device has not loaded yet: plaintext held in memory.
        val unseenVoice = UUID.randomUUID()
        socket.eventsFlow.tryEmit(RealtimeEvent.MessageNew(annotation(unseenVoice, "the secret words")))
        runCurrent()
        assertEquals(ChatPeerActivity.Typing, controller.peerActivity(peer))

        controller.lockSensitiveMemory()
        runCurrent()

        // Batched reaction saves went out before the store forgot its key.
        assertTrue("flushPendingSaves" in reactions.log)
        assertEquals(1, store.lockCount)
        assertTrue(controller.threads.value.isEmpty())
        assertNull(controller.activePeerId.value)
        assertTrue(controller.unreadCounts.value.isEmpty())
        assertNull(controller.peerActivity(peer))
        assertEquals(1, sink.locked)
        val voice = ChatMessage(unseenVoice, peer, peer, "Voice message", Instant.parse("2026-09-24T12:00:00Z"), isMine = false, kind = ChatMessageKind.Voice)
        assertNull(controller.foldSharedTranscripts(listOf(voice)).single().transcript)
        // The list shells stay for a less jarring re-unlock.
        assertEquals(listOf(peer), controller.conversations.value.map { it.peer.id })
        // Nothing reaches the writer until the cache was read again.
        val writes = store.persisted.size + store.persistedThreads.size
        controller.persistRoster()
        runCurrent()
        assertEquals(writes, store.persisted.size + store.persistedThreads.size)

        // Back in front with the key: the sealed cache is read again.
        controller.handleAppBecameActive()
        runCurrent()
        assertEquals(2, store.events.count { it == "hydrate" })
        assertEquals("secret", controller.threads.value[peer]?.single()?.text)
        controller.stop(wipeDisk = false)
    }

    // --- Foreground and background (messaging-core §6) -----------------------------------------------

    @Test
    fun leavingTheForegroundTellsTheServerFirstThenLetsTheSocketGo() = engineTest { // MessagingController.swift:633-653
        val controller = controller()
        controller.start()
        runCurrent()
        socket.log.clear()

        controller.leaveForeground(keepSocket = false)
        assertEquals(listOf("noteFocus:false", "deliverFocus", "release"), socket.log)
        assertEquals(listOf(true), notifier.pushCovers)
        assertTrue("background" in contacts.log)
        // The poll stopped: time passes, nothing is fetched.
        val calls = backend.conversationsCalls
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(calls, backend.conversationsCalls)
    }

    @Test
    fun aCallKeepsTheSocketAndAReturnCancelsTheClose() = engineTest {
        val controller = controller()
        controller.start()
        runCurrent()
        socket.log.clear()
        controller.leaveForeground(keepSocket = true)
        assertFalse("release" in socket.log)

        socket.log.clear()
        val leaving = scopeFor(testScheduler).launch { controller.leaveForeground(keepSocket = false) }
        runCurrent()
        // Back during the 200 ms grace: the new socket stays.
        controller.handleAppBecameActive()
        advanceTimeBy(1_000)
        runCurrent()
        leaving.join()
        assertFalse("release" in socket.log)
        assertEquals(listOf(true, false), notifier.pushCovers)
        controller.stop(wipeDisk = false)
    }

    @Test
    fun comingBackRefocusesAndReconcilesTheOpenChat() = engineTest { // MessagingController.swift:598-624
        val controller = controller()
        controller.start()
        runCurrent()
        controller.setActivePeer(peer)
        socket.log.clear()
        backend.pageRequests.clear()

        controller.handleAppBecameActive()
        runCurrent()
        assertEquals(listOf(false), notifier.pushCovers)
        assertEquals(listOf("noteFocus:true", "hold", "deliverFocus"), socket.log)
        assertEquals(listOf(peer), backend.pageRequests.map { it.peer })
        assertEquals(listOf("flushOutbox", "flushOutbox"), send.log)
        assertTrue("foreground" in contacts.log)
        controller.stop(wipeDisk = false)
    }

    // --- Chat list (messaging-core §7) -------------------------------------------------------------

    @Test
    fun overlappingListRefreshesShareOneFetchAndForceFetchesAgain() = engineTest { // MessagingController.swift:1002-1014
        val controller = controller()
        val gate = CompletableDeferred<Unit>()
        backend.conversationsGate = gate
        val scope = scopeFor(testScheduler)
        val a = scope.launch { controller.refreshConversations() }
        val b = scope.launch { controller.refreshConversations() }
        val forced = scope.launch { controller.refreshConversations(force = true) }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        a.join()
        b.join()
        forced.join()
        assertEquals(2, backend.conversationsCalls)
    }

    @Test
    fun aFailedListKeepsWhatIsShownAndAFailedFirstListSaysWhy() = engineTest { // MessagingController.swift:1050-1059
        val controller = controller()
        backend.conversationsError = FakeBackend.offline()
        controller.refreshConversations()
        assertEquals("The Internet connection appears to be offline.", controller.listStatus.value.chatsError)
        assertEquals(controller.listStatus.value.chatsError, controller.lastError.value)
        assertTrue(controller.listStatus.value.hasLoadedChats)
        assertFalse(controller.isOffline.value)

        backend.conversationsError = null
        backend.conversationList = listOf(Dtos.conversation(peer, conversation))
        controller.refreshConversations()
        assertNull(controller.listStatus.value.chatsError)
        assertNull(controller.lastError.value)

        backend.conversationsError = FakeBackend.offline()
        controller.refreshConversations()
        assertNull(controller.listStatus.value.chatsError)
        assertTrue(controller.isOffline.value)
        assertEquals(listOf(peer), controller.conversations.value.map { it.peer.id })
    }

    @Test
    fun theListAdoptsLocalSeenStateAndMarksTheOpenChatsReactionsSeen() = engineTest { // MessagingController.swift:1029-1049
        val controller = controller()
        val other = UUID.randomUUID()
        reactions.seenLocally += other
        backend.conversationList = listOf(
            Dtos.conversation(peer, conversation).copy(unseenReactions = 2),
            Dtos.conversation(other, UUID.randomUUID(), username = "carol").copy(unseenReactions = 1),
        )
        controller.refreshConversations()
        assertEquals(listOf(2, 0), controller.conversations.value.map { it.unseenReactions })
        assertTrue(reactions.shown.isEmpty())

        controller.setActivePeer(peer)
        controller.refreshConversations()
        assertEquals(listOf(peer), reactions.shown)
        assertEquals(peer, notifier.activePeerId)
    }

    // --- Events (messaging-core §12, §15, §17; api-realtime §11.15) --------------------------------

    @Test
    fun aPeersMessageIsAckedCountedAndAnnounced() = engineTest { // MessagingController.swift:4365-4445
        backend.conversationList = listOf(Dtos.conversation(peer, conversation))
        val controller = controller()
        controller.start()
        runCurrent()
        socket.eventsFlow.tryEmit(RealtimeEvent.Typing(peer, true))
        // The list the arrival refreshes counts it on the server too: its count wins (`:1036`).
        backend.conversationList = listOf(Dtos.conversation(peer, conversation, unread = 1))
        val dto = Dtos.message(sender = peer, conversation = conversation, ciphertext = Dtos.sealed("hi there"))
        socket.eventsFlow.tryEmit(RealtimeEvent.MessageNew(dto))
        runCurrent()

        assertEquals(listOf(dto.id), backend.delivered)
        assertEquals("hi there", controller.threads.value[peer]?.single()?.text)
        assertEquals(1, controller.unreadCount(peer))
        assertNull(controller.peerActivity(peer))
        val announcement = notifier.announcements.single()
        assertEquals(NotificationKind.Message, announcement.kind)
        assertEquals("bob", announcement.username)
        assertEquals("hi there", announcement.text)
        assertEquals(conversation, announcement.conversationId)
        assertTrue(store.persisted.isNotEmpty())

        // The same message again (a duplicate event): no second bubble, count or banner.
        socket.eventsFlow.tryEmit(RealtimeEvent.MessageNew(dto))
        runCurrent()
        assertEquals(1, controller.threads.value[peer]?.size)
        assertEquals(1, controller.unreadCount(peer))
        assertEquals(1, notifier.announcements.size)
        // Opened once: the second decode came from the held copy, never the one-shot ratchet.
        assertEquals(1, opener.calls.size)
        controller.stop(wipeDisk = false)
    }

    @Test
    fun aMessageInTheChatBeingReadIsReadAtOnce() = engineTest {
        backend.conversationList = listOf(Dtos.conversation(peer, conversation))
        val controller = controller()
        controller.start()
        runCurrent()
        controller.setActivePeer(peer)
        advanceTimeBy(1_000)
        backend.chatReads.clear()

        socket.eventsFlow.tryEmit(RealtimeEvent.MessageNew(Dtos.message(sender = peer, conversation = conversation)))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, controller.unreadCount(peer))
        assertEquals(listOf(peer), backend.chatReads)

        // In the background the open chat is not being read (`isReading`, `:5633-5635`).
        resumed = false
        // The server counts it, and its list says the chat moved past what was read here.
        backend.conversationList = listOf(Dtos.conversation(peer, conversation, unread = 1, last = Instant.parse("2026-09-24T12:00:01.000001Z")))
        socket.eventsFlow.tryEmit(RealtimeEvent.MessageNew(Dtos.message(sender = peer, conversation = conversation, createdAtWire = "2026-09-24T12:00:01.000001Z")))
        runCurrent()
        assertEquals(1, controller.unreadCount(peer))
        controller.stop(wipeDisk = false)
    }

    @Test
    fun ourMessageFromAnotherDeviceMarksTheChatRead() = engineTest { // MessagingController.swift:4429-4431
        backend.conversationList = listOf(Dtos.conversation(peer, conversation, unread = 3))
        val controller = controller()
        controller.start()
        runCurrent()
        assertEquals(3, controller.unreadCount(peer))
        val dto = Dtos.message(sender = me, conversation = conversation, ciphertext = Dtos.sealed("from my tablet"))
        socket.eventsFlow.tryEmit(RealtimeEvent.MessageNew(dto))
        runCurrent()

        val message = controller.threads.value[peer]!!.single()
        assertTrue(message.isMine)
        assertEquals(0, controller.unreadCount(peer))
        assertTrue(conversation in notifier.cleared)
        assertTrue(notifier.announcements.isEmpty())
        // Not acked: it is ours.
        assertTrue(backend.delivered.isEmpty())
        controller.stop(wipeDisk = false)
    }

    @Test
    fun aSharedTranscriptFillsItsVoiceNoteWithoutABubble() = engineTest { // MessagingController.swift:4396-4410
        val voiceId = UUID.randomUUID()
        val voice = ChatMessage(voiceId, peer, peer, "Voice message", Instant.parse("2026-09-24T11:00:00Z"), isMine = false, kind = ChatMessageKind.Voice)
        hydrateWith(mapOf(peer to listOf(voice)))
        backend.conversationList = listOf(Dtos.conversation(peer, conversation))
        val controller = controller()
        controller.start()
        runCurrent()
        val note = annotation(voiceId, "  see you at nine  ")
        socket.eventsFlow.tryEmit(RealtimeEvent.MessageNew(note))
        runCurrent()

        val thread = controller.threads.value[peer]!!
        assertEquals(listOf(voiceId), thread.map { it.id })
        assertEquals("see you at nine", thread.single().transcript)
        assertEquals(0, controller.unreadCount(peer))
        assertTrue(notifier.announcements.isEmpty())
        // Annotations are acked too, and indexed for the purge of their target (Android D5).
        assertEquals(listOf(note.id), backend.delivered)
        assertEquals(setOf(note.id), store.annotationsFor(voiceId))
        controller.stop(wipeDisk = false)
    }

    /** Decision D8b (`MessageReadEventTests`): our own reads on another device never tick our messages read. */
    @Test
    fun ourOwnReadEventIsIgnored() = engineTest {
        val mine = ChatMessage(UUID.randomUUID(), peer, me, "sent", Instant.parse("2026-09-20T10:00:00Z"), isMine = true, receipt = ReceiptStatus.Delivered)
        hydrateWith(mapOf(peer to listOf(mine)))
        val controller = controller()
        controller.start()
        runCurrent()

        socket.eventsFlow.tryEmit(RealtimeEvent.MessageRead(mine.id, mine.id, conversation, me, UUID.randomUUID(), null, 1))
        runCurrent()
        assertEquals(ReceiptStatus.Delivered, controller.threads.value[peer]!!.single().receipt)

        socket.eventsFlow.tryEmit(RealtimeEvent.MessageRead(mine.id, mine.id, conversation, peer, UUID.randomUUID(), null, 1))
        runCurrent()
        assertEquals(ReceiptStatus.Read, controller.threads.value[peer]!!.single().receipt)
        controller.stop(wipeDisk = false)
    }

    @Test
    fun eventsAreIgnoredWhileTheChatsDoNotUseTheSocket() = engineTest { // MessagingController.swift:439-442
        val controller = controller()
        socket.eventsFlow.tryEmit(RealtimeEvent.MessageNew(Dtos.message(sender = peer, conversation = conversation)))
        socket.eventsFlow.tryEmit(RealtimeEvent.Typing(peer, true))
        runCurrent()
        assertTrue(backend.delivered.isEmpty())
        assertNull(controller.peerActivity(peer))
    }

    @Test
    fun anOfflinePeerAndTypingTurnedOffClearIndicators() = engineTest { // MessagingController.swift:2156-2181, 4359-4362
        val controller = controller()
        controller.start()
        runCurrent()
        socket.eventsFlow.tryEmit(RealtimeEvent.Recording(peer, true))
        runCurrent()
        assertEquals(ChatPeerActivity.Recording, controller.peerActivity(peer))
        socket.eventsFlow.tryEmit(RealtimeEvent.PresenceUpdate(peer, online = false, lastSeenAt = null))
        runCurrent()
        assertNull(controller.peerActivity(peer))

        controller.setTyping(peer, true)
        socket.eventsFlow.tryEmit(RealtimeEvent.Typing(peer, true))
        runCurrent()
        privacy.settings.value = privacy.settings.value.copy(sendTyping = false)
        runCurrent()
        assertNull(controller.peerActivity(peer))
        assertEquals(listOf(peer to true, peer to false), socket.typingFrames)
        controller.stop(wipeDisk = false)
    }

    @Test
    fun readReceiptsTurnedOffStepReadTicksBack() = engineTest { // MessagingController.swift:2156-2195
        val mine = ChatMessage(UUID.randomUUID(), peer, me, "sent", Instant.parse("2026-09-20T10:00:00Z"), isMine = true, receipt = ReceiptStatus.Read)
        hydrateWith(mapOf(peer to listOf(mine)))
        val controller = controller()
        controller.start()
        runCurrent()
        privacy.settings.value = privacy.settings.value.copy(sendReadReceipts = false)
        runCurrent()
        assertEquals(ReceiptStatus.Delivered, controller.threads.value[peer]!!.single().receipt)
        controller.stop(wipeDisk = false)
    }

    @Test
    fun aMessageNewThatDidNotDecodeRefreshesTheListAndTheOpenChat() = engineTest { // MessagingController.swift:4141-4148
        val controller = controller()
        controller.start()
        runCurrent()
        controller.setActivePeer(peer)
        val calls = backend.conversationsCalls
        backend.pageRequests.clear()
        backend.pages = { ListMessagesResponse(messages = emptyList(), hasMore = false) }
        socket.eventsFlow.tryEmit(RealtimeEvent.MessageNew(null))
        runCurrent()
        assertEquals(calls + 1, backend.conversationsCalls)
        assertEquals(listOf(peer), backend.pageRequests.map { it.peer })
        controller.stop(wipeDisk = false)
    }

    // --- The poll (messaging-core §6.1) -------------------------------------------------------------

    @Test
    fun withTheSocketDownThePollCatchesUpEveryThreeSeconds() = engineTest { // MessagingController.swift:705-741
        socket.connected.value = false
        val controller = controller()
        controller.start()
        runCurrent()
        controller.setActivePeer(peer)
        val calls = backend.conversationsCalls
        backend.pageRequests.clear()
        send.log.clear()

        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(calls + 1, backend.conversationsCalls)
        assertEquals(listOf(peer), backend.pageRequests.map { it.peer })
        assertEquals(listOf("flushOutbox"), send.log)

        // Socket up: only every fifth tick, and the open chat without a reconcile.
        socket.connected.value = true
        val upCalls = backend.conversationsCalls
        advanceTimeBy(4 * 3_000)
        runCurrent()
        assertEquals(upCalls + 1, backend.conversationsCalls)
        controller.stop(wipeDisk = false)
    }

    @Test
    fun theNetworkComingBackCatchesEverythingUp() = engineTest { // MessagingController.swift:677-690
        val controller = controller()
        controller.start()
        runCurrent()
        online = false
        advanceTimeBy(3_001)
        runCurrent()
        assertTrue(controller.isOffline.value)
        val calls = backend.conversationsCalls
        online = true
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(controller.isOffline.value)
        assertTrue("regained" in contacts.log)
        assertTrue(backend.conversationsCalls > calls)
        controller.stop(wipeDisk = false)
    }

    // --- Glue for W2-MSG-SEND's SendHost (CR-1) ----------------------------------------------------

    @Test
    fun theSendHostMembersReadAndWriteTheControllersState() = engineTest {
        backend.conversationList = listOf(Dtos.conversation(peer, conversation, username = "bob"))
        val controller = controller()
        controller.refreshConversations()
        assertEquals("bob", controller.username(peer))
        assertNull(controller.username(UUID.randomUUID()))
        controller.editConversations { list -> list.map { it.copy(unseenReactions = 4) } }
        assertEquals(4, controller.conversations.value.single().unseenReactions)
        controller.setOffline(true)
        assertTrue(controller.isOffline.value)
    }

    private fun annotation(target: UUID, text: String) = Dtos.message(
        sender = peer,
        conversation = conversation,
        contentType = ContentType.ANNOTATION,
        ciphertext = Dtos.sealed("{\"t\":\"transcript\",\"r\":\"$target\",\"c\":\"$text\"}"),
    )
}
