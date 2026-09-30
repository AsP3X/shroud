package de.corespace.shroud.core.auth

import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.Sealer
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

/** A reversible stand-in for the Keystore: unit tests have none. */
class XorSealer : Sealer {
    override fun seal(plaintext: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun open(sealed: ByteArray) = seal(sealed)
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private var signedOutCalls = 0

    private fun session(token: String = "tok", user: String = "noah") =
        """{"token":"$token","user":{"id":"U1","username":"$user","share_code":"ABCDEFGHJK"},"device":{"id":"D1"}}"""

    private fun store(dir: File = folder.root) = SessionStore(
        SealedFile(File(dir, "session.sealed"), XorSealer()),
        SealedFile(File(dir, "anchor.sealed"), XorSealer()),
        json,
    )

    private fun controller(store: SessionStore = store()) = SessionController(
        ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json)),
        store,
        CoroutineScope(Dispatchers.Unconfined),
        onSignedOut = { signedOutCalls++ },
    )

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
        assertEquals("u1", s.userId)
        assertEquals("d1", s.deviceId)
        // A fresh process reads the same session back.
        assertEquals(s, store().session)
    }

    @Test
    fun loginReusesTheAnchoredDeviceAfterLogOutClearedTheSession() = runTest {
        server.enqueue(MockResponse(code = 200, body = session()))
        val st = store()
        st.save(Session("old", "u1", "noah", null, "d-anchor"))
        st.clear()
        controller(st).login("NOAH", "pw")
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("\"d-anchor\"", body["device_id"].toString())
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
    }

    @Test
    fun plain401sSignOutOnlyAfterThreeInARowAndSuccessResets() = runTest {
        server.enqueue(MockResponse(code = 201, body = session()))
        repeat(2) { server.enqueue(MockResponse(code = 401, body = "")) }
        server.enqueue(MockResponse(code = 200, body = """{"user":{"id":"U1","username":"noah"},"device":{"id":"D1"}}"""))
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
        // A plain sign-out keeps the anchor for the next login.
        assertEquals("d1", store().anchorFor("noah"))
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
}
