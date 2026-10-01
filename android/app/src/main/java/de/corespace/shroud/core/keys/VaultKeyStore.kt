package de.corespace.shroud.core.keys

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import de.corespace.shroud.core.crypto.Entropy
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.crypto.hex
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Where the vault's wrap key lives (crypto spec §10.2): import a software-made AES-256 key under a
 * fresh alias with the vault's auth policy, hand out a decrypt [Cipher] for it, list and delete the
 * aliases. [HistoryKeyVault] is written against this interface so its logic runs in JVM tests with a
 * software stand-in; [AndroidVaultKeyStore] is the real one.
 */
interface VaultKeyStore {
    /** Where an imported key ended up (`"security"` in the vault record, crypto §10.2, P3c). */
    enum class Security(val wire: String) {
        StrongBox("strongbox"),
        Tee("tee"),
        Software("software"),
        ;

        companion object {
            fun fromWire(value: String?): Security? = entries.firstOrNull { it.wire == value }
        }
    }

    class Imported(val alias: String, val security: Security)

    /**
     * The record's protection marker for keys this store imports: 1 = per-use user presence
     * (iOS `currentProtectionMarker`, `HistoryKeyVault.swift:20-23`); anything else marks a vault the
     * next unlock re-wraps (`isWrapKeyProtected`).
     */
    val protectionMarker: Int

    /**
     * Imports the 32-byte [wrapKey] under a new alias. Never prompts (iOS stores without Face ID,
     * `HistoryKeyVault.swift:59-75`). The caller zeroes [wrapKey]. Throws on any failure.
     */
    fun import(wrapKey: ByteArray): Imported

    /**
     * An AES-GCM cipher in decrypt mode for [alias] and the 12-byte [iv] — for a per-use auth key
     * it works only after the prompt authenticated it. `Cipher.init` notices an invalidated key
     * without any prompt, which is how `HistoryKeyVault.state` probes.
     *
     * @throws VaultError.KeyInvalidated when the alias is gone or the key was permanently invalidated
     *   (new biometric enrolment, screen lock removed; crypto §10.3 steps 3–4).
     * @throws VaultError.Keystore for any other Keystore failure.
     */
    fun decryptCipher(alias: String, iv: ByteArray): Cipher

    /** Every alias this store owns. */
    fun aliases(): List<String>

    /** Deletes [alias]; never needs authentication. */
    fun delete(alias: String)
}

/**
 * How [AndroidVaultKeyStore] protects an imported key. Production uses [UserPresence]; the
 * instrumented round-trip tests supply one without authentication so they run unattended (crypto
 * spec §16.1, *HistoryKeyVaultTests*).
 */
interface VaultKeyPolicy {
    /** Written to the vault record as `protection` ([VaultKeyStore.protectionMarker]). */
    val protectionMarker: Int

    fun configure(builder: KeyProtection.Builder): KeyProtection.Builder

    /**
     * iOS `.userPresence` + `WhenPasscodeSetThisDeviceOnly` (`HistoryKeyVault.swift:567-579`):
     * authentication per use (`CryptoObject`) by a strong biometric or the screen lock, invalidated
     * by a new biometric enrolment and by removing the screen lock (crypto §10.2, §11.1).
     */
    object UserPresence : VaultKeyPolicy {
        override val protectionMarker: Int = 1

        override fun configure(builder: KeyProtection.Builder): KeyProtection.Builder = builder
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(true)
    }
}

/**
 * [VaultKeyStore] on the AndroidKeyStore (crypto spec §10.2; plan decision P3b "import").
 *
 * Aliases rotate: `<aliasPrefix><8 random hex>` (`shroud.vault.wrap.3f9a0c1d`). StrongBox is tried
 * first when [preferStrongBox] says the phone has one, and any failure falls back to the TEE (some
 * phones throw other exceptions than `StrongBoxUnavailableException`). After the import the key's
 * `KeyInfo` says where it ended up ([VaultKeyStore.Security]); a software-only Keystore is allowed
 * and recorded (P3c — the lock screen and Privacy and Security tell the user, W3).
 *
 * `SecretKeySpec` and the provider keep their own copies of the imported bytes for the length of
 * the import call; nothing here can wipe those (unreachable garbage afterwards).
 */
class AndroidVaultKeyStore(
    private val policy: VaultKeyPolicy = VaultKeyPolicy.UserPresence,
    private val preferStrongBox: () -> Boolean = { false },
    private val aliasPrefix: String = ALIAS_PREFIX,
    private val entropy: Entropy = SystemEntropy,
) : VaultKeyStore {
    override val protectionMarker: Int get() = policy.protectionMarker

    override fun import(wrapKey: ByteArray): VaultKeyStore.Imported {
        require(wrapKey.size == 32) { "the wrap key is 32 bytes" }
        val alias = aliasPrefix + entropy.bytes(4).hex()
        val keyStore = keyStore()
        var strongBox = preferStrongBox()
        try {
            importInto(keyStore, alias, wrapKey, strongBox)
        } catch (e: Exception) {
            runCatching { keyStore.deleteEntry(alias) }
            if (!strongBox) throw e
            strongBox = false
            importInto(keyStore, alias, wrapKey, strongBox = false)
        }
        return VaultKeyStore.Imported(alias, security(keyStore, alias, strongBox))
    }

    override fun decryptCipher(alias: String, iv: ByteArray): Cipher {
        val key = try {
            keyStore().getKey(alias, null) as? SecretKey
        } catch (_: UnrecoverableKeyException) {
            throw VaultError.KeyInvalidated
        } catch (e: GeneralSecurityException) {
            throw VaultError.Keystore(e.javaClass.simpleName, e)
        } ?: throw VaultError.KeyInvalidated
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw VaultError.KeyInvalidated
        } catch (e: GeneralSecurityException) {
            throw VaultError.Keystore(e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            throw VaultError.Keystore(e.javaClass.simpleName, e)
        }
    }

    override fun aliases(): List<String> = keyStore().aliases().toList().filter { it.startsWith(aliasPrefix) }.sorted()

    override fun delete(alias: String) {
        if (!alias.startsWith(aliasPrefix)) return
        keyStore().deleteEntry(alias)
    }

    private fun importInto(keyStore: KeyStore, alias: String, wrapKey: ByteArray, strongBox: Boolean) {
        val protection = policy.configure(
            KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE),
        ).setIsStrongBoxBacked(strongBox).build()
        keyStore.setEntry(alias, KeyStore.SecretKeyEntry(SecretKeySpec(wrapKey, "AES")), protection)
    }

    private fun security(keyStore: KeyStore, alias: String, strongBox: Boolean): VaultKeyStore.Security = try {
        val key = keyStore.getKey(alias, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> VaultKeyStore.Security.StrongBox
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> VaultKeyStore.Security.Tee
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> VaultKeyStore.Security.Software
                else -> if (strongBox) VaultKeyStore.Security.StrongBox else VaultKeyStore.Security.Tee
            }
        } else {
            @Suppress("DEPRECATION")
            val hardware = info.isInsideSecureHardware
            when {
                !hardware -> VaultKeyStore.Security.Software
                strongBox -> VaultKeyStore.Security.StrongBox
                else -> VaultKeyStore.Security.Tee
            }
        }
    } catch (_: Exception) {
        if (strongBox) VaultKeyStore.Security.StrongBox else VaultKeyStore.Security.Tee
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        /** Production aliases: `shroud.vault.wrap.<hex8>` (crypto spec §11.2). */
        const val ALIAS_PREFIX = "shroud.vault.wrap."

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
