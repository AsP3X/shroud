package de.corespace.shroud.core.media.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sender's `th` for a PDF (docs/file-sharing.md §10.1): the 2:1 crop and the shrink ladder. */
class PdfEnvelopeThumbTest {
    @Test
    fun theCropIsTheTopHalfOfTheWidthOrTheWholeWidePage() {
        assertEquals(240, PdfEnvelopeThumb.cropHeight(480, PdfPageSize(595f, 842f)))
        assertEquals(240, PdfEnvelopeThumb.cropHeight(480, PdfPageSize(612f, 792f)))
        // Wider than 2:1: the whole page.
        assertEquals(160, PdfEnvelopeThumb.cropHeight(480, PdfPageSize(900f, 300f)))
    }

    @Test
    fun theLadderShrinksTheWidthBy08AndLowersTheQualityUntilItFits() {
        val tries = ArrayList<Pair<Int, Int>>()
        val result = PdfEnvelopeThumb.ladder(480) { width, quality ->
            tries += width to quality
            ByteArray(if (width <= 300) 5_000 else 9_000)
        }
        assertEquals(listOf(480 to 70, 384 to 60, 307 to 50, 245 to 40), tries)
        assertEquals(245, result!!.second)
        assertEquals(5_000, result.first.size)
    }

    @Test
    fun itGivesUpBelow160PxWideOrWhenAnEncodeFails() {
        val tries = ArrayList<Int>()
        assertNull(PdfEnvelopeThumb.ladder(480) { width, _ -> tries += width; ByteArray(7_000) })
        assertEquals(listOf(480, 384, 307, 245, 196), tries)
        assertNull(PdfEnvelopeThumb.ladder(480) { _, _ -> null })
        // 6 KB exactly fits.
        assertEquals(480, PdfEnvelopeThumb.ladder(480) { _, _ -> ByteArray(6 * 1024) }!!.second)
    }
}
