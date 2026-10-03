package de.corespace.shroud.core.push

import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.push.backgroundconnection.BackgroundConnectionController
import de.corespace.shroud.core.push.unifiedpush.DistributorDirectory
import de.corespace.shroud.core.push.unifiedpush.DistributorEvent
import de.corespace.shroud.core.push.unifiedpush.UnifiedPushBroadcaster
import de.corespace.shroud.core.push.unifiedpush.UnifiedPushProtocol
import de.corespace.shroud.core.push.unifiedpush.UnifiedPushSubscriptionStore
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.testing.XorSealer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * Registration against a scripted server. Nothing here dials a live host. The registrar's own
 * scope does the HTTP, so a state that follows a request is waited on rather than assumed.
 */
class PushRegistrarTest {
    @get:Rule val folder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var server: MockWebServer
    private val seen = Collections.synchronizedList(ArrayList<Recorded>())

    @Volatile private var keyStatus: Int = 200
    @Volatile private var putStatus: Int = 204

    private var token: String? = "session-token"
    private var notificationsOn = true
    private val installed = mutableListOf(Distributor("org.example.distributor", "Example"))
    private lateinit var prefs: MemoryPrefs
    private lateinit var broadcasts: RecordingBroadcast
    private lateinit var host: RecordingHost
    private lateinit var store: UnifiedPushSubscriptionStore
    private lateinit var reg: PushRegistrar

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val method = request.method
                val body = request.body?.utf8().orEmpty()
                seen += Recorded(method, path, body, request.headers["Authorization"])
                return when {
                    path.endsWith("/push/web/key") ->
                        if (keyStatus == 200) MockResponse(code = 200, body = """{"public_key":"$VAPID"}""")
                        else MockResponse(code = keyStatus, body = """{"error":{"code":"NOT_FOUND","message":"no"}}""")
                    method == "PUT" && path.endsWith("/push/web/subscription") ->
                        if (putStatus == 204) MockResponse(code = 204)
                        else MockResponse(code = putStatus, body = """{"error":{"code":"VALIDATION_ERROR","message":"host"}}""")
                    method == "DELETE" && path.endsWith("/push/web/subscription") -> MockResponse(code = 204)
                    else -> MockResponse(code = 500, body = """{"error":{"code":"INTERNAL","message":"x"}}""")
                }
            }
        }
        server.start()
        prefs = MemoryPrefs()
        broadcasts = RecordingBroadcast()
        host = RecordingHost()
        store = UnifiedPushSubscriptionStore(
            SealedFile(File(folder.root, "unifiedpush.sealed"), XorSealer()),
            deleteKey = {},
        )
        val background = BackgroundConnectionController(
            host = host,
            prefs = prefs,
            sessionToken = { token },
            hold = {},
            releaseHold = {},
            onEnded = {},
            battery = object : BackgroundConnectionController.BatteryGate {
                override fun unrestricted() = true
                override fun askOnceIfNeeded() = Unit
            },
            reconnect = object : BackgroundConnectionController.ReconnectSchedule {
                override fun arm() = Unit
                override fun cancel() = Unit
                override fun pulse(reconnect: () -> Unit) = Unit
            },
            onReconnect = {},
        )
        reg = PushRegistrar(
            scope = scope,
            sessionToken = { token },
            api = ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json)),
            directory = DistributorDirectory { installed.toList() },
            store = { store },
            prefs = prefs,
            broadcast = broadcasts,
            background = background,
            notificationsEnabled = { notificationsOn },
            ourPackage = OUR_PACKAGE,
            dispatcher = quietDispatcher(),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun noDistributorBecomesUnavailable() {
        installed.clear()
        assertTrue(reg.distributors().isEmpty())
        reg.register()
        assertEquals(UnifiedPushState.Unavailable(NoPushReason.NoDistributorInstalled), reg.delivery.value.unifiedPush)
        assertTrue(broadcasts.registers.isEmpty())
    }

    @Test
    fun aPackageThatIsNotInstalledDoesNotRegister() {
        reg.chooseDistributor(OUR_PACKAGE)
        assertEquals(UnifiedPushState.Unavailable(NoPushReason.DistributorFailed), reg.delivery.value.unifiedPush)
        assertTrue(broadcasts.registers.isEmpty())
    }

    @Test
    fun playServicesDefaultsToTheEmbeddedDistributorWhenAnotherIsInstalled() {
        val embedded = Distributor(OUR_PACKAGE, "Google Play", embedded = true)
        val ntfy = Distributor("io.heckel.ntfy", "ntfy")
        installed.clear()
        installed += embedded
        installed += ntfy
        reg.register()
        await("embedded register") { broadcasts.registers.isNotEmpty() }
        assertEquals(OUR_PACKAGE, broadcasts.registers.first().pkg)
        assertEquals(OUR_PACKAGE, prefs.distributorChoice)
    }

    @Test
    fun aChosenDistributorReplacesTheEmbeddedOne() {
        val embedded = Distributor(OUR_PACKAGE, "Google Play", embedded = true)
        val ntfy = Distributor("io.heckel.ntfy", "ntfy")
        installed.clear()
        installed += embedded
        installed += ntfy
        reg.chooseDistributor(ntfy.packageName)
        await("ntfy register") { broadcasts.registers.isNotEmpty() }
        assertEquals(ntfy.packageName, broadcasts.registers.first().pkg)
    }

    @Test
    fun noneLeavesTheEmbeddedDistributorOff() {
        installed += Distributor(OUR_PACKAGE, "Google Play", embedded = true)
        reg.start()
        await("default embedded") { broadcasts.registers.isNotEmpty() }
        val sent = broadcasts.registers.size
        reg.chooseDistributor(null)
        await("none") {
            reg.delivery.value.unifiedPush == UnifiedPushState.Unavailable(NoPushReason.NoneChosen)
        }
        assertEquals(sent, broadcasts.registers.size)
        assertEquals(PushSettings.NONE, prefs.distributorChoice)
    }

    @Test
    fun signedOutStaysUnknownAndLeavesTheConnectionOff() {
        token = null
        reg.start()
        reg.setBackgroundConnection(true)
        assertTrue(reg.delivery.value.unifiedPush is UnifiedPushState.Unknown)
        assertFalse(reg.delivery.value.backgroundConnection)
        assertEquals(0, host.starts)
        assertTrue(prefs.backgroundConnection)
    }

    @Test
    fun backgroundConnectionTwiceKeepsOneService() {
        reg.start()
        reg.setBackgroundConnection(true)
        reg.setBackgroundConnection(true)
        assertEquals(1, host.starts)
        assertTrue(reg.delivery.value.backgroundConnection)
    }

    @Test
    fun subscriptionBodySaysAndroidAndASecondRegisterKeepsTheToken() {
        val first = registerUntilAccepted()
        reg.register()
        await("second register") { broadcasts.registers.size >= 2 }
        assertEquals(first, broadcasts.registers[1].token)
        val put = seen.toList().filter { it.method == "PUT" }
        assertTrue(put.isNotEmpty())
        assertTrue(put.first().body.contains("\"client\":\"android\""))
        assertTrue(put.first().body.contains("https://up.example/push/abc"))
    }

    @Test
    fun aRefusedHostUnregisters() {
        putStatus = 400
        reg.register()
        await("register sent") { broadcasts.registers.isNotEmpty() }
        val connection = broadcasts.registers.first().token
        reg.onDistributorEvent(endpoint(connection))
        await("refused") {
            reg.delivery.value.unifiedPush == UnifiedPushState.Unavailable(NoPushReason.ServerRefusedHost)
        }
        assertEquals(listOf(connection), broadcasts.unregisters.map { it.token })
        assertTrue(seen.toList().any { it.method == "PUT" && it.body.contains("\"client\":\"android\"") })
    }

    @Test
    fun theServerWithoutWebPushIsUnavailable() {
        keyStatus = 404
        reg.register()
        await("no web push") {
            reg.delivery.value.unifiedPush == UnifiedPushState.Unavailable(NoPushReason.ServerHasNoWebPush)
        }
        assertTrue(broadcasts.registers.isEmpty())
    }

    @Test
    fun aKeyLookupFailureIsReported() {
        keyStatus = 500
        reg.register()
        await("key failed") {
            reg.delivery.value.unifiedPush == UnifiedPushState.Unavailable(NoPushReason.DistributorFailed)
        }
        assertTrue(broadcasts.registers.isEmpty())
    }

    @Test
    fun choosingTheSameDistributorAgainRetriesWhileItIsConnecting() {
        reg.chooseDistributor("org.example.distributor")
        await("first register") { broadcasts.registers.size == 1 }
        assertTrue(reg.delivery.value.unifiedPush is UnifiedPushState.Registering)
        reg.chooseDistributor("org.example.distributor")
        await("second register") { broadcasts.registers.size == 2 }
    }

    @Test
    fun aSilentDistributorBecomesAFailure() {
        reg.registrationWaitMs = 30
        reg.register()
        await("timed out") {
            reg.delivery.value.unifiedPush == UnifiedPushState.Unavailable(NoPushReason.DistributorFailed)
        }
        assertEquals(1, broadcasts.registers.size)
        assertTrue(broadcasts.unregisters.isEmpty())
    }

    @Test
    fun aServerErrorWhileSavingTheEndpointIsReported() {
        putStatus = 500
        reg.register()
        await("register sent") { broadcasts.registers.isNotEmpty() }
        val connection = broadcasts.registers.first().token
        reg.onDistributorEvent(endpoint(connection))
        await("put failed") {
            reg.delivery.value.unifiedPush == UnifiedPushState.Unavailable(NoPushReason.DistributorFailed)
        }
        assertTrue(broadcasts.unregisters.isEmpty())
    }

    @Test
    fun forgetDeletesTheSubscriptionWhileTheTokenIsValid() {
        registerUntilAccepted()
        runBlocking { reg.forgetRegistration() }
        val deleted = seen.toList().filter { it.method == "DELETE" && it.path.endsWith("/push/web/subscription") }
        assertEquals(listOf("Bearer session-token"), deleted.map { it.authorization })
        assertTrue(broadcasts.unregisters.isNotEmpty())
        assertNull(store.load())
        assertFalse(prefs.backgroundConnection)
        assertNull(prefs.distributorChoice)
        assertTrue(reg.delivery.value.unifiedPush is UnifiedPushState.Unknown)
        assertEquals(1, host.stops)
    }

    @Test
    fun aForeignTokenIsIgnored() {
        val before = reg.delivery.value
        reg.onDistributorEvent(
            DistributorEvent(UnifiedPushProtocol.ACTION_NEW_ENDPOINT, "someone-else", "https://up.example/x", null, "1", null, null),
        )
        assertEquals(before, reg.delivery.value)
        assertTrue(seen.isEmpty())
        assertTrue(broadcasts.acks.isEmpty())
    }

    private fun registerUntilAccepted(): String {
        reg.register()
        await("register sent") { broadcasts.registers.isNotEmpty() }
        val connection = broadcasts.registers.first().token
        reg.onDistributorEvent(endpoint(connection))
        await("registered") {
            reg.delivery.value.unifiedPush == UnifiedPushState.Registered("org.example.distributor", "Example")
        }
        return connection
    }

    private fun endpoint(connection: String) = DistributorEvent(
        action = UnifiedPushProtocol.ACTION_NEW_ENDPOINT,
        token = connection,
        endpoint = "https://up.example/push/abc",
        bytes = null,
        id = "1",
        reason = null,
        useDistributor = null,
    )

    private fun await(what: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        error("$what; delivery=${reg.delivery.value} registers=${broadcasts.registers.size} requests=${seen.size}")
    }

    private fun quietDispatcher() = PushDispatcher(
        dedup = PushDedup(),
        clock = object : AppClock {
            override fun nowMillis(): Long = 0
            override fun elapsedMillis(): Long = 0
        },
        post = { _, _ -> },
        cancelChat = {},
        onPushWhileRunning = { _, _ -> false },
        calls = {},
        scheduleRemoval = {},
        nameFor = { null },
        selfUserId = { null },
    )

    private class MemoryPrefs : PushSettings {
        override var backgroundConnection: Boolean = false
        override var distributorChoice: String? = null
        override var batteryPromptShown: Boolean = false
        override fun clear() {
            backgroundConnection = false
            distributorChoice = null
            batteryPromptShown = false
        }
    }

    private class RecordingBroadcast : UnifiedPushBroadcaster {
        val registers = Collections.synchronizedList(mutableListOf<Sent>())
        val unregisters = Collections.synchronizedList(mutableListOf<Sent>())
        val acks = Collections.synchronizedList(mutableListOf<Sent>())
        override fun register(distributorPackage: String, token: String, vapid: String) {
            registers += Sent(distributorPackage, token, vapid)
        }
        override fun unregister(distributorPackage: String, token: String) {
            unregisters += Sent(distributorPackage, token, "")
        }
        override fun acknowledge(distributorPackage: String, token: String, id: String) {
            acks += Sent(distributorPackage, token, id)
        }
    }

    private class RecordingHost : BackgroundConnectionController.Host {
        var starts = 0
        var stops = 0
        override fun start() {
            starts++
        }
        override fun stop() {
            stops++
        }
    }

    private data class Sent(val pkg: String, val token: String, val extra: String)
    private data class Recorded(val method: String, val path: String, val body: String, val authorization: String?)

    private companion object {
        const val OUR_PACKAGE = "de.corespace.shroud"
        const val VAPID =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    }
}
