package de.corespace.shroud.e2e

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.LOCAL_NETWORK_PERMISSION
import de.corespace.shroud.MainActivity
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.keys.DeviceLock
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.keys.VaultState
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.realtime.RealtimeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The wave 1 exit gate on an emulator against the local stack (00-plan §2.2 W1-INT, §6.3):
 * `GET /config` and the socket's `auth.ok` with a fresh account, presence accounting of a socket the
 * background connection holds (W1-RT against X1-SRV-UP), and the vault round trip with real
 * key routes — sign up publishes the bundle, a "relaunched" controller opens the history key with
 * the screen lock and publishes nothing.
 *
 * Needs `android/e2e/stack-up.sh` on the host and `android/e2e/emulator-setup.sh` (PIN, Android 17's
 * local-network grant); skipped when the API cannot be reached. The base URL is the debug default
 * `http://10.0.2.2:8080/api/v1`, or the instrumentation argument `shroudApi`. Usernames are unique
 * per run; each account logs out at the end.
 */
@RunWith(AndroidJUnit4::class)
class W1SmokeTest {
    private val app = ApplicationProvider.getApplicationContext<ShroudApplication>()
    private val container get() = app.container
    private val baseUrl: String =
        InstrumentationRegistry.getArguments().getString("shroudApi") ?: "http://10.0.2.2:8080/api/v1"
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
    private val api = ShroudApi(ApiClient({ baseUrl }, container.json, ApiClient.defaultHttpClient()))
    private val tokens = mutableListOf<String>()

