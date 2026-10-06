package de.corespace.shroud.core.auth

import de.corespace.shroud.core.model.deviceUuid
import de.corespace.shroud.core.model.userUuid
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.testing.XorSealer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * `ios/shroudTests/SessionAuthFailureTests.swift` (every case, verbatim tokens) plus the Android
 * listener path through a real `ApiClient` (settings-lock §13, §18.1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private var marks = 0
    private var wipePresented = false

    private fun session(token: String = "tok", user: String = "noah") =
        """{"token":"$token","user":{"id":"$USER_ID","username":"$user","share_code":"ABCDEFGHJK"},"device":{"id":"$DEVICE_ID"}}"""

    private fun store(dir: File = folder.root) = SessionStore(
        SealedFile(File(dir, "session.sealed"), XorSealer()),
        SealedFile(File(dir, "anchor.sealed"), XorSealer()),
        json,
    )

    /** Wired as AuthModule wires it: the client reports every authenticated answer to the session. */
    private fun controller(store: SessionStore = store()): SessionController {
        val client = ApiClient({ server.url("/api/v1").toString() }, json)
        return SessionController(
            ShroudApi(client),
            store,
            CoroutineScope(Dispatchers.Unconfined),
            wipeMarker = { marks++ },
            isWipePresented = { wipePresented },
        ).also { client.authOutcomes = it.authOutcomes }
    }

    /** iOS `applySessionForTests(sampleSession)`: a stored session, no network. */
    private fun signedIn(): SessionController {
        val st = store()
        st.save(SAMPLE)
        return controller(st)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    // ---- SessionAuthFailureTests.swift ----

    @Test
    fun ignoresFailuresWhenNotSignedIn() {
        val c = controller()
        repeat(3) { c.recordAuthenticationFailure() }
        assertEquals(0, c.consecutiveAuthenticationFailures)
        assertFalse(c.pendingFullLocalWipe.value)
        assertNull(c.session.value)
        assertEquals(0, marks)
    }

    @Test
    fun resetClearsConsecutiveFailures() {
        val c = signedIn()
        c.recordAuthenticationFailure()
        c.resetAuthenticationFailures()
        assertEquals(0, c.consecutiveAuthenticationFailures)
    }

    @Test
    fun consumePendingFullLocalWipeIsOneShot() {
        val c = controller()
        assertFalse(c.consumePendingFullLocalWipe())
        assertFalse(c.consumePendingFullLocalWipe())
    }

    @Test
    fun thresholdConstantIsAtLeastTwo() {
        assertTrue(SessionController.AUTH_FAILURE_THRESHOLD >= 2)
        assertEquals(3, SessionController.AUTH_FAILURE_THRESHOLD)
    }

    @Test
    fun threeFailuresMarkFullWipeAndKeepTheToken() {
        val c = signedIn()
        assertTrue(c.session.value != null)

        c.recordAuthenticationFailure()
        assertTrue(c.session.value != null)
        assertEquals(1, c.consecutiveAuthenticationFailures)
        assertFalse(c.pendingFullLocalWipe.value)

        c.recordAuthenticationFailure()
        assertTrue(c.session.value != null)
        assertEquals(2, c.consecutiveAuthenticationFailures)

        // The token stays until the wipe revokes it. A fourth 401 must not start a second wipe.
        c.recordAuthenticationFailure()
        assertEquals("test-token", c.session.value?.token)
        assertTrue(c.pendingFullLocalWipe.value)
        assertEquals(0, c.consecutiveAuthenticationFailures)
        // The pending marker is persisted now, so a kill before the overlay still finishes.
        assertEquals(1, marks)
        c.recordAuthenticationFailure()
        assertTrue(c.session.value != null)
        assertEquals(1, marks)
        assertEquals(WipeReason.SessionEnded, c.pendingWipeReason)
        assertTrue(c.consumePendingFullLocalWipe())
        assertFalse(c.consumePendingFullLocalWipe())
        // The stored session is untouched too.
        assertEquals("test-token", store().session?.token)
    }

    @Test
    fun deviceRemovedMarksFullWipeOnFirstAnswer() {
        val c = signedIn()
        c.recordDeviceRemoved("test-token")
        assertTrue(c.pendingFullLocalWipe.value)
        assertTrue(c.sessionEndedByDeviceRemoval.value)
        assertEquals(WipeReason.Removed, c.pendingWipeReason)
        // Like the 401 streak, the token stays for the wipe to revoke, and a second report
        // (the socket and a request both saying so) does not queue another wipe.
        assertEquals("test-token", c.session.value?.token)
        c.recordDeviceRemoved("test-token")
        c.recordAuthenticationFailure()
        assertEquals(1, marks)
        assertTrue(c.consumePendingFullLocalWipe())
        assertFalse(c.consumePendingFullLocalWipe())
    }

    @Test
    fun deviceRemovedIgnoredWhenSignedOut() {
        val c = controller()
        c.recordDeviceRemoved("test-token")
        assertFalse(c.pendingFullLocalWipe.value)
    }

    @Test
    fun deviceRemovedAboutAnotherTokenIsIgnored() {
        // A late reply to a request made before this login: the old session was removed, not this one.
        val c = signedIn()
        c.recordDeviceRemoved("an-older-token")
        assertFalse(c.pendingFullLocalWipe.value)
        assertEquals(0, marks)
    }

    @Test
    fun interruptedWipeSuppressesASecondOne() {
        val c = signedIn()
        c.beginInterruptedWipe()
        c.recordDeviceRemoved("test-token")
        assertFalse(c.pendingFullLocalWipe.value)
    }

    @Test
    fun aRemovalWhileTheWipeRunsQueuesNothing() {
        // Log Out on a removed phone: its own logout answers DEVICE_REMOVED (`:179-182`).
        val c = signedIn()
        wipePresented = true
        c.recordDeviceRemoved("test-token")
        assertFalse(c.pendingFullLocalWipe.value)
        assertFalse(c.sessionEndedByDeviceRemoval.value)
        // From now on nothing counts: the session is ending.
        repeat(3) { c.recordAuthenticationFailure() }
        assertFalse(c.pendingFullLocalWipe.value)
        assertEquals(0, marks)
    }

    @Test
    fun onlyDeviceRemovedCodeIsARemoval() {
        val removed = """{"error":{"code":"DEVICE_REMOVED","message":"x"}}"""
        val plain = """{"error":{"code":"UNAUTHORIZED","message":"x"}}"""
        assertTrue(ApiError.from(401, removed).isDeviceRemoved)
        assertTrue(ApiError.from(401, removed).isUnauthorized)
        assertFalse(ApiError.from(401, plain).isDeviceRemoved)
        assertFalse(ApiError.from(401, "").isDeviceRemoved)
        assertFalse(ApiError.from(403, removed).isDeviceRemoved)
    }

    // ---- The listener path (any authenticated request, HTTP or socket) ----

    @Test
    fun registerPersistsAndNormalises() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        val c = controller()
        val s = c.register("  Noah ", "pw")
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        // Only the hash of the case-folded name leaves the phone.
        assertEquals("\"${UsernameHash.digest("noah")}\"", body["username_hash"].toString())
        assertEquals(null, body["username"])
        // Stored lower-case (the wire form), read back as UUIDs.
        assertEquals(USER_ID.lowercase(), s.userId)
        assertEquals(DEVICE_ID.lowercase(), s.deviceId)
        assertEquals(UUID.fromString(USER_ID), s.userUuid)
        assertEquals(UUID.fromString(DEVICE_ID), s.deviceUuid)
        // A fresh process reads the same session back.
        assertEquals(s, store().session)
    }

    /** settings-lock A.4: the `AuthModelsTests.swift` vector becomes this Session (ids in their lower-case wire form). */
    @Test
    fun authSessionDecodesTheIosVector() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"token":"opaque-token-value","user":{"id":"11111111-1111-1111-1111-111111111111","username":"alice","share_code":"ABCD234567"},"device":{"id":"22222222-2222-2222-2222-222222222222","sealed_name":"c2VhbGVk"}}""",
            ),
        )
        val s = controller().login("alice", "pw")
        assertEquals(
            Session("opaque-token-value", "11111111-1111-1111-1111-111111111111", "alice", "ABCD234567", "22222222-2222-2222-2222-222222222222"),
            s,
        )
    }

    @Test
    fun loginReusesTheAnchoredDeviceAfterTheSessionWasCleared() = runTest {
        server.enqueue(MockResponse(code = 200, body = session()))
        val st = store()
        st.save(Session("old", USER_ID.lowercase(), "noah", null, ANCHOR))
        st.clear()
        controller(st).login("NOAH", "pw")
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("\"$ANCHOR\"", body["device_id"].toString())
    }

    @Test
    fun theDeviceLimitRetryKeepsTheAnchorAndNamesTheDeviceToLogOut() = runTest {
        val oldest = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        server.enqueue(
            MockResponse(
                code = 409,
                body = """{"error":{"code":"DEVICE_LIMIT","message":"full"},"oldest_device":{"id":"$oldest","created_at":"2025-03-12T08:30:00Z"}}""",
            ),
        )
        server.enqueue(MockResponse(code = 200, body = session()))
        val st = store()
        st.save(Session("old", USER_ID.lowercase(), "noah", null, ANCHOR))
        st.clear()
        val c = controller(st)
        val limit = runCatching { c.login("noah", "pw") }.exceptionOrNull() as ApiError
        assertNull(c.session.value)
        c.login("noah", "pw", limit.deviceLimit!!.oldestDevice!!.id)
        val first = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val retry = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("\"$ANCHOR\"", first["device_id"].toString())
        assertEquals(first["device_id"], retry["device_id"])
        assertEquals("\"$oldest\"", retry["replace_device_id"].toString())
        assertEquals("tok", c.session.value?.token)
    }

    @Test
    fun anAnchorThatIsNotAUuidIsNotSent() = runTest {
        server.enqueue(MockResponse(code = 200, body = session()))
        val st = store()
        st.save(Session("old", USER_ID.lowercase(), "noah", null, "d-anchor"))
        st.clear()
        controller(st).login("noah", "pw")
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("null", body["device_id"].toString())
    }

    @Test
    fun deviceRemovedOnARequestMarksTheWipeAndKeepsTheSession() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"DEVICE_REMOVED","message":"removed"}}"""))
        val c = controller()
        c.register("noah", "pw")
        assertEquals(SessionController.Validation.DeviceRemoved, c.validate())
        assertEquals("tok", c.session.value?.token)
        assertEquals("tok", store().session?.token)
        assertTrue(c.pendingFullLocalWipe.value)
        assertTrue(c.sessionEndedByDeviceRemoval.value)
        assertEquals(1, marks)
    }

    @Test
    fun aRemovalSeenOnTheSocketCountsOnlyForItsOwnSession() = runTest {
        server.enqueue(MockResponse(code = 201, body = session(token = "new")))
        val c = controller()
        c.register("noah", "pw")
        // A late answer about an older login (`SessionController.swift:174-178`): nothing happens.
        c.authOutcomes.onDeviceRemoved("old")
        assertFalse(c.pendingFullLocalWipe.value)
        // The socket's auth.error for this session's token marks the wipe at once.
        c.authOutcomes.onDeviceRemoved("new")
        assertTrue(c.pendingFullLocalWipe.value)
        assertEquals(WipeReason.Removed, c.pendingWipeReason)
    }

    @Test
    fun anyAuthenticatedRequestCountsTowardsTheStreakNotOnlyValidate() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        repeat(3) { server.enqueue(MockResponse(code = 401, body = "")) }
        val c = controller()
        c.register("noah", "pw")
        val api = ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json).also { it.authOutcomes = c.authOutcomes })
        repeat(3) { runCatching { api.contacts("tok") } }
        assertTrue(c.pendingFullLocalWipe.value)
        assertEquals("tok", c.session.value?.token)
        assertEquals(SessionController.Validation.SignedOut, c.sessionAfterFailure())
        assertEquals(WipeReason.SessionEnded, c.pendingWipeReason)
    }

    @Test
    fun plain401sEndTheSessionOnlyAfterThreeInARowAndSuccessResets() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        repeat(2) { server.enqueue(MockResponse(code = 401, body = "")) }
        server.enqueue(MockResponse(code = 200, body = """{"user":{"id":"$USER_ID","username":"noah"},"device":{"id":"$DEVICE_ID"}}"""))
        repeat(3) { server.enqueue(MockResponse(code = 401, body = "")) }
        val c = controller()
        c.register("noah", "pw")
        assertEquals(SessionController.Validation.Offline, c.validate())
        assertEquals(SessionController.Validation.Offline, c.validate())
        assertEquals(SessionController.Validation.Valid, c.validate())
        assertEquals(SessionController.Validation.Offline, c.validate())
        assertEquals(SessionController.Validation.Offline, c.validate())
        assertFalse(c.pendingFullLocalWipe.value)
        assertEquals(SessionController.Validation.SignedOut, c.validate())
        assertTrue(c.pendingFullLocalWipe.value)
        assertEquals("tok", c.session.value?.token)
    }

    @Test
    fun validateWritesBackOnlyAChangedProfile() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        server.enqueue(MockResponse(code = 200, body = """{"user":{"id":"$USER_ID","username":"noah","share_code":"ABCDEFGHJK"},"device":{"id":"$DEVICE_ID"}}"""))
        server.enqueue(MockResponse(code = 200, body = """{"user":{"id":"$USER_ID","username":"noah","share_code":"ZZZZZZZZZZ"},"device":{"id":"$DEVICE_ID"}}"""))
        val c = controller()
        c.register("noah", "pw")
        val file = File(folder.root, "session.sealed")
        val written = file.lastModified()
        file.setLastModified(written - 10_000)
        assertEquals(SessionController.Validation.Valid, c.validate())
        assertEquals(written - 10_000, file.lastModified())
        assertEquals(SessionController.Validation.Valid, c.validate())
        assertEquals("ZZZZZZZZZZ", c.session.value?.shareCode)
        assertEquals("ZZZZZZZZZZ", store().session?.shareCode)
    }

    @Test
    fun offlineNeverCounts() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        val c = controller()
        c.register("noah", "pw")
        server.close()
        repeat(4) { assertEquals(SessionController.Validation.Offline, c.validate()) }
        assertTrue(c.session.value != null)
        assertFalse(c.pendingFullLocalWipe.value)
    }

    // ---- logout(): the wipe's endLocalSession step ----

    @Test
    fun logoutForgetsSessionAndAnchorThenRevokes() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        server.enqueue(MockResponse(code = 204))
        val c = controller()
        c.register("noah", "pw")
        server.takeRequest()
        c.logout()
        assertNull(c.session.value)
        assertNull(store().session)
        assertTrue(c.hasNoSession())
        assertNull(store().anchorFor("noah"))
        val revoke = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/auth/logout", revoke.url.encodedPath)
        assertEquals("Bearer tok", revoke.headers["Authorization"])
    }

    @Test
    fun logoutEndsAForcedSignOutSoTheNextSessionCountsAgain() = runTest {
        val c = signedIn()
        c.recordDeviceRemoved("test-token")
        assertTrue(c.consumePendingFullLocalWipe())
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"DEVICE_REMOVED","message":"removed"}}"""))
        c.logout()
        // The background revoke of the old token: its DEVICE_REMOVED answer finds no session to end.
        assertEquals("Bearer test-token", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Authorization"])
        assertFalse(c.sessionEndedByDeviceRemoval.value)
        assertEquals(SessionController.Validation.DeviceRemoved, c.sessionAfterFailure())

        server.enqueue(MockResponse(code = 201, body = session(token = "next")))
        c.register("noah", "pw")
        c.recordDeviceRemoved("next")
        assertTrue(c.pendingFullLocalWipe.value)
    }

    private companion object {
        // Upper case, as a server might send them: the session stores the lower-case wire form.
        const val USER_ID = "8F14E45F-CEEA-467A-9575-3A6B7A1E6C0E"
        const val DEVICE_ID = "2E6F9B0C-1D3A-4E5B-8C7D-9F0A1B2C3D4E"
        const val ANCHOR = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

        /** `SessionAuthFailureTests.sampleSession` (`:131-137`), lower-case wire ids. */
        val SAMPLE = Session("test-token", "11111111-1111-1111-1111-111111111111", "tester", null, "22222222-2222-2222-2222-222222222222")
    }
}
