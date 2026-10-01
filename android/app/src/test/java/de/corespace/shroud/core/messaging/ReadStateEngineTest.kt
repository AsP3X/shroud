package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.realtime.RealtimeEvent
import de.corespace.shroud.testing.FakeAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Receipts, unread counts, read markers, badge and mutes (messaging-core §15–§16; `MessagingController.swift:4262-4327, 5611-5848`). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadStateEngineTest {
    private val scopes = EngineScopes()
    private var engineScope: kotlinx.coroutines.CoroutineScope? = null

    /** One engine scope per test (see [EngineScopes]). */
    private fun scopeFor(scheduler: TestCoroutineScheduler) = engineScope ?: scopes.create(scheduler).also { engineScope = it }

    @After
    fun tearDown() = scopes.cancelAll()

    private val me = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    private val peer = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private val backend = FakeBackend()
    private val notifier = FakeNotifier()
    private val clock = FakeAppClock()
    private var resumed = true
    private lateinit var state: ThreadStore

    private fun TestScope.engine(): ReadStateEngine {
        state = ThreadStore(
            sessionFlow = MutableStateFlow(testSession(me)),
            store = FakeMessagingStore(),
            scope = scopeFor(testScheduler),
            io = StandardTestDispatcher(testScheduler),
            roster = { emptyList<ContactItemDto>() to emptyList() },
            onlineNow = { true },
            realtimeConnectedNow = { true },
        ).also { it.writable = true }
        state.editListStatus { it.copy(hasLoadedChats = true) }
        return ReadStateEngine(state, backend, { notifier }, { resumed }, scopeFor(testScheduler), clock)
    }

    private fun mine(at: Long, receipt: ReceiptStatus = ReceiptStatus.Delivered, id: UUID = UUID.randomUUID()) =
        ChatMessage(id, peer, me, "m$at", Instant.ofEpochSecond(at), isMine = true, receipt = receipt)

    private fun theirs(at: Long, id: UUID = UUID.randomUUID()) = ChatMessage(id, peer, peer, "t$at", Instant.ofEpochSecond(at), isMine = false)

    // --- Receipts ---------------------------------------------------------------------------------

    @Test
    fun aReceiptOnlyRises() = runTest {
        val read = engine()
        val message = mine(1, ReceiptStatus.Read)
        state.setThread(peer, listOf(message))
        read.updateReceipt(message.id, ReceiptStatus.Delivered)
        assertEquals(ReceiptStatus.Read, state.messages(peer)?.single()?.receipt)
        val sent = mine(2, ReceiptStatus.Sent)
        state.setThread(peer, listOf(sent))
        read.updateReceipt(sent.id, ReceiptStatus.Delivered)
        assertEquals(ReceiptStatus.Delivered, state.messages(peer)?.single()?.receipt)
    }

    @Test
    fun aReadMarksEveryEarlierMessageOfOursRead() = runTest { // MessagingController.swift:4308-4326
        val read = engine()
        val first = mine(1)
        val anchor = mine(2)
        val later = mine(3)
        val inbound = theirs(1)
        state.setThread(peer, listOf(first, inbound, anchor, later))
        read.onMessageRead(RealtimeEvent.MessageRead(anchor.id, anchor.id, null, peer, null, null, null), me)
        assertEquals(
            listOf(ReceiptStatus.Read, ReceiptStatus.Sent, ReceiptStatus.Read, ReceiptStatus.Delivered),
            state.messages(peer)?.map { it.receipt },
        )
    }

    /** Decision D8b (`MessageReadEventTests`): our own reads on another device tick nothing. */
    @Test
    fun ourOwnReadOnAnotherDeviceIsIgnored() = runTest {
        val read = engine()
        val message = mine(1)
        state.setThread(peer, listOf(message))
        read.onMessageRead(RealtimeEvent.MessageRead(message.id, message.id, null, me, null, null, null), me)
        assertEquals(ReceiptStatus.Delivered, state.messages(peer)?.single()?.receipt)
        read.onMessageRead(RealtimeEvent.MessageRead(message.id, null, null, peer, null, null, null), me)
        assertEquals(ReceiptStatus.Read, state.messages(peer)?.single()?.receipt)
    }

    @Test
    fun isPeerReadVectors() { // MessageReadEventTests.swift:23-41
        assertTrue(ReadStateEngine.isPeerRead(peer, me))
        assertFalse(ReadStateEngine.isPeerRead(me, me))
        // Without a readable reader (absent or not a UUID, which the parser leaves null) or a session: the peer's.
        assertTrue(ReadStateEngine.isPeerRead(null, me))
        assertTrue(ReadStateEngine.isPeerRead(me, null))
    }

    @Test
    fun readReceiptsOffStepReadTicksBack() = runTest { // MessagingController.swift:2184-2195
        val read = engine()
        state.setThread(peer, listOf(mine(1, ReceiptStatus.Read), theirs(2), mine(3, ReceiptStatus.Delivered)))
        read.hideReadTicks()
        assertEquals(listOf(ReceiptStatus.Delivered, ReceiptStatus.Sent, ReceiptStatus.Delivered), state.messages(peer)?.map { it.receipt })
    }

    // --- Reading a chat ---------------------------------------------------------------------------

    @Test
    fun readingAChatCoalescesIntoOneMarkerAndClosesNotifications() = runTest {
        val read = engine()
        val conversation = Dtos.conversation(peer, unread = 3, last = Instant.ofEpochSecond(50))
        state.setConversations(listOf(conversation))
        state.editUnread { mapOf(peer to 3) }
        read.didReadChat(peer)
        read.didReadChat(peer)
        assertEquals(0, read.unreadCount(peer))
        assertEquals(0, state.conversations.single().unreadCount)
        assertEquals(listOf(conversation.id, conversation.id), notifier.cleared)
        advanceTimeBy(399)
        assertTrue(backend.chatReads.isEmpty())
        advanceUntilIdle()
        // The second read came before the request left: one request covers both (`:5676-5678`).
        assertEquals(listOf(peer), backend.chatReads)
        assertEquals(0, notifier.badges.last())
    }

    @Test
    fun notesAreNeverRead() = runTest {
        val read = engine()
        read.didReadChat(NOTES_PEER_ID)
        advanceUntilIdle()
        assertTrue(backend.chatReads.isEmpty())
    }

    @Test
    fun aReadTheServerMissedIsSentAgainAfterTheNextList() = runTest {
        val read = engine()
        backend.chatReadError = FakeBackend.offline()
        read.didReadChat(peer)
        advanceUntilIdle()
        assertEquals(1, backend.chatReads.size)
        backend.chatReadError = null
        read.retryChatReads()
        advanceUntilIdle()
        assertEquals(2, backend.chatReads.size)
        read.retryChatReads()
        advanceUntilIdle()
        assertEquals(2, backend.chatReads.size)
    }

    @Test
    fun anOlderServerGetsReceiptsUpToThePeersNewestMessage() = runTest { // :5694-5707
        val read = engine()
        read.serverKeepsReadMarkers = false
        val newest = theirs(5)
        state.setThread(peer, listOf(theirs(1), newest, mine(6)))
        read.didReadChat(peer)
        advanceUntilIdle()
        assertEquals(listOf(peer to newest.id), backend.bulkReads)
        assertTrue(backend.chatReads.isEmpty())
    }

    // --- The server's counts ----------------------------------------------------------------------

    @Test
    fun aListThatRacedTheReadStillCountsNothing() = runTest { // :5614-5631
        val read = engine()
        read.markReadLocally(peer, null, Instant.ofEpochSecond(100))
        val raced = Dtos.conversation(peer, unread = 2, last = Instant.ofEpochSecond(90))
        assertEquals(0, read.applyingLocalChatState(listOf(raced)).single().unreadCount)
        val newer = Dtos.conversation(peer, unread = 2, last = Instant.ofEpochSecond(110))
        assertEquals(2, read.applyingLocalChatState(listOf(newer)).single().unreadCount)
    }

    @Test
    fun theServersCountsWinExceptForTheChatBeingRead() = runTest { // :5640-5657
        val read = engine()
        val other = UUID.randomUUID()
        state.editUnread { mapOf(UUID.randomUUID() to 4, NOTES_PEER_ID to 0) }
        state.setActivePeer(peer)
        read.adoptServerUnreadCounts(listOf(Dtos.conversation(peer, unread = 1), Dtos.conversation(other, unread = 2)))
        assertEquals(mapOf(NOTES_PEER_ID to 0, peer to 0, other to 2), state.unread.value)
        advanceUntilIdle()
        // The chat on screen that the server still counted was read now.
        assertEquals(listOf(peer), backend.chatReads)
        assertEquals(2, notifier.badges.last())

        // In the background the open chat is not being read.
        resumed = false
        read.adoptServerUnreadCounts(listOf(Dtos.conversation(peer, unread = 1)))
        assertEquals(1, read.unreadCount(peer))
    }

    @Test
    fun anOlderServerWithoutCountsKeepsTheLocalOnes() = runTest {
        val read = engine()
        state.editUnread { mapOf(peer to 4) }
        read.adoptServerUnreadCounts(listOf(Dtos.conversation(peer, unread = null)))
        assertEquals(4, read.unreadCount(peer))
    }

    @Test
    fun ourOtherDeviceReadAChat() = runTest { // :5725-5737
        val read = engine()
        state.editUnread { mapOf(peer to 5) }
        read.onConversationRead(RealtimeEvent.ConversationRead(peer, UUID.randomUUID(), Instant.ofEpochSecond(10), unreadCount = 2))
        assertEquals(2, read.unreadCount(peer))
        read.onConversationRead(RealtimeEvent.ConversationRead(peer, null, null, unreadCount = 0))
        assertEquals(0, read.unreadCount(peer))
    }

    @Test
    fun totalsSkipNotesAndMutedChats() = runTest { // :5835-5848
        val read = engine()
        val muted = UUID.randomUUID()
        state.setConversations(listOf(Dtos.conversation(peer), Dtos.conversation(muted, mute = ChatMuteDto(until = null))))
        state.editUnread { mapOf(peer to 2, muted to 3, NOTES_PEER_ID to 9, UUID.randomUUID() to -1) }
        assertEquals(2, read.unreadTotal(includeMuted = false))
        assertEquals(5, read.unreadTotal(includeMuted = true))
        notifier.badgeIncludesMuted = true
        read.updateBadge()
        assertEquals(5, notifier.badges.last())
        // Nothing before the chats loaded.
        state.editListStatus { it.copy(hasLoadedChats = false) }
        val count = notifier.badges.size
        read.updateBadge()
        assertEquals(count, notifier.badges.size)
    }

    // --- Mutes ------------------------------------------------------------------------------------

    @Test
    fun aMuteIsOptimisticConfirmedAndHeldForSixSeconds() = runTest { // :5781-5827
        val read = engine()
        state.setConversations(listOf(Dtos.conversation(peer)))
        backend.muteUntil = clock.now().plusSeconds(3600)
        assertNull(read.changeMute(peer, MuteDuration.Hour) { })
        assertEquals(listOf(peer to 3600L), backend.mutes)
        assertEquals(ChatMuteDto(clock.now().plusSeconds(3600)), read.mute(peer))
        // A list that predates the mute does not undo it while held.
        assertEquals(ChatMuteDto(clock.now().plusSeconds(3600)), read.applyingLocalChatState(listOf(Dtos.conversation(peer))).single().mute)
        advanceTimeBy(6_001)
        runCurrent()
        assertNull(read.applyingLocalChatState(listOf(Dtos.conversation(peer))).single().mute)
    }

    @Test
    fun aFailedMuteRollsBack() = runTest {
        val read = engine()
        state.setConversations(listOf(Dtos.conversation(peer)))
        backend.muteError = FakeBackend.offline()
        var refreshed = false
        val error = read.changeMute(peer, MuteDuration.Forever) { refreshed = true }
        assertEquals("The Internet connection appears to be offline.", error)
        assertNull(read.mute(peer))
        assertNull(state.conversations.single().mute)
        assertTrue(refreshed)
    }

    @Test
    fun unmuteIsPendingAtOnce() = runTest {
        val read = engine()
        state.setConversations(listOf(Dtos.conversation(peer, mute = ChatMuteDto(until = null))))
        assertTrue(read.isMuted(peer))
        assertNull(read.changeMute(peer, null) { })
        assertEquals(listOf(peer), backend.unmutes)
        assertFalse(read.isMuted(peer))
    }

    @Test
    fun muteRulesAndStrings() = runTest {
        val read = engine()
        assertEquals("A chat can be muted once it has messages.", read.changeMute(peer, MuteDuration.Hour) { })
        assertFalse(read.canMute(NOTES_PEER_ID))
        state.setConversations(listOf(Dtos.conversation(peer, mute = ChatMuteDto(until = clock.now().minusSeconds(1)))))
        assertTrue(read.canMute(peer))
        // An expired mute is over before the next list says so.
        assertFalse(read.isMuted(peer))
        read.onConversationMute(RealtimeEvent.ConversationMute(peer, ChatMuteDto(until = null)))
        assertTrue(read.isMuted(peer))
    }

    @Test
    fun withoutASessionAMuteIsRefused() = runTest {
        val read = engine()
        state.setConversations(listOf(Dtos.conversation(peer)))
        val signedOut = ReadStateEngine(
            ThreadStore(MutableStateFlow(null), FakeMessagingStore(), scopeFor(testScheduler), StandardTestDispatcher(testScheduler), { emptyList<ContactItemDto>() to emptyList() }, { true }, { true }),
            backend, { notifier }, { true }, scopeFor(testScheduler), clock,
        )
        assertEquals("Not signed in.", signedOut.changeMute(peer, MuteDuration.Hour) { })
        assertTrue(read.canMute(peer))
    }
}
