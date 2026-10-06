package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.keys.HistoryKeyVault
import de.corespace.shroud.core.keys.IdentityKeyStore
import de.corespace.shroud.core.keys.IdentityPresence
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.keys.UnlockMethod
import de.corespace.shroud.core.keys.VaultError
import de.corespace.shroud.core.keys.VaultKeyStore
import de.corespace.shroud.core.keys.VaultState
import de.corespace.shroud.core.model.userUuid
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.LimitDeviceDto
import de.corespace.shroud.core.net.OneTimePreKeyDto
import de.corespace.shroud.core.net.PutKeyBundleRequest
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.net.SignedPreKeyDto
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Failures of the unlock flows (iOS `CryptoControllerError`, `ios/shroud/Services/Crypto/CryptoController.swift:345-351`,
 * plus Android's own). [CryptoController.userMessage] turns each into its copy. Open so the vault's
 * errors (`core/keys/VaultError`) are crypto exceptions too.
 */
abstract class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause, false, false) {
    /** The phrase derives a different identity key from the one the account published (or the stored shell). */
    class PhraseDoesNotMatchAccount : CryptoException("That phrase doesn’t match this account on this device.")

    /** No screen lock: the history key could not be protected on this phone. */
    class NoScreenLock : CryptoException("Set a screen lock to use Shroud.")

    /** Android-only: chats locked (the app left the foreground) while the keys were being derived or opened; they were dropped. */
    class LockedWhileUnlocking : CryptoException("Shroud was locked while unlocking. Try again.")

    /** `notUnlocked`. */
    class NotUnlocked : CryptoException("Unlock messaging with your encryption phrase first.")

    /** `historyLocked`, Android wording (P14). */
    class HistoryLocked : CryptoException("Unlock with your fingerprint or screen lock to open chats.")

    /** `localDataMissing`: a session without the identity on this phone (wiped, incomplete login). */
    class LocalDataMissing : CryptoException("Local encryption data is missing. Sign in or create an account again.")
}

/**
 * Messaging keys on this phone (iOS `CryptoController`, `ios/shroud/Services/Crypto/CryptoController.swift:4-343`;
 * crypto spec §12). Session token ≠ crypto unlock: the phrase derives the keys, and the
 * phrase-derived history key is vaulted behind the fingerprint / screen lock after the first unlock,
 * so a relaunch needs the screen lock, not the phrase, and publishes nothing.
 *
 * - Holds the [IdentityKeyMaterial] in memory while chats are unlocked; [withMaterial] lends it under
 *   a read lock, [lock] wipes it under the write lock (crypto §1.6). Every change of the material
 *   drives [SealedLocalState] (`material.didSet`, `:13-21`).
 * - Writes the [IdentityKeyStore] and the [HistoryKeyVault]; calls the key-bundle routes of [api].
 * - A lock generation guards every adopt: an unlock that started before a [lock] never adopts its
 *   keys ([CryptoException.LockedWhileUnlocking]) — the Android form of iOS's "no await between
 *   opening and setting the material" (`:102-104`).
 * - While a wipe runs ([storageSeal], crypto §14) nothing is persisted, published or adopted; iOS
 *   gets this by halting its controllers first (`DeviceWipeController.swift:68-81`).
 * - [vaultPromptInFlight] is true while the system prompt is up, so the shell's background lock can
 *   leave an unlock that is completing alone (crypto §10.7, settings-lock §11.5).
 *
 * State flows are written with equality guards (plan §1.1 rule 3). CPU work runs on [compute], disk
 * and Keystore on [io]; every hop that produces a key goes through [withContextHandingOver], so a
 * caller cancelled meanwhile never leaves it unwiped on the heap. Never logs the phrase, keys or ids.
 */
