package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.net.UserCardDto
import de.corespace.shroud.core.realtime.RealtimeEvent
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * `ContactsController` against a scripted backend (iOS `MessagingController.swift:743-998,
 * 2197-2246, 4137-4198, 4348-4363`; contacts §4.3–4.7, §4.10, §9 *ContactsControllerTest*).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsControllerTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val me = UUID.fromString("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e")
    private val alice = contact("alice", UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val bob = contact("bob", UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val zed = contact("Zed_9", UUID.fromString("33333333-3333-3333-3333-333333333333"))

    private val backend = FakeContactsBackend()
    private val hooks = RecordingHooks()
    private val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
    private val clock = FakeAppClock()
    private var currentSession: Session? = session(me)
    private val stopped = mutableListOf<Boolean>()
    private var activations = 0

    private fun TestScope.controller(bind: Boolean = true) = ContactsController(
        backend = backend,
        session = { currentSession },
        events = events,
        scope = backgroundScope,
        clock = clock,
        main = main.dispatcher,
        usernameOrder = icuOrder(),
        listeners = {
            listOf(object : ContactsLifecycleListener {
                override fun onContactsActive() {
                    activations++
                }

                override fun onContactsStopped(wipe: Boolean) {
                    stopped += wipe
                }
            })
        },
    ).also { if (bind) it.bind(hooks) }

    // ---- Refresh (`:766-840`) ----

    @Test
    fun aRefreshPublishesTheRosterSortedAndSettlesTheLoad() = runTest(main.dispatcher) {
        backend.onContacts = { listOf(zed, bob, alice) }
        val request = pendingRequest(from = bob.userId, to = me, name = "bob")
        backend.onRequests = { listOf(request) }
        val contacts = controller()
        contacts.refresh()
        assertEquals(listOf("alice", "bob", "Zed_9"), contacts.contacts.value.map { it.username })
        assertEquals(listOf(request), contacts.incomingRequests.value)
        assertEquals(false, contacts.listState.value.isLoading)
        assertTrue(contacts.listState.value.hasLoaded)
        assertNull(contacts.listState.value.error)
        assertNull(hooks.error)
        assertEquals(false, hooks.offline)
        assertEquals(1, hooks.persisted)
        // An unchanged roster saves nothing (`:812-813`).
        contacts.refresh()
        assertEquals(1, hooks.persisted)
    }

    @Test
    fun theFirstLoadShowsLoadingAndLaterPollsDoNot() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<List<ContactItemDto>>()
        backend.onContacts = { gate.await() }
        val contacts = controller()
        val first = launch { contacts.refresh() }
        runCurrent()
        assertTrue(contacts.listState.value.isLoading)
        gate.complete(listOf(alice))
        first.join()
        assertFalse(contacts.listState.value.isLoading)

        val again = CompletableDeferred<List<ContactItemDto>>()
        backend.onContacts = { again.await() }
        val second = launch { contacts.refresh() }
        runCurrent()
        assertFalse(contacts.listState.value.isLoading)
        again.complete(listOf(alice))
        second.join()
    }

    @Test
    fun concurrentRefreshesShareOneRequestPair() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<List<ContactItemDto>>()
        backend.onContacts = { gate.await() }
        val contacts = controller()
        val first = launch { contacts.refresh() }
        val second = launch { contacts.refresh() }
        runCurrent()
        gate.complete(listOf(alice))
        first.join()
        second.join()
        assertEquals(1, backend.count("contacts"))
        assertEquals(1, backend.count("requests"))
        assertEquals(listOf(alice), contacts.contacts.value)
    }

    @Test
    fun forceWaitsForTheRefreshInFlightThenRunsItsOwn() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<List<ContactItemDto>>()
        backend.onContacts = { gate.await() }
        val contacts = controller()
        val first = launch { contacts.refresh() }
        runCurrent()
        val forced = launch { contacts.refresh(force = true) }
        runCurrent()
        assertEquals(1, backend.count("contacts"))
        backend.onContacts = { listOf(alice, bob) }
        gate.complete(listOf(alice))
        first.join()
        forced.join()
        assertEquals(2, backend.count("contacts"))
        assertEquals(listOf(alice, bob), contacts.contacts.value)
    }

    @Test
    fun aHalfFailedRefreshPublishesNothing() = runTest(main.dispatcher) {
        backend.onContacts = { listOf(alice) }
        backend.onRequests = { throw ApiError.Server(ErrorCodes.INTERNAL_ERROR, "Internal server error.", 500) }
        val contacts = controller()
        contacts.refresh()
        assertEquals(emptyList<ContactItemDto>(), contacts.contacts.value)
        assertEquals(0, hooks.persisted)
        assertEquals("Internal server error.", contacts.listState.value.error)
    }

    @Test
    fun aFailureWithNothingKnownSaysWhyAndStillSettlesTheFirstLoad() = runTest(main.dispatcher) {
        backend.onContacts = { throw ApiError.Transport("The Internet connection appears to be offline.") }
        val contacts = controller()
        contacts.refresh()
        val state = contacts.listState.value
        assertEquals("The Internet connection appears to be offline.", state.error)
        assertEquals("The Internet connection appears to be offline.", hooks.error)
        assertTrue(state.hasLoaded)
        assertFalse(state.isLoading)
        assertNull(hooks.offline)
    }

    @Test
    fun aFailureWithAKnownRosterKeepsItAndGoesOffline() = runTest(main.dispatcher) {
        val contacts = controller()
        contacts.hydrate(listOf(alice), emptyList())
        backend.onContacts = { throw ApiError.Transport("The request timed out.") }
        contacts.refresh()
        assertEquals(listOf(alice), contacts.contacts.value)
        assertNull(contacts.listState.value.error)
        assertEquals(true, hooks.offline)
        assertEquals("stale", hooks.error)
    }

    @Test
    fun withoutASessionTheFirstLoadIsSettledAtOnce() = runTest(main.dispatcher) {
        currentSession = null
        val contacts = controller()
        contacts.refresh()
        assertTrue(contacts.listState.value.hasLoaded)
        assertEquals(0, backend.count("contacts"))
    }

    @Test
    fun hydrationFillsOnlyEmptyListsAndCountsAsLoaded() = runTest(main.dispatcher) {
        val contacts = controller()
        val request = pendingRequest(from = bob.userId, to = me, name = "bob")
        contacts.hydrate(listOf(alice), listOf(request))
        assertEquals(listOf(alice), contacts.contacts.value)
        assertEquals(listOf(request), contacts.incomingRequests.value)
        assertTrue(contacts.listState.value.hasLoaded)
        contacts.hydrate(listOf(bob), emptyList())
        assertEquals(listOf(alice), contacts.contacts.value)
        assertEquals(0, hooks.persisted)
    }

    @Test
    fun aChangedRosterAnnouncesItselfForCallSecrets() = runTest(main.dispatcher) {
        val contacts = controller()
        var changes = 0
        backgroundScope.launch { contacts.rosterChanges.collect { changes++ } }
        runCurrent()
        backend.onContacts = { listOf(alice) }
        contacts.refresh()
        runCurrent()
        assertEquals(1, changes)
        contacts.refresh()
        runCurrent()
        assertEquals(1, changes)
        backend.onContacts = { listOf(alice, bob) }
        contacts.refresh()
        runCurrent()
        assertEquals(2, changes)
    }

    // ---- Presence (`:843-882`, `:4348-4363`) ----

    @Test
    fun presenceIsSweptOnARosterChangeAndThrottledTo30Seconds() = runTest(main.dispatcher) {
        backend.onContacts = { listOf(alice, bob) }
        backend.onPresence = { id ->
            if (id == bob.userId) throw ApiError.Server(ErrorCodes.FORBIDDEN, "Presence is only visible to accepted contacts.", 403)
            PresenceDto(id, online = true)
        }
        val contacts = controller()
        contacts.refresh()
        assertEquals(2, backend.count("presence"))
        // One publish, failures skipped per user.
        assertEquals(mapOf(alice.userId to PresenceDto(alice.userId, online = true)), contacts.presence.value)

        clock.advanceBy(29_000)
        contacts.refresh()
        assertEquals(2, backend.count("presence"))
        clock.advanceBy(1_000)
        contacts.refresh()
        assertEquals(4, backend.count("presence"))

        // A new contact forces a sweep at once.
        backend.onContacts = { listOf(alice, bob, zed) }
        contacts.refresh()
        assertEquals(7, backend.count("presence"))
    }

    @Test
    fun aPresenceEventReplacesTheEntryAndOfflineClearsActivity() = runTest(main.dispatcher) {
        val contacts = controller()
        contacts.start()
        runCurrent()
        val seen = Instant.parse("2026-09-30T09:41:00Z")
        events.emit(RealtimeEvent.PresenceUpdate(alice.userId, online = true, lastSeenAt = null))
        runCurrent()
        assertEquals(PresenceDto(alice.userId, online = true), contacts.presence.value[alice.userId])
        assertTrue(hooks.offlinePeers.isEmpty())
        events.emit(RealtimeEvent.PresenceUpdate(alice.userId, online = false, lastSeenAt = seen))
        runCurrent()
        assertEquals(PresenceDto(alice.userId, online = false, lastSeenAt = seen), contacts.presence.value[alice.userId])
        assertEquals(listOf(alice.userId), hooks.offlinePeers)
    }

    @Test
    fun hidingPresenceClearsItAndShowingItSweepsAgain() = runTest(main.dispatcher) {
        backend.onContacts = { listOf(alice) }
        backend.onPresence = { PresenceDto(it, online = true) }
        val contacts = controller()
        contacts.refresh()
        assertEquals(1, backend.count("presence"))
        contacts.onSharePresenceChanged(sharing = false)
        assertTrue(contacts.presence.value.isEmpty())
        runCurrent()
        assertEquals(1, backend.count("presence"))
        contacts.onSharePresenceChanged(sharing = true)
        runCurrent()
        assertEquals(2, backend.count("presence"))
        assertEquals(setOf(alice.userId), contacts.presence.value.keys)
    }

    // ---- Add (`:895-923`) ----

    @Test
    fun addingSendsARequestAndSaysWhatBecameOfIt() = runTest(main.dispatcher) {
        val card = UserCardDto(alice.userId, "alice", "QWERTY2345")
        backend.onByUsername = { card }
        backend.onCreateRequest = { pendingRequest(from = me, to = it, name = "me").copy(status = "pending") }
        val contacts = controller()
        assertEquals(AddContactOutcome.Requested("alice"), contacts.add("@Alice"))
        assertEquals(listOf("by-username alice", "create ${alice.userId}", "contacts", "requests"), backend.calls.take(4))

        // They had asked us already: the server accepts both at once (200).
        backend.onCreateRequest = { pendingRequest(from = it, to = me, name = "me").copy(status = "accepted") }
        assertEquals(AddContactOutcome.Added("alice"), contacts.add("alice"))
    }

    @Test
    fun addingByAShareCodeThatIsReallyAUsernameFallsBack() = runTest(main.dispatcher) {
        backend.onByCode = { throw notFound() }
        backend.onByUsername = { UserCardDto(bob.userId, "niklasvorberg") }
        backend.onCreateRequest = { pendingRequest(from = me, to = it, name = null) }
        val contacts = controller()
        assertEquals(AddContactOutcome.Requested("niklasvorberg"), contacts.add("NiklasVorberg"))
        assertEquals(listOf("by-code NIKLASVORBERG", "by-username niklasvorberg"), backend.calls.take(2))
    }

    @Test
    fun addingFailsWithTheIosTexts() = runTest(main.dispatcher) {
        val contacts = controller()
        assertEquals(AddContactOutcome.Failed("Enter a share code, username, link, or user ID."), contacts.add("!!!"))
        backend.onUser = { UserCardDto(me, "me") }
        assertEquals(AddContactOutcome.Failed("You can't add yourself."), contacts.add(me.toString().uppercase()))
        backend.onByUsername = { UserCardDto(alice.userId, "alice") }
        backend.onCreateRequest = { throw ApiError.Server(ErrorCodes.ALREADY_EXISTS, "A pending contact request already exists.", 409) }
        assertEquals(AddContactOutcome.Failed("A pending contact request already exists."), contacts.add("alice"))
        backend.onByUsername = { throw notFound() }
        assertEquals(AddContactOutcome.Failed("User not found."), contacts.add("nobody_here"))
        assertEquals(0, backend.count("contacts"))
        currentSession = null
        assertEquals(AddContactOutcome.Failed("Not signed in."), contacts.add("alice"))
    }

    // ---- Accept / reject (`:969-998`) ----

    @Test
    fun acceptAndRejectRefreshOnSuccessAndReportTheServersText() = runTest(main.dispatcher) {
        val request = pendingRequest(from = alice.userId, to = me, name = "alice")
        backend.onRequests = { listOf(request) }
        val contacts = controller()
        contacts.refresh()
        backend.onAccept = { request.copy(status = "accepted") }
        backend.onContacts = { listOf(alice) }
        backend.onRequests = { emptyList() }
        assertNull(contacts.accept(request))
        assertEquals(listOf(alice), contacts.contacts.value)
        assertTrue(contacts.incomingRequests.value.isEmpty())

        backend.onReject = { throw ApiError.Server(ErrorCodes.VALIDATION_ERROR, "Contact request is not pending.", 400) }
        assertEquals("Contact request is not pending.", contacts.reject(request))
        assertEquals("Contact request is not pending.", hooks.error)

        backend.onAccept = { throw ApiError.Server(ErrorCodes.FORBIDDEN, "Cannot accept a contact request while blocked.", 403) }
        assertEquals("Cannot accept a contact request while blocked.", contacts.accept(request))

        currentSession = null
        assertEquals("Not signed in.", contacts.accept(request))
        assertEquals("Not signed in.", contacts.reject(request))
    }

    // ---- Blocks (`:2197-2246`) ----

    @Test
    fun blockingRemovesTheContactAtOnceAndRefreshes() = runTest(main.dispatcher) {
        val contacts = controller()
        contacts.hydrate(listOf(alice, bob), emptyList())
        val blockedAlice = BlockItemDto(alice.userId, "alice", Instant.parse("2026-09-30T12:00:00Z"))
        var serverRoster = listOf(alice, bob)
        backend.onContacts = { serverRoster }
        backend.onBlock = { serverRoster = listOf(bob) }
        backend.onBlocks = { listOf(blockedAlice) }
        assertNull(contacts.block(alice.userId, "alice"))
        assertEquals(listOf(bob), contacts.contacts.value)
        assertEquals(listOf(blockedAlice), contacts.blocked.value)
        assertEquals(listOf(alice.userId), hooks.blockedPeers)
        assertNull(hooks.error)
        assertEquals(listOf("block ${alice.userId}", "blocks", "contacts", "requests"), backend.calls.take(4))
        assertTrue(hooks.persisted >= 1)
    }

    @Test
    fun blockAndUnblockReportFailuresAndNeedASession() = runTest(main.dispatcher) {
        val contacts = controller()
        backend.onBlock = { throw ApiError.Server(ErrorCodes.VALIDATION_ERROR, "Cannot block yourself.", 400) }
        assertEquals("Cannot block yourself.", contacts.block(me, "me"))
        assertEquals("Cannot block yourself.", hooks.error)
        assertTrue(hooks.blockedPeers.isEmpty())

        backend.onUnblock = { throw ApiError.Server(ErrorCodes.NOT_FOUND, "Block not found.", 404) }
        assertEquals("Block not found.", contacts.unblock(alice.userId))

        currentSession = null
        assertEquals("Sign in to block contacts.", contacts.block(alice.userId, "alice"))
        assertEquals("Sign in to manage blocked contacts.", contacts.unblock(alice.userId))
    }

    @Test
    fun unblockingDropsTheEntryAndDoesNotRestoreTheContact() = runTest(main.dispatcher) {
        val blockedAlice = BlockItemDto(alice.userId, "alice", Instant.parse("2026-09-30T12:00:00Z"))
        backend.onBlocks = { listOf(blockedAlice) }
        val contacts = controller()
        contacts.refreshBlocks()
        assertEquals(listOf(blockedAlice), contacts.blocked.value)
        backend.onBlocks = { throw ApiError.Transport("offline") }
        assertNull(contacts.unblock(alice.userId))
        assertTrue(contacts.blocked.value.isEmpty())
        assertEquals(0, backend.count("contacts"))
        // A failed refresh keeps the last list (silent).
        backend.onBlocks = { listOf(blockedAlice) }
        contacts.refreshBlocks()
        backend.onBlocks = { throw ApiError.Transport("offline") }
        contacts.refreshBlocks()
        assertEquals(listOf(blockedAlice), contacts.blocked.value)
    }

    // ---- Socket events (`:4137-4198`) ----

    @Test
    fun anIncomingRequestEventIsInsertedOnceAtTheTopAndAnnounced() = runTest(main.dispatcher) {
        val older = pendingRequest(from = bob.userId, to = me, name = "bob")
        val contacts = controller()
        contacts.hydrate(emptyList(), listOf(older))
        backend.onRequests = { contacts.incomingRequests.value }
        contacts.start()
        runCurrent()
        val fresh = pendingRequest(from = alice.userId, to = me, name = "alice")
        val event = RealtimeEvent.ContactChanged(RealtimeEvent.ContactChanged.Kind.Request, fresh, null, null)
        events.emit(event)
        runCurrent()
        assertEquals(listOf(fresh, older), contacts.incomingRequests.value)
        assertEquals(listOf(fresh), hooks.announced)
        events.emit(event)
        runCurrent()
        assertEquals(listOf(fresh, older), contacts.incomingRequests.value)
        assertEquals(1, hooks.announced.size)
        // The start's refresh and one per event.
        assertEquals(3, backend.count("contacts"))
    }

    @Test
    fun otherContactEventsOnlyRefresh() = runTest(main.dispatcher) {
        val contacts = controller()
        contacts.start()
        runCurrent()
        val before = backend.count("contacts")
        // Our own request on another device: not addressed to us.
        val outgoing = pendingRequest(from = me, to = alice.userId, name = "me")
        events.emit(RealtimeEvent.ContactChanged(RealtimeEvent.ContactChanged.Kind.Request, outgoing, null, null))
        runCurrent()
        assertTrue(contacts.incomingRequests.value.isEmpty())
        assertTrue(hooks.announced.isEmpty())
        assertEquals(before + 1, backend.count("contacts"))
        events.emit(RealtimeEvent.ContactChanged(RealtimeEvent.ContactChanged.Kind.Removed, null, alice.userId, me))
        runCurrent()
        assertEquals(before + 2, backend.count("contacts"))
        // A `contact.*` this build does not know refreshes too (`hasPrefix("contact.")`).
        events.emit(RealtimeEvent.Unknown("contact.renamed", JsonObject(emptyMap())))
        runCurrent()
        assertEquals(before + 3, backend.count("contacts"))
        events.emit(RealtimeEvent.Unknown("conversation.archived", JsonObject(emptyMap())))
        runCurrent()
        assertEquals(before + 3, backend.count("contacts"))
    }

    @Test
    fun socketEventsCountOnlyWhileActive() = runTest(main.dispatcher) {
        val contacts = controller()
        contacts.start()
        runCurrent()
        contacts.onBackground()
        val fresh = pendingRequest(from = alice.userId, to = me, name = "alice")
        events.emit(RealtimeEvent.ContactChanged(RealtimeEvent.ContactChanged.Kind.Request, fresh, null, null))
        events.emit(RealtimeEvent.PresenceUpdate(alice.userId, online = true, lastSeenAt = null))
        runCurrent()
        assertTrue(contacts.incomingRequests.value.isEmpty())
        assertTrue(contacts.presence.value.isEmpty())
        contacts.onForeground()
        runCurrent()
        events.emit(RealtimeEvent.PresenceUpdate(alice.userId, online = true, lastSeenAt = null))
        runCurrent()
        assertEquals(setOf(alice.userId), contacts.presence.value.keys)
    }

    // ---- Lifecycle (`:464-592, 598-690`) and the poll (`:742-757`) ----

    @Test
    fun thePollRefreshesEvery5SecondsOrEvery30WhileTheSocketIsUp() = runTest(main.dispatcher) {
        val contacts = controller()
        contacts.start()
        runCurrent()
        assertEquals(1, backend.count("contacts"))
        advanceTimeBy(5_001)
        assertEquals(2, backend.count("contacts"))
        advanceTimeBy(5_000)
        assertEquals(3, backend.count("contacts"))
        hooks.isRealtimeConnected.value = true
        // Ticks 3, 4, 5 skip; tick 6 (30 s after start) refreshes.
        advanceTimeBy(15_000)
        assertEquals(3, backend.count("contacts"))
        advanceTimeBy(5_000)
        assertEquals(4, backend.count("contacts"))
        contacts.onBackground()
        hooks.isRealtimeConnected.value = false
        advanceTimeBy(60_000)
        assertEquals(4, backend.count("contacts"))
        contacts.onForeground()
        runCurrent()
        assertEquals(5, backend.count("contacts"))
        advanceTimeBy(5_001)
        assertEquals(6, backend.count("contacts"))
        assertEquals(2, activations)
        contacts.stop(wipe = false)
    }

    @Test
    fun startWithoutASessionDoesNothing() = runTest(main.dispatcher) {
        currentSession = null
        val contacts = controller()
        contacts.start()
        contacts.onForeground()
        contacts.onConnectivityRegained()
        advanceTimeBy(20_000)
        assertEquals(0, backend.count("contacts"))
        assertEquals(0, activations)
    }

    @Test
    fun regainedConnectivityForcesARefresh() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<List<ContactItemDto>>()
        backend.onContacts = { gate.await() }
        val contacts = controller()
        val first = launch { contacts.refresh() }
        runCurrent()
        contacts.onConnectivityRegained()
        runCurrent()
        backend.onContacts = { listOf(alice) }
        gate.complete(emptyList())
        first.join()
        // The forced refresh runs in the (background) app scope: runCurrent, not advanceUntilIdle.
        runCurrent()
        assertEquals(2, backend.count("contacts"))
        assertEquals(listOf(alice), contacts.contacts.value)
    }

    @Test
    fun stoppingClearsEverythingAndDropsTheRefreshInFlight() = runTest(main.dispatcher) {
        backend.onContacts = { listOf(alice) }
        backend.onPresence = { PresenceDto(it, online = true) }
        backend.onBlocks = { listOf(BlockItemDto(bob.userId, "bob", Instant.parse("2026-09-30T12:00:00Z"))) }
        val contacts = controller()
        contacts.start()
        runCurrent()
        contacts.refreshBlocks()
        contacts.pendingInvite.value = "ABCD234567"
        assertEquals(listOf(alice), contacts.contacts.value)

        val gate = CompletableDeferred<List<ContactItemDto>>()
        backend.onContacts = { gate.await() }
        val late = launch { contacts.refresh(force = true) }
        runCurrent()
        contacts.stop(wipe = false)
        assertTrue(contacts.contacts.value.isEmpty())
        assertTrue(contacts.presence.value.isEmpty())
        assertTrue(contacts.blocked.value.isEmpty())
        assertFalse(contacts.listState.value.hasLoaded)
        // A lock keeps the App Link invite until the unlock.
        assertEquals("ABCD234567", contacts.pendingInvite.value)
        assertEquals(listOf(false), stopped)

        // The answer that was in flight lands after the stop and publishes nothing; its caller returns.
        gate.complete(listOf(alice, bob))
        late.join()
        assertTrue(contacts.contacts.value.isEmpty())
        assertFalse(contacts.listState.value.hasLoaded)

        // No poll and no socket events after a stop.
        val before = backend.count("contacts")
        advanceTimeBy(60_000)
        events.emit(RealtimeEvent.PresenceUpdate(alice.userId, online = true, lastSeenAt = null))
        runCurrent()
        assertEquals(before, backend.count("contacts"))
        assertTrue(contacts.presence.value.isEmpty())

        // Nothing refills the lists behind the lock: not a screen's refresh, not a forced one, not blocks.
        backend.onContacts = { listOf(alice) }
        contacts.refresh()
        contacts.refresh(force = true)
        contacts.refreshBlocks()
        contacts.refreshPresence(listOf(alice.userId))
        assertEquals(before, backend.count("contacts"))
        assertTrue(contacts.blocked.value.isEmpty())
        assertEquals(1, backend.count("blocks"))

        contacts.stop(wipe = true)
        assertNull(contacts.pendingInvite.value)
        assertEquals(listOf(false, true), stopped)

        // Only a start resumes; a stray foreground does not.
        contacts.onForeground()
        runCurrent()
        assertEquals(before, backend.count("contacts"))
        // The next unlock starts over.
        contacts.start()
        runCurrent()
        assertEquals(listOf(alice), contacts.contacts.value)
        contacts.stop(wipe = false)
    }

    @Test
    fun usernamesComeFromContactsRequestsAndBlocks() = runTest(main.dispatcher) {
        val carol = UUID.randomUUID()
        val dave = UUID.randomUUID()
        backend.onBlocks = { listOf(BlockItemDto(dave, "dave", Instant.parse("2026-09-30T12:00:00Z"))) }
        val contacts = controller()
        contacts.hydrate(listOf(alice), listOf(pendingRequest(from = carol, to = me, name = "carol")))
        contacts.refreshBlocks()
        assertEquals("alice", contacts.username(alice.userId))
        assertEquals("carol", contacts.username(carol))
        assertEquals("dave", contacts.username(dave))
        assertNull(contacts.username(UUID.randomUUID()))
    }

    @Test
    fun aRequestWithoutAUserCardIsStillListed() = runTest(main.dispatcher) {
        val bare: ContactRequestDto = pendingRequest(from = alice.userId, to = me, name = null)
        backend.onRequests = { listOf(bare) }
        val contacts = controller(bind = false)
        contacts.refresh()
        assertEquals(listOf(bare), contacts.incomingRequests.value)
        assertNull(contacts.username(alice.userId))
    }
}
