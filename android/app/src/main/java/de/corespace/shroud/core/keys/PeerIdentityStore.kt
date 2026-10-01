package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.CryptoJson
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Pinned peer identity keys and "safety number verified" flags (iOS `PeerIdentityStore`,
 * `ios/shroud/Services/API/PeerIdentityStore.swift:4-119`; api-realtime §10.1 with plan C16) — the
 * store only; the trust-on-first-use logic, key changes and verification live in W2-CONTACTS'
 * `PeerIdentityController`.
 *
 * First-seen keys are TOFU-trusted; a later mismatch is *not* written here — the caller records a
 * `PeerIdentityChange` and waits for the user. One record, `keys/peer-identity.v1` under the shared
 * WhenUnlocked sealer `shroud.local.v1` (iOS `WhenUnlockedThisDeviceOnly`, `:103`), outer seal
 * only — like iOS it is not under the history key, so it reads while chats are locked once the phone
 * is unlocked (plan §1.5):
 *
 * ```json
 * {"v":1,"keys":{"<lower-case id>":"<b64 key>"},"verified":["<lower-case id>"]}
 * ```
 *
 * Cached in memory after the first definitive read (a record, or none). A read the phone's lock
 * refused (or a transient Keystore error) is not cached and is **never "not pinned"**: [pin] says
 * [PinRead.Unavailable], and every other reader and writer throws [CryptoError.Locked]. Android
 * contract (plan §1.7.4 note): while the pin is unavailable `PeerIdentityController` (W2-CONTACTS)
 * never trusts a key the server sends (TOFU) and never sends or decrypts with one — it throws so the
 * message is queued or retried after unlock. iOS reads a refused Keychain read as `nil`
 * (`PeerIdentityStore.swift:21-34`), which lets a server-sent key through unchecked while the phone
 * is locked and the chats are not. A write never merges over an unreadable record (that would lose
 * every other pin). Writes that fail are dropped like iOS's `try?` (`:41-45`), leaving memory as
 * it was; writes are also dropped while [StorageSeal.isSealed]. Synchronized. Never logs ids or
 * keys. The legacy `UserDefaults` migration (`:26-33`) has nothing to migrate on Android.
 */
class PeerIdentityStore(private val file: SealedFile, private val seal: StorageSeal) {
    /** What [pin] found for one peer. */
    sealed interface PinRead {
        /** A pinned key ([key] is a fresh copy the caller owns) and whether its safety number was compared. */
        class Pinned(val key: ByteArray, val verified: Boolean) : PinRead

        /** Nothing pinned for this peer (or a pin that is not strict Base64, as iOS reads it): trust on first use applies. */
        data object None : PinRead

        /** The record exists but the phone cannot read it now: decide nothing, retry after unlock. */
        data object Unavailable : PinRead
    }

    @Serializable
    private data class Record(
        val v: Int,
        val keys: Map<String, String> = emptyMap(),
        val verified: List<String> = emptyList(),
    )

    private var cache: Record? = null

    /** The pin of [userId] as a three-way answer — the read `PeerIdentityController` decides by. */
    @Synchronized
    fun pin(userId: UUID): PinRead {
        val record = current() ?: return PinRead.Unavailable
        val id = Ids.wire(userId)
        val key = record.keys[id]?.let(B64::decodeStrict) ?: return PinRead.None
        return PinRead.Pinned(key, id in record.verified)
    }

    /**
     * The pinned key of [userId], Base64 as stored (`publicKeyBase64`, `:21-34`); null when none.
     * @throws CryptoError.Locked while the phone cannot read the record (never "not pinned").
     */
    @Synchronized
    fun publicKeyBase64(userId: UUID): String? = readable().keys[Ids.wire(userId)]

    /**
     * The pinned key of [userId] (`publicKeyData`, `:36-39`); null when none or not strict Base64.
     * @throws CryptoError.Locked while the phone cannot read the record.
     */
    @Synchronized
    fun publicKey(userId: UUID): ByteArray? = publicKeyBase64(userId)?.let(B64::decodeStrict)

    /**
     * Pins [publicKey] for [userId], replacing a previous pin (`save`, `:41-45`).
     * @throws CryptoError.Locked while the phone cannot read the record: a merge would lose the other pins.
     */
    @Synchronized
    fun save(userId: UUID, publicKey: ByteArray) {
        val record = readable()
        write(record.copy(keys = record.keys + (Ids.wire(userId) to B64.encode(publicKey))))
    }

    /**
     * The safety number for this contact has been compared (`:47-50`). A new key clears it (caller).
     * @throws CryptoError.Locked while the phone cannot read the record (never "not verified").
     */
    @Synchronized
    fun isVerified(userId: UUID): Boolean = Ids.wire(userId) in readable().verified

    /** @throws CryptoError.Locked while the phone cannot read the record. */
    @Synchronized
    fun setVerified(userId: UUID, verified: Boolean) {
        val record = readable()
        val id = Ids.wire(userId)
        if ((id in record.verified) == verified) return
        write(record.copy(verified = if (verified) record.verified + id else record.verified - id))
    }

    /** Deletes every pin and flag (`clear`, `:61-71`) — sign-out. Works while locked. */
    @Synchronized
    fun clear() {
        file.delete()
        cache = Record(v = VERSION)
    }

    /**
     * Drops the in-memory copy without touching the file: the next call reads the record again.
     * `KeyMaterialWipe` calls it after deleting everything under `keys/`, so a wiped pin cannot answer from RAM.
     */
    @Synchronized
    fun forgetCache() {
        cache = null
    }

    private fun readable(): Record = current() ?: throw CryptoError.Locked

    /** The record, read once; null while the phone's lock (or a transient error) refuses it. */
    private fun current(): Record? {
        cache?.let { return it }
        val loaded = when (val read = file.readClassified()) {
            is RecordRead.Found -> try {
                CryptoJson.decodeFromString(Record.serializer(), read.bytes.decodeToString())
            } catch (_: Exception) {
                // Opened under our key yet unparsable: no build writes that, so start over.
                Record(v = VERSION)
            }
            RecordRead.NotFound -> Record(v = VERSION)
            RecordRead.DeviceLocked, RecordRead.Failed -> return null
        }
        cache = loaded
        return loaded
    }

    private fun write(record: Record) {
        if (seal.isSealed) return
        try {
            file.write(utf8(CryptoJson.encodeToString(Record.serializer(), record)))
        } catch (_: Exception) {
            return
        }
        cache = record
    }

    companion object {
        const val VERSION = 1
    }
}
