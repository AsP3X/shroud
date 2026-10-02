package de.corespace.shroud.ui.media.video

import de.corespace.shroud.core.media.video.VideoTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trim strip's maths (`VideoTrimStrip`, `ios/shroud/ShroudUI/Components/VideoTrimStrip.swift:157-190`;
 * conversation-compose-media §21.2 `TrimMathTest`): a 10 s clip on a strip 232 wide (track 200,
 * handles 16).
 */
class TrimMathTest {
    private val duration = 10.0
    private val track = 200f
    private val full = VideoTrim(0.0, 10.0)

    @Test
    fun trackIsTheStripMinusBothHandles() {
        assertEquals(200f, TrimMath.trackWidth(232f), 0f)
        assertEquals(1f, TrimMath.trackWidth(20f), 0f) // never below 1
    }

    @Test
    fun xClampsToTheTrack() {
        assertEquals(0f, TrimMath.x(0.0, duration, track), 0f)
        assertEquals(100f, TrimMath.x(5.0, duration, track), 1e-4f)
        assertEquals(200f, TrimMath.x(12.0, duration, track), 0f)
        assertEquals(0f, TrimMath.x(-3.0, duration, track), 0f)
        assertEquals(0f, TrimMath.x(5.0, 0.0, track), 0f) // no length
        assertEquals(16f + 50f, TrimMath.startX(VideoTrim(2.5, 10.0), duration, track), 1e-4f)
        assertEquals(16f + 150f, TrimMath.endX(VideoTrim(0.0, 7.5), duration, track), 1e-4f)
    }

    @Test
    fun startMovesButKeepsASecond() {
        // Strip position 16 + 60 = 3 s.
        assertEquals(3.0, TrimMath.move(full, TrimHandle.Start, 76f, duration, track)!!.start, 1e-6)
        // Before the strip: clamped to 0.
        assertEquals(0.0, TrimMath.move(full, TrimHandle.Start, -40f, duration, track)!!.start, 0.0)
        // Past the end cut: stops one second before it.
        val pushed = TrimMath.move(VideoTrim(0.0, 6.0), TrimHandle.Start, 216f, duration, track)!!
        assertEquals(5.0, pushed.start, 1e-9)
        assertEquals(6.0, pushed.end, 0.0)
    }

    @Test
    fun endMovesButKeepsASecond() {
        assertEquals(7.0, TrimMath.move(full, TrimHandle.End, 156f, duration, track)!!.end, 1e-6)
        assertEquals(10.0, TrimMath.move(full, TrimHandle.End, 400f, duration, track)!!.end, 0.0)
        val pushed = TrimMath.move(VideoTrim(4.0, 10.0), TrimHandle.End, 0f, duration, track)!!
        assertEquals(5.0, pushed.end, 1e-9)
        assertEquals(4.0, pushed.start, 0.0)
    }

    @Test
    fun aClipShorterThanASecondUsesItsLengthAsTheMinimum() {
        val clip = VideoTrim(0.0, 0.6)
        assertEquals(0.6, TrimMath.minimum(0.6), 0.0)
        assertEquals(1.0, TrimMath.minimum(10.0), 0.0)
        val start = TrimMath.move(clip, TrimHandle.Start, 216f, 0.6, track)!!
        assertEquals(0.0, start.start, 1e-9)
        val end = TrimMath.move(clip, TrimHandle.End, 0f, 0.6, track)!!
        assertEquals(0.6, end.end, 1e-9)
    }

    @Test
    fun nothingMovesWithoutALengthOrATrack() {
        assertNull(TrimMath.move(full, TrimHandle.Start, 50f, 0.0, track))
        assertNull(TrimMath.move(full, TrimHandle.Start, 50f, duration, 0f))
        assertNull(TrimMath.nudge(full, TrimHandle.End, 1.0, 0.0))
    }

    @Test
    fun nudgeStepsATwentiethAtLeastHalfASecond() {
        assertEquals(0.5, TrimMath.nudgeStep(6.0), 0.0) // 0.3 → 0.5
        assertEquals(0.5, TrimMath.nudgeStep(10.0), 0.0)
        assertEquals(3.0, TrimMath.nudgeStep(60.0), 0.0)
        assertEquals(1.5, TrimMath.nudge(full, TrimHandle.Start, 1.5, duration)!!.start, 0.0)
        assertEquals(0.0, TrimMath.nudge(full, TrimHandle.Start, -1.0, duration)!!.start, 0.0)
        // The end cannot come within a second of the start, nor pass the clip's end.
        assertEquals(10.0, TrimMath.nudge(VideoTrim(9.5, 10.0), TrimHandle.End, -5.0, duration)!!.end, 0.0)
        assertEquals(6.0, TrimMath.nudge(VideoTrim(5.0, 9.0), TrimHandle.End, -5.0, duration)!!.end, 0.0)
        assertEquals(10.0, TrimMath.nudge(full, TrimHandle.End, 1.0, duration)!!.end, 0.0)
        assertEquals(8.0, TrimMath.nudge(VideoTrim(0.0, 9.0), TrimHandle.Start, 20.0, duration)!!.start, 1e-9)
    }

    @Test
    fun playheadShowsInsideTheKeptRangeOnlyWhileNoHandleIsHeld() {
        val trim = VideoTrim(2.0, 8.0)
        assertTrue(TrimMath.showsPlayhead(2.0, trim, dragging = false))
        assertTrue(TrimMath.showsPlayhead(8.0, trim, dragging = false))
        assertFalse(TrimMath.showsPlayhead(8.1, trim, dragging = false))
        assertFalse(TrimMath.showsPlayhead(5.0, trim, dragging = true))
        assertFalse(TrimMath.showsPlayhead(null, trim, dragging = false))
    }

    @Test
    fun aTouchGrabsTheNearestHandleWithinItsSlop() {
        // Start cut at 16 + 40 = 56 (handle 40…56), end cut at 16 + 160 = 176 (handle 176…192).
        assertEquals(TrimHandle.Start, TrimMath.handleAt(48f, 56f, 176f))
        assertEquals(TrimHandle.Start, TrimMath.handleAt(30f, 56f, 176f)) // 12 dp slop
        assertEquals(TrimHandle.End, TrimMath.handleAt(184f, 56f, 176f))
        assertEquals(TrimHandle.End, TrimMath.handleAt(203f, 56f, 176f))
        assertNull(TrimMath.handleAt(110f, 56f, 176f)) // the middle of the kept range
        // Handles side by side: the nearer wins; a tie goes to the end handle, drawn on top.
        assertEquals(TrimHandle.Start, TrimMath.handleAt(70f, 80f, 80f))
        assertEquals(TrimHandle.End, TrimMath.handleAt(80f, 80f, 80f))
    }

    @Test
    fun seekFollowsTheMovedCut() {
        val trim = VideoTrim(1.0, 4.0)
        assertEquals(1.0, TrimMath.seekTime(trim, TrimHandle.Start), 0.0)
        assertEquals(4.0, TrimMath.seekTime(trim, TrimHandle.End), 0.0)
    }
}
