package de.corespace.shroud.core.media

import de.corespace.shroud.core.crypto.MediaCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `th` ladder of iOS `MediaCrypto.chatPreviewJPEG` (`ios/shroud/Services/Crypto/MediaCrypto.swift:151-171`;
 * web `envelopePreview`, `web/src/media/envelopePreview.ts:16-28`; media-voice-links §4.4) with a
 * scripted encoder: which edges and qualities are tried, in what order, and what comes back. The
 * real `ImageDecoder` + JPEG path runs in `EnvelopePreviewBitmapTest` (Robolectric) and on a
 * device (`MediaStoreDeviceTest`).
 */
class EnvelopePreviewTest {
    /** Records every try and answers with a JPEG of the size [sizes] gives for that try. */
    private class ScriptedEncoder(private vararg val sizes: Int?) : EnvelopePreview.JpegEncoder {
        val tries = ArrayList<Pair<Int, Double>>()

        override fun encode(edge: Int, quality: Double): ByteArray? {
            tries += edge to quality
            val size = sizes.getOrElse(tries.size - 1) { sizes.last() } ?: return null
            return ByteArray(size) { tries.size.toByte() }
        }
    }

    @Test
    fun budgetsMatchIos() {
        assertEquals(6 * 1024, MediaEnvelopeBudget.MAX_THUMB)
        assertEquals(MediaCrypto.MAX_ENVELOPE_PREVIEW_BYTES, MediaEnvelopeBudget.MAX_THUMB)
        assertEquals(12 * 1024, MediaEnvelopeBudget.MAX_PAYLOAD)
        assertEquals(60 * 1024, MediaEnvelopeBudget.MAX_SEALED)
    }

    @Test
    fun theFirstTryThatFitsIsReturned() {
        val encoder = ScriptedEncoder(6144)
        val jpeg = EnvelopePreview.ladder(encoder)
        assertEquals(6144, jpeg!!.size)
        assertEquals(listOf(160 to 0.42), encoder.tries)
    }

    @Test
    fun theLadderShrinksEdgeAndQualityAsIos() {
        // 160 → 112 → max(80, 78.4) = 80 → 80 → 80; 0.42 → 0.34 → 0.26 → 0.18 → max(0.15, 0.10) = 0.15.
        val encoder = ScriptedEncoder(6145, 6145, 6145, 6145, 3000)
        val jpeg = EnvelopePreview.ladder(encoder)
        assertEquals(3000, jpeg!!.size)
        assertEquals(listOf(160, 112, 80, 80, 80), encoder.tries.map { it.first })
        val qualities = encoder.tries.map { it.second }
        listOf(0.42, 0.34, 0.26, 0.18, 0.15).forEachIndexed { i, expected -> assertEquals(expected, qualities[i], 1e-9) }
    }

    @Test
    fun afterFiveMissesTheLastResortIsReturnedEvenWhenTooBig() {
        // iOS returns 80 px at 0.15 whatever its size (MediaCrypto.swift:169-170); the web would return null.
        val encoder = ScriptedEncoder(9000, 9000, 9000, 9000, 9000, 7000)
        val jpeg = EnvelopePreview.ladder(encoder)
        assertEquals(7000, jpeg!!.size)
        assertEquals(6, encoder.tries.size)
        assertEquals(80 to 0.15, encoder.tries.last())
        assertArrayEquals(ByteArray(7000) { 6 }, jpeg)
    }

    @Test
    fun anUndecodablePictureGivesNoPreview() {
        assertNull(EnvelopePreview.ladder(ScriptedEncoder(null)))
        // A failure later in the ladder ends it too (iOS `guard … else { return nil }`).
        val encoder = ScriptedEncoder(9000, null)
        assertNull(EnvelopePreview.ladder(encoder))
        assertEquals(2, encoder.tries.size)
    }

    @Test
    fun qualityIsClampedToTheIosRange() {
        val encoder = ScriptedEncoder(9000, 9000, 9000, 9000, 9000, 9000)
        EnvelopePreview.ladder(encoder)
        encoder.tries.forEach { (_, quality) -> assertTrue("quality $quality", quality in 0.15..0.85) }
    }

    @Test
    fun edgesAreWholePixelsAsImageIoTakesThem() {
        assertEquals(160, EnvelopePreview.edgePixels(160.0))
        assertEquals(112, EnvelopePreview.edgePixels(160.0 * 0.7))
        assertEquals(80, EnvelopePreview.edgePixels(80.0))
        assertEquals(1, EnvelopePreview.edgePixels(0.2))
    }

    @Test
    fun fitEdgeKeepsTheAspectAndNeverUpscales() {
        assertEquals(160 to 120, EnvelopePreview.fitEdge(4032, 3024, 160))
        assertEquals(90 to 160, EnvelopePreview.fitEdge(1080, 1920, 160))
        assertEquals(100 to 50, EnvelopePreview.fitEdge(100, 50, 160))
        assertEquals(160 to 1, EnvelopePreview.fitEdge(10_000, 10, 160))
        assertEquals(1 to 1, EnvelopePreview.fitEdge(0, 0, 160))
    }
}
