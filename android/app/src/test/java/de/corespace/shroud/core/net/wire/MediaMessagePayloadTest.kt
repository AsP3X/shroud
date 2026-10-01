package de.corespace.shroud.core.net.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Wire format shared with the web client (`parseMediaPayload`): it must survive JSON a strict decoder
 * rejects (missing zeros, doubles, extra keys). Ports `ios/shroudTests/MediaMessagePayloadTests.swift`
 * (all 7) and the media part of `MessageReplyTests.swift:130-168` (3); vectors verbatim.
 */
class MediaMessagePayloadTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private fun parse(json: String): MediaMessagePayload? = MediaMessagePayload.parse(json.toByteArray())

    // MediaMessagePayloadTests.swift:8-18
    @Test
    fun webStyleVoicePayloadParsesWithoutWidthHeight() {
        val payload = parse("""{"t":"voice","mime":"audio/mp4","k":"a2V5","d":4200,"wf":"AQI="}""")!!
        assertTrue(payload.isVoice)
        assertEquals(0, payload.w)
        assertEquals(0, payload.h)
        assertEquals(4200, payload.d)
        assertEquals("a2V5", payload.k)
        assertEquals("AQI=", payload.wf)
    }

    // MediaMessagePayloadTests.swift:20-29
    @Test
    fun webStyleImagePayloadParses() {
        val payload = parse("""{"t":"image","mime":"image/jpeg","w":800,"h":600,"k":"a2V5","s":12345,"th":"qqo="}""")!!
        assertTrue(payload.isImage)
        assertEquals(800, payload.w)
        assertEquals(600, payload.h)
        assertEquals(12345L, payload.s)
        assertEquals(2, payload.previewJpeg?.size)
    }

    // MediaMessagePayloadTests.swift:31-40
    @Test
    fun numericFieldsAcceptJsonDoubles() {
        val payload = parse("""{"t":"voice","mime":"audio/mp4","w":0.0,"h":0.0,"k":"a2V5","d":1500.0,"s":99.0}""")!!
        assertTrue(payload.isVoice)
        assertEquals(0, payload.w)
        assertEquals(0, payload.h)
        assertEquals(1500, payload.d)
        assertEquals(99L, payload.s)
    }

    // MediaMessagePayloadTests.swift:42-49
    @Test
    fun extraKeysAndNullOptionalsAreIgnored() {
        val payload = parse("""{"t":"video","mime":"video/mp4","w":1,"h":2,"k":"a2V5","c":null,"wf":null,"extra":true}""")!!
        assertTrue(payload.isVideo)
        assertNull(payload.c)
        assertNull(payload.wf)
    }

    // MediaMessagePayloadTests.swift:51-57
    @Test
    fun mimeSniffsWhenTypeIsUnknown() {
        val payload = parse("""{"t":"note","mime":"audio/mp4","k":"a2V5"}""")!!
        assertTrue(payload.isVoice)
        assertFalse(payload.isImage)
    }

    // MediaMessagePayloadTests.swift:59-65
    @Test
    fun utf8BomDoesNotHideAValidPayload() {
        val data = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            """{"t":"image","mime":"image/png","w":1,"h":1,"k":"a2V5"}""".toByteArray()
        assertTrue(MediaMessagePayload.parse(data)!!.isImage)
    }

    // MediaMessagePayloadTests.swift:67-83
    @Test
    fun encodedRoundTripsThroughParse() {
        val original = MediaMessagePayload(
            t = MediaMessagePayload.KIND_VOICE, mime = "audio/mp4", w = 0, h = 0, k = "a2V5",
            c = "hello", d = 900, wf = "AQI=", th = null, s = 44,
        )
        assertEquals(original, MediaMessagePayload.parse(original.encoded()))
    }

    /**
     * MediaMessagePayloadTests.swift:85-105 checks that `JSONEncoder` output still parses. Android has
     * no second encoder; the equivalent is a naive object writer — every key present, nulls included.
     */
    @Test
    fun naiveEncoderOutputStillParses() {
        val naive = """{"t":"image","mime":"image/jpeg","w":10,"h":20,"k":"a2V5","c":null,"d":null,"wf":null,"th":"qqo=","s":8,"re":null,"lp":null}"""
        val parsed = parse(naive)!!
        assertEquals("image", parsed.t)
        assertEquals(10, parsed.w)
        assertEquals(20, parsed.h)
        assertEquals("qqo=", parsed.th)
        assertNull(parsed.re)
        assertNull(parsed.lp)
    }

    // --- Reply media part (MessageReplyTests.swift:130-168) ----------------------------------------

    private val messageId = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    private val senderId = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    @Test
    fun mediaPayloadCarriesTheQuote() {
        val quote = MessageReplyReference(messageId, senderId, MessageReplyReference.Kind.Image, "Nice shot")
        val payload = MediaMessagePayload(t = MediaMessagePayload.KIND_IMAGE, mime = "image/jpeg", w = 1024, h = 768, k = "a2V5", s = 2048, re = quote)
        val parsed = MediaMessagePayload.parse(payload.encoded())!!
        assertEquals(quote, parsed.re)
        assertEquals("a2V5", parsed.k)
    }

    @Test
    fun mediaPayloadWithoutQuoteStillParses() {
        val payload = MediaMessagePayload(t = MediaMessagePayload.KIND_VOICE, mime = "audio/mp4", w = 0, h = 0, k = "a2V5", d = 1200)
        assertNull(MediaMessagePayload.parse(payload.encoded())!!.re)
    }

    @Test
    fun legacyMediaPayloadIsUnaffected() {
        val parsed = parse("""{"t":"image","mime":"image/jpeg","w":4,"h":3,"k":"a2V5","c":"caption"}""")!!
        assertNull(parsed.re)
        assertEquals("caption", parsed.c)
    }

    /** web `reply.selftest.ts:100-111`: the web's `withReply` output carries the quote. */
    @Test
    fun webMediaWithQuoteParses() {
        val fromWeb = """{"t":"image","mime":"image/jpeg","w":1024,"h":768,"k":"a2V5","s":2048,"re":{"id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","u":"6ba7b810-9dad-11d1-80b4-00c04fd430c8","k":"image","x":"Nice shot"}}"""
        val quote = parse(fromWeb)!!.re!!
        assertEquals(messageId, quote.messageId)
        assertEquals(MessageReplyReference.Kind.Image, quote.kind)
        assertEquals("Nice shot", quote.snippet)
    }

    // --- The lenient rules of MediaModels.swift:120-192, one by one ---------------------------------

    @Test
    fun typeAndKeyAreRequiredNonBlankStrings() {
        assertNull(parse("""{"mime":"image/jpeg","k":"a2V5"}"""))
        assertNull(parse("""{"t":"image","mime":"image/jpeg"}"""))
        assertNull(parse("""{"t":"  ","k":"a2V5"}"""))
        assertNull(parse("""{"t":"image","k":" \n"}"""))
        assertNull(parse("""{"t":1,"k":"a2V5"}"""))
        assertNull(parse("""{"t":"image","k":null}"""))
        assertNull(parse("""["t","k"]"""))
        assertNull(parse("not json"))
        assertNull(MediaMessagePayload.parse(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun stringsAreTrimmedAndBlankOnesAreAbsent() {
        val payload = parse("""{"t":" image ","k":" a2V5 ","mime":" ","c":"  caption \n","th":""}""")!!
        assertEquals("image", payload.t)
        assertEquals("a2V5", payload.k)
        assertEquals("application/octet-stream", payload.mime)
        assertEquals("caption", payload.c)
        assertNull(payload.th)
        assertNull(payload.previewJpeg)
    }

    @Test
    fun numbersMayBeNumericStringsOrBooleansLikeNsNumber() {
        val payload = parse("""{"t":"video","k":"a2V5","w":"640","h":"+480","d":"-5","s":"12345678901"}""")!!
        assertEquals(640, payload.w)
        assertEquals(480, payload.h)
        assertEquals(-5, payload.d)
        assertEquals(12_345_678_901L, payload.s)
        // Swift Int(_:) takes ASCII digits only, no spaces or fractions.
        val strict = parse("""{"t":"video","k":"a2V5","w":" 640","h":"4.5","d":"١٢","s":"1e3"}""")!!
        assertEquals(0, strict.w)
        assertEquals(0, strict.h)
        assertNull(strict.d)
        assertNull(strict.s)
        // JSONSerialization hands booleans over as NSNumber 0/1; doubles truncate toward zero.
        val odd = parse("""{"t":"video","k":"a2V5","w":true,"h":-2.9,"d":1.5e3}""")!!
        assertEquals(1, odd.w)
        assertEquals(-2, odd.h)
        assertEquals(1500, odd.d)
    }

    @Test
    fun replyAndPreviewAreReadOnlyFromObjectsAndDroppedWhenBroken() {
        val payload = parse("""{"t":"link","k":"a2V5","re":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","lp":{"u":"ftp://x"}}""")!!
        assertNull(payload.re)
        assertNull(payload.lp)
        assertFalse(payload.isLink)
    }

    @Test
    fun whitespaceAroundTheObjectIsTolerated() {
        // Unicode whitespace JSON does not allow: the trimmed retry of MediaModels.swift:126-131.
        assertNotNull(parse("   {\"t\":\"image\",\"k\":\"a2V5\"}　"))
        assertNull(parse("x {\"t\":\"image\",\"k\":\"a2V5\"}"))
    }

    @Test
    fun previewNeedsStrictBase64() {
        assertNull(parse("""{"t":"image","k":"a2V5","th":"qqo"}""")!!.previewJpeg)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xAA.toByte()), parse("""{"t":"image","k":"a2V5","th":"qqo="}""")!!.previewJpeg)
    }

    @Test
    fun encodedWritesTheRequiredKeysAlwaysAndTheRestOnlyWhenSet() {
        val bare = Json.parseToJsonElement(
            String(MediaMessagePayload(t = "voice", mime = "audio/mp4", w = 0, h = 0, k = "a2V5").encoded()),
        ).jsonObject
        assertEquals(setOf("t", "mime", "w", "h", "k"), bare.keys)
        val full = MediaMessagePayload(
            t = "link", mime = "image/jpeg", w = 4, h = 3, k = "a2V5", c = "c", d = 1, wf = "AQI=", th = "qqo=", s = 2,
            re = MessageReplyReference(messageId, senderId, MessageReplyReference.Kind.Text, ""),
            lp = LinkPreview("https://example.com"),
        )
        val obj = Json.parseToJsonElement(String(full.encoded())).jsonObject
        assertEquals(setOf("t", "mime", "w", "h", "k", "c", "d", "wf", "th", "s", "re", "lp"), obj.keys)
        assertTrue(obj["re"] is JsonObject)
        assertTrue(full.isLink)
    }

    @Test
    fun toStringNeverShowsTheKeyOrCaption() {
        val text = MediaMessagePayload(t = "image", mime = "image/jpeg", w = 1, h = 1, k = "c2VjcmV0LWtleQ==", c = "private caption").toString()
        assertFalse(text.contains("c2VjcmV0LWtleQ=="))
        assertFalse(text.contains("private caption"))
    }
}
