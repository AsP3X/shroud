package de.corespace.shroud.core.realtime

import de.corespace.shroud.core.net.AuthOutcomeListener
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.realtime.RealtimeClient.ConnectionState
import de.corespace.shroud.core.realtime.RealtimeClient.Holder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * `RealtimeClient` against a real WebSocket server (MockWebServer upgrades): the behaviour of iOS
 * `RealtimeClient.swift` and web `realtime.ts` / `realtime.selftest.ts`, the Android additions of
 * api-realtime §11.7–11.9 and plan C31, and the background connection of plan §1.7.3.
 *
 * Threading: the client's scope is the test's `backgroundScope` (a `StandardTestDispatcher`), so
 * its state only moves when the test runs the scheduler; [await] alternates `runCurrent()` with
 * short real waits for OkHttp's threads. Backoff runs on virtual time (`advanceTimeBy`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val outcomes = RecordingOutcomes()

    /** `AppPhaseMonitor.isStarted` stand-in. */
    @Volatile private var foreground = true

    private val userId = "11111111-1111-1111-1111-111111111111"
    private val deviceId = "22222222-2222-2222-2222-222222222222"
    private val peerId = UUID.fromString("8F14E45F-CEEA-467A-9575-3A6B7A1E6C0E")

    private val authOk = """{"type":"auth.ok","user_id":"$userId","device_id":"$deviceId"}"""
    private val focusTrue = """{"type":"focus","focused":true}"""
    private val focusFalse = """{"type":"focus","focused":false}"""

    /** Away while the background connection holds the socket (plan §1.4; X1-SRV-UP `update_focus`). */
    private val focusFalseBackground = """{"type":"focus","focused":false,"background":true}"""

    /** The background connection let go of a socket others keep: it counts like any socket again. */
    private val focusFalseNotBackground = """{"type":"focus","focused":false,"background":false}"""
    private fun authFrame(token: String = "tok") = """{"type":"auth","token":"$token"}"""
    private fun backgroundAuthFrame(token: String = "tok") = """{"type":"auth","token":"$token","background":true}"""
    private fun authError(code: String) = """{"type":"auth.error","error":{"code":"$code","message":"m"}}"""
    private val typingMarker = """{"type":"typing","peer_user_id":"8f14e45f-ceea-467a-9575-3a6b7a1e6c0e","is_typing":true}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.close()
        http.dispatcher.executorService.shutdownNow()
        http.connectionPool.evictAll()
    }

    // ---- Harness ----

    private fun TestScope.newClient(
        base: String = server.url("/api/v1").toString(),
        baseHttp: OkHttpClient = http,
    ) = newClientFor({ base }, baseHttp)

    private fun TestScope.newClientFor(baseUrl: () -> String, baseHttp: OkHttpClient = http) = RealtimeClient(
        baseUrl = baseUrl,
        json = json,
        baseHttp = baseHttp,
        scope = backgroundScope,
        authOutcomes = { outcomes },
        isForeground = { foreground },
    )

    /** The server side of one socket. [lateFrame] is sent after the client's close arrived. */
    private class Peer(private val lateFrame: String? = null) : WebSocketListener() {
        val frames = LinkedBlockingQueue<String>()
        val closeCodes = LinkedBlockingQueue<Int>()
        @Volatile var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            frames.put(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCodes.put(code)
            lateFrame?.let { webSocket.send(it) }
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit

        fun send(text: String) {
            check(socket!!.send(text))
        }
    }

    private fun upgrade(peer: Peer) = MockResponse.Builder().webSocketUpgrade(peer).build()

    private fun TestScope.await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            testScheduler.runCurrent()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(2)
        }
    }

    /** Lets OkHttp's threads and the client settle for [millis] of real time (virtual time stands still). */
    private fun TestScope.settle(millis: Long = 300) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            testScheduler.runCurrent()
            Thread.sleep(5)
        }
        testScheduler.runCurrent()
    }

    private fun TestScope.nextFrame(peer: Peer): String {
        var frame: String? = null
        await("a frame from the client") {
            frame = peer.frames.poll()
            frame != null
        }
        return frame!!
    }

    private fun TestScope.nextClose(peer: Peer): Int {
        var code: Int? = null
        await("the client's close") {
            code = peer.closeCodes.poll()
            code != null
        }
        return code!!
    }

    /** hold → auth frame → `auth.ok` → connected; returns the first frame after `auth.ok` (the focus). */
    private fun TestScope.connect(
        client: RealtimeClient,
        peer: Peer,
        holder: Holder = Holder.Messaging,
        token: String = "tok",
        expectedAuth: String = authFrame(token),
    ): String {
        server.enqueue(upgrade(peer))
        client.hold(holder, token)
        assertEquals(expectedAuth, nextFrame(peer))
        peer.send(authOk)
        await("auth.ok") { client.isConnected }
        return nextFrame(peer)
    }

    private fun TestScope.record(client: RealtimeClient): List<RealtimeEvent> {
        val seen = Collections.synchronizedList(ArrayList<RealtimeEvent>())
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { seen += it } }
        return seen
    }

    private class RecordingOutcomes : AuthOutcomeListener {
        val successes = Collections.synchronizedList(ArrayList<Unit>())
        val failures = Collections.synchronizedList(ArrayList<Unit>())
        val removed = Collections.synchronizedList(ArrayList<String>())

        override fun onAuthenticatedSuccess() {
            successes += Unit
        }

        override fun onAuthenticationFailure() {
            failures += Unit
        }

        override fun onDeviceRemoved(token: String) {
            removed += token
        }
    }

    private fun assertFailed(client: RealtimeClient) =
        assertTrue("state ${client.state.value}", client.state.value is ConnectionState.Failed)

    // ---- Handshake and focus (RealtimeClient.swift:165-205, 292-306, 77-101) ----

    @Test
    fun authIsTheFirstFrameAndFocusFollowsAuthOk() = runTest {
        val client = newClient()
        val events = record(client)
        val peer = Peer()
        server.enqueue(upgrade(peer))
        client.hold(Holder.Messaging, "tok")
        assertEquals(ConnectionState.Connecting, client.state.value)
        assertEquals(authFrame(), nextFrame(peer))
        assertFalse(client.isConnected)

        peer.send(authOk)
        await("auth.ok") { client.isConnected }
        assertEquals(ConnectionState.Connected(UUID.fromString(userId), UUID.fromString(deviceId)), client.state.value)
        // The first frame of an authenticated socket is its focus (`:304`).
        assertEquals(focusTrue, nextFrame(peer))
        await("the Connected event") { events.isNotEmpty() }
        assertEquals(RealtimeEvent.Connected(UUID.fromString(userId), UUID.fromString(deviceId), isReconnect = false), events.single())
        assertEquals("/api/v1/ws", server.takeRequest().url.encodedPath)
    }

    @Test
    fun deliverFocusIsIdempotent() = runTest {
        val client = newClient()
        assertFalse("not connected yet", client.deliverFocus())
        val peer = Peer()
        assertEquals(focusTrue, connect(client, peer))

        // Already told: true without a frame, twice.
        assertTrue(client.deliverFocus())
        assertTrue(client.deliverFocus())
        client.noteFocus(false)
        assertTrue(client.deliverFocus())
        assertTrue(client.deliverFocus())
        client.sendTyping(peerId, true)
        assertEquals(focusFalse, nextFrame(peer))
        assertEquals(typingMarker, nextFrame(peer))
    }

    @Test
    fun typingBeforeAuthOkIsDropped() = runTest {
        val client = newClient()
        val peer = Peer()
        server.enqueue(upgrade(peer))
        client.hold(Holder.Messaging, "tok")
        assertEquals(authFrame(), nextFrame(peer))
        client.sendTyping(peerId, true)
        client.sendRecording(peerId, true)
        settle()
        peer.send(authOk)
        await("auth.ok") { client.isConnected }
        // Nothing queued before auth.ok: the focus comes first.
        assertEquals(focusTrue, nextFrame(peer))

        client.sendTyping(peerId, true)
        client.sendRecording(peerId, false)
        assertEquals(typingMarker, nextFrame(peer))
        assertEquals("""{"type":"recording","peer_user_id":"8f14e45f-ceea-467a-9575-3a6b7a1e6c0e","is_recording":false}""", nextFrame(peer))
    }

    @Test
    fun socketOpenedInTheBackgroundNeverClaimsFocus() = runTest {
        // Decision §17-5: a socket opened for a call while the app is away must not stop pushes.
        foreground = false
        val client = newClient()
        assertEquals(focusFalse, connect(client, Peer(), holder = Holder.Call))
        // A wish to be in front does not count while the app is not.
        client.noteFocus(true)
        assertTrue(client.deliverFocus())
    }

    @Test
    fun aFreshStartTakesItsFocusFromTheAppPhaseNotFromAnOldWish() = runTest {
        // Left the app (focus:false), the chats auto-locked and released the socket; the user
        // returns and unlocks: MessagingController.start() holds without noting focus.
        val client = newClient()
        val first = Peer()
        connect(client, first)
        foreground = false
        client.noteFocus(false)
        assertTrue(client.deliverFocus())
        assertEquals(focusFalse, nextFrame(first))
        client.release(Holder.Messaging)
        assertEquals(1001, nextClose(first))

        foreground = true
        assertEquals(focusTrue, connect(client, Peer()))
    }

    @Test
    fun aFreshStartInTheBackgroundSaysAway() = runTest {
        // The app was in front when the client was built, then a call is answered from the
        // background after everything had let go of the socket.
        val client = newClient()
        connect(client, Peer())
        client.release(Holder.Messaging)
        foreground = false
        assertEquals(focusFalse, connect(client, Peer(), holder = Holder.Call))
    }

    @Test
    fun eventsArriveInOrder() = runTest {
        val client = newClient()
        val events = record(client)
        val peer = Peer()
        connect(client, peer)
        val other = "33333333-3333-3333-3333-333333333333"
        peer.send("""{"type":"typing","is_typing":true,"user_id":"$other"}""")
        peer.send("""{"type":"presence.update","user_id":"$other","online":false,"last_seen_at":"2026-07-15T12:00:00Z"}""")
        peer.send("""{"type":"typing","user_id":"$other"}""") // dropped by the parser
        peer.send("""{"type":"recording","is_recording":true,"user_id":"$other"}""")
        await("three events after Connected") { events.size == 4 }
        settle(100)
        assertEquals(
            listOf(RealtimeEvent.Typing::class, RealtimeEvent.PresenceUpdate::class, RealtimeEvent.Recording::class),
            events.drop(1).map { it::class },
        )
    }

    // ---- Holders (RealtimeClient.swift:59-75, 109-140) ----

    @Test
    fun twoHoldersShareOneSocketAndTheLastReleaseClosesWith1001() = runTest {
        val client = newClient()
        val peer = Peer()
        connect(client, peer)
        client.hold(Holder.Call, "tok")
        settle()
        assertEquals(1, server.requestCount)
        assertTrue(client.isHeld(Holder.Messaging) && client.isHeld(Holder.Call))

        client.release(Holder.Messaging)
        settle()
        assertTrue(client.isConnected)
        assertNull(peer.closeCodes.poll())

        client.release(Holder.Call)
        assertEquals(ConnectionState.Disconnected, client.state.value)
        assertEquals(1001, nextClose(peer))
        // Never reconnects.
        advanceTimeBy(120_000)
        settle()
        assertEquals(ConnectionState.Disconnected, client.state.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aNewTokenOpensANewSocketAndTheOldOnesCallbacksAreIgnored() = runTest {
        val client = newClient()
        val events = record(client)
        val stranger = "99999999-9999-9999-9999-999999999999"
        // The old socket still sends an event after the client's close reached it.
        val old = Peer(lateFrame = """{"type":"typing","is_typing":true,"user_id":"$stranger"}""")
        connect(client, old, token = "t1")

        val fresh = Peer()
        server.enqueue(upgrade(fresh))
        client.hold(Holder.Messaging, "t2")
        assertEquals(1001, nextClose(old))
        assertEquals(authFrame("t2"), nextFrame(fresh))
        settle()
        // The old socket's late frame and close changed nothing.
        assertEquals(ConnectionState.Connecting, client.state.value)
        fresh.send(authOk)
        await("auth.ok on the new socket") { client.isConnected }
        assertEquals(focusTrue, nextFrame(fresh))
        settle()
        assertTrue(events.none { it is RealtimeEvent.Typing })
        // A new session's first auth.ok is not a reconnect.
        assertEquals(listOf(false, false), events.filterIsInstance<RealtimeEvent.Connected>().map { it.isReconnect })
        advanceTimeBy(120_000)
        settle()
        assertEquals(2, server.requestCount)
        assertTrue(client.isConnected)
    }

    @Test
    fun shutdownForgetsHoldersAndToken() = runTest {
        val client = newClient()
        val peer = Peer()
        connect(client, peer)
        client.hold(Holder.Call, "tok")
        client.shutdown()
        assertEquals(1001, nextClose(peer))
        assertFalse(client.isHeld(Holder.Messaging) || client.isHeld(Holder.Call))
        assertEquals(ConnectionState.Disconnected, client.state.value)
        client.onNetworkAvailable()
        advanceTimeBy(120_000)
        settle()
        assertEquals(1, server.requestCount)
    }

    // ---- Reconnect (RealtimeClient.swift:246-272; api-realtime §11.7) ----

    /**
     * After a failure is seen, the next attempt comes exactly [seconds] later (virtual time). The
     * attempt is counted at the server: a quick 500 can come back inside the same `runCurrent()`,
     * so the brief Connecting state is not something to wait for.
     */
    private fun TestScope.assertReconnectAfter(client: RealtimeClient, seconds: Long) {
        await("the drop") { client.state.value is ConnectionState.Failed }
        val attempts = server.requestCount
        advanceTimeBy(seconds * 1000 - 1)
        runCurrent()
        settle(30)
        assertFailed(client)
        assertEquals("no attempt before $seconds s", attempts, server.requestCount)
        advanceTimeBy(1)
        runCurrent()
        await("reconnect after $seconds s") { server.requestCount == attempts + 1 }
    }

    @Test
    fun dropsReconnectAfter1_2_4_8_16_30_30SecondsResetByAuthOk() = runTest {
        val client = newClient()
        val events = record(client)
        repeat(7) { server.enqueue(MockResponse(code = 500)) }
        client.hold(Holder.Messaging, "tok")
        for (seconds in listOf(1L, 2, 4, 8, 16, 30, 30)) assertReconnectAfter(client, seconds)

        // The eighth attempt connects: auth.ok resets the backoff (`:301`).
        val peer = Peer()
        server.enqueue(upgrade(peer))
        assertEquals(authFrame(), nextFrame(peer))
        peer.send(authOk)
        await("auth.ok") { client.isConnected }
        assertEquals(focusTrue, nextFrame(peer))

        // The server drops it (a restart): back to 1 s, and the next auth.ok says reconnect.
        // MockWebServer's server-side socket has no call to cancel(), so it closes instead.
        val again = Peer()
        server.enqueue(upgrade(again))
        peer.socket!!.close(1011, "restart")
        assertReconnectAfter(client, 1)
        assertEquals(authFrame(), nextFrame(again))
        again.send(authOk)
        await("auth.ok again") { client.isConnected }
        await("two Connected events") { events.count { it is RealtimeEvent.Connected } == 2 }
        assertEquals(listOf(false, true), events.filterIsInstance<RealtimeEvent.Connected>().map { it.isReconnect })
        // A focus frame on every new socket.
        assertEquals(focusTrue, nextFrame(again))
    }

    @Test
    fun aServerCloseReconnects() = runTest {
        val client = newClient()
        val peer = Peer()
        connect(client, peer)
        server.enqueue(MockResponse(code = 500))
        peer.socket!!.close(1001, "going away")
        assertReconnectAfter(client, 1)
    }

    @Test
    fun networkAvailableReconnectsAtOnceAndResetsTheBackoff() = runTest {
        val client = newClient()
        repeat(3) { server.enqueue(MockResponse(code = 500)) }
        client.hold(Holder.Messaging, "tok")
        await("the first drop") { client.state.value is ConnectionState.Failed }
        // Half-way through the first 1 s wait the network comes back.
        advanceTimeBy(500)
        runCurrent()
        assertFailed(client)
        client.onNetworkAvailable()
        assertEquals(ConnectionState.Connecting, client.state.value)
        // The pending attempt (due at 1 000 ms) is gone; the new drop waits 1 s from 500 ms.
        assertReconnectAfter(client, 1)
        await("the third drop") { client.state.value is ConnectionState.Failed }
        settle()
        assertEquals(3, server.requestCount)
    }

    @Test
    fun networkAvailableDoesNothingWhileConnectedOrUnheld() = runTest {
        val client = newClient()
        client.onNetworkAvailable()
        assertEquals(ConnectionState.Disconnected, client.state.value)
        connect(client, Peer())
        client.onNetworkAvailable()
        settle()
        assertEquals(1, server.requestCount)
        assertTrue(client.isConnected)
    }

    // ---- auth.error (plan C31; RealtimeClient.swift:307-317, realtime.ts:125-132) ----

    @Test
    fun deviceRemovedReportsTheTokenOnceAndNeverReconnects() = runTest {
        val client = newClient()
        val peer = Peer()
        connect(client, peer)
        peer.send(authError("DEVICE_REMOVED"))
        await("the removal") { outcomes.removed.isNotEmpty() }
        assertEquals(ConnectionState.Failed(RealtimeClient.AUTH_FAILED_REASON), client.state.value)
        // A second frame of the same socket is stale.
        runCatching { peer.send(authError("DEVICE_REMOVED")) }
        advanceTimeBy(120_000)
        settle()
        assertEquals(listOf("tok"), outcomes.removed.toList())
        assertTrue(outcomes.failures.isEmpty())
        assertEquals(1, server.requestCount)
        client.onNetworkAvailable()
        settle()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unauthorizedStopsWithoutSigningOut() = runTest {
        val client = newClient()
        val peer = Peer()
        connect(client, peer)
        peer.send(authError("UNAUTHORIZED"))
        await("the stop") { client.state.value is ConnectionState.Failed }
        assertEquals(ConnectionState.Failed(RealtimeClient.AUTH_FAILED_REASON), client.state.value)
        advanceTimeBy(120_000)
        settle()
        assertTrue(outcomes.removed.isEmpty())
        assertTrue(outcomes.failures.isEmpty())
        assertEquals(1, server.requestCount)

        // A later hold with the same token tries again (`:110-117`).
        val again = Peer()
        server.enqueue(upgrade(again))
        client.hold(Holder.Messaging, "tok")
        assertEquals(authFrame(), nextFrame(again))
    }

    @Test
    fun unauthorizedBeforeAuthOkStopsToo() = runTest {
        val client = newClient()
        val peer = Peer()
        server.enqueue(upgrade(peer))
        client.hold(Holder.Messaging, "tok")
        assertEquals(authFrame(), nextFrame(peer))
        peer.send("""{"type":"auth.error","error":{"code":"UNAUTHORIZED","message":"Authentication required."}}""")
        peer.socket!!.close(1000, null)
        await("the stop") { client.state.value is ConnectionState.Failed }
        advanceTimeBy(120_000)
        settle()
        assertEquals(ConnectionState.Failed(RealtimeClient.AUTH_FAILED_REASON), client.state.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rateLimitedRetriesAfterAtLeast30Seconds() = runTest {
        val client = newClient()
        val peer = Peer()
        connect(client, peer)
        val next = Peer()
        server.enqueue(upgrade(next))
        // ws.rs:150-157: auth.ok came first (and reset the backoff), then the cap.
        peer.send(authError("RATE_LIMITED"))
        assertReconnectAfter(client, 30)
        assertTrue(outcomes.removed.isEmpty())
        assertEquals(authFrame(), nextFrame(next))
        assertEquals(1000, nextClose(peer))
    }

    @Test
    fun aNetworkChangeDoesNotShortenTheRateLimitedWait() = runTest {
        val client = newClient()
        val peer = Peer()
        connect(client, peer)
        peer.send(authError("RATE_LIMITED"))
        await("the cap") { client.state.value == ConnectionState.Failed(RealtimeClient.RATE_LIMITED_REASON) }
        client.onNetworkAvailable()
        assertEquals(ConnectionState.Failed(RealtimeClient.RATE_LIMITED_REASON), client.state.value)
        val next = Peer()
        server.enqueue(upgrade(next))
        assertReconnectAfter(client, 30)
        assertEquals(authFrame(), nextFrame(next))
        assertEquals(2, server.requestCount)
    }

    // ---- Cleartext (api-realtime §11.1) ----

    @Test
    fun plainWsToAPublicHostIsRefusedBeforeAnyRequest() = runTest {
        val requests = Collections.synchronizedList(ArrayList<String>())
        val spy = http.newBuilder().addInterceptor(Interceptor { chain -> requests += chain.request().url.host; chain.proceed(chain.request()) }).build()
        val client = newClient(base = "http://api.example.com/api/v1", baseHttp = spy)
        client.hold(Holder.Messaging, "tok")
        assertEquals(ConnectionState.Failed(ServerConfiguration.PLAIN_HTTP_REFUSED), client.state.value)
        advanceTimeBy(120_000)
        settle(100)
        assertEquals(ConnectionState.Failed(ServerConfiguration.PLAIN_HTTP_REFUSED), client.state.value)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun invalidUrlRetriesWithBackoffAndRereadsTheSettings() = runTest {
        var base = "not a url"
        val client = newClientFor({ base })
        client.hold(Holder.Messaging, "tok")
        assertEquals(ConnectionState.Failed(RealtimeClient.INVALID_URL), client.state.value)
        // `:169-175`: failed + scheduleReconnect; the next attempt reads the server settings again.
        base = server.url("/api/v1").toString()
        val peer = Peer()
        server.enqueue(upgrade(peer))
        advanceTimeBy(999)
        runCurrent()
        assertEquals(ConnectionState.Failed(RealtimeClient.INVALID_URL), client.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(ConnectionState.Connecting, client.state.value)
        assertEquals(authFrame(), nextFrame(peer))
    }

    // ---- Background connection (plan §1.7.3, decision record 2026-10-01; X1-SRV-UP) ----

    @Test
    fun aSocketHeldOnlyByTheBackgroundConnectionAuthenticatesAsBackgroundAndNeverClaimsFocus() = runTest {
        val client = newClient()
        val peer = Peer()
        // Even with the app in front (chats locked, say): only the background connection holds it.
        // The first frame after auth.ok is still its focus (`:304`), away and background.
        val first = connect(client, peer, holder = Holder.Background, expectedAuth = backgroundAuthFrame())
        assertEquals(focusFalseBackground, first)
        client.noteFocus(true)
        assertTrue(client.deliverFocus())
        client.sendTyping(peerId, true)
        assertEquals(typingMarker, nextFrame(peer))
    }

    @Test
    fun aLaterMessagingHoldSendsFocusTrueOnTheSameSocket() = runTest {
        foreground = false
        val client = newClient()
        val peer = Peer()
        assertEquals(focusFalseBackground, connect(client, peer, holder = Holder.Background, expectedAuth = backgroundAuthFrame()))

        // The app comes to the front and the chats unlock (MessagingController.handleAppBecameActive).
        foreground = true
        client.noteFocus(true)
        client.hold(Holder.Messaging, "tok")
        assertEquals(focusTrue, nextFrame(peer))
        assertTrue(client.deliverFocus())
        settle()
        assertEquals(1, server.requestCount)

        // leaveForeground(keepSocket = true): focus:false, still a background socket; it stays.
        foreground = false
        client.noteFocus(false)
        assertTrue(client.deliverFocus())
        assertEquals(focusFalseBackground, nextFrame(peer))

        // Back in front, then the chats lock: messaging lets go, the background connection keeps
        // the socket, and the server hears at once that nobody is looking any more.
        foreground = true
        client.noteFocus(true)
        assertTrue(client.deliverFocus())
        assertEquals(focusTrue, nextFrame(peer))
        client.release(Holder.Messaging)
        assertEquals(focusFalseBackground, nextFrame(peer))
        settle()
        assertTrue(client.isConnected)
        assertNull(peer.closeCodes.poll())
        assertTrue(client.isHeld(Holder.Background))
    }

    @Test
    fun aSocketOpenedInFrontAndKeptForTheBackgroundConnectionDeclaresItselfOnLeaving() = runTest {
        // Plan §1.4: leaving with the background connection on, the socket messaging opened in
        // front stays (keepSocket) and must stop counting as online — it says so with its focus.
        val client = newClient()
        val peer = Peer()
        assertEquals(focusTrue, connect(client, peer, holder = Holder.Messaging))
        // Switched on in Settings while in front: nothing for the server yet.
        client.hold(Holder.Background, "tok")
        client.sendTyping(peerId, true)
        assertEquals(typingMarker, nextFrame(peer))

        // leaveForeground(keepSocket = true).
        foreground = false
        client.noteFocus(false)
        assertTrue(client.deliverFocus())
        assertEquals(focusFalseBackground, nextFrame(peer))
        assertTrue(client.deliverFocus())

        // Back in front and away again: the flag stays with the socket, every away frame says it.
        foreground = true
        client.noteFocus(true)
        assertTrue(client.deliverFocus())
        assertEquals(focusTrue, nextFrame(peer))
        foreground = false
        client.noteFocus(false)
        assertTrue(client.deliverFocus())
        assertEquals(focusFalseBackground, nextFrame(peer))

        // Switched off while away: the socket stops counting as background at once (away but
        // online, like any socket), until the coordinator steps away and messaging lets go.
        client.release(Holder.Background)
        assertEquals(focusFalseNotBackground, nextFrame(peer))
        assertTrue(client.deliverFocus())
        client.sendTyping(peerId, true)
        assertEquals(typingMarker, nextFrame(peer))
        assertEquals(1, server.requestCount)
    }

    /**
     * The background connection ends while a call keeps the socket: the server must hear
     * `"background": false`, or the next `focus:false` (leaving during the call, keepSocket) counts
     * the user offline with the socket open — iOS and the web stay online there.
     */
    @Test
    fun releasingTheBackgroundConnectionWhileACallKeepsTheSocketClearsTheFlag() = runTest {
        val client = newClient()
        val peer = Peer()
        assertEquals(focusTrue, connect(client, peer, holder = Holder.Call))
        client.hold(Holder.Background, "tok")
        foreground = false
        client.noteFocus(false)
        assertTrue(client.deliverFocus())
        assertEquals(focusFalseBackground, nextFrame(peer))

        // Back in front with the call, then the background connection is switched off.
        foreground = true
        client.noteFocus(true)
        assertTrue(client.deliverFocus())
        assertEquals(focusTrue, nextFrame(peer))
        client.release(Holder.Background)
        assertEquals("""{"type":"focus","focused":true,"background":false}""", nextFrame(peer))
        assertTrue(client.deliverFocus())

        // Leaving during the call (keepSocket): a plain away frame, the socket stays online.
        foreground = false
        client.noteFocus(false)
        assertTrue(client.deliverFocus())
        assertEquals(focusFalse, nextFrame(peer))
        client.sendTyping(peerId, true)
        assertEquals(typingMarker, nextFrame(peer))
        assertTrue(client.isConnected)
    }

    @Test
    fun aBackgroundHoldArrivingWhileAwayDeclaresTheKeptSocketAtOnce() = runTest {
        // A call kept the socket in the background (focus:false sent), then the background
        // connection starts (W3-PUSH restarts its service): the server hears it right away.
        val client = newClient()
        val peer = Peer()
        connect(client, peer, holder = Holder.Messaging)
        client.hold(Holder.Call, "tok")
        foreground = false
        client.noteFocus(false)
        assertTrue(client.deliverFocus())
        assertEquals(focusFalse, nextFrame(peer))
        client.hold(Holder.Background, "tok")
        assertEquals(focusFalseBackground, nextFrame(peer))
        assertTrue(client.deliverFocus())
        client.sendTyping(peerId, true)
        assertEquals(typingMarker, nextFrame(peer))
    }

    @Test
    fun aKeptSocketReconnectingInTheBackgroundStillAuthenticatesAsBackground() = runTest {
        foreground = false
        val client = newClient()
        client.noteFocus(false)
        val peer = Peer()
        server.enqueue(upgrade(peer))
        client.hold(Holder.Messaging, "tok")
        client.hold(Holder.Background, "tok")
        // Messaging kept its hold in the background (keepSocket): nobody claims focus.
        assertEquals(backgroundAuthFrame(), nextFrame(peer))
    }

    @Test
    fun aMessagingSocketNeverSaysBackground() = runTest {
        foreground = false
        val client = newClient()
        assertEquals(focusFalse, connect(client, Peer(), holder = Holder.Messaging))
    }

    @Test
    fun deviceRemovedOnABackgroundSocketReachesTheWipe() = runTest {
        foreground = false
        val client = newClient()
        val peer = Peer()
        connect(client, peer, holder = Holder.Background, expectedAuth = backgroundAuthFrame("bg-token"), token = "bg-token")
        peer.send(authError("DEVICE_REMOVED"))
        await("the removal") { outcomes.removed.isNotEmpty() }
        assertEquals(listOf("bg-token"), outcomes.removed.toList())
        advanceTimeBy(120_000)
        settle()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun releasingTheBackgroundHoldKeepsAMessagingSocket() = runTest {
        val client = newClient()
        val peer = Peer()
        connect(client, peer, holder = Holder.Messaging)
        client.hold(Holder.Background, "tok")
        client.release(Holder.Background)
        settle()
        assertTrue(client.isConnected)
        assertNull(peer.closeCodes.poll(10, TimeUnit.MILLISECONDS))
    }
}
