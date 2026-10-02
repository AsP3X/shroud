package de.corespace.shroud.ui.calls

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.calls.SpeakingMeter
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The speaking meter's bars (iOS `SpeakingIndicatorView.Meter.step`, `SpeakingIndicatorView.swift:101-123`;
 * calls §8.10): each bar eases towards the target with a fast attack and a slower release; the
 * outer bars follow a little less than the middle; a slight wobble keeps it alive (not under
 * reduce motion). A plain object mutated per frame: nothing recomposes for it.
 */
class SpeakingBars {
    /** The display level the bars head towards, 0…1 ([SpeakingMeter.display]). */
    var target: Float = 0f
    private val heights = FloatArray(COUNT)
    private var lastTick = 0.0

    /** Steps the bars to [now] (seconds) and returns their heights, 0…1. */
    fun step(now: Double, wobble: Boolean): FloatArray {
        val delta = if (lastTick == 0.0) 1.0 / 30 else min(0.1, now - lastTick)
        lastTick = now
        val attack = 1 - exp(-delta * 40)
        val release = 1 - exp(-delta * 12)
        for (index in heights.indices) {
            var goal = target * WEIGHTS[index]
            if (wobble && goal > 0f) goal *= (0.75 + 0.25 * sin(now * RATES[index] + PHASES[index])).toFloat()
            val rate = if (goal > heights[index]) attack else release
            heights[index] += ((goal - heights[index]) * rate).toFloat()
        }
        return heights
    }

    companion object {
        const val COUNT = 5
        val WEIGHTS = floatArrayOf(0.55f, 0.8f, 1f, 0.8f, 0.55f)
        val RATES = doubleArrayOf(9.1, 12.7, 7.3, 11.2, 8.6)
        val PHASES = doubleArrayOf(0.0, 1.9, 3.1, 4.4, 5.6)

        /** Polls while speaking and while quiet (SPK:23-26). */
        const val POLL_SPEAKING_MS = 66L
        const val POLL_QUIET_MS = 250L

        /** The bars keep moving this long after the last voice (SPK:27-28). */
        const val HOLD_MS = 1_000L

        /** At most 30 frames a second. */
        const val FRAME_NANOS = 1_000_000_000L / 30
    }
}

/**
 * "You're speaking": a glass capsule with a mic glyph and five bars following the microphone
 * (iOS `SpeakingIndicatorView`). The level is polled from [level] every 66 ms while speaking,
 * 250 ms while quiet; only the speaking/quiet flip is Compose state, and the bars are drawn in one
 * Canvas whose frames run only while speaking. Hidden from TalkBack.
 */
@Composable
internal fun SpeakingIndicator(level: suspend () -> Float?, modifier: Modifier = Modifier) {
    val reduceMotion = ShroudTheme.reduceMotion
    val currentLevel by rememberUpdatedState(level)
    val bars = remember { SpeakingBars() }
    var speaking by remember { mutableStateOf(false) }
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(reduceMotion) {
        var lastVoice = 0L
        while (true) {
            val raw = currentLevel() ?: 0f
            val now = SystemClock.uptimeMillis()
            val target = SpeakingMeter.display(raw)
            bars.target = target
            if (target > SpeakingMeter.GATE) lastVoice = now
            val active = lastVoice != 0L && now - lastVoice < SpeakingBars.HOLD_MS
            if (active != speaking) speaking = active
            delay(if (active) SpeakingBars.POLL_SPEAKING_MS else SpeakingBars.POLL_QUIET_MS)
        }
    }
    LaunchedEffect(speaking) {
        if (!speaking) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (nanos - last >= SpeakingBars.FRAME_NANOS) {
                    last = nanos
                    frame.longValue = nanos
                }
            }
        }
    }
    Row(
        modifier
            .clearAndSetSemantics {}
            .callGlass(CircleShape)
            .padding(vertical = 5.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(ShroudIcons.MicrophoneFill, Color.White, size = 12.dp)
        Canvas(Modifier.size(BARS_WIDTH.dp, BAR_BOX.dp)) {
            // Read in the draw phase only: a new frame redraws this canvas and nothing else.
            val nanos = frame.longValue
            val now = (if (nanos == 0L) SystemClock.uptimeMillis() * 1_000_000L else nanos) / 1e9
            val heights = bars.step(now, wobble = !reduceMotion)
            val barWidth = BAR_WIDTH.dp.toPx()
            val spacing = BAR_SPACING.dp.toPx()
            for (index in 0 until SpeakingBars.COUNT) {
                val height = max(barWidth, size.height * heights[index])
                val x = index * (barWidth + spacing)
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(x, (size.height - height) / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
    }
}

private const val BAR_WIDTH = 3
private const val BAR_SPACING = 2
private const val BAR_BOX = 16
private const val BARS_WIDTH = SpeakingBars.COUNT * BAR_WIDTH + (SpeakingBars.COUNT - 1) * BAR_SPACING
