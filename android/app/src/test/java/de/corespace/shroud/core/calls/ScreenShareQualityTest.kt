package de.corespace.shroud.core.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The screen's resolution, frame rate and wire size — iOS `ScreenShareWireTests` quality parts
 * (`ios/shroudTests/ScreenShareWireTests.swift:84-104, 147-163`); calls §7.1.
 */
class ScreenShareQualityTest {
    private val hd = ScreenShareQuality.Resolution.P720
    private val fullHd = ScreenShareQuality.Resolution.P1080
    private val source = ScreenShareQuality.Resolution.Source

    @Test
    fun framesAreScaledToTheLongestSideAndKeptEven() {
        // An iPhone 17 Pro screen, portrait: its long side comes down to 1920.
        val (width, height) = screenWireSize(1206, 2622)
        assertTrue(height in 1918..1920)
        assertTrue(width % 2 == 0 && height % 2 == 0)
        assertTrue(abs(width.toDouble() / height - 1206.0 / 2622.0) < 0.01)
        // Never enlarged.
        assertEquals(640 to 480, screenWireSize(640, 480))
        assertEquals(640 to 480, screenWireSize(641, 481))
    }

    @Test
    fun theChosenResolutionSetsTheLongestSide() {
        val small = screenWireSize(1206, 2622, hd.maxSide)
        assertTrue(small.second in 1278..1280 && small.first % 2 == 0)
        assertTrue(screenWireSize(1206, 2622, fullHd.maxSide).second >= 1918)
        // Source: the screen's own pixels, only made even.
        assertEquals(1206 to 2622, screenWireSize(1206, 2622, null))
        assertEquals(1178 to 2556, screenWireSize(1179, 2556, null))
        // At least 2 a side.
        assertEquals(2 to 2, screenWireSize(1, 1, null))
    }

    @Test
    fun moreFramesOrMorePixelsGetMoreBits() {
        assertEquals(ScreenShareQuality(fullHd, 15), ScreenShareQuality.Standard)
        assertEquals(2_500_000, ScreenShareQuality(fullHd, 30).bitrate)
        for (rate in ScreenShareQuality.FRAME_RATES) {
            assertTrue(ScreenShareQuality(hd, rate).bitrate < ScreenShareQuality(fullHd, rate).bitrate)
            assertTrue(ScreenShareQuality(fullHd, rate).bitrate < ScreenShareQuality(source, rate).bitrate)
        }
        assertTrue(ScreenShareQuality(source, 30).bitrate < ScreenShareQuality(source, 60).bitrate)
        assertTrue(ScreenShareQuality(fullHd, 30).keepsResolution)
        assertFalse(ScreenShareQuality(fullHd, 60).keepsResolution)
        assertEquals("Source · 60 fps", ScreenShareQuality(source, 60).label)
        assertEquals("1080p · 15 fps", ScreenShareQuality.Standard.label)
        // The same names as the web client stores.
        assertEquals(listOf("720p", "1080p", "source"), ScreenShareQuality.Resolution.entries.map { it.raw })
    }

    /** Every cell of the table (ScreenShareWire.swift:280-292). */
    @Test
    fun theBitrateTable() {
        val table = mapOf(
            hd to listOf(1_200_000, 1_800_000, 2_800_000),
            fullHd to listOf(1_800_000, 2_500_000, 4_000_000),
            source to listOf(3_000_000, 4_500_000, 6_500_000),
        )
        for ((resolution, row) in table) {
            assertEquals(row, ScreenShareQuality.FRAME_RATES.map { ScreenShareQuality(resolution, it).bitrate })
        }
        assertEquals(listOf(1280, 1920, null), listOf(hd.maxSide, fullHd.maxSide, source.maxSide))
    }

    /**
     * Changing only the frame rate keeps the wire size and still reports the new rate.
     * A resolution change reports a different size. The running share applies both
     * (`ScreenCaptureSource`, `CallMediaEngine`).
     */
    @Test
    fun aFrameRateChangeKeepsTheSizeAndAResolutionChangeDoesNot() {
        val standard = screenOutput(1206, 2622, ScreenShareQuality.Standard)
        val faster = screenOutput(1206, 2622, ScreenShareQuality(fullHd, 60))
        assertEquals(standard.width, faster.width)
        assertEquals(standard.height, faster.height)
        assertEquals(15, standard.frameRate)
        assertEquals(60, faster.frameRate)
        val ownPixels = screenOutput(1206, 2622, ScreenShareQuality(source, 60))
        assertTrue(ownPixels.height > faster.height)
        assertEquals(60, ownPixels.frameRate)
        val smaller = screenOutput(1206, 2622, ScreenShareQuality(hd, 30))
        assertTrue(smaller.height < standard.height)
        assertEquals(30, smaller.frameRate)
    }

    /** `ScreenShareQuality.saved` (CallController.swift:2155-2162): each part falls back alone. */
    @Test
    fun aStoredChoiceReadsBackAndUnknownPartsFallBackAlone() {
        assertEquals(ScreenShareQuality.Standard, ScreenShareQuality.fromStored(null, null))
        assertEquals(ScreenShareQuality(source, 60), ScreenShareQuality.fromStored("source", 60))
        assertEquals(ScreenShareQuality(fullHd, 30), ScreenShareQuality.fromStored("4k", 30))
        assertEquals(ScreenShareQuality(hd, 15), ScreenShareQuality.fromStored("720p", 24))
        assertEquals(ScreenShareQuality(hd, 15), ScreenShareQuality.fromStored("720p", 0))
    }
}
