package de.corespace.shroud.ui.media.edit

import androidx.compose.ui.geometry.Rect
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.ui.media.edit.CropMath.AspectPreset
import de.corespace.shroud.ui.media.edit.CropMath.Frame
import de.corespace.shroud.ui.media.edit.CropMath.Handle
import de.corespace.shroud.ui.media.edit.CropMath.NormRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop editor's geometry (conversation-compose-media §11.3–§11.4, §21.2 `CropMathTest`;
 * `MediaCropEditor.swift:150-192, 285-457`). iOS has no unit test for these; the vectors are the
 * spec's (square on 4:3, 16:9 on 3:4) and values worked from the Swift code by hand.
 */
class CropMathTest {
    private fun assertRect(expected: NormRect, actual: NormRect?, delta: Double = 1e-9) {
        requireNotNull(actual) { "expected $expected, got null" }
        assertEquals("x of $actual", expected.x, actual.x, delta)
        assertEquals("y of $actual", expected.y, actual.y, delta)
        assertEquals("width of $actual", expected.width, actual.width, delta)
        assertEquals("height of $actual", expected.height, actual.height, delta)
    }

    // ---- Presets (MCE:49-76, 443-457) ----

    @Test
    fun squarePresetOnAFourByThreePhotoIsTheCentredFullHeightSquare() {
        assertRect(NormRect(0.125, 0.0, 0.75, 1.0), CropMath.presetCrop(AspectPreset.Square, 4.0 / 3.0))
    }

    @Test
    fun sixteenByNinePresetOnAThreeByFourPhotoIsTheCentredFullWidthBand() {
        assertRect(NormRect(0.0, 0.2890625, 1.0, 0.421875), CropMath.presetCrop(AspectPreset.Landscape16x9, 3.0 / 4.0))
    }

    @Test
    fun everyPresetOnASquarePhoto() {
        assertNull(CropMath.presetCrop(AspectPreset.Free, 1.0))
        assertRect(CropMath.UNIT, CropMath.presetCrop(AspectPreset.Square, 1.0))
        assertRect(NormRect(0.125, 0.0, 0.75, 1.0), CropMath.presetCrop(AspectPreset.Portrait3x4, 1.0))
        assertRect(NormRect(0.0, 0.125, 1.0, 0.75), CropMath.presetCrop(AspectPreset.Landscape4x3, 1.0))
        assertRect(NormRect(0.21875, 0.0, 0.5625, 1.0), CropMath.presetCrop(AspectPreset.Portrait9x16, 1.0))
        assertRect(NormRect(0.0, 0.21875, 1.0, 0.5625), CropMath.presetCrop(AspectPreset.Landscape16x9, 1.0))
    }

    @Test
    fun anEmptyPhotoAspectIsGuardedAt0point0001() {
        // `max(0.0001, imageAspect)`: n = 10 000, so the square is the full width and 1/10 000 tall.
        assertRect(NormRect(0.0, 0.49995, 1.0, 0.0001), CropMath.presetCrop(AspectPreset.Square, 0.0), delta = 1e-12)
    }

    @Test
    fun presetLabelsAndValuesMatchIos() {
        assertEquals(listOf("Free", "Square", "3:4", "4:3", "9:16", "16:9"), AspectPreset.entries.map { it.label })
        assertEquals(listOf(null, 1.0, 0.75, 4.0 / 3.0, 0.5625, 16.0 / 9.0), AspectPreset.entries.map { it.value })
    }

    // ---- rect(minX:minY:maxX:maxY:) (MCE:375-381) ----

    @Test
    fun rectKeepsTheTwelvePercentMinimum() {
        assertRect(NormRect(0.43, 0.43, 0.12, 0.12), CropMath.rect(0.5, 0.5, 0.55, 0.55))
        // The origin gives way: min(0.2, 0.25 − 0.12) = 0.13.
        assertRect(NormRect(0.13, 0.23, 0.12, 0.12), CropMath.rect(0.2, 0.3, 0.25, 0.35))
    }

