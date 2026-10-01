package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.crypto.CryptoJson
import de.corespace.shroud.core.crypto.Entropy
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How the user proves presence before the vault opens (`HistoryKeyVault.UnlockMethod`, `HistoryKeyVault.swift:37-43`). */
enum class UnlockMethod {
    /** A strong biometric first; the system offers the screen lock as well. */
    BiometryPreferred,

    /** The screen lock (PIN, pattern, password) only. */
    PasscodeOnly,
}

/**
 * What the lock screen shows before any tap (Android-only, crypto spec §10.3; settings-lock §11.2):
 * *Locked* ([Ready]), *Phrase needed* ([NotFound] with an identity, [KeyInvalidated]) or
 * *Set a screen lock* ([NoScreenLock]).
 */
enum class VaultState { Ready, NotFound, KeyInvalidated, NoScreenLock }

/**
 * Vault errors (`HistoryKeyVault.VaultError`, `HistoryKeyVault.swift:25-35`; crypto spec §10.6).
 * `CryptoController.userMessage` turns each into its Android copy. No case carries key bytes.
 */
sealed class VaultError(message: String, cause: Throwable? = null) : CryptoException(message, cause) {
    data object SealFailed : VaultError("vault seal failed")

    data object OpenFailed : VaultError("vault open failed")

    /** A Keystore or prompt failure; [detail] is an error code or exception class name, never key material. */
    class Keystore(val detail: Any?, cause: Throwable? = null) : VaultError("keystore failure", cause)

    data object UserCancelled : VaultError("authentication cancelled")

    data object NotFound : VaultError("no vault for this account")

    /** Our 90 s limit, or the system's own prompt timeout. */
    data object TimedOut : VaultError("authentication timed out")

    /** No screen lock: the history key cannot be protected and chats stay shut. */
    data object PasscodeNotSet : VaultError("no screen lock")

    /**
     * Android-only: a new biometric enrolment or a screen-lock change killed the wrap key (or the
     * Keystore lost it). The vault is cleared; the phrase restores it (crypto §10.3, §12.2).
     */
    data object KeyInvalidated : VaultError("vault key invalidated")
}

/**
 * Proves the user's presence for a per-use auth key (crypto spec §10.4): shows the system prompt
 * for [cipher] and returns the authenticated cipher. Fails with [BiometricPromptError] carrying the
 * system's error code, or a [VaultError]. Cancelling the coroutine dismisses the prompt.
 */
fun interface VaultAuthenticator {
    suspend fun authenticate(cipher: Cipher, method: UnlockMethod): Cipher
}

/** The system prompt ended with `BiometricPrompt.BIOMETRIC_ERROR_*` [code] (mapped by [HistoryKeyVault.classifyPromptError]). */
class BiometricPromptError(val code: Int) : Exception("biometric prompt error $code", null, false, false)

/**
 * The phrase-derived history key, wrapped so the phone's storage alone cannot open the chats
 * (iOS `HistoryKeyVault`, `ios/shroud/Services/Crypto/HistoryKeyVault.swift:6-611`; crypto spec §10;
 * android-plan decision 1, P3b; architecture invariant 5).
 *
 * - **Wrap key**: 32 random bytes from [entropy], imported into the AndroidKeyStore by [keys] with
 *   per-use authentication (strong biometric or screen lock), invalidated by new biometric enrolment
 *   and by removing the screen lock — iOS `.userPresence` + `WhenPasscodeSetThisDeviceOnly`.
 *   Imported (not generated) so storing needs no prompt, exactly like iOS (`:59-75`).
 * - **Record** `keys/history-vault.v1` ([record], WhenUnlocked sealer):
 *   `{"v":1,"user_id":"…","alias":"shroud.vault.wrap.3f9a0c1d","blob":"<b64 60 B>","protection":1,"security":"tee"}`
 *   with `blob = nonce(12) ‖ AES-256-GCM(wrapKey, historyKey, aad = "shroud-history-vault-v1:" + userId.lowercase())`.
 *   The AAD binds the blob to the account (Android-only hardening, local format).
 *
 * No screen lock means no vault: [store] and [unlock] throw [VaultError.PasscodeNotSet] and there is
 * no unprotected fallback, ever (`:486-507`). One prompt at a time — `CryptoController` coalesces
 * (`vaultUnlockInFlight`). Never logs keys, blobs or ids.
 */
