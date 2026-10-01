package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.keys.LocalNames
import de.corespace.shroud.core.net.wire.LenientJson
import de.corespace.shroud.core.storage.StorageSeal
import java.io.File
import java.util.UUID

/**
 * Plaintext of a message after its first successful decrypt (or send), sealed at rest (iOS
 * `LocalPlaintextCache`, `ios/shroud/Services/Messaging/LocalPlaintextCache.swift:4-168`;
 * messaging-core §22.3). Ratchet message keys are one-shot: a received message is opened once and
 * read from here ever after, so this cache is what keeps old messages readable.
 *
 * - **L1**: a map for this unlock (`:14-15`), cleared on every chat lock ([clearMemory]).
 * - **L2**: `shroud/plaintext/<name msg:id>.sealed` under [root], one [LocalHistoryCrypto] blob per
 *   message under the `PlaintextPayload` subkey, its path as AAD (plan §1.5, C10). Not per account,
 *   like iOS: the whole directory goes on sign-out ([clearAll]).
 *
 * Fails closed: a file that does not open reads as null, never as something else (`:47-50`). While
 * chats are locked nothing is named, read, kept in L1 or written. iOS's `UserDefaults` migration
 * (`:53-59`, `:134-159`) has no Android counterpart.
 *
 * Thread-safe. L1 has its own short lock; each message's file operations run under one of
 * [STRIPES] locks picked by its id, so a purge and a save of the same message never interleave,
 * while different messages decode in parallel. A lock that lands during a read never leaves its
 * plaintext in L1 (the [epoch] check). Never logs ids, names or contents.
 */
class LocalPlaintextCache(
    private val root: File,
    private val keys: LocalStoreKeys,
    private val storageSeal: StorageSeal,
) {
    private val memoryLock = Any()
    private val memory = HashMap<UUID, ByteArray>() // guarded by memoryLock
    private var epoch = 0L // guarded by memoryLock; bumped by clearMemory
    private val stripes = Array(STRIPES) { Any() }

    /** `shroud/plaintext`. */
    val directory: File get() = File(root, PLAINTEXT)

    /**
     * The plaintext of [messageId] (`data(for:)`, `:27-61`): from L1, else from its sealed file
     * (then kept in L1). Null while locked, when there is none, or when the file does not open. The
     * caller gets its own copy.
     */
    fun data(messageId: UUID): ByteArray? {
        val startEpoch = synchronized(memoryLock) { epoch }
        val at = location(messageId) ?: return null
        synchronized(memoryLock) { memory[messageId]?.let { return it.copyOf() } }
        val plain = synchronized(stripe(messageId)) {
            val blob = LocalFiles.read(at.file) ?: return null
            keys.open(LocalHistoryCrypto.Context.PlaintextPayload, blob, at.aad) ?: return null
        }
        synchronized(memoryLock) {
            if (epoch == startEpoch && keys.isUnlocked) memory[messageId] = plain.copyOf()
        }
        return plain
    }

    /** [data] as UTF-8 (`text(for:)`, `:63-66`); null when the bytes are not UTF-8. */
    fun text(messageId: UUID): String? = data(messageId)?.let(LenientJson::utf8OrNull)

    /**
     * Keeps [bytes] as the plaintext of [messageId] (`save`, `:68-100`): L1 first, then the sealed
     * file, written atomically with an fsync — the message key is gone after this, so a crash must
     * not lose the only readable copy. iOS reads the file back and ignores the result (`:85-95`);
     * here the fsync'd rename is the check. Dropped while locked and while a wipe runs.
     */
    fun save(messageId: UUID, bytes: ByteArray) {
        if (storageSeal.isSealed) return
        val startEpoch = synchronized(memoryLock) { epoch }
        val at = location(messageId) ?: return
        synchronized(stripe(messageId)) {
            synchronized(memoryLock) {
                if (epoch == startEpoch && keys.isUnlocked) memory[messageId] = bytes.copyOf()
            }
            val sealed = keys.seal(LocalHistoryCrypto.Context.PlaintextPayload, bytes, at.aad) ?: return
            if (storageSeal.isSealed) return
            try {
                LocalFiles.write(at.file, sealed)
            } catch (_: Exception) {
                // L1 still holds it for this unlock; the next save may land (`:96-98`).
            }
        }
    }

    /** `save(messageID:text:)` (`:102-104`). */
    fun save(messageId: UUID, text: String) = save(messageId, text.toByteArray(Charsets.UTF_8))

    /**
     * Drops [messageIds] from L1 and disk (`remove(messageIDs:)`, `:121-132`). The file names need
     * the key: returns the ids whose files could not be named (chats locked) so the caller can retry
     * them after the next unlock; their L1 entries are gone either way.
     */
    fun remove(messageIds: Collection<UUID>): List<UUID> {
        synchronized(memoryLock) { messageIds.forEach { memory.remove(it) } }
        val unnamed = ArrayList<UUID>()
        for (id in messageIds) {
            val at = location(id)
            if (at == null) {
                unnamed += id
                continue
            }
            synchronized(stripe(id)) { at.file.delete() }
        }
        return unnamed
    }

    /** Drops L1 only (`clearMemory`, `:106-111`): chats locked, sealed files stay. */
    fun clearMemory() {
        synchronized(memoryLock) {
            epoch++
            memory.values.forEach { it.fill(0) }
            memory.clear()
        }
    }

    /** Drops every entry, L1 and disk (`clearAll`, `:113-119`): sign-out. */
    fun clearAll() {
        clearMemory()
        directory.deleteRecursively()
    }

    /** The sealed file of [messageId] (tests check its bytes and modification time); null while locked. */
    fun file(messageId: UUID): File? = location(messageId)?.file

    private fun location(messageId: UUID): LocalMessageStore.Location? {
        val name = keys.name(LocalNames.Kind.MESSAGE, messageId) ?: return null
        val relative = "$PLAINTEXT/$name${LocalMessageStore.SEALED}"
        return LocalMessageStore.Location(File(root, relative), relative)
    }

    private fun stripe(id: UUID): Any = stripes[(id.hashCode() and Int.MAX_VALUE) % STRIPES]

    companion object {
        const val PLAINTEXT = "shroud/plaintext"
        private const val STRIPES = 32
    }
}