    @Test
    fun rectStaysOnThePhoto() {
        assertRect(CropMath.UNIT, CropMath.rect(-0.2, -0.1, 1.3, 1.5))
        assertRect(NormRect(0.1, 0.2, 0.9, 0.8), CropMath.rect(0.1, 0.2, 4.0, 1.0))
    }

    @Test
    fun aTrailingEdgeDraggedPastTheLeftSideStopsAtTheEdgeWhereIosWentNegative() {
        // iOS: x0 = min(max(0, 0), −0.5 − 0.12) = −0.62, a window off the photo.
        assertRect(NormRect(0.0, 0.0, 0.12, 1.0), CropMath.rect(0.0, 0.0, -0.5, 1.0))
        assertRect(NormRect(0.0, 0.0, 1.0, 0.12), CropMath.rect(0.0, 0.0, 1.0, -2.0))
    }

    // ---- handle(near:in:) (MCE:307-329) ----

    private val window = Frame(100f, 100f, 200f, 200f)

    private fun handleAt(x: Float, y: Float) = CropMath.handle(x, y, window, slop = 34f, moveOutset = 12f)

    @Test
    fun cornersAndEdgesWinWithin34() {
        assertEquals(Handle.TopLeft, handleAt(102f, 98f))
        assertEquals(Handle.TopRight, handleAt(300f, 100f))
        assertEquals(Handle.BottomLeft, handleAt(100f, 333f))
        assertEquals(Handle.BottomRight, handleAt(300f, 300f))
        assertEquals(Handle.Top, handleAt(200f, 100f))
        assertEquals(Handle.Bottom, handleAt(200f, 320f))
        assertEquals(Handle.Leading, handleAt(80f, 200f))
        assertEquals(Handle.Trailing, handleAt(330f, 210f))
    }

    @Test
    fun theSlopIsInclusiveAt34() {
        assertEquals(Handle.Leading, handleAt(66f, 200f))
        // 34.01 from the leading midpoint and outside the grown window: nothing.
        assertNull(handleAt(65.99f, 200f))
    }

    @Test
    fun insideTheWindowOrTwelveOutsideItMoves() {
        assertEquals(Handle.Move, handleAt(200f, 200f))
        // 51 from the nearest handles, but inside the window grown by 12.
        assertEquals(Handle.Move, handleAt(89f, 150f))
        assertEquals(Handle.Move, handleAt(88f, 150f))
        assertNull(handleAt(87f, 150f))
        assertNull(handleAt(200f, 350f))
    }

    @Test
    fun aTieGoesToTheEarlierCandidateLikeSwiftMin() {
        // 25 from both the top-left corner and the top edge's midpoint.
        assertEquals(Handle.TopLeft, CropMath.handle(25f, 0f, Frame(0f, 0f, 100f, 100f), 34f, 12f))
    }

    // ---- constrained(_:to:anchor:) (MCE:383-441): the corner not held stays put ----

    private val start = NormRect(0.1, 0.1, 0.6, 0.6)
    private val dragged = NormRect(0.2, 0.3, 0.5, 0.4)

    @Test
    fun cornersFollowTheAxisThatMovedFurtherAndPinTheOppositeCorner() {
        // |0.4 − 0.6|·1 > |0.5 − 0.6|: height drives, so a 0.4 square.
        val topLeft = CropMath.constrained(dragged, 1.0, Handle.TopLeft, start, 1.0)
        assertRect(NormRect(0.3, 0.3, 0.4, 0.4), topLeft)
        assertEquals(dragged.maxX, topLeft.maxX, 1e-12)
        assertEquals(dragged.maxY, topLeft.maxY, 1e-12)

        val topRight = CropMath.constrained(dragged, 1.0, Handle.TopRight, start, 1.0)
        assertRect(NormRect(0.2, 0.3, 0.4, 0.4), topRight)
        assertEquals(dragged.minX, topRight.minX, 1e-12)
        assertEquals(dragged.maxY, topRight.maxY, 1e-12)

        val bottomLeft = CropMath.constrained(dragged, 1.0, Handle.BottomLeft, start, 1.0)
        assertRect(NormRect(0.3, 0.3, 0.4, 0.4), bottomLeft)
        assertEquals(dragged.maxX, bottomLeft.maxX, 1e-12)
        assertEquals(dragged.minY, bottomLeft.minY, 1e-12)

        val bottomRight = CropMath.constrained(dragged, 1.0, Handle.BottomRight, start, 1.0)
        assertRect(NormRect(0.2, 0.3, 0.4, 0.4), bottomRight)
    }

