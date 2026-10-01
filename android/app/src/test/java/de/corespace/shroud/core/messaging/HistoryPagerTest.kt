package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MessageReaction
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ListMessagesResponse
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.net.ReactionDto
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.testing.FakeAppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Thread loading and history paging (`MessagingController.swift:1074-1529`; messaging-core §8) and
 * the Android traps the card names: the cursor is the server's raw `created_at`, a flush that
 * re-keys a bubble while a page is in flight never brings the optimistic bubble back, a re-keyed
 * message (text and Notes) is one bubble after a reload, and a page tombstone purges once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPagerTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

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

    /** Saved Messages: the self conversation, never in the chat list. */
    private val selfConversation = UUID.fromString("16fd2706-8baf-433b-82eb-8c7fada847da")
    private val backend = FakeBackend()
    private val store = FakeMessagingStore()
    private val keys = FakeKeys()
    private val opener = FakeOpener()
    private val peerKeys = FakePeerIdentities()
    private val reactions = FakeReactionsEngine()
    private val notifier = FakeNotifier()
    private val presence = ArrayList<UUID>()
    private val clock = FakeAppClock()

    /** Scripted pages, served in order; then an empty first page. */
    private val responses = ArrayDeque<ListMessagesResponse>()
    private lateinit var state: ThreadStore

    private fun TestScope.pager(): HistoryPager {
        val scope = scopeFor(testScheduler)
        val dispatcher = StandardTestDispatcher(testScheduler)
        state = ThreadStore(
            sessionFlow = MutableStateFlow(testSession(me)),
            store = store,
            scope = scope,
            io = dispatcher,
            roster = { emptyList<ContactItemDto>() to emptyList() },
            onlineNow = { true },
            realtimeConnectedNow = { true },
        ).also { it.writable = true }
        state.setConversations(listOf(Dtos.conversation(peer, conversation)))
        backend.pages = { responses.removeFirstOrNull() ?: ListMessagesResponse(messages = emptyList(), hasMore = false) }
        val decoder = MessageDecoder(store, keys, opener, PeerLocks(), { false }, dispatcher, dispatcher)
        val readState = ReadStateEngine(state, backend, { notifier }, { true }, scope, clock)
        return HistoryPager(
            state = state,
            backend = backend,
            decoder = decoder,
            decodeContext = { me ->
                MessageDecoder.Context(me, state.conversations, state.threads.value) { sender ->
                    if (sender == me) keys.publicKey.copyOf() else peerKeys.resolvePublicKey(sender)
                }
            },
            isUnlocked = { keys.isUnlocked },
            reactions = { reactions },
            readState = readState,
            refreshPresence = { presence += it },
            scope = scope,
            clock = clock,
        )
    }

    /** `2026-09-24T12:mm:ss.<6 digits>Z`: the server's microsecond text, one per [n]. */
    private fun wire(n: Int): String = "2026-09-24T12:%02d:%02d.%06dZ".format(n / 60, n % 60, 100_000 + n)

    private fun theirs(n: Int, text: String = "t$n", id: UUID = UUID.randomUUID(), deleted: Boolean = false) =
        Dtos.message(id = id, sender = peer, conversation = conversation, createdAtWire = wire(n), ciphertext = Dtos.sealed(text), deleted = deleted)

    private fun ours(n: Int, text: String = "m$n", id: UUID = UUID.randomUUID(), sender: UUID = me) =
        Dtos.message(id = id, sender = sender, conversation = conversation, createdAtWire = wire(n), ciphertext = Dtos.sealed(text))

    /** A server page: [chronological] reversed to the server's newest-first order. */
    private fun page(vararg chronological: MessageDto, hasMore: Boolean = false, reactionSeq: Long? = null) =
        ListMessagesResponse(conversationId = conversation, messages = chronological.reversed(), hasMore = hasMore, reactionSeq = reactionSeq)

    private fun held(dto: MessageDto, text: String, isMine: Boolean = dto.senderUserId == me) =
        ChatMessage(dto.id, peer, dto.senderUserId, text, dto.createdAt, dto.createdAtWire, isMine = isMine, receipt = ReceiptStatus.Sent)

    private fun ids() = state.messages(peer).orEmpty().map { it.id }

    // --- The cursor (messaging-core §8.3, HistoryCursorTests) -------------------------------------

    /** `HistoryCursorTests.messageCreatedAtWireIsTheServerText`: the cursor is the text, never a re-formatted date. */
    @Test
    fun theCursorIsTheServersCreatedAtText() = engineTest {
        val pager = pager()
        val oldest = theirs(0, id = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
            .copy(createdAtWire = "2026-09-24T12:00:00.123456Z")
        responses += page(oldest, theirs(1), hasMore = true)
        pager.loadThread(peer, activate = false)
        responses += page()
        pager.loadOlderMessages(peer)

        val older = backend.pageRequests.last()
        assertEquals("2026-09-24T12:00:00.123456Z", older.beforeCreatedAt)
        assertEquals(oldest.id, older.beforeId)
        assertEquals(HistoryPager.HISTORY_PAGE_SIZE, older.limit)
        // The parsed date keeps only milliseconds when formatted: that is the fallback, not the cursor.
        assertEquals("2026-09-24T12:00:00.123Z", HistoryPager.cursorFallback(oldest.createdAt))
    }

    /** A message saved by an older build has no server text: its time with milliseconds (`MessagingController.swift:1474-1475`). */
    @Test
    fun aHeldMessageWithoutTheServerTextPagesFromItsMilliseconds() = engineTest {
        val pager = pager()
        val legacy = ChatMessage(UUID.randomUUID(), peer, peer, "old", Instant.parse("2026-09-24T12:00:00.123456Z"), createdAtWire = null, isMine = false)
        state.setThread(peer, listOf(legacy))
        pager.loadOlderMessages(peer)
        assertEquals("2026-09-24T12:00:00.123Z", backend.pageRequests.single().beforeCreatedAt)
        assertEquals(legacy.id, backend.pageRequests.single().beforeId)
    }

    // --- How far a load walks (messaging-core §8.5) -----------------------------------------------

    @Test
    fun aFirstOpenStopsAfterTheNewestPage() = engineTest {
        val pager = pager()
        responses += page(*Array(40) { theirs(it) }, hasMore = true)
        pager.loadThread(peer, activate = false)

        assertEquals(1, backend.pageRequests.size)
        assertEquals(HistoryPager.FIRST_PAGE_SIZE, backend.pageRequests.single().limit)
        assertNull(backend.pageRequests.single().beforeCreatedAt)
        assertEquals(40, state.messages(peer)?.size)
        assertTrue(pager.hasOlderHistory(peer))
        // Chronological, oldest first.
        assertEquals("t0", state.messages(peer)?.first()?.text)
    }

    @Test
    fun aRefreshWalksBackUntilItMeetsAHeldMessage() = engineTest {
        val pager = pager()
        val known = theirs(0)
        state.setThread(peer, listOf(held(known, "t0")))
        val newer = Array(40) { theirs(100 + it) }
        responses += page(*newer, hasMore = true)
        responses += page(known, theirs(1), hasMore = true)
        responses += page(theirs(2)) // never asked for
        pager.loadThread(peer, activate = false)

        assertEquals(2, backend.pageRequests.size)
        assertEquals(newer.first().createdAtWire, backend.pageRequests[1].beforeCreatedAt)
        assertEquals(newer.first().id, backend.pageRequests[1].beforeId)
        assertEquals(HistoryPager.HISTORY_PAGE_SIZE, backend.pageRequests[1].limit)
        assertEquals(42, state.messages(peer)?.size)
        assertEquals(1, responses.size)
    }

    @Test
    fun aReconcileWalksUntilTheOldestHeldMessageIsCovered() = engineTest {
        val pager = pager()
        val oldest = theirs(1)
        val recent = theirs(50)
        state.setThread(peer, listOf(held(oldest, "t1"), held(recent, "t50")))
        // The newest page meets `recent`: a refresh would stop here; a reconcile goes on to `oldest`.
        responses += page(recent, theirs(60), hasMore = true)
        responses += page(oldest.copy(ciphertext = null, deletedForEveryone = true), theirs(10), hasMore = true)
        responses += page(theirs(0))
        pager.loadThread(peer, activate = false, reconcile = true)
        advanceUntilIdle()

        assertEquals(2, backend.pageRequests.size)
        // The delete this device missed while the socket was down is applied.
        val tombstone = state.messages(peer)?.first { it.id == oldest.id }
        assertTrue(tombstone!!.deleted)
        assertEquals(ThreadMessageMerge.MESSAGE_DELETED, tombstone.text)
        assertTrue(oldest.id in store.removedIds)
    }

    @Test
    fun aPlainRefreshStopsAtTheFirstHeldMessage() = engineTest {
        val pager = pager()
        val oldest = theirs(0)
        val recent = theirs(50)
        state.setThread(peer, listOf(held(oldest, "t0"), held(recent, "t50")))
        responses += page(recent, theirs(60), hasMore = true)
        pager.loadThread(peer, activate = false)
        assertEquals(1, backend.pageRequests.size)
    }

    @Test
    fun theWalkStopsAtTheLastPageAndMarksTheHistoryExhausted() = engineTest {
        val pager = pager()
        state.setThread(peer, listOf(held(theirs(0), "ancient")))
        responses += page(*Array(40) { theirs(100 + it) }, hasMore = true)
        responses += page(theirs(1), hasMore = false)
        pager.loadThread(peer, activate = false)
        assertEquals(2, backend.pageRequests.size)
        assertFalse(pager.hasOlderHistory(peer))
        assertTrue(peer in pager.olderHistoryExhausted.value)
    }

    @Test
    fun messagesPastRetentionAreNotShownAndEndThePaging() = engineTest { // MessagingController.swift:1214-1216, 1250
        val pager = pager()
        val ancient = Dtos.message(sender = peer, conversation = conversation, createdAtWire = "2026-06-01T00:00:00.000001Z", ciphertext = Dtos.sealed("old"))
        responses += page(ancient, theirs(0), hasMore = true)
        pager.loadThread(peer, activate = false)
        assertEquals(listOf("t0"), state.messages(peer)?.map { it.text })
        assertFalse(pager.hasOlderHistory(peer))
    }

    // --- Delivery acks, read, presence, reactions (messaging-core §8.5, §15) -----------------------

    @Test
    fun newInboundMessagesAreAckedAfterThePageAndOnlyOnce() = engineTest {
        val pager = pager()
        val known = theirs(0)
        state.setThread(peer, listOf(held(known, "t0")))
        val fresh = theirs(1)
        val mine = ours(2)
        val annotation = theirs(3).copy(contentType = "annotation", ciphertext = Dtos.sealed("{\"t\":\"transcript\",\"r\":\"${known.id}\",\"c\":\"hi\"}"))
        responses += page(known, fresh, mine, annotation)
        pager.loadThread(peer, activate = false)

        // The held one was acked when it arrived; ours never are; an annotation is (`:1205-1207`).
        assertEquals(listOf(fresh.id, annotation.id), backend.delivered)
        assertEquals(listOf(peer), presence)
        // An annotation is never a bubble.
        assertEquals(listOf(known.id, fresh.id, mine.id), ids())
    }

    @Test
    fun onlyTheChatOnScreenSendsTheReadMarker() = engineTest {
        val pager = pager()
        responses += page(theirs(0))
        pager.loadThread(peer, activate = false)
        advanceUntilIdle()
        assertTrue(backend.chatReads.isEmpty())

        responses += page(theirs(0), theirs(1))
        pager.loadThread(peer, activate = true)
        advanceUntilIdle()
        assertEquals(listOf(peer), backend.chatReads)
        assertEquals(peer, state.activePeerId)
        pager.cancelHistoryPaging()
    }

    @Test
    fun aPagesTrustedReactionsGoToTheEngineAndTakenBackOnesAreDropped() = engineTest {
        val pager = pager()
        val stranger = UUID.randomUUID()
        val withRecords = theirs(0)
        val withoutRecords = theirs(1)
        val records = listOf(
            ReactionDto(withRecords.id, peer, "AAAA", 3, Instant.parse("2026-09-24T12:00:00Z")),
            ReactionDto(withRecords.id, peer, "BBBB", 5, Instant.parse("2026-09-24T12:00:01Z")),
            ReactionDto(withRecords.id, stranger, "CCCC", 6, Instant.parse("2026-09-24T12:00:02Z")),
            ReactionDto(withoutRecords.id, me, "DDDD", 7, Instant.parse("2026-09-24T12:00:03Z")),
        )
        // The thread shows a reaction of the peer (seq 4) on the second message; the page (snapshot 10) has none for it.
        state.setThread(peer, listOf(held(withoutRecords, "t1").copy(reactions = listOf(MessageReaction(peer, listOf("❤️"), 4)))))
        responses += page(withRecords.copy(reactions = records), withoutRecords, reactionSeq = 10)
        pager.loadThread(peer, activate = false)

        val (storePeer, trusted, snapshot) = reactions.pages.single()
        assertEquals(peer, storePeer)
        assertEquals(10L, snapshot)
        // Only this message's records of the two people in the chat, the newest per person (`:1221-1228`).
        assertEquals(listOf(5L), trusted.map { it.seq })
        // Taken back at or before the snapshot: gone (`ReactionMerge.reconcile`, `:1270-1281`).
        assertTrue(state.messages(peer)!!.first { it.id == withoutRecords.id }.reactions.isEmpty())
        assertEquals(listOf(peer), reactions.catchUps)
    }

    @Test
    fun theOpenChatsUnseenReactionsAreMarkedSeenAfterALoad() = engineTest { // MessagingController.swift:1408-1410
        val pager = pager()
        state.setActivePeer(peer)
        responses += page(theirs(0))
        pager.loadThread(peer, activate = false)
        assertTrue(reactions.shown.isEmpty())

        state.setConversations(listOf(Dtos.conversation(peer, conversation).copy(unseenReactions = 2)))
        responses += page(theirs(0))
        pager.loadThread(peer, activate = false)
        assertEquals(listOf(peer), reactions.shown)
        pager.cancelHistoryPaging()
    }

    // --- Notes (messaging-core §11.4) --------------------------------------------------------------

    @Test
    fun notesPageThroughOurOwnIdAndLandUnderTheSentinel() = engineTest {
        val pager = pager()
        val note = ours(0, text = "milk").copy(conversationId = selfConversation)
        val todo = ours(1, text = "[todo:1]Buy milk").copy(conversationId = selfConversation)
        responses += page(note, todo)
        pager.loadThread(NOTES_PEER_ID, activate = false)

        assertEquals(me, backend.pageRequests.single().peer)
        val notes = state.messages(NOTES_PEER_ID)!!
        assertEquals(listOf("milk", "Buy milk"), notes.map { it.text })
        assertTrue(notes.all { it.peerUserId == NOTES_PEER_ID && it.isMine })
        assertEquals(ChatMessageKind.Todo, notes[1].kind)
        assertEquals(true, notes[1].todoDone)
        // Opened from our self box, never a peer ratchet.
        assertTrue(opener.calls.all { it.peerUserId == me })
        // No acks, no read marker, no presence for Notes.
        advanceUntilIdle()
        assertTrue(backend.delivered.isEmpty())
        assertTrue(backend.chatReads.isEmpty())
        assertTrue(presence.isEmpty())
        assertNull(state.messages(me))
    }

    @Test
    fun withoutAKeyNotesStillGetAnEmptyThread() = engineTest { // MessagingController.swift:1075-1083
        val pager = pager()
        keys.unlocked = false
        pager.loadThread(NOTES_PEER_ID)
        assertEquals(emptyList<ChatMessage>(), state.messages(NOTES_PEER_ID))
        pager.loadThread(peer)
        assertNull(state.messages(peer))
        assertTrue(backend.pageRequests.isEmpty())
    }

    // --- Re-keys (memory: *Server re-keys sent messages*) ------------------------------------------

    @Test
    fun aReKeyedTextIsOneBubbleAfterAReload() = engineTest {
        val pager = pager()
        val optimistic = ChatMessage(UUID.randomUUID(), peer, me, "hello", Instant.parse("2026-09-24T12:00:05Z"), isMine = true, receipt = ReceiptStatus.Sending, pendingSync = true)
        state.setThread(peer, listOf(optimistic))
        store.savePlaintext(optimistic.id, "hello".toByteArray())
        val serverCopy = ours(5, text = "hello")
        store.savePlaintext(serverCopy.id, "hello".toByteArray())
        state.rekey(peer, optimistic.id, held(serverCopy, "hello"))
        advanceUntilIdle()

        responses += page(theirs(1), serverCopy)
        pager.loadThread(peer, activate = false, reconcile = true)
        advanceUntilIdle()

        assertEquals(1, state.messages(peer)!!.count { it.text == "hello" })
        assertFalse(optimistic.id in ids())
        assertNull(store.plaintext(optimistic.id))
        // Our own v3 body could not be reopened: it came from the cache, not the ratchet.
        assertTrue(opener.calls.none { it.peerUserId == peer && it.role == de.corespace.shroud.core.crypto.OpenAs.Sender })
    }

    @Test
    fun aReKeyedNoteIsOneBubbleAfterAReload() = engineTest {
        val pager = pager()
        val local = ChatMessage(UUID.randomUUID(), NOTES_PEER_ID, me, "milk", Instant.parse("2026-09-24T12:00:05Z"), isMine = true)
        state.setThread(NOTES_PEER_ID, listOf(local))
        val serverCopy = ours(5, text = "milk").copy(conversationId = selfConversation)
        store.savePlaintext(serverCopy.id, "milk".toByteArray())
        state.rekey(NOTES_PEER_ID, local.id, local.copy(id = serverCopy.id, createdAt = serverCopy.createdAt, createdAtWire = serverCopy.createdAtWire))
        advanceUntilIdle()

        responses += page(serverCopy)
        pager.loadThread(NOTES_PEER_ID, activate = false)

        assertEquals(listOf(serverCopy.id), state.messages(NOTES_PEER_ID)?.map { it.id })
        assertNull(state.messages(me))
        assertTrue(local.id in store.removedIds)
    }

    /** The trap of messaging-core §8.5: a page decoded before a flush re-keyed must not re-append the optimistic bubble. */
    @Test
    fun aFlushThatReKeysWhileAPageIsInFlightIsKept() = engineTest {
        val pager = pager()
        val known = theirs(0)
        val optimistic = ChatMessage(UUID.randomUUID(), peer, me, "queued", Instant.parse("2026-09-24T12:00:30Z"), isMine = true, receipt = ReceiptStatus.Sending, pendingSync = true)
        state.setThread(peer, listOf(held(known, "t0"), optimistic))
        val serverCopy = ours(30, text = "queued")
        store.savePlaintext(serverCopy.id, "queued".toByteArray())

        val gate = CompletableDeferred<Unit>()
        backend.gate = gate
        responses += page(known, serverCopy)
        val load = scopeFor(testScheduler).launch { pager.loadThread(peer, activate = false, reconcile = true) }
        runCurrent()
        assertEquals(1, backend.pageRequests.size)

        // The flush lands while the page is on the wire.
        state.rekey(peer, optimistic.id, held(serverCopy, "queued").copy(receipt = ReceiptStatus.Sent))
        gate.complete(Unit)
        load.join()
        advanceUntilIdle()

        assertEquals(listOf(known.id, serverCopy.id), ids())
        assertTrue(state.messages(peer)!!.none { it.pendingSync })
    }

    // --- Tombstones (messaging-core §10.4, §10.6) --------------------------------------------------

    @Test
    fun aPageTombstonePurgesOnce() = engineTest {
        val pager = pager()
        val gone = theirs(0, text = "secret")
        state.setThread(peer, listOf(held(gone, "secret")))
        store.savePlaintext(gone.id, "secret".toByteArray())
        val sink = RecordingSink()
        state.registerSink(sink)

        responses += page(gone.copy(ciphertext = null, deletedForEveryone = true))
        pager.loadThread(peer, activate = false)
        advanceUntilIdle()
        assertTrue(state.messages(peer)!!.single().deleted)
        assertEquals(listOf(gone.id), sink.purged)
        assertNull(store.plaintext(gone.id))
        val removals = store.removedIds.count { it == gone.id }

        responses += page(gone.copy(ciphertext = null, deletedForEveryone = true))
        pager.loadThread(peer, activate = false)
        advanceUntilIdle()
        assertEquals(removals, store.removedIds.count { it == gone.id })
        assertEquals(listOf(gone.id), sink.purged)
    }

    @Test
    fun aDeletedAnnotationIsPurgedAndItsTargetsAnnotationsGoWithTheTarget() = engineTest { // Android D5, messaging-core §14.6
        val pager = pager()
        val voice = theirs(0)
        val annotation = theirs(1).copy(contentType = "annotation", ciphertext = Dtos.sealed("{\"t\":\"transcript\",\"r\":\"${voice.id}\",\"c\":\"hello there\"}"))
        responses += page(voice, annotation)
        pager.loadThread(peer, activate = false)
        advanceUntilIdle()
        assertEquals(setOf(annotation.id), store.annotationsFor(voice.id))

        responses += page(voice, annotation.copy(ciphertext = null, deletedForEveryone = true))
        pager.loadThread(peer, activate = false, reconcile = true)
        advanceUntilIdle()
        assertTrue(annotation.id in store.removedIds)

        state.purge(listOf(voice.id))
        advanceUntilIdle()
        assertTrue(store.removedCaches.last().containsAll(listOf(voice.id, annotation.id)))
    }

    // --- Failures, locks, older pages --------------------------------------------------------------

    @Test
    fun aFailedLoadOfAnEmptyThreadSaysWhyAndOfAShownOneGoesOffline() = engineTest { // MessagingController.swift:1414-1422
        val pager = pager()
        backend.pagesError = FakeBackend.offline()
        pager.loadThread(peer, activate = false)
        assertTrue(state.lastError.value != null)
        assertFalse(state.isOffline)

        state.setThread(peer, listOf(held(theirs(0), "t0")))
        pager.loadThread(peer, activate = false)
        assertNull(state.lastError.value)
        assertTrue(state.isOffline)
    }

    @Test
    fun aPageThatLandsAfterALockIsDropped() = engineTest {
        val pager = pager()
        val gate = CompletableDeferred<Unit>()
        backend.gate = gate
        responses += page(theirs(0))
        val load = scopeFor(testScheduler).launch { pager.loadThread(peer, activate = false) }
        runCurrent()
        state.bumpLockGeneration()
        state.setThreads(emptyMap())
        gate.complete(Unit)
        load.join()
        assertNull(state.messages(peer))
    }

    @Test
    fun anOlderPageMergesInAndAcksWhatIsNew() = engineTest {
        val pager = pager()
        val newest = theirs(10)
        state.setThread(peer, listOf(held(newest, "t10")))
        val older = theirs(5)
        responses += page(older, hasMore = false)
        pager.loadOlderMessages(peer)

        assertEquals(listOf(older.id, newest.id), ids())
        assertEquals(listOf(older.id), backend.delivered)
        assertFalse(pager.hasOlderHistory(peer))
        assertFalse(pager.isLoadingOlderHistory(peer))
        // Exhausted: nothing more is asked.
        pager.loadOlderMessages(peer)
        assertEquals(1, backend.pageRequests.size)
    }

    @Test
    fun overlappingLoadsShareOneFetchUnlessAReconcileIsWanted() = engineTest { // MessagingController.swift:1100-1104
        val pager = pager()
        val gate = CompletableDeferred<Unit>()
        backend.gate = gate
        responses += page(theirs(0))
        responses += page(theirs(0))
        val scope = scopeFor(testScheduler)
        val first = scope.launch { pager.loadThread(peer, activate = false) }
        val second = scope.launch { pager.loadThread(peer, activate = false) }
        val reconcile = scope.launch { pager.loadThread(peer, activate = false, reconcile = true) }
        runCurrent()
        gate.complete(Unit)
        first.join()
        second.join()
        reconcile.join()
        // One plain load for both plain callers, one more for the reconcile.
        assertEquals(2, backend.pageRequests.size)
    }
}
