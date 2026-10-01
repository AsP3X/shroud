package de.corespace.shroud.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clocks behind [TypingLabel] and [PresenceDot]: `TypingWave` and `RecordingWave`
 * (`TypingIndicatorBubble.swift:120-142, 237-267`; conversation-thread §12.2-12.3) and the halo
 * loop (`PresenceDot.swift:35-49`; contacts §5.9).
 */
class LiveIndicatorsTest {
    private val period = TypingWave.PERIOD

    @Test
    fun typingDotRestsThenCrestsAtAQuarterOfTheCycle() {
        assertEquals(0.0, TypingWave.crest(0.0, 0), 1e-9)
        assertEquals(1.0, TypingWave.crest(0.24 * period, 0), 1e-6)
        assertEquals(0.0, TypingWave.crest(0.56 * period, 0), 1e-6)
        assertEquals(0.0, TypingWave.crest(0.8 * period, 0), 1e-9)
    }

    @Test
    fun typingDotRisesThenFalls() {
        var last = -1.0
        for (step in 0..24) {
            val value = TypingWave.crest(step / 100.0 * period, 0)
            assertTrue("rise at $step", value >= last - 1e-9)
            last = value
        }
        for (step in 24..56) {
            val value = TypingWave.crest(step / 100.0 * period, 0)
            assertTrue("fall at $step", value <= last + 1e-9)
            last = value
        }
    }

    @Test
    fun eachDotFollowsTheOneBeforeBySeventeenHundredths() {
        for (t in listOf(0.0, 0.1, 0.33, 0.7, 1.2, 5.5)) {
            assertEquals(TypingWave.crest(t, 0), TypingWave.crest(t + TypingWave.STAGGER, 1), 1e-9)
            assertEquals(TypingWave.crest(t, 0), TypingWave.crest(t + 2 * TypingWave.STAGGER, 2), 1e-9)
        }
    }

    @Test
    fun typingWaveRepeatsEveryPeriodAndCopesWithEarlyTimes() {
        for (t in listOf(0.05, 0.2, 0.9)) {
            assertEquals(TypingWave.crest(t, 2), TypingWave.crest(t + period, 2), 1e-9)
        }
        // Dot 2 at t = 0 sits 0.34 s before its cycle start: the phase wraps instead of going negative.
        assertEquals(TypingWave.crest(period - 0.34, 0), TypingWave.crest(0.0, 2), 1e-9)
    }

    @Test
    fun recordingBarsWalkTheirStops() {
        for ((index, bar) in RecordingWave.bars.withIndex()) {
            val (barPeriod, stops) = bar
            assertEquals(stops[0], RecordingWave.level(0.0, index), 1e-9)
            assertEquals(stops[1], RecordingWave.level(barPeriod * 0.25, index), 1e-3)
            assertEquals(stops[2], RecordingWave.level(barPeriod * 0.5, index), 1e-3)
            assertEquals(stops[3], RecordingWave.level(barPeriod * 0.75, index), 1e-3)
            assertEquals(stops[4], RecordingWave.level(barPeriod * 0.9999, index), 1e-3)
        }
    }

    @Test
    fun recordingBarsHoldTheirFirstStopUnderReduceMotion() {
        assertEquals(0.35, RecordingWave.level(null, 0), 1e-9)
        assertEquals(0.8, RecordingWave.level(null, 1), 1e-9)
        assertEquals(0.5, RecordingWave.level(null, 2), 1e-9)
    }

    @Test
    fun recordingWaveTableMatchesIos() {
        assertEquals(listOf(1.05, 0.9, 1.2, 0.95, 1.1), RecordingWave.bars.map { it.first })
        assertEquals(listOf(0.35, 0.9, 0.5, 0.75, 0.35), RecordingWave.bars[0].second.toList())
        assertEquals(listOf(0.8, 0.4, 1.0, 0.55, 0.8), RecordingWave.bars[1].second.toList())
        assertEquals(listOf(0.5, 1.0, 0.65, 0.3, 0.5), RecordingWave.bars[2].second.toList())
        assertEquals(listOf(0.9, 0.55, 0.8, 0.45, 0.9), RecordingWave.bars[3].second.toList())
        assertEquals(listOf(0.4, 0.7, 0.35, 0.95, 0.4), RecordingWave.bars[4].second.toList())
    }

    @Test
    fun recorderDotBlinksDownToThirtyPercent() {
        assertEquals(1.0, RecordingWave.blink(null), 1e-9)
        assertEquals(1.0, RecordingWave.blink(0.0), 1e-9)
        assertEquals(0.3, RecordingWave.blink(RecordingWave.BLINK_PERIOD / 2), 1e-9)
        assertEquals(1.0, RecordingWave.blink(RecordingWave.BLINK_PERIOD), 1e-9)
    }

    @Test
    fun presenceHaloExpandsFadesPausesAndResets() {
        assertEquals(2110, PresenceHalo.CYCLE_MS)
        assertEquals(PresenceHalo.Frame(1f, 0.45f), PresenceHalo.frame(0f))
        val late = PresenceHalo.frame(1599.9f)
        assertEquals(2.6f, late.scale, 1e-3f)
        assertEquals(0f, late.alpha, 1e-3f)
        assertEquals(PresenceHalo.Frame(2.6f, 0f), PresenceHalo.frame(1600f))
        assertEquals(PresenceHalo.Frame(2.6f, 0f), PresenceHalo.frame(2099f))
        val reset = PresenceHalo.frame(2109.99f)
        assertEquals(1f, reset.scale, 1e-2f)
        assertEquals(0.45f, reset.alpha, 1e-2f)
        assertEquals(PresenceHalo.frame(0f), PresenceHalo.frame(2110f))
    }

    @Test
    fun presenceHaloEasesOut() {
        // iOS `.easeOut(duration: 1.6)`: more than half the growth in the first half of the time.
        val half = PresenceHalo.frame(800f)
        assertTrue(half.scale > 1.8f && half.scale < 2.6f)
        assertTrue(half.alpha < 0.225f && half.alpha > 0f)
    }
}
