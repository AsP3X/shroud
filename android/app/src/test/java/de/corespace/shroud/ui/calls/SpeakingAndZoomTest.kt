package de.corespace.shroud.ui.calls

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import de.corespace.shroud.core.calls.SpeakingMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/** The speaking meter's bars (SPK:101-123) and their shared screen's zoom rules (SSV:46-164). */
class SpeakingAndZoomTest {
    @Test
    fun theFirstFrameAttacksWithAThirtiethOfASecond() {
        val bars = SpeakingBars()
        bars.target = 1f
        val heights = bars.step(now = 100.0, wobble = false).copyOf()
        val attack = (1 - exp(-40.0 / 30)).toFloat()
        assertEquals(attack, heights[2], 1e-4f)
        assertEquals(0.55f * attack, heights[0], 1e-4f)
        assertEquals(0.8f * attack, heights[1], 1e-4f)
        assertEquals(heights[1], heights[3], 0f)
        assertEquals(heights[0], heights[4], 0f)
    }

    @Test
    fun barsReleaseSlowerThanTheyAttack() {
        val bars = SpeakingBars()
        bars.target = 1f
        bars.step(now = 1.0, wobble = false)
        val peak = bars.step(now = 1.1, wobble = false)[2]
        bars.target = 0f
        val after = bars.step(now = 1.2, wobble = false)[2]
        // Release rate at 0.1 s: 1 − e^(−1.2).
        assertEquals(peak * exp(-1.2).toFloat(), after, 1e-4f)
    }

    @Test
    fun aLongPauseCountsAsATenthOfASecond() {
        val bars = SpeakingBars()
        bars.target = 1f
        bars.step(now = 1.0, wobble = false)
        val before = bars.step(now = 1.0 + 1e-9, wobble = false)[2]
        val jumped = SpeakingBars().apply { target = 1f }
        jumped.step(now = 1.0, wobble = false)
        val later = jumped.step(now = 60.0, wobble = false)[2]
        val expected = before + (1f - before) * (1 - exp(-4.0)).toFloat()
        assertEquals(expected, later, 1e-3f)
    }

    @Test
    fun theMeterMapsDecibelsAndGatesQuiet() {
        // −40 dBFS → 0.25, −10 dBFS and louder → full (SPK:92-99).
        assertEquals(0.25f, SpeakingMeter.display(0.01f), 1e-4f)
        assertEquals(1f, SpeakingMeter.display(0.316f), 1e-3f)
        assertEquals(0f, SpeakingMeter.display(0f), 0f)
        assertTrue(SpeakingMeter.display(0.01f) > SpeakingMeter.GATE)
        assertFalse(SpeakingMeter.display(0.004f) > SpeakingMeter.GATE)
    }

    @Test
    fun theScreenIsFittedWholeAndRounded() {
        val view = Size(1080f, 2000f)
        assertEquals(Size(1080f, 608f), SharedScreenZoom.fittedSize(Size(1920f, 1080f), view))
        // A phone's tall screen: as high as the view.
        assertEquals(Size(920f, 2000f), SharedScreenZoom.fittedSize(Size(1206f, 2622f), view))
        // Before the first frame: the whole view.
        assertEquals(view, SharedScreenZoom.fittedSize(Size.Zero, view))
    }

    @Test
    fun aNewResolutionOfTheSamePictureKeepsTheZoom() {
        assertTrue(SharedScreenZoom.keepsZoom(Size(1920f, 1080f), Size(1280f, 720f)))
        assertFalse(SharedScreenZoom.keepsZoom(Size(1920f, 1080f), Size(1080f, 1920f)))
        assertFalse(SharedScreenZoom.keepsZoom(Size.Zero, Size(1280f, 720f)))
    }

    @Test
    fun zoomKeepsThePointUnderTheFingers() {
        val view = Size(1000f, 2000f)
        val focus = Offset(800f, 400f)
        val offset = SharedScreenZoom.zoomed(Offset.Zero, scale = 1f, newScale = 2.5f, focus = focus, view = view)
        // The content point under the focus before and after.
        val center = Offset(500f, 1000f)
        val before = focus - center
        val after = (focus - center - offset) / 2.5f
        assertEquals(before.x, after.x, 1e-3f)
        assertEquals(before.y, after.y, 1e-3f)
    }

    @Test
    fun aFittingPictureCannotBeDraggedAway() {
        val content = Size(1000f, 562f)
        val view = Size(1000f, 2000f)
        assertEquals(Offset.Zero, SharedScreenZoom.clamp(Offset(300f, 300f), content, view, scale = 1f))
        val zoomed = SharedScreenZoom.clamp(Offset(5000f, 5000f), content, view, scale = 4f)
        assertEquals(1500f, zoomed.x, 0f)
        assertEquals(124f, zoomed.y, 0f)
    }
}
