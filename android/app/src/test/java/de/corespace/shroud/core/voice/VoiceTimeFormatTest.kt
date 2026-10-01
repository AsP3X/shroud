package de.corespace.shroud.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * `VoiceTimeFormat` vectors (conversation-compose-media §4.1, derived from
 * `ios/shroud/ShroudUI/Components/VoiceRecordingUI.swift:217-239`). Robolectric for `android.icu`'s
 * `MeasureFormat` behind [VoiceTimeFormat.spoken].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceTimeFormatTest {
    @Test
    fun recordingShowsCentiseconds() {
        assertEquals("0:07,32", VoiceTimeFormat.recording(7.32))
        assertEquals("1:05,00", VoiceTimeFormat.recording(65.0))
        assertEquals("0:00,00", VoiceTimeFormat.recording(-1.0))
        assertEquals("0:07,50", VoiceTimeFormat.recording(7.5))
        assertEquals("0:00,00", VoiceTimeFormat.recording(Double.NaN))
    }

    /** `Int((t − floor t) · 100)` truncates on both platforms: 0.29 is 28.999… centiseconds (§4.1). */
    @Test
    fun recordingCentisecondsTruncateLikeIos() {
        assertEquals("0:00,28", VoiceTimeFormat.recording(0.29))
        assertEquals("10:00,99", VoiceTimeFormat.recording(600.999))
    }

    @Test
    fun durationRoundsHalfAwayFromZero() {
        assertEquals("0:07", VoiceTimeFormat.duration(6.5))
        assertEquals("1:00", VoiceTimeFormat.duration(59.6))
        assertEquals("0:00", VoiceTimeFormat.duration(-3.0))
        assertEquals("0:00", VoiceTimeFormat.duration(0.49))
        assertEquals("0:01", VoiceTimeFormat.duration(0.5))
        // Kotlin's round() would give 0:02 (half to even); Swift's .rounded() gives 0:03.
        assertEquals("0:03", VoiceTimeFormat.duration(2.5))
        assertEquals("61:40", VoiceTimeFormat.duration(3700.0))
    }

    @Test
    fun spokenNamesMinutesAndSeconds() {
        val en = Locale.US
        assertEquals("7 seconds", VoiceTimeFormat.spoken(7.9, en))
        assertEquals("1 minute, 35 seconds", VoiceTimeFormat.spoken(95.0, en))
        assertEquals("1 minute", VoiceTimeFormat.spoken(60.0, en))
        assertEquals("0 seconds", VoiceTimeFormat.spoken(0.0, en))
        assertEquals("1 second", VoiceTimeFormat.spoken(1.2, en))
        assertEquals("0 seconds", VoiceTimeFormat.spoken(-5.0, en))
    }

    /** No hours: iOS allows only minutes and seconds. */
    @Test
    fun spokenNeverUsesHours() {
        assertEquals("61 minutes, 40 seconds", VoiceTimeFormat.spoken(3700.0, Locale.US))
    }

    @Test
    fun spokenIsLocalised() {
        val german = VoiceTimeFormat.spoken(95.0, Locale.GERMANY)
        assertTrue(german, german.startsWith("1 Minute") && german.endsWith("35 Sekunden"))
        assertEquals("7 Sekunden", VoiceTimeFormat.spoken(7.0, Locale.GERMANY))
    }
}
