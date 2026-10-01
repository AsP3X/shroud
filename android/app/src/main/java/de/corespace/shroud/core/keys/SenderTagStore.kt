package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.RecordStore
import de.corespace.shroud.core.storage.StorageSeal
import java.time.Instant

/**
 * When each sender was first seen tagging their identity boxes, as server time (iOS
 * `SenderTagStore`, `ios/shroud/Services/Crypto/SenderTagStore.swift:5-139`; crypto spec §6), behind
 * the [SenderTagWatermarks] seam `MessageCrypto` (W1-CRYPTO) works against. Once a contact's tagged
 * boxes show up, their untagged ones from that time on are refused, so the server cannot slip in
 * a message "from" them.
 *
 * Keyed by the sender's identity **public key** (ours for self boxes), so a new phrase is a new
 * sender (`:10-11`). On disk: `keys/sender-tags/<LocalNames.name("sender-tag", hex(key))>` in
 * [records] (WhenUnlocked Keystore sealer, plan §1.5) — a keyed name, not iOS's plain SHA-256
 * (`:97-99`), so the file list does not confirm whom this phone talks to (crypto §6.3, D8). The
 * value is the watermark as `Instant.toString()` (ISO-8601, keeps the server's microseconds; iOS
 * writes seconds as a decimal `Double`, `:76-83` — a local format, crypto §6.2), sealed with
 * context `SenderTagKeychain` and the logical name as AAD.
 *
 * While locked [taggedSince] reports [Watermark.Locked] and [noteTagged] drops the write; writes
 * are also dropped while [StorageSeal.isSealed]. Thread-safe; [noteTagged]'s read-modify-write is
 * serialised (`:25-28`). Never logs keys or names.
 */
class SenderTagStore(
    private val records: RecordStore,
    private val state: SealedLocalState,
    private val seal: StorageSeal,
) : SenderTagWatermarks {
    private val writeLock = Any()

    /**
     * `SenderTagStore.swift:36-56`: [Watermark.Locked] without the history key; [Watermark.Untagged]
     * without a record; `Since(Instant.MIN)` for a record that exists but cannot be read, opened or
     * parsed ("a Keychain error is not 'never tagged'"); else `Since(stored)`.
     */
    override fun taggedSince(senderIdentityPublic: ByteArray): Watermark = state.withKeyAndNames { key, names ->
        val name = names.name(LocalNames.Kind.SENDER_TAG, senderIdentityPublic)
        when (val read = records.read(name)) {
            RecordRead.NotFound -> Watermark.Untagged
            RecordRead.DeviceLocked, RecordRead.Failed -> Watermark.Since(Instant.MIN)
            is RecordRead.Found -> try {
                val opened = LocalHistoryCrypto.open(read.bytes, key, LocalHistoryCrypto.Context.SenderTagKeychain, aad(name))
                Watermark.Since(Instant.parse(opened.decodeToString()))
            } catch (_: Exception) {
                // Unreadable is not "never tagged": that would reopen the door this closes (`:49-54`).
                Watermark.Since(Instant.MIN)
            } finally {
                read.bytes.fill(0)
            }
        }
    } ?: Watermark.Locked

    /**
     * `SenderTagStore.swift:58-84`: the watermark only ever moves **earlier**. A stored
     * `Since(existing)` with `existing <= sentAt` is kept — so an unreadable record
     * (`Instant.MIN`) is never overwritten. Dropped while locked, during a wipe, or when the write
     * fails (iOS ignores the Keychain status, `:127-138`).
     */
    override fun noteTagged(senderIdentityPublic: ByteArray, sentAt: Instant) {
        synchronized(writeLock) {
            if (seal.isSealed) return
            val current = taggedSince(senderIdentityPublic)
            if (current is Watermark.Since && current.at <= sentAt) return
            if (current is Watermark.Locked) return
            state.withKeyAndNames { key, names ->
                val name = names.name(LocalNames.Kind.SENDER_TAG, senderIdentityPublic)
                val sealed = LocalHistoryCrypto.seal(utf8(sentAt.toString()), key, LocalHistoryCrypto.Context.SenderTagKeychain, aad(name))
                if (seal.isSealed) return@withKeyAndNames
                try {
                    records.write(name, sealed)
                } catch (_: Exception) {
                    // Dropped, see above.
                }
            }
        }
    }

    /** Forgets every watermark — sign-out, with the ratchets (`:86-93`). Works while locked. */
    override fun deleteAll() {
        synchronized(writeLock) { records.deleteAll() }
    }

    companion object {
        /** The sealed value's AAD: its location `keys/sender-tags/<name>` (web-parity §3.2). */
        fun aad(name: String): ByteArray = utf8("keys/sender-tags/$name")
    }
}
