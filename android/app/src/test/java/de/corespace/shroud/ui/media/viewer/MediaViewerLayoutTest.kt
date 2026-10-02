package de.corespace.shroud.ui.media.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.media.ViewerItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * `MediaViewerLayout` (`ios/shroud/ShroudUI/Components/ZoomableImageView.swift:5-38`) with the
 * vectors of conversation-compose-media §21.2, plus the zoom model (`ZoomImageScrollView`,
 * `:207-309`) and the dismiss drag (`MediaImageViewerOverlay.swift:539-583`).
 */
class MediaViewerLayoutTest {
    private val pixel = Size(412f, 915f)
    private val iphone = Size(390f, 844f)

    @Test
    fun screenShapedPhotoFillsExactlyAndIsNotFullBleed() {
        val aspect = 412f / 915f
        assertEquals(1f, MediaViewerLayout.fillScale(aspect, pixel), 1e-5f)
        assertEquals(1f, MediaViewerLayout.presentationScale(aspect, pixel), 1e-5f)
        assertFalse(MediaViewerLayout.opensFullBleed(aspect, pixel))
    }

    @Test
    fun nineBySixteenOpensEdgeToEdge() {
        assertEquals(1.24924f, MediaViewerLayout.presentationScale(9f / 16f, pixel), 1e-5f)
        assertTrue(MediaViewerLayout.opensFullBleed(9f / 16f, pixel))
        assertEquals(1.21731f, MediaViewerLayout.presentationScale(9f / 16f, iphone), 1e-5f)
        assertTrue(MediaViewerLayout.opensFullBleed(9f / 16f, iphone))
    }

    @Test
    fun otherShapesOpenWhole() {
        assertEquals(1f, MediaViewerLayout.presentationScale(3f / 4f, pixel), 0f) // fill ≈ 1.665 > 1.25
        assertEquals(1f, MediaViewerLayout.presentationScale(2f / 3f, pixel), 0f)
        assertEquals(1f, MediaViewerLayout.presentationScale(16f / 9f, pixel), 0f) // landscape always fits
        assertEquals(1f, MediaViewerLayout.presentationScale(1f, pixel), 0f) // square always fits
        assertEquals(1f, MediaViewerLayout.presentationScale(0f, pixel), 0f)
        assertEquals(1f, MediaViewerLayout.presentationScale(-1f, pixel), 0f)
        assertEquals(1f, MediaViewerLayout.presentationScale(Float.NaN, pixel), 0f)
        assertFalse(MediaViewerLayout.opensFullBleed(3f / 4f, pixel))
    }

    @Test
    fun fittedSizeAndFillScale() {
        val fitted = MediaViewerLayout.fittedSize(9f / 16f, pixel)
        assertEquals(412f, fitted.width, 1e-3f)
        assertEquals(412f * 16f / 9f, fitted.height, 1e-3f)
        val landscape = MediaViewerLayout.fittedSize(16f / 9f, pixel)
        assertEquals(412f, landscape.width, 1e-3f)
        assertEquals(231.75f, landscape.height, 1e-3f)
        // Bad input gives the container back, and a fill scale of 1.
        assertEquals(pixel, MediaViewerLayout.fittedSize(0f, pixel))
        assertEquals(Size.Zero, MediaViewerLayout.fittedSize(1f, Size.Zero))
        assertEquals(1f, MediaViewerLayout.fillScale(1f, Size.Zero), 0f)
        assertEquals(915f / 231.75f, MediaViewerLayout.fillScale(16f / 9f, pixel), 1e-4f)
    }

    @Test
    fun chromeScrimOnlyWhereThePhotoReachesTheBar() {
        // Full bleed always reaches; a wide photo letterboxes clear of a 120 dp bar.
        assertTrue(MediaViewerLayout.reachesChrome(9f / 16f, pixel, 120f))
        assertFalse(MediaViewerLayout.reachesChrome(16f / 9f, pixel, 120f))
        // A 3:4 photo leaves (915 − 549.33) / 2 ≈ 182.8 dp of margin.
        assertFalse(MediaViewerLayout.reachesChrome(3f / 4f, pixel, 182f))
        assertTrue(MediaViewerLayout.reachesChrome(3f / 4f, pixel, 183f))
    }

    @Test
    fun barExtentsBeforeTheyAreMeasured() {
        assertEquals(24f + 62f, topBarExtent(0f, 24.dp), 0f)
        assertEquals(140f, topBarExtent(140f, 24.dp), 0f)
        assertEquals(8f + 78f, bottomBarExtent(0f, 0.dp), 0f)
        assertEquals(48f + 78f, bottomBarExtent(0f, 48.dp), 0f)
    }

    @Test
    fun zoomOpensAtThePresentationScaleAndReachesNativePixels() {
        val viewport = Size(1080f, 2400f)
        // A 9:16 photo 1440 × 2560 px.
        val fitted = ZoomMath.fittedSize(1440f, 2560f, viewport)
        assertEquals(Size(1080f, 1920f), fitted)
        val base = ZoomMath.baseScale(1440f, 2560f, viewport)
        assertEquals(1.25f, base, 1e-5f)
        // 3 × base beats the native 1440 / 1080.
        assertEquals(3.75f, ZoomMath.maxScale(base, 1440f, fitted.width), 1e-5f)
        // A 12 000 px wide photo is capped at 8.
        assertEquals(8f, ZoomMath.maxScale(1f, 12_000f, 1080f), 0f)
        assertFalse(ZoomMath.isZoomedIn(1.255f, base))
        assertTrue(ZoomMath.isZoomedIn(1.27f, base))
        assertEquals(Size.Zero, ZoomMath.fittedSize(0f, 10f, viewport))
    }

