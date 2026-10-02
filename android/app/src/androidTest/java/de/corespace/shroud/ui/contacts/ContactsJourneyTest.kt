package de.corespace.shroud.ui.contacts

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import de.corespace.shroud.AppContainer
import de.corespace.shroud.LOCAL_NETWORK_PERMISSION
import de.corespace.shroud.MainActivity
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.keys.DeviceLock
import de.corespace.shroud.core.net.ServerConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.regex.Pattern

/**
 * The contacts journey on a device, through the real `MainActivity` and two web peers (W3-CONTACTS-UI
 * acceptance; plan §6.4 rows that need the contacts screens; for C17):
 *
 * 1. Sign Up through the UI (as C4's journey), then the Contacts tab.
 * 2. **Add by username:** Add Contact, the first peer's username, Add → "Request sent to <name>". The
 *    peer accepts; the contact appears in its letter section, its presence line reading "online"
 *    while the peer's socket is up.
 * 3. **Accept:** the second peer asks; its row appears under "Pending" ("<name>, wants to connect");
 *    Accept → the contact moves into the list and Pending goes.
 * 4. **Profile verify:** the first contact's chat → the header → the profile. The safety number on
 *    screen is the one core computes (12 groups of 5 digits); Mark as Verified → "Verified", and core
 *    says the peer is verified.
 *
 * Driven with UiAutomator on what TalkBack reads (labels, descriptions, texts), so the real frame
 * clock, sockets and lifecycle take part. Needs the throwaway stack (`android/e2e/stack-up.sh`), two
 * web peers on consecutive ports (`android/e2e/engine-e2e.sh` starts them; the first at `shroudPeer`,
 * default `http://10.0.2.2:8099`), `android/e2e/emulator-setup.sh` (PIN 1234, Android 17's
 * local-network grant) and an unlocked phone (`android/e2e/unlock.sh`). Skipped when the stack or
 * a peer cannot be reached, unless `shroudRequired=true`.
 *
 * Owed to C17, which runs it: `ANDROID_SERIAL=emulator-<port> gw :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=de.corespace.shroud.ui.contacts.ContactsJourneyTest`
 * (never on emulator-5554).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ContactsJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val app = instrumentation.targetContext.applicationContext as ShroudApplication
    private val container: AppContainer get() = app.container
    private val arguments = InstrumentationRegistry.getArguments()
    private val baseUrl: String = arguments.getString("shroudApi") ?: "http://10.0.2.2:8080/api/v1"
    private val required = arguments.getString("shroudRequired") == "true"
    private val peerUrls: List<String> = run {
        val first = URI((arguments.getString("shroudPeer") ?: "http://10.0.2.2:8099").trimEnd('/'))
        listOf(first, URI(first.scheme, null, first.host, first.port + 1, null, null, null)).map { it.toString().trimEnd('/') }
    }
    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousServer: ServerConfiguration? = null
    private val peers = ArrayList<Peer>()

    private val username = "c8_" + UUID.randomUUID().toString().replace("-", "").take(12)
    private val password = "C8-journey-" + UUID.randomUUID().toString().take(8) + "!7"

    /** A web peer (`android/e2e/peer`): one web account, driven over its control API. */
    private inner class Peer(private val url: String) {
        lateinit var id: UUID
        lateinit var name: String

        fun call(method: String, path: String, body: JsonObject? = null): JsonObject = peerCall(url, method, path, body)

        fun create(): Peer = apply {
            val account = call("POST", "/account")
            id = UUID.fromString(account.str("userId"))
            name = account.str("username")
        }
    }

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 37) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, LOCAL_NETWORK_PERMISSION)
        // P6a asks for notifications on the first unlock; granted up front, its dialog never covers the journey.
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        require("the local stack is not reachable at $baseUrl (android/e2e/stack-up.sh)", reachable("$baseUrl/health/live"))
        for (url in peerUrls) require("the web peer is not reachable at $url (android/e2e/engine-e2e.sh)", reachable("$url/health"))
        DeviceLock.ensureUnlocked()
        require("needs a screen lock: android/e2e/emulator-setup.sh", DeviceLock.isSecure)
        if (container.auth.sessionController.session.value != null) wipeAndWait()
        val server = container.serverConfiguration
        val url = URI(baseUrl)
        if (server.configuration.value.resolvedBaseUrl != baseUrl) {
            previousServer = server.configuration.value
            server.save(ServerConfiguration.localDevelopment(url.host, url.port))
        }
    }

    @After
    fun tearDown() {
        for (peer in peers) runCatching { peer.call("POST", "/logout") }
        // A failed run leaves nothing of its account on the phone.
        if (container.auth.sessionController.session.value != null) runCatching { wipeAndWait() }
        scenario?.close()
        previousServer?.let { container.serverConfiguration.save(it) }
    }

    @Test
    fun addByUsernameAcceptARequestAndVerifyTheSafetyNumber() {
        val friend = Peer(peerUrls[0]).also { peers += it }.create()
        val asker = Peer(peerUrls[1]).also { peers += it }.create()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        signUpThroughTheUi()
        tap("Contacts")
        await("Your invite")

        // 2. Add by username: the request goes out, the tab says so.
        tap("Add contact")
        await("Add Contact")
        val field = awaitObject(By.clazz("android.widget.EditText"))
        field.click()
        field.text = friend.name
        SystemClock.sleep(150)
        tapButton("Add")
        await("Request sent to ${friend.name}")

        // The friend accepts with its socket up: the row arrives (contact.* event or the poll) and its
        // presence line reads "online" (the sweep is forced on a roster change).
        friend.call("POST", "/socket", buildJsonObject { put("on", true) })
        assertEquals(1, friend.call("POST", "/contacts/accept").int("accepted"))
        awaitObject(By.desc(Pattern.compile(Pattern.quote(friend.name) + ", .+")), timeoutMs = 45_000)
        awaitObject(By.desc("${friend.name}, online"), timeoutMs = 45_000)

        // 3. Accept: the asker's request under Pending, answered from the row.
        val me = container.auth.sessionController.session.value!!.userId
        asker.call("POST", "/contacts/request", buildJsonObject { put("userId", me) })
        await("Pending", timeoutMs = 45_000)
        await("${asker.name}, wants to connect")
        tapButton("Accept")
        awaitObject(By.desc(Pattern.compile(Pattern.quote(asker.name) + ", (?!wants to connect).+")), timeoutMs = 45_000)
        assertTrue("Pending goes once the answer landed", DeviceLock.waitFor(30_000) { device.findObject(By.text("Pending")) == null })
        assertTrue(onMain { container.contacts.controller.contacts.value.any { it.userId == asker.id } })

        // 4. The friend's chat, its header, the profile: the number core computes, then verified.
        awaitObject(By.desc(Pattern.compile(Pattern.quote(friend.name) + ", .+"))).click()
        awaitObject(By.textStartsWith(friend.name)).click()
        await("safety number", timeoutMs = 30_000)
        val shown = awaitObject(By.text(SAFETY_NUMBER), timeoutMs = 30_000).text
        val expected = onMain { container.contacts.peerIdentities.safetyNumber(friend.id) }
        assertNotNull(expected)
        assertEquals("the profile shows core's number", expected, shown)
        tap("Mark as Verified")
        await("Verified")
        assertTrue(onMain { container.contacts.peerIdentities.isSafetyVerified(friend.id) })
    }

    // ---- Steps ----

    /** Welcome → Sign Up → the chats (C4's journey, `LockOnboardJourneyTest`). */
    private fun signUpThroughTheUi() {
        tap("Start Messaging")
        await("Create Account")
        type("Username", username)
        type("Password", password)
        tap("Continue")
        awaitObject(By.desc(Pattern.compile("Word 12: .+")), timeoutMs = 30_000)
        tap("I wrote down my encryption phrase")
        tap("Create Account")
        val unlocked = DeviceLock.waitFor(60_000) { onMain { container.shell.controller.router.isUnlocked } }
        assertTrue("the chats did not open", unlocked)
    }

    private fun wipeAndWait() {
        val wipe = container.auth.deviceWipe
        instrumentation.runOnMainSync { wipe.start(WipeReason.Logout) }
        DeviceLock.waitFor(60_000) { wipe.phase.value == WipePhase.Idle && !wipe.isPresented.value }
    }

    // ---- UiAutomator on TalkBack's labels ----

    private fun selectors(label: String): List<BySelector> = listOf(By.desc(label), By.text(label))

    private fun awaitObject(selector: BySelector, timeoutMs: Long = 15_000): UiObject2 {
        var found: UiObject2? = null
        DeviceLock.waitFor(timeoutMs) { device.findObject(selector)?.also { found = it } != null }
        return checkNotNull(found) { "nothing matches $selector within $timeoutMs ms" }
    }

    private fun await(label: String, timeoutMs: Long = 15_000): UiObject2 {
        var found: UiObject2? = null
        DeviceLock.waitFor(timeoutMs) {
            found = selectors(label).firstNotNullOfOrNull { device.findObject(it) }
            found != null
        }
        return checkNotNull(found) { "\"$label\" not on screen within $timeoutMs ms" }
    }

    /** Taps the control labelled [label] once it is on screen and enabled. */
    private fun tap(label: String) {
        val target = await(label)
        DeviceLock.waitFor(10_000) { target.isEnabled }
        target.click()
        device.waitForIdle()
    }

    /** Taps the clickable control whose text is [label] (the same words may also be a description). */
    private fun tapButton(label: String) {
        val target = awaitObject(By.text(label).clickable(true))
        DeviceLock.waitFor(10_000) { target.isEnabled }
        target.click()
        device.waitForIdle()
    }

    /** Sets the text of the field TalkBack calls [label] (accessibility `ACTION_SET_TEXT`). */
    private fun type(label: String, text: String) {
        val field = awaitObject(By.desc(label))
        field.click()
        field.text = text
        SystemClock.sleep(150)
    }

    // ---- Plumbing ----

    private fun require(message: String, condition: Boolean) {
        if (required) assertTrue(message, condition) else assumeTrue(message, condition)
    }

    private fun reachable(url: String): Boolean = runCatching {
        (URI(url).toURL().openConnection() as HttpURLConnection).run {
            connectTimeout = 3_000
            readTimeout = 3_000
            try {
                responseCode in 200..299
            } finally {
                disconnect()
            }
        }
    }.getOrDefault(false)

    private fun peerCall(base: String, method: String, path: String, body: JsonObject?): JsonObject {
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 60_000
            connection.requestMethod = method
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write((body ?: JsonObject(emptyMap())).toString().toByteArray()) }
            }
            val ok = connection.responseCode in 200..299
            val text = (if (ok) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            check(ok) { "peer $method $path: ${connection.responseCode} $text" }
            return container.json.parseToJsonElement(text).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    /** The controllers are main-confined (R1). */
    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String): Int = str(key).toInt()

    private companion object {
        /** iOS's safety number: 12 groups of 5 digits (`IdentitySafetyNumber.swift:10-36`). */
        val SAFETY_NUMBER: Pattern = Pattern.compile("\\d{5}( \\d{5}){11}")
    }
}
