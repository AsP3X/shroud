package de.corespace.shroud.core.keys

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.MainActivity
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * W1-KEYS acceptance on a device with a PIN (00-plan §2.2 W1-KEYS; crypto spec §10, §12, §16.4):
 * sign up → "kill" → relaunch → the vault opens with the screen lock, typed by UiAutomator into the
 * system's credential view, and nothing is published again; and a screen lock that was cleared and
 * set again kills the wrap key, so the user is sent to the phrase, which rebuilds the vault.
 *
 * Production objects throughout: the container's `KeysModule` (WhenUnlocked sealer
 * `shroud.local.v1`, `AndroidVaultKeyStore` with the user-presence policy, `BiometricPrompt` on
 * `MainActivity`) and its one `CryptoController`, which also stands for the relaunched process —
 * it never held the keys the sign-up controller derived and reads them back from disk only. The
 * key routes are answered in-process by [FakeKeysServer] (no server; the count of `PUT
 * /keys/bundle` is what the server log would show). Wipes the test phone's key material before
 * and after.
 */
@RunWith(AndroidJUnit4::class)
class VaultFlowTest {
    private val app = ApplicationProvider.getApplicationContext<ShroudApplication>()
    private val container get() = app.container
    private val keys get() = container.keys
    private val userId = UUID.randomUUID().toString()
    private val deviceId = UUID.randomUUID().toString()
    private val session by lazy { Session("vault-flow-token", userId, "vaultflow", null, deviceId) }
    private val server = FakeKeysServer(deviceId)

    /** The process that signed up: same disk and Keystore, its own API and memory. */
    private val signUpController by lazy {
        val http = OkHttpClient.Builder().addInterceptor(server).build()
        CryptoController(
            api = ShroudApi(ApiClient({ "https://shroud.test/api/v1" }, container.json, http)),
            bip39 = keys.bip39,
            identityStore = keys.identityStore,
            vault = keys.historyVault,
            sealedLocalState = SealedLocalState(),
            storageSeal = container.storageSeal,
        )
    }

    @Before
    fun setUp() {
        DeviceLock.ensureUnlocked()
        assumeTrue("needs a screen lock: adb shell locksettings set-pin ${DeviceLock.PIN}", DeviceLock.isSecure)
        wipe()
    }

    @After
    fun tearDown() {
        DeviceLock.ensureUnlocked()
        wipe()
    }

    private fun wipe() {
        container.auth.sessionStore.clear()
        keys.cryptoController.lock(wipeStore = true)
        keys.keyMaterialWipe.wipeAll()
        assertEquals(emptyList<String>(), keys.keyMaterialWipe.leftovers().filter { it.startsWith("keys/") })
    }

    private fun signUp(words: List<String>) = runBlocking {
        signUpController.establishFromSignup(words, session)
        assertEquals(userId, signUpController.unlockedUserId.value)
        assertEquals(listOf("GET /api/v1/keys/identity/$userId", "PUT /api/v1/keys/bundle"), server.requests)
        // A real sign-up stores its session next to the keys. Without it the relaunch below is a
        // signed-out phone with leftovers, which the launch check wipes (W2-AUTH-WIPE,
        // `DeviceWipeController.finishInterruptedWipeIfNeeded`; iOS skips it only in its unit-test host).
        container.auth.sessionStore.save(session)
        // The sign-up process goes away: its keys leave memory, the disk keeps them.
        signUpController.lock()
    }

    @Test
    fun signUpThenRelaunchUnlocksWithTheScreenLockAndPublishesNothing() {
        val words = keys.bip39.generate()
        signUp(words)

        val crypto = keys.cryptoController
        assertNull(crypto.unlockedUserId.value)
        assertTrue(crypto.hasLocalIdentity(userId))
        assertEquals(VaultState.Ready, crypto.vaultState(userId))

        ActivityScenario.launch(MainActivity::class.java).use {
            val unlock = CoroutineScope(Dispatchers.Default).async { crypto.unlockHistoryIfPossible(userId) }
            if (!DeviceLock.answerPromptWithPin()) {
                val result = runBlocking { withTimeout(100_000) { unlock.await() } }
                val direct = runCatching { runBlocking { keys.historyVault.unlock(userId) } }.exceptionOrNull()
                val detail = (direct as? VaultError.Keystore)?.let { "${it.detail} cause=${it.cause}" }
                throw AssertionError(
                    "no system prompt (unlock=$result, error=${crypto.lastUnlockErrorMessage.value}, " +
                        "top=${container.appPhase.topActivity}, direct=$direct $detail)",
                )
            }
            val unlocked = runBlocking { withTimeout(30_000) { unlock.await() } }
            assertTrue("vault unlock failed: ${crypto.lastUnlockErrorMessage.value}", unlocked)
            // Checked while Shroud is still in front: the interim background lock
            // (`ShroudApplication`, invariant 5) drops the keys once the activity closes.
            assertEquals(userId, crypto.unlockedUserId.value)
            assertFalse(crypto.needsHistoryUnlock.value)
            assertFalse(crypto.vaultPromptInFlight.value)
            assertTrue(keys.sealedLocalState.isUnlocked)
        }
        assertEquals("no second PUT /keys/bundle, no request at all", 2, server.requests.size)
        assertEquals(1, server.requests.count { it == "PUT /api/v1/keys/bundle" })
        crypto.lock()
    }

