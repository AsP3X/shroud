package de.corespace.shroud.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals small records at rest. */
interface Sealer {
    fun seal(plaintext: ByteArray): ByteArray

    /** Opens [seal]'s output; throws on any failure. */
    fun open(sealed: ByteArray): ByteArray

    /**
     * Opens [sealed] and says *why* it did not open, so a caller can tell "gone for good" from
     * "the phone is locked right now" (crypto spec §11.3; iOS reads the Keychain `OSStatus`,
     * `ios/shroud/Services/Crypto/IdentityKeyStore.swift:57-89`). A sealer that cannot tell
     * (test stand-ins) reports every failure as [SealResult.Failed].
     */
    fun openClassified(sealed: ByteArray): SealResult = try {
        SealResult.Opened(open(sealed))
    } catch (_: Exception) {
        SealResult.Failed
    }
}

/** Outcome of [Sealer.openClassified]. */
sealed interface SealResult {
    /** The plaintext. The caller owns (and may zero) [bytes]. */
    class Opened(val bytes: ByteArray) : SealResult

    /**
     * The key refuses because the phone is locked (`UserNotAuthenticatedException`, or any
     * Keystore failure while the keyguard is locked). Not definitive: the same record opens once
     * the user unlocks the phone — iOS `errSecInteractionNotAllowed`.
     */
    data object DeviceLocked : SealResult

    /**
     * The key is gone or permanently invalidated (alias missing after an OS update,
     * `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`). Definitive: the record
     * can never open again.
     */
    data object KeyGone : SealResult

    /** The bytes are not a sealed record of this key (wrong magic, too short, GCM tag mismatch). Definitive. */
    data object Corrupt : SealResult

    /** Anything else (a transient Keystore error while the phone is unlocked). Not definitive. */
    data object Failed : SealResult
}

/**
 * AES-256-GCM under a non-exportable Android Keystore key that needs no user authentication —
 * the Android side of the iOS Keychain accessibility classes (crypto spec §11.1; plan §1.5):
 *
 * - **AFU** (`AfterFirstUnlockThisDeviceOnly`): [unlockedDeviceRequired] = false. Readable from the
 *   first unlock after boot on, also while the phone is locked — the session token, the device
 *   anchor, call secrets, the UnifiedPush keys (P3a).
 * - **WU** (`WhenUnlockedThisDeviceOnly`): [unlockedDeviceRequired] = true
 *   (`setUnlockedDeviceRequired`). Usable only while the phone is unlocked — the identity record,
 *   the vault record, ratchets and peer pins under `keys/` share one such key,
 *   `shroud.local.v1`.
 *
 * The key lives in the TEE (StrongBox is slow and meant for wrapping keys, which this is not),
 * never leaves it and is not in any backup, so a restored copy of a sealed file is unreadable.
 * With [preferStrongBox], StrongBox is tried first and any failure falls back to the TEE — some
 * phones throw other exceptions than `StrongBoxUnavailableException`.
 *
 * Output: `SHRK1` ‖ 12-byte IV ‖ ciphertext ‖ 16-byte tag. Distinct from `LocalHistoryCrypto`'s
 * `SHRD1`; a file can carry both (outer `SHRK1`, inner `SHRD1`, crypto spec §2.3).
 *
 * [isDeviceLocked] (`KeyguardManager.isDeviceLocked`, supplied by `DeviceSecurity`) lets
 * [openClassified] read a generic Keystore failure while the keyguard is up as
 * [SealResult.DeviceLocked] (crypto spec §9.2: OEM builds report locked keys in different ways).
 *
 * A key is generated on the first [seal] only; opening never creates one, so a vanished alias is
 * reported as [SealResult.KeyGone] instead of being replaced by a key that cannot open anything.
 * Never logs plaintext, keys or record contents.
 */
class KeystoreSealer(
    private val alias: String,
    private val preferStrongBox: Boolean = false,
    private val unlockedDeviceRequired: Boolean = false,
    private val isDeviceLocked: () -> Boolean = { false },
) : Sealer {
    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return MAGIC + cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(isWellFormed(sealed)) { "not a sealed record" }
        val key = existingKey() ?: throw UnrecoverableKeyException("no key under this alias")
        return decrypt(key, sealed)
    }

    override fun openClassified(sealed: ByteArray): SealResult {
        if (!isWellFormed(sealed)) return SealResult.Corrupt
        return try {
            val key = existingKey() ?: return SealResult.KeyGone
            SealResult.Opened(decrypt(key, sealed))
        } catch (e: Exception) {
            classify(e)
        }
    }

    /** Deletes the key; every record sealed with it becomes [SealResult.KeyGone]. */
    fun deleteKey() {
        keyStore().deleteEntry(alias)
    }

    private fun decrypt(key: SecretKey, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, MAGIC.size, IV_BYTES))
        val offset = MAGIC.size + IV_BYTES
        return cipher.doFinal(sealed, offset, sealed.size - offset)
    }

    /**
     * Maps a failure to its class (crypto spec §11.3). The cause chain is searched because the
     * Keystore often wraps the telling exception (`InvalidKeyException` → `KeyStoreException`).
     */
    private fun classify(error: Throwable): SealResult {
        var e: Throwable? = error
        while (e != null) {
            when (e) {
                is AEADBadTagException -> return SealResult.Corrupt
                is KeyPermanentlyInvalidatedException, is UnrecoverableKeyException -> return SealResult.KeyGone
                is UserNotAuthenticatedException -> return SealResult.DeviceLocked
            }
            e = e.cause?.takeIf { it !== e }
        }
        val locked = try {
            isDeviceLocked()
        } catch (_: RuntimeException) {
            false
        }
        return if (locked) SealResult.DeviceLocked else SealResult.Failed
    }

    private fun isWellFormed(sealed: ByteArray): Boolean {
        if (sealed.size < MAGIC.size + IV_BYTES + TAG_BYTES) return false
        for (i in MAGIC.indices) if (sealed[i] != MAGIC[i]) return false
        return true
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    @Synchronized
    private fun key(): SecretKey {
        existingKey()?.let { return it }
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
            .setUnlockedDeviceRequired(unlockedDeviceRequired)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    companion object {
        /** The shared WhenUnlocked sealer's alias for every record under `keys/` (plan §1.5, C16). */
        const val LOCAL_ALIAS = "shroud.local.v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * 8
        private val MAGIC = "SHRK1".toByteArray()
    }
}
