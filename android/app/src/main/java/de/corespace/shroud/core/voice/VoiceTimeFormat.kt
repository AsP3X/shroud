package de.corespace.shroud.core.voice

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/**
 * Voice timer and duration text — iOS `VoiceTimeFormat` (`ios/shroud/ShroudUI/Components/VoiceRecordingUI.swift:217-239`;
 * conversation-compose-media §4.1, plan C24). The recording bar uses [recording], the voice bubble
 * [duration], TalkBack [spoken].
 */
object VoiceTimeFormat {
    /**
     * `0:07,32` — centiseconds while recording, so the timer visibly runs (`VoiceRecordingUI.swift:219-226`).
     * Centiseconds truncate, `Int((t − floor t) · 100)`, exactly like iOS: `0.29` reads `0:00,28` on
     * both platforms; don't "fix" it with rounding. Negative and NaN read as 0.
     */
    fun recording(seconds: Double): String {
        val total = nonNegative(seconds)
        val minutes = total.toInt() / 60
        val secs = total.toInt() % 60
        val centis = ((total - floor(total)) * 100).toInt()
        return String.format(Locale.ROOT, "%d:%02d,%02d", minutes, secs, centis)
    }

    /**
     * `0:07` — playback and duration readouts (`VoiceRecordingUI.swift:228-232`): rounded half away
     * from zero like Swift's `.rounded()` (not Kotlin's half-even `round`), so `6.5` reads `0:07`.
     */
    fun duration(seconds: Double): String {
        val total = floor(nonNegative(seconds) + 0.5).toLong()
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    /**
     * "7 seconds", "1 minute, 35 seconds", "1 minute", "0 seconds" — for TalkBack, which would read
     * "0:07" as a clock time (`VoiceRecordingUI.swift:234-238`). Rounds down like the on-screen timer.
     * iOS formats `Duration.formatted(.units(allowed: [.minutes, .seconds], width: .wide))`: minutes and
     * seconds only (no hours), zero units dropped unless the whole value is zero. ICU's wide
     * [MeasureFormat] gives the same text and localises it.
     */
    fun spoken(seconds: Double, locale: Locale = Locale.getDefault()): String {
        val total = floor(nonNegative(seconds)).toLong()
        val minutes = total / 60
        val secs = total % 60
        val measures = buildList {
            if (minutes > 0) add(Measure(minutes, MeasureUnit.MINUTE))
            if (secs > 0 || minutes == 0L) add(Measure(secs, MeasureUnit.SECOND))
        }
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).formatMeasures(*measures.toTypedArray())
    }

    private fun nonNegative(seconds: Double): Double = if (seconds.isNaN()) 0.0 else max(0.0, seconds)
}
