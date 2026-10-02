package de.corespace.shroud.ui.conversation.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The renderer parts of `ios/shroudTests/VoiceWaveformTests.swift` (conversation-thread §20.6): what
 * `VoiceWaveformView` (`VoiceWaveformView.swift:43-64`) draws from the samples. The codec, downsample,
 * resample and placeholder vectors run in `core/voice/VoiceWaveformTest` (W2-VOICE, plan C24).
 */
class VoiceWaveformRenderTest {
    // ---- Visible window (`:43-49`) ----

    /** A bubble sized for exactly N bars keeps all N: 44 bars of 3 + 2 need 218 dp, not 220. */
    @Test
    fun aBubbleSizedForItsBarsShowsEveryOne() {
        assertEquals(44, VoiceWaveformRender.visibleCount(width = 218f, bar = 3f, gap = 2f, sampleCount = 44))
        assertEquals(32, VoiceWaveformRender.visibleCount(width = 160f, bar = 3f, gap = 2f, sampleCount = 32))
    }

    /** Half a point of slack: 217.5 dp still holds 44 bars, 217.4 only 43. */
    @Test
    fun halfAPointOfSlackAbsorbsRounding() {
        assertEquals(44, VoiceWaveformRender.visibleCount(width = 217.5f, bar = 3f, gap = 2f, sampleCount = 44))
        assertEquals(43, VoiceWaveformRender.visibleCount(width = 217.4f, bar = 3f, gap = 2f, sampleCount = 44))
    }

    /** The slack is half a point, so in pixels it scales with the density. */
    @Test
    fun theSlackScalesWithTheDensity() {
        // 3 px/dp: 44 bars need 654 px; 652.5 px is within 0.5 dp (1.5 px).
        assertEquals(44, VoiceWaveformRender.visibleCount(width = 652.5f, bar = 9f, gap = 6f, sampleCount = 44, density = 3f))
        assertEquals(43, VoiceWaveformRender.visibleCount(width = 652.4f, bar = 9f, gap = 6f, sampleCount = 44, density = 3f))
    }

    /** A live recording longer than its readout shows only the newest samples that fit. */
    @Test
    fun aLongRecordingShowsTheNewestThatFit() {
        assertEquals(20, VoiceWaveformRender.visibleCount(width = 98f, bar = 3f, gap = 2f, sampleCount = 300))
        assertEquals(0, VoiceWaveformRender.visibleCount(width = 0f, bar = 3f, gap = 2f, sampleCount = 300))
        assertEquals(0, VoiceWaveformRender.visibleCount(width = 100f, bar = 3f, gap = 2f, sampleCount = 0))
    }

    // ---- Bar heights (`:51-53`) ----

    @Test
    fun barsScaleWithTheSampleAndNeverVanish() {
        assertEquals(26f, VoiceWaveformRender.barHeight(1f, available = 26f, minHeight = 3f), 1e-6f)
        assertEquals(13f, VoiceWaveformRender.barHeight(0.5f, available = 26f, minHeight = 3f), 1e-6f)
        // Silence keeps a visible stub.
        assertEquals(3f, VoiceWaveformRender.barHeight(0f, available = 26f, minHeight = 3f), 1e-6f)
        // Out-of-range samples are clamped, not drawn outside the row.
        assertEquals(26f, VoiceWaveformRender.barHeight(1.7f, available = 26f, minHeight = 3f), 1e-6f)
        assertEquals(3f, VoiceWaveformRender.barHeight(-0.4f, available = 26f, minHeight = 3f), 1e-6f)
        assertEquals(3f, VoiceWaveformRender.barHeight(Float.NaN, available = 26f, minHeight = 3f), 1e-6f)
    }

    // ---- Played colour (`:55-64`) ----

    @Test
    fun barsBehindThePlayheadArePlayedAndTheOneUnderItBlends() {
        // Ten bars at 35 %: bars 0–2 played, bar 3 half-way, 4–9 remaining.
        val fractions = (0 until 10).map { VoiceWaveformRender.playedFraction(it, 10, 0.35) }
        assertEquals(listOf(1f, 1f, 1f), fractions.take(3))
        assertEquals(0.5f, fractions[3], 1e-4f)
        assertEquals(List(6) { 0f }, fractions.drop(4))
    }

    @Test
    fun theEndsAreExact() {
        assertEquals(0f, VoiceWaveformRender.playedFraction(0, 10, 0.0))
        assertEquals(1f, VoiceWaveformRender.playedFraction(9, 10, 1.0))
        // On a bar boundary the next bar has not started.
        assertEquals(1f, VoiceWaveformRender.playedFraction(1, 10, 0.2))
        assertEquals(0f, VoiceWaveformRender.playedFraction(2, 10, 0.2))
        // No bars: nothing is played.
        assertEquals(0f, VoiceWaveformRender.playedFraction(0, 0, 0.5))
    }
}
