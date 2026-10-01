package de.corespace.shroud.core.links

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The link-preview OkHttp client (media-voice-links §10.4, D11): its configuration, that OkHttp
 * really connects only through [PublicAddressDns] (a name resolving to a private address never
 * reaches the socket), and that [OkHttpLinkTransport] neither follows redirects nor outlives a
 * cancelled caller. A local MockWebServer stands in for a website (plain HTTP: the production
 * client itself refuses cleartext, so the socket tests add the cleartext spec back).
 */
class LinkPreviewHttpClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private val loopbackDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }

    @Test
    fun theClientIsEphemeralHttpsOnlyAndFollowsNoRedirects() {
        val tracking = Interceptor { chain -> chain.proceed(chain.request()) }
        val base = OkHttpClient.Builder().addInterceptor(tracking).addNetworkInterceptor(tracking).build()
        val dns = PublicAddressDns()
        val client = LinkPreviewFetcher.httpClient(base, dns)

        assertSame(dns, client.dns)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertEquals(null, client.cache)
        assertEquals(listOf(ConnectionSpec.MODERN_TLS), client.connectionSpecs)
        assertEquals(false, client.followRedirects)
        assertEquals(false, client.followSslRedirects)
        assertEquals(8_000, client.connectTimeoutMillis)
        assertEquals(8_000, client.readTimeoutMillis)
        assertEquals(8_000, client.writeTimeoutMillis)
        assertEquals(15_000, client.callTimeoutMillis)
        assertTrue("no interceptors carried over", client.interceptors.isEmpty() && client.networkInterceptors.isEmpty())
        assertSame("shares the app's dispatcher", base.dispatcher, client.dispatcher)
        assertTrue("its own connection pool", base.connectionPool !== client.connectionPool)
    }

    @Test
    fun cleartextIsRefusedByTheClientItself() {
        val client = LinkPreviewFetcher.httpClient(OkHttpClient(), PublicAddressDns(loopbackDns))
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            fail("cleartext must be refused")
        } catch (_: java.io.IOException) {
            // UnknownServiceException: CLEARTEXT communication not enabled
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aNameResolvingToAPrivateAddressNeverReachesTheSocket() {
        val client = LinkPreviewFetcher.httpClient(OkHttpClient(), PublicAddressDns(loopbackDns))
            .newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
            .build()
        val url = server.url("/page").newBuilder().host("rebinding.example").build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute()
            fail("a loopback answer must be refused")
        } catch (_: UnknownHostException) {
            // the filter threw; OkHttp never opened a connection
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theTransportReadsTheResponseAndDoesNotFollowRedirects() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build())
        val client = OkHttpClient.Builder().followRedirects(false).build()
        val transport = OkHttpLinkTransport(client)
        val (code, location) = transport.execute(Request.Builder().url(server.url("/a")).build()) { it.code to it.header("Location") }
        assertEquals(302, code)
        assertEquals("/elsewhere", location)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theTransportHandsBackTheBody() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "<head><title>x</title></head>"))
        val transport = OkHttpLinkTransport(OkHttpClient())
        val head = transport.execute(Request.Builder().url(server.url("/a")).build()) { LinkPreviewFetcher.readHead(it.body.source()) }
        assertEquals("<head><title>x</title></head>", String(head))
    }

    @Test
    fun cancellingTheCallerCancelsTheCall() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("x").onResponseStart(SocketEffect.Stall).build())
        val transport = OkHttpLinkTransport(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build())
        val pending = async(Dispatchers.IO) {
            transport.execute(Request.Builder().url(server.url("/slow")).build()) { it.code }
        }
        server.takeRequest(5, TimeUnit.SECONDS)
        pending.cancel()
        // Returns promptly: the stalled socket is closed by Call.cancel(), not by the 30 s timeout.
        withTimeout(5_000) { pending.join() }
        assertTrue(pending.isCancelled)
    }
}
