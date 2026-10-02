package de.corespace.shroud.ui.contacts

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.ContactsHooks
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.model.PeerIdentityChange
import de.corespace.shroud.core.model.PeerIdentityEvent
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ContactRequestStatus
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.UserCardDto
import de.corespace.shroud.ui.shell.ChatRoute
import de.corespace.shroud.ui.shell.MainTab
import de.corespace.shroud.ui.shell.SettingsRoute
import de.corespace.shroud.ui.shell.ShellNavigation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.UUID

/**
 * The contacts engine as the screens see it, scripted: every call is logged in order ([log]) and
 * answers what the test set. Lists are plain [MutableStateFlow]s the test writes.
 */
class FakeContacts(private val log: MutableList<String>) : Contacts {
    override val contacts = MutableStateFlow<List<ContactItemDto>>(emptyList())
    override val incomingRequests = MutableStateFlow<List<ContactRequestDto>>(emptyList())
    override val listState = MutableStateFlow(ContactsListState())
    override val presence = MutableStateFlow<Map<UUID, PresenceDto>>(emptyMap())
    override val blocked = MutableStateFlow<List<BlockItemDto>>(emptyList())
    override val rosterChanges = MutableSharedFlow<Unit>()
    override val pendingInvite = MutableStateFlow<String?>(null)

    /** What [add] answers. */
    var addOutcome: (String) -> AddContactOutcome = { AddContactOutcome.Requested("jane") }

    /** Holds [add] until completed, when set. */
    var addGate: CompletableDeferred<Unit>? = null

    /** What [accept] / [reject] answer (null = done). */
    var answerError: String? = null

    /** Holds [accept] / [reject] until completed, when set. */
    var answerGate: CompletableDeferred<Unit>? = null

    /** What [block] / [unblock] answer (null = done). */
    var blockError: String? = null

    override fun username(of: UUID): String? = contacts.value.firstOrNull { it.userId == of }?.username

    override suspend fun refresh(force: Boolean) {
        log += "refresh(force=$force)"
    }

    override suspend fun refreshPresence(userIds: Collection<UUID>) {
        log += "refreshPresence(${userIds.joinToString()})"
    }

    override suspend fun add(invite: String): AddContactOutcome {
        log += "add($invite)"
        addGate?.await()
        return addOutcome(invite)
    }

    override suspend fun accept(request: ContactRequestDto): String? {
        log += "accept(${request.id})"
        answerGate?.await()
        return answerError
    }

    override suspend fun reject(request: ContactRequestDto): String? {
        log += "reject(${request.id})"
        answerGate?.await()
        return answerError
    }

    override suspend fun refreshBlocks() {
        log += "refreshBlocks"
    }

    override suspend fun block(userId: UUID, username: String): String? {
        log += "block($userId)"
        if (blockError == null) blocked.value = blocked.value + BlockItemDto(userId, username, Instant.EPOCH)
        return blockError
    }

    override suspend fun unblock(userId: UUID): String? {
        log += "unblock($userId)"
        if (blockError == null) blocked.value = blocked.value.filterNot { it.userId == userId }
        return blockError
    }

    override fun bind(hooks: ContactsHooks) = Unit
    override fun hydrate(contacts: List<ContactItemDto>, requests: List<ContactRequestDto>) = Unit
    override fun start() = Unit
    override fun onForeground() = Unit
    override fun onBackground() = Unit
    override fun onConnectivityRegained() = Unit
    override fun stop(wipe: Boolean) = Unit
}

/** Key pins as the profile reads them: [numbers] per peer, a change and verification the test sets. */
class FakePeerIdentities(private val log: MutableList<String>) : PeerIdentities {
    override val identityChanges = MutableStateFlow<Map<UUID, PeerIdentityChange>>(emptyMap())
    override val verifiedPeers = MutableStateFlow<Set<UUID>>(emptySet())
    override val events = MutableSharedFlow<PeerIdentityEvent>()
    val numbers = HashMap<UUID, String>()

    override suspend fun resolvePublicKey(peer: UUID): ByteArray = error("not used by the screens")
    override suspend fun publicKeyForSending(peer: UUID): ByteArray = error("not used by the screens")

    override suspend fun refresh(peer: UUID) {
        log += "refreshIdentity($peer)"
    }

    override fun identityChange(peer: UUID): PeerIdentityChange? = identityChanges.value[peer]
    override fun isSafetyVerified(peer: UUID): Boolean = peer in verifiedPeers.value

    override fun confirmSafety(peer: UUID) {
        log += "confirmSafety($peer)"
        verifiedPeers.value = verifiedPeers.value + peer
    }

    override fun safetyNumber(peer: UUID): String? = numbers[peer]

