package de.corespace.shroud.ui.settings.notifications

import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.notifications.ControllerHarness
import de.corespace.shroud.core.notifications.NotificationAuthorization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sentence under "Send a Test Notification" as the screen shows it (iOS `sendTest`,
 * `NotificationsSettingsView.swift:363-373` → `NotificationsController.sendTest`,
 * `NotificationsController.swift:261-290`; settings-lock §6.4, §18.3; notifications-push §5.12.9 with
 * the UnifiedPush test channel of X1-SRV-UP and no Apple or Google wording, decision record
 * 2026-10-01).
 *
 * The screen's model on the real notifications controller and a fake server: the settings go
 * first (errors ignored), then `POST /push/test`; every status of the `"unifiedpush"` channel maps
 * to its Android sentence; with no delivery path (W3-PUSH's reason) nothing is sent and the reason
 * is the result; a phone that blocks Shroud is told so before any test.
 */
class NotificationTestOutcomeCopyTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ShroudApi
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ShroudApi(ApiClient(baseUrl = { server.url("/api/v1").toString() }, json = json))
    }

    @After
    fun tearDown() = server.close()

    private val settingsBody =
        """{"enabled":true,"show_sender":true,"reactions":true,"contact_requests":true,"sound":"default","badge":true,"badge_includes_muted":false}"""

    private class Screen(val harness: ControllerHarness, val model: NotificationsSettingsModel, val scope: CoroutineScope)

    private fun TestScope.screen(token: String? = "tok"): Screen {
        val harness = ControllerHarness(this, api = { api })
        val scope = CoroutineScope(coroutineContext + Job())
        val model = NotificationsSettingsModel(
            preferences = harness.preferences,
            pushSettings = harness.controller::pushSettings,
            sendTest = harness.controller::sendTest,
            token = { token },
            updateBadge = {},
            unmuteChat = { null },
            noun = "phone",
            scope = scope,
        )
        return Screen(harness, model, scope)
    }

    /** Runs the test row once and waits for its sentence. */
    private suspend fun Screen.tapTest(): String? {
        model.sendTestNotification()
        val done = model.state.first { !it.isTesting }
        scope.coroutineContext[Job]?.children?.forEach { it.join() }
        return done.testResult
    }

    private fun answerTest(status: String, detail: String? = null) {
        server.enqueue(MockResponse(code = 200, body = settingsBody))
        val detailField = detail?.let { ""","detail":"$it"""" } ?: ""
        server.enqueue(MockResponse(code = 200, body = """{"channel":"unifiedpush","status":"$status"$detailField}"""))
    }

    @Test
    fun everyUnifiedPushOutcome() = runTest {
        val screen = screen()
        val expected = listOf(
            Triple("sent", null, "Sent. It should arrive in a moment."),
            Triple("not_configured", null, "This server is not set up to send notifications to Android phones."),
            Triple(
                "misconfigured",
                "VAPID key mismatch",
                "The push service refused this server's setup (VAPID key mismatch). Whoever runs the server needs to check its push settings.",
            ),
            Triple("misconfigured", null, "The push service refused this server's setup. Whoever runs the server needs to check its push settings."),
            Triple("failed", null, "The push service could not be reached. Try again in a moment."),
            Triple("not_registered", null, "This phone was not registered for notifications. Shroud registered it now — try again in a moment."),
            Triple("rejected", null, "The push service refused this phone's registration. Shroud registered again — try again in a moment."),
        )
        for ((status, detail, sentence) in expected) {
            answerTest(status, detail)
            assertEquals(status, sentence, screen.tapTest())
            // Settings first ("so the test uses the sound just picked", `:368-370`), then the test.
            val settings = server.takeRequest()
            assertEquals("PUT", settings.method)
            assertEquals("/api/v1/notifications/settings", settings.url.encodedPath)
            val test = server.takeRequest()
            assertEquals("POST", test.method)
            assertEquals("/api/v1/push/test", test.url.encodedPath)
            assertEquals("Bearer tok", test.headers["Authorization"])
        }
        // not_registered and rejected register again (iOS `registerForRemoteNotifications`, `:275, :283`).
        assertEquals(2, screen.harness.pushHooks.registered)
    }

    /** The settings failing does not stop the test (`try?`, `:369`). */
    @Test
    fun aFailedSettingsSaveStillTests() = runTest {
        val screen = screen()
        server.enqueue(MockResponse(code = 500, body = """{"error":{"code":"INTERNAL","message":"Something went wrong."}}"""))
        server.enqueue(MockResponse(code = 200, body = """{"channel":"unifiedpush","status":"sent"}"""))
        assertEquals("Sent. It should arrive in a moment.", screen.tapTest())
        assertEquals(2, server.requestCount)
    }

    /** No delivery path: the push would never arrive, so none is sent and the reason is the result (W3-PUSH copy). */
    @Test
    fun noDeliveryOutcome() = runTest {
        val screen = screen()
        val reason = "No UnifiedPush distributor installed. Install one such as ntfy, or turn on Background connection."
        screen.harness.pushHooks.reason = reason
        server.enqueue(MockResponse(code = 200, body = settingsBody))
        assertEquals(reason, screen.tapTest())
        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/notifications/settings", server.takeRequest().url.encodedPath)
    }

    /** The phone decides first: blocked, not asked yet, or the Messages channel off (`:262-269`; N14). */
    @Test
    fun thePhoneBlocksBeforeTheServerIsAsked() = runTest {
        val screen = screen()
        repeat(3) { server.enqueue(MockResponse(code = 200, body = settingsBody)) }
        screen.harness.permission.authorization = NotificationAuthorization.Denied
        assertEquals("Notifications are off for Shroud. Turn them on in Android Settings, then try again.", screen.tapTest())
        screen.harness.permission.authorization = NotificationAuthorization.NotDetermined
        assertEquals("Shroud has not been allowed to notify yet. Tap Allow notifications above first.", screen.tapTest())
        screen.harness.permission.authorization = NotificationAuthorization.Authorized
        screen.harness.store.blockMessages(screen.harness.channels.messages())
        assertEquals(
            "Message notifications are turned off for Shroud in Android Settings. Turn them on, then try again.",
            screen.tapTest(),
        )
        // Only the three settings saves reached the server.
        assertEquals(3, server.requestCount)
        repeat(3) { assertEquals("/api/v1/notifications/settings", server.takeRequest().url.encodedPath) }
    }

    @Test
    fun requestErrorsAreTheServersWords() = runTest {
        val screen = screen()
        server.enqueue(MockResponse(code = 200, body = settingsBody))
        server.enqueue(MockResponse(code = 429, body = """{"error":{"code":"RATE_LIMITED","message":"Too many requests. Try again later."}}"""))
        assertEquals("Too many requests. Try again later.", screen.tapTest())
    }

    /** The row says "Sending…" while it runs, and the previous sentence goes first (`:365-366`). */
    @Test
    fun theRowWhileTesting() = runTest {
        val screen = screen()
        answerTest("sent")
        assertEquals("Sent. It should arrive in a moment.", screen.tapTest())
        answerTest("failed")
        screen.model.sendTestNotification()
        val running = screen.model.state.value
        assertTrue(running.isTesting)
        assertNull(running.testResult)
        // A second tap while it runs does nothing.
        screen.model.sendTestNotification()
        val done = screen.model.state.first { !it.isTesting }
        assertEquals("The push service could not be reached. Try again in a moment.", done.testResult)
        assertFalse(done.isTesting)
    }

    @Test
    fun signedOutDoesNothing() = runTest {
        val screen = screen(token = null)
        screen.model.sendTestNotification()
        assertFalse(screen.model.state.value.isTesting)
        assertEquals(0, server.requestCount)
    }
}
