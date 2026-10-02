package de.corespace.shroud.e2e

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.AppContainer
import de.corespace.shroud.LOCAL_NETWORK_PERMISSION
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.calls.CallController
import de.corespace.shroud.core.calls.CallPhase
import de.corespace.shroud.core.calls.ScreenCaptureGrant
import de.corespace.shroud.core.calls.media.CallMediaEngine
import de.corespace.shroud.core.keys.DeviceLock
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ServerConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One voice call each way between this phone's real WebRTC engine and one scripted web peer
 * (`android/e2e/peer/peer.ts --media chrome`). Offer, answer, ICE and hangup go through the
 * throwaway API. The peer listens on the host; this process uses `10.0.2.2` (instrumentation
 * `shroudApi` and `shroudPeer`), the same way [EngineE2eTest] does.
 *
 * The offer carries a screen m-line. After the answer, [CallMediaEngine.canSendScreen] is how
 * that section is checked: the negotiated direction has to be sendable.
 *
 * Real screen frames are not asserted. [CallMediaEngine.startScreen] needs a MediaProjection
 * token, and the platform only hands one over after the system consent dialog. This test does
 * not add an Activity to obtain one, and it does not call a deprecated API to fake one.
 * [CallMediaEngine.startScreen] with an empty grant is false, which is the consent block.
 *
 * Run on one emulator, with `android/e2e/stack-up.sh` and one `--media chrome` peer already up.
 * A missing stack or peer fails the test.
 */
