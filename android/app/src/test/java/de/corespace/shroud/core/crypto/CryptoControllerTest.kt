package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.keys.FakeVaultAuthenticator
import de.corespace.shroud.core.keys.FakeVaultAuthenticator.Step
import de.corespace.shroud.core.keys.HistoryKeyVault
import de.corespace.shroud.core.keys.IdentityKeyStore
import de.corespace.shroud.core.keys.IdentityPresence
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.keys.SoftwareVaultKeyStore
import de.corespace.shroud.core.keys.UnlockMethod
import de.corespace.shroud.core.keys.VaultAuthenticator
import de.corespace.shroud.core.keys.VaultError
import de.corespace.shroud.core.keys.VaultKeyStore
import de.corespace.shroud.core.keys.VaultState
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.storage.ScriptedSealer
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unlock flows of the messaging keys (iOS `ios/shroud/Services/Crypto/CryptoController.swift`;
 * crypto spec §12) against MockWebServer, a software vault key store and a scripted prompt: sign up
 * persists before it publishes; a relaunch opens through the vault and publishes nothing; the
 * stored-shell phrase unlock keeps this device's prekeys and uploads only if the server has no
 * identity for it; another account's keys are replaced; `KeyInvalidated` sends the user to the
 * phrase; locks win over unlocks in flight.
 *
 * Real time (`runBlocking`), not `runTest`: the controller and the vault hop to `Dispatchers.IO`,
 * and while the test scheduler idles on that, virtual time would skip straight past the vault's
 * 90 s prompt limit. The limit itself is pinned in `HistoryKeyVaultLogicTest`.
 */
class CryptoControllerTest {
    @get:Rule
    val temp = TempDirRule()

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val bip39 = TestWordlist.bip39
    private val words = List(11) { "abandon" } + "about"
    private val otherWords by lazy { bip39.fromEntropy(ByteArray(16) { 0x11 }) }
    private val session = Session("tok", USER_ID, "noah", null, DEVICE_ID)
    private val otherSession = Session("tok2", OTHER_USER_ID, "mia", null, DEVICE_ID)
    private val abandonKey = "mTuaX8n1TBpa7jCzFg2HyYcLPjB5ZW7YJ8YUtlmaayQ="

    private val sealer = ScriptedSealer()
    private val vaultKeys = SoftwareVaultKeyStore()
    private val storageSeal = StorageSeal()
    private var secure = true
    private var auth = FakeVaultAuthenticator()
    private lateinit var state: SealedLocalState
    private lateinit var crypto: CryptoController

    private val identityStore get() = IdentityKeyStore(SealedFile(temp.noBackupFilesDir.resolve("keys/identity.v1"), sealer), storageSeal)
    private val vault get() = vault(auth)

    private fun vault(authenticator: VaultAuthenticator) = HistoryKeyVault(
        record = SealedFile(temp.noBackupFilesDir.resolve("keys/history-vault.v1"), sealer),
        keys = vaultKeys,
        isDeviceSecure = { secure },
        authenticator = authenticator,
        seal = storageSeal,
    )

