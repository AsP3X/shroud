package de.corespace.shroud.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom

/**
 * What reading a sealed record found (crypto spec §11.3). The Android counterpart of the Keychain
 * statuses iOS branches on (`errSecSuccess`, `errSecItemNotFound`, `errSecInteractionNotAllowed`,
 * anything else — `ios/shroud/Services/Crypto/IdentityKeyStore.swift:68-89`).
 */
sealed interface RecordRead {
    /** The opened record. The caller owns (and may zero) [bytes]. */
    class Found(val bytes: ByteArray) : RecordRead

    /**
     * No record — or one that can never open again (its key is gone, or the bytes are not a record
     * of this key). Definitive, like `errSecItemNotFound`: crypto spec §9.2.
     */
    data object NotFound : RecordRead

    /** The record exists but the phone is locked (`errSecInteractionNotAllowed`). Retry after unlock. */
    data object DeviceLocked : RecordRead

    /** Any other failure (I/O, a transient Keystore error). Not definitive. */
    data object Failed : RecordRead
}

/**
 * One sealed record in app-private, no-backup storage. Written atomically (temp file, fsync,
 * rename) so a crash leaves either the old record or the new one, never half of one. The parent
 * directory is created on the first write.
 */
class SealedFile(private val file: File, private val sealer: Sealer) {
    /** The record, or null when there is none or it does not open now (key gone, damaged file, phone locked). */
    fun read(): ByteArray? = (readClassified() as? RecordRead.Found)?.bytes

    /**
     * The record with the reason it could not be read (crypto spec §11.3): a missing file, a
     * vanished or invalidated key and bytes that are not a record of this key are [RecordRead.NotFound];
     * a locked phone is [RecordRead.DeviceLocked]; anything else is [RecordRead.Failed].
     */
    fun readClassified(): RecordRead {
        if (!file.exists()) return RecordRead.NotFound
        val sealed = try {
            file.readBytes()
        } catch (_: IOException) {
            return if (file.exists()) RecordRead.Failed else RecordRead.NotFound
        }
        return when (val opened = sealer.openClassified(sealed)) {
            is SealResult.Opened -> RecordRead.Found(opened.bytes)
            SealResult.DeviceLocked -> RecordRead.DeviceLocked
            SealResult.KeyGone, SealResult.Corrupt -> RecordRead.NotFound
            SealResult.Failed -> RecordRead.Failed
        }
    }

    /**
     * Seals [plaintext] and replaces the record. Throws when sealing or writing fails; the old
     * record then stays. Each write uses its own temp name, so two writers of one file never
     * share a half-written temp file; the last rename wins.
     */
    fun write(plaintext: ByteArray) {
        val sealed = sealer.seal(plaintext)
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("could not create the record directory")
        val tmp = File(dir, file.name + "." + java.lang.Long.toHexString(ThreadLocalRandom.current().nextLong()) + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use {
                it.write(sealed)
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun delete() {
        file.delete()
    }

    fun exists(): Boolean = file.exists()

    companion object {
        /** Suffix of in-flight temp files; directory listings skip them. */
        const val TMP_SUFFIX = ".tmp"
    }
}
