package de.corespace.shroud.core.media.share

import de.corespace.shroud.core.media.SealedMediaReader
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Plaintext a share target may read, held only in memory. [DecryptedMediaProvider] is created
 * before [de.corespace.shroud.ShroudApplication.onCreate], so it reads this object and never the
 * container. [clear] drops every grant; a later [DecryptedMediaProvider.openFile] throws.
 *
 * A photo's grant holds its decrypted bytes ([Bytes]); a file's ([SealedFile]) holds only how to
 * open its SHRM1 reader, so even a 2 GB file is decrypted segment by segment as the other app reads
 * it (docs/file-sharing.md §8). [clear] also closes the readers such grants opened, and [revoke]
 * does both for the messages that were deleted: a descriptor another app still holds stops reading.
 */
internal object SharedMediaRegistry {
    /** A grant over [messageId]'s media, typed [mime]. */
    sealed class Item(val messageId: UUID, val mime: String)

    class Bytes(messageId: UUID, val bytes: ByteArray, mime: String) : Item(messageId, mime)

    /** A file's grant: its cleaned [name] and [size] for `OpenableColumns`, [open] for the bytes. */
    class SealedFile(messageId: UUID, val name: String, val size: Long, mime: String, private val open: () -> SealedMediaReader?) :
        Item(messageId, mime) {
        private val readers: MutableSet<SealedMediaReader> = ConcurrentHashMap.newKeySet()

        /** A new reader for one `openFile`; null once the media is gone or chats are locked. Close it through [release]. */
        fun reader(): SealedMediaReader? = open()?.also { readers += it }

        fun release(reader: SealedMediaReader) {
            readers -= reader
            try {
                reader.close()
            } catch (_: IOException) {
            }
        }

        fun closeAll() {
            for (reader in readers.toList()) release(reader)
        }
    }

    private val items = ConcurrentHashMap<String, Item>()

    fun put(id: String, messageId: UUID, bytes: ByteArray, mime: String) {
        items[id] = Bytes(messageId, bytes, mime)
    }

    fun putFile(id: String, file: SealedFile) {
        items[id] = file
    }

    fun open(id: String?): Item? = if (id.isNullOrEmpty()) null else items[id]

    /** Drops every grant over the media of [messageIds] and closes the readers file grants opened. */
    fun revoke(messageIds: Collection<UUID>) {
        if (messageIds.isEmpty()) return
        val ids = messageIds.toHashSet()
        val dropped = ArrayList<Item>()
        val entries = items.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.value.messageId in ids) {
                dropped += entry.value
                entries.remove()
            }
        }
        dropped.filterIsInstance<SealedFile>().forEach { it.closeAll() }
    }

    fun clear() {
        val dropped = items.values.toList()
        items.clear()
        dropped.filterIsInstance<SealedFile>().forEach { it.closeAll() }
    }
}
