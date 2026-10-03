package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ContactRequestStatus
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * Work that follows the contacts engine's lifecycle: the privacy settings refresh with it and reset
 * when it stops, the peer-identity memory is dropped with it (iOS keeps all of it in
 * `MessagingController`, whose `start`/`handleAppBecameActive`/`stopActivity`/`clearLocalData`
 * drive them together, `MessagingController.swift:464-564, 598-626`). Wired by `ContactsModule`.
 */
interface ContactsLifecycleListener {
    /** [Contacts.start] or [Contacts.onForeground] ran with a session. */
    fun onContactsActive() {}

    /** [Contacts.stop]: chats locked ([wipe] false) or Log Out / removal ([wipe] true). */
    fun onContactsStopped(wipe: Boolean) {}
}

/**
 * The roster, pending incoming requests, presence and blocks (iOS `MessagingController`, contacts
 * slices: `:19-35, 743-998, 2197-2246, 4137-4198, 4348-4363, 4656-4664`; contacts §4.3–4.7,
 * §4.10, messaging-core §18, §20.1, §20.3; plan C6).
 *
 * State model = iOS `@MainActor @Observable` (plan §1.1 rule 3): every member runs on [main]; the
 * suspend members switch there themselves, the others must be called on the main thread. Every
 * publish is equality-guarded (`StateFlow` drops equal values; iOS guards by hand so an unchanged
 * poll never redraws, `:785-787`). Work started before [stop] publishes nothing afterwards (a
 * generation guard; iOS lets such a late answer land).
 *
 * Driven by `MessagingController` through the seam: [bind], [hydrate] from the sealed roster,
 * [start], [onForeground], [onBackground] (only when the socket is let go), [onConnectivityRegained],
 * [stop]. It also drives the [ContactsLifecycleListener]s (privacy settings, peer identities), so
 * messaging need not call those separately. Collects the socket's `contact.*` and `presence.update`
 * events itself (plan C4) while active.
 *
 * @param session the signed-in session (token and own user id), null when signed out.
 * @param events `RealtimeClient.events`.
 * @param scope the app scope; refreshes, polls and event handling run in it.
 * @param clock monotonic time for the 30 s presence-sweep throttle (iOS uses the wall clock, `:843-853`).
 * @param usernameOrder iOS `localizedCaseInsensitiveCompare` ([ContactsSorting.collator]).
 * @param listeners privacy and peer-identity lifecycles (a provider: they are built after this).
 */
