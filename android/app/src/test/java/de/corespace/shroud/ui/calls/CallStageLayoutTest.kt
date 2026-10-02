package de.corespace.shroud.ui.calls

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The call screen's face stays in the middle of the stage. On a voice call, and while only our
 * camera is on, the name block hangs under it. Their picture puts it in the top-leading corner.
 * On the way it rises and slides together, one straight glide.
 *
 * Port of `ios/shroudTests/CallStageLayoutTests.swift` (all 15 tests, the same vectors; calls §12).
 */
class CallStageLayoutTest {
    /** iPhone 17 Pro in portrait: the safe area above the controls. */
    private val stage = Rect(0f, 0f, 402f, 616f)
    private val face = Size(104f, 104f)

    /** Name, clock and speaking meter. */
    private val block = Size(150f, 93f)

    private fun frames(progress: Float, stage: Rect = this.stage, block: Size = this.block): CallStageGeometry.Frames =
        CallStageGeometry.frames(stage, face, block, progress)

    @Test
    fun theNameMovesOnlyForTheirCamera() {
        assertFalse(CallScreenMetrics.nameBelongsInCorner(remotePicture = false))
        assertTrue(CallScreenMetrics.nameBelongsInCorner(remotePicture = true))
    }

    @Test
    fun theFaceSitsInTheMiddleAndTheBlockHangsUnderIt() {
        val frames = frames(0f)
        assertEquals(stage.center.x, frames.face.center.x, 0f)
        assertEquals(stage.center.y, frames.face.center.y, 0f)
        assertEquals(stage.center.x, frames.block.center.x, 0f)
        assertEquals(CallStageGeometry.GAP, frames.block.top - frames.face.bottom, 0f)
    }

    @Test
    fun dockedTheBlockSitsInTheCornerAndTheFaceStaysInTheMiddle() {
        val frames = frames(1f)
        assertEquals(Offset(20f, 12f), frames.block.topLeft)
        assertEquals(stage.center.x, frames.face.center.x, 0f)
        assertEquals(stage.center.y, frames.face.center.y, 0f)
    }

    /** The "Not verified" badge takes the top of the corner: the docked block sits under it, and the block under the face does not move for it. */
    @Test
    fun theSafetyBadgeDropsOnlyTheDockedBlock() {
        val drop = CallScreenMetrics.SAFETY_BADGE_RESERVE
        val docked = CallStageGeometry.frames(stage, face, block, progress = 1f, cornerDrop = drop)
        assertEquals(Offset(20f, 12f + drop), docked.block.topLeft)
        assertEquals(frames(1f).face, docked.face)
        val under = CallStageGeometry.frames(stage, face, block, progress = 0f, cornerDrop = drop)
        assertEquals(frames(0f).block, under.block)
        assertEquals(CallScreenMetrics.SHARE_CONTROL + 10f, drop, 0f)
    }

    @Test
    fun onlyTheBlockMoves() {
        val face = frames(0f).face
        for (progress in listOf(0.1f, 0.5f, 0.9f, 1f)) {
            assertEquals(face, frames(progress).face)
        }
        // Nor does the face follow the block's height (the meter coming and going).
        assertEquals(face, frames(0f, block = Size(150f, 55f)).face)
    }

