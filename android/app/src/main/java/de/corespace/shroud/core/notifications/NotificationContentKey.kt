package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal

/**
 * The history key, sealed so a notification can open one message after the chats have locked.
 *
 * Present only while Show Content is on. The Keystore key is AFU (no biometric, readable after the
 * first unlock since boot) — the same class as the call secrets — so the text can be decrypted on
 * this phone without asking again and without sending it anywhere. Turning Show Content off, or
 * Log Out, deletes the record and the key. Never logs the key.
 *
 * The phone's own lock still applies to the identity record and the ratchet files. While that lock
 * is up, [open] may succeed and the stores may still refuse; the notification then keeps the
 * generic line.
 */
class NotificationContentKey(
    private val file: SealedFile,
    private val seal: StorageSeal,
    private val deleteKey: () -> Unit,
) {
    /** Replaces the stored key. A no-op while a wipe runs, or when [historyKey] is not 32 bytes. */
    fun save(historyKey: ByteArray) {
        if (seal.isSealed || historyKey.size != KEY_BYTES) return
        val current = open()
        try {
            if (current != null && current.contentEquals(historyKey)) return
        } finally {
            current?.fill(0)
        }
        val copy = historyKey.copyOf()
        try {
            file.write(copy)
        } finally {
            copy.fill(0)
        }
    }

    /** A copy of the stored key, or null when there is none or it cannot be opened now. The caller wipes it. */
    fun open(): ByteArray? {
        val bytes = file.read() ?: return null
        if (bytes.size != KEY_BYTES) {
            bytes.fill(0)
            return null
        }
        return bytes
    }

    /** Deletes the record and its Keystore key. */
    fun clear() {
        runCatching { file.delete() }
        runCatching { deleteKey() }
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
