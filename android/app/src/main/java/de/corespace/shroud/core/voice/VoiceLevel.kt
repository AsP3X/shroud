package de.corespace.shroud.core.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Microphone level metering — the Android stand-in for `AVAudioRecorder.averagePower` /
 * `peakPower` plus iOS's dB curve (`ios/shroud/Services/Voice/VoiceRecorder.swift:154-182`;
 * media-voice-links §8.1, D8, §13.5 item 3). The web meters PCM the same way
 * (`web/src/voice/recorder.ts:75-131`): RMS and peak of the samples since the last tick.
 */
object VoiceLevel {
    /** iOS's noise floor: everything at or below −50 dBFS draws as silence (`VoiceRecorder.swift:175`). */
    const val FLOOR_DB = -50f

    /** One level per 50 ms, i.e. 20 Hz like iOS's metering timer (`VoiceRecorder.swift:63`). */
    const val METER_INTERVAL_MS = 50

    /**
     * Maps a dB pair (−160…0, −∞ allowed) onto 0…1 (`VoiceRecorder.swift:169-182`): clamp each to the
     * −50 dB floor, blend average 70 % with peak 30 % so transients still show, then `^0.6` to spread
     * the quiet half of the range. Speech sits around −40…−5 dB, so a linear map would pin every bar
     * near the floor.
     */
    fun normalize(averageDb: Float, peakDb: Float): Float {
        val blended = scaled(averageDb) * 0.7f + scaled(peakDb) * 0.3f
        return min(1f, blended.pow(0.6f))
    }

    /** `20·log10(amplitude)`; 0 (or less) is −∞, which [normalize] reads as silence. */
    fun decibels(amplitude: Double): Float = if (amplitude > 0) (20.0 * log10(amplitude)).toFloat() else Float.NEGATIVE_INFINITY

    private fun scaled(db: Float): Float {
        if (!db.isFinite()) return 0f
        return max(0f, min(1f, (db - FLOOR_DB) / -FLOOR_DB))
    }

    /**
     * Accumulates 16-bit PCM and cuts it into metering windows of [windowFrames] frames (50 ms of audio
     * at 44.1 kHz): each full window yields one level from its RMS and peak (`rms = √(Σx²/n) / 32768`,
     * `peak = max|x| / 32768`), handed to the callback in [add]. Windows are cut from the stream itself
     * rather than by a wall-clock timer, so the envelope has exactly 20 levels per second of audio and
     * the reader thread needs no lock with a ticker. A partial window at the end is dropped, as iOS's
     * last timer tick is. Not thread-safe: one recording loop owns it.
     */
    class Meter(private val windowFrames: Int) {
        init {
            require(windowFrames > 0) { "windowFrames must be positive" }
        }

        private var sumSquares = 0.0
        private var peak = 0
        private var count = 0

        /** Feeds `samples[offset until offset + size]`; calls [onLevel] once per completed window. */
        fun add(samples: ShortArray, offset: Int, size: Int, onLevel: (Float) -> Unit) {
            var i = offset
            val end = offset + size
            while (i < end) {
                val take = min(windowFrames - count, end - i)
                for (k in i until i + take) {
                    val s = samples[k].toInt()
                    sumSquares += (s * s).toDouble()
                    val a = abs(s)
                    if (a > peak) peak = a
                }
                count += take
                i += take
                if (count == windowFrames) onLevel(takeLevel())
            }
        }

        private fun takeLevel(): Float {
            val rms = sqrt(sumSquares / count) / FULL_SCALE
            val level = normalize(decibels(rms), decibels(peak / FULL_SCALE))
            sumSquares = 0.0
            peak = 0
            count = 0
            return level
        }
    }

    private const val FULL_SCALE = 32768.0
}