    @Test
    fun aCornerDrivenByWidthWhenTheFingerMovedSideways() {
        // |0.55 − 0.6| < |0.3 − 0.6|: width drives, 0.3 square, top-left corner fixed.
        val sideways = NormRect(0.1, 0.1, 0.3, 0.55)
        assertRect(NormRect(0.1, 0.1, 0.3, 0.3), CropMath.constrained(sideways, 1.0, Handle.BottomRight, start, 1.0))
    }

    @Test
    fun edgesDriveTheirOwnAxisAndStayCentredOnTheOther() {
        assertRect(NormRect(0.25, 0.3, 0.4, 0.4), CropMath.constrained(dragged, 1.0, Handle.Top, start, 1.0))
        assertRect(NormRect(0.25, 0.3, 0.4, 0.4), CropMath.constrained(dragged, 1.0, Handle.Bottom, start, 1.0))
        assertRect(NormRect(0.2, 0.25, 0.5, 0.5), CropMath.constrained(dragged, 1.0, Handle.Leading, start, 1.0))
        assertRect(NormRect(0.2, 0.25, 0.5, 0.5), CropMath.constrained(dragged, 1.0, Handle.Trailing, start, 1.0))
    }

    @Test
    fun theRatioIsOnScreenSoThePhotoAspectIsDividedOut() {
        // A square window on a 2:1 photo is half as wide (in photo fractions) as it is tall.
        val square = CropMath.constrained(NormRect(0.0, 0.0, 0.5, 0.5), 1.0, Handle.Bottom, NormRect(0.0, 0.0, 0.5, 0.3), 2.0)
        assertEquals(0.25, square.width, 1e-12)
        assertEquals(0.5, square.height, 1e-12)
    }

    @Test
    fun theReshapedWindowNeverOutgrowsThePhoto() {
        val huge = CropMath.constrained(NormRect(0.0, 0.0, 1.0, 1.0), 16.0 / 9.0, Handle.Leading, CropMath.UNIT, 1.0)
        assertTrue(huge.width <= 1.0 && huge.height <= 1.0)
        assertEquals(1.0, huge.width, 1e-12)
        assertEquals(0.5625, huge.height, 1e-12)
        val tall = CropMath.constrained(NormRect(0.0, 0.0, 1.0, 1.0), 9.0 / 16.0, Handle.Top, CropMath.UNIT, 1.0)
        assertEquals(1.0, tall.height, 1e-12)
        assertEquals(0.5625, tall.width, 1e-12)
        assertTrue(tall.minX >= 0.0 && tall.maxX <= 1.0 + 1e-12)
    }

    // ---- apply(translation:handle:imageFrame:) (MCE:331-373) ----

    private val imageFrame = Frame(20f, 80f, 200f, 100f)

    @Test
    fun moveSlidesInsideThePhotoAndIgnoresTheRatio() {
        val moved = CropMath.drag(NormRect(0.1, 0.1, 0.5, 0.5), Handle.Move, 400f, -50f, imageFrame, ratio = 1.0, imageAspect = 2.0)
        assertRect(NormRect(0.5, 0.0, 0.5, 0.5), moved)
        val nudged = CropMath.drag(NormRect(0.1, 0.1, 0.5, 0.5), Handle.Move, 20f, 10f, imageFrame, ratio = null, imageAspect = 2.0)
        assertRect(NormRect(0.2, 0.2, 0.5, 0.5), nudged)
    }

