package de.corespace.shroud.ui.conversation.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The voice bubble's sizing and copy (`VoiceMessageBubble.swift:69-178, 479-507, 579-582, 647-660`;
 * conversation-thread §11.2, §11.5, §11.7) — new Android tests pinning the ported rules (§21).
 */
class VoiceBubbleMathTest {
    /** The chrome beside the waveform: 2·10 + 38 + 10 + 8 + 28. */
    @Test
    fun theChromeIs104() {
        assertEquals(104f, VoiceBubbleMath.WAVEFORM_CHROME)
    }

    /** A 412 dp phone (row 380, bubble 324): ceiling 220 — 160…220 dp, 32…44 bars (§11.2). */
    @Test
    fun theWaveformRampsWithTheDurationInWholeBars() {
        val maxBubble = MessageBubbleMetrics.maxBubbleWidth(380f)
        assertEquals(160f, VoiceBubbleMath.waveformWidth(1_000, maxBubble))
        assertEquals(160f, VoiceBubbleMath.waveformWidth(2_000, maxBubble))
        // 8 s is half-way: 160 + 60 · 0.5 = 190.
        assertEquals(190f, VoiceBubbleMath.waveformWidth(8_000, maxBubble))
        // 5 s: 160 + 60 · 0.25 = 175.
        assertEquals(175f, VoiceBubbleMath.waveformWidth(5_000, maxBubble))
        // 3 s: 160 + 60 / 12 = 165.
        assertEquals(165f, VoiceBubbleMath.waveformWidth(3_000, maxBubble))
        assertEquals(220f, VoiceBubbleMath.waveformWidth(14_000, maxBubble))
        assertEquals(220f, VoiceBubbleMath.waveformWidth(60_000, maxBubble))
        assertEquals(32, VoiceBubbleMath.barCount(160f))
        assertEquals(44, VoiceBubbleMath.barCount(220f))
    }

    /** Whole bars: 360 dp phone (row 328, bubble 272) has a ceiling of 168, cut to 165. */
    @Test
    fun theCeilingIsCutToAWholeBar() {
        val maxBubble = MessageBubbleMetrics.maxBubbleWidth(328f)
        assertEquals(165f, VoiceBubbleMath.waveformWidth(14_000, maxBubble))
        // A row too narrow for the chrome keeps the minimum.
        assertEquals(160f, VoiceBubbleMath.waveformWidth(14_000, 200f))
    }

    @Test
    fun theTranscriptWrapsAtTheBubblesOwnWidth() {
        val column = VoiceBubbleMath.columnWidth(220f, showsTranscriptButton = true)
        assertEquals(256f, column)
        assertEquals(220f, VoiceBubbleMath.columnWidth(220f, showsTranscriptButton = false))
        assertEquals(304f, VoiceBubbleMath.contentWidth(column))
        assertEquals(300f, VoiceBubbleMath.transcriptWidth(column))
    }

    /** Payloads from the broken recorder say `d = 1`: the measured length wins below 300 ms (`:69-83`). */
    @Test
    fun aBogusDurationGivesWayToTheMeasuredOne() {
        assertEquals(7_000, VoiceBubbleMath.durationMs(7_000, resolved = 9_000))
        assertEquals(300, VoiceBubbleMath.durationMs(300, resolved = 9_000))
        assertEquals(9_000, VoiceBubbleMath.durationMs(1, resolved = 9_000))
        assertEquals(1, VoiceBubbleMath.durationMs(1, resolved = null))
        assertEquals(0, VoiceBubbleMath.durationMs(null, resolved = null))
    }

    @Test
    fun theSpeedChipDropsTheDecimalsOfWholeRates() {
        assertEquals("1×", VoiceBubbleMath.rateLabel(1f))
        assertEquals("1.5×", VoiceBubbleMath.rateLabel(1.5f))
        assertEquals("2×", VoiceBubbleMath.rateLabel(2f))
    }

    @Test
    fun theDrawerSaysWhatIsHappening() {
        assertEquals("Transcribing…", VoiceBubbleMath.progressLabel(downloading = false, fraction = 0.5, isDeterminate = true, languageName = null))
        assertEquals("Downloading model… 42%", VoiceBubbleMath.progressLabel(true, 0.42, true, null))
        // Rounded, as Swift's `.rounded()` does.
        assertEquals("Downloading model… 43%", VoiceBubbleMath.progressLabel(true, 0.425, true, null))
        // No percent before the first one, nor while it is indeterminate.
        assertEquals("Downloading model…", VoiceBubbleMath.progressLabel(true, 0.001, true, null))
        assertEquals("Downloading model…", VoiceBubbleMath.progressLabel(true, 0.5, false, null))
        assertEquals("Downloading German… 7%", VoiceBubbleMath.progressLabel(true, 0.07, true, "German"))
        assertEquals("Downloading German…", VoiceBubbleMath.progressLabel(true, 0.0, true, "German"))
    }

    @Test
    fun theTranscriptActionIsNamedForWhatItDoes() {
        assertEquals("Hide transcript", VoiceBubbleMath.transcriptActionName(isOpen = true, hasTranscript = true, isWorking = false))
        assertEquals("Transcribe", VoiceBubbleMath.transcriptActionName(isOpen = false, hasTranscript = false, isWorking = false))
        assertEquals("Show transcript", VoiceBubbleMath.transcriptActionName(isOpen = false, hasTranscript = true, isWorking = false))
        assertEquals("Show transcript", VoiceBubbleMath.transcriptActionName(isOpen = false, hasTranscript = false, isWorking = true))
    }

    /** The ring laps only while this phone works and nothing is readable yet; a landed transcript stops it. */
    @Test
    fun theLapRingStopsOnceATranscriptIsThere() {
        assertTrue(VoiceBubbleMath.showsLapRing(isWorking = true, hasTranscript = false))
        assertFalse(VoiceBubbleMath.showsLapRing(isWorking = true, hasTranscript = true))
        assertFalse(VoiceBubbleMath.showsLapRing(isWorking = false, hasTranscript = false))
        assertFalse(VoiceBubbleMath.showsLapRing(isWorking = false, hasTranscript = true))
    }

    /** A note younger than 15 s when its bubble appears arrived in front of the reader (`:558-566`). */
    @Test
    fun freshMeansYoungerThanTheArrivalWindow() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        assertTrue(VoiceBubbleMath.isFresh(now.minusSeconds(14), now))
        assertFalse(VoiceBubbleMath.isFresh(now.minusSeconds(15), now))
        assertFalse(VoiceBubbleMath.isFresh(now.minusSeconds(3_600), now))
    }
}
