package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.OneTimePreKeyDto
import de.corespace.shroud.core.net.PutKeyBundleRequest
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.net.SignedPreKeyDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed class CryptoException(message: String) : Exception(message) {
    /** The phrase derives a different identity key from the one the account published. */
    class PhraseDoesNotMatchAccount : CryptoException("That phrase doesn’t match this account.")

    /** No screen lock: the history key could not be protected on this phone. */
    class NoScreenLock : CryptoException("Set a screen lock to use Shroud.")

    /** The app went to the background while the keys were being derived; they were dropped. */
    class LockedWhileUnlocking : CryptoException("Shroud was locked while unlocking. Try again.")
}

/**
 * Messaging keys (`CryptoController.swift`). Holds the phrase-derived [IdentityKeyMaterial] in
 * memory while messaging is unlocked.
 *
 * Not yet here: the Keystore history-key vault (android-plan.md, decision 1). Until it lands,
 * nothing derived from the phrase is written to disk, so every start of the app — and every
 * return from the background (invariant 5) — asks for the phrase again, and each unlock publishes
 * a fresh signed prekey and one-time prekeys, the iOS "re-establish" path.
 */
class CryptoController(private val api: ShroudApi, private val bip39: Bip39) {
    private var material: IdentityKeyMaterial? = null

    /** Bumped by [lock]; an unlock that started before a lock does not adopt its keys. */
    @Volatile private var lockGeneration = 0
    private val unlocked = MutableStateFlow<String?>(null)

    /** The account whose keys are in memory, or null while locked. */
    val unlockedUserId: StateFlow<String?> = unlocked.asStateFlow()

    /**
     * Sign Up: derive the keys and publish the bundle. The phrase never leaves the phone. Refuses
     * to replace keys the account already published with a different phrase's.
     */
    suspend fun establishFromSignup(words: List<String>, session: Session) {
        val generation = lockGeneration
        val established = derive(words, session.userId)
        try {
            rejectPhraseThatIsNotTheAccountKey(established, session)
            publish(established, session.token)
        } catch (e: Throwable) {
            established.wipe()
            throw e
        }
        adopt(established, generation)
    }

    /**
     * Log In, phrase step. A later device must derive the key the account already published;
     * `KEYS_REQUIRED` means this is the account's first device.
     */
    suspend fun unlockWithPhrase(words: List<String>, session: Session) {
        val generation = lockGeneration
        val established = derive(words, session.userId)
        try {
            rejectPhraseThatIsNotTheAccountKey(established, session)
            publish(established, session.token)
        } catch (e: Throwable) {
            established.wipe()
            throw e
        }
        adopt(established, generation)
    }

    /** Drops the keys from memory (backgrounding, Log Out). */
    @Synchronized
    fun lock() {
        lockGeneration++
        material?.wipe()
        material = null
        unlocked.value = null
    }

    private suspend fun derive(words: List<String>, userId: String): IdentityKeyMaterial =
        withContext(Dispatchers.Default) {
            val validated = bip39.validate(words)
            IdentityKeyMaterial.establish(bip39, validated, userId)
        }

    private suspend fun rejectPhraseThatIsNotTheAccountKey(established: IdentityKeyMaterial, session: Session) {
        try {
            val published = api.identityKey(session.token, session.userId)
            val key = runCatching { java.util.Base64.getDecoder().decode(published.identityKey.trim()) }.getOrNull()
            if (key == null || !key.contentEquals(established.agreementPublic)) {
                throw CryptoException.PhraseDoesNotMatchAccount()
            }
        } catch (e: ApiError.Server) {
            if (e.code != ErrorCodes.KEYS_REQUIRED) throw e
        }
    }

    private suspend fun publish(material: IdentityKeyMaterial, token: String) {
        api.putKeyBundle(token, bundleRequest(material))
    }

    /** Keeps the keys unless the app locked (went to the background) while they were derived. */
    @Synchronized
    private fun adopt(established: IdentityKeyMaterial, generation: Int) {
        if (generation != lockGeneration) {
            established.wipe()
            throw CryptoException.LockedWhileUnlocking()
        }
        material?.wipe()
        material = established
        unlocked.value = established.userId
    }

    companion object {
        fun bundleRequest(material: IdentityKeyMaterial) = PutKeyBundleRequest(
            registrationId = material.registrationId,
            identityKey = b64(material.agreementPublic),
            signedPreKey = SignedPreKeyDto(material.signedPreKeyId, b64(material.signedPreKeyPublic), b64(material.signedPreKeySignature)),
            oneTimePreKeys = material.oneTimePreKeys.map { OneTimePreKeyDto(it.keyId, b64(it.publicKey)) },
        )

        /** Standard Base64 with padding, no line breaks — what the server's `STANDARD` decodes. */
        fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

        fun userMessage(error: Throwable): String = when (error) {
            is Bip39.PhraseException.InvalidWordCount -> "Enter all 12 words of your encryption phrase."
            is Bip39.PhraseException.UnknownWord -> "One or more words are not in the recovery word list."
            is Bip39.PhraseException.InvalidChecksum -> "That phrase isn’t valid. Check the words and order."
            is CryptoException -> error.message ?: "Could not unlock encryption. Try again."
            is ApiError -> error.userMessage
            else -> "Could not unlock encryption. Try again."
        }
    }
}
