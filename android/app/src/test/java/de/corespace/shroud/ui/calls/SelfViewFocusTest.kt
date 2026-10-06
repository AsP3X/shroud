package de.corespace.shroud.ui.calls

import de.corespace.shroud.core.calls.media.FramePoint
import de.corespace.shroud.core.calls.media.FrameSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Our own small picture crops round the faces (docs/calls.md, "Framing and Center Stage"), on the
 * side the user sees them: the front camera's picture is drawn mirrored.
 */
class SelfViewFocusTest {
    /** The self view's tile, in pixels (108 × 164 dp at 3×). */
    private val tile = FrameSize(324, 492)

    /** A landscape cut (their wide window): shown 2.7 times the tile's width. */
    private val wide = FrameSize(1920, 1080)

    @Test
    fun withoutFacesThePictureIsCroppedRoundItsMiddle() {
        val at = selfViewOffset(tile, wide, FramePoint.Middle, mirrored = false)
        val shownWidth = 1920 * (492.0 / 1080)
        assertEquals((324 - shownWidth) / 2, at.x, 1e-9)
        assertEquals(0.0, at.y, 1e-9)
        assertEquals("mirroring the middle changes nothing", at, selfViewOffset(tile, wide, FramePoint.Middle, mirrored = true))
    }

    @Test
    fun theFacesLandInTheTilesMiddleWhereTheUserSeesThem() {
        val scale = 492.0 / 1080
        val shownWidth = 1920 * scale
        val face = FramePoint(0.6, 0.4)
        // Not mirrored (back camera): the face's point, 0.6 of the way across, in the tile's middle.
        val back = selfViewOffset(tile, wide, face, mirrored = false)
        assertEquals(162 - 0.6 * shownWidth, back.x, 1e-9)
        assertEquals(162.0, back.x + 0.6 * shownWidth, 1e-9)
        // Mirrored (front camera): the face is drawn 0.4 of the way across, and that is centred.
        val front = selfViewOffset(tile, wide, face, mirrored = true)
        assertEquals(162.0, front.x + 0.4 * shownWidth, 1e-9)
        assertEquals("the faces' height is the same either way", back.y, front.y, 1e-9)
    }

    @Test
    fun aFaceAtTheEdgeStopsAtThePicturesEdge() {
        val shownWidth = 1920 * (492.0 / 1080)
        assertEquals(0.0, selfViewOffset(tile, wide, FramePoint(0.0, 0.5), mirrored = false).x, 1e-9)
        assertEquals(324 - shownWidth, selfViewOffset(tile, wide, FramePoint(0.0, 0.5), mirrored = true).x, 1e-9)
    }
}