    @Before
    fun setUp() {
        // Android 17: the stack is on the host's loopback (10.0.2.2), a local-network address. The
        // test run reinstalls the app, which drops emulator-setup.sh's grant (as in EngineE2eTest).
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, LOCAL_NETWORK_PERMISSION)
        }
        val live = runCatching {
            http.newCall(Request.Builder().url("$baseUrl/health/live").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        assumeTrue("the local stack is not reachable at $baseUrl (android/e2e/stack-up.sh)", live)
    }

    @After
    fun tearDown() = runBlocking {
        tokens.forEach { runCatching { api.logout(it) } }
    }

    private fun signUp(): Session = runBlocking {
        val name = "w1smoke_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val response = api.register(name, "W1 smoke test passphrase " + UUID.randomUUID())
        tokens += response.token
        // Only an account that lost its username has none; a fresh one has the name it signed up with.
        assertEquals(name, response.user.username)
        Session(response.token, Ids.wire(response.user.id), name, response.user.shareCode, Ids.wire(response.device.id))
    }

    @Test
    fun configAndTheSocketAnswerAFreshAccount() {
        val session = signUp()
        val config = runBlocking { api.clientConfig(session.token) }
        assertTrue("reactions.max_per_user is positive", config.reactions.maxPerUser > 0)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val client = runBlocking(Dispatchers.Main) {
            RealtimeClient(
                baseUrl = { baseUrl },
                json = container.json,
                baseHttp = ApiClient.defaultHttpClient(),
                scope = scope,
                authOutcomes = { null },
                isForeground = { false },
            ).also { it.hold(RealtimeClient.Holder.Messaging, session.token) }
        }
        try {
            val connected = runBlocking {
                withTimeout(20_000) { client.state.first { it is RealtimeClient.ConnectionState.Connected } }
            } as RealtimeClient.ConnectionState.Connected
            assertEquals(session.userId, Ids.wire(connected.userId))
            assertEquals(session.deviceId, Ids.wire(connected.deviceId))
        } finally {
            runBlocking(Dispatchers.Main) { client.shutdown() }
            scope.cancel()
        }
    }

    /** Polls [userId]'s presence as seen by [viewer] until it is [online] (or fails after [timeoutMs]). */
    private fun awaitPresence(viewer: Session, userId: UUID, online: Boolean, what: String, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Boolean? = null
        while (System.currentTimeMillis() < deadline) {
            last = runBlocking { api.presence(viewer.token, userId).online }
            if (last == online) return
            Thread.sleep(250)
        }
        throw AssertionError("$what: expected online=$online, still $last after $timeoutMs ms")
    }

    /**
     * W1-RT acceptance against X1-SRV-UP, end to end: a socket held only by the background
     * connection never shows the user online; a messaging hold in front does; letting go again
     * returns to background accounting; and once the background connection lets go of a socket a
     * call or messaging keeps, an away socket counts online again (the `"background": false` frame).
     */
    @Test
    fun aBackgroundHeldSocketDoesNotMakeTheUserOnline() {
        val a = signUp()
        val b = signUp()
        runBlocking {
            api.createContactRequest(a.token, UUID.fromString(b.userId))
            val request = api.contactRequests(b.token).single()
            api.acceptContactRequest(b.token, request.id)
        }
        val aId = UUID.fromString(a.userId)
        awaitPresence(b, aId, online = false, what = "no socket yet")

        val foreground = AtomicBoolean(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val client = runBlocking(Dispatchers.Main) {
            RealtimeClient(
                baseUrl = { baseUrl },
                json = container.json,
                baseHttp = ApiClient.defaultHttpClient(),
                scope = scope,
                authOutcomes = { null },
                isForeground = { foreground.get() },
            ).also { it.hold(RealtimeClient.Holder.Background, a.token) }
        }
        try {
            runBlocking { withTimeout(20_000) { client.state.first { it is RealtimeClient.ConnectionState.Connected } } }
            // Held only by the background connection, even with the app in front: offline, and it stays so.
            Thread.sleep(1_500)
            awaitPresence(b, aId, online = false, what = "background-held socket", timeoutMs = 1_000)

            // The chats unlock in front: messaging holds the same socket and says focus:true.
            runBlocking(Dispatchers.Main) {
                client.noteFocus(true)
                client.hold(RealtimeClient.Holder.Messaging, a.token)
            }
            awaitPresence(b, aId, online = true, what = "messaging hold in front")

            // The chats lock: messaging lets go, the background connection keeps the socket.
            runBlocking(Dispatchers.Main) { client.release(RealtimeClient.Holder.Messaging) }
            awaitPresence(b, aId, online = false, what = "messaging released")

            // In front again, then leaving with the socket kept (keepSocket): offline.
            runBlocking(Dispatchers.Main) { client.hold(RealtimeClient.Holder.Messaging, a.token) }
            awaitPresence(b, aId, online = true, what = "messaging hold again")
            foreground.set(false)
            runBlocking(Dispatchers.Main) {
                client.noteFocus(false)
                client.deliverFocus()
            }
            awaitPresence(b, aId, online = false, what = "left with the background connection on")

            // The background connection is switched off while messaging (a call) keeps the socket:
            // an ordinary away socket, online again, as on iOS and the web.
            runBlocking(Dispatchers.Main) { client.release(RealtimeClient.Holder.Background) }
            awaitPresence(b, aId, online = true, what = "background connection released")
            assertTrue(client.isConnected)
        } finally {
            runBlocking(Dispatchers.Main) { client.shutdown() }
            scope.cancel()
        }
        awaitPresence(b, aId, online = false, what = "socket closed")
    }

    @Test
    fun theVaultOpensWithTheScreenLockAfterARealSignUp() {
        DeviceLock.ensureUnlocked()
        assumeTrue("needs a screen lock: android/e2e/emulator-setup.sh", DeviceLock.isSecure)
        val keys = container.keys
        keys.cryptoController.lock(wipeStore = true)
        val session = signUp()
        try {
            // The process that signed up: same disk and Keystore, its own memory, the real key routes.
            val signUpController = CryptoController(
                api = api,
                bip39 = keys.bip39,
                identityStore = keys.identityStore,
                vault = keys.historyVault,
                sealedLocalState = SealedLocalState(),
                storageSeal = container.storageSeal,
            )
            runBlocking { signUpController.establishFromSignup(keys.bip39.generate(), session) }
            // A real sign-up stores its session next to the keys. Without it the relaunch below is a
            // signed-out phone with leftovers, which the launch check wipes before the vault prompt
            // (W2-AUTH-WIPE, `DeviceWipeController.finishInterruptedWipeIfNeeded`), as in VaultFlowTest.
            container.auth.sessionStore.save(session)
            signUpController.lock()
            val status = runBlocking { api.keyStatus(session.token) }
            assertTrue("the bundle reached the server", status.hasIdentity)

            // The relaunched process: only the disk knows the keys.
            val crypto = keys.cryptoController
            assertEquals(VaultState.Ready, crypto.vaultState(session.userId))
            ActivityScenario.launch(MainActivity::class.java).use {
                val unlock = CoroutineScope(Dispatchers.Default).async { crypto.unlockHistoryIfPossible(session.userId) }
                assertTrue("the vault prompt appeared", DeviceLock.answerPromptWithPin())
                val unlocked = runBlocking { withTimeout(30_000) { unlock.await() } }
                assertTrue("vault unlock failed: ${crypto.lastUnlockErrorMessage.value}", unlocked)
                assertEquals(session.userId, crypto.unlockedUserId.value)
            }
        } finally {
            container.auth.sessionStore.clear()
            runBlocking { withContext(Dispatchers.Main) { keys.cryptoController.lock(wipeStore = true) } }
            keys.keyMaterialWipe.wipeAll()
        }
    }
}
