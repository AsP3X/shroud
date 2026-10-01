package de.corespace.shroud.core.keys

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A JVM [VaultKeyStore]: imported wrap keys live in a map and [decryptCipher] hands out a plain JCA
 * cipher, so `HistoryKeyVault`'s logic runs without the AndroidKeyStore (crypto spec §16.1: the
 * Keystore itself is covered by the instrumented tests). [invalidate] plays a new fingerprint
 * enrolment or a screen-lock change; [importFailure] a Keystore that refuses the key.
 */
class SoftwareVaultKeyStore(
    var security: VaultKeyStore.Security = VaultKeyStore.Security.Tee,
    override val protectionMarker: Int = 1,
) : VaultKeyStore {
    private val keys = LinkedHashMap<String, ByteArray>()
    private val invalid = HashSet<String>()
    private var counter = 0

    /** Thrown by the next imports while set. */
    var importFailure: Exception? = null

    /** Called at the start of every [import] — a test can drop the screen lock "meanwhile". */
    var onImport: () -> Unit = {}

    /** Every cipher handed out, in order (fresh-cipher assertions). */
    val ciphers = mutableListOf<Cipher>()

    @Synchronized
    override fun import(wrapKey: ByteArray): VaultKeyStore.Imported {
        onImport()
        importFailure?.let { throw it }
        val alias = "shroud.vault.wrap.%08x".format(++counter)
        keys[alias] = wrapKey.copyOf()
        return VaultKeyStore.Imported(alias, security)
    }

    @Synchronized
    override fun decryptCipher(alias: String, iv: ByteArray): Cipher {
        val key = keys[alias]
        if (key == null || alias in invalid) throw VaultError.KeyInvalidated
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }.also { ciphers += it }
    }

    @Synchronized
    override fun aliases(): List<String> = keys.keys.sorted()

    @Synchronized
    override fun delete(alias: String) {
        keys.remove(alias)
        invalid.remove(alias)
    }

    /** The key under [alias] is permanently invalidated (it stays listed, like a Keystore alias). */
    @Synchronized
    fun invalidate(alias: String) {
        invalid += alias
    }
}

/**
 * A scripted [VaultAuthenticator]: each call takes the next [Step] (the last one repeats). Records
 * the cipher and method of every prompt, so tests can check "one retry, with a fresh cipher".
 */
class FakeVaultAuthenticator(vararg steps: Step) : VaultAuthenticator {
    sealed interface Step {
        /** The user authenticated. */
        data object Succeed : Step

        /** The system prompt ended with `BIOMETRIC_ERROR_*` [code]. */
        data class Error(val code: Int) : Step

        /** The authenticator threw [error] itself (no host activity, a broken prompt). */
        data class Throw(val error: Exception) : Step

        /** The prompt stays up until cancelled (the 90 s limit). */
        data object Hang : Step

        /** The prompt stays up until [release] completes, then succeeds. */
        class Gate(val release: CompletableDeferred<Unit> = CompletableDeferred()) : Step
    }

    private val script = ArrayDeque(steps.toList().ifEmpty { listOf(Step.Succeed) })

    val prompts = mutableListOf<Pair<Cipher, UnlockMethod>>()

    /** Prompts dismissed by cancellation (the vault's timeout, a cancelled unlock). */
    var cancelled = 0
        private set

    /** Completed once the first prompt is up. */
    val shown = CompletableDeferred<Unit>()

    override suspend fun authenticate(cipher: Cipher, method: UnlockMethod): Cipher {
        val step = synchronized(this) {
            prompts += cipher to method
            if (script.size > 1) script.removeFirst() else script.first()
        }
        shown.complete(Unit)
        return when (step) {
            Step.Succeed -> cipher
            is Step.Error -> throw BiometricPromptError(step.code)
            is Step.Throw -> throw step.error
            Step.Hang -> try {
                awaitCancellation()
            } finally {
                cancelled++
            }
            is Step.Gate -> {
                step.release.await()
                cipher
            }
        }
    }
}
