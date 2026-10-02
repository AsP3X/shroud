package de.corespace.shroud.e2e

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.AppContainer
import de.corespace.shroud.LOCAL_NETWORK_PERMISSION
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.auth.WakeResult
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.keys.DeviceLock
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.push.PushCopy
import de.corespace.shroud.core.push.UnifiedPushState
import de.corespace.shroud.core.realtime.RealtimeClient
import de.corespace.shroud.core.storage.PrefsFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Wave 3 system paths that can run inside the process (G8). Killed-app, Doze, standby and reboot
 * are `android/e2e/system-e2e.sh`: `am force-stop` would kill this instrumentation.
 *
 * [aliveProcessPaths] keeps the process: background delivery while offline, push alone, both paths
 * once, a read that closes the chat, a ring and a missed call with [de.corespace.shroud.core.calls.CallController.attach],
 * then removal only after `GET /auth/me` says the device is still a member and, after revoke, only
 * because the removal worker confirms.
 *
 * [prepareClosedApp] signs in, registers the distributor, turns the background connection on and
 * returns still signed in. The shell then force-stops.
 */
@RunWith(AndroidJUnit4::class)
class SystemE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShroudApplication
    private val container: AppContainer get() = app.container
    private val arguments = InstrumentationRegistry.getArguments()
    private val baseUrl: String = arguments.getString("shroudApi") ?: "http://10.0.2.2:8080/api/v1"
    private val ntfyPort: Int = arguments.getString("shroudNtfyPort")?.toIntOrNull() ?: 2586
    private val peerUrls: List<String> = run {
        val first = (arguments.getString("shroudPeer") ?: "http://10.0.2.2:8099").toHttpUrl()
        val count = arguments.getString("shroudPeerCount")?.toIntOrNull() ?: 2
        (0 until count).map { first.newBuilder().port(first.port + it).build().toString().trimEnd('/') }
    }
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private var previousServer: ServerConfiguration? = null
    private var signedUp = false
    private var retainSession = false

    private var myName = ""
    private var myPassword = ""
    private var myWords: List<String> = emptyList()

    private val messaging: MessagingController get() = container.messaging.controller
    private val me: UUID get() = UUID.fromString(container.auth.sessionController.session.value!!.userId)
    private val required = arguments.getString("shroudRequired") == "true"

    private fun require(message: String, condition: Boolean) = if (required) assertTrue(message, condition) else assumeTrue(message, condition)

    private inner class Peer(val index: Int) {
        lateinit var id: UUID
        lateinit var name: String

        fun call(method: String, path: String, body: JsonObject? = null): JsonObject = peerCall(peerUrls[index], method, path, body)

        fun create(): Peer = apply {
            val account = call("POST", "/account")
            id = UUID.fromString(account.str("userId"))
            name = account.str("username")
        }

        fun sendText(text: String, to: UUID = me): UUID =
            UUID.fromString(call("POST", "/text", buildJsonObject { put("peer", Ids.wire(to)); put("text", text) }).str("id"))

        fun socket(on: Boolean) {
            call("POST", "/socket", buildJsonObject { put("on", on) })
        }

        fun online(user: UUID): Boolean = call("GET", "/presence?user=${Ids.wire(user)}").bool("online")
    }

    private val usedPeers = ArrayList<Peer>()

    private fun peer(index: Int): Peer = Peer(index).also { usedPeers += it }

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 37) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, LOCAL_NETWORK_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        require("the local stack is not reachable at $baseUrl", reachable("$baseUrl/health/live"))
        for (url in peerUrls) require("the web peer is not reachable at $url", reachable("$url/health"))
        DeviceLock.ensureUnlocked()
        require("needs a screen lock: android/e2e/emulator-setup.sh", DeviceLock.isSecure)
        val server = container.serverConfiguration
        val url = baseUrl.toHttpUrl()
        if (server.configuration.value.resolvedBaseUrl != baseUrl) {
            previousServer = server.configuration.value
            server.save(ServerConfiguration.localDevelopment(url.host, url.port))
        }
        // apply() does not survive am force-stop. The shell's killed-app steps need this URL.
        commit(PrefsFiles.SERVER)
        tellStub()
    }

    @After
    fun tearDown() {
        if (retainSession) return
        for (peer in usedPeers) runCatching { peer.call("POST", "/logout") }
        if (signedUp && container.auth.sessionController.session.value != null && sessionFile().exists()) {
            runCatching {
                onMain { container.auth.deviceWipe.start(WipeReason.Logout) }
                val wipe = container.auth.deviceWipe
                eventuallyBlocking("the wipe finishes", 60_000) {
                    if (wipe.phase.value == WipePhase.Idle && !wipe.isPresented.value) Unit else null
                }
            }
        }
        previousServer?.let { container.serverConfiguration.save(it) }
    }

    @Test
    fun aliveProcessPaths() {
        onMain { container.push.registration.chooseDistributor(null) }
        val friend = peer(0).create()
        signUp()
        startAndBefriend(friend)
        val other = peer(1)
        other.call("POST", "/login", buildJsonObject {
            put("username", myName)
            put("password", myPassword)
            put("words", buildJsonArray { myWords.forEach { add(JsonPrimitive(it)) } })
        })
        val myDevice = container.auth.sessionController.session.value!!.deviceId

        onMain { container.push.registration.setBackgroundConnection(true) }
        dismissSettings()
        eventually("the background socket is connected", 45_000) {
            if (container.realtime.client.state.value is RealtimeClient.ConnectionState.Connected) Unit else null
        }
        waitDump("the background connection is posted") { connected(it) }

        onMain { container.calls.controller.attach(E2eCallEngine(), container.callsSystem.system) }
        friend.socket(on = true)
        friend.call("POST", "/call/start", buildJsonObject { put("peer", Ids.wire(me)); put("username", myName) })
        waitDump("the incoming call is posted", 40_000) { incoming(it) }
        friend.call("POST", "/call/hangup")
        waitDump("the missed call offers Call back", 40_000) { missed(it) && !incoming(it) }

        onMain { container.lockChatsInMemory() }
        container.notifications.nameCache.remember(friend.id, friend.name)
        container.notifications.nameCache.drain()
        onMain { container.realtime.client.deliverFocus() }
        eventuallyBlocking("this user is connected and not online", 30_000) {
            val up = container.realtime.client.state.value is RealtimeClient.ConnectionState.Connected
            if (up && connected(dumpShade()) && !friend.online(me)) Unit else null
        }

        dismissMessages()
        friend.sendText("m")
        waitDump("the background socket posts one named message") { named(it, friend.name) == 1 }

        onMain { container.push.registration.chooseDistributor(DISTRIBUTOR) }
        eventually("UnifiedPush is registered", 45_000) {
            if (container.push.registration.delivery.value.unifiedPush is UnifiedPushState.Registered) Unit else null
        }
        commit(PrefsFiles.PUSH)

        onMain {
            messaging.leaveForeground(keepSocket = false)
            container.push.registration.setBackgroundConnection(false)
        }
        eventually("the socket is down so only push can deliver", 20_000) {
            val state = container.realtime.client.state.value
            if (state is RealtimeClient.ConnectionState.Disconnected || state is RealtimeClient.ConnectionState.Failed) Unit else null
        }
        dismissMessages()
        friend.sendText("p")
        waitDump("push alone posts one named message", 60_000) { named(it, friend.name) == 1 }

        onMain { container.push.registration.setBackgroundConnection(true) }
        dismissSettings()
        eventually("the background socket is back", 45_000) {
            if (container.realtime.client.state.value is RealtimeClient.ConnectionState.Connected) Unit else null
        }
        waitDump("the background connection is posted again") { connected(it) }
        dismissMessages()
        friend.sendText("b")
        waitDump("both paths post the message once") { named(it, friend.name) == 1 }

        other.call("POST", "/read", buildJsonObject { put("peer", Ids.wire(friend.id)) })
        waitDump("the read closes the chat and leaves the background connection", 45_000) {
            named(it, friend.name) == 0 && connected(it)
        }

        assertEquals(WakeResult.NoData, onMain { container.auth.removalWake.handle() })
        other.call("POST", "/revoke", buildJsonObject { put("deviceId", myDevice) })
        eventuallyBlocking("the session file is gone after the worker confirms", 70_000) {
            if (!sessionFile().exists()) Unit else null
        }
        signedUp = false
    }

    /**
     * Account the shell force-stops. Leaves the session, the server URL and both delivery paths
     * in place; [tearDown] does not log out.
     */
    @Test
    fun prepareClosedApp() {
        retainSession = true
        onMain { container.push.registration.chooseDistributor(DISTRIBUTOR) }
        val friend = peer(0).create()
        signUp()
        startAndBefriend(friend)
        peer(1).call("POST", "/login", buildJsonObject {
            put("username", myName)
            put("password", myPassword)
            put("words", buildJsonArray { myWords.forEach { add(JsonPrimitive(it)) } })
        })
        onMain { container.push.registration.setBackgroundConnection(true) }
        dismissSettings()
        eventually("UnifiedPush is registered for the killed-app steps", 45_000) {
            if (container.push.registration.delivery.value.unifiedPush is UnifiedPushState.Registered) Unit else null
        }
        eventually("the background socket is connected", 45_000) {
            if (container.realtime.client.state.value is RealtimeClient.ConnectionState.Connected) Unit else null
        }
        commit(PrefsFiles.SERVER)
        commit(PrefsFiles.PUSH)
        assertTrue(sessionFile().exists())
    }

    private fun signUp() {
        val auth = container.auth.sessionController
        myName = "e2e_" + UUID.randomUUID().toString().replace("-", "").take(12)
        myPassword = "Engine e2e passphrase " + UUID.randomUUID()
        val session = onMain { auth.register(myName, myPassword) }
        signedUp = true
        val keys = container.keys
        myWords = keys.bip39.generate()
        onMain { keys.cryptoController.establishFromSignup(myWords, session) }
        assertEquals(session.userId, keys.cryptoController.unlockedUserId.value)
    }

    private fun startAndBefriend(peer: Peer) {
        onMain { messaging.start() }
        val contacts = container.contacts.controller
        val added = onMain { contacts.add(peer.name) }
        assertTrue("$added", added is AddContactOutcome.Requested || added is AddContactOutcome.Added)
        assertEquals(1, peer.call("POST", "/contacts/accept").int("accepted"))
        eventually("${peer.name} becomes a contact") {
            contacts.refresh(force = true)
            contacts.contacts.value.firstOrNull { it.userId == peer.id }
        }
    }

    private fun tellStub() {
        val intent = Intent(ACTION_CONFIG).setClassName(DISTRIBUTOR, "$DISTRIBUTOR.DistributorReceiver")
        intent.putExtra("port", ntfyPort)
        app.sendBroadcast(intent)
        val start = Intent()
            .setClassName(DISTRIBUTOR, "$DISTRIBUTOR.StarterActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("port", ntfyPort)
        app.startActivity(start)
    }

    private fun dismissSettings() {
        shell("input keyevent 4")
    }

    private fun dismissMessages() {
        val manager = app.getSystemService(NotificationManager::class.java)
        for (posted in manager.activeNotifications) {
            val text = posted.notification.extras.getCharSequence("android.text")?.toString().orEmpty()
            if (text == NotificationKind.Message.bodyLine || text.endsWith(" new messages")) {
                manager.cancel(posted.tag, posted.id)
            }
        }
    }

    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }

    private fun dumpShade(): String = shell("dumpsys notification --noredact")

    private fun blocks(dump: String): List<String> =
        dump.split("NotificationRecord(").filter { "pkg=${app.packageName}" in it }

    private fun field(block: String, key: String): List<String> =
        Regex("""android\.$key=String \((.*?)\)""").findAll(block).map { it.groupValues[1] }.toList()

    private fun named(dump: String, title: String): Int {
        val rows = blocks(dump)
        val doubled = rows.any { block -> field(block, "text").any { it.endsWith(" new messages") } }
        if (doubled) return 2
        return rows.count { block ->
            title in field(block, "title") && NotificationKind.Message.bodyLine in field(block, "text")
        }
    }

    private fun connected(dump: String): Boolean =
        blocks(dump).any { PushCopy.CONNECTED in field(it, "text") }

    /**
     * Current `dumpsys notification` prints a call pending intent as
     * `de.corespace.shroud startActivity` and omits the activity class. The ring is the
     * CallStyle record: our package and `calls.incoming`. API 31+ keeps the text
     * "Incoming voice call". API 30's CallStyle fallback replaces it with "Incoming call".
     */
    private fun incoming(dump: String): Boolean =
        blocks(dump).any { block ->
            val text = field(block, "text")
            ("Incoming voice call" in text || "Incoming call" in text) && "channel=calls.incoming" in block
        }

    private fun missed(dump: String): Boolean =
        blocks(dump).any { "Call back" in it && "Missed call" in field(it, "text") }

    private fun waitDump(what: String, timeoutMs: Long = 30_000, match: (String) -> Boolean): String {
        var last = ""
        try {
            return eventuallyBlocking(what, timeoutMs) {
                val dump = dumpShade()
                last = dump
                if (match(dump)) dump else null
            }
        } catch (error: AssertionError) {
            val brief = blocks(last).map { block ->
                val channel = Regex("""channel=([A-Za-z0-9._]+)""").find(block)?.groupValues?.get(1)
                "channel=$channel title=${field(block, "title")} text=${field(block, "text")}"
            }.distinct().take(8).joinToString("\n")
            throw AssertionError("${error.message}\n$brief", error)
        }
    }

    private fun sessionFile(): File = File(app.noBackupFilesDir, "session.sealed")

    private fun commit(name: String) {
        assertTrue(app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit())
    }

    private fun peerCall(base: String, method: String, path: String, body: JsonObject? = null): JsonObject {
        val request = Request.Builder().url(base + path).apply {
            if (method == "POST") post((body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON))
        }.build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) { "peer $method $path: ${response.code}" }
            return container.json.parseToJsonElement(text).jsonObject
        }
    }

    private fun reachable(url: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun <T : Any> eventually(what: String, timeoutMs: Long = 30_000, block: suspend () -> T?): T =
        eventuallyBlocking(what, timeoutMs) { onMain(block) }

    private fun <T : Any> eventuallyBlocking(what: String, timeoutMs: Long, block: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                block()?.let { return it }
            } catch (e: Exception) {
                last = e
            }
            Thread.sleep(300)
        }
        throw AssertionError("timed out: $what${last?.let { " ($it)" } ?: ""}")
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String): Int = str(key).toInt()
    private fun JsonObject.bool(key: String): Boolean = str(key).toBoolean()

    private companion object {
        val JSON = "application/json".toMediaType()
        const val DISTRIBUTOR = "de.corespace.shroud.upstub"
        const val ACTION_CONFIG = "de.corespace.shroud.upstub.CONFIG"
    }
}
