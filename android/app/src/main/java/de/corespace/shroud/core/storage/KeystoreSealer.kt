package de.corespace.shroud.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals small records at rest. */
interface Sealer {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM under a non-exportable Android Keystore key that needs no user authentication —
 * the Android side of iOS `AfterFirstUnlockThisDeviceOnly` items (the session token, the device
 * anchor). The key lives in the TEE (StrongBox is slow and meant for wrapping keys, which this is
 * not), never leaves it and is not in any backup, so a restored copy of the sealed file is
 * unreadable. With [preferStrongBox], StrongBox is tried first and any failure falls back to
 * the TEE — some phones throw other exceptions than `StrongBoxUnavailableException`.
 *
 * Output: `SHRK1` ‖ 12-byte IV ‖ ciphertext ‖ 16-byte tag.
 */
class KeystoreSealer(private val alias: String, private val preferStrongBox: Boolean = false) : Sealer {
    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return MAGIC + cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > MAGIC.size + IV_BYTES && sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not a sealed record" }
        val iv = sealed.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(sealed, MAGIC.size + IV_BYTES, sealed.size - MAGIC.size - IV_BYTES)
    }

    fun deleteKey() {
        keyStore().deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (preferStrongBox) {
            try {
                return generate(strongBox = true)
            } catch (_: Exception) {
                // No StrongBox, or a broken one: the TEE below.
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        val MAGIC = "SHRK1".toByteArray()
    }
}
