package de.corespace.shroud.core.net.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * The recipient sealed inside a message (`to`, security audit M7): what Android seals, that the
 * parsers every current build runs still read it, and which plaintexts [SealedRecipient.accepts]
 * lets through. The fixtures are the strings `web/src/sealedRecipient.selftest.ts` and iOS
 * `SealedRecipientTests` check.
 */
class SealedRecipientTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val me = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val bob = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    private val alice = UUID.fromString("a11ce000-0000-4000-8000-000000000001")

    private fun accepts(plaintext: String, contentType: String, expected: UUID) =
        SealedRecipient.accepts(plaintext.toByteArray(), contentType, expected)

    // --- Text -------------------------------------------------------------------------------------

    @Test
    fun aPlainMessageIsSealedAsTheEnvelopeNamingItsRecipient() {
        val wire = MessageTextPayload.wire("See you at 10", null, null, bob)
        assertEquals("""{"c":"See you at 10","t":"text","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}""", wire)
        // Without a recipient the body stays raw, as every build sent it before.
        assertEquals("See you at 10", MessageTextPayload.wire("See you at 10", null))
    }

    @Test
    fun theParserEveryBuildRunsShowsTheBody() {
        val parsed = MessageTextPayload.parse(MessageTextPayload.wire("See you at 10", null, null, bob))
        assertEquals("See you at 10", parsed.body)
        assertNull(parsed.replyTo)
        assertNull(parsed.linkPreview)
        // The web seals the keys in another order.
        val fromWeb = """{"t":"text","c":"See you at 10","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}"""
        assertEquals("See you at 10", MessageTextPayload.parse(fromWeb).body)
        assertTrue(accepts(fromWeb, "text", bob))
    }

    @Test
    fun boundAddsTheRecipientToAReadyWireAndWrapsAnythingElse() {
        val reply = MessageReplyReference(UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301"), me, MessageReplyReference.Kind.Text, "10?")
        val replyWire = MessageTextPayload.wire("Yes", reply)
        assertEquals(MessageTextPayload.wire("Yes", reply, null, bob), MessageTextPayload.bound(replyWire, bob))
        assertEquals(MessageTextPayload.wire("hi", null, null, bob), MessageTextPayload.bound("hi", bob))
        // A Notes todo keeps its marker as the body.
        val todo = MessageTextPayload.bound("[todo:0]Buy milk", me)
        assertEquals("[todo:0]Buy milk", MessageTextPayload.parse(todo).body)
        assertTrue(accepts(todo, "text", me))
        // An envelope that already names someone is re-addressed, not doubled.
        val readdressed = MessageTextPayload.bound(MessageTextPayload.wire("x", null, null, alice), bob)
        assertEquals(MessageTextPayload.wire("x", null, null, bob), readdressed)
    }

    @Test
    fun theRecipientIsCheckedOnText() {
        val wire = MessageTextPayload.wire("See you at 10", null, null, bob)
        assertTrue(accepts(wire, "text", bob))
        assertFalse(accepts(wire, "text", alice))
        assertFalse(accepts(wire, "text", me))
        assertEquals(SealedRecipient.Named.User(bob), SealedRecipient.named(wire.toByteArray(), "text"))
    }

    @Test
    fun legacyAndTypedTextNamesNobody() {
        assertTrue(accepts("See you at 10", "text", alice))
        val reply = MessageReplyReference(UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301"), me, MessageReplyReference.Kind.Text, "10?")
        assertTrue(accepts(MessageTextPayload.wire("Yes", reply), "text", alice))
        // JSON someone typed is not the envelope, whatever keys it has.
        assertTrue(accepts("""{"to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}""", "text", alice))
        assertTrue(accepts("""{"t":"text","c":"x","to":null}""", "text", alice))
        assertEquals(SealedRecipient.Named.Nobody, SealedRecipient.named("plain".toByteArray(), "text"))
    }

    @Test
    fun aToThatIsNotAnIdFailsClosed() {
        assertFalse(accepts("""{"t":"text","c":"x","to":5}""", "text", alice))
        assertFalse(accepts("""{"t":"text","c":"x","to":""}""", "text", alice))
        assertFalse(accepts("""{"t":"text","c":"x","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb "}""", "text", bob))
        assertEquals(SealedRecipient.Named.Invalid, SealedRecipient.named("""{"t":"text","c":"x","to":"nope"}""".toByteArray(), "text"))
        // Any case of the canonical form is the same id.
        assertTrue(accepts("""{"t":"text","c":"x","to":"BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB"}""", "text", bob))
    }

    // --- Media and annotations ----------------------------------------------------------------------

    @Test
    fun mediaPayloadsNameTheirRecipientAndStillParse() {
        val fixture = """{"h":768,"k":"a2V5","mime":"image/jpeg","t":"image","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","w":1024}"""
        val payload = MediaMessagePayload.parse(fixture.toByteArray())!!
        assertEquals("image", payload.t)
        assertEquals(1024, payload.w)
        assertNull(payload.to) // never read back: a re-encoded payload carries no old recipient
        assertTrue(accepts(fixture, "media", bob))
        assertFalse(accepts(fixture, "media", alice))

        val sealed = MediaMessagePayload(t = "voice", mime = "audio/mp4", w = 0, h = 0, k = "a2V5", d = 1200, to = bob).encoded()
        assertEquals(SealedRecipient.Named.User(bob), SealedRecipient.named(sealed, "media"))
        assertEquals("voice", MediaMessagePayload.parse(sealed)!!.t)

        assertTrue(accepts("""{"t":"voice","mime":"audio/mp4","w":0,"h":0,"k":"a2V5","d":1200}""", "media", alice))
        assertTrue(accepts("not json", "media", alice))
    }

    @Test
    fun annotationsNameTheirRecipientAndStillParse() {
        val voice = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        val fixture = """{"c":"Hello there","r":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","t":"transcript","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}"""
        val shared = MessageAnnotation.parseTranscript(fixture)!!
        assertEquals(voice, shared.messageId)
        assertEquals("Hello there", shared.text)
        assertTrue(accepts(fixture, MessageAnnotation.CONTENT_TYPE, bob))
        assertFalse(accepts(fixture, MessageAnnotation.CONTENT_TYPE, me))

        val sealed = MessageAnnotation.transcript("Hello there", voice, to = bob).encoded()
        assertEquals(
            """{"t":"transcript","r":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","c":"Hello there","to":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}""",
            String(sealed),
        )
        assertEquals("Hello there", MessageAnnotation.parseTranscript(sealed)!!.text)
        // Without a recipient the bytes are what every build sealed.
        assertEquals(
            """{"t":"transcript","r":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","c":"Hello there"}""",
            String(MessageAnnotation.transcript("Hello there", voice).encoded()),
        )
    }
}
