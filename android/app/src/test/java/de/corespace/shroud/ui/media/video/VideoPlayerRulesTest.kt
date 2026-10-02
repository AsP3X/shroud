package de.corespace.shroud.ui.media.video

import de.corespace.shroud.core.media.video.ChatVideoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player overlay's chrome and gestures (`VideoPlayerOverlay`,
 * `ios/shroud/ShroudUI/Components/VideoPlayerOverlay.swift:25-56, 150, 278-363`; conversation-compose-media §17)
 * and `ChatVideoPlayer.timeLabel` (§21.2 `MediaEditsTest` vectors).
 */
class VideoPlayerRulesTest {
    @Test
    fun timeLabelsMatchIos() {
        assertEquals("0:59", ChatVideoPlayer.timeLabel(59.99))
        assertEquals("0:00", ChatVideoPlayer.timeLabel(-1.0))
        assertEquals("0:00", ChatVideoPlayer.timeLabel(Double.NaN))
        assertEquals("-0:00", VideoPlayerRules.remainingLabel(0.0, 0.0))
        assertEquals("-1:00", VideoPlayerRules.remainingLabel(90.0, 30.0))
        assertEquals("-0:00", VideoPlayerRules.remainingLabel(10.0, 12.0))
        assertEquals("0:30 of 1:30", VideoPlayerRules.positionValue(30.0, 90.0))
    }

    @Test
    fun dragShapesTheStageAndChrome() {
        assertEquals(1f, VideoPlayerRules.backdropOpacity(0f), 0f)
        assertEquals(0.5f, VideoPlayerRules.backdropOpacity(210f), 1e-5f)
        assertEquals(0.35f, VideoPlayerRules.backdropOpacity(400f), 0f)
        assertEquals(0.5f, VideoPlayerRules.chromeOpacity(-90f), 1e-5f)
        assertEquals(0.9f, VideoPlayerRules.dragScale(160f, reduceMotion = false), 1e-5f)
        assertEquals(0.86f, VideoPlayerRules.dragScale(800f, reduceMotion = false), 0f)
        assertEquals(1f, VideoPlayerRules.dragScale(800f, reduceMotion = true), 0f)
    }

    @Test
    fun dismissNeedsDistanceOrADownwardFling() {
        assertFalse(VideoPlayerRules.shouldDismiss(110f, 110f))
        assertTrue(VideoPlayerRules.shouldDismiss(111f, 111f))
        assertTrue(VideoPlayerRules.shouldDismiss(-111f, -111f))
        // A downward flick: 60 dp + ~0.5 s at 600 dp/s ≈ 359 > 320.
        assertTrue(VideoPlayerRules.shouldDismiss(60f, VideoPlayerRules.predictedEnd(60f, 600f)))
        // An upward flick never counts as a fling (iOS compares the signed prediction).
        assertFalse(VideoPlayerRules.shouldDismiss(-60f, VideoPlayerRules.predictedEnd(-60f, -600f)))
        assertEquals(60f + 0.499f * 600f, VideoPlayerRules.predictedEnd(60f, 600f), 1e-3f)
    }

    @Test
    fun scrubberMapsTheFingerToTheClip() {
        assertEquals(30.0, VideoPlayerRules.scrubTarget(100f, 400f, 120.0), 1e-9)
        assertEquals(0.0, VideoPlayerRules.scrubTarget(-20f, 400f, 120.0), 0.0)
        assertEquals(120.0, VideoPlayerRules.scrubTarget(500f, 400f, 120.0), 0.0)
        assertEquals(0.0, VideoPlayerRules.scrubTarget(100f, 400f, 0.0), 0.0)
        assertEquals(0.25f, VideoPlayerRules.fraction(30.0, 120.0), 1e-6f)
        assertEquals(1f, VideoPlayerRules.fraction(200.0, 120.0), 0f)
        assertEquals(0f, VideoPlayerRules.fraction(5.0, 0.0), 0f)
        assertEquals(1.0, VideoPlayerRules.accessibilityStep(10.0), 0.0)
        assertEquals(6.0, VideoPlayerRules.accessibilityStep(120.0), 0.0)
    }

    @Test
    fun chromeTiming() {
        assertTrue(VideoPlayerRules.showsCentreControl(isReady = true, isPlaying = false, chromeVisible = false))
        assertTrue(VideoPlayerRules.showsCentreControl(isReady = true, isPlaying = true, chromeVisible = true))
        assertFalse(VideoPlayerRules.showsCentreControl(isReady = true, isPlaying = true, chromeVisible = false))
        assertFalse(VideoPlayerRules.showsCentreControl(isReady = false, isPlaying = false, chromeVisible = true))
        assertTrue(VideoPlayerRules.autoHides(isPlaying = true, scrubbing = false, assistive = false))
        assertFalse(VideoPlayerRules.autoHides(isPlaying = false, scrubbing = false, assistive = false))
        assertFalse(VideoPlayerRules.autoHides(isPlaying = true, scrubbing = true, assistive = false))
        assertFalse(VideoPlayerRules.autoHides(isPlaying = true, scrubbing = false, assistive = true))
        assertEquals(2_800L, VideoPlayerRules.CHROME_IDLE_MS)
    }
}