@RunWith(AndroidJUnit4::class)
class WebRtcCallE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShroudApplication
    private val container: AppContainer get() = app.container
    private val arguments = InstrumentationRegistry.getArguments()
    private val baseUrl: String = arguments.getString("shroudApi") ?: "http://10.0.2.2:18081/api/v1"
    private val peerUrl: String = (arguments.getString("shroudPeer") ?: "http://10.0.2.2:18099").trimEnd('/')
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private var previousServer: ServerConfiguration? = null
    private var signedUp = false
    private var myName = ""
    private var engine: CallMediaEngine? = null

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 37) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, LOCAL_NETWORK_PERMISSION)
        }
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        check(reachable("$baseUrl/health/live")) { "the local stack is not reachable at $baseUrl" }
        check(reachable("$peerUrl/health")) { "the web peer is not reachable at $peerUrl" }
        DeviceLock.ensureUnlocked()
        check(DeviceLock.isSecure) { "needs a screen lock: android/e2e/emulator-setup.sh" }
        val server = container.serverConfiguration
        val url = baseUrl.toHttpUrl()
        if (server.configuration.value.resolvedBaseUrl != baseUrl) {
            previousServer = server.configuration.value
            server.save(ServerConfiguration.localDevelopment(url.host, url.port))
        }
    }

    @After
    fun tearDown() {
        runCatching { peer("POST", "/logout") }
        if (signedUp && container.auth.sessionController.session.value != null) {
            runCatching {
                onMain { container.auth.deviceWipe.start(WipeReason.Logout) }
                val wipe = container.auth.deviceWipe
                eventually("the wipe finishes", 60_000) {
                    if (wipe.phase.value == WipePhase.Idle && !wipe.isPresented.value) Unit else null
                }
            }
        }
        runCatching { engine?.close() }
        engine = null
        previousServer?.let { container.serverConfiguration.save(it) }
    }

    @Test
    fun voiceCallConnectsBothWaysAndTheScreenSectionIsSendable() {
        val account = peer("POST", "/account")
        val peerId = UUID.fromString(account.str("userId"))
        val peerName = account.str("username")
        signUp()
        onMain { container.messaging.controller.start() }
        val contacts = container.contacts.controller
        val added = onMain { contacts.add(peerName) }
        assertTrue(added is AddContactOutcome.Requested || added is AddContactOutcome.Added)
        check(peer("POST", "/contacts/accept").int("accepted") == 1)
        eventually("the peer is a contact") {
            onMain {
                contacts.refresh(force = true)
                contacts.contacts.value.firstOrNull { it.userId == peerId }
            }
        }
        onMain { container.messaging.controller.loadThread(peerId) }
        peer("POST", "/socket", buildJsonObject { put("on", true) })
        onMain { container.messaging.controller.sendText("Call me", peerId) }
        eventually("the call secret exists", 20_000) {
            onMain { container.calls.secrets.refreshAll() }
            runBlocking(Dispatchers.IO) { container.calls.secrets.secret(peerId) }
        }

        val calls: CallController = container.calls.controller
        val media = CallMediaEngine(app)
        engine = media
        onMain { calls.attach(media, E2eCallSystem()) }

        // This phone calls. The offer's second video section is the screen.
        onMain { calls.startCall(peerId, peerName, CallModality.Voice) }
        eventually("the web rings", 20_000) { peer("GET", "/call").takeIf { it.str("phase") == "incoming" } }
        peer("POST", "/call/accept")
        eventually("connected here", 45_000) {
            onMain { calls.ui.value.active?.takeIf { it.phase == CallPhase.Active && it.isOutgoing } }
        }
        eventually("connected on the web", 20_000) {
            peer("GET", "/call").takeIf { it.str("phase") == "active" && it.bool("connected") }
        }
        assertTrue("the negotiated screen section is not sendable", onMain { media.canSendScreen })
        eventually("the web left the screen section sendable", 15_000) {
            peer("GET", "/call").takeIf { it.bool("canShare") }
        }
        // No MediaProjection consent in this process, so capture does not start.
        assertFalse(onMain { media.startScreen(ScreenCaptureGrant(0, Intent())) })
        onMain { calls.hangup() }
        eventually("the web hears the hangup", 20_000) {
            peer("GET", "/call").takeIf { it.str("phase") == "ended" || it.str("phase") == "idle" }
        }
        eventually("ended here", 20_000) {
            onMain { if (calls.ui.value.active.let { it == null || it.phase == CallPhase.Ending }) Unit else null }
        }

        // The web calls back. Same engine, a new peer connection inside it.
        eventually("idle again", 20_000) { onMain { if (calls.ui.value.active == null) Unit else null } }
        peer("POST", "/call/start", buildJsonObject { put("peer", Ids.wire(me())); put("username", myName) })
        eventually("Android rings", 20_000) {
            onMain { calls.ui.value.active?.takeIf { it.phase == CallPhase.IncomingRinging && !it.isOutgoing } }
        }
        onMain { calls.acceptIncoming() }
        eventually("connected here again", 45_000) {
            onMain { calls.ui.value.active?.takeIf { it.phase == CallPhase.Active } }
        }
        eventually("connected on the web again", 20_000) {
            peer("GET", "/call").takeIf { it.str("phase") == "active" && it.bool("connected") }
        }
        assertTrue("the answer did not leave the screen section sendable", onMain { media.canSendScreen })
        assertFalse(onMain { media.startScreen(ScreenCaptureGrant(0, Intent())) })
        peer("POST", "/call/hangup")
        eventually("the web's hangup ends the call here", 20_000) {
            onMain { if (calls.ui.value.active.let { it == null || it.phase == CallPhase.Ending }) Unit else null }
        }
        eventually("the history lists both calls") {
            onMain {
                calls.refreshHistory()
                calls.history.value.recent.takeIf { it.size >= 2 }
            }
        }
    }

    private fun signUp() {
        val auth = container.auth.sessionController
        myName = "e2e_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val session = onMain { auth.register(myName, "Engine e2e passphrase " + UUID.randomUUID()) }
        signedUp = true
        val words = container.keys.bip39.generate()
        onMain { container.keys.cryptoController.establishFromSignup(words, session) }
    }

    private fun me(): UUID = UUID.fromString(container.auth.sessionController.session.value!!.userId)

    private fun peer(method: String, path: String, body: JsonObject? = null): JsonObject {
        val request = Request.Builder().url(peerUrl + path).apply {
            if (method == "POST") post((body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON))
        }.build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) { "peer $method $path failed: ${response.code}" }
            return container.json.parseToJsonElement(text).jsonObject
        }
    }

    private fun reachable(url: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    /** The block runs off the main thread. Controller reads inside it go through [onMain], so a peer HTTP call cannot stall call signalling. */
    private fun <T : Any> eventually(what: String, timeoutMs: Long = 30_000, block: () -> T?): T {
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
        val web = runCatching { peer("GET", "/call") }.getOrNull()
        throw AssertionError("timed out: $what${web?.let { " (web phase=${it.optStr("phase")} ice=${it.optStr("ice")} connected=${it.optStr("connected")})" } ?: ""}${last?.let { " ($it)" } ?: ""}")
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.optStr(key: String): String? = (get(key) as? JsonPrimitive)?.content
    private fun JsonObject.int(key: String): Int = str(key).toInt()
    private fun JsonObject.bool(key: String): Boolean = str(key).toBoolean()

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