    @Test
    fun nothingChangesSizeOnTheWay() {
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val frames = frames(progress)
            assertEquals(face, frames.face.size)
            assertEquals(block, frames.block.size)
        }
    }

    @Test
    fun theBlockRisesAndSlidesTogether() {
        val start = frames(0f).block.topLeft
        val end = frames(1f).block.topLeft
        for (progress in listOf(0.2f, 0.4f, 0.5f, 0.6f, 0.8f)) {
            val at = frames(progress).block.topLeft
            val across = (at.x - start.x) / (end.x - start.x)
            val up = (at.y - start.y) / (end.y - start.y)
            assertTrue("across $across at $progress", abs(across - progress) < 0.001f)
            assertTrue("up $up at $progress", abs(up - progress) < 0.001f)
        }
    }

    @Test
    fun aShortStageLiftsTheFaceJustEnoughForTheBlockToFitUnderIt() {
        // iPhone 17 Pro in landscape: 219 pt above the controls.
        val short = Rect(0f, 0f, 750f, 219f)
        val small = Size(150f, 55f)
        val frames = frames(0f, stage = short, block = small)
        assertEquals(short.bottom, frames.block.bottom, 0f)
        assertTrue(frames.face.top < short.center.y - face.height / 2)
        assertTrue(frames.face.top >= short.top)
    }

    @Test
    fun theBlockHasOneWidthInBothPlacesAndStopsShortOfOurPicture() {
        val width = CallStageGeometry.blockWidth(stage.width)
        val picture = CallScreenMetrics.selfView
        assertEquals(CallScreenMetrics.INSET_TOP, CallStageGeometry.CORNER_Y, 0f)
        assertEquals(stage.width, CallStageGeometry.CORNER_X + width + 12f + picture.width + CallScreenMetrics.INSET_TRAILING, 0f)
        // Room for a name of about fourteen characters at 26 sp on this phone.
        assertEquals(246f, width, 0f)
        assertTrue(width <= stage.width - CallStageGeometry.MARGIN * 2)
    }

    @Test
    fun aWideStageCapsTheBlock() {
        // Landscape or a tablet: the block never runs across the screen.
        assertEquals(CallStageGeometry.CORNER_MAX_WIDTH, CallStageGeometry.blockWidth(750f), 0f)
        assertEquals(0f, CallStageGeometry.blockWidth(100f), 0f)
    }

    @Test
    fun aShortStageMovesTheFaceBesideAWideDockedBlock() {
        // Landscape: 750 × 219 above the controls, and a long name.
        val short = Rect(0f, 0f, 750f, 219f)
        val wide = Size(320f, 130f)
        val frames = frames(1f, stage = short, block = wide)
        assertFalse(frames.face.overlaps(frames.block))
        assertEquals(frames.block.right + CallStageGeometry.GAP, frames.face.left, 0f)
        assertEquals(CallStageGeometry.roundHalfAway(short.center.y), frames.face.center.y, 0f)
    }

    @Test
    fun withNoRoomBesideItTheFaceGoesBelowTheDockedBlock() {
        val narrow = Rect(0f, 0f, 402f, 219f)
        // Too wide to leave the face room beside it.
        val tall = Size(300f, 150f)
        val frames = frames(1f, stage = narrow, block = tall)
        assertFalse(frames.face.overlaps(frames.block))
        assertEquals(frames.block.bottom + CallStageGeometry.GAP, frames.face.top, 0f)
    }

    @Test
    fun aPairTallerThanTheStageStartsAtItsTop() {
        val short = Rect(0f, 40f, 750f, 220f)
        assertEquals(short.top, frames(0f, stage = short).face.top, 0f)
    }

    @Test
    fun aSpringPastTheEndHoldsTheCorner() {
        val start = frames(0f)
        val end = frames(1f)
        val past = frames(1.2f)
        val before = frames(-0.2f)
        assertEquals(end.block.topLeft, past.block.topLeft)
        assertEquals(end.face, past.face)
        assertEquals(start.block.topLeft, before.block.topLeft)
        assertEquals(start.face, before.face)
    }

    @Test
    fun bothEndsAreOnWholePoints() {
        val odd = Size(151.3f, 92.7f)
        val stage = Rect(0.5f, 0f, 401.5f, 615f)
        for (progress in listOf(0f, 1f)) {
            val frames = frames(progress, stage = stage, block = odd)
            for (origin in listOf(frames.face.topLeft, frames.block.topLeft)) {
                assertEquals(CallStageGeometry.roundHalfAway(origin.x), origin.x, 0f)
                assertEquals(CallStageGeometry.roundHalfAway(origin.y), origin.y, 0f)
            }
        }
    }
}