    /** A new process over the same disk and Keystore (kill → relaunch). */
    private fun relaunch(authenticator: FakeVaultAuthenticator = FakeVaultAuthenticator()): CryptoController {
        auth = authenticator
        state = SealedLocalState()
        crypto = CryptoController(ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json)), bip39, identityStore, vault, state, storageSeal)
        return crypto
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        relaunch()
    }

    @After
    fun tearDown() = server.close()

    private fun identity(key: String) =
        """{"user_id":"$USER_ID","device_id":"6ba7b810-9dad-11d1-80b4-00c04fd430c8","registration_id":1,"identity_key":"$key"}"""

    private fun status(hasIdentity: Boolean) = """{"device_id":"$DEVICE_ID","has_identity":$hasIdentity,"otpk_count":100}"""

    private fun keysRequired() = MockResponse(code = 404, body = """{"error":{"code":"KEYS_REQUIRED","message":"none"}}""")

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun RecordedRequest.jsonBody(): JsonObject = json.parseToJsonElement(body!!.utf8()).jsonObject

    /** Signs up with the abandon phrase as the account's first device; returns the published bundle. */
    private suspend fun signUp(): JsonObject {
        server.enqueue(keysRequired())
        server.enqueue(MockResponse(code = 204))
        crypto.establishFromSignup(words, session)
        assertEquals("/api/v1/keys/identity/$USER_ID", take().url.encodedPath)
        val put = take()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/keys/bundle", put.url.encodedPath)
        return put.jsonBody()
    }

    // ---- Phrase flows that existed before the vault (still hold) ----

    @Test
    fun firstDevicePublishesAndUnlocks() = runBlocking<Unit> {
        server.enqueue(keysRequired())
        server.enqueue(MockResponse(code = 204))
        crypto.unlockWithPhrase(words, session)
        assertEquals(USER_ID, crypto.unlockedUserId.value)
        assertTrue(crypto.isUnlocked)
        assertEquals("/api/v1/keys/identity/$USER_ID", take().url.encodedPath)
        val put = take().jsonBody()
        assertEquals(abandonKey, put["identity_key"]!!.jsonPrimitive.content)
        assertEquals(100, put["one_time_pre_keys"]!!.jsonArray.size)
        // Stored and vaulted, and the sealed stores are open with the history key.
        assertEquals(IdentityPresence.Present, crypto.identityPresence(USER_ID))
        assertEquals(VaultState.Ready, crypto.vaultState(USER_ID))
        assertArrayEquals(crypto.withMaterial { it.historyKey.copyOf() }, state.withKey { it.copyOf() })
        assertFalse(crypto.needsHistoryUnlock.value)
    }

    @Test
    fun matchingPhraseOnALaterDeviceUnlocks() = runBlocking<Unit> {
        server.enqueue(MockResponse(code = 200, body = identity(abandonKey)))
        server.enqueue(MockResponse(code = 204))
        crypto.unlockWithPhrase(words, session)
        assertEquals(USER_ID, crypto.unlockedUserId.value)
    }

    @Test
    fun wrongPhraseIsRefusedAndNothingIsPublishedOrStored() = runBlocking<Unit> {
        server.enqueue(MockResponse(code = 200, body = identity("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")))
        val error = runCatching { crypto.unlockWithPhrase(words, session) }.exceptionOrNull()
        assertTrue(error is CryptoException.PhraseDoesNotMatchAccount)
        assertEquals("That phrase doesn’t match this account on this device.", CryptoController.userMessage(error!!))
        assertNull(crypto.unlockedUserId.value)
        assertEquals(1, server.requestCount)
        assertEquals(IdentityPresence.Absent, crypto.identityPresence(USER_ID))
        assertEquals(VaultState.NotFound, crypto.vaultState(USER_ID))
    }

    @Test
    fun signUpNeverReplacesAnotherPhrasesPublishedKey() = runBlocking<Unit> {
        server.enqueue(MockResponse(code = 200, body = identity("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")))
        val error = runCatching { crypto.establishFromSignup(words, session) }.exceptionOrNull()
        assertTrue(error is CryptoException.PhraseDoesNotMatchAccount)
        assertEquals(1, server.requestCount)
        assertFalse(identityStore.hasRecord())
    }

    @Test
    fun badChecksumFailsBeforeAnyRequest() = runBlocking<Unit> {
        val error = runCatching { crypto.unlockWithPhrase(List(12) { "abandon" }, session) }.exceptionOrNull()
        assertTrue(error is Bip39.PhraseException.InvalidChecksum)
        assertEquals("That phrase isn’t valid. Check the words and order.", CryptoController.userMessage(error!!))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun lockDuringUnlockDropsTheKeysAndSaysSo() = runBlocking<Unit> {
        server.enqueue(keysRequired())
        server.enqueue(MockResponse(code = 204).newBuilder().headersDelay(300, TimeUnit.MILLISECONDS).build())
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { crypto.unlockWithPhrase(words, session) }.exceptionOrNull() }
        Thread.sleep(150)
        crypto.lock()
        val error = job.await()
        assertTrue(error.toString(), error is CryptoException.LockedWhileUnlocking)
        assertNull(crypto.unlockedUserId.value)
        assertFalse(state.isUnlocked)
    }

    // ---- Sign up (crypto §12.3: derive → reject → persist → PUT → adopt) ----

    @Test
    fun signUpPersistsTheIdentityAndTheVaultThenPublishes() = runBlocking<Unit> {
        val put = signUp()
        assertEquals(abandonKey, put["identity_key"]!!.jsonPrimitive.content)
        assertEquals(USER_ID, crypto.unlockedUserId.value)
        assertTrue(identityStore.hasIdentity(USER_ID))
        assertTrue(vault.hasBlob(USER_ID))
        assertTrue(state.isUnlocked)
    }

    @Test
    fun aFailedPublishAtSignUpKeepsTheStoredKeysButDoesNotUnlock() = runBlocking<Unit> {
        server.enqueue(keysRequired())
        server.enqueue(MockResponse(code = 500, body = """{"error":{"code":"INTERNAL","message":"x"}}"""))
        val error = runCatching { crypto.establishFromSignup(words, session) }.exceptionOrNull()
        assertTrue(error.toString(), error is ApiError)
        assertNull(crypto.unlockedUserId.value)
        // iOS persists first too (`CryptoController.swift:158-160`); the phrase step uploads later.
        assertTrue(identityStore.hasIdentity(USER_ID))
    }

    @Test
    fun withoutAScreenLockSignUpPublishesNothing() = runBlocking<Unit> {
        secure = false
        server.enqueue(keysRequired())
        val error = runCatching { crypto.establishFromSignup(words, session) }.exceptionOrNull()
        assertTrue(error.toString(), error === VaultError.PasscodeNotSet)
        assertEquals("Set a screen lock to use Shroud.", CryptoController.userMessage(error!!))
        assertEquals(1, server.requestCount)
        assertNull(crypto.unlockedUserId.value)
        assertEquals(VaultState.NoScreenLock, crypto.vaultState(USER_ID))
    }

    // ---- Relaunch through the vault (crypto §12.2) ----

    @Test
    fun aRelaunchUnlocksThroughTheVaultAndPublishesNothing() = runBlocking<Unit> {
        val put = signUp()
        val requestsAfterSignUp = server.requestCount
        relaunch(FakeVaultAuthenticator(Step.Succeed))
        assertNull(crypto.unlockedUserId.value)
        assertTrue(crypto.hasLocalIdentity(USER_ID))
        assertEquals(VaultState.Ready, crypto.vaultState(USER_ID))

        assertTrue(crypto.unlockHistoryIfPossible(USER_ID))
        assertEquals(USER_ID, crypto.unlockedUserId.value)
        assertNull(crypto.lastUnlockErrorMessage.value)
        assertFalse(crypto.needsHistoryUnlock.value)
        assertEquals(listOf(UnlockMethod.BiometryPreferred), auth.prompts.map { it.second })
        assertEquals("no request at all, so no second PUT /keys/bundle", requestsAfterSignUp, server.requestCount)
        // The same identity with this device's signed prekey.
        assertEquals(abandonKey, crypto.withMaterial { B64.encode(it.identityPublicKey) })
        assertEquals(put["signed_pre_key"]!!.jsonObject["key_id"]!!.jsonPrimitive.int, crypto.withMaterial { it.signedPreKeyId })
        assertEquals(put["signed_pre_key"]!!.jsonObject["public_key"]!!.jsonPrimitive.content, crypto.withMaterial { B64.encode(it.signedPreKeyPublic) })
        assertTrue(state.isUnlocked)
    }

    @Test
    fun thePasscodeOnlyMethodIsPassedToThePrompt() = runBlocking<Unit> {
        signUp()
        relaunch()
        assertTrue(crypto.unlockHistoryIfPossible(USER_ID, method = UnlockMethod.PasscodeOnly))
        assertEquals(listOf(UnlockMethod.PasscodeOnly), auth.prompts.map { it.second })
    }

    @Test
    fun withoutALocalIdentityTheSessionIsOrphaned() = runBlocking<Unit> {
        assertFalse(crypto.unlockHistoryIfPossible(USER_ID))
        assertEquals("Local encryption data is missing. Sign in or create an account again.", crypto.lastUnlockErrorMessage.value)
        assertFalse(crypto.needsHistoryUnlock.value)
        assertTrue("no prompt", auth.prompts.isEmpty())
    }

    @Test
    fun cancellingKeepsChatsLockedAndAutomaticPromptsStop() = runBlocking<Unit> {
        signUp()
        relaunch(FakeVaultAuthenticator(Step.Error(10), Step.Succeed))
        assertFalse(crypto.unlockHistoryIfPossible(USER_ID, automatic = true))
        assertEquals("Authentication cancelled.", crypto.lastUnlockErrorMessage.value)
        assertTrue(crypto.needsHistoryUnlock.value)
        assertNull(crypto.unlockedUserId.value)
        // Automatic prompts stay suppressed (`:74-77`)…
        assertFalse(crypto.unlockHistoryIfPossible(USER_ID, automatic = true))
        assertEquals(1, auth.prompts.size)
        assertTrue(crypto.needsHistoryUnlock.value)
        // …a tap always prompts.
        assertTrue(crypto.unlockHistoryIfPossible(USER_ID))
        assertEquals(2, auth.prompts.size)
    }

    @Test
    fun aTimedOutPromptUsesTheAndroidCopy() = runBlocking<Unit> {
        signUp()
        relaunch(FakeVaultAuthenticator(Step.Error(3)))
        assertFalse(crypto.unlockHistoryIfPossible(USER_ID))
        assertEquals("Unlock timed out. Try again, use screen lock, or your encryption phrase.", crypto.lastUnlockErrorMessage.value)
        assertTrue(crypto.needsHistoryUnlock.value)
    }

    @Test
    fun otherVaultFailuresKeepTheIdentityAndSaySo() = runBlocking<Unit> {
        signUp()
        relaunch(FakeVaultAuthenticator(Step.Error(7), Step.Error(7)))
        assertFalse(crypto.unlockHistoryIfPossible(USER_ID))
        assertEquals("Could not unlock encrypted chats. Try your encryption phrase.", crypto.lastUnlockErrorMessage.value)
        assertTrue(crypto.needsHistoryUnlock.value)
    }

    /** Crypto §12.2 step 10, Android: a new fingerprint or screen lock killed the wrap key → *Phrase needed*. */
    @Test
    fun anInvalidatedWrapKeySendsTheUserToThePhraseWhichRebuildsTheVault() = runBlocking<Unit> {
        signUp()
        relaunch()
        vaultKeys.invalidate(vaultKeys.aliases().single())
        assertEquals(VaultState.KeyInvalidated, crypto.vaultState(USER_ID))
        assertFalse(crypto.unlockHistoryIfPossible(USER_ID))
        assertEquals("Enter your encryption phrase to unlock chats on this device.", crypto.lastUnlockErrorMessage.value)
        assertTrue(crypto.needsHistoryUnlock.value)
        assertTrue("no prompt for a dead key", auth.prompts.isEmpty())
        assertEquals(VaultState.NotFound, crypto.vaultState(USER_ID))
        assertTrue("the identity stays", crypto.hasLocalIdentity(USER_ID))

        // The phrase opens the stored shell and re-stores the vault; nothing is published.
        val before = server.requestCount
        server.enqueue(MockResponse(code = 200, body = status(true)))
        crypto.unlockWithPhrase(words, session)
        assertEquals("/api/v1/keys/status", take().url.encodedPath)
        assertEquals(before + 1, server.requestCount)
        assertEquals(VaultState.Ready, crypto.vaultState(USER_ID))
        relaunch()
        assertTrue(crypto.unlockHistoryIfPossible(USER_ID))
    }

    @Test
    fun twoTapsShowOnePrompt() = runBlocking<Unit> {
        signUp()
        val gate = Step.Gate()
        relaunch(FakeVaultAuthenticator(gate))
        val first = CoroutineScope(Dispatchers.Default).async { crypto.unlockHistoryIfPossible(USER_ID) }
        auth.shown.await()
        assertTrue(crypto.vaultPromptInFlight.value)
        assertFalse("coalesced: answers isUnlocked", crypto.unlockHistoryIfPossible(USER_ID))
        gate.release.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, auth.prompts.size)
        assertFalse(crypto.vaultPromptInFlight.value)
    }

    /** No adopt after a lock (`CryptoController.swift:102-104`; crypto §10.7). */
    @Test
    fun aLockDuringThePromptDropsTheKeys() = runBlocking<Unit> {
        signUp()
        val gate = Step.Gate()
        relaunch(FakeVaultAuthenticator(gate))
        val unlock = CoroutineScope(Dispatchers.Default).async { crypto.unlockHistoryIfPossible(USER_ID) }
        auth.shown.await()
        crypto.lock()
        gate.release.complete(Unit)
        assertFalse(unlock.await())
        assertEquals("Shroud was locked while unlocking. Try again.", crypto.lastUnlockErrorMessage.value)
        assertNull(crypto.unlockedUserId.value)
        assertFalse(state.isUnlocked)
        assertTrue(crypto.needsHistoryUnlock.value)
    }

    // ---- Phrase unlock with a stored shell (crypto §12.4) ----

    @Test
    fun theStoredShellKeepsThisDevicesPreKeysAndSkipsTheUploadWhenTheServerHasThem() = runBlocking<Unit> {
        val first = signUp()
        relaunch()
        val aliasBefore = vaultKeys.aliases().single()
        server.enqueue(MockResponse(code = 200, body = status(true)))
        crypto.unlockWithPhrase(words, session)
        assertEquals("/api/v1/keys/status", take().url.encodedPath)
        assertEquals(3, server.requestCount)
        assertEquals(first["signed_pre_key"]!!.jsonObject["key_id"]!!.jsonPrimitive.int, crypto.withMaterial { it.signedPreKeyId })
        assertEquals(USER_ID, crypto.unlockedUserId.value)
        // The vault was re-stored under a fresh wrap key (no prompt).
        assertNotEquals(aliasBefore, vaultKeys.aliases().single())
        assertTrue(auth.prompts.isEmpty())
    }

    @Test
    fun theStoredShellIsUploadedWhenTheServerHasNoIdentityForThisDevice() = runBlocking<Unit> {
        val first = signUp()
        relaunch()
        server.enqueue(MockResponse(code = 200, body = status(false)))
        server.enqueue(MockResponse(code = 204))
        crypto.unlockWithPhrase(words, session)
        assertEquals("/api/v1/keys/status", take().url.encodedPath)
        val again = take().jsonBody()
        assertEquals("the same prekeys, not fresh ones", first["signed_pre_key"], again["signed_pre_key"])
        assertEquals(first["one_time_pre_keys"], again["one_time_pre_keys"])
    }

    @Test
    fun aStatusErrorFallsThroughToTheUpload() = runBlocking<Unit> {
        signUp()
        relaunch()
        server.enqueue(MockResponse(code = 500, body = """{"error":{"code":"INTERNAL","message":"x"}}"""))
        server.enqueue(MockResponse(code = 204))
        crypto.unlockWithPhrase(words, session)
        assertEquals("/api/v1/keys/status", take().url.encodedPath)
        assertEquals("/api/v1/keys/bundle", take().url.encodedPath)
    }

    /** iOS adopts before the conditional upload, so a failed upload throws with chats unlocked (crypto R5). */
    @Test
    fun aFailedUploadAfterAdoptingThrowsWithTheKeysUnlocked() = runBlocking<Unit> {
        signUp()
        relaunch()
        server.enqueue(MockResponse(code = 200, body = status(false)))
        server.enqueue(MockResponse(code = 500, body = """{"error":{"code":"INTERNAL","message":"x"}}"""))
        val error = runCatching { crypto.unlockWithPhrase(words, session) }.exceptionOrNull()
        assertTrue(error.toString(), error is ApiError)
        assertEquals(USER_ID, crypto.unlockedUserId.value)
    }

    @Test
    fun aPhraseThatDoesNotOpenTheShellIsCheckedAgainstTheAccountAndChangesNothing() = runBlocking<Unit> {
        signUp()
        relaunch()
        server.enqueue(MockResponse(code = 200, body = identity(abandonKey)))
        val error = runCatching { crypto.unlockWithPhrase(otherWords, session) }.exceptionOrNull()
        assertTrue(error.toString(), error is CryptoException.PhraseDoesNotMatchAccount)
        assertEquals("/api/v1/keys/identity/$USER_ID", take().url.encodedPath)
        assertNull(crypto.unlockedUserId.value)
        assertTrue(crypto.hasLocalIdentity(USER_ID))
        assertEquals(VaultState.Ready, crypto.vaultState(USER_ID))
    }

    @Test
    fun anotherAccountsKeysAreReplaced() = runBlocking<Unit> {
        signUp()
        relaunch()
        server.enqueue(keysRequired())
        server.enqueue(MockResponse(code = 204))
        crypto.unlockWithPhrase(otherWords, otherSession)
        assertEquals("/api/v1/keys/identity/$OTHER_USER_ID", take().url.encodedPath)
        assertEquals("/api/v1/keys/bundle", take().url.encodedPath)
        assertEquals(OTHER_USER_ID, identityStore.storedUserId())
        assertEquals(IdentityPresence.Absent, crypto.identityPresence(USER_ID))
        assertEquals(VaultState.NotFound, crypto.vaultState(USER_ID))
        assertEquals(VaultState.Ready, crypto.vaultState(OTHER_USER_ID))
        assertEquals(OTHER_USER_ID, crypto.unlockedUserId.value)
    }

    /** Crypto §14: during a wipe nothing is stored (the stores drop it), so nothing may be published or adopted. */
    @Test
    fun aWipeInProgressRefusesToPersistPublishOrAdopt() = runBlocking<Unit> {
        storageSeal.seal()
        server.enqueue(keysRequired())
        val error = runCatching { crypto.unlockWithPhrase(words, session) }.exceptionOrNull()
        assertTrue(error.toString(), error is CryptoException.LockedWhileUnlocking)
        assertEquals("no PUT /keys/bundle", 1, server.requestCount)
        assertEquals("/api/v1/keys/identity/$USER_ID", take().url.encodedPath)
        assertFalse(identityStore.hasRecord())
        assertNull(crypto.unlockedUserId.value)
        storageSeal.unseal()

        signUp()
        relaunch()
        storageSeal.seal()
        assertFalse(crypto.unlockHistoryIfPossible(USER_ID))
        assertEquals("Shroud was locked while unlocking. Try again.", crypto.lastUnlockErrorMessage.value)
        assertNull(crypto.unlockedUserId.value)
        assertFalse(state.isUnlocked)
        storageSeal.unseal()
    }

    // ---- Lock (crypto §12.5) ----

    @Test
    fun lockKeepsTheStoreAndSaysChatsNeedUnlocking() = runBlocking<Unit> {
        signUp()
        var material: IdentityKeyMaterial? = null
        crypto.withMaterial { material = it } // tests only: peek at the held object
        crypto.lock()
        assertNull(crypto.unlockedUserId.value)
        assertFalse(crypto.isUnlocked)
        assertNull(crypto.withMaterial { it })
        assertFalse(state.isUnlocked)
        assertTrue(crypto.needsHistoryUnlock.value)
        assertTrue("wiped", material!!.historyKey.all { it == 0.toByte() } && material!!.agreementPrivateKey.all { it == 0.toByte() })
        assertTrue(crypto.hasLocalIdentity(USER_ID))
        assertEquals(VaultState.Ready, crypto.vaultState(USER_ID))
    }

    @Test
    fun lockWithWipeStoreDeletesTheIdentityAndTheVault() = runBlocking<Unit> {
        signUp()
        crypto.lock(wipeStore = true)
        assertFalse(crypto.needsHistoryUnlock.value)
        assertFalse(identityStore.hasRecord())
        assertEquals(VaultState.NotFound, crypto.vaultState(USER_ID))
        assertTrue(vaultKeys.aliases().isEmpty())
    }

    @Test
    fun lockHistoryInMemoryOnlyDropsRam() = runBlocking<Unit> {
        signUp()
        crypto.lockHistoryInMemory()
        assertNull(crypto.unlockedUserId.value)
        assertFalse(state.isUnlocked)
        assertTrue(crypto.needsHistoryUnlock.value)
        assertTrue(identityStore.hasRecord())
        // A lock while the phone itself is locked still knows an identity is stored.
        sealer.readFailure = de.corespace.shroud.core.storage.SealResult.DeviceLocked
        crypto.lock()
        assertTrue(crypto.needsHistoryUnlock.value)
    }

    @Test
    fun withoutAStoredIdentityNothingNeedsUnlocking() {
        crypto.lock()
        assertFalse(crypto.needsHistoryUnlock.value)
    }

    // ---- Copy (crypto §12.6, Android wording P14) ----

    @Test
    fun userMessagesUseTheAndroidWording() {
        val cases = listOf(
            Bip39.PhraseException.InvalidWordCount() to "Enter all 12 words of your encryption phrase.",
            Bip39.PhraseException.UnknownWord() to "One or more words are not in the recovery word list.",
            Bip39.PhraseException.InvalidChecksum() to "That phrase isn’t valid. Check the words and order.",
            CryptoException.PhraseDoesNotMatchAccount() to "That phrase doesn’t match this account on this device.",
            CryptoException.NotUnlocked() to "Unlock messaging with your encryption phrase first.",
            CryptoException.HistoryLocked() to "Unlock with your fingerprint or screen lock to open chats.",
            CryptoException.LocalDataMissing() to "Local encryption data is missing. Sign in or create an account again.",
            CryptoException.NoScreenLock() to "Set a screen lock to use Shroud.",
            CryptoException.LockedWhileUnlocking() to "Shroud was locked while unlocking. Try again.",
            VaultError.UserCancelled to "Authentication cancelled.",
            VaultError.TimedOut to "Unlock timed out. Try again, use screen lock, or your encryption phrase.",
            VaultError.NotFound to "Enter your encryption phrase to unlock chats on this device.",
            VaultError.KeyInvalidated to "Enter your encryption phrase to unlock chats on this device.",
            VaultError.PasscodeNotSet to "Set a screen lock to use Shroud.",
            VaultError.OpenFailed to "Could not unlock encrypted chats. Try your encryption phrase.",
            VaultError.SealFailed to "Could not unlock encrypted chats. Try your encryption phrase.",
            VaultError.Keystore(7) to "Could not unlock encrypted chats. Try your encryption phrase.",
            IllegalStateException("x") to "Could not unlock encryption. Try again.",
        )
        for ((error, copy) in cases) assertEquals(error.toString(), copy, CryptoController.userMessage(error))
        val api = ApiError.Transport("That server address is not a valid URL.")
        assertEquals(api.userMessage, CryptoController.userMessage(api))
    }

    /** [CryptoController.vaultKeySecurity] reads the vault the controller holds, on its IO dispatcher, and does not prompt. */
    @Test
    fun vaultKeySecurityReadsTheRecordOnIoAndDoesNotPrompt() = runBlocking {
        val io = RecordingDispatcher()
        val history = vault
        val controller = CryptoController(
            ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json)),
            bip39,
            identityStore,
            history,
            SealedLocalState(),
            storageSeal,
            io = io,
        )
        try {
            assertNull(controller.vaultKeySecurity())
            assertEquals(1, io.dispatched)
            assertTrue(auth.prompts.isEmpty())
            vaultKeys.security = VaultKeyStore.Security.StrongBox
            val historyKey = ByteArray(32) { 7 }
            try {
                history.store(historyKey, USER_ID)
            } finally {
                historyKey.fill(0)
            }
            assertEquals(VaultKeyStore.Security.StrongBox, controller.vaultKeySecurity())
            assertEquals(2, io.dispatched)
            assertTrue(auth.prompts.isEmpty())
        } finally {
            io.close()
        }
    }

    @Test
    fun phraseErrorsNeverQuoteTheWords() {
        val error = runCatching { bip39.validate(List(11) { "abandon" } + "secretword") }.exceptionOrNull()!!
        assertFalse(error.message!!.contains("secretword"))
    }

    private companion object {
        const val USER_ID = "8f14e45f-ceea-467a-9575-3a6b7a1e6c0e"
        const val OTHER_USER_ID = "11111111-2222-4333-8444-555555555555"
        const val DEVICE_ID = "2e6f9b0c-1d3a-4e5b-8c7d-9f0a1b2c3d4e"
    }
}

/** Counts hops onto the controller's IO dispatcher and actually runs them. */
private class RecordingDispatcher : CoroutineDispatcher(), AutoCloseable {
    var dispatched = 0
        private set

    private val pool = Executors.newSingleThreadExecutor()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched++
        pool.execute(block)
    }

    override fun close() {
        pool.shutdown()
    }
}