    @Test
    fun doubleTapZoomsToFillThenAStepAndPutsBack() {
        val viewport = Size(1080f, 2400f)
        val fitted = Size(1080f, 810f) // a 4:3 landscape photo
        // fill = 2400 / 810 ≈ 2.963; × 1.4 ≈ 4.148 beats base × 2.
        assertEquals(2400f / 810f * 1.4f, ZoomMath.doubleTapScale(fitted, viewport, 1f, 8f), 1e-4f)
        assertEquals(3f, ZoomMath.doubleTapScale(fitted, viewport, 1f, 3f), 0f)
        assertEquals(2f, ZoomMath.doubleTapScale(Size.Zero, viewport, 1f, 8f), 0f)
        assertTrue(ZoomMath.doubleTapResets(2f, 1f))
        assertTrue(ZoomMath.doubleTapResets(0.9f, 1f)) // pinched out below the opening scale
        assertFalse(ZoomMath.doubleTapResets(1f, 1f))
    }

    @Test
    fun panIsClampedToTheContentAndCentredWhenSmaller() {
        val viewport = Size(1000f, 2000f)
        val fitted = Size(1000f, 750f)
        assertOffset(Offset(0f, 0f), ZoomMath.maxOffset(fitted, viewport, 1f))
        assertOffset(Offset(500f, 0f), ZoomMath.maxOffset(fitted, viewport, 2f))
        assertOffset(Offset(1000f, 125f), ZoomMath.maxOffset(fitted, viewport, 3f))
        assertOffset(Offset(500f, 0f), ZoomMath.clampOffset(Offset(900f, 300f), fitted, viewport, 2f))
        assertOffset(Offset(-500f, 0f), ZoomMath.clampOffset(Offset(-900f, -300f), fitted, viewport, 2f))
    }

    @Test
    fun pinchKeepsThePointUnderTheFingersAndTapZoomCentresIt() {
        // Zooming 1 → 2 about a point 100 px right of centre moves the content 100 px left.
        assertOffset(Offset(-100f, 0f), ZoomMath.offsetAfterZoom(Offset.Zero, 1f, 2f, Offset(100f, 0f)))
        // The point under a tap 100 px right of centre ends in the middle at 3×.
        assertOffset(Offset(-300f, 0f), ZoomMath.offsetCentring(Offset.Zero, 1f, 3f, Offset(100f, 0f)))
        assertOffset(Offset(5f, 5f), ZoomMath.offsetAfterZoom(Offset(5f, 5f), 0f, 2f, Offset(1f, 1f)))
    }

    @Test
    fun rubberBandGivesWayPastTheLimits() {
        assertEquals(2f, ZoomMath.rubberBand(2f, 1f, 4f), 0f)
        assertEquals(4f + 2f * 0.35f, ZoomMath.rubberBand(6f, 1f, 4f), 1e-5f)
        assertEquals(1f - 0.5f * 0.35f, ZoomMath.rubberBand(0.5f, 1f, 4f), 1e-5f)
    }

    @Test
    fun dismissDragMatchesIos() {
        assertEquals(1f, ViewerDismiss.dimOpacity(0f), 0f)
        assertEquals(0.5f, ViewerDismiss.dimOpacity(210f), 1e-5f)
        assertEquals(0.25f, ViewerDismiss.dimOpacity(-1000f), 0f)
        assertEquals(1f - 0.12f * 0.5f, ViewerDismiss.dragScale(250f, reduceMotion = false), 1e-5f)
        assertEquals(0.88f, ViewerDismiss.dragScale(900f, reduceMotion = false), 1e-5f)
        assertEquals(1f, ViewerDismiss.dragScale(900f, reduceMotion = true), 0f)
        assertEquals(0.5f, ViewerDismiss.chromeOpacity(90f), 1e-5f)
        assertEquals(0f, ViewerDismiss.chromeOpacity(500f), 0f)
        assertFalse(ViewerDismiss.shouldDismiss(110f, 0f))
        assertTrue(ViewerDismiss.shouldDismiss(111f, 0f))
        assertTrue(ViewerDismiss.shouldDismiss(-111f, 0f))
        assertTrue(ViewerDismiss.shouldDismiss(20f, 901f))
        assertTrue(ViewerDismiss.shouldDismiss(20f, -901f))
        assertFalse(ViewerDismiss.shouldDismiss(20f, 900f))
        assertEquals(40f, ViewerDismiss.exitOffset(0f, 1f), 0f)
        assertEquals(-40f, ViewerDismiss.exitOffset(0f, -1f), 0f)
        assertEquals(160f, ViewerDismiss.exitOffset(100f, 1f), 1e-5f)
        assertEquals(55f, ViewerDismiss.backOffset(0.5f), 1e-5f)
        assertEquals(110f, ViewerDismiss.backOffset(2f), 0f)
    }

    /** Offsets compare by value; -0 and 0 are the same position. */
    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 1e-4f)
        assertEquals(expected.y, actual.y, 1e-4f)
    }

    @Test
    fun pagerPositionAndDecodeEdge() {
        val items = List(3) { ViewerItem(UUID.randomUUID(), "You", "02.10.26", null, 1f, true) }
        assertEquals("2 of 3", positionValue(items, items[1].id))
        assertEquals("", positionValue(items, UUID.randomUUID()))
        assertEquals(3600, decodeMaxEdge(2400f))
        assertEquals(2048, decodeMaxEdge(800f))
        assertEquals(4096, decodeMaxEdge(3200f))
    }
}
