package de.corespace.shroud.core.crypto

/**
 * One account's username, sealed to one contact with the same identity box as a message
 * (`sealedBox.ts`, `MessageCrypto.sealBox`). The server stores the JSON and cannot read it.
 */
object ContactNameSeal {
    private val namePattern = Regex("^[a-z0-9_]{3,32}$")

    fun seal(name: String, ourPrivate: ByteArray, ourPublic: ByteArray, theirPublic: ByteArray): String {
        val box = IdentityBoxes.seal(name.encodeToByteArray(), ourPrivate, ourPublic, theirPublic, SystemEntropy)
        return CryptoJson.encodeToString(SealedBox.serializer(), box)
    }

    /** The username, or null when the box does not prove that contact. */
    fun open(sealed: String, ourPrivate: ByteArray, ourPublic: ByteArray, theirPublic: ByteArray): String? {
        val box = try {
            CryptoJson.decodeFromString(SealedBox.serializer(), sealed)
        } catch (_: Exception) {
            return null
        }
        if (box.t == null) return null
        return try {
            if (!IdentityBoxes.verifyTag(box, ourPrivate, theirPublic, ourPublic)) return null
            val name = IdentityBoxes.open(box, ourPrivate, theirPublic, ourPublic).decodeToString()
            name.takeIf { namePattern.matches(it) }
        } catch (_: Exception) {
            null
        }
    }
}
