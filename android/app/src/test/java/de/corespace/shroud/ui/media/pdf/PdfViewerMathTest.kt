package de.corespace.shroud.ui.media.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** The viewer's copy, page layout and zoom (docs/file-sharing.md §10.2). */
class PdfViewerMathTest {
    @Test
    fun theSubtitleSaysLoadingThenOnePageOrTheLivePage() {
        assertEquals("Loading…", PdfViewerCopy.subtitle(1, null))
        assertEquals("1 page", PdfViewerCopy.subtitle(1, 1))
        assertEquals("Page 3 of 12", PdfViewerCopy.subtitle(3, 12))
        assertEquals("Page 12 of 12", PdfViewerCopy.subtitle(40, 12))
        assertEquals("Page 2 of 5", PdfViewerCopy.pageLabel(2, 5))
        assertEquals("3 of 7", PdfViewerCopy.searchPosition(3, 7))
    }

    @Test
    fun pagesFitTheWidthWithA12DpMarginUpTo920() {
        assertEquals(388f, PdfViewerMetrics.pageWidth(412f))
        assertEquals(920f, PdfViewerMetrics.pageWidth(1280f))
        assertEquals(0f, PdfViewerMetrics.pageWidth(10f))
    }

    @Test
    fun thumbnailsAre128WideAtTheirAspectAndNoTallerThan182() {
        assertEquals(128f to 128f * 842f / 595f, PdfViewerMetrics.thumbnailSize(842f / 595f))
        assertEquals(128f to 64f, PdfViewerMetrics.thumbnailSize(0.5f))
        // A very tall page narrows instead.
        assertEquals(91f to 182f, PdfViewerMetrics.thumbnailSize(2f))
        assertEquals("Pages", PdfViewerCopy.PAGES)
    }

    @Test
    fun theDrawerIsAtMost280OrFourFifthsOfTheWindow() {
        assertEquals(280f, PdfViewerMetrics.drawerWidth(412f))
        assertEquals(256f, PdfViewerMetrics.drawerWidth(320f))
        assertEquals(840f, PdfViewerMetrics.SIDEBAR_MIN_WINDOW)
    }

    @Test
    fun aDoubleTapTogglesOneAndTwoAndAHalf() {
        assertEquals(2.5f, PdfZoomMath.doubleTapTarget(1f))
        assertEquals(1f, PdfZoomMath.doubleTapTarget(2.5f))
        assertEquals(1f, PdfZoomMath.doubleTapTarget(4f))
    }

    @Test
    fun zoomingKeepsThePointUnderTheFingers() {
        val viewport = Size(400f, 800f)
        val anchor = Offset(300f, 200f)
        val offset = PdfZoomMath.offsetAfterZoom(Offset.Zero, 1f, 2f, anchor, viewport)
        // Column point under the anchor before: anchor itself; after: centre + (p − centre) × 2 + offset.
        val centre = Offset(200f, 400f)
        assertEquals(anchor, centre + (anchor - centre) * 2f + offset)
        assertEquals(Offset(200f, 400f), PdfZoomMath.maxOffset(viewport, 2f))
        assertEquals(Offset(200f, -400f), PdfZoomMath.clamp(Offset(900f, -900f), viewport, 2f))
        assertEquals(Offset.Zero, PdfZoomMath.maxOffset(viewport, 1f))
    }

    @Test
    fun theVisibleBandIsTheMiddleOfTheColumnWhenZoomed() {
        assertEquals(0f..800f, PdfZoomMath.visibleBand(800f, 1f, 0f))
        assertEquals(200f..600f, PdfZoomMath.visibleBand(800f, 2f, 0f))
        assertEquals(0f..400f, PdfZoomMath.visibleBand(800f, 2f, 400f))
    }

    @Test
    fun aPanSpendsTheOffsetFirstThenScrollsThenTakesWhatScrollingCannot() {
        val scrolled = ArrayList<Float>()
        // At the top (offset 300 of 400), panning up 100 px spends the offset first.
        assertEquals(200f, PdfZoomMath.panVertically(-100f, 300f, 400f, 2f) { scrolled += it; it })
        assertEquals(emptyList<Float>(), scrolled)
        // Offset 0, the column scrolls by the pan ÷ zoom.
        assertEquals(0f, PdfZoomMath.panVertically(-100f, 0f, 400f, 2f) { scrolled += it; it })
        assertEquals(listOf(50f), scrolled)
        // At the very top the column cannot scroll back: the offset takes it, within its limit.
        assertEquals(400f, PdfZoomMath.panVertically(600f, 0f, 400f, 2f) { 0f })
    }

    @Test
    fun theCurrentPageIsTheOneFillingMostOfTheBand() {
        val items = listOf(Triple(3, -500f, 300f), Triple(4, 312f, 1100f))
        assertEquals(4, PdfZoomMath.mostVisible(items, 0f..800f, 0))
        assertEquals(3, PdfZoomMath.mostVisible(items, 0f..400f, 0))
        assertEquals(9, PdfZoomMath.mostVisible(emptyList(), 0f..800f, 9))
    }
}
