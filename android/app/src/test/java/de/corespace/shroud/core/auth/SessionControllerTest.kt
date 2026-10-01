package de.corespace.shroud.core.auth

import de.corespace.shroud.core.model.deviceUuid
import de.corespace.shroud.core.model.userUuid
import de.corespace.shroud.core.net.ApiClient
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

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private var signedOutCalls = 0
    private val signedOutWipes = mutableListOf<Boolean>()

    private fun session(token: String = "tok", user: String = "noah") =
        """{"token":"$token","user":{"id":"$USER_ID","username":"$user","share_code":"ABCDEFGHJK"},"device":{"id":"$DEVICE_ID"}}"""

    private fun store(dir: File = folder.root) = SessionStore(
        SealedFile(File(dir, "session.sealed"), XorSealer()),
        SealedFile(File(dir, "anchor.sealed"), XorSealer()),
        json,
    )

    /** Wired as AppContainer wires it: the client reports every authenticated answer to the session. */
    private fun controller(store: SessionStore = store()): SessionController {
        val client = ApiClient({ server.url("/api/v1").toString() }, json)
        return SessionController(
            ShroudApi(client),
            store,
            CoroutineScope(Dispatchers.Unconfined),
            onSignedOut = { wipe -> signedOutCalls++; signedOutWipes += wipe },
        ).also { client.authOutcomes = it.authOutcomes }
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

    @Test
    fun registerPersistsAndNormalises() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        val c = controller()
        val s = c.register("  Noah ", "pw")
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("\"noah\"", body["username"].toString())
        // Stored lower-case (the wire form), read back as UUIDs.
        assertEquals(USER_ID.lowercase(), s.userId)
        assertEquals(DEVICE_ID.lowercase(), s.deviceId)
        assertEquals(UUID.fromString(USER_ID), s.userUuid)
        assertEquals(UUID.fromString(DEVICE_ID), s.deviceUuid)
        // A fresh process reads the same session back.
        assertEquals(s, store().session)
    }

    @Test
    fun loginReusesTheAnchoredDeviceAfterLogOutClearedTheSession() = runTest {
        server.enqueue(MockResponse(code = 200, body = session()))
        val st = store()
        st.save(Session("old", USER_ID.lowercase(), "noah", null, ANCHOR))
        st.clear()
        controller(st).login("NOAH", "pw")
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("\"$ANCHOR\"", body["device_id"].toString())
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
    fun logOutWipesTheAnchorAndRevokes() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        server.enqueue(MockResponse(code = 204))
        val c = controller()
        c.register("noah", "pw")
        server.takeRequest()
        c.logOut()
        assertNull(c.session.value)
        assertEquals(1, signedOutCalls)
        // Log Out deletes the stored identity and vault too (DeviceWipeController.swift:209).
        assertEquals(listOf(true), signedOutWipes)
        assertNull(store().anchorFor("noah"))
        val revoke = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("/api/v1/auth/logout", revoke.url.encodedPath)
        assertEquals("Bearer tok", revoke.headers["Authorization"])
    }

    @Test
    fun deviceRemovedEndsTheSessionAtOnce() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"DEVICE_REMOVED","message":"removed"}}"""))
        val c = controller()
        c.register("noah", "pw")
        assertEquals(SessionController.Validation.DeviceRemoved, c.validate())
        assertNull(c.session.value)
        assertNull(store().session)
        assertEquals(1, signedOutCalls)
        assertEquals(listOf(true), signedOutWipes)
        // The root reads the reason once (wipe overlay message), then it is gone.
        assertEquals(SessionController.Validation.DeviceRemoved, c.endedByServer.value)
        assertEquals(SessionController.Validation.DeviceRemoved, c.consumeEnding())
        assertNull(c.endedByServer.value)
    }

    @Test
    fun aRemovalSeenOnTheSocketWipesOnlyItsOwnSession() = runTest {
        server.enqueue(MockResponse(code = 201, body = session(token = "new")))
        val c = controller()
        c.register("noah", "pw")
        // A late answer about an older login (SessionController.swift:170-174): nothing happens.
        c.authOutcomes.onDeviceRemoved("old")
        assertEquals("new", c.session.value?.token)
        assertEquals(0, signedOutCalls)
        assertNull(c.endedByServer.value)
        // The socket's auth.error for this session's token wipes at once.
        c.authOutcomes.onDeviceRemoved("new")
        assertNull(c.session.value)
        assertEquals(listOf(true), signedOutWipes)
        assertEquals(SessionController.Validation.DeviceRemoved, c.endedByServer.value)
    }

    @Test
    fun anyAuthenticatedRequestCountsTowardsTheStreakNotOnlyValidate() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        repeat(3) { server.enqueue(MockResponse(code = 401, body = "")) }
        val c = controller()
        c.register("noah", "pw")
        val api = ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json).also { it.authOutcomes = c.authOutcomes })
        repeat(3) { runCatching { api.contacts("tok") } }
        assertNull(c.session.value)
        assertEquals(SessionController.Validation.SignedOut, c.sessionAfterFailure())
        assertEquals(listOf(false), signedOutWipes)
    }

    @Test
    fun logOutOfOurOwnLeavesNoServerReason() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        server.enqueue(MockResponse(code = 204))
        val c = controller()
        c.register("noah", "pw")
        server.takeRequest()
        c.logOut()
        server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNull(c.endedByServer.value)
        assertEquals(SessionController.Validation.Offline, c.sessionAfterFailure())
    }

    @Test
    fun plain401sSignOutOnlyAfterThreeInARowAndSuccessResets() = runTest {
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
        assertEquals(SessionController.Validation.SignedOut, c.validate())
        assertNull(c.session.value)
        // A plain sign-out keeps the stored keys (RootView.swift:213) and the anchor for the next login.
        assertEquals(listOf(false), signedOutWipes)
        assertEquals(DEVICE_ID.lowercase(), store().anchorFor("noah"))
    }

    @Test
    fun offlineNeverCounts() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        val c = controller()
        c.register("noah", "pw")
        server.close()
        repeat(4) { assertEquals(SessionController.Validation.Offline, c.validate()) }
        assertTrue(c.session.value != null)
        assertFalse(signedOutCalls > 0)
    }

    private companion object {
        // Upper case, as a server might send them: the session stores the lower-case wire form.
        const val USER_ID = "8F14E45F-CEEA-467A-9575-3A6B7A1E6C0E"
        const val DEVICE_ID = "2E6F9B0C-1D3A-4E5B-8C7D-9F0A1B2C3D4E"
        const val ANCHOR = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
    }
}
