package de.corespace.shroud.core.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** The wire shapes of `server-plan.md` Milestone 1, against a fake server. */
class ShroudApiTest {
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

    private val session = """{"token":"tok","user":{"id":"8F14E45F-CEEA-467A-9575-3A6B7A1E6C0E","username":"noah","share_code":"ABCDEFGHJK"},"device":{"id":"2E6F9B0C-1D3A-4E5B-8C7D-9F0A1B2C3D4E"}}"""
    private val anchor = UUID.fromString("2e6f9b0c-1d3a-4e5b-8c7d-9f0a1b2c3d4e")

    @Test
    fun registerSendsOnlyUsernameAndPassword() = runTest {
        server.enqueue(MockResponse(code = 201, body = session))
        val response = api.register("noah", "secret")
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/register", request.url.encodedPath)
        assertEquals("POST", request.method)
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("username", "password"), body.keys)
        assertEquals("tok", response.token)
        assertEquals("ABCDEFGHJK", response.user.shareCode)
        // Upper-case ids from the wire become UUIDs; their wire form is lower-case.
        assertEquals("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e", response.user.id.toString())
        assertEquals(anchor, response.device.id)
    }

    @Test
    fun loginSendsTheDeviceIdOrNull() = runTest {
        server.enqueue(MockResponse(code = 200, body = session))
        server.enqueue(MockResponse(code = 200, body = session))
        api.login("noah", "pw", anchor)
        api.login("noah", "pw", null)
        val first = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val second = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("2e6f9b0c-1d3a-4e5b-8c7d-9f0a1b2c3d4e", first["device_id"]!!.jsonPrimitive.content)
        assertTrue(second.containsKey("device_id"))
        assertEquals("null", second["device_id"].toString())
    }

    @Test
    fun errorEnvelopeBecomesServerError() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid username or password."}}"""))
        val error = runCatching { api.login("noah", "bad", null) }.exceptionOrNull() as ApiError.Server
        assertEquals("INVALID_CREDENTIALS", error.code)
        assertEquals("Invalid username or password.", error.userMessage)
        assertTrue(error.isUnauthorized)
        assertFalse(error.isDeviceRemoved)
    }

    @Test
    fun bareUnauthorizedAndOtherStatuses() = runTest {
        server.enqueue(MockResponse(code = 401, body = ""))
        server.enqueue(MockResponse(code = 502, body = "<html>bad gateway</html>"))
        val unauthorized = runCatching { api.me("t") }.exceptionOrNull() as ApiError.Server
        assertEquals(ErrorCodes.UNAUTHORIZED, unauthorized.code)
        val transport = runCatching { api.me("t") }.exceptionOrNull()
        assertTrue(transport is ApiError.Transport)
    }

    @Test
    fun bearerTokenAndKeyPaths() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"user":{"id":"8f14e45f-ceea-467a-9575-3a6b7a1e6c0e","username":"noah"},"device":{"id":"2e6f9b0c-1d3a-4e5b-8c7d-9f0a1b2c3d4e","sealed_name":"AAAA"}}"""))
        server.enqueue(MockResponse(code = 404, body = """{"error":{"code":"KEYS_REQUIRED","message":"No keys."}}"""))
        server.enqueue(MockResponse(code = 204))
        val me = api.me("tok")
        assertEquals("AAAA", me.device.sealedName)
        assertEquals("Bearer tok", server.takeRequest().headers["Authorization"])
        val missing = runCatching { api.identityKey("tok", UUID.fromString("ABCDEF01-2345-4678-9ABC-DEF012345678")) }.exceptionOrNull() as ApiError.Server
        assertEquals(ErrorCodes.KEYS_REQUIRED, missing.code)
        assertEquals("/api/v1/keys/identity/abcdef01-2345-4678-9abc-def012345678", server.takeRequest().url.encodedPath)
        api.putKeyBundle("tok", PutKeyBundleRequest(1, "a", SignedPreKeyDto(2, "b", "c"), listOf(OneTimePreKeyDto(1, "d"))))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        val body = json.parseToJsonElement(put.body!!.utf8()).jsonObject
        assertEquals(setOf("registration_id", "identity_key", "signed_pre_key", "one_time_pre_keys"), body.keys)
    }
}