class HistoryKeyVault(
    private val record: SealedFile,
    private val keys: VaultKeyStore,
    private val isDeviceSecure: () -> Boolean,
    private val authenticator: VaultAuthenticator,
    private val seal: StorageSeal,
    private val entropy: Entropy = SystemEntropy,
    private val authTimeout: Duration = AUTH_TIMEOUT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Serializable
    private data class Record(
        val v: Int,
        @kotlinx.serialization.SerialName("user_id") val userId: String,
        val alias: String,
        val blob: String,
        val protection: Int,
        val security: String? = null,
    )

    /** The device has a screen lock, so the wrap key can be gated (`canProtectWrapKey`, `:470-475`). */
    val canProtectWrapKey: Boolean get() = isDeviceSecure()

    /**
     * The stored wrap key carries the current protection (`isWrapKeyProtected`, `:461-468`). Every
     * Android record is written protected, so `CryptoController`'s re-wrap never fires today; the hook
     * stays for a future policy change (crypto §10.2).
     */
    val isWrapKeyProtected: Boolean get() = readRecord()?.protection == PROTECTED

    /** Where the wrap key lives (`"security"`), null without a vault — for the software-only notice (P3c, W3). */
    fun keySecurity(): VaultKeyStore.Security? = VaultKeyStore.Security.fromWire(readRecord()?.security)

    /** A vault for [userId] is stored (`hasBlob`, `:477-484`). Never prompts. */
    fun hasBlob(userId: String): Boolean = vaultFor(userId) != null

    /**
     * The lock screen's mode before any tap (crypto §10.3): [VaultState.NoScreenLock] >
     * [VaultState.NotFound] (no vault for this account) > [VaultState.KeyInvalidated]
     * (`Cipher.init` refuses without authentication) > [VaultState.Ready]. A read-only probe: it
     * never prompts and never clears; any other Keystore failure is left for the unlock to report.
     */
    fun state(userId: String): VaultState {
        if (!isDeviceSecure()) return VaultState.NoScreenLock
        val vault = vaultFor(userId) ?: return VaultState.NotFound
        return try {
            keys.decryptCipher(vault.alias, vault.blob.copyOfRange(0, NONCE_BYTES))
            VaultState.Ready
        } catch (_: VaultError.KeyInvalidated) {
            VaultState.KeyInvalidated
        } catch (_: Exception) {
            VaultState.Ready
        }
    }

    /**
     * Seals [historyKey] (32 bytes) for [userId] under a fresh wrap key — always rotating, never
     * prompting (`store`, `:59-75`; crypto §10.5). Imports the new alias, writes the record, **then**
     * deletes the older aliases, so an interrupted store keeps the previous vault. A no-op while a
     * wipe runs. Blocking (Keystore, disk): call off the main thread.
     *
     * @throws VaultError.PasscodeNotSet without a screen lock (checked again after the import: the
     *   lock can vanish in between, `:496-499`).
     * @throws VaultError.SealFailed / [VaultError.Keystore] when sealing, importing or writing fails.
     */
    fun store(historyKey: ByteArray, userId: String) {
        require(historyKey.size == KEY_BYTES) { "the history key is 32 bytes" }
        if (seal.isSealed) return
        if (!isDeviceSecure()) throw VaultError.PasscodeNotSet
        val wrap = entropy.bytes(KEY_BYTES)
        val blob: ByteArray
        val imported: VaultKeyStore.Imported
        try {
            blob = try {
                Primitives.aesGcmSeal(wrap, entropy.bytes(NONCE_BYTES), historyKey, aad(userId))
            } catch (_: CryptoError) {
                throw VaultError.SealFailed
            }
            imported = try {
                keys.import(wrap)
            } catch (e: Exception) {
                throw if (!isDeviceSecure()) VaultError.PasscodeNotSet else VaultError.Keystore(e.javaClass.simpleName, e)
            }
        } finally {
            wrap.fill(0)
        }
        if (!isDeviceSecure()) {
            runCatching { keys.delete(imported.alias) }
            throw VaultError.PasscodeNotSet
        }
        val stored = Record(VERSION, userId, imported.alias, B64.encode(blob), keys.protectionMarker, imported.security.wire)
        try {
            if (seal.isSealed) throw IllegalStateException("sealed")
            record.write(utf8(CryptoJson.encodeToString(Record.serializer(), stored)))
        } catch (e: Exception) {
            runCatching { keys.delete(imported.alias) }
            if (seal.isSealed) return
            throw VaultError.Keystore(e.javaClass.simpleName, e)
        }
        for (alias in runCatching { keys.aliases() }.getOrDefault(emptyList())) {
            if (alias != imported.alias) runCatching { keys.delete(alias) }
        }
    }

    /**
     * The history key of [userId], after the user proved presence with [method] (`unlock`,
     * `:109-151`; crypto §10.3). The caller owns and zeroes the result.
     *
     * Order: screen lock set ([VaultError.PasscodeNotSet]) → a vault for this account with a 60-byte
     * blob ([VaultError.NotFound]) → the wrap key's cipher (a vanished or invalidated key clears the
     * vault: [VaultError.KeyInvalidated]) → the prompt, raced against [authTimeout] (90 s, `:45-48`;
     * [VaultError.TimedOut], the prompt is dismissed) → open the blob after authentication, 32 bytes
     * or [VaultError.OpenFailed].
     *
     * Prompt errors map through [classifyPromptError]: with [UnlockMethod.BiometryPreferred], a
     * biometric that is locked out, missing or broken retries **once** with
     * [UnlockMethod.PasscodeOnly] and a freshly initialised cipher (the first operation was bound to
     * the failed challenge) — iOS falls through to the passcode sheet (`:267-316`).
     */
    suspend fun unlock(userId: String, method: UnlockMethod = UnlockMethod.BiometryPreferred): ByteArray {
        if (!isDeviceSecure()) throw VaultError.PasscodeNotSet
        val vault = withContext(io) { vaultFor(userId) } ?: throw VaultError.NotFound
        val authenticated = withTimeoutOrNull(authTimeout) { authenticate(vault, method) } ?: throw VaultError.TimedOut
        return withContext(io) { open(authenticated, vault.blob, userId) }
    }

    /** Deletes the record and every wrap-key alias (`clear`, `:454-459`). Never needs authentication. */
    fun clear() {
        record.delete()
        for (alias in runCatching { keys.aliases() }.getOrDefault(emptyList())) runCatching { keys.delete(alias) }
    }

    private class Vault(val alias: String, val blob: ByteArray)

    private suspend fun authenticate(vault: Vault, method: UnlockMethod): Cipher {
        val iv = vault.blob.copyOfRange(0, NONCE_BYTES)
        val first = cipherFor(vault.alias, iv)
        return try {
            authenticator.authenticate(first, method)
        } catch (e: BiometricPromptError) {
            when (val outcome = classifyPromptError(e.code, method)) {
                is PromptOutcome.Fail -> throw outcome.error
                PromptOutcome.RetryWithPasscode -> {
                    val fresh = cipherFor(vault.alias, iv)
                    try {
                        authenticator.authenticate(fresh, UnlockMethod.PasscodeOnly)
                    } catch (again: BiometricPromptError) {
                        when (val second = classifyPromptError(again.code, UnlockMethod.PasscodeOnly)) {
                            is PromptOutcome.Fail -> throw second.error
                            PromptOutcome.RetryWithPasscode -> throw VaultError.Keystore(again.code)
                        }
                    }
                }
            }
        }
    }

    /** The wrap key's cipher; a vanished or invalidated key clears the vault first (crypto §10.3 steps 3–4). */
    private suspend fun cipherFor(alias: String, iv: ByteArray): Cipher = withContext(io) {
        try {
            keys.decryptCipher(alias, iv)
        } catch (e: VaultError.KeyInvalidated) {
            clear()
            throw e
        }
    }

    private fun open(cipher: Cipher, blob: ByteArray, userId: String): ByteArray {
        val plain = try {
            cipher.updateAAD(aad(userId))
            cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
        } catch (_: AEADBadTagException) {
            throw VaultError.OpenFailed
        } catch (e: GeneralSecurityException) {
            throw VaultError.Keystore(e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            throw VaultError.Keystore(e.javaClass.simpleName, e)
        }
        if (plain.size != KEY_BYTES) {
            plain.fill(0)
            throw VaultError.OpenFailed
        }
        return plain
    }

    private fun readRecord(): Record? {
        val read = record.readClassified() as? RecordRead.Found ?: return null
        return try {
            CryptoJson.decodeFromString(Record.serializer(), read.bytes.decodeToString())
        } catch (_: Exception) {
            null
        } finally {
            read.bytes.fill(0)
        }
    }

    /** The vault of [userId] (case-insensitive, `:88-91`) with a well-formed blob, or null. */
    private fun vaultFor(userId: String): Vault? {
        val stored = readRecord() ?: return null
        if (!stored.userId.equals(userId, ignoreCase = true)) return null
        val blob = B64.decodeStrict(stored.blob)?.takeIf { it.size == BLOB_BYTES } ?: return null
        return Vault(stored.alias, blob)
    }

    /** What a prompt error code means for the unlock (crypto spec §10.4 table). */
    sealed interface PromptOutcome {
        class Fail(val error: VaultError) : PromptOutcome

        /** Biometry unusable this time: one retry with the screen lock and a fresh cipher. */
        data object RetryWithPasscode : PromptOutcome
    }

    companion object {
        /** iOS `biometryAuthenticationTimeout` / `passcodeAuthenticationTimeout` (`HistoryKeyVault.swift:45-48`). */
        val AUTH_TIMEOUT: Duration = 90.seconds

        const val VERSION = 1
        const val PROTECTED = 1
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12

        /** `nonce (12) ‖ ciphertext (32) ‖ tag (16)`. */
        const val BLOB_BYTES = 60

        // android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_* (values fixed by the platform API).
        const val ERROR_HW_UNAVAILABLE = 1
        const val ERROR_UNABLE_TO_PROCESS = 2
        const val ERROR_TIMEOUT = 3
        const val ERROR_CANCELED = 5
        const val ERROR_LOCKOUT = 7
        const val ERROR_VENDOR = 8
        const val ERROR_LOCKOUT_PERMANENT = 9
        const val ERROR_USER_CANCELED = 10
        const val ERROR_NO_BIOMETRICS = 11
        const val ERROR_HW_NOT_PRESENT = 12
        const val ERROR_NEGATIVE_BUTTON = 13
        const val ERROR_NO_DEVICE_CREDENTIAL = 14
        const val ERROR_SECURITY_UPDATE_REQUIRED = 15

        /** The blob's AAD: `shroud-history-vault-v1:` + the lower-case user id (crypto §10.2). */
        fun aad(userId: String): ByteArray = utf8("shroud-history-vault-v1:" + userId.lowercase())

        /**
         * Maps a prompt error (crypto spec §10.4, mirroring `HistoryKeyVault.swift:245-316`):
         * user / system cancel and the (never shown) negative button → [VaultError.UserCancelled];
         * no screen lock → [VaultError.PasscodeNotSet]; the system's own timeout →
         * [VaultError.TimedOut]; lockout, no or broken biometric hardware, unable to process,
         * vendor and security-update errors → one passcode retry with [UnlockMethod.BiometryPreferred],
         * [VaultError.Keystore] with [UnlockMethod.PasscodeOnly]; anything else → [VaultError.Keystore].
         * Our own 90 s timeout never gets here: it cancels the coroutine first.
         */
        fun classifyPromptError(code: Int, method: UnlockMethod): PromptOutcome = when (code) {
            ERROR_USER_CANCELED, ERROR_CANCELED, ERROR_NEGATIVE_BUTTON -> PromptOutcome.Fail(VaultError.UserCancelled)
            ERROR_NO_DEVICE_CREDENTIAL -> PromptOutcome.Fail(VaultError.PasscodeNotSet)
            ERROR_TIMEOUT -> PromptOutcome.Fail(VaultError.TimedOut)
            ERROR_LOCKOUT, ERROR_LOCKOUT_PERMANENT, ERROR_HW_UNAVAILABLE, ERROR_NO_BIOMETRICS, ERROR_HW_NOT_PRESENT,
            ERROR_UNABLE_TO_PROCESS, ERROR_VENDOR, ERROR_SECURITY_UPDATE_REQUIRED,
            ->
                if (method == UnlockMethod.BiometryPreferred) PromptOutcome.RetryWithPasscode else PromptOutcome.Fail(VaultError.Keystore(code))
            else -> PromptOutcome.Fail(VaultError.Keystore(code))
        }
    }
}
