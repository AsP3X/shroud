package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.DeleteConversationResponse
import de.corespace.shroud.core.net.ListMessagesResponse
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.realtime.RealtimeEvent
import de.corespace.shroud.testing.FakeAppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Deletes of a message, a note and a whole chat, and the matching socket events
 * (`MessagingController.swift:1856-2091, 4211-4253`; messaging-core §14). Every path purges what the
 * device held (plaintext, media, annotations, UI caches) and a tombstone is purged once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteEngineTest {
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
    private val backend = FakeBackend()
    private val store = FakeMessagingStore()
    private val socket = FakeSocket()
    private val sink = RecordingSink()
    private val refreshes = ArrayList<Boolean>()
    private var online = true
    private val session = MutableStateFlow<de.corespace.shroud.core.auth.Session?>(testSession(me))
    private lateinit var state: ThreadStore
    private lateinit var typing: TypingSignals

    private fun TestScope.engine(): DeleteEngine {
        val scope = scopeFor(testScheduler)
        val dispatcher = StandardTestDispatcher(testScheduler)
        state = ThreadStore(
            sessionFlow = session,
            store = store,
            scope = scope,
            io = dispatcher,
            roster = { emptyList<ContactItemDto>() to emptyList() },
            onlineNow = { online },
            realtimeConnectedNow = { true },
        ).also { it.writable = true }
        state.registerSink(sink)
        state.setConversations(listOf(Dtos.conversation(peer, conversation)))
        val clock = FakeAppClock()
        val keys = FakeKeys()
        val readState = ReadStateEngine(state, backend, { FakeNotifier() }, { true }, scope, clock)
        val pager = HistoryPager(
            state = state,
            backend = backend,
            decoder = MessageDecoder(store, keys, FakeOpener(), PeerLocks(), { false }, dispatcher, dispatcher),
            decodeContext = { me -> MessageDecoder.Context(me, state.conversations, state.threads.value) { ByteArray(32) } },
            isUnlocked = { true },
            reactions = { FakeReactionsEngine() },
            readState = readState,
            refreshPresence = {},
            scope = scope,
            clock = clock,
        )
        typing = TypingSignals(scope, object : TypingSignals.Sender {
            override fun sendTyping(peer: UUID, isTyping: Boolean) = socket.sendTyping(peer, isTyping)
            override fun sendRecording(peer: UUID, isRecording: Boolean) = socket.sendRecording(peer, isRecording)
        }, { true }, clock::elapsedMillis)
        return DeleteEngine(state, backend, pager, typing, { online }, { force -> refreshes += force }, scope)
    }

    private fun mine(n: Long, text: String = "m$n", receipt: ReceiptStatus = ReceiptStatus.Delivered, pending: Boolean = false, peerId: UUID = peer) =
        ChatMessage(UUID.randomUUID(), peerId, me, text, Instant.ofEpochSecond(1_790_000_000 + n), "2026-09-21T14:13:%02d.000001Z".format(n), isMine = true, receipt = receipt, pendingSync = pending)

    private fun theirs(n: Long, kind: ChatMessageKind = ChatMessageKind.Text) =
        ChatMessage(UUID.randomUUID(), peer, peer, "t$n", Instant.ofEpochSecond(1_790_000_000 + n), isMine = false, kind = kind)

    // --- One message (messaging-core §14.1) --------------------------------------------------------

    @Test
    fun aMessageThatNeverReachedTheServerIsDroppedHereOnly() = engineTest { // MessagingController.swift:1861-1867
        val deletes = engine()
        val queued = mine(1, receipt = ReceiptStatus.Sending, pending = true)
        val failed = mine(2, receipt = ReceiptStatus.Failed)
        state.setThread(peer, listOf(queued, failed, mine(3)))

        assertNull(deletes.deleteMessage(queued, MessageDeleteScope.Everyone))
        assertNull(deletes.deleteMessage(failed, MessageDeleteScope.Me))
        advanceUntilIdle()

        assertTrue(backend.deletedMessages.isEmpty())
        assertEquals(1, state.messages(peer)?.size)
        // Dropping it from the thread takes it out of the outbox, which is derived from the threads.
        assertTrue(OutboundPending.items(state.threads.value).isEmpty())
        assertTrue(store.removedIds.containsAll(listOf(queued.id, failed.id)))
        assertEquals(listOf(true, true), refreshes)
    }

    @Test
    fun deleteForMeAsksTheServerFirstThenDropsAndPurges() = engineTest {
        val deletes = engine()
        val message = theirs(1)
        state.setThread(peer, listOf(message, theirs(2)))
        assertNull(deletes.deleteMessage(message, MessageDeleteScope.Me))
        advanceUntilIdle()

        assertEquals(listOf(message.id to MessageDeleteScope.Me), backend.deletedMessages)
        assertFalse(state.messages(peer)!!.any { it.id == message.id })
        assertEquals(listOf(message.id), sink.purged)
        assertTrue(message.id in store.removedIds)
        assertTrue(store.persistedThreads.any { it.first == peer })
        assertEquals(listOf(true), refreshes)
    }

    @Test
    fun deleteForEveryoneLeavesTheTombstoneTheServerKeeps() = engineTest { // MessagingController.swift:1884-1888, 1977-1988
        val deletes = engine()
        val voice = mine(1).copy(kind = ChatMessageKind.Voice, text = "hello", transcript = "hello", hasFullMedia = true, durationMs = 1200)
        state.setThread(peer, listOf(voice))
        assertNull(deletes.deleteMessage(voice, MessageDeleteScope.Everyone))
        advanceUntilIdle()

        val tombstone = state.messages(peer)!!.single()
        assertEquals(ThreadMessageMerge.tombstone(voice), tombstone)
        assertEquals(ThreadMessageMerge.MESSAGE_DELETED, tombstone.text)
        assertNull(tombstone.transcript)
        assertFalse(tombstone.hasFullMedia)
        assertEquals(listOf(voice.id), sink.purged)
    }

    @Test
    fun aFailedDeleteChangesNothing() = engineTest {
        val deletes = engine()
        val message = mine(1)
        state.setThread(peer, listOf(message))
        backend.deleteMessageError = ApiError.Server("FORBIDDEN", "Only the sender can delete for everyone.", 403)
        val error = deletes.deleteMessage(message, MessageDeleteScope.Everyone)
        advanceUntilIdle()

        assertEquals("Only the sender can delete for everyone.", error)
        assertEquals(error, state.lastError.value)
        assertEquals(listOf(message), state.messages(peer))
        assertTrue(sink.purged.isEmpty())
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun withoutASessionDeletesAreRefused() = engineTest {
        val deletes = engine()
        session.value = null
        val message = mine(1)
        state.setThread(peer, listOf(message))
        assertEquals("Sign in to delete messages.", deletes.deleteMessage(message, MessageDeleteScope.Me))
        assertEquals(ChatDeleteOutcome.Failed("Sign in to delete chats."), deletes.deleteConversation(peer, ConversationDeleteScope.Me))
        assertTrue(backend.deletedMessages.isEmpty())
        assertTrue(backend.deletedConversations.isEmpty())
    }

    // --- Notes (messaging-core §14.2) --------------------------------------------------------------

    @Test
    fun aNoteIsHardDeletedOnlineAndANeverMirroredOneIsFine() = engineTest { // MessagingController.swift:1900-1939
        val deletes = engine()
        val note = mine(1, peerId = NOTES_PEER_ID).copy(receipt = ReceiptStatus.Sent)
        val other = mine(2, peerId = NOTES_PEER_ID).copy(receipt = ReceiptStatus.Sent)
        state.setThread(NOTES_PEER_ID, listOf(note, other))
        // A half-finished send parked a copy under our own id.
        state.setThread(me, listOf(note.copy(peerUserId = me)))
        backend.deleteMessageError = FakeBackend.notFound()

        assertNull(deletes.deleteMessage(note, MessageDeleteScope.Everyone))
        advanceUntilIdle()

        // Notes are always `me` on the wire (Saved Messages).
        assertEquals(listOf(note.id to MessageDeleteScope.Me), backend.deletedMessages)
        assertEquals(listOf(other.id), state.messages(NOTES_PEER_ID)?.map { it.id })
        assertNull(state.messages(me))
        assertTrue(note.id in store.removedIds)
        assertEquals(listOf(true), refreshes)
    }

    @Test
    fun anOfflineNoteDeleteIsLocalAndAnErrorKeepsTheNote() = engineTest {
        val deletes = engine()
        val note = mine(1, peerId = NOTES_PEER_ID)
        state.setThread(NOTES_PEER_ID, listOf(note))
        backend.deleteMessageError = FakeBackend.offline()
        val error = deletes.deleteMessage(note, MessageDeleteScope.Me)
        assertTrue(error != null)
        assertEquals(listOf(note), state.messages(NOTES_PEER_ID))

        online = false
        assertNull(deletes.deleteMessage(note, MessageDeleteScope.Me))
        assertEquals(emptyList<ChatMessage>(), state.messages(NOTES_PEER_ID))
        // One server call: the first; offline only the local copy goes (iOS behaviour).
        assertEquals(1, backend.deletedMessages.size)
    }

    // --- Whole chat (messaging-core §14.3) ---------------------------------------------------------

    @Test
    fun chatDeleteOutcomesSayWhatHappened() = engineTest { // MessagingController.swift:2014-2058
        val deletes = engine()
        state.setThread(peer, listOf(theirs(1), mine(2)))
        backend.deleteConversationResponse = DeleteConversationResponse(clearedForMe = true, clearedForPeer = true, tombstoned = 1)
        assertEquals(ChatDeleteOutcome.ClearedForBoth, deletes.deleteConversation(peer, ConversationDeleteScope.Everyone))

        backend.deleteConversationResponse = DeleteConversationResponse(clearedForMe = true, clearedForPeer = false, tombstoned = 1)
        assertEquals(ChatDeleteOutcome.UnsentForPeer, deletes.deleteConversation(peer, ConversationDeleteScope.Everyone))
        assertEquals(ChatDeleteOutcome.ClearedForMe, deletes.deleteConversation(peer, ConversationDeleteScope.Me))

        backend.deleteConversationError = FakeBackend.notFound()
        assertEquals(ChatDeleteOutcome.ClearedForMe, deletes.deleteConversation(peer, ConversationDeleteScope.Everyone))

        backend.deleteConversationError = FakeBackend.offline()
        val failed = deletes.deleteConversation(peer, ConversationDeleteScope.Me)
        assertTrue(failed is ChatDeleteOutcome.Failed)
        assertEquals((failed as ChatDeleteOutcome.Failed).message, state.lastError.value)
        assertEquals(listOf(peer to ConversationDeleteScope.Everyone, peer to ConversationDeleteScope.Everyone, peer to ConversationDeleteScope.Me,
            peer to ConversationDeleteScope.Everyone, peer to ConversationDeleteScope.Me), backend.deletedConversations)
    }

    @Test
    fun savedMessagesOnlyAcceptMe() = engineTest {
        val deletes = engine()
        assertEquals(
            ChatDeleteOutcome.Failed("Saved Messages can only be deleted for you."),
            deletes.deleteConversation(NOTES_PEER_ID, ConversationDeleteScope.Everyone),
        )
        assertTrue(backend.deletedConversations.isEmpty())
        state.setThread(NOTES_PEER_ID, listOf(mine(1, peerId = NOTES_PEER_ID)))
        assertEquals(ChatDeleteOutcome.ClearedForMe, deletes.deleteConversation(NOTES_PEER_ID, ConversationDeleteScope.Me))
        // The self conversation on the wire; the Notes row stays (a fixture, not a server chat).
        assertEquals(listOf(me to ConversationDeleteScope.Me), backend.deletedConversations)
        assertEquals(emptyList<ChatMessage>(), state.messages(NOTES_PEER_ID))
    }

    @Test
    fun clearingAChatDropsItsRowCountTypingAndCaches() = engineTest { // MessagingController.swift:2064-2091
        val deletes = engine()
        val messages = listOf(theirs(1), mine(2))
        state.setThread(peer, messages)
        state.editUnread { it + (peer to 3) }
        typing.setPeerTyping(peer, true)
        assertEquals(ChatDeleteOutcome.ClearedForMe, deletes.deleteConversation(peer, ConversationDeleteScope.Me))
        advanceUntilIdle()

        assertEquals(emptyList<ChatMessage>(), state.messages(peer))
        assertTrue(state.conversations.isEmpty())
        assertFalse(peer in state.unread.value)
        assertNull(typing.peerActivity(peer))
        assertEquals(messages.map { it.id }.toSet(), sink.purged.toSet())
        // The contact stays: nothing here touches contacts.
    }

    // --- Socket events (messaging-core §10.6, §14.4) -----------------------------------------------

    @Test
    fun messageDeletedTombstonesAndPurgesOnce() = engineTest { // MessagingController.swift:4211-4218
        val deletes = engine()
        val message = theirs(1, kind = ChatMessageKind.Image).copy(text = "Photo", hasFullMedia = true, mediaObjectId = UUID.randomUUID())
        state.setThread(peer, listOf(message))
        deletes.onMessageDeleted(RealtimeEvent.MessageDeleted(message.id, conversation))
        deletes.onMessageDeleted(RealtimeEvent.MessageDeleted(message.id, conversation))
        // An id no thread holds is nothing to do.
        deletes.onMessageDeleted(RealtimeEvent.MessageDeleted(UUID.randomUUID(), conversation))
        advanceUntilIdle()

        val tombstone = state.messages(peer)!!.single()
        assertTrue(tombstone.deleted)
        assertEquals(ChatMessageKind.Image, tombstone.kind)
        assertNull(tombstone.mediaObjectId)
        assertEquals(listOf(message.id), sink.purged)
        assertEquals(1, store.removedIds.count { it == message.id })
        assertEquals(listOf(true, true), refreshes)
    }

    @Test
    fun ourOtherDeviceOrAConsentedPeerDeleteClearsTheChat() = engineTest { // MessagingController.swift:4227-4253
        val deletes = engine()
        state.setThread(peer, listOf(theirs(1)))
        deletes.onConversationDeleted(RealtimeEvent.ConversationDeleted(me, peer, conversation, "me", clearedForPeer = false))
        advanceUntilIdle()
        assertEquals(emptyList<ChatMessage>(), state.messages(peer))

        state.setThread(peer, listOf(theirs(2)))
        deletes.onConversationDeleted(RealtimeEvent.ConversationDeleted(peer, me, conversation, "everyone", clearedForPeer = true))
        advanceUntilIdle()
        assertEquals(emptyList<ChatMessage>(), state.messages(peer))

        // Saved Messages cleared on our other device: the Notes thread.
        state.setThread(NOTES_PEER_ID, listOf(mine(3, peerId = NOTES_PEER_ID)))
        deletes.onConversationDeleted(RealtimeEvent.ConversationDeleted(me, me, null, "me", clearedForPeer = false))
        advanceUntilIdle()
        assertEquals(emptyList<ChatMessage>(), state.messages(NOTES_PEER_ID))
        assertTrue(backend.pageRequests.isEmpty())
    }

    @Test
    fun aPeerDeleteWithoutConsentReloadsTheChatWithoutOpeningIt() = engineTest {
        val deletes = engine()
        val ours = mine(1)
        val their = theirs(2)
        state.setThread(peer, listOf(ours, their))
        val other = UUID.randomUUID()
        state.setActivePeer(other)
        val tombstoneDto = Dtos.message(id = their.id, sender = peer, conversation = conversation, createdAtWire = "2026-09-21T14:13:22.000001Z", deleted = true)
        backend.pages = { ListMessagesResponse(messages = listOf(tombstoneDto), hasMore = false) }

        deletes.onConversationDeleted(RealtimeEvent.ConversationDeleted(peer, me, conversation, "everyone", clearedForPeer = false))
        advanceUntilIdle()

        // A reconcile walk, the user stays where they were, their messages turn into tombstones.
        assertEquals(peer, backend.pageRequests.single().peer)
        assertEquals(other, state.activePeerId)
        assertTrue(state.messages(peer)!!.first { it.id == their.id }.deleted)
        assertFalse(state.messages(peer)!!.first { it.id == ours.id }.deleted)
        assertEquals(listOf(their.id), sink.purged)
        assertTrue(true in refreshes)
    }
}