class ContactsController(
    private val backend: ContactsBackend,
    private val session: () -> Session?,
    private val events: Flow<RealtimeEvent>,
    private val scope: CoroutineScope,
    private val clock: AppClock,
    private val main: CoroutineContext = Dispatchers.Main.immediate,
    private val usernameOrder: Comparator<String> = ContactsSorting.collator(),
    private val listeners: () -> List<ContactsLifecycleListener> = { emptyList() },
    private val names: ContactNameExchange = ContactNameExchange.KEEP_LOCAL,
) : Contacts {
    private val contactsState = MutableStateFlow<List<ContactItemDto>>(emptyList())
    override val contacts: StateFlow<List<ContactItemDto>> = contactsState.asStateFlow()

    private val requestsState = MutableStateFlow<List<ContactRequestDto>>(emptyList())
    override val incomingRequests: StateFlow<List<ContactRequestDto>> = requestsState.asStateFlow()

    private val listStateFlow = MutableStateFlow(ContactsListState())
    override val listState: StateFlow<ContactsListState> = listStateFlow.asStateFlow()

    private val presenceState = MutableStateFlow<Map<UUID, PresenceDto>>(emptyMap())
    override val presence: StateFlow<Map<UUID, PresenceDto>> = presenceState.asStateFlow()

    private val blockedState = MutableStateFlow<List<BlockItemDto>>(emptyList())
    override val blocked: StateFlow<List<BlockItemDto>> = blockedState.asStateFlow()

    private val rosterChangeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val rosterChanges: SharedFlow<Unit> = rosterChangeEvents.asSharedFlow()

    override val pendingInvite: MutableStateFlow<String?> = MutableStateFlow(null)

    private var hooks: ContactsHooks? = null

    /** The one refresh in flight (iOS `contactsRefreshTask`); overlapping callers share it. */
    private var inFlight: Deferred<Unit>? = null
    private var pollJob: Job? = null
    private var eventsJob: Job? = null

    /** iOS `realtimeActive`: socket events count only between start/foreground and background/stop. */
    private var active = false

    /** Bumped by [stop]: what started before publishes nothing. */
    private var generation = 0L

    /**
     * Between [stop] and the next [start]: refreshes (a queued event's, a forced waiter's, a
     * screen's) do nothing, so locked chats are not refilled behind the lock screen.
     */
    private var stopped = false

    /** Monotonic millis of the last presence sweep (iOS `lastPresenceSweep`). */
    private var lastPresenceSweepAt: Long? = null

    // ---- Lifecycle (`MessagingController.swift:464-592, 598-690`) ----

    override fun bind(hooks: ContactsHooks) {
        this.hooks = hooks
    }

    /** Messaging's `lastError`, for the privacy controller (iOS writes the one `lastError`, `:2118-2125`). */
    fun reportLastError(message: String?) {
        hooks?.setLastError(message)
    }

    /**
     * The sealed roster's contacts and requests (`hydrateFromDisk`, `:4656-4664`): adopted only into
     * empty lists; cached contacts count as loaded, so no skeleton covers them.
     */
    override fun hydrate(contacts: List<ContactItemDto>, requests: List<ContactRequestDto>) {
        if (contactsState.value.isEmpty() && contacts.isNotEmpty()) {
            contactsState.value = contacts
            listStateFlow.update { it.copy(hasLoaded = true) }
        }
        if (requestsState.value.isEmpty() && requests.isNotEmpty()) requestsState.value = requests
    }

    /** Messaging unlocked (`start`, `:464-487`): socket events, the poll, a refresh, the privacy settings. */
    override fun start() {
        if (token() == null) return
        stopped = false
        activate()
        startPolling()
        scope.launch(main) { refresh() }
        listeners().forEach { it.onContactsActive() }
    }

    /**
     * Back in front (`handleAppBecameActive`, `:598-626`): the poll again if it stopped, a refresh.
     * After [stop] only [start] resumes (the chats were locked or signed out meanwhile).
     */
    override fun onForeground() {
        if (token() == null || stopped) return
        activate()
        if (pollJob == null) startPolling()
        scope.launch(main) { refresh() }
        listeners().forEach { it.onContactsActive() }
    }

    /** The socket was let go (`leaveForeground(keepSocket: false)`, `:633-656`): no poll, no events. */
    override fun onBackground() {
        pollJob?.cancel()
        pollJob = null
        active = false
    }

    /** The network came back (`handleConnectivityChanged`, `:677-690`). */
    override fun onConnectivityRegained() {
        if (token() == null) return
        scope.launch(main) { refresh(force = true) }
    }

    /**
     * Chats locked or signed out (`stopActivity` + `clearInMemoryState`, `:520-592`): the poll and
     * socket events stop, the in-flight refresh is dropped (not cancelled — its waiters still return),
     * every list, presence, block and load state is cleared. [wipe] (Log Out, removal) also forgets
     * an App Link invite that waited for the unlock. The listeners reset the privacy settings and
     * the peer-identity memory ([wipe]: also every pin, `clearLocalData`, `:558-564`).
     */
    override fun stop(wipe: Boolean) {
        pollJob?.cancel()
        pollJob = null
        eventsJob?.cancel()
        eventsJob = null
        active = false
        stopped = true
        generation++
        inFlight = null
        contactsState.value = emptyList()
        requestsState.value = emptyList()
        presenceState.value = emptyMap()
        blockedState.value = emptyList()
        listStateFlow.value = ContactsListState()
        lastPresenceSweepAt = null
        if (wipe) pendingInvite.value = null
        listeners().forEach { it.onContactsStopped(wipe) }
    }

    private fun activate() {
        active = true
        if (eventsJob == null) eventsJob = scope.launch(main) { events.collect(::handle) }
    }

    /**
     * Invites must appear even when the socket is blocked (`startContactsPolling`, `:742-757`): a
     * tick every 5 s; while the socket is connected only every 6th tick (30 s) refreshes, since
     * `contact.*` events carry the changes then.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(main) {
            var tick = 0
            while (true) {
                delay(POLL_INTERVAL_MS)
                tick++
                if (hooks?.isRealtimeConnected?.value == true && tick % POLLS_PER_REFRESH_WHILE_CONNECTED != 0) continue
                refresh()
            }
        }
    }

    // ---- Roster (`refreshContacts`, `performContactsRefresh`, `:766-840`) ----

    /**
     * Reloads contacts and pending incoming requests. Overlapping callers (poll, socket, screen)
     * share one fetch; [force] waits that one out and then runs a fresh one, so the caller sees its
     * own write (`:766-784`). Never throws: a failure lands in [listState] (`:788-840`). Does
     * nothing after [stop] until the next start.
     */
    override suspend fun refresh(force: Boolean): Unit = withContext(main) {
        if (stopped) return@withContext
        inFlight?.let { existing ->
            existing.join()
            if (!force || stopped) return@withContext
        }
        val job = scope.async(main) { performRefresh() }
        inFlight = job
        try {
            job.join()
        } finally {
            if (inFlight === job) inFlight = null
        }
    }

    private suspend fun performRefresh() {
        val gen = generation
        val bearer = token()
        if (bearer == null) {
            // Nothing in flight to settle the first load later: settle it now (`:789-795`).
            listStateFlow.update { it.copy(hasLoaded = true) }
            return
        }
        // Loading chrome belongs to the first load only; polls refresh in place (`:796-801`).
        val showsLoading = !listStateFlow.value.hasLoaded
        if (showsLoading) listStateFlow.update { it.copy(isLoading = true) }
        try {
            // Both, concurrently, before anything is published: a half-failed refresh never lands.
            val (list, requests) = coroutineScope {
                val contactsCall = async { backend.contacts(bearer) }
                val requestsCall = async { backend.incomingRequests(bearer) }
                contactsCall.await() to requestsCall.await()
            }
            if (gen != generation) return
            val named = names.apply(bearer, list, contactsState.value)
            val sorted = named.sortedWith(compareBy(usernameOrder) { it.username })
            val redacted = requests.map { request ->
                request.copy(user = request.user?.copy(username = CONTACT_PLACEHOLDER))
            }
            val rosterChanged = contactsState.value.map { it.userId } != sorted.map { it.userId }
            val changed = contactsState.value != sorted || requestsState.value != redacted
            contactsState.value = sorted
            requestsState.value = redacted
            // Rows are publishable now; presence does not hold the skeleton up.
            listStateFlow.update { it.copy(error = null, hasLoaded = true) }
            hooks?.setLastError(null)
            hooks?.setOffline(false)
            // Every poll lands here; an unchanged roster has nothing to save.
            if (changed) hooks?.persistRoster()
            // A new contact needs presence at once; otherwise the slow sweep.
            sweepPresenceIfNeeded(bearer, force = rosterChanged, gen = gen)
            // Their call secret has to exist before a locked phone can answer them (plan C29).
            if (rosterChanged && gen == generation) rosterChangeEvents.tryEmit(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen != generation) return
            if (contactsState.value.isNotEmpty() || requestsState.value.isNotEmpty()) {
                // Keep showing the last good roster when the network is gone.
                listStateFlow.update { it.copy(error = null) }
                hooks?.setOffline(true)
            } else {
                val message = SessionController.userMessage(e)
                listStateFlow.update { it.copy(error = message) }
                hooks?.setLastError(message)
            }
        } finally {
            if (gen == generation) {
                listStateFlow.update { it.copy(isLoading = if (showsLoading) false else it.isLoading, hasLoaded = true) }
            }
        }
    }

    // ---- Presence (`:843-882`, `:2172-2179`, `:4348-4363`) ----

    /** At most every 30 s unless [force] (`sweepPresenceIfNeeded`, `:843-853`). */
    private suspend fun sweepPresenceIfNeeded(bearer: String, force: Boolean, gen: Long) {
        val now = clock.elapsedMillis()
        val last = lastPresenceSweepAt
        if (!force && last != null && now - last < PRESENCE_SWEEP_INTERVAL_MS) return
        lastPresenceSweepAt = now
        fetchPresence(contactsState.value.map { it.userId }, bearer, gen)
    }

    /** Presence of [userIds], all at once; failures (a 403 for a non-contact) are skipped per user. */
    override suspend fun refreshPresence(userIds: Collection<UUID>): Unit = withContext(main) {
        if (stopped) return@withContext
        val bearer = token() ?: return@withContext
        fetchPresence(userIds.distinct(), bearer, generation)
    }

    /** One publish for the whole sweep — per-user writes made the list strobe (`:855-882`). */
    private suspend fun fetchPresence(userIds: List<UUID>, bearer: String, gen: Long) {
        if (userIds.isEmpty()) return
        val updates = coroutineScope {
            userIds.map { id ->
                async {
                    try {
                        id to backend.presence(bearer, id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull()
        if (gen != generation || updates.isEmpty()) return
        presenceState.value = presenceState.value + updates
    }

    /**
     * The account's own `share_presence` changed (`adoptPrivacySettings`, `:2172-2179`): everyone
     * reads as offline and never seen while hidden; shown again, presence is fetched afresh.
     * Called by [PrivacyController].
     */
    fun onSharePresenceChanged(sharing: Boolean) {
        presenceState.value = emptyMap()
        lastPresenceSweepAt = null
        if (!sharing || stopped) return
        val bearer = token() ?: return
        val gen = generation
        scope.launch(main) { sweepPresenceIfNeeded(bearer, force = true, gen = gen) }
    }

    // ---- Add, accept, reject (`:885-998`) ----

    /**
     * Resolves a share code, username, user id or link and sends a contact request
     * (`addContact(fromInvite:)`, `:895-923`): `Requested` while it waits for them, `Added` when
     * they had asked us already (mutual accept), else `Failed` with the text to show.
     */
    override suspend fun add(invite: String): AddContactOutcome = withContext(main) {
        val bearer = token() ?: return@withContext AddContactOutcome.Failed(NOT_SIGNED_IN)
        val parsed = ContactInviteParser.parse(invite) ?: return@withContext AddContactOutcome.Failed(UNREADABLE_INVITE)
        try {
            val card = InviteLookup.card(backend, bearer, parsed)
            if (card.id == myUserId()) return@withContext AddContactOutcome.Failed(CANNOT_ADD_YOURSELF)
            // Never read `user` from this answer: on a mutual accept it is our own card (contacts §2.2).
            val request = backend.createContactRequest(bearer, card.id)
            refresh(force = true)
            if (request.status == ContactRequestStatus.ACCEPTED) AddContactOutcome.Added(card.username) else AddContactOutcome.Requested(card.username)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AddContactOutcome.Failed(SessionController.userMessage(e))
        }
    }

    /** Null once the contact is in the roster (`acceptRequest`, `:969-983`). No optimistic removal. */
    override suspend fun accept(request: ContactRequestDto): String? =
        respond(request) { bearer, id -> backend.acceptContactRequest(bearer, id) }

    /** Null once the request is gone (`rejectRequest`, `:984-998`). */
    override suspend fun reject(request: ContactRequestDto): String? =
        respond(request) { bearer, id -> backend.rejectContactRequest(bearer, id) }

    private suspend fun respond(request: ContactRequestDto, call: suspend (String, UUID) -> Unit): String? = withContext(main) {
        val bearer = token() ?: return@withContext NOT_SIGNED_IN
        try {
            call(bearer, request.id)
            refresh(force = true)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionController.userMessage(e).also { hooks?.setLastError(it) }
        }
    }

    // ---- Blocks (`:2197-2246`) ----

    /** Silent on failure: the last list stands (`refreshBlocks`, `:2199-2203`). */
    override suspend fun refreshBlocks(): Unit = withContext(main) {
        if (stopped) return@withContext
        val bearer = token() ?: return@withContext
        val gen = generation
        val list = try {
            backend.blocks(bearer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext
        }
        if (gen == generation) blockedState.value = list
    }

    /**
     * Blocks a user (`blockUser`, `:2205-2229`): the server also drops the contact both ways and
     * cancels pending requests. The contact leaves the roster at once (and the sealed roster with it);
     * the chat stays — blocking is not deleting.
     */
    override suspend fun block(userId: UUID, username: String): String? = withContext(main) {
        val bearer = token() ?: return@withContext "Sign in to block contacts."
        try {
            backend.block(bearer, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext SessionController.userMessage(e).also { hooks?.setLastError(it) }
        }
        val remaining = contactsState.value.filterNot { it.userId == userId }
        if (remaining != contactsState.value) {
            contactsState.value = remaining
            hooks?.persistRoster()
        }
        hooks?.setLastError(null)
        refreshBlocks()
        refresh(force = true)
        hooks?.onBlocked(userId)
        null
    }

    /** Lifts a block (`unblockUser`, `:2231-2246`). The contact is **not** restored. */
    override suspend fun unblock(userId: UUID): String? = withContext(main) {
        val bearer = token() ?: return@withContext "Sign in to manage blocked contacts."
        try {
            backend.unblock(bearer, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext SessionController.userMessage(e).also { hooks?.setLastError(it) }
        }
        blockedState.update { list -> list.filterNot { it.userId == userId } }
        hooks?.setLastError(null)
        refreshBlocks()
        null
    }

    // ---- Lookups ----

    /** A known name for [of]: a contact, a pending requester or a blocked user; null otherwise. */
    override fun username(of: UUID): String? =
        contactsState.value.firstOrNull { it.userId == of }?.username
            ?: requestsState.value.firstOrNull { it.fromUserId == of }?.user?.username
            ?: blockedState.value.firstOrNull { it.userId == of }?.username

    // ---- Socket events (`handleRealtime`, `:4137-4175`; `handleContactRealtime`, `:4177-4198`; `handlePresence`, `:4348-4363`) ----

    private fun handle(event: RealtimeEvent) {
        if (!active) return
        when (event) {
            is RealtimeEvent.ContactChanged -> onContactEvent(event)
            is RealtimeEvent.PresenceUpdate -> onPresenceEvent(event)
            // Any `contact.*` refreshes, also kinds this build does not know.
            is RealtimeEvent.Unknown -> if (event.type.startsWith("contact.")) scope.launch(main) { refresh() }
            else -> Unit
        }
    }

    /**
     * A pending request to us is shown before the round trip: inserted at the top once, and
     * announced (contact requests ignore mutes). Every `contact.*` then refreshes.
     */
    private fun onContactEvent(event: RealtimeEvent.ContactChanged) {
        val request = event.request
        val me = myUserId()
        val shown = request?.copy(user = request.user?.copy(username = CONTACT_PLACEHOLDER))
        if (event.kind == RealtimeEvent.ContactChanged.Kind.Request && shown != null && me != null &&
            shown.toUserId == me && shown.status == ContactRequestStatus.PENDING &&
            requestsState.value.none { it.id == shown.id }
        ) {
            requestsState.value = listOf(shown) + requestsState.value
            hooks?.announceContactRequest(shown)
        }
        scope.launch(main) { refresh() }
    }

    /** Replaces that user's presence; offline also clears their typing and recording. */
    private fun onPresenceEvent(event: RealtimeEvent.PresenceUpdate) {
        val presence = PresenceDto(userId = event.userId, online = event.online, lastSeenAt = event.lastSeenAt)
        if (presenceState.value[event.userId] != presence) presenceState.value = presenceState.value + (event.userId to presence)
        if (!event.online) hooks?.onPeerOffline(event.userId)
    }

    private fun token(): String? = session()?.token
    private fun myUserId(): UUID? = Ids.parse(session()?.userId)

    companion object {
        const val POLL_INTERVAL_MS = 5_000L
        const val POLLS_PER_REFRESH_WHILE_CONNECTED = 6
        const val PRESENCE_SWEEP_INTERVAL_MS = 30_000L

        const val NOT_SIGNED_IN = "Not signed in."
        const val UNREADABLE_INVITE = "Enter a share code, link, or user ID."
        const val CANNOT_ADD_YOURSELF = "You can't add yourself."
    }
}
