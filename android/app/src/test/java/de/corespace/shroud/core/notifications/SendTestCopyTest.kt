package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The sentence under "Send a Test Notification" (iOS `NotificationsController.sendTest`,
 * `NotificationsController.swift:261-290`; notifications-push §5.12.9 with the transport-neutral
 * wording of web-parity §7.8 — no FCM, no Google, decision record 2026-10-01; `SendTestCopyTest` of
 * §7.2), against a fake server answering `POST /push/test`.
 */
class SendTestCopyTest {
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

    private fun TestScope.harness() = ControllerHarness(this, api = { api })

    private fun answer(body: String, code: Int = 200) {
        server.enqueue(MockResponse(code = code, body = body))
    }

    private suspend fun ControllerHarness.outcome(status: String, detail: String? = null): String {
        val detailField = detail?.let { ""","detail":"$it"""" } ?: ""
        answer("""{"channel":"unifiedpush","status":"$status"$detailField}""")
        return controller.sendTest("tok")
    }

    @Test
    fun everyServerOutcome() = runTest {
        val h = harness()
        assertEquals("Sent. It should arrive in a moment.", h.outcome("sent"))
        assertEquals(
            "This server is not set up to send notifications to Android phones.",
            h.outcome("not_configured"),
        )
        assertEquals(
            "The push service refused this server's setup (VAPID key mismatch). Whoever runs the server needs to check its push settings.",
            h.outcome("misconfigured", "VAPID key mismatch"),
        )
        assertEquals(
            "The push service refused this server's setup. Whoever runs the server needs to check its push settings.",
            h.outcome("misconfigured"),
        )
        assertEquals("The push service could not be reached. Try again in a moment.", h.outcome("failed"))
        assertEquals("The push service could not be reached. Try again in a moment.", h.outcome("something_new"))
        assertEquals(0, h.pushHooks.registered)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/push/test", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers["Authorization"])
    }

    /** `not_registered` and `rejected` register again, as iOS does (`:274-284`). */
    @Test
    fun registrationOutcomesRegisterAgain() = runTest {
        val h = harness()
        assertEquals(
            "This phone was not registered for notifications. Shroud registered it now — try again in a moment.",
            h.outcome("not_registered"),
        )
        assertEquals(1, h.pushHooks.registered)
        assertEquals(
            "The push service refused this phone's registration. Shroud registered again — try again in a moment.",
            h.outcome("rejected"),
        )
        assertEquals(2, h.pushHooks.registered)
    }

    /** A push for a phone that blocks Shroud is dropped there: no request is made (`:262-269`). */
    @Test
    fun thePhoneDecidesFirst() = runTest {
        val h = harness()
        h.permission.authorization = NotificationAuthorization.Denied
        assertEquals("Notifications are off for Shroud. Turn them on in Android Settings, then try again.", h.controller.sendTest("tok"))
        h.permission.authorization = NotificationAuthorization.NotDetermined
        assertEquals("Shroud has not been allowed to notify yet. Tap Allow notifications above first.", h.controller.sendTest("tok"))
        h.permission.authorization = NotificationAuthorization.Authorized
        h.store.blockMessages(h.channels.messages())
        assertEquals(
            "Message notifications are turned off for Shroud in Android Settings. Turn them on, then try again.",
            h.controller.sendTest("tok"),
        )
        assertEquals(0, server.requestCount)
    }

    /** No delivery path (W3-PUSH's copy): the push would never arrive, so it is not sent. */
    @Test
    fun noDeliveryPathSaysWhy() = runTest {
        val h = harness()
        h.pushHooks.reason = "No UnifiedPush distributor installed. Install one such as ntfy, or turn on Background connection."
        assertEquals(h.pushHooks.reason, h.controller.sendTest("tok"))
        assertEquals(0, server.requestCount)
    }

    /** A request error is the session's user message (`SessionController.userMessage`, `:287-289`). */
    @Test
    fun requestErrors() = runTest {
        val h = harness()
        answer("""{"error":{"code":"RATE_LIMITED","message":"Too many requests. Try again later."}}""", code = 429)
        assertEquals("Too many requests. Try again later.", h.controller.sendTest("tok"))
    }

    /** `pushSettings` sends the seven fields, `enabled` only as far as the system allows (§5.10.4). */
    @Test
    fun pushSettingsSendsTheEffectiveSettings() = runTest {
        val h = harness()
        h.preferences.sound = NotificationSound.Chime
        h.systemAllows = false
        answer("""{"enabled":false,"show_sender":true,"reactions":true,"contact_requests":true,"sound":"chime","badge":true,"badge_includes_muted":false}""")
        h.controller.pushSettings("tok")
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/notifications/settings", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(
            Json.parseToJsonElement(
                """{"enabled":false,"show_sender":true,"reactions":true,"contact_requests":true,"sound":"chime","badge":true,"badge_includes_muted":false}""",
            ).jsonObject,
            body,
        )
        assertEquals(true, h.preferences.enabled)
    }
}
