package de.corespace.shroud.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The metering curve: `VoiceWaveformTests` "Level normalisation" (`ios/shroudTests/VoiceWaveformTests.swift:196-219`,
 * `VoiceRecorder.normalize` `VoiceRecorder.swift:174-182`) and the PCM meter that feeds it on Android
 * (media-voice-links §8.1: RMS and peak per 50 ms window, web `recorder.ts:75-131`).
 */
class VoiceLevelTest {
    // MARK: - normalize (VoiceWaveformTests.swift:198-219)

    @Test
    fun normalizeMapsTheDecibelWindowOntoUnitRange() {
        assertEquals(0f, VoiceRecorder.normalize(-50f, -50f), 0f)
        assertEquals(1f, VoiceRecorder.normalize(0f, 0f), 0f)
        val mid = VoiceRecorder.normalize(-25f, -25f)
        assertTrue(mid > 0f && mid < 1f)
    }

    @Test
    fun normalizeClampsBelowTheFloorAndHandlesInfinity() {
        assertEquals(0f, VoiceRecorder.normalize(-160f, -160f), 0f)
        assertEquals(0f, VoiceRecorder.normalize(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0f, VoiceRecorder.normalize(Float.NaN, Float.NaN), 0f)
    }

    @Test
    fun normalizeLetsPeaksLiftAQuietAverage() {
        val flat = VoiceRecorder.normalize(-40f, -40f)
        val transient = VoiceRecorder.normalize(-40f, -5f)
        assertTrue(transient > flat)
    }

    /** The exact curve: `min(1, (0.7·scaled(avg) + 0.3·scaled(peak))^0.6)`, `scaled(db) = clamp((db + 50) / 50)`. */
    @Test
    fun normalizeFollowsTheIosCurve() {
        val expected = (0.7f * 0.5f + 0.3f * 0.9f).pow(0.6f)
        assertEquals(expected, VoiceLevel.normalize(-25f, -5f), 1e-6f)
        assertEquals(1f, VoiceLevel.normalize(6f, 6f), 0f)
    }

    @Test
    fun decibelsOfSilenceIsMinusInfinity() {
        assertEquals(Float.NEGATIVE_INFINITY, VoiceLevel.decibels(0.0), 0f)
        assertEquals(0f, VoiceLevel.decibels(1.0), 0f)
        assertEquals(-20f, VoiceLevel.decibels(0.1), 1e-5f)
    }

    // MARK: - Meter

    private fun levels(samples: ShortArray, window: Int, chunk: Int = samples.size): List<Float> {
        val meter = VoiceLevel.Meter(window)
        val out = ArrayList<Float>()
        var i = 0
        while (i < samples.size) {
            val n = minOf(chunk, samples.size - i)
            meter.add(samples, i, n) { out += it }
            i += n
        }
        return out
    }

    @Test
    fun silenceMetersAsZero() {
        val out = levels(ShortArray(2205 * 3), 2205)
        assertEquals(listOf(0f, 0f, 0f), out)
    }

    @Test
    fun fullScaleSquareWaveMetersAsOne() {
        val square = ShortArray(2205) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertEquals(1f, levels(square, 2205).single(), 1e-4f)
    }

    /** A −20 dBFS sine: RMS ≈ −23 dB, peak −20 dB → the iOS curve of that pair. */
    @Test
    fun sineMetersWithRmsAndPeak() {
        val amplitude = 0.1 * 32768
        val sine = ShortArray(2205) { (amplitude * sin(2 * PI * 441.0 * it / 44_100.0)).roundToInt().toShort() }
        val rmsDb = (20 * log10(0.1 / kotlin.math.sqrt(2.0))).toFloat()
        val expected = VoiceLevel.normalize(rmsDb, -20f)
        assertEquals(expected, levels(sine, 2205).single(), 2e-3f)
    }

    /** Windows are cut from the stream, whatever the read size; a partial last window is dropped. */
    @Test
    fun windowsIgnoreChunkBoundariesAndDropThePartialTail() {
        val loud = ShortArray(2205 * 2 + 1000) { if (it < 2205) 0 else 16_000 }
        val whole = levels(loud, 2205)
        val chunked = levels(loud, 2205, chunk = 441)
        assertEquals(2, whole.size)
        assertEquals(whole, chunked)
        assertEquals(0f, whole[0], 0f)
        assertTrue(whole[1] > 0.8f)
    }

    /** Twenty levels per second of audio, like iOS's 20 Hz timer. */
    @Test
    fun twentyLevelsPerSecond() {
        val second = ShortArray(44_100) { 1000 }
        assertEquals(20, levels(second, VoiceRecorder.WINDOW_FRAMES, chunk = VoiceRecorder.CHUNK_FRAMES).size)
        assertEquals(2205, VoiceRecorder.WINDOW_FRAMES)
        assertEquals(441, VoiceRecorder.CHUNK_FRAMES)
    }
}
