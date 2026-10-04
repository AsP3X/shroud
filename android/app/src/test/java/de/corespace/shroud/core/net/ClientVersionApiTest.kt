package de.corespace.shroud.core.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * `GET /client-version` (server `routes/client_version.rs`): the query the app sends, no token (it
 * runs signed out and on the lock screen) and so no auth outcome, and the answer decoded.
 */
class ClientVersionApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ShroudApi
    private var outcomes = 0
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = ApiClient(baseUrl = { server.url("/api/v1").toString() }, json = json)
        client.authOutcomes = object : AuthOutcomeListener {
            override fun onAuthenticatedSuccess() { outcomes++ }
            override fun onAuthenticationFailure() { outcomes++ }
            override fun onDeviceRemoved(token: String) { outcomes++ }
        }
        api = ShroudApi(client)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun asksWithPlatformAndVersionAndNoToken() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"status":"update_available","latest_version":"0.2.0","update_url":null}"""))
        val answer = api.clientVersion("android", "0.1.0")
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/client-version", request.url.encodedPath)
        assertEquals("platform=android&version=0.1.0", request.url.encodedQuery)
        assertNull(request.headers["Authorization"])
        assertEquals(ClientVersionDto("update_available", "0.2.0", null), answer)
        assertEquals(0, outcomes)
    }

    @Test
    fun aBadRequestIsAnApiError() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"error":{"code":"VALIDATION_ERROR","message":"Invalid version."}}"""))
        try {
            api.clientVersion("android", "")
            fail("expected an ApiError")
        } catch (e: ApiError) {
            assertTrue(e is ApiError.Server)
        }
        assertEquals(0, outcomes)
    }
}
