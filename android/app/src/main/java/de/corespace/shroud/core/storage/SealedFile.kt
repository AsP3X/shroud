package de.corespace.shroud.core.storage

import java.io.File
import java.io.FileOutputStream

/** One sealed record in app-private, no-backup storage. Written atomically and synced. */
class SealedFile(private val file: File, private val sealer: Sealer) {
    /** The record, or null when there is none or it no longer opens (key gone, damaged file). */
    fun read(): ByteArray? {
        if (!file.exists()) return null
        return runCatching { sealer.open(file.readBytes()) }.getOrNull()
    }

    fun write(plaintext: ByteArray) {
        val sealed = sealer.seal(plaintext)
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(sealed)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("could not replace ${file.name}")
        }
    }

    fun delete() {
        file.delete()
    }
}