    @Test
    fun aCornerMovesOnlyItsOwnSides() {
        // dx 20 px of 200 = 0.1, dy 10 px of 100 = 0.1.
        val next = CropMath.drag(CropMath.UNIT, Handle.TopLeft, 20f, 10f, imageFrame, ratio = null, imageAspect = 2.0)
        assertRect(NormRect(0.1, 0.1, 0.9, 0.9), next)
        val edge = CropMath.drag(CropMath.UNIT, Handle.Trailing, -40f, 30f, imageFrame, ratio = null, imageAspect = 2.0)
        assertRect(NormRect(0.0, 0.0, 0.8, 1.0), edge)
    }

    @Test
    fun aLockedRatioReshapesTheDrag() {
        val next = CropMath.drag(CropMath.UNIT, Handle.BottomRight, -100f, -20f, imageFrame, ratio = 1.0, imageAspect = 2.0)
        // x 0…0.5 (width drives: |−0.2|·0.5 = 0.1 < 0.5), so a square on screen is 0.5 × 1.0 → clamped to 1 tall.
        assertRect(NormRect(0.0, 0.0, 0.5, 1.0), next)
    }

    @Test
    fun anEmptyImageFrameLeavesTheCropAlone() {
        val start = NormRect(0.1, 0.1, 0.5, 0.5)
        assertRect(start, CropMath.drag(start, Handle.Move, 10f, 10f, Frame(0f, 0f, 0f, 0f), null, 1.0))
    }

    // ---- Geometry and conversions ----

    @Test
    fun imageFrameAspectFitsAndCentres() {
        val frame = CropMath.imageFrame(4000f, 3000f, Frame(20f, 80f, 372f, 625f))
        assertEquals(20f, frame.x, 1e-3f)
        assertEquals(372f, frame.width, 1e-3f)
        assertEquals(279f, frame.height, 1e-3f)
        assertEquals(80f + 625f / 2f - 279f / 2f, frame.y, 1e-3f)
        val canvas = Frame(0f, 0f, 100f, 100f)
        assertEquals(canvas, CropMath.imageFrame(0f, 10f, canvas))
    }

    @Test
    fun cropFrameMapsFractionsOntoTheImage() {
        assertEquals(Frame(70f, 90f, 100f, 50f), CropMath.cropFrame(NormRect(0.25, 0.1, 0.5, 0.5), Frame(20f, 80f, 200f, 100f)))
    }

    @Test
    fun theUnitCropRoundTripsExactly() {
        assertEquals(MediaEdits.UNIT, CropMath.of(MediaEdits.UNIT).toRect())
        assertEquals(CropMath.UNIT, CropMath.of(Rect(0f, 0f, 1f, 1f)))
        val odd = Rect(0.125f, 0.2890625f, 0.875f, 0.7109375f)
        assertEquals(odd, CropMath.of(odd).toRect())
        // Any stored crop survives a crop session that changes nothing.
        val steps = (0..20).map { it / 20f * 0.97f + 0.003f }
        for (left in steps) for (right in steps) {
            if (right <= left) continue
            val rect = Rect(left, 1f - right, right, 1f - left)
            assertEquals(rect, CropMath.of(rect).toRect())
        }
    }

    @Test
    fun rotateTurnsAQuarterCounterClockwise() {
        assertEquals(3, CropMath.rotatedLeft(0))
        assertEquals(0, CropMath.rotatedLeft(1))
        assertEquals(2, CropMath.rotatedLeft(3))
        assertEquals(2, CropMath.rotatedLeft(-1))
        assertEquals(0, CropMath.rotatedLeft(5))
        assertFalse((0..7).any { CropMath.rotatedLeft(it) !in 0..3 })
    }
}
