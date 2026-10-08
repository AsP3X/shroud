package de.corespace.shroud.core.storage

import java.io.File

/**
 * Named sealed records in one directory — the Android stand-in for one Keychain *service* with
 * one item per account (crypto spec §11.3): ratchet sessions (`<bundle>.dr-sessions`,
 * `ios/shroud/Services/Crypto/RatchetSessionStore.swift:15-17`) under `noBackupFilesDir/keys/…`
 * (plan §1.5).
 *
 * Names are the keyed `LocalNames` hashes, never ids: [NAME] allows `[a-z0-9._-]{1,64}` (no
 * `.`/`..`, no temp suffix). Implementations make each single call thread-safe; read-modify-write
 * sequences are the caller's to serialise.
 */
interface RecordStore {
    /** The record [name], classified like [SealedFile.readClassified]. */
    fun read(name: String): RecordRead

    /** Replaces the record [name] atomically. Throws when sealing or writing fails. */
    fun write(name: String, bytes: ByteArray)

    fun delete(name: String)

    /** Deletes every record (and leftover temp file) of the store. Needs no key. */
    fun deleteAll()

    /** The names of the stored records, sorted. */
    fun names(): List<String>

    companion object {
        /** Valid record names (crypto spec §11.3). */
        val NAME = Regex("[a-z0-9._-]{1,64}")

        /** @throws IllegalArgumentException for a name outside [NAME], `.`, `..` or a temp-file name. */
        fun requireValidName(name: String) {
            require(NAME.matches(name) && name != "." && name != ".." && !name.endsWith(SealedFile.TMP_SUFFIX)) {
                "invalid record name"
            }
        }
    }
}

/**
 * [RecordStore] with one [SealedFile] per name in [dir], sealed by [sealer] (the WhenUnlocked
 * `KeystoreSealer` for `keys/ratchets`). The directory is created on the
 * first write; [deleteAll] removes it with everything in it.
 */
class SealedDirectoryStore(private val dir: File, private val sealer: Sealer) : RecordStore {
    override fun read(name: String): RecordRead = file(name).readClassified()

    override fun write(name: String, bytes: ByteArray) = file(name).write(bytes)

    override fun delete(name: String) = file(name).delete()

    override fun deleteAll() {
        dir.deleteRecursively()
    }

    override fun names(): List<String> =
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(SealedFile.TMP_SUFFIX) && RecordStore.NAME.matches(it.name) }
            .map { it.name }
            .sorted()

    private fun file(name: String): SealedFile {
        RecordStore.requireValidName(name)
        return SealedFile(File(dir, name), sealer)
    }
}
