package de.corespace.shroud.core.net.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Wire-format tests for replies: the quote travels inside the sealed plaintext, so how a reply is
 * written and read back is the only thing the clients must agree on. Ports
 * `ios/shroudTests/MessageReplyTests.swift:8-128` (snippet, wire object, text payload; the media part
 * `:130-168` is in [MediaMessagePayloadTest]) and `web/src/reply.selftest.ts`; vectors verbatim.
 */
class MessageReplyReferenceTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val messageId = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    private val senderId = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    private fun reference(
        kind: MessageReplyReference.Kind = MessageReplyReference.Kind.Text,
        snippet: String = "Hey! Are we still on for tomorrow?",
    ) = MessageReplyReference(messageId, senderId, kind, snippet)

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    // --- Snippet ------------------------------------------------------------------------------------

    @Test
    fun snippetCollapsesWhitespace() { // :26-29
        assertEquals("two lines of text", reference(snippet = "  two   lines\nof   text \n").snippet)
    }

    @Test
    fun snippetIsClampedWithEllipsis() { // :31-36
        val clamped = MessageReplyReference.clampSnippet("a".repeat(400))
        assertEquals(MessageReplyReference.MAX_SNIPPET_CHARACTERS, Icu4jTextUnits.graphemeCount(clamped))
        assertTrue(clamped.endsWith("…"))
        assertEquals("a".repeat(119) + "…", clamped)
    }

    @Test
    fun snippetKeepsGraphemeClusters() { // :38-42
        val family = "👩‍👩‍👧‍👦"
        val clamped = MessageReplyReference.clampSnippet(family.repeat(60))
        assertTrue(Icu4jTextUnits.graphemeCount(clamped) <= MessageReplyReference.MAX_SNIPPET_CHARACTERS)
        // Sixty families are sixty characters: nothing to cut, nothing split.
        assertEquals(family.repeat(60), clamped)
        val cut = MessageReplyReference.clampSnippet(family.repeat(130))
        assertEquals(family.repeat(119) + "…", cut)
    }

    @Test
    fun snippetCutTrimsTrailingSpacesBeforeTheEllipsis() {
        val raw = "a".repeat(118) + " " + "b".repeat(10)
        assertEquals("a".repeat(118) + "…", MessageReplyReference.clampSnippet(raw))
    }

    @Test
    fun everyUnicodeWhitespaceCollapsesLikeSwift() {
        // NBSP, ideographic space, LINE SEPARATOR and NEL are White_Space; U+200B (zero width) is not.
        assertEquals("a b c d", MessageReplyReference.clampSnippet(" a　b c\u0085d\t"))
        assertEquals("a​b", MessageReplyReference.clampSnippet("a​b"))
        assertEquals("", MessageReplyReference.clampSnippet(" \n\t "))
    }

    // --- Wire object ----------------------------------------------------------------------------------

    @Test
    fun wireObjectRoundTrip() { // :46-52
        val original = reference(kind = MessageReplyReference.Kind.Image, snippet = "At the trailhead")
        assertEquals(original, MessageReplyReference.parse(original.wireObject()))
    }

    @Test
    fun wireObjectUsesLowercasedIds() { // :54-59
        val wire = reference().wireObject()
        assertEquals("3f2504e0-4f89-41d3-9a0c-0305e82c3301", wire["id"]!!.jsonPrimitive.content)
        assertEquals("6ba7b810-9dad-11d1-80b4-00c04fd430c8", wire["u"]!!.jsonPrimitive.content)
        assertEquals("text", wire["k"]!!.jsonPrimitive.content)
    }

    @Test
    fun wireObjectOmitsEmptySnippet() { // :61-63
        assertNull(reference(kind = MessageReplyReference.Kind.Voice, snippet = "").wireObject()["x"])
    }

    @Test
    fun parseRejectsMissingIds() { // :65-68
        assertNull(MessageReplyReference.parse(obj("u" to senderId.toString().uppercase(), "k" to "text")))
        assertNull(MessageReplyReference.parse(obj("id" to "not-a-uuid", "u" to senderId.toString().uppercase())))
        // web reply.selftest.ts:55-56
        assertNull(MessageReplyReference.parse(obj("u" to senderId.toString())))
        assertNull(MessageReplyReference.parse(obj("id" to messageId.toString())))
    }

    @Test
    fun parseFallsBackToTextKind() { // :70-77
        val parsed = MessageReplyReference.parse(
            obj("id" to messageId.toString().uppercase(), "u" to senderId.toString().uppercase(), "k" to "sticker"),
        )
        assertEquals(MessageReplyReference.Kind.Text, parsed?.kind)
    }

    @Test
    fun parseTrimsIdsAndReclampsTheSnippet() {
        val parsed = MessageReplyReference.parse(
            obj("id" to "  ${messageId.toString().uppercase()}\n", "u" to " $senderId ", "k" to " voice ", "x" to " one\n\ntwo "),
        )!!
        assertEquals(messageId, parsed.messageId)
        assertEquals(senderId, parsed.senderUserId)
        assertEquals(MessageReplyReference.Kind.Voice, parsed.kind)
        assertEquals("one two", parsed.snippet)
        // UUID(uuidString:) is strict: the lenient 1-1-1-1-1 that UUID.fromString takes is refused.
        assertNull(MessageReplyReference.parse(obj("id" to "1-1-1-1-1", "u" to senderId.toString())))
    }

    @Test
    fun kindLabels() { // MessageReplyReference.swift:22-29, web replyKindLabel
        assertNull(MessageReplyReference.Kind.Text.mediaLabel)
        assertEquals("Photo", MessageReplyReference.Kind.Image.mediaLabel)
        assertEquals("Video", MessageReplyReference.Kind.Video.mediaLabel)
        assertEquals("Voice message", MessageReplyReference.Kind.Voice.mediaLabel)
    }

    // --- Text payload -----------------------------------------------------------------------------

    @Test
    fun plainTextIsSealedVerbatim() { // :81-83
        assertEquals("Just a message", MessageTextPayload.wire("Just a message", null))
    }

    @Test
    fun plainTextParsesAsItself() { // :85-89
        val parsed = MessageTextPayload.parse("Just a message")
        assertEquals("Just a message", parsed.body)
        assertNull(parsed.replyTo)
    }

    @Test
    fun replyEnvelopeRoundTrip() { // :91-97
        val quote = reference()
        val parsed = MessageTextPayload.parse(MessageTextPayload.wire("Yes! 10am at the trailhead", quote))
        assertEquals("Yes! 10am at the trailhead", parsed.body)
        assertEquals(quote, parsed.replyTo)
    }

    @Test
    fun replyEnvelopeHasSortedKeys() {
        // .sortedKeys (MessageReplyReference.swift:148-150): c, re, t; inside re: id, k, u, x.
        assertEquals(
            """{"c":"Yes!","re":{"id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","k":"text","u":"6ba7b810-9dad-11d1-80b4-00c04fd430c8","x":"Hey! Are we still on for tomorrow?"},"t":"text"}""",
            MessageTextPayload.wire("Yes!", reference()),
        )
    }

    @Test
    fun jsonLookingTextIsNotMistakenForAReply() { // :99-108
        val typed = """{"t":"text","c":"nice try"}"""
        val parsed = MessageTextPayload.parse(typed)
        assertEquals("nice try", parsed.body)
        assertNull(parsed.replyTo)
        val other = """{"hello":"world"}"""
        assertEquals(other, MessageTextPayload.parse(other).body)
    }

    @Test
    fun truncatedEnvelopeFallsBackToRawText() { // :110-113
        val broken = """{"t":"text","c":"half"""
        assertEquals(broken, MessageTextPayload.parse(broken).body)
    }

    @Test
    fun opensWebClientEnvelope() { // :115-128, web reply.selftest.ts:85-98 (the same golden string)
        val fromWeb = "{\"t\":\"text\",\"c\":\"Perfect, see you there\"," +
            "\"re\":{\"id\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\"," +
            "\"u\":\"6ba7b810-9dad-11d1-80b4-00c04fd430c8\",\"k\":\"voice\",\"x\":\"Voice message\"}}"
        val parsed = MessageTextPayload.parse(fromWeb)
        assertEquals("Perfect, see you there", parsed.body)
        assertEquals(messageId, parsed.replyTo?.messageId)
        assertEquals(senderId, parsed.replyTo?.senderUserId)
        assertEquals(MessageReplyReference.Kind.Voice, parsed.replyTo?.kind)
        assertEquals("Voice message", parsed.replyTo?.snippet)
        assertTrue(MessageTextPayload.isEnvelope(fromWeb))
    }

    @Test
    fun bodyIsKeptUntrimmedAndSurroundingWhitespaceIsTolerated() {
        val wire = "\n  " + MessageTextPayload.wire("  spaced  ", reference()) + "  "
        assertEquals("  spaced  ", MessageTextPayload.parse(wire).body)
    }

    @Test
    fun envelopeNeedsTextKindAndStringBody() {
        for (raw in listOf(
            """{"t":"text"}""",
            """{"t":"text","c":5}""",
            """{"t":"image","c":"x"}""",
            """{"c":"x"}""",
            """[{"t":"text","c":"x"}]""",
        )) {
            assertEquals(raw, MessageTextPayload.parse(raw).body)
            assertFalse(MessageTextPayload.isEnvelope(raw))
        }
    }

    @Test
    fun textAJsonParserWouldBendStaysText() {
        // A raw line break inside a string, or a bare word, is not JSON (JSONSerialization refuses
        // both; kotlinx alone would read them).
        val lineBreak = "{\"t\":\"text\",\"c\":\"two\nlines\"}"
        assertEquals(lineBreak, MessageTextPayload.parse(lineBreak).body)
        val bareWord = """{"t":"text","c":"x","re":abc}"""
        assertEquals(bareWord, MessageTextPayload.parse(bareWord).body)
        // Escaped, the same line break is fine.
        assertEquals("two\nlines", MessageTextPayload.parse("""{"t":"text","c":"two\nlines"}""").body)
    }

    @Test
    fun bytesThatAreNotUtf8ReadAsAnEmptyBody() { // :175-180
        val parsed = MessageTextPayload.parse(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))
        assertEquals("", parsed.body)
        assertNull(parsed.replyTo)
        assertEquals("héllo", MessageTextPayload.parse("héllo".toByteArray()).body)
    }

    @Test
    fun toStringNeverShowsContent() {
        assertFalse(reference(snippet = "secret plans").toString().contains("secret"))
        assertFalse(MessageTextPayload.parse("secret plans").toString().contains("secret"))
    }
}
