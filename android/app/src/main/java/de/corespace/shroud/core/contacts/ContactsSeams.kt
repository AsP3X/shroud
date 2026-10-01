package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.PeerIdentityChange
import de.corespace.shroud.core.model.PeerIdentityEvent
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.net.UpdatePrivacySettingsBody
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

// Contacts, peer identity and privacy (plan §1.7.8, C6: split as contacts.md). Published by W1-INT
// with the final signatures; W2-CONTACTS implements Contacts (ContactsController), PeerIdentities
// (PeerIdentityController) and Privacy (PrivacyController); MessagingController (W2-MSG-CORE)
// implements ContactsHooks. Changing one is a contract change request (plan §2.0 rule 4).

/**
 * What contacts needs from messaging (contacts §7.1); implemented by `MessagingController`, whose
 * public API (plan §1.7.7) exposes the same names, so the types match it: [isRealtimeConnected] is
 * the controller's `StateFlow` (contacts reads `.value`).
 */
interface ContactsHooks {
    val isRealtimeConnected: StateFlow<Boolean>

    /** Roster or requests changed: write the sealed roster (contacts persist through the messaging roster file). */
    fun persistRoster()

    /** Clears the peer's typing and recording. */
    fun onPeerOffline(userId: UUID)

    /** Clears activity and refreshes the chat list (`refreshConversations(force = true)`). */
    suspend fun onBlocked(userId: UUID)

    fun announceContactRequest(request: ContactRequestDto)
    fun setLastError(message: String?)
    fun setOffline(offline: Boolean)
}

/** The roster, requests, presence and blocks; implemented by `ContactsController` (W2-CONTACTS). */
interface Contacts {
    /** Sorted by username (`Collator`, SECONDARY strength). */
    val contacts: StateFlow<List<ContactItemDto>>
    val incomingRequests: StateFlow<List<ContactRequestDto>>
    val listState: StateFlow<ContactsListState>

    /** Published once per sweep. */
    val presence: StateFlow<Map<UUID, PresenceDto>>
    val blocked: StateFlow<List<BlockItemDto>>

    /** Fires when the roster changed; calls refresh their secrets on it (plan C29). */
    val rosterChanges: SharedFlow<Unit>

    /** An App Link's invite, prefilled into Add Contact (P10c); memory only. */
    val pendingInvite: MutableStateFlow<String?>

    fun username(of: UUID): String?
    suspend fun refresh(force: Boolean = false)
    suspend fun refreshPresence(userIds: Collection<UUID>)

    /** Share code, username, link or user id (`ContactInviteParser`, `InviteLookup`). */
    suspend fun add(invite: String): AddContactOutcome

    /** Null on success, else the user-facing error. */
    suspend fun accept(request: ContactRequestDto): String?
    suspend fun reject(request: ContactRequestDto): String?

    suspend fun refreshBlocks()
    suspend fun block(userId: UUID, username: String): String?
    suspend fun unblock(userId: UUID): String?

    // Lifecycle, driven by MessagingController.
    fun bind(hooks: ContactsHooks)
    fun hydrate(contacts: List<ContactItemDto>, requests: List<ContactRequestDto>)
    fun start()
    fun onForeground()
    fun onBackground()
    fun onConnectivityRegained()
    fun stop(wipe: Boolean)
}

/** Trust on first use, key changes, verification, safety numbers; implemented by `PeerIdentityController` (W2-CONTACTS). */
interface PeerIdentities {
    val identityChanges: StateFlow<Map<UUID, PeerIdentityChange>>
    val verifiedPeers: StateFlow<Set<UUID>>
    val events: SharedFlow<PeerIdentityEvent>

    /**
     * Decrypt path: the pinned key (pinned now if this is the first). Reads the pin through
     * `PeerIdentityStore.pin`: while it is `Unavailable` (phone locked, chats unlocked) throws
     * `CryptoError.Locked` — never trusts the server's key as a first use (plan §1.7.4 note).
     */
    suspend fun resolvePublicKey(peer: UUID): ByteArray

    /**
     * Throws `PeerIdentityChangedException` while a change is pending, and `CryptoError.Locked`
     * while the pin is unavailable, so the send is queued and retried after unlock.
     */
    suspend fun publicKeyForSending(peer: UUID): ByteArray

    suspend fun refresh(peer: UUID)
    fun identityChange(peer: UUID): PeerIdentityChange?
    fun isSafetyVerified(peer: UUID): Boolean
    fun confirmSafety(peer: UUID)

    /** The pending key's number while a change is pending (P10b). */
    fun safetyNumber(peer: UUID): String?

    /** Repin, clear verified, delete the ratchet session, emit `KeyAccepted`. */
    fun acceptNewIdentity(peer: UUID)

    fun clearMemory()
    fun wipe()
}

/** Privacy settings and the share code; implemented by `PrivacyController` (W2-CONTACTS). */
interface Privacy {
    val settings: StateFlow<PrivacySettingsDto>
    val hasLoaded: StateFlow<Boolean>
    suspend fun refresh()

    /** Null on success, else the user-facing error. */
    suspend fun update(change: UpdatePrivacySettingsBody): String?
    suspend fun setAllowsPeerChatDelete(value: Boolean): String?
    suspend fun rotateShareCode(): String?
    fun reset()
}
