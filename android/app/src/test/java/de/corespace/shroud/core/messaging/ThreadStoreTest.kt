package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.MessageReaction
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ContactItemDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The shared thread state (plan §1.7.7 `ThreadState`): re-keys, purges, persistence and transfers. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadStoreTest {
    private val scopes = EngineScopes()
    private var engineScope: kotlinx.coroutines.CoroutineScope? = null

    /** One engine scope per test (see [EngineScopes]). */
    private fun scopeFor(scheduler: TestCoroutineScheduler) = engineScope ?: scopes.create(scheduler).also { engineScope = it }

    @After
    fun tearDown() = scopes.cancelAll()

    private val me = UUID.randomUUID()
    private val peer = UUID.randomUUID()
    private val disk = FakeMessagingStore()
    private val contacts = MutableStateFlow<List<ContactItemDto>>(emptyList())
    private val base = Instant.parse("2026-09-24T12:00:00Z")

    private fun TestScope.threadStore(writable: Boolean = true) = ThreadStore(
        sessionFlow = MutableStateFlow(testSession(me)),
        store = disk,
        scope = scopeFor(testScheduler),
        io = StandardTestDispatcher(testScheduler),
        roster = { contacts.value to emptyList() },
        onlineNow = { true },
        realtimeConnectedNow = { true },
    ).also { it.writable = writable }

    private fun mine(text: String, at: Instant = base, id: UUID = UUID.randomUUID(), pending: Boolean = false) =
        ChatMessage(id, peer, me, text, at, isMine = true, receipt = if (pending) ReceiptStatus.Sending else ReceiptStatus.Sent, pendingSync = pending)

    @Test
    fun editPublishesOnlyRealChanges() = runTest {
        val state = threadStore()
        state.edit(peer) { it }
        assertNull(state.messages(peer))
        val message = mine("a")
        state.edit(peer) { it + message }
        val published = state.threads.value
        state.edit(peer) { it.toList() }
        assertSame(published, state.threads.value)
        assertTrue(state.update(message.id) { it.copy(text = "b") })
        assertEquals("b", state.messages(peer)?.single()?.text)
        assertFalse(state.update(UUID.randomUUID()) { it })
    }

    /** Memory: *Server re-keys sent messages* — the bubble ends up under the server id, its caches gone. */
    @Test
    fun rekeyReplacesTheOptimisticBubbleInPlace() = runTest {
        val state = threadStore()
        val sink = RecordingSink()
        state.registerSink(sink)
        val before = mine("before", base.minusSeconds(5))
        val optimistic = mine("hello", pending = true)
        val after = mine("after", base.plusSeconds(5))
        state.setThread(peer, listOf(before, optimistic, after))
        state.transfers.begin(optimistic.id, isUpload = true)

        val sent = optimistic.copy(id = UUID.randomUUID(), pendingSync = false, receipt = ReceiptStatus.Sent, createdAtWire = "2026-09-24T12:00:01.000001Z")
        state.rekey(peer, optimistic.id, sent)
        advanceUntilIdle()

        assertEquals(listOf(before.id, sent.id, after.id), state.messages(peer)?.map { it.id })
        assertEquals(listOf(optimistic.id), disk.removedIds)
        assertEquals(listOf(optimistic.id to sent.id), sink.rekeyed)
        assertTrue(sent.id in state.transfers.transfers.value)
        assertFalse(optimistic.id in state.transfers.transfers.value)
        assertEquals(sent, disk.persisted.last().threads.getValue(peer)[1])
    }

    @Test
    fun rekeyNeverLeavesTwoCopies() = runTest {
        val state = threadStore()
        val optimistic = mine("x", pending = true)
        val sent = optimistic.copy(id = UUID.randomUUID(), pendingSync = false)
        // A refresh brought the server copy in before the send returned.
        state.setThread(peer, listOf(optimistic, sent))
        state.rekey(peer, optimistic.id, sent)
        assertEquals(listOf(sent.id), state.messages(peer)?.map { it.id })
        // The optimistic bubble is gone already: the server copy is not appended twice.
        state.rekey(peer, optimistic.id, sent)
        assertEquals(listOf(sent.id), state.messages(peer)?.map { it.id })
        // Neither copy present: appended in time order.
        state.setThread(peer, listOf(mine("later", base.plusSeconds(60))))
        state.rekey(peer, optimistic.id, sent)
        assertEquals(sent.id, state.messages(peer)?.first()?.id)
    }

    @Test
    fun aNotesRekeyDropsTheCopyUnderOurOwnId() = runTest { // MessagingController.swift:4801-4815
        val state = threadStore()
        val note = ChatMessage(UUID.randomUUID(), NOTES_PEER_ID, me, "milk", base, isMine = true)
        val sent = note.copy(id = UUID.randomUUID(), createdAtWire = "2026-09-24T12:00:00.5Z")
        state.setThread(NOTES_PEER_ID, listOf(note))
        // The shared send helper appended the server copy under our own id.
        state.setThread(me, listOf(sent.copy(peerUserId = me)))
        state.rekey(NOTES_PEER_ID, note.id, sent)
        assertEquals(listOf(sent), state.messages(NOTES_PEER_ID))
        assertNull(state.messages(me))
    }

    @Test
    fun rekeyFoldsATranscriptSharedWhileSending() = runTest {
        val state = threadStore()
        val voice = mine("Voice message", pending = true).copy(kind = ChatMessageKind.Voice)
        val sent = voice.copy(id = UUID.randomUUID(), pendingSync = false)
        state.setThread(peer, listOf(voice))
        state.noteSharedTranscript("shared", sent.id)
        state.rekey(peer, voice.id, sent)
        assertEquals("shared", state.messages(peer)?.single()?.transcript)
        // Folded once, then forgotten.
        assertEquals(listOf(voice), state.foldSharedTranscripts(listOf(voice)))
    }

    /** Android D5: a purge takes the annotations about a message along (messaging-core §14.6). */
    @Test
    fun purgeScrubsCachesAnnotationsSinksJobsAndRings() = runTest {
        val state = threadStore()
        val sink = RecordingSink()
        state.registerSink(sink)
        val cancelled = ArrayList<UUID>()
        state.onPurge = { cancelled += it }
        val voice = UUID.randomUUID()
        val annotation = UUID.randomUUID()
        disk.annotations[voice] = mutableSetOf(annotation)
        state.noteSharedTranscript("pending", voice)
        state.transfers.begin(voice, isUpload = false)

        state.purge(listOf(voice))
        advanceUntilIdle()

        assertEquals(setOf(voice, annotation), disk.removedIds.toSet())
        assertEquals(listOf(voice), sink.purged)
        assertEquals(listOf(voice), cancelled)
        assertTrue(state.transfers.transfers.value.isEmpty())
        val thread = listOf(mine("Voice message", id = voice).copy(kind = ChatMessageKind.Voice))
        assertSame(thread, state.foldSharedTranscripts(thread))
    }

    @Test
    fun aDeletedAnnotationIsPurgedOncePerSession() = runTest {
        val state = threadStore()
        val annotation = UUID.randomUUID()
        state.purgeAnnotation(annotation)
        state.purgeAnnotation(annotation)
        advanceUntilIdle()
        assertEquals(listOf(annotation), disk.removedIds)
    }

    @Test
    fun nothingIsWrittenUntilTheCacheWasRead() = runTest {
        val state = threadStore(writable = false)
        state.setThread(peer, listOf(mine("a")))
        state.persistSnapshot()
        state.persistThread(peer)
        advanceUntilIdle()
        assertTrue(disk.persisted.isEmpty())
        assertTrue(disk.persistedThreads.isEmpty())
        state.writable = true
        state.persistThread(peer)
        advanceUntilIdle()
        assertEquals(1, disk.persistedThreads.size)
    }

    @Test
    fun persistWritesSettledThreadsAndDropsWhatRetentionPruned() = runTest {
        val state = threadStore()
        val old = mine("old", base.minusSeconds(100 * 86_400L))
        val fresh = mine("fresh").copy(reactions = listOf(MessageReaction(me, listOf("🔥"), seq = 3, pending = true)))
        state.setThread(peer, listOf(old, fresh))
        state.settle = { messages -> messages.map { it.copy(reactions = it.reactions.filterNot(MessageReaction::pending)) } }
        disk.pruneOnPersist = setOf(old.id)
        state.persistSnapshot()
        advanceUntilIdle()
        val written = disk.persisted.single().threads.getValue(peer)
        assertTrue(written.last().reactions.isEmpty())
        assertEquals(listOf(fresh.id), state.messages(peer)?.map { it.id })
        // Memory keeps the pending entry; only the written copy is settled.
        assertEquals(1, state.messages(peer)?.single()?.reactions?.size)
    }

    @Test
    fun aMissingThreadPersistsTheSnapshot() = runTest { // MessagingController.swift:4708-4712
        val state = threadStore()
        state.persistThread(peer)
        advanceUntilIdle()
        assertEquals(1, disk.persisted.size)
        assertTrue(disk.persistedThreads.isEmpty())
    }

    @Test
    fun flushWaitsForQueuedWritesThenTheStore() = runTest {
        val state = threadStore()
        state.setThread(peer, listOf(mine("a")))
        state.persistThread(peer)
        state.flush()
        assertEquals(listOf("persistThread", "flush"), disk.events)
    }

    @Test
    fun notesMapToOurOwnIdOnTheWire() = runTest {
        val state = threadStore()
        assertEquals(NOTES_PEER_ID, state.storePeer(me))
        assertEquals(peer, state.storePeer(peer))
        assertEquals(me, state.apiPeer(NOTES_PEER_ID))
        assertEquals(peer, state.apiPeer(peer))
        assertTrue(state.isNotes(NOTES_PEER_ID))
        assertFalse(state.isNotes(me))
    }

    @Test
    fun usernamesComeFromTheListThenContacts() = runTest { // MessagingController.swift:5850-5854
        val state = threadStore()
        contacts.value = listOf(ContactItemDto(peer, "bob-contact", base))
        assertEquals("bob-contact", state.username(peer))
        state.setConversations(listOf(Dtos.conversation(peer, username = "bob")))
        assertEquals("bob", state.username(peer))
        assertNull(state.username(UUID.randomUUID()))
    }

    @Test
    fun theRosterSnapshotCarriesListContactsAndPositiveCounts() = runTest {
        val state = threadStore()
        val conversation = Dtos.conversation(peer).copy(reactionSeq = 9, unseenReactions = 1)
        state.setConversations(listOf(conversation))
        contacts.value = listOf(ContactItemDto(peer, "bob", base))
        state.editUnread { mapOf(peer to 2, UUID.randomUUID() to 0) }
        val roster = state.rosterSnapshot()
        assertEquals(listOf(ThreadStore.cached(conversation)), roster.conversations)
        assertEquals(mapOf(peer to 2), roster.unreadByPeer)
        assertEquals(1, roster.contacts.size)
        // Restored rows carry neither the server's count nor its mute.
        val restored = ThreadStore.restored(roster.conversations.single())
        assertEquals(conversation.copy(unreadCount = null, mute = null), restored)
    }

    @Test
    fun transfersFollowTheRingRules() { // MessagingController.swift:2943-2991
        val board = TransferBoard()
        val id = UUID.randomUUID()
        board.update(id, 0.5) // unknown: ignored
        assertTrue(board.transfers.value.isEmpty())
        board.begin(id, isUpload = true)
        board.advance(id, MediaTransfer.Phase.Preparing, totalBytes = 1000)
        board.update(id, 1.7)
        assertEquals(MediaTransfer(MediaTransfer.Phase.Preparing, isUpload = true, fraction = 1.0, totalBytes = 1000), board.transfers.value[id])
        board.advance(id, MediaTransfer.Phase.Transferring)
        assertEquals(MediaTransfer(MediaTransfer.Phase.Transferring, isUpload = true, fraction = null, totalBytes = 1000), board.transfers.value[id])
        board.update(id, -1.0)
        assertEquals(0.0, board.transfers.value[id]?.fraction)
        val to = UUID.randomUUID()
        board.rekey(id, to)
        assertEquals(setOf(to), board.transfers.value.keys)
        board.end(to)
        assertTrue(board.transfers.value.isEmpty())
    }
}
