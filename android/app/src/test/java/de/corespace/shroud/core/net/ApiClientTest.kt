package de.corespace.shroud.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `ApiClient` against a fake server (api-realtime §13 row *ApiClientTest*): the behaviour of
 * `APIClient.swift` — headers (`:318-326, 352-363`), URL building (`:383-396`), auth outcomes
 * (`:328-342`), status handling (`:398-410`) — plus the Android additions: `Retry-After`, `raw()`,
 * progress-counted media transfers, the local-only cleartext rule and cancellation.
 */
class ApiClientTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient
    private val outcomes = RecordingOutcomes()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    @Serializable
    private data class Thing(val name: String, val count: Int = 0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApiClient(baseUrl = { server.url("/api/v1").toString() }, json = json)
        client.authOutcomes = outcomes
    }

    @After
    fun tearDown() = server.close()

    // ---- Headers and bodies (APIClient.swift:318-326, 352-363) ----

    @Test
    fun headersFollowIos() = runTest {
        repeat(4) { server.enqueue(MockResponse(code = 200, body = """{"name":"a"}""")) }
        client.get("things", "tok", Thing.serializer())
        client.get("things", null, Thing.serializer())
        client.get("things", "", Thing.serializer())
        client.post("things", "tok", Thing("b", 2), Thing.serializer(), Thing.serializer())

        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/api/v1/things", get.url.encodedPath)
        assertEquals("application/json, application/octet-stream, */*", get.headers["Accept"])
        assertEquals("Bearer tok", get.headers["Authorization"])
        assertNull(get.headers["Content-Type"])
        assertNull("no token, no Authorization", server.takeRequest().headers["Authorization"])
        assertNull("an empty token is no token", server.takeRequest().headers["Authorization"])
        val post = server.takeRequest()
        assertEquals("application/json", post.headers["Content-Type"])
        assertEquals("""{"name":"b","count":2}""", post.body!!.utf8())
        assertNull("no request id (iOS and web send none)", post.headers["x-request-id"])
    }

    @Test
    fun bodilessPostsSendNothing() = runTest {
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 200, body = """{"name":"x"}"""))
        client.postEmpty("auth/logout", "tok")
        assertEquals("x", client.postEmpty("calls/1/hangup", "tok", Thing.serializer()).name)
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(0L, request.bodySize)
            assertNull(request.headers["Content-Type"])
        }
    }

    @Test
    fun pathsAreTrimmedAndQueriesSortedAndEncoded() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"name":"a"}"""))
        client.get("/things/", "tok", Thing.serializer(), mapOf("z" to "a b+c", "a" to "2026-09-24T12:00:00.123456Z"))
        val request = server.takeRequest()
        assertEquals("/api/v1/things", request.url.encodedPath)
        // Sorted by name (APIClient.swift:388-390); `+` and `:` encoded, space as %20.
        assertEquals("a=2026-09-24T12%3A00%3A00.123456Z&z=a%20b%2Bc", request.url.encodedQuery)
        assertEquals("a b+c", request.url.queryParameter("z"))
        assertEquals("2026-09-24T12:00:00.123456Z", request.url.queryParameter("a"))
    }

    @Test
    fun pathSegmentsEncodeEverythingThatCouldChangeTheRoute() {
        assertEquals("a%20b", ApiClient.pathSegment("a b"))
        assertEquals("alice_1-x", ApiClient.pathSegment("alice_1-x"))
        assertEquals("a%2Fb%3Fc%23d%25e%2E", ApiClient.pathSegment("a/b?c#d%e."))
        assertEquals("M%C3%9CLLER", ApiClient.pathSegment("MÜLLER"))
    }

    // ---- Status handling (APIClient.swift:398-410) ----

    @Test
    fun answersMapToErrors() = runTest {
        server.enqueue(MockResponse(code = 409, body = """{"error":{"code":"USERNAME_TAKEN","message":"That username is already taken."}}"""))
        server.enqueue(MockResponse(code = 502, body = "<html>bad gateway</html>"))
        server.enqueue(MockResponse(code = 200, body = "not json"))
        server.enqueue(MockResponse(code = 200, body = """{"count":1}"""))
        val taken = runCatching { client.get("a", "tok", Thing.serializer()) }.exceptionOrNull() as ApiError.Server
        assertEquals(ErrorCodes.USERNAME_TAKEN, taken.code)
        assertEquals(409, taken.status)
        val gateway = runCatching { client.get("a", "tok", Thing.serializer()) }.exceptionOrNull() as ApiError.Transport
        assertEquals("Request failed with status 502", gateway.detail)
        assertTrue(runCatching { client.get("a", "tok", Thing.serializer()) }.exceptionOrNull() is ApiError.Decoding)
        assertTrue("a missing field is a decoding error", runCatching { client.get("a", "tok", Thing.serializer()) }.exceptionOrNull() is ApiError.Decoding)
    }

    @Test
    fun noContentIsSuccessForUnitCallsOnly() = runTest {
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 200, body = "ignored"))
        server.enqueue(MockResponse(code = 204))
        client.putUnit("keys/bundle", "tok", Thing("a"), Thing.serializer())
        client.deleteUnit("blocks/x", "tok")
        client.postUnit("blocks", "tok", Thing("a"), Thing.serializer())
        assertTrue(runCatching { client.get("a", "tok", Thing.serializer()) }.exceptionOrNull() is ApiError.Decoding)
        assertEquals("PUT", server.takeRequest().method)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun retryAfterReachesTheError() = runTest {
        server.enqueue(
            MockResponse.Builder().code(429).addHeader("Retry-After", "60")
                .body("""{"error":{"code":"RATE_LIMITED","message":"Too many requests. Try again later."}}""").build(),
        )
        val error = runCatching { client.get("a", "tok", Thing.serializer()) }.exceptionOrNull() as ApiError.Server
        assertTrue(error.isRateLimited)
        assertEquals(60L, error.retryAfterSeconds)
        assertEquals("Too many requests. Try again later.", error.userMessage)
    }

    @Test
    fun rawHandsBackTheConflictBody() = runTest {
        val conflict = """{"error":{"code":"REACTION_CHANGED","message":"Try again."},"current":null}"""
        server.enqueue(MockResponse(code = 409, body = conflict))
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 200, body = "{}"))
        assertEquals(RawResponse(409, conflict), client.raw("PUT", "messages/m/reaction", "tok", jsonBody = """{"a":1}"""))
        assertEquals(RawResponse(204, ""), client.raw("DELETE", "messages/m/reaction", "tok", query = mapOf("base_seq" to "3")))
        client.raw("post", "x", "tok")
        val put = server.takeRequest()
        assertEquals("application/json", put.headers["Content-Type"])
        assertEquals("""{"a":1}""", put.body!!.utf8())
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("3", delete.url.queryParameter("base_seq"))
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals(0L, post.bodySize)
        assertEquals(listOf("success", "success"), outcomes.events)
    }

    // ---- Auth outcomes (APIClient.swift:328-342; SessionAuthFailureTests) ----

    @Test
    fun everyAuthenticatedAnswerReportsItsOutcome() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"name":"a"}"""))
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"UNAUTHORIZED","message":"Authentication required."}}"""))
        server.enqueue(MockResponse(code = 401, body = ""))
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"DEVICE_REMOVED","message":"This device was removed from your account."}}"""))
        server.enqueue(MockResponse(code = 403, body = """{"error":{"code":"DEVICE_REMOVED","message":"x"}}"""))
        server.enqueue(MockResponse(code = 404, body = """{"error":{"code":"NOT_FOUND","message":"User not found."}}"""))
        server.enqueue(MockResponse(code = 500, body = """{"error":{"code":"INTERNAL_ERROR","message":"An unexpected error occurred."}}"""))
        server.enqueue(MockResponse(code = 204))
        client.get("a", "tok-1", Thing.serializer())
        repeat(6) { runCatching { client.get("a", "tok-$it", Thing.serializer()) } }
        client.deleteUnit("a", "tok-9")
        assertEquals(listOf("success", "failure", "failure", "removed:tok-2", "success"), outcomes.events)
    }

    @Test
    fun tokenLessCallsAndTransportErrorsNeverReport() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid username or password."}}"""))
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"DEVICE_REMOVED","message":"x"}}"""))
        val wrongPassword = runCatching { client.post("auth/login", null, Thing("a"), Thing.serializer(), Thing.serializer()) }.exceptionOrNull()
        assertTrue((wrongPassword as ApiError).isUnauthorized)
        runCatching { client.get("a", "", Thing.serializer()) }
        // Nobody listening: a dead port.
        val dead = MockWebServer().apply { start() }
        val deadUrl = dead.url("/api/v1").toString()
        dead.close()
        val offline = ApiClient(baseUrl = { deadUrl }, json = json).also { it.authOutcomes = outcomes }
        val error = runCatching { offline.get("a", "tok", Thing.serializer()) }.exceptionOrNull()
        assertTrue(error is ApiError.Transport)
        assertEquals("Can’t reach the server. Check that it is running and the address is right.", error!!.message)
        assertTrue(outcomes.events.toString(), outcomes.events.isEmpty())
    }

    // ---- Cleartext and URLs ----

    @Test
    fun plainHttpToAPublicHostIsRefusedBeforeAnyRequest() = runTest {
        val calls = AtomicInteger()
        val counting = OkHttpClient.Builder().addInterceptor { chain -> calls.incrementAndGet(); chain.proceed(chain.request()) }.build()
        val public = ApiClient(baseUrl = { "http://api.example.com/api/v1" }, json = json, http = counting)
        public.authOutcomes = outcomes
        val error = runCatching { public.get("auth/me", "tok", Thing.serializer()) }.exceptionOrNull() as ApiError.Transport
        assertEquals(ServerConfiguration.PLAIN_HTTP_REFUSED, error.detail)
        val media = runCatching { public.getBytes("media/x/content", "tok") }.exceptionOrNull() as ApiError.Transport
        assertEquals(ServerConfiguration.PLAIN_HTTP_REFUSED, media.detail)
        assertEquals(0, calls.get())
        assertTrue(outcomes.events.isEmpty())

        // The emulator's host and https are fine; a broken address is a transport error.
        assertEquals("http://10.0.2.2:8080/api/v1/auth/me", ApiClient({ "http://10.0.2.2:8080/api/v1/" }, json).url("/auth/me").toString())
        assertEquals("https://api.shroud.app/api/v1/config", ApiClient({ ServerConfiguration.OFFICIAL_BASE_URL }, json).url("config").toString())
        val broken = assertThrows(ApiError.Transport::class.java) { ApiClient({ "server.example/api/v1" }, json).url("config") }
        assertEquals("That server address is not a valid URL.", broken.detail)
    }

    @Test
    fun theBaseUrlIsReadOnEveryCall() = runTest {
        var base = server.url("/one").toString()
        val live = ApiClient(baseUrl = { base }, json = json)
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 204))
        live.deleteUnit("x", "tok")
        base = server.url("/two").toString()
        live.deleteUnit("x", "tok")
        assertEquals("/one/x", server.takeRequest().url.encodedPath)
        assertEquals("/two/x", server.takeRequest().url.encodedPath)
    }

    // ---- Media transfers (APIClient.swift:257-314; api-realtime §2.8) ----

    @Test
    fun uploadProgressIsMonotonicAndEndsAtOne() = runTest {
        server.enqueue(MockResponse(code = 204))
        val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        val progress = CopyOnWriteArrayList<Double>()
        client.putBytes("media/m/content", "tok", payload.toRequestBody("application/octet-stream".toMediaType())) { progress += it }
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("application/octet-stream", request.headers["Content-Type"])
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertArrayEquals(payload, request.body!!.toByteArray())
        assertMonotonicEndingAtOne(progress)
        assertEquals(listOf("success"), outcomes.events)
    }

    @Test
    fun downloadProgressIsMonotonicAndEndsAtOne() = runTest {
        val payload = ByteArray(256 * 1024) { (it % 253).toByte() }
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(payload)).throttleBody(32 * 1024, 40, TimeUnit.MILLISECONDS).build())
        val progress = CopyOnWriteArrayList<Double>()
        val bytes = withContext(Dispatchers.Default) { client.getBytes("media/m/content", "tok") { progress += it } }
        assertArrayEquals(payload, bytes)
        assertMonotonicEndingAtOne(progress)
        assertTrue("throttled over ~320 ms, the ring moved more than once: $progress", progress.size >= 2)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/media/m/content", request.url.encodedPath)
    }

    @Test
    fun downloadsToAFile() = runTest {
        val payload = ByteArray(300_000) { (it % 7).toByte() }
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(payload)).build())
        val target = File(temp.root, "media.bin")
        val progress = CopyOnWriteArrayList<Double>()
        assertEquals(payload.size.toLong(), client.getToFile("media/m/content", "tok", target) { progress += it })
        assertArrayEquals(payload, target.readBytes())
        assertMonotonicEndingAtOne(progress)
    }

    @Test
    fun aBrokenDownloadLeavesNoFile() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(Buffer().write(ByteArray(200_000))).onResponseBody(SocketEffect.CloseSocket()).build(),
        )
        server.enqueue(MockResponse(code = 403, body = """{"error":{"code":"FORBIDDEN","message":"This media was deleted for everyone."}}"""))
        val target = File(temp.root, "media.bin")
        val broken = runCatching { client.getToFile("media/m/content", "tok", target) }.exceptionOrNull()
        assertTrue("$broken", broken is ApiError.Transport)
        assertFalse(target.exists())
        val forbidden = runCatching { client.getToFile("media/m/content", "tok", target) }.exceptionOrNull() as ApiError.Server
        assertEquals("This media was deleted for everyone.", forbidden.userMessage)
        assertFalse(target.exists())
        assertEquals("the broken body still came with a 200", listOf("success"), outcomes.events)
    }

    @Test
    fun mediaErrorsAreServerErrors() = runTest {
        server.enqueue(MockResponse(code = 503, body = """{"error":{"code":"MEDIA_UNAVAILABLE","message":"Media storage is unavailable. Try again shortly."}}"""))
        val error = runCatching {
            client.putBytes("media/m/content", "tok", ByteArray(10).toRequestBody("application/octet-stream".toMediaType()))
        }.exceptionOrNull() as ApiError.Server
        assertEquals(ErrorCodes.MEDIA_UNAVAILABLE, error.code)
    }

    @Test
    fun theMediaClientSharesThePoolWithAnHourLongCallTimeout() {
        assertEquals(0, client.http.callTimeoutMillis)
        assertEquals(TimeUnit.HOURS.toMillis(1).toInt(), client.mediaHttp.callTimeoutMillis)
        assertTrue(client.mediaHttp.connectionPool === client.http.connectionPool)
        assertTrue(client.mediaHttp.dispatcher === client.http.dispatcher)
        assertEquals(20_000, client.http.readTimeoutMillis)
        assertNull(client.http.cache)
        assertFalse(client.http.followRedirects)
        assertFalse(client.http.followSslRedirects)
    }

    // ---- Cancellation ----

    @Test
    fun cancellingTheCallerCancelsTheRequestAndStaysACancellation() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").onResponseStart(SocketEffect.Stall).build())
        val call = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            client.get("slow", "tok", String.serializer())
        }
        server.takeRequest()
        call.cancel()
        try {
            call.await()
            fail("cancelled")
        } catch (e: CancellationException) {
            // Not an ApiError: a cancelled screen shows nothing.
        }
        assertTrue(outcomes.events.isEmpty())
    }

    private fun assertMonotonicEndingAtOne(progress: List<Double>) {
        assertTrue("some progress", progress.isNotEmpty())
        assertEquals(1.0, progress.last(), 0.0)
        assertEquals("1.0 once", 1, progress.count { it == 1.0 })
        progress.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b in $progress", b > a && b - a >= ProgressThrottle.MIN_STEP || b == 1.0) }
        progress.forEach { assertTrue(it in 0.0..1.0) }
    }

    private class RecordingOutcomes : AuthOutcomeListener {
        val events: MutableList<String> = CopyOnWriteArrayList()
        override fun onAuthenticatedSuccess() { events += "success" }
        override fun onAuthenticationFailure() { events += "failure" }
        override fun onDeviceRemoved(token: String) { events += "removed:$token" }
    }
}
