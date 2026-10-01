package de.corespace.shroud.core.contacts

import com.ibm.icu.text.Collator
import com.ibm.icu.util.ULocale
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.keys.RatchetSessionRecords
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.net.UpdatePrivacySettingsBody
import de.corespace.shroud.core.net.UserCardDto
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.Collections
import java.util.UUID

// Test doubles for the contacts engines: a scriptable backend that logs every call, the hooks
// MessagingController implements, and ICU collation as Android's `java.text.Collator` does it (the
// JVM's own collator is not ICU).

internal fun notFound() = ApiError.Server(ErrorCodes.NOT_FOUND, "User not found.", 404)

internal fun session(userId: UUID, token: String = "tok", shareCode: String? = "ABCD234567") =
    Session(token = token, userId = userId.toString(), username = "me", shareCode = shareCode, deviceId = "2e6f9b0c-1d3a-4e5b-8c7d-9f0a1b2c3d4e")

internal fun contact(name: String, id: UUID = UUID.nameUUIDFromBytes(name.toByteArray())) =
    ContactItemDto(userId = id, username = name, createdAt = Instant.parse("2026-09-01T10:00:00Z"))

internal fun pendingRequest(from: UUID, to: UUID, name: String?, id: UUID = UUID.randomUUID()) = ContactRequestDto(
    id = id,
    fromUserId = from,
    toUserId = to,
    status = "pending",
    createdAt = Instant.parse("2026-09-30T12:00:00Z"),
    user = name?.let { UserCardDto(from, it) },
)

/** Android's collation for the tests: ICU, US English, secondary strength. */
internal fun icuOrder(): Comparator<String> {
    val collator = Collator.getInstance(ULocale.US).apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(a, b) }
}

/** A [ContactsBackend] whose answers a test scripts; [calls] lists every call in order. */
internal class FakeContactsBackend : ContactsBackend {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    var onContacts: suspend () -> List<ContactItemDto> = { emptyList() }
    var onRequests: suspend () -> List<ContactRequestDto> = { emptyList() }
    var onUser: suspend (UUID) -> UserCardDto = { throw notFound() }
    var onByUsername: suspend (String) -> UserCardDto = { throw notFound() }
    var onByCode: suspend (String) -> UserCardDto = { throw notFound() }
    var onCreateRequest: suspend (UUID) -> ContactRequestDto = { error("no request scripted") }
    var onAccept: suspend (UUID) -> ContactRequestDto = { error("no accept scripted") }
    var onReject: suspend (UUID) -> ContactRequestDto = { error("no reject scripted") }
    var onPresence: suspend (UUID) -> PresenceDto = { PresenceDto(it, online = false) }
    var onBlocks: suspend () -> List<BlockItemDto> = { emptyList() }
    var onBlock: suspend (UUID) -> Unit = {}
    var onUnblock: suspend (UUID) -> Unit = {}
    var onIdentityKey: suspend (UUID) -> String = { error("no identity key scripted") }
    var onPrivacy: suspend () -> PrivacySettingsDto = { PrivacySettingsDto(allowPeerChatDelete = false) }
    var onUpdatePrivacy: suspend (UpdatePrivacySettingsBody) -> PrivacySettingsDto = { error("no privacy update scripted") }
    var onRotate: suspend () -> String = { error("no rotation scripted") }

    /** How often [name] was called (the first word of the logged entry). */
    fun count(name: String): Int = synchronized(calls) { calls.count { it.substringBefore(' ') == name } }

    override suspend fun contacts(token: String): List<ContactItemDto> = log("contacts").let { onContacts() }
    override suspend fun incomingRequests(token: String): List<ContactRequestDto> = log("requests").let { onRequests() }
    override suspend fun user(token: String, userId: UUID): UserCardDto = log("user $userId").let { onUser(userId) }
    override suspend fun userByUsername(token: String, username: String): UserCardDto = log("by-username $username").let { onByUsername(username) }
    override suspend fun userByShareCode(token: String, code: String): UserCardDto = log("by-code $code").let { onByCode(code) }
    override suspend fun createContactRequest(token: String, userId: UUID): ContactRequestDto = log("create $userId").let { onCreateRequest(userId) }
    override suspend fun acceptContactRequest(token: String, requestId: UUID): ContactRequestDto = log("accept $requestId").let { onAccept(requestId) }
    override suspend fun rejectContactRequest(token: String, requestId: UUID): ContactRequestDto = log("reject $requestId").let { onReject(requestId) }
    override suspend fun presence(token: String, userId: UUID): PresenceDto = log("presence $userId").let { onPresence(userId) }
    override suspend fun blocks(token: String): List<BlockItemDto> = log("blocks").let { onBlocks() }
    override suspend fun block(token: String, userId: UUID) = log("block $userId").let { onBlock(userId) }
    override suspend fun unblock(token: String, userId: UUID) = log("unblock $userId").let { onUnblock(userId) }
    override suspend fun identityKey(token: String, userId: UUID): String = log("identity $userId").let { onIdentityKey(userId) }
    override suspend fun privacySettings(token: String): PrivacySettingsDto = log("privacy").let { onPrivacy() }
    override suspend fun updatePrivacySettings(token: String, change: UpdatePrivacySettingsBody): PrivacySettingsDto =
        log("update-privacy").let { onUpdatePrivacy(change) }
    override suspend fun rotateShareCode(token: String): String = log("rotate").let { onRotate() }

    private fun log(entry: String) {
        calls += entry
    }
}

/** What `MessagingController` does for contacts, recorded. */
internal class RecordingHooks : ContactsHooks {
    override val isRealtimeConnected = MutableStateFlow(false)
    var persisted = 0
    val offlinePeers = mutableListOf<UUID>()
    val blockedPeers = mutableListOf<UUID>()
    val announced = mutableListOf<ContactRequestDto>()

    /** Messaging's `lastError`; starts non-null so a test sees a success clear it. */
    var error: String? = "stale"
    var offline: Boolean? = null

    override fun persistRoster() {
        persisted++
    }

    override fun onPeerOffline(userId: UUID) {
        offlinePeers += userId
    }

    override suspend fun onBlocked(userId: UUID) {
        blockedPeers += userId
    }

    override fun announceContactRequest(request: ContactRequestDto) {
        announced += request
    }

    override fun setLastError(message: String?) {
        error = message
    }

    override fun setOffline(offline: Boolean) {
        this.offline = offline
    }
}

/** Ratchet sessions in memory, with every delete recorded. */
internal class RecordingRatchets : RatchetSessionRecords {
    val sessions = Collections.synchronizedMap(mutableMapOf<UUID, ByteArray>())
    val deletes: MutableList<UUID> = Collections.synchronizedList(mutableListOf())
    override val isUnlocked: Boolean = true
    override fun load(peerUserId: UUID): ByteArray? = sessions[peerUserId]
    override fun save(peerUserId: UUID, session: ByteArray) {
        sessions[peerUserId] = session
    }

    override fun delete(peerUserId: UUID) {
        deletes += peerUserId
        sessions.remove(peerUserId)
    }

    override fun deleteAll() = sessions.clear()
}
