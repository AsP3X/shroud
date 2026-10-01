package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.keys.RatchetSessionRecords
import de.corespace.shroud.core.keys.SenderTagWatermarks
import de.corespace.shroud.core.keys.Watermark
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests: [RatchetSessionRecords] in memory, with the contract of the sealed store (W1-KEYS) —
 * the role of iOS `SealedTestKey.unlockSealedLocalState()` + the Keychain-backed
 * `RatchetSessionStore` in `ios/shroudTests/DoubleRatchetTests.swift:17-31`. One instance can stand
 * for two parties, as on iOS: sessions are keyed by the peer's user id only.
 *
 * [isUnlocked] = false behaves like a locked store: [load] reads nothing, [save] is dropped.
 * [unreadable] = true behaves like a locked phone with chats unlocked (see there).
 */
class InMemoryRatchetSessionRecords : RatchetSessionRecords {
    private val sessions = ConcurrentHashMap<UUID, ByteArray>()

    @Volatile
    override var isUnlocked: Boolean = true

    /** Saves that reached the map (tests: "nothing was written"). */
    @Volatile
    var saveCount: Int = 0
        private set

    /**
     * Chats unlocked but the phone locked (or a transient Keystore error): an existing record
     * cannot be read — [load] throws [CryptoError.Locked] as the sealed store does — and a save is
     * dropped, as the WhenUnlocked sealer refuses to write.
     */
    @Volatile
    var unreadable: Boolean = false

    override fun load(peerUserId: UUID): ByteArray? {
        if (!isUnlocked) return null
        val stored = sessions[peerUserId] ?: return null
        if (unreadable) throw CryptoError.Locked
        return stored.copyOf()
    }

    override fun save(peerUserId: UUID, sessionJson: ByteArray) {
        if (!isUnlocked || unreadable) return
        sessions[peerUserId] = sessionJson.copyOf()
        synchronized(this) { saveCount++ }
    }

    override fun delete(peerUserId: UUID) {
        sessions.remove(peerUserId)
    }

    override fun deleteAll() = sessions.clear()

    /** The stored session, decoded (tests compare sessions by value, like Swift's `Equatable`). */
    fun session(peerUserId: UUID): DoubleRatchet.Session? = sessions[peerUserId]?.let(DoubleRatchet.Session::fromJson)

    /** Puts [session] back as it is (iOS `RatchetSessionStore.save(_:peerUserID:)` in a test). */
    fun put(peerUserId: UUID, session: DoubleRatchet.Session) {
        sessions[peerUserId] = session.toJson()
    }
}

/**
 * Tests: [SenderTagWatermarks] in memory (iOS `SenderTagStore.useInMemoryStorageForTesting()`,
 * `ios/shroud/Services/Crypto/SenderTagStore.swift:31-34`), keyed by the sender's identity key.
 * [isLocked] = true answers [Watermark.Locked] and drops writes, as the sealed store does without the
 * history key.
 */
class InMemorySenderTagWatermarks : SenderTagWatermarks {
    private val watermarks = HashMap<String, Instant>()

    @Volatile
    var isLocked: Boolean = false

    @Synchronized
    override fun taggedSince(senderIdentityPublic: ByteArray): Watermark {
        if (isLocked) return Watermark.Locked
        return watermarks[senderIdentityPublic.hex()]?.let { Watermark.Since(it) } ?: Watermark.Untagged
    }

    /** The watermark only moves earlier (`SenderTagStore.swift:59-84`). */
    @Synchronized
    override fun noteTagged(senderIdentityPublic: ByteArray, sentAt: Instant) {
        if (isLocked) return
        val existing = watermarks[senderIdentityPublic.hex()]
        if (existing != null && !existing.isAfter(sentAt)) return
        watermarks[senderIdentityPublic.hex()] = sentAt
    }

    @Synchronized
    override fun deleteAll() = watermarks.clear()

    @Synchronized
    fun count(): Int = watermarks.size
}
