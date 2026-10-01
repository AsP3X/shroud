package de.corespace.shroud.core.net.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * The transcript share-back's wire format (shared with the web client). Ports the wire part of
 * `ios/shroudTests/MessageAnnotationTests.swift:25-83` (5 cases; folding into a thread is
 * `ThreadMessageMerge`, W2-MSG-CORE); vectors verbatim.
 */
class MessageAnnotationTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    @Test
    fun transcriptRoundTrips() { // :27-34
        val id = UUID.randomUUID()
        val parsed = MessageAnnotation.parseTranscript(MessageAnnotation.transcript("See you at eight", id).encoded())!!
        assertEquals(id, parsed.messageId)
        assertEquals("See you at eight", parsed.text)
    }

    @Test
    fun referenceIsLowercased() { // :36-44
        val id = UUID.fromString("3F2B8C4E-6A1D-4E3B-9C7A-1B2C3D4E5F60")
        val json = String(MessageAnnotation.transcript("hi", id).encoded())
        assertEquals("""{"t":"transcript","r":"3f2b8c4e-6a1d-4e3b-9c7a-1b2c3d4e5f60","c":"hi"}""", json)
        assertFalse(json.contains("3F2B8C4E"))
    }

    @Test
    fun readsTheWebEncoding() { // :46-53
        val raw = """{"t":"transcript","r":"3f2b8c4e-6a1d-4e3b-9c7a-1b2c3d4e5f60","c":"  Bis gleich!  "}"""
        val parsed = MessageAnnotation.parseTranscript(raw)!!
        assertEquals(UUID.fromString("3F2B8C4E-6A1D-4E3B-9C7A-1B2C3D4E5F60"), parsed.messageId)
        assertEquals("Bis gleich!", parsed.text)
    }

    @Test
    fun rejectsAnythingElse() { // :55-62
        val id = UUID.randomUUID().toString()
        assertNull(MessageAnnotation.parseTranscript("just text"))
        assertNull(MessageAnnotation.parseTranscript("""{"t":"reaction","r":"$id","c":"👍"}"""))
        assertNull(MessageAnnotation.parseTranscript("""{"t":"transcript","r":"not-a-uuid","c":"x"}"""))
        assertNull(MessageAnnotation.parseTranscript("""{"t":"transcript","r":"$id","c":"   "}"""))
    }

    @Test
    fun strictLikeJsonDecoder() {
        val id = UUID.randomUUID().toString()
        // All three keys, all strings (JSONDecoder of a non-optional String).
        assertNull(MessageAnnotation.parseTranscript("""{"t":"transcript","r":"$id"}"""))
        assertNull(MessageAnnotation.parseTranscript("""{"t":"transcript","c":"x"}"""))
        assertNull(MessageAnnotation.parseTranscript("""{"t":"transcript","r":"$id","c":null}"""))
        assertNull(MessageAnnotation.parseTranscript("""{"t":"transcript","r":"$id","c":5}"""))
        // UUID(uuidString:) takes no surrounding whitespace and no short forms.
        assertNull(MessageAnnotation.parseTranscript("""{"t":"transcript","r":" $id","c":"x"}"""))
        assertNull(MessageAnnotation.parseTranscript("""{"t":"transcript","r":"1-1-1-1-1","c":"x"}"""))
        // Extra keys are fine; upper-case ids too.
        val upper = id.uppercase()
        assertEquals("x", MessageAnnotation.parseTranscript("""{"t":"transcript","r":"$upper","c":"x","v":2}""")?.text)
        assertNull(MessageAnnotation.parseTranscript(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun capsAnOverlongTranscript() { // :64-71
        val long = "a".repeat(MessageAnnotation.MAX_TRANSCRIPT_BYTES + 500)
        val parsed = MessageAnnotation.parseTranscript(MessageAnnotation.transcript(long, UUID.randomUUID()).encoded())!!
        assertTrue(WireText.utf8Count(parsed.text) <= MessageAnnotation.MAX_TRANSCRIPT_BYTES)
        assertTrue(parsed.text.endsWith("…"))
        assertEquals("a".repeat(MessageAnnotation.MAX_TRANSCRIPT_BYTES - 3) + "…", parsed.text)
    }

    @Test
    fun capCountsBytesNotCharacters() { // :73-83
        val clamped = MessageAnnotation.clampTranscript("語".repeat(8000))
        assertTrue(WireText.utf8Count(clamped) <= MessageAnnotation.MAX_TRANSCRIPT_BYTES)
        assertTrue(clamped.endsWith("…"))
        assertTrue("never splits a character", clamped.dropLast(1).all { it == '語' })
        // (16384 − 3) / 3 = 5460 whole characters fit.
        assertEquals(5460, clamped.length - 1)
        assertEquals("short", MessageAnnotation.clampTranscript("  short  "))
    }

    @Test
    fun capNeverSplitsAnEmojiSequence() {
        val family = "👩‍👩‍👧‍👦" // 25 UTF-8 bytes, one grapheme
        val clamped = MessageAnnotation.clampTranscript(family.repeat(1000))
        assertTrue(clamped.removeSuffix("…").chunked(family.length).all { it == family })
        assertTrue(WireText.utf8Count(clamped) <= MessageAnnotation.MAX_TRANSCRIPT_BYTES)
    }

    @Test
    fun toStringNeverShowsTheText() {
        assertFalse(MessageAnnotation.transcript("private words", UUID.randomUUID()).toString().contains("private"))
    }
}
