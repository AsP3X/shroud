package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.RecordStore
import de.corespace.shroud.core.storage.StorageSeal
import java.util.UUID

/**
 * Double Ratchet sessions, one sealed record per peer (iOS `RatchetSessionStore`,
 * `ios/shroud/Services/Crypto/RatchetSessionStore.swift:5-177`; crypto spec §7), behind the
 * [RatchetSessionRecords] seam `MessageCrypto` (W1-CRYPTO) works against.
 *
 * On disk: `keys/ratchets/<LocalNames.name("ratchet", peer)>` in [records] (the WhenUnlocked
 * Keystore sealer, plan §1.5), the value `LocalHistoryCrypto.seal([VERSION] ‖ encoded session)` with
 * context `RatchetKeychain` under the history key and the record's logical name as AAD (web-parity
 * §3.2). The leading version byte is the record's `v`, so a later format can migrate (crypto §7.1).
 *
 * Follows the chat lock through [state]: while locked [load] is null and [save] is dropped — never
 * stored in the clear: "a lost ratchet step costs at most a skipped message key, a plaintext one
 * leaks the chain" (`:33-40`). [save] is also dropped while [StorageSeal.isSealed] (crypto §14).
 *
 * Not ported (no older Android build): the plaintext migration and the sealed marker (`:59-110`, `:147-176`).
 * Each call is thread-safe; callers serialise load → mutate → save per peer (`PeerLocks`, plan §1.4).
 */
class RatchetSessionStore(
    private val records: RecordStore,
    private val state: SealedLocalState,
    private val seal: StorageSeal,
) : RatchetSessionRecords {
    override val isUnlocked: Boolean get() = state.isUnlocked

    /**
     * `RatchetSessionStore.swift:22-31`: null while chats are locked, without a record, or when it
     * does not open. A record the phone cannot read now ([RecordRead.DeviceLocked], the
     * WhenUnlocked sealer while the phone is locked; [RecordRead.Failed], a transient Keystore or
     * I/O error) throws [CryptoError.Locked]: it is there, so it must never read as "no session"
     * ([RatchetSessionRecords.load], crypto D5).
     */
    override fun load(peerUserId: UUID): ByteArray? = state.withKeyAndNames { key, names ->
        val name = names.name(LocalNames.Kind.RATCHET, peerUserId)
        val read = when (val result = records.read(name)) {
            is RecordRead.Found -> result
            RecordRead.NotFound -> return@withKeyAndNames null
            RecordRead.DeviceLocked, RecordRead.Failed -> throw CryptoError.Locked
        }
        val opened = try {
            LocalHistoryCrypto.open(read.bytes, key, LocalHistoryCrypto.Context.RatchetKeychain, aad(name))
        } catch (_: Exception) {
            return@withKeyAndNames null
        } finally {
            read.bytes.fill(0)
        }
        try {
            if (opened.size < 2 || opened[0] != VERSION) null else opened.copyOfRange(1, opened.size)
        } finally {
            opened.fill(0)
        }
    }

    /**
     * `RatchetSessionStore.swift:33-40`: dropped while locked or while a wipe runs. A failed write
     * (the phone locked meanwhile, I/O) is dropped too, as iOS ignores the Keychain status
     * (`:132-145`): the next step re-saves, and a lost step costs at most a skipped message key.
     */
    override fun save(peerUserId: UUID, session: ByteArray) {
        if (seal.isSealed) return
        state.withKeyAndNames { key, names ->
            val name = names.name(LocalNames.Kind.RATCHET, peerUserId)
            val plain = ByteArray(1 + session.size).also {
                it[0] = VERSION
                session.copyInto(it, 1)
            }
            val sealed = try {
                LocalHistoryCrypto.seal(plain, key, LocalHistoryCrypto.Context.RatchetKeychain, aad(name))
            } finally {
                plain.fill(0)
            }
            if (seal.isSealed) return@withKeyAndNames
            try {
                records.write(name, sealed)
            } catch (_: Exception) {
                // Dropped, see above.
            }
        }
    }

    /**
     * Forgets [peerUserId]'s session — the user accepted a changed identity key
     * (`RatchetSessionStore.swift:42-49`, `MessagingController.swift:4501-4510`). The keyed name
     * needs the history key, so this is a no-op while locked (the accept runs in an unlocked chat).
     */
    override fun delete(peerUserId: UUID) {
        state.withKeyAndNames { _, names -> records.delete(names.name(LocalNames.Kind.RATCHET, peerUserId)) }
    }

    /** Forgets every session — sign-out (`:51-57`). Works while locked. */
    override fun deleteAll() {
        records.deleteAll()
    }

    companion object {
        /** The record format: `[VERSION] ‖ DoubleRatchet.Session.encode()` (its own format byte inside). */
        const val VERSION: Byte = 1

        /** The sealed value's AAD: its location `keys/ratchets/<name>` (web-parity §3.2). */
        fun aad(name: String): ByteArray = utf8("keys/ratchets/$name")
    }
}
