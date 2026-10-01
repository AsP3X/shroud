package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.B64
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
 * refused is not cached, and writes are dropped until it reads — a merge over an unreadable record
 * would lose every other pin. Writes that fail are dropped like iOS's `try?` (`:41-45`), leaving
 * memory as it was; writes are also dropped while [StorageSeal.isSealed]. Synchronized. Never logs
 * ids or keys. The legacy `UserDefaults` migration (`:26-33`) has nothing to migrate on Android.
 */
class PeerIdentityStore(private val file: SealedFile, private val seal: StorageSeal) {
    @Serializable
    private data class Record(
        val v: Int,
        val keys: Map<String, String> = emptyMap(),
        val verified: List<String> = emptyList(),
    )

    private var cache: Record? = null

    /** The pinned key of [userId], Base64 as stored (`publicKeyBase64`, `:21-34`). */
    @Synchronized
    fun publicKeyBase64(userId: UUID): String? = current()?.keys?.get(Ids.wire(userId))

    /** The pinned key of [userId] (`publicKeyData`, `:36-39`); null when none or not strict Base64. */
    @Synchronized
    fun publicKey(userId: UUID): ByteArray? = publicKeyBase64(userId)?.let(B64::decodeStrict)

    /** Pins [publicKey] for [userId], replacing a previous pin (`save`, `:41-45`). */
    @Synchronized
    fun save(userId: UUID, publicKey: ByteArray) {
        val record = current() ?: return
        write(record.copy(keys = record.keys + (Ids.wire(userId) to B64.encode(publicKey))))
    }

    /** The safety number for this contact has been compared (`:47-50`). A new key clears it (caller). */
    @Synchronized
    fun isVerified(userId: UUID): Boolean = current()?.verified?.contains(Ids.wire(userId)) == true

    @Synchronized
    fun setVerified(userId: UUID, verified: Boolean) {
        val record = current() ?: return
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
