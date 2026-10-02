package de.corespace.shroud.core.push.unifiedpush

import de.corespace.shroud.core.storage.SealedFile
import java.io.IOException

/**
 * `unifiedpush.sealed` (plan §1.5): connection token, distributor package, endpoint, P-256 key pair
 * and auth secret, sealed with the AFU alias [ALIAS]. [forget] deletes the file and the key.
 */
class UnifiedPushSubscriptionStore(
    private val file: SealedFile,
    private val deleteKey: () -> Unit,
) {
    data class Record(
        val token: String,
        val privateKey: ByteArray,
        val publicKey: ByteArray,
        val authSecret: ByteArray,
        val distributorPackage: String,
        val endpoint: String,
    )

    fun load(): Record? {
        val raw = file.read() ?: return null
        return try {
            decode(raw)
        } finally {
            raw.fill(0)
        }
    }

    fun save(record: Record) {
        val bytes = encode(record) ?: throw IOException("subscription record is not usable")
        try {
            file.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /** A usable record for [distributor], keeping keys and token when they are already stored. */
    fun loadOrCreate(distributor: String): Record {
        val existing = load()
        if (existing != null && existing.usable() && existing.token.isNotEmpty()) {
            val updated = existing.copy(distributorPackage = distributor)
            save(updated)
            return updated
        }
        val keys = P256.generate() ?: throw IOException("could not generate a subscription key")
        val created = Record(
            token = P256.token(),
            privateKey = keys.privateKey,
            publicKey = keys.publicKey,
            authSecret = keys.auth,
            distributorPackage = distributor,
            endpoint = "",
        )
        save(created)
        return created
    }

    /** Next registration after [REGISTRATION_FAILED] uses a new token (AND_3.1.0). */
    fun rotateToken(): Record? {
        val existing = load() ?: return null
        val updated = existing.copy(token = P256.token(), endpoint = "")
        save(updated)
        return updated
    }

    fun forget() {
        file.delete()
        deleteKey()
    }

    private fun Record.usable(): Boolean =
        privateKey.size == 32 && publicKey.size == 65 && publicKey[0] == 0x04.toByte() && authSecret.size == 16

    companion object {
        const val FILE_NAME = "unifiedpush.sealed"
        const val ALIAS = "shroud.unifiedpush.v1"
        private val MAGIC = byteArrayOf(0x53, 0x48, 0x55, 0x50, 0x31)

        internal fun encode(record: Record): ByteArray? {
            val token = record.token.toByteArray(Charsets.UTF_8)
            val distributor = record.distributorPackage.toByteArray(Charsets.UTF_8)
            val endpoint = record.endpoint.toByteArray(Charsets.UTF_8)
            if (token.size !in 1..100) return null
            if (record.privateKey.size != 32 || record.publicKey.size != 65 || record.authSecret.size != 16) return null
            if (distributor.size > 65535 || endpoint.size > 65535) return null
            val out = ByteArray(5 + 1 + token.size + 32 + 65 + 16 + 2 + distributor.size + 2 + endpoint.size)
            var i = 0
            MAGIC.copyInto(out, i)
            i += 5
            out[i++] = token.size.toByte()
            token.copyInto(out, i)
            i += token.size
            record.privateKey.copyInto(out, i)
            i += 32
            record.publicKey.copyInto(out, i)
            i += 65
            record.authSecret.copyInto(out, i)
            i += 16
            writeU16(out, i, distributor.size)
            i += 2
            distributor.copyInto(out, i)
            i += distributor.size
            writeU16(out, i, endpoint.size)
            i += 2
            endpoint.copyInto(out, i)
            return out
        }

        internal fun decode(raw: ByteArray): Record? {
            if (raw.size < 5 + 1 + 1 + 32 + 65 + 16 + 2 + 2) return null
            if (!raw.copyOfRange(0, 5).contentEquals(MAGIC)) return null
            var i = 5
            val tokenLen = raw[i].toInt() and 0xFF
            i += 1
            if (tokenLen !in 1..100 || raw.size < i + tokenLen + 32 + 65 + 16 + 4) return null
            val token = raw.copyOfRange(i, i + tokenLen).toString(Charsets.UTF_8)
            i += tokenLen
            val privateKey = raw.copyOfRange(i, i + 32)
            i += 32
            val publicKey = raw.copyOfRange(i, i + 65)
            i += 65
            val auth = raw.copyOfRange(i, i + 16)
            i += 16
            val distLen = readU16(raw, i) ?: return null
            i += 2
            if (raw.size < i + distLen + 2) return null
            val distributor = raw.copyOfRange(i, i + distLen).toString(Charsets.UTF_8)
            i += distLen
            val endLen = readU16(raw, i) ?: return null
            i += 2
            if (raw.size != i + endLen) return null
            val endpoint = raw.copyOfRange(i, i + endLen).toString(Charsets.UTF_8)
            if (publicKey[0] != 0x04.toByte()) return null
            return Record(token, privateKey, publicKey, auth, distributor, endpoint)
        }

        private fun writeU16(out: ByteArray, at: Int, value: Int) {
            out[at] = (value ushr 8).toByte()
            out[at + 1] = value.toByte()
        }

        private fun readU16(raw: ByteArray, at: Int): Int? {
            if (at + 1 >= raw.size) return null
            return ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)
        }
    }
}