    override fun acceptNewIdentity(peer: UUID) {
        log += "acceptNewIdentity($peer)"
        identityChanges.value = identityChanges.value - peer
        verifiedPeers.value = verifiedPeers.value - peer
    }

    override fun clearMemory() = Unit
    override fun wipe() = Unit

    /** A pending change for [peer]. */
    fun change(peer: UUID) {
        identityChanges.value = identityChanges.value + (peer to PeerIdentityChange(Bytes.of(ByteArray(32) { 1 }), Bytes.of(ByteArray(32) { 2 })))
    }
}

/** [ContactsPorts] on [FakeContacts] and [FakePeerIdentities], with the messaging, calls and notification parts scripted. */
class FakeContactsPorts(
    override val actionScope: CoroutineScope,
    override val clock: AppClock,
) : ContactsPorts {
    /** Every engine call, in order. */
    val log = ArrayList<String>()
    override val contacts = FakeContacts(log)
    override val identities = FakePeerIdentities(log)
    override val session = MutableStateFlow<Session?>(Session("token", ContactsFixtures.ME.toString(), "noah", "ABCD234567", "device"))
    override val server = MutableStateFlow(ServerConfiguration.official)
    override val appPhase = MutableStateFlow(AppPhase.Active)
    override val peerActivities = MutableStateFlow<Map<UUID, ChatPeerActivity>>(emptyMap())
    override val conversations = MutableStateFlow<List<ConversationItemDto>>(emptyList())

    val mutes = HashMap<UUID, ChatMuteDto>()
    var canMuteChats = true
    var muteError: String? = null
    var deleteOutcome: ChatDeleteOutcome = ChatDeleteOutcome.ClearedForMe
    var callFailure: String? = null
    var notificationClears = 0
        private set

    override suspend fun validateSession() {
        log += "validateSession"
    }

    override fun mute(peer: UUID): ChatMuteDto? = mutes[peer]?.takeIf { it.isActive(clock.now()) }
    override fun isMuted(peer: UUID): Boolean = mute(peer) != null
    override fun canMute(peer: UUID): Boolean = canMuteChats

    override suspend fun muteChat(peer: UUID, duration: MuteDuration): String? {
        log += "muteChat($peer, $duration)"
        if (muteError == null) mutes[peer] = ChatMuteDto(duration.seconds?.let { clock.now().plusSeconds(it) })
        return muteError
    }

    override suspend fun unmuteChat(peer: UUID): String? {
        log += "unmuteChat($peer)"
        if (muteError == null) mutes.remove(peer)
        return muteError
    }

    override suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome {
        log += "deleteConversation($peer, $scope)"
        return deleteOutcome
    }

    override val callError = MutableStateFlow<String?>(null)

    override suspend fun startCall(peer: UUID, username: String, modality: CallModality) {
        log += "startCall($peer, $username, $modality)"
        callError.value = callFailure
    }

    override fun clearContactRequestNotifications() {
        notificationClears++
    }

    /** The calls made so far that start with [prefix]. */
    fun calls(prefix: String): List<String> = log.filter { it.startsWith(prefix) }
}

/** The shell's navigation with a tab the test selects, recording pushes. */
class ContactsTestNavigation(tab: MainTab = MainTab.Contacts) : ShellNavigation {
    val log = ArrayList<String>()
    override val selection = MutableStateFlow(tab)
    override fun select(tab: MainTab) {
        selection.value = tab
    }

    override fun push(route: ChatRoute) {
        log += "push:$route"
    }

    override fun push(route: SettingsRoute) {
        log += "push:$route"
    }

    override fun pop(): Boolean {
        log += "pop"
        return true
    }

    override fun popToRoot() = Unit
    override fun openChat(peerId: UUID, username: String) {
        log += "openChat:$peerId"
    }
}

/** Ids and DTOs of the contacts tests (the design's names, CumKS). */
object ContactsFixtures {
    val ME: UUID = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    val JANE: UUID = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    val ZOE: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")

    fun contact(name: String, id: UUID = UUID.nameUUIDFromBytes(name.toByteArray())) = ContactItemDto(id, name, Instant.parse("2026-09-01T10:00:00Z"))

    fun request(from: UUID, name: String?, id: UUID = UUID.nameUUIDFromBytes("request:$from".toByteArray())) = ContactRequestDto(
        id = id,
        fromUserId = from,
        toUserId = ME,
        status = ContactRequestStatus.PENDING,
        createdAt = Instant.parse("2026-10-01T08:00:00Z"),
        user = name?.let { UserCardDto(from, it) },
    )

    /** A safety number in the iOS shape: 12 groups of 5 digits (golden vector a/b, contacts §4.9). */
    const val NUMBER = "39936 00420 36095 80875 11472 14538 50394 55836 81423 99087 38599 17095"
}
