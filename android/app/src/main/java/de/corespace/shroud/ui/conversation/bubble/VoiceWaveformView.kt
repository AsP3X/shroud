package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Rounded amplitude bars, [progress] (0…1) drawn in [playedColor] and the rest in [remainingColor] —
 * iOS `VoiceWaveformView` (`VoiceWaveformView.swift:9-65`; conversation-thread §11.9, plan C24). One
 * renderer for both sides of the feature: the composer's live recording bar and the sent bubble, so a
 * recording looks like the message it becomes. A bar straddling the playhead blends between the two
 * colours by how far in it is, which keeps scrubbing continuous instead of steppy.
 *
 * Shows only as many bars as the width holds, newest last ([VoiceWaveformRender.visibleCount]): a live
 * recording's window can be longer than the readout it sits in. Bars are [barWidth] wide, [spacing]
 * apart, at least [minHeight] tall, vertically centred, capsule-ended. Hidden from TalkBack.
 */
@Composable
fun VoiceWaveformView(
    samples: List<Float>,
    progress: Float,
    playedColor: Color,
    remainingColor: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    spacing: Dp = 2.dp,
    minHeight: Dp = 3.dp,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.clipToBounds().clearAndSetSemantics {}) {
        val bar = barWidth.toPx()
        val gap = spacing.toPx()
        val floorHeight = minHeight.toPx()
        val count = VoiceWaveformRender.visibleCount(size.width, bar, gap, samples.size, density)
        if (count <= 0) return@Canvas
        val first = samples.size - count
        val radius = CornerRadius(bar / 2f, bar / 2f)
        for (index in 0 until count) {
            val height = VoiceWaveformRender.barHeight(samples[first + index], size.height, floorHeight)
            val x = index * (bar + gap)
            val left = if (rtl) size.width - x - bar else x
            val fraction = VoiceWaveformRender.playedFraction(index, count, progress.toDouble())
            val color = when {
                fraction >= 1f -> playedColor
                fraction <= 0f -> remainingColor
                else -> lerp(remainingColor, playedColor, fraction)
            }
            drawRoundRect(color, topLeft = Offset(left, (size.height - height) / 2f), size = Size(bar, height), cornerRadius = radius)
        }
    }
}

/** The pure parts of [VoiceWaveformView] (`VoiceWaveformView.swift:43-64`), unit-tested. */
object VoiceWaveformRender {
    /**
     * How many of [sampleCount] bars fit [width] at [bar] + [gap] per bar: the newest are kept. Half a
     * point of slack ([density] px) keeps a bubble sized for exactly N bars from losing its oldest one
     * to rounding (`:45-49`).
     */
    fun visibleCount(width: Float, bar: Float, gap: Float, sampleCount: Int, density: Float = 1f): Int {
        if (sampleCount <= 0 || bar + gap <= 0f) return 0
        val capacity = floor((width + gap + 0.5f * density) / (bar + gap)).toInt()
        return if (capacity < sampleCount) max(0, capacity) else sampleCount
    }

    /** A bar's height: the sample (clamped to 0…1) of [available], never below [minHeight] (`:51-53`). */
    fun barHeight(sample: Float, available: Float, minHeight: Float): Float =
        max(minHeight, available * min(1f, max(0f, if (sample.isNaN()) 0f else sample)))

    /**
     * How much of bar [index] of [count] is behind the playhead: 1 played, 0 remaining, in between
     * for the bar under it (`:55-64`).
     */
    fun playedFraction(index: Int, count: Int, progress: Double): Float {
        if (count <= 0) return 0f
        val position = index.toDouble() / count
        val next = (index + 1).toDouble() / count
        if (progress >= next) return 1f
        if (progress <= position) return 0f
        return ((progress - position) / max(next - position, Double.MIN_VALUE)).toFloat()
    }
}
