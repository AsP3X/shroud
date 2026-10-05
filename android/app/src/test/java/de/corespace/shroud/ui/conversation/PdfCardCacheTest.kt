package de.corespace.shroud.ui.conversation

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** The PDF cards' renders (docs/file-sharing.md §10.1): memory only, bounded by bytes, following purges, locks and re-keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PdfCardCacheTest {
    @After
    fun tearDown() = PdfCardCache.clear()

    /** A ~1.2 MB card: 780 × 390 ARGB. */
    private fun card() = Bitmap.createBitmap(780, 390, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Test
    fun itHoldsAbout24MbOfPixelsLeastRecentlyUsedOut() {
        val ids = List(30) { UUID.randomUUID() }
        ids.forEach { PdfCardCache.store(it, 780, card(), pageCount = 3) }
        assertTrue(PdfCardCache.totalBytes() <= PdfCardCache.MAX_BYTES)
        assertEquals(20, PdfCardCache.count())
        assertNull(PdfCardCache.image(ids.first(), 780))
        assertNotNull(PdfCardCache.image(ids.last(), 780))
        // Another width is another render; the page count outlives an evicted render.
        assertNull(PdfCardCache.image(ids.last(), 600))
        assertEquals(3, PdfCardCache.pageCount(ids.first()))
    }

    @Test
    fun purgesLocksAndRekeysReachIt() {
        val sent = UUID.randomUUID()
        val server = UUID.randomUUID()
        val broken = UUID.randomUUID()
        PdfCardCache.store(sent, 780, card(), pageCount = 12)
        PdfCardCache.markFailed(broken)
        PdfCardCache.rekey(sent, server)
        assertNull(PdfCardCache.image(sent, 780))
        assertNotNull(PdfCardCache.image(server, 780))
        assertEquals(12, PdfCardCache.pageCount(server))
        assertTrue(PdfCardCache.hasFailed(broken))
        PdfCardCache.remove(listOf(server))
        assertNull(PdfCardCache.image(server, 780))
        assertNull(PdfCardCache.pageCount(server))
        PdfCardCache.clear()
        assertFalse(PdfCardCache.hasFailed(broken))
    }
}
