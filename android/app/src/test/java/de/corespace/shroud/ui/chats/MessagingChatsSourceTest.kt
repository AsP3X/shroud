package de.corespace.shroud.ui.chats

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.messaging.CachedConversation
import de.corespace.shroud.core.messaging.Dtos
import de.corespace.shroud.core.messaging.EngineScopes
import de.corespace.shroud.core.messaging.FakeBackend
import de.corespace.shroud.core.messaging.FakeContacts
import de.corespace.shroud.core.messaging.FakeKeys
import de.corespace.shroud.core.messaging.FakeMediaLoader
import de.corespace.shroud.core.messaging.FakeMessagingStore
import de.corespace.shroud.core.messaging.FakeNotifier
import de.corespace.shroud.core.messaging.FakeOpener
import de.corespace.shroud.core.messaging.FakePeerIdentities
import de.corespace.shroud.core.messaging.FakePrivacy
import de.corespace.shroud.core.messaging.FakeReactionsEngine
import de.corespace.shroud.core.messaging.FakeSendEngine
import de.corespace.shroud.core.messaging.FakeSocket
import de.corespace.shroud.core.messaging.HydratedMessages
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.messaging.MessagingDependencies
import de.corespace.shroud.core.messaging.RosterSnapshot
import de.corespace.shroud.core.messaging.testSession
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.testing.FakeAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * [MessagingChatsSource] on the real `MessagingController` (W2-MSG-CORE) over the messaging test
 * fakes: what the list reads (previews, unread, mutes, Notes) and what its actions reach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagingChatsSourceTest {
    private val scopes = EngineScopes()
    private val me = UUID.fromString("0b6e1f2a-6c3d-4e8f-9a1b-2c3d4e5f6a7b")
    private val jane = ChatsFixtures.jane
    private val mom = ChatsFixtures.mom
    private val janeChat = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")
    private val momChat = UUID.fromString("e1d2c3b4-a596-4877-8695-a4b3c2d1e0f9")
    private val session = MutableStateFlow<Session?>(testSession(me))
    private val backend = FakeBackend()
    private val store = FakeMessagingStore()
    private val contacts = FakeContacts()
    private val clock = FakeAppClock()

    @After
    fun tearDown() = scopes.cancelAll()

    private fun engineTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            scopes.cancelAll()
        }
    }

    private fun TestScope.controller(): MessagingController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MessagingController(
            MessagingDependencies(
                scope = scopes.create(testScheduler),
                session = session,
                backend = backend,
                socket = FakeSocket(),
                keys = FakeKeys(),
                opener = FakeOpener(),
                peerLocks = PeerLocks(),
                store = store,
                hasMedia = { false },
                contacts = contacts,
                peerIdentities = FakePeerIdentities(),
                privacy = FakePrivacy(),
                notifier = FakeNotifier(),
                sendEngine = { FakeSendEngine() },
                reactionsEngine = { FakeReactionsEngine() },
                mediaLoader = { FakeMediaLoader() },
                isOnline = { true },
                isResumed = { true },
                wipeKeyRecords = {},
                refreshCallSecrets = {},
                clock = clock,
                io = dispatcher,
                compute = dispatcher,
            ),
        )
    }

    private fun message(peer: UUID, text: String, at: String) =
        ChatMessage(UUID.randomUUID(), peer, peer, text, Instant.parse(at), isMine = false)

    @Test
    fun theSnapshotCarriesWhatTheListShows() = engineTest {
        store.hydrated = HydratedMessages(
            RosterSnapshot(
                conversations = listOf(CachedConversation(janeChat, jane, "jane", Instant.parse("2026-09-01T10:00:00Z"), null, null, null)),
                contacts = emptyList(),
                incomingRequests = emptyList(),
                unreadByPeer = mapOf(jane to 2),
            ),
            mapOf(
                jane to listOf(message(jane, "Ok", "2026-09-21T09:38:00Z")),
                NOTES_PEER_ID to listOf(message(NOTES_PEER_ID, "Buy milk", "2026-09-21T08:15:00Z")),
            ),
        )
        backend.conversationList = listOf(
            Dtos.conversation(jane, janeChat, username = "jane", unread = 2, last = Instant.parse("2026-09-21T09:38:00Z")),
            Dtos.conversation(mom, momChat, username = "mom", unread = 0, mute = ChatMuteDto(until = null)),
        )
        val controller = controller()
        controller.start()
        runCurrent()

        val snapshot = MessagingChatsSource(controller, contacts).snapshot()
        assertEquals(listOf(jane, mom), snapshot.conversations.map { it.peer.id })
        assertTrue(snapshot.status.hasLoadedChats)
        assertEquals("Ok", snapshot.previews[jane])
        assertEquals("Encrypted conversation", snapshot.previews[mom])
        assertEquals("Buy milk", snapshot.previews[NOTES_PEER_ID])
        assertEquals(Instant.parse("2026-09-21T08:15:00Z"), snapshot.notesLastActivity)
        assertEquals(mapOf(jane to 2), snapshot.unread)
        assertEquals(mapOf<UUID, Instant?>(mom to null), snapshot.mutedUntil)
        assertTrue(snapshot.unseenReactions.isEmpty())
        assertNull(snapshot.activePeer)
        assertFalse(snapshot.isOffline)
        controller.stop(wipeDisk = false)
    }

    @Test
    fun anExpiredMuteIsNotListed() = engineTest {
        backend.conversationList = listOf(
            Dtos.conversation(mom, momChat, username = "mom", mute = ChatMuteDto(until = clock.now().minusSeconds(1))),
        )
        val controller = controller()
        controller.start()
        runCurrent()
        assertTrue(MessagingChatsSource(controller, contacts).snapshot().mutedUntil.isEmpty())
        controller.stop(wipeDisk = false)
    }

    @Test
    fun changesFireWhenTheListMoves() = engineTest {
        val controller = controller()
        val source = MessagingChatsSource(controller, contacts)
        var emissions = 0
        val collector = backgroundScope.launch { source.changes.collect { emissions++ } }
        runCurrent()
        val before = emissions
        backend.conversationList = listOf(Dtos.conversation(jane, janeChat, username = "jane"))
        controller.start()
        runCurrent()
        assertTrue("a list that changed must reach the screen", emissions > before)
        assertEquals(listOf(jane), source.snapshot().conversations.map { it.peer.id })
        collector.cancel()
        controller.stop(wipeDisk = false)
    }

    @Test
    fun actionsReachTheEngine() = engineTest {
        backend.conversationList = listOf(Dtos.conversation(jane, janeChat, username = "jane", unread = 1))
        backend.muteUntil = clock.now().plusSeconds(3_600)
        val controller = controller()
        controller.start()
        runCurrent()
        val source = MessagingChatsSource(controller, contacts)

        assertNull(source.muteChat(jane, MuteDuration.Hour))
        assertEquals(listOf(jane to 3_600L), backend.mutes)
        assertEquals(ChatMuteDto(until = clock.now().plusSeconds(3_600)), source.mute(jane))
        assertNull(source.unmuteChat(jane))
        assertEquals(listOf(jane), backend.unmutes)
        assertNull(source.mute(jane))

        // Notes cannot be deleted for both (MC:1975-1977); the engine says why.
        assertEquals(
            ChatDeleteOutcome.Failed("Saved Messages can only be deleted for you."),
            source.deleteConversation(NOTES_PEER_ID, ConversationDeleteScope.Everyone),
        )
        assertEquals(ChatDeleteOutcome.ClearedForMe, source.deleteConversation(jane, ConversationDeleteScope.Me))
        assertEquals(listOf(jane to ConversationDeleteScope.Me), backend.deletedConversations)

        source.refreshContacts(force = true)
        assertEquals("refresh:true", contacts.log.last())
        controller.stop(wipeDisk = false)
    }

    @Test
    fun theRosterSnapshotIsTheContactsControllers() = engineTest {
        val controller = controller()
        val source = MessagingChatsSource(controller, contacts)
        val roster = listOf(ContactItemDto(jane, "jane", Instant.parse("2026-09-01T10:00:00Z")))
        contacts.contacts.value = roster
        contacts.listState.value = ContactsListState(hasLoaded = true)
        contacts.presence.value = mapOf(jane to PresenceDto(jane, online = true))
        assertEquals(
            NewChatSnapshot(roster, ContactsListState(hasLoaded = true), mapOf(jane to PresenceDto(jane, online = true))),
            source.contactsSnapshot(),
        )
    }
}
