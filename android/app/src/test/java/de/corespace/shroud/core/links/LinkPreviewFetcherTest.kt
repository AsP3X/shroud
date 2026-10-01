package de.corespace.shroud.core.links

import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/**
 * The fetch pipeline of `LinkPreviewFetcher.swift:114-229` and its redirect guard (`:455-482`) on
 * scripted responses: what is requested, with which headers, what is refused before any request,
 * how redirects are held to the target rules, and how the page and its image become a draft
 * (media-voice-links §10.4). The OkHttp client itself is covered by [LinkPreviewHttpClientTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkPreviewFetcherTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    // ---- fakes ------------------------------------------------------------------------------------

    private class Scripted(val code: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0), val declaredLength: Long? = null)

    /** Answers by full URL; records every request. Unknown URLs are 404. */
    private class FakeTransport(private val script: Map<String, Scripted>) : LinkHttpTransport {
        val requests = ArrayList<Request>()

        override suspend fun <T> execute(request: Request, read: (Response) -> T): T {
            requests += request
            val scripted = script[request.url.toString()] ?: Scripted(404)
            val contentType = scripted.headers["Content-Type"]?.toMediaTypeOrNull()
            val body: ResponseBody = if (scripted.declaredLength != null) {
                object : ResponseBody() {
                    override fun contentType(): MediaType? = contentType
                    override fun contentLength(): Long = scripted.declaredLength
                    override fun source(): BufferedSource = Buffer().write(scripted.body)
                }
            } else {
                scripted.body.toResponseBody(contentType)
            }
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(scripted.code)
                .message("scripted")
                .headers(headersOf(*scripted.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .body(body)
                .build()
            return response.use(read)
        }
    }

    /** Hosts → literal addresses; anything else does not resolve. */
    private class FakeDns(private val table: Map<String, String>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName(table[hostname] ?: throw UnknownHostException("no such host")))
    }

    /** "Decodes" bytes that start with `IMG w h`; anything else is not an image. */
    private class FakeImages : LinkImagePreparer {
        val prepared = ArrayList<ByteArray>()

        override fun prepare(bytes: ByteArray): PreparedLinkImages? {
            prepared += bytes
            val words = String(bytes).split(' ')
            if (words.firstOrNull() != "IMG") return null
            return PreparedLinkImages(large = "LARGE".toByteArray(), thumbnail = "THUMB".toByteArray(), width = words[1].toInt(), height = words[2].toInt())
        }
    }

    private val dns = PublicAddressDns(
        FakeDns(
            mapOf(
                "example.com" to "93.184.216.34",
                "www.example.com" to "93.184.216.34",
                "cdn.example.com" to "151.101.1.1",
                "home.example.com" to "192.168.1.20",
                "intranet.example" to "10.0.0.5",
            ),
        ),
    )

    private val images = FakeImages()

    private fun fetcher(script: Map<String, Scripted>, transport: LinkHttpTransport = FakeTransport(script)) =
        LinkPreviewFetcher(
            transport = transport,
            dns = dns,
            images = images,
            acceptLanguage = { "de-DE,de;q=0.9,en;q=0.8" },
            io = Dispatchers.Unconfined,
            cpu = Dispatchers.Unconfined,
        )

    private fun html(body: String, headers: Map<String, String> = mapOf("Content-Type" to "text/html; charset=utf-8")) =
        Scripted(200, headers, body.toByteArray())

    private val cardPage = html(
        """
        <html><head>
        <meta property="og:site_name" content="Example">
        <meta property="og:title" content="The real title">
        <meta property="og:description" content="OG description">
        <meta property="og:image" content="http://cdn.example.com/card.jpg">
        </head><body>…</body></html>
        """.trimIndent(),
    )

    private val cardImage = Scripted(200, mapOf("Content-Type" to "image/jpeg"), "IMG 1024 538".toByteArray())

    private suspend fun expectFailure(reason: LinkPreviewException.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: LinkPreviewException) {
            assertEquals(reason, e.reason)
        }
    }

    // ---- the happy path ---------------------------------------------------------------------------

    @Test
    fun aCardPageBecomesALargePreview() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/a" to cardPage, "https://cdn.example.com/card.jpg" to cardImage))
        val draft = fetcher(emptyMap(), transport).fetchPreview("https://example.com/a")

        assertEquals("https://example.com/a", draft.preview.url)
        assertEquals("Example", draft.preview.siteName)
        assertEquals("The real title", draft.preview.title)
        assertEquals("OG description", draft.preview.summary)
        assertArrayEquals("THUMB".toByteArray(), draft.preview.thumbnail?.toByteArray())
        assertEquals(1024, draft.preview.imageWidth)
        assertEquals(538, draft.preview.imageHeight)
        assertFalse(draft.preview.isVideo)
        assertFalse(draft.preview.showsAboveText)
        assertArrayEquals("LARGE".toByteArray(), draft.largeImage?.toByteArray())
        assertEquals(1024, draft.largeImageWidth)
        assertEquals(538, draft.largeImageHeight)
        assertTrue("1024 × 538 is a wide card", draft.prefersLargeImage)

        // The page, then the image — upgraded to https — with the iOS headers.
        assertEquals(listOf("https://example.com/a", "https://cdn.example.com/card.jpg"), transport.requests.map { it.url.toString() })
        val page = transport.requests[0]
        assertEquals("WhatsApp/2 (compatible; ShroudBot/1.0)", page.header("User-Agent"))
        assertEquals("text/html,application/xhtml+xml;q=0.9,*/*;q=0.5", page.header("Accept"))
        assertEquals("de-DE,de;q=0.9,en;q=0.8", page.header("Accept-Language"))
        assertNull("no cookies, no referer", page.header("Cookie") ?: page.header("Referer"))
        assertEquals("image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8", transport.requests[1].header("Accept"))
        assertEquals("GET", page.method)
    }

    @Test
    fun aTypedHttpLinkIsFetchedOverHttpsButKeptAsTyped() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/a" to html("<head><title>T</title></head>")))
        val draft = fetcher(emptyMap(), transport).fetchPreview("http://example.com/a")
        assertEquals("https://example.com/a", transport.requests.single().url.toString())
        assertEquals("http://example.com/a", draft.preview.url)
        assertEquals("T", draft.preview.title)
        assertNull(draft.preview.thumbnail)
        assertFalse(draft.prefersLargeImage)
    }

    @Test
    fun aBareHostLinkKeepsItsCaseInThePreview() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/Tour" to html("<head><title>T</title></head>")))
        val draft = fetcher(emptyMap(), transport).fetchPreview("https://Example.COM/Tour")
        assertEquals("https://Example.COM/Tour", draft.preview.url)
    }

    @Test
    fun aVideoPageAlwaysPrefersTheLargeLayout() = runTest {
        val page = html("""<head><meta property="og:type" content="video.other"><meta property="og:image" content="https://cdn.example.com/v.jpg"></head>""")
        val transport = FakeTransport(mapOf("https://example.com/v" to page, "https://cdn.example.com/v.jpg" to Scripted(200, body = "IMG 300 300".toByteArray())))
        val draft = fetcher(emptyMap(), transport).fetchPreview("https://example.com/v")
        assertTrue(draft.preview.isVideo)
        assertTrue(draft.prefersLargeImage)
        assertNull("no title, no summary: the picture carries it", draft.preview.title)
    }

    @Test
    fun aDirectImageLinkIsItsOwnPreviewFromOneRequest() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/p.png" to Scripted(200, mapOf("Content-Type" to "image/png"), "IMG 800 400".toByteArray())))
        val draft = fetcher(emptyMap(), transport).fetchPreview("https://example.com/p.png")
        assertEquals(1, transport.requests.size)
        assertEquals("IMG 800 400", String(images.prepared.single()))
        assertNull(draft.preview.title)
        assertEquals(800, draft.preview.imageWidth)
        assertTrue(draft.prefersLargeImage)
    }

    @Test
    fun anEmptyContentTypeIsReadAsHtml() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/a" to Scripted(200, body = "<head><title>Untyped</title></head>".toByteArray())))
        assertEquals("Untyped", fetcher(emptyMap(), transport).fetchPreview("https://example.com/a").preview.title)
    }

    @Test
    fun xhtmlIsReadToo() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/a" to html("<head><title>X</title></head>", mapOf("Content-Type" to "application/xhtml+xml"))))
        assertEquals("X", fetcher(emptyMap(), transport).fetchPreview("https://example.com/a").preview.title)
    }

    // ---- refused before any request ---------------------------------------------------------------

    @Test
    fun forbiddenTargetsAreNeverRequested() = runTest {
        val transport = FakeTransport(emptyMap())
        val fetcher = fetcher(emptyMap(), transport)
        for (url in listOf(
            "https://192.168.1.1/",
            "https://10.0.0.8:443/",
            "https://[::1]/",
            "https://localhost/a",
            "https://printer.local/",
            "https://nas.home/",
            "https://intranet/",
            "https://example.com:8443/",
            "ftp://example.com/",
            "mailto:bob@example.com",
            "not a url",
        )) {
            expectFailure(LinkPreviewException.Reason.NotAllowed) { fetcher.fetchPreview(url) }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun aNameResolvingToAPrivateAddressIsNeverRequested() = runTest {
        val transport = FakeTransport(emptyMap())
        expectFailure(LinkPreviewException.Reason.NotAllowed) { fetcher(emptyMap(), transport).fetchPreview("https://home.example.com/router") }
        expectFailure(LinkPreviewException.Reason.NotAllowed) { fetcher(emptyMap(), transport).fetchPreview("https://unknown.example.org/") }
        assertTrue(transport.requests.isEmpty())
    }

    // ---- redirects --------------------------------------------------------------------------------

    @Test
    fun redirectsAreFollowedByHandAndUpgraded() = runTest {
        val transport = FakeTransport(
            mapOf(
                "https://example.com/a" to Scripted(301, mapOf("Location" to "/b")),
                "https://example.com/b" to Scripted(302, mapOf("Location" to "http://www.example.com/c?x=1")),
                "https://www.example.com/c?x=1" to html("""<head><title>Landed</title><meta property="og:image" content="img.jpg"></head>"""),
                "https://www.example.com/img.jpg" to Scripted(200, body = "IMG 200 200".toByteArray()),
            ),
        )
        val draft = fetcher(emptyMap(), transport).fetchPreview("https://example.com/a")
        assertEquals("Landed", draft.preview.title)
        assertEquals("https://example.com/a", draft.preview.url)
        assertEquals(
            listOf("https://example.com/a", "https://example.com/b", "https://www.example.com/c?x=1", "https://www.example.com/img.jpg"),
            transport.requests.map { it.url.toString() },
        )
        assertFalse("200 × 200 is a square: small layout", draft.prefersLargeImage)
    }

    @Test
    fun aRedirectToAPrivateAddressStopsTheFetch() = runTest {
        for (location in listOf("https://intranet.example/admin", "https://192.168.0.1/", "https://localhost/", "https://example.com:8080/", "ftp://example.com/x")) {
            val transport = FakeTransport(mapOf("https://example.com/a" to Scripted(307, mapOf("Location" to location))))
            expectFailure(LinkPreviewException.Reason.BadResponse) { fetcher(emptyMap(), transport).fetchPreview("https://example.com/a") }
            assertEquals(location, listOf("https://example.com/a"), transport.requests.map { it.url.toString() })
        }
    }

    @Test
    fun aRedirectLoopEndsAfterSixteenHops() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/loop" to Scripted(302, mapOf("Location" to "/loop"))))
        expectFailure(LinkPreviewException.Reason.BadResponse) { fetcher(emptyMap(), transport).fetchPreview("https://example.com/loop") }
        assertEquals(LinkPreviewFetcher.MAX_REDIRECTS + 1, transport.requests.size)
    }

    @Test
    fun aRedirectWithoutLocationIsABadResponse() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/a" to Scripted(302)))
        expectFailure(LinkPreviewException.Reason.BadResponse) { fetcher(emptyMap(), transport).fetchPreview("https://example.com/a") }
    }

    @Test
    fun aRefusedImageRedirectOnlyCostsThePicture() = runTest {
        val page = html("""<head><title>T</title><meta property="og:image" content="https://cdn.example.com/i.jpg"></head>""")
        val transport = FakeTransport(
            mapOf(
                "https://example.com/a" to page,
                "https://cdn.example.com/i.jpg" to Scripted(302, mapOf("Location" to "https://home.example.com/i.jpg")),
            ),
        )
        val draft = fetcher(emptyMap(), transport).fetchPreview("https://example.com/a")
        assertEquals("T", draft.preview.title)
        assertNull(draft.preview.thumbnail)
        assertEquals(2, transport.requests.size)
    }

    // ---- page answers -----------------------------------------------------------------------------

    @Test
    fun anErrorStatusIsABadResponse() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/a" to Scripted(500, body = "<head><title>Oops</title></head>".toByteArray())))
        expectFailure(LinkPreviewException.Reason.BadResponse) { fetcher(emptyMap(), transport).fetchPreview("https://example.com/a") }
    }

    @Test
    fun somethingThatIsNotAPageIsRefused() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/a.json" to Scripted(200, mapOf("Content-Type" to "application/json"), "{}".toByteArray())))
        expectFailure(LinkPreviewException.Reason.NotHtml) { fetcher(emptyMap(), transport).fetchPreview("https://example.com/a.json") }
    }

    @Test
    fun aPageWithNothingToShowIsEmpty() = runTest {
        val transport = FakeTransport(mapOf("https://example.com/a" to html("<html><head></head><body>Hello</body></html>")))
        expectFailure(LinkPreviewException.Reason.Empty) { fetcher(emptyMap(), transport).fetchPreview("https://example.com/a") }
    }

    @Test
    fun anImageOnlyPageWhoseImageFailsIsEmpty() = runTest {
        val page = html("""<head><meta property="og:image" content="https://cdn.example.com/gone.jpg"></head>""")
        val transport = FakeTransport(mapOf("https://example.com/a" to page))
        expectFailure(LinkPreviewException.Reason.Empty) { fetcher(emptyMap(), transport).fetchPreview("https://example.com/a") }
        assertEquals(2, transport.requests.size)
    }

    // ---- images -----------------------------------------------------------------------------------

    @Test
    fun aBrokenImageKeepsTheText() = runTest {
        val page = html("""<head><title>Text</title><meta property="og:image" content="https://cdn.example.com/broken.jpg"></head>""")
        val transport = FakeTransport(mapOf("https://example.com/a" to page, "https://cdn.example.com/broken.jpg" to Scripted(200, body = "not an image".toByteArray())))
        val draft = fetcher(emptyMap(), transport).fetchPreview("https://example.com/a")
        assertEquals("Text", draft.preview.title)
        assertNull(draft.preview.thumbnail)
        assertNull(draft.largeImage)
        assertNull(draft.preview.imageWidth)
        assertFalse(draft.prefersLargeImage)
    }

    @Test
    fun anImageDeclaredOverFiveMegabytesIsNeverDecoded() = runTest {
        val page = html("""<head><title>Text</title><meta property="og:image" content="https://cdn.example.com/huge.jpg"></head>""")
        val transport = FakeTransport(
            mapOf(
                "https://example.com/a" to page,
                "https://cdn.example.com/huge.jpg" to Scripted(200, body = "IMG 2000 1000".toByteArray(), declaredLength = 5L * 1024 * 1024 + 1),
            ),
        )
        val draft = fetcher(emptyMap(), transport).fetchPreview("https://example.com/a")
        assertNull(draft.preview.thumbnail)
        assertTrue(images.prepared.isEmpty())
    }

    @Test
    fun anImageThatTurnsOutOverFiveMegabytesIsNeverDecoded() = runTest {
        val page = html("""<head><title>Text</title><meta property="og:image" content="https://cdn.example.com/huge.jpg"></head>""")
        val big = "IMG 2000 1000 ".toByteArray() + ByteArray(5 * 1024 * 1024)
        val transport = FakeTransport(mapOf("https://example.com/a" to page, "https://cdn.example.com/huge.jpg" to Scripted(200, body = big)))
        fetcher(emptyMap(), transport).fetchPreview("https://example.com/a")
        assertTrue(images.prepared.isEmpty())
    }

    @Test
    fun anImageOnAPrivateHostIsDroppedWithoutARequest() = runTest {
        val page = html("""<head><title>T</title><meta property="og:image" content="https://home.example.com/cam.jpg"></head>""")
        val transport = FakeTransport(mapOf("https://example.com/a" to page))
        val draft = fetcher(emptyMap(), transport).fetchPreview("https://example.com/a")
        assertEquals("T", draft.preview.title)
        assertEquals(1, transport.requests.size)
    }

    // ---- reading ------------------------------------------------------------------------------------

    @Test
    fun theHeadReadStopsAtTheFirstFourKilobyteCheckAfterTheHeadEnds() {
        val page = ByteArray(5000) { 'a'.code.toByte() } + "</head>".toByteArray() + ByteArray(600 * 1024) { 'b'.code.toByte() }
        assertEquals(8192, LinkPreviewFetcher.readHead(Buffer().write(page)).size)
        val upper = ByteArray(100) + "</HEAD>".toByteArray() + ByteArray(600 * 1024)
        assertEquals(4096, LinkPreviewFetcher.readHead(Buffer().write(upper)).size)
    }

    @Test
    fun theHeadReadStopsAtHalfAMegabyte() {
        val page = ByteArray(600 * 1024) { 'x'.code.toByte() }
        assertEquals(LinkPreviewFetcher.MAX_HEAD_BYTES, LinkPreviewFetcher.readHead(Buffer().write(page)).size)
        // iOS looks for "</head" and "</HEAD" only; the parser still finds "</Head".
        val mixed = ByteArray(100) + "</Head>".toByteArray() + ByteArray(600 * 1024)
        assertEquals(LinkPreviewFetcher.MAX_HEAD_BYTES, LinkPreviewFetcher.readHead(Buffer().write(mixed)).size)
    }

    @Test
    fun aShortPageIsReadWhole() {
        val page = "<head><title>x</title>".toByteArray()
        assertArrayEquals(page, LinkPreviewFetcher.readHead(Buffer().write(page)))
    }

    // ---- small rules ------------------------------------------------------------------------------

    @Test
    fun acceptLanguage() { // LinkPreviewFetcher.swift:105-112, web acceptLanguageHeader
        assertEquals("de-DE,de;q=0.9,en;q=0.8", LinkPreviewFetcher.acceptLanguageHeader(listOf("de-DE", "de", "en", "fr")))
        assertEquals("en-US", LinkPreviewFetcher.acceptLanguageHeader(listOf("en-US")))
        assertEquals("en", LinkPreviewFetcher.acceptLanguageHeader(emptyList()))
        assertEquals("en", LinkPreviewFetcher.acceptLanguageHeader(listOf("und")))
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // a comma decimal separator must not leak into the header
            assertEquals("de-DE,en-GB;q=0.9", LinkPreviewFetcher.acceptLanguageHeader(listOf("de-DE", "en-GB")))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun theLargeLayoutRule() { // LinkPreviewFetcher.swift:164-168
        fun images(w: Int, h: Int) = PreparedLinkImages(null, null, w, h)
        assertFalse(LinkPreviewFetcher.prefersLargeImage(null, isVideo = true))
        assertTrue(LinkPreviewFetcher.prefersLargeImage(images(100, 100), isVideo = true))
        assertTrue(LinkPreviewFetcher.prefersLargeImage(images(400, 333), isVideo = false))
        assertFalse(LinkPreviewFetcher.prefersLargeImage(images(400, 334), isVideo = false))
        assertFalse(LinkPreviewFetcher.prefersLargeImage(images(399, 100), isVideo = false))
        assertFalse(LinkPreviewFetcher.prefersLargeImage(images(1024, 1024), isVideo = false))
    }

    @Test
    fun cancellingTheCallerCancelsTheFetch() = runTest {
        val hanging = object : LinkHttpTransport {
            var entered = false
            override suspend fun <T> execute(request: Request, read: (Response) -> T): T {
                entered = true
                awaitCancellation()
            }
        }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            fetcher(emptyMap(), hanging).fetchPreview("https://example.com/a")
        }
        assertTrue(hanging.entered)
        job.cancel()
        assertTrue(job.isCancelled)
    }

    @Test
    fun errorsNeverNameTheLink() = runTest {
        try {
            fetcher(emptyMap(), FakeTransport(emptyMap())).fetchPreview("https://secret-link.example.org/private")
            fail()
        } catch (e: LinkPreviewException) {
            assertFalse(e.message.orEmpty().contains("secret"))
        }
    }
}