    @Test
    fun cancellingThePromptKeepsChatsLocked() {
        signUp(keys.bip39.generate())
        val crypto = keys.cryptoController
        ActivityScenario.launch(MainActivity::class.java).use {
            val unlock = CoroutineScope(Dispatchers.Default).async { crypto.unlockHistoryIfPossible(userId, method = UnlockMethod.PasscodeOnly) }
            assertTrue(DeviceLock.waitFor(20_000) { crypto.vaultPromptInFlight.value })
            assertTrue("the system prompt appeared", DeviceLock.waitForPrompt())
            DeviceLock.dismissPrompt()
            val unlocked = runBlocking { withTimeout(30_000) { unlock.await() } }
            assertFalse(unlocked)
            assertEquals("Authentication cancelled.", crypto.lastUnlockErrorMessage.value)
            assertTrue(crypto.needsHistoryUnlock.value)
            assertNull(crypto.unlockedUserId.value)
            assertEquals(VaultState.Ready, crypto.vaultState(userId))
        }
        crypto.lock()
    }

    /** Crypto §10.3: removing and re-setting the screen lock invalidates the wrap key → *Phrase needed*. */
    @Test
    fun aResetScreenLockSendsTheUserToThePhraseWhichRebuildsTheVault() {
        val words = keys.bip39.generate()
        signUp(words)
        try {
            DeviceLock.shell("locksettings clear --old ${DeviceLock.PIN}")
            assertTrue(DeviceLock.waitFor(5_000) { !DeviceLock.isSecure })
            assertEquals(VaultState.NoScreenLock, keys.cryptoController.vaultState(userId))
        } finally {
            DeviceLock.shell("locksettings set-pin ${DeviceLock.PIN}")
        }
        assertTrue(DeviceLock.waitFor(5_000) { DeviceLock.isSecure })
        DeviceLock.ensureUnlocked()

        val crypto = keys.cryptoController
        assertEquals(VaultState.KeyInvalidated, crypto.vaultState(userId))
        val unlocked = runBlocking { crypto.unlockHistoryIfPossible(userId) }
        assertFalse(unlocked)
        assertEquals("Enter your encryption phrase to unlock chats on this device.", crypto.lastUnlockErrorMessage.value)
        assertTrue(crypto.needsHistoryUnlock.value)
        assertEquals(VaultState.NotFound, crypto.vaultState(userId))
        assertTrue("the identity record survives", crypto.hasLocalIdentity(userId))

        // The phrase opens the stored identity, re-stores the vault and publishes nothing.
        server.hasIdentity = true
        runBlocking { signUpController.unlockWithPhrase(words, session) }
        assertEquals(VaultState.Ready, crypto.vaultState(userId))
        assertEquals(1, server.requests.count { it == "PUT /api/v1/keys/bundle" })
        assertEquals("GET /api/v1/keys/status", server.requests.last())
        signUpController.lock()
    }

    /** Answers the key routes of the Shroud API in-process and records every request. */
    class FakeKeysServer(private val deviceId: String) : Interceptor {
        val requests = CopyOnWriteArrayList<String>()

        @Volatile
        var hasIdentity = false

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            requests += "${request.method} $path"
            val (code, body) = when {
                request.method == "GET" && path.startsWith("/api/v1/keys/identity/") ->
                    404 to """{"error":{"code":"KEYS_REQUIRED","message":"no keys yet"}}"""
                request.method == "PUT" && path == "/api/v1/keys/bundle" -> 204 to ""
                request.method == "GET" && path == "/api/v1/keys/status" ->
                    200 to """{"device_id":"$deviceId","has_identity":$hasIdentity,"otpk_count":100}"""
                else -> 500 to """{"error":{"code":"INTERNAL","message":"unexpected request"}}"""
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
