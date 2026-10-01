package de.corespace.shroud.core.net.wire

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.model.Bytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Wire-format tests for link previews: the preview is sealed inside the plaintext, so how `lp` is
 * written and read is the whole contract. Ports `ios/shroudTests/LinkPreviewPayloadTests.swift`
 * (all but the two stored-message cases, which belong to the local store, W2-MSG-STORE; their
 * `{"u":"https://example.com"}` decode is covered here through [LinkPreview.parse]) and the `lp`
 * part of `web/src/links.selftest.ts`; vectors verbatim.
 */
class LinkPreviewPayloadTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val thumbnail = Bytes.of(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46))

    private fun sample(thumbnail: Bytes? = null, isVideo: Boolean = true, showsAboveText: Boolean = true) = LinkPreview(
        url = "https://komoot.com/tour/1398273",
        siteName = "komoot",
        title = "Herzogstand – Heimgarten ridge walk",
        summary = "Intermediate hike · 13.6 km",
        thumbnail = thumbnail,
        imageWidth = 1200,
        imageHeight = 630,
        isVideo = isVideo,
        showsAboveText = showsAboveText,
    )

    private fun lp(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (key, value) ->
            key to when (value) {
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> error("unsupported")
            }
        },
    )

    // --- lp object --------------------------------------------------------------------------------

    @Test
    fun wireObjectRoundTrip() { // :28-31
        val original = sample(thumbnail)
        assertEquals(original, LinkPreview.parse(original.wire()))
    }

    @Test
    fun wireObjectUsesTerseKeysAndOmitsFalseFlags() { // :33-45
        val wire = sample(isVideo = false, showsAboveText = false).wire()
        assertEquals("https://komoot.com/tour/1398273", wire["u"]!!.jsonPrimitive.content)
        assertEquals("komoot", wire["n"]!!.jsonPrimitive.content)
        assertEquals("Herzogstand – Heimgarten ridge walk", wire["ti"]!!.jsonPrimitive.content)
        assertEquals("Intermediate hike · 13.6 km", wire["d"]!!.jsonPrimitive.content)
        assertNull(wire["vd"])
        assertNull(wire["ab"])
        assertNull(wire["th"])
    }

    @Test
    fun wireObjectKeysAreSortedAndNonPositiveSizesLeftOut() {
        assertEquals(listOf("ab", "d", "h", "n", "th", "ti", "u", "vd", "w"), sample(thumbnail).wire().keys.toList())
        val wire = LinkPreview("https://example.com", imageWidth = 0, imageHeight = -3).wire()
        assertEquals(listOf("u"), wire.keys.toList())
    }

    @Test
    fun onlyWebUrlsParse() { // :47-52, web links.selftest.ts:92-94
        for (url in listOf("javascript:alert(1)", "ftp://example.com", "file:///etc/passwd", "")) {
            assertNull(url, LinkPreview.parse(lp("u" to url)))
        }
        assertNotNull(LinkPreview.parse(lp("u" to "http://example.com")))
        assertNotNull(LinkPreview.parse(lp("u" to "HTTPS://Example.com/Path")))
        assertNull(LinkPreview.parse(lp("n" to "no url")))
        assertNull(LinkPreview.parse(JsonObject(mapOf("u" to JsonPrimitive(5)))))
    }

    @Test
    fun urlIsTrimmedAndCappedAt2048Characters() {
        assertEquals("https://example.com/a", LinkPreview.parse(lp("u" to "  https://example.com/a \n"))?.url)
        val base = "https://example.com/"
        assertNotNull(LinkPreview.parse(lp("u" to base + "a".repeat(LinkPreview.MAX_URL_CHARACTERS - base.length))))
        assertNull(LinkPreview.parse(lp("u" to base + "a".repeat(LinkPreview.MAX_URL_CHARACTERS - base.length + 1))))
    }

    @Test
    fun oversizedThumbnailIsDropped() { // :54-59
        val big = ByteArray(LinkPreview.MAX_THUMBNAIL_BYTES + 1) { 1 }
        val parsed = LinkPreview.parse(lp("u" to "https://example.com", "th" to B64.encode(big)))
        assertNotNull(parsed)
        assertNull(parsed!!.thumbnail)
        val fits = ByteArray(LinkPreview.MAX_THUMBNAIL_BYTES) { 1 }
        assertEquals(Bytes.of(fits), LinkPreview.parse(lp("u" to "https://example.com", "th" to B64.encode(fits)))!!.thumbnail)
    }

    @Test
    fun thumbnailThatIsNotStrictBase64IsDropped() {
        val parsed = LinkPreview.parse(lp("u" to "https://example.com", "th" to "/9j/4AAQSkZJRg"))!!
        assertNull(parsed.thumbnail)
    }

    @Test
    fun longFieldsAreClamped() { // :61-65, web links.selftest.ts:99-102
        val preview = LinkPreview(url = "https://example.com", title = "a".repeat(500))
        assertEquals(LinkPreview.MAX_TITLE_CHARACTERS, Icu4jTextUnits.graphemeCount(preview.title!!))
        assertTrue(preview.title!!.endsWith("…"))
        val parsed = LinkPreview.parse(lp("u" to "https://example.com", "ti" to "a".repeat(500), "n" to "n".repeat(70), "d" to "d".repeat(301)))!!
        assertEquals(LinkPreview.MAX_TITLE_CHARACTERS, Icu4jTextUnits.graphemeCount(parsed.title!!))
        assertEquals("n".repeat(63) + "…", parsed.siteName)
        assertEquals("d".repeat(299) + "…", parsed.summary)
    }

    @Test
    fun textsAreCollapsedAndBlankOnesDropped() {
        val preview = LinkPreview(url = "https://example.com", siteName = "  ", title = " A\n\n page ", summary = "\t")
        assertNull(preview.siteName)
        assertEquals("A page", preview.title)
        assertNull(preview.summary)
        assertTrue(LinkPreview("https://example.com").isEmpty)
        assertFalse(preview.isEmpty)
    }

    @Test
    fun displaySiteNameFallsBackToHost() { // :67-70, web links.selftest.ts:103-105
        assertEquals("example.com", LinkPreview(url = "https://www.example.com/a", title = "A page").displaySiteName)
        assertEquals("komoot", sample().displaySiteName)
        assertEquals("not a url", LinkPreview("not a url").displayHost)
        assertEquals("example.org", LinkPreview("https://WWW.Example.org").displayHost)
    }

    @Test
    fun derivedValues() {
        assertEquals(1200f / 630f, sample().imageAspect!!, 0.0001f)
        assertNull(LinkPreview("https://example.com", imageWidth = 10).imageAspect)
        assertEquals("https://komoot.com/tour/1398273", sample().openUrl)
        assertNull(LinkPreview("javascript:alert(1)").openUrl)
        assertNull(sample(thumbnail).withoutThumbnail().thumbnail)
        assertNull(sample().withoutSummary().summary)
        assertEquals("komoot", sample().withoutSummary().siteName)
    }

    @Test
    fun flagsAndSizesAreReadLikeTheSwiftCasts() {
        // `as? Bool == true`: JSON true, or a number Swift bridges to true; never a string.
        val parsed = LinkPreview.parse(lp("u" to "https://example.com", "vd" to 1, "ab" to "true", "w" to "1200", "h" to 630.9))!!
        assertTrue(parsed.isVideo)
        assertFalse(parsed.showsAboveText)
        // `(value as? NSNumber)?.intValue`: a string "1200" is ignored, 630.9 truncates.
        assertNull(parsed.imageWidth)
        assertEquals(630, parsed.imageHeight)
    }

    @Test
    fun storedPreviewWithOnlyAUrlDecodesWithFalseFlags() { // :232-238
        val preview = LinkPreview.parse(lp("u" to "https://example.com"))!!
        assertEquals("https://example.com", preview.url)
        assertFalse(preview.isVideo)
        assertFalse(preview.showsAboveText)
    }

    @Test
    fun webSelftestObjectRoundTrips() { // web links.selftest.ts:78-91
        val full = LinkPreview.parse(
            lp(
                "u" to "https://komoot.com/tour/1398273", "n" to "komoot", "ti" to "Herzogstand – Heimgarten ridge walk",
                "d" to "Intermediate hike · 13.6 km", "th" to "/9j/4AAQ", "w" to 1200, "h" to 630, "vd" to true, "ab" to true,
            ),
        )!!
        assertEquals(full, LinkPreview.parse(full.wire()))
        assertEquals("/9j/4AAQ", full.wire()["th"]!!.jsonPrimitive.content)
    }

    // --- Text envelope --------------------------------------------------------------------------------

    @Test
    fun plainTextStaysRaw() { // :74-76
        assertEquals("hi", MessageTextPayload.wire("hi", null, null))
    }

    @Test
    fun textEnvelopeRoundTrip() { // :78-84
        val wire = MessageTextPayload.wire("Route: komoot.com/tour/1398273", null, sample(thumbnail))
        val parsed = MessageTextPayload.parse(wire)
        assertEquals("Route: komoot.com/tour/1398273", parsed.body)
        assertNull(parsed.replyTo)
        assertEquals(sample(thumbnail), parsed.linkPreview)
    }

    @Test
    fun replyAndPreviewTravelTogether() { // :86-98
        val quote = MessageReplyReference(
            UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301"),
            UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
            MessageReplyReference.Kind.Text,
            "Which trail?",
        )
        val wire = MessageTextPayload.wire("This one", quote, sample())
        val parsed = MessageTextPayload.parse(wire)
        assertEquals(quote, parsed.replyTo)
        assertEquals(sample(), parsed.linkPreview)
        assertTrue(MessageTextPayload.isEnvelope(wire))
        // Sorted recursively: c, lp (its keys sorted), re, t.
        assertTrue(wire.startsWith("""{"c":"This one","lp":{"ab":true,"d":"""))
        assertTrue(wire.endsWith(""""x":"Which trail?"},"t":"text"}"""))
    }

    @Test
    fun parsesWebSealedPreview() { // :100-113, web links.selftest.ts:108-124
        val fromWeb = """{"t":"text","c":"Route: komoot.com/tour/1398273","lp":{"u":"https://komoot.com/tour/1398273","n":"komoot","ti":"Herzogstand","d":"Ridge walk","w":1200,"h":630,"vd":true,"ab":true}}"""
        val parsed = MessageTextPayload.parse(fromWeb)
        assertEquals("Route: komoot.com/tour/1398273", parsed.body)
        val preview = parsed.linkPreview!!
        assertEquals("https://komoot.com/tour/1398273", preview.url)
        assertEquals("komoot", preview.siteName)
        assertEquals("Herzogstand", preview.title)
        assertEquals("Ridge walk", preview.summary)
        assertEquals(1200, preview.imageWidth)
        assertEquals(630, preview.imageHeight)
        assertTrue(preview.isVideo)
        assertTrue(preview.showsAboveText)
    }

    @Test
    fun parsesWhatTheWebClientSeals() { // :115-135
        val fromWeb = """{"t":"text","c":"Route for Saturday","re":{"id":"11111111-1111-1111-1111-111111111111","u":"22222222-2222-2222-2222-222222222222","k":"text","x":"Which route?"},"lp":{"u":"https://www.komoot.com/tour/1398273","n":"komoot","ti":"Herzogstand – Heimgarten ridge walk","d":"Intermediate hike · 13.6 km · 5:10 h.","th":"/9j/4AAQSkZJRg==","w":1200,"h":630,"ab":true}}"""
        val parsed = MessageTextPayload.parse(fromWeb)
        assertEquals("Route for Saturday", parsed.body)
        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), parsed.replyTo?.messageId)
        assertEquals("Which route?", parsed.replyTo?.snippet)
        val preview = parsed.linkPreview!!
        assertEquals("https://www.komoot.com/tour/1398273", preview.url)
        assertEquals("komoot", preview.siteName)
        assertEquals("Herzogstand – Heimgarten ridge walk", preview.title)
        assertEquals("Intermediate hike · 13.6 km · 5:10 h.", preview.summary)
        assertEquals(B64.decodeStrict("/9j/4AAQSkZJRg==")!!.let(Bytes::of), preview.thumbnail)
        assertEquals(1200, preview.imageWidth)
        assertTrue(preview.showsAboveText)
    }

    @Test
    fun parsesTheLinkMediaTheWebClientSeals() { // :137-148
        val fromWeb = """{"t":"link","mime":"image/jpeg","w":1200,"h":630,"k":"a2V5","s":48000,"c":"Route for Saturday","th":"/9j/4AAQ","lp":{"u":"https://www.komoot.com/tour/1398273","n":"komoot","ti":"Herzogstand – Heimgarten ridge walk","d":"Intermediate hike · 13.6 km · 5:10 h.","w":1200,"h":630,"ab":true}}"""
        val payload = MediaMessagePayload.parse(fromWeb.toByteArray())!!
        assertTrue(payload.isLink)
        assertFalse(payload.isImage)
        assertEquals("Route for Saturday", payload.c)
        assertEquals(1200, payload.w)
        assertEquals("Herzogstand – Heimgarten ridge walk", payload.lp?.title)
        assertNull("the blob is the picture", payload.lp?.thumbnail)
        assertTrue(B64.decodeStrict("/9j/4AAQ")!!.contentEquals(payload.previewJpeg!!))
    }

    @Test
    fun brokenPreviewKeepsTheMessage() { // :150-155, web links.selftest.ts:127-130
        val parsed = MessageTextPayload.parse("""{"t":"text","c":"still readable","lp":{"u":"javascript:alert(1)"}}""")
        assertEquals("still readable", parsed.body)
        assertNull(parsed.linkPreview)
        assertNull(MessageTextPayload.parse("just text").linkPreview)
    }

    // --- Media envelope (large image) -----------------------------------------------------------------

    @Test
    fun linkMediaPayloadRoundTrip() { // :159-177
        val payload = MediaMessagePayload(
            t = MediaMessagePayload.KIND_LINK, mime = "image/jpeg", w = 1200, h = 630, k = "a2V5",
            c = "Route: komoot.com/tour/1398273", s = 48_000, lp = sample(),
        )
        val parsed = MediaMessagePayload.parse(payload.encoded())!!
        assertTrue(parsed.isLink)
        assertFalse(parsed.isImage)
        assertFalse(parsed.isVoice)
        assertFalse(parsed.isVideo)
        assertEquals(sample(), parsed.lp)
        assertEquals("Route: komoot.com/tour/1398273", parsed.c)
    }

    @Test
    fun linkPayloadWithoutPreviewIsNotALink() { // :179-183
        val parsed = MediaMessagePayload.parse("""{"t":"link","mime":"image/jpeg","w":4,"h":3,"k":"a2V5","c":"text"}""".toByteArray())!!
        assertFalse(parsed.isLink)
        assertFalse(parsed.isImage)
    }

    @Test
    fun legacyPhotoPayloadHasNoPreview() { // :185-191, web links.selftest.ts:141-142
        val parsed = MediaMessagePayload.parse("""{"t":"image","mime":"image/jpeg","w":4,"h":3,"k":"a2V5","c":"caption"}""".toByteArray())!!
        assertTrue(parsed.isImage)
        assertNull(parsed.lp)
    }

    @Test
    fun webLinkMediaIsALinkNotAPhotoVoiceNoteOrVideo() { // web links.selftest.ts:134-140
        val raw = """{"t":"link","mime":"image/jpeg","w":1200,"h":630,"k":"a2V5","c":"Route","s":48000,"lp":{"u":"https://komoot.com"}}"""
        val parsed = MediaMessagePayload.parse(raw.toByteArray())!!
        assertTrue(parsed.isLink)
        assertFalse(parsed.isVoice)
        assertFalse(parsed.isVideo)
    }

    // --- Budget (MessagingController.textWire) -------------------------------------------------------

    @Test
    fun textWireKeepsEverythingWhenSmall() { // :195-198
        assertEquals(sample(thumbnail), MessageTextPayload.textWire("short", null, sample(thumbnail)).sealedPreview)
    }

    @Test
    fun textWireDropsThumbnailThenPreviewForLongMessages() { // :200-211
        val bigThumb = Bytes.of(ByteArray(5 * 1024) { 7 })
        val mid = "a".repeat(7 * 1024)
        val trimmed = MessageTextPayload.textWire(mid, null, sample(bigThumb))
        assertNotNull(trimmed.sealedPreview)
        assertNull(trimmed.sealedPreview!!.thumbnail)

        val huge = "a".repeat(13 * 1024)
        val dropped = MessageTextPayload.textWire(huge, null, sample())
        assertNull(dropped.sealedPreview)
        assertEquals(huge, dropped.wire)
    }

    @Test
    fun textWireDropsTheSummaryBeforeThePreview() {
        val summary = "s".repeat(LinkPreview.MAX_SUMMARY_CHARACTERS)
        val preview = LinkPreview("https://example.com", title = "t", summary = summary)
        // The body plus the envelope overhead is ~12 KiB, the summary pushes it over.
        val body = "b".repeat(MessageTextPayload.MAX_TEXT_PLAINTEXT_BYTES - 100)
        val result = MessageTextPayload.textWire(body, null, preview)
        assertNotNull(result.sealedPreview)
        assertNull(result.sealedPreview!!.summary)
        assertEquals("t", result.sealedPreview!!.title)
        assertTrue(WireText.utf8Count(result.wire) <= MessageTextPayload.MAX_TEXT_PLAINTEXT_BYTES)
        // Without a preview the reply envelope (or the raw body) goes as it is.
        assertEquals("plain", MessageTextPayload.textWire("plain", null, null).wire)
    }

    @Test
    fun toStringNeverShowsContent() {
        val text = sample(thumbnail).toString()
        assertFalse(text.contains("komoot"))
        assertFalse(text.contains("Herzogstand"))
    }
}