class CryptoController(
    private val api: ShroudApi,
    private val bip39: Bip39,
    private val identityStore: IdentityKeyStore,
    private val vault: HistoryKeyVault,
    private val sealedLocalState: SealedLocalState,
    private val storageSeal: StorageSeal,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val rw = ReentrantReadWriteLock()
    private var material: IdentityKeyMaterial? = null // guarded by rw

    /** Bumped by every lock; an unlock that started before it does not adopt its keys. Guarded by rw. */
    private var lockGeneration = 0L

    private val unlocked = MutableStateFlow<String?>(null)
    private val needsUnlock = MutableStateFlow(false)
    private val lastError = MutableStateFlow<String?>(null)
    private val promptInFlight = MutableStateFlow(false)
    private val vaultUnlockInFlight = AtomicBoolean(false)

    /** After a cancelled automatic prompt, automatic prompts stop until the next lock (`:44-46`). Manual unlocks always work. */
    @Volatile private var suppressAutomaticVaultPrompt = false

    /** The account whose keys are in memory, or null while locked. */
    val unlockedUserId: StateFlow<String?> = unlocked.asStateFlow()

    /** True when an identity is stored for the account but the vault has not been opened (`:41-42`). */
    val needsHistoryUnlock: StateFlow<Boolean> = needsUnlock.asStateFlow()

    /** The last unlock failure for a toast, null after a success (`:59-60`). */
    val lastUnlockErrorMessage: StateFlow<String?> = lastError.asStateFlow()

    /** The vault's system prompt is up (crypto §10.7): the shell must not lock chats for a stop it causes. */
    val vaultPromptInFlight: StateFlow<Boolean> = promptInFlight.asStateFlow()

    val isUnlocked: Boolean get() = rw.read { material != null }

    /**
     * Runs [block] with the unlocked material under the read lock; null while locked. Never let a
     * key array escape [block] — derive or copy inside it.
     */
    fun <T> withMaterial(block: (IdentityKeyMaterial) -> T): T? {
        rw.read {
            val m = material ?: return null
            return block(m)
        }
    }

    /**
     * True when this phone holds the account's identity (`hasLocalIdentity`, `:28-34`). False after a
     * wipe or an incomplete login — and while the phone is locked; use [identityPresence] before
     * ending a session. Reads the record (Keystore): cheap, but off the hot path.
     */
    fun hasLocalIdentity(userId: String): Boolean = identityStore.hasIdentity(userId)

    /** [IdentityPresence.Unavailable] while the phone is locked — never a wiped identity (`:36-39`). */
    fun identityPresence(userId: String): IdentityPresence = identityStore.presence(userId)

    /** The lock screen's mode before any tap (Android-only, crypto §10.3). */
    fun vaultState(userId: String): VaultState = vault.state(userId)

    /**
     * Where the history-key wrap key lives (`HistoryKeyVault.keySecurity`, crypto spec §10.2, P3c).
     * Null when this phone has no vault. Read on [io]: the lock screen must not do that file read
     * on the main thread. Never prompts.
     */
    suspend fun vaultKeySecurity(): VaultKeyStore.Security? = withContext(io) { vault.keySecurity() }

    /**
     * Re-opens the chats through the vault (`unlockHistoryIfPossible`, `:62-146`; crypto §12.2): one
     * system prompt per call, coalesced (a second call while one runs returns [isUnlocked]). Returns
     * whether the keys are in memory; a failure sets [lastUnlockErrorMessage] and [needsHistoryUnlock].
     *
     * - no identity on this phone → `localDataMissing`, not locked-but-recoverable;
     * - cancelled / timed out → [needsHistoryUnlock] stays true;
     * - [VaultError.KeyInvalidated] (Android) → the vault was cleared; the phrase restores it
     *   ("Enter your encryption phrase to unlock chats on this device.");
     * - a lock arriving while the prompt or the open ran → the keys are dropped.
     * [automatic] is kept for parity: a cancelled automatic attempt suppresses further automatic ones.
     */
    suspend fun unlockHistoryIfPossible(
        userId: String,
        automatic: Boolean = false,
        method: UnlockMethod = UnlockMethod.BiometryPreferred,
    ): Boolean {
        lastError.value = null
        if (automatic && suppressAutomaticVaultPrompt) {
            needsUnlock.value = withContext(io) { identityStore.hasIdentity(userId) }
            return false
        }
        // Coalesce: two taps must not stack two system prompts (`:78-83`).
        if (!vaultUnlockInFlight.compareAndSet(false, true)) return isUnlocked
        try {
            val generation = rw.read { lockGeneration }
            if (!withContext(io) { identityStore.hasIdentity(userId) }) {
                dropMaterial()
                needsUnlock.value = false
                lastError.value = userMessage(CryptoException.LocalDataMissing())
                return false
            }
            try {
                val historyKey = try {
                    promptInFlight.value = true
                    vault.unlock(userId, method)
                } finally {
                    promptInFlight.value = false
                }
                val restored = try {
                    withContextHandingOver(io, wipe = IdentityKeyMaterial::wipe) {
                        // The privates are sealed under the history key, so they open only now.
                        val stored = identityStore.load(historyKey)
                        if (stored == null || !stored.userId.equals(userId, ignoreCase = true)) {
                            stored?.wipe()
                            throw VaultError.NotFound
                        }
                        try {
                            rewrapHistoryIfNeeded(historyKey, userId)
                            IdentityKeyMaterial.restore(stored, historyKey)
                        } finally {
                            stored.wipe()
                        }
                    }
                } finally {
                    historyKey.fill(0)
                }
                adopt(restored, generation)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultError.UserCancelled) {
                failUnlock(e, needs = true, automatic = automatic)
            } catch (e: VaultError.TimedOut) {
                failUnlock(e, needs = true, automatic = automatic)
            } catch (_: VaultError.KeyInvalidated) {
                failUnlock(VaultError.NotFound, needs = true, automatic = automatic)
            } catch (e: CryptoException.LockedWhileUnlocking) {
                // The lock already set needsHistoryUnlock; the keys were wiped by adopt.
                lastError.value = userMessage(e)
            } catch (e: Exception) {
                failUnlock(e, needs = withContext(io) { identityStore.hasIdentity(userId) }, automatic = automatic)
            }
            return false
        } finally {
            vaultUnlockInFlight.set(false)
        }
    }

    /**
     * Sign Up: derive the keys from the phrase, store them (identity record + vault), publish the
     * bundle, then adopt (`establishFromSignup`, `:148-164`). Android keeps its extra check that
     * refuses a phrase which is not the key the account already published (a two-step sign-up can
     * retry against an account that has keys): derive → reject → persist → PUT → adopt (crypto §12.3).
     */
    suspend fun establishFromSignup(words: List<String>, session: Session) {
        val generation = rw.read { lockGeneration }
        val established = derive(words, session.userId, oneTimePreKeyCount = 100)
        try {
            rejectPhraseThatIsNotTheAccountKey(established, session)
            persistUnlocked(established)
            api.putKeyBundle(session.token, bundleRequest(established))
        } catch (e: Throwable) {
            established.wipe()
            throw e
        }
        adopt(established, generation)
    }

    /**
     * Log In, phrase step (`unlockWithPhrase`, `:166-219`; crypto §12.4). The phrase is validated
     * before any request. When this phone stores the account's identity and the phrase opens it,
     * the stored shell is reused — its signed prekey and one-time prekeys stay — the vault is
     * re-stored (which is how *Phrase needed* recovers), and the bundle is uploaded only if the server
     * says this device has no identity (`uploadBundleIfNeeded`, `:287-297`). Otherwise (another
     * account's identity is cleared first) the keys are re-established: the phrase must match the
     * account's published key (`KEYS_REQUIRED` = first device), fresh prekeys are stored and
     * published.
     *
     * Like iOS the keys are adopted before the conditional upload, so a failed upload throws
     * although chats are unlocked (`:189-194`, crypto R5).
     */
    suspend fun unlockWithPhrase(words: List<String>, session: Session) {
        val validated = bip39.validate(words)
        val generation = rw.read { lockGeneration }
        val userId = session.userId
        val storedUserId = withContext(io) { identityStore.storedUserId() }
        if (storedUserId != null && storedUserId.equals(userId, ignoreCase = true)) {
            val shell = openStoredShell(validated, userId)
            if (shell != null) {
                try {
                    if (!shell.matches(bip39, validated)) throw CryptoException.PhraseDoesNotMatchAccount()
                    persistUnlocked(shell)
                } catch (e: Throwable) {
                    shell.wipe()
                    throw e
                }
                adopt(shell, generation)
                uploadBundleIfNeeded(session.token)
                return
            }
        }
        if (storedUserId != null && !storedUserId.equals(userId, ignoreCase = true)) {
            withContext(io) {
                identityStore.clear()
                vault.clear()
            }
        }
        val established = derive(validated, userId, oneTimePreKeyCount = 100)
        try {
            // No usable keys here: still refuse a phrase that is not the account's published key.
            rejectPhraseThatIsNotTheAccountKey(established, session)
            persistUnlocked(established)
            api.putKeyBundle(session.token, bundleRequest(established))
        } catch (e: Throwable) {
            established.wipe()
            throw e
        }
        adopt(established, generation)
    }

    /**
     * Log In on a full account, before any device is offered for sign-out: is [words] the phrase
     * of [identityKey] (Base64, the account's published identity key from the login's `409
     * DEVICE_LIMIT`)? There is no session yet, so nothing is fetched and nothing is stored.
     * Invalid words throw their [Bip39.PhraseException]; another phrase throws
     * [CryptoException.PhraseDoesNotMatchAccount] — the phrase step's usual errors.
     */
    suspend fun checkPhrase(words: List<String>, identityKey: String) {
        val validated = bip39.validate(words)
        val key = B64.decodeStrict(identityKey)
        val matches = key != null && withContext(compute) { IdentityKeyMaterial.phraseMatches(bip39, validated, key) }
        if (!matches) throw CryptoException.PhraseDoesNotMatchAccount()
    }

    /**
     * The names of a full account's [devices] (the login's `409 DEVICE_LIMIT`), opened with the
     * history key of [words] — a phrase [checkPhrase] accepted — the way Settings › Devices opens
     * them ([DeviceNameSeal.open]). Null for a device without a name or one that doesn't open. The
     * key is derived here and zeroed; names stay in memory.
     */
    suspend fun openDeviceNames(words: List<String>, devices: List<LimitDeviceDto>): Map<UUID, DeviceNameSeal.Label?> {
        val validated = bip39.validate(words)
        return withContext(compute) {
            val historyKey = IdentityKeyMaterial.historyKey(bip39, validated)
            try {
                devices.associate { it.id to DeviceNameSeal.open(it.sealedName, it.id, historyKey) }
            } finally {
                historyKey.fill(0)
            }
        }
    }

    /**
     * Drops the keys from memory (backgrounding, Log Out; `lock`, `:221-231`). The stored identity
     * and vault stay unless [wipeStore], which deletes both. [needsHistoryUnlock] says whether an
     * identity is still stored — by the record's existence, since an Android lock can run while the
     * phone itself is locked and the record unreadable.
     */
    fun lock(wipeStore: Boolean = false) {
        clearMaterial()
        suppressAutomaticVaultPrompt = false
        if (wipeStore) {
            identityStore.clear()
            vault.clear()
            needsUnlock.value = false
        } else {
            needsUnlock.value = identityStore.hasRecord()
        }
    }

    /** Drops the keys from RAM only; identity record and vault stay sealed (`lockHistoryInMemory`, `:233-238`). */
    fun lockHistoryInMemory() {
        clearMaterial()
        needsUnlock.value = identityStore.hasRecord()
        suppressAutomaticVaultPrompt = false
    }

    /**
     * Runs [block] with the identity keys in memory, without unlocking the chats.
     *
     * Show Content keeps a copy of the history key so a notification can open one message after
     * auto-lock. [unlockedUserId] stays null, so the lock screen stays up. If the chats are already
     * unlocked, [block] just runs. A real unlock that lands during [block] is left in place.
     * [historyKey] is not kept. Null when the identity cannot be opened (the phone is locked, or
     * the key does not match).
     */
    suspend fun <T> withKeysForNotification(historyKey: ByteArray, block: suspend () -> T): T? {
        if (historyKey.size != 32 || storageSeal.isSealed) return null
        if (isUnlocked) return block()
        val restored = try {
            withContext(io) {
                val stored = identityStore.load(historyKey) ?: return@withContext null
                try {
                    IdentityKeyMaterial.restore(stored, historyKey)
                } finally {
                    stored.wipe()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } ?: return null
        val installed = rw.write {
            if (material != null || storageSeal.isSealed) {
                restored.wipe()
                false
            } else {
                material = restored
                sealedLocalState.unlock(restored.historyKey)
                true
            }
        }
        if (!installed) return if (isUnlocked) block() else null
        return try {
            block()
        } finally {
            rw.write {
                if (unlocked.value == null && material === restored) {
                    lockGeneration++
                    material?.wipe()
                    material = null
                    sealedLocalState.lock()
                }
            }
        }
    }

    // ---- internals ----

    private suspend fun derive(words: List<String>, userId: String, oneTimePreKeyCount: Int): IdentityKeyMaterial =
        withContextHandingOver(compute, wipe = IdentityKeyMaterial::wipe) {
            val validated = bip39.validate(words)
            IdentityKeyMaterial.establish(bip39, validated, userId, oneTimePreKeyCount)
        }

    /** The stored identity opened with the phrase's history key, or null when it does not open (`:177-184`). */
    private suspend fun openStoredShell(words: List<String>, userId: String): IdentityKeyMaterial? {
        val probe = derive(words, userId, oneTimePreKeyCount = 0)
        try {
            return withContextHandingOver(io, wipe = IdentityKeyMaterial::wipe) {
                val stored = identityStore.load(probe.historyKey) ?: return@withContextHandingOver null
                try {
                    IdentityKeyMaterial.restore(stored, probe.historyKey)
                } finally {
                    stored.wipe()
                }
            }
        } finally {
            probe.wipe()
        }
    }

    /**
     * Identity record, then the vault (`persistUnlocked`, `:240-244`). Refused while a wipe runs
     * ([StorageSeal]): the stores would drop both writes silently, and the flow would then publish
     * a bundle whose keys exist nowhere on this phone.
     */
    private suspend fun persistUnlocked(material: IdentityKeyMaterial) = withContext(io) {
        if (storageSeal.isSealed) throw CryptoException.LockedWhileUnlocking()
        identityStore.save(material)
        vault.store(material.historyKey, material.userId)
    }

    /** Re-seals a vault whose wrap key is not protected (`rewrapHistoryIfNeeded`, `:246-260`); never true today. */
    private fun rewrapHistoryIfNeeded(historyKey: ByteArray, userId: String) {
        if (vault.isWrapKeyProtected) return
        try {
            vault.store(historyKey, userId)
        } catch (_: Exception) {
            // Keep the old vault; the phrase can re-store it later.
        }
    }

    /** First device: the account has no published identity yet; any later device must derive it (`:262-285`). */
    private suspend fun rejectPhraseThatIsNotTheAccountKey(established: IdentityKeyMaterial, session: Session) {
        try {
            val published = api.identityKey(session.token, session.userUuid)
            val key = B64.decodeStrict(published.identityKey)
            if (key == null || !key.contentEquals(established.agreementPublic)) {
                throw CryptoException.PhraseDoesNotMatchAccount()
            }
        } catch (e: ApiError.Server) {
            if (e.code != ErrorCodes.KEYS_REQUIRED) throw e
        }
    }

    /** `GET keys/status`: `has_identity` → nothing to do; false or an error → `PUT keys/bundle` (`:287-297`). */
    private suspend fun uploadBundleIfNeeded(token: String) {
        val request = withMaterial { bundleRequest(it) } ?: return
        try {
            if (api.keyStatus(token).hasIdentity) return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fall through to upload.
        }
        api.putKeyBundle(token, request)
    }

    /**
     * Keeps [established] unless a lock came after [generation] or a wipe is running (then it is
     * wiped and [CryptoException.LockedWhileUnlocking] thrown). Opens [SealedLocalState] inside the
     * write lock, so a racing [lock] cannot leave it unlocked with wiped bytes.
     */
    private fun adopt(established: IdentityKeyMaterial, generation: Long) {
        rw.write {
            if (generation != lockGeneration || storageSeal.isSealed) {
                established.wipe()
                throw CryptoException.LockedWhileUnlocking()
            }
            material?.wipe()
            material = established
            sealedLocalState.unlock(established.historyKey)
        }
        unlocked.value = established.userId
        needsUnlock.value = false
        suppressAutomaticVaultPrompt = false
    }

    /** A lock: bumps the generation, wipes the material, locks [SealedLocalState]. */
    private fun clearMaterial() {
        rw.write {
            lockGeneration++
            material?.wipe()
            material = null
            sealedLocalState.lock()
        }
        unlocked.value = null
    }

    /** iOS `material = nil` after a failed vault unlock: wipes without counting as a lock. */
    private fun dropMaterial() {
        val had = rw.write {
            val m = material
            m?.wipe()
            material = null
            if (m != null) sealedLocalState.lock()
            m != null
        }
        if (had) unlocked.value = null
    }

    private fun failUnlock(error: Throwable, needs: Boolean, automatic: Boolean) {
        dropMaterial()
        needsUnlock.value = needs
        lastError.value = userMessage(error)
        if (automatic) suppressAutomaticVaultPrompt = true
    }

    companion object {
        fun bundleRequest(material: IdentityKeyMaterial) = PutKeyBundleRequest(
            registrationId = material.registrationId,
            identityKey = B64.encode(material.agreementPublic),
            signedPreKey = SignedPreKeyDto(material.signedPreKeyId, B64.encode(material.signedPreKeyPublic), B64.encode(material.signedPreKeySignature)),
            oneTimePreKeys = material.oneTimePreKeys.map { OneTimePreKeyDto(it.keyId, B64.encode(it.publicKey)) },
        )

        /**
         * The copy for an unlock failure (`userMessage(for:)`, `CryptoController.swift:299-342`;
         * crypto §12.6 with the Android wording of P14).
         */
        fun userMessage(error: Throwable): String = when (error) {
            is Bip39.PhraseException.InvalidWordCount -> "Enter all 12 words of your encryption phrase."
            is Bip39.PhraseException.UnknownWord -> "One or more words are not in the recovery word list."
            is Bip39.PhraseException.InvalidChecksum -> "That phrase isn’t valid. Check the words and order."
            is VaultError.UserCancelled -> "Authentication cancelled."
            is VaultError.TimedOut -> "Unlock timed out. Try again, use screen lock, or your encryption phrase."
            is VaultError.NotFound, is VaultError.KeyInvalidated -> "Enter your encryption phrase to unlock chats on this device."
            is VaultError.PasscodeNotSet -> "Set a screen lock to use Shroud."
            is VaultError -> "Could not unlock encrypted chats. Try your encryption phrase."
            is CryptoException -> error.message ?: GENERIC
            is ApiError -> error.userMessage
            else -> GENERIC
        }

        private const val GENERIC = "Could not unlock encryption. Try again."
    }
}
