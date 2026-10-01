package de.corespace.shroud.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min

/**
 * "typing" with three small dots riding [TypingWave], or "recording" with a small level meter
 * ([RecordingWave]) — replaces presence in the thread header and the preview in the chat and
 * contact lists (`TypingIndicatorBubble.swift:88-118`; shell-chats §10.4; conversation-thread
 * §12.3). A change of activity cross-fades the word and the glyph in place (`Motion.fade`).
 * TalkBack hears one node, the activity's spoken label ("recording a voice message").
 *
 * Geometry: `Row(spacing 3)`; dots 3.5 dp, gap 2, offset y +1, alpha `0.4 + 0.6·crest`, lifted
 * `2.5·crest`; bars 2 dp wide, gap 1.5, 9 dp tall at the crest. Reduce motion: dots full and still,
 * bars at their first stop. The clock is the frame clock, so every label is in step and it pauses
 * off screen.
 */
@Composable
fun TypingLabel(
    activity: ChatPeerActivity,
    style: TextStyle = inter(12f),
    color: Color = ShroudTheme.colors.accent,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val time = if (reduceMotion) null else frameSeconds()
    AnimatedContent(
        targetState = activity,
        transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
        contentAlignment = Alignment.CenterStart,
        modifier = modifier.clearAndSetSemantics { contentDescription = activity.spokenLabel },
        label = "typingLabel",
    ) { shown ->
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(shown.label, style = style.copy(color = color), maxLines = 1)
            when (shown) {
                ChatPeerActivity.Typing -> TypingDots(color, time)
                ChatPeerActivity.Recording -> RecordingBars(color, time)
            }
        }
    }
}

/** Seconds on the frame clock, or a constant 0 under a still clock; drives the wave. */
@Composable
private fun frameSeconds(): State<Double> = produceState(0.0) {
    while (true) {
        androidx.compose.runtime.withFrameNanos { value = it / 1_000_000_000.0 }
    }
}

/** Three small dots for [TypingLabel] (`TypingIndicatorBubble.swift:206-233`). */
@Composable
private fun TypingDots(color: Color, time: State<Double>?) {
    Canvas(
        Modifier
            .size(width = 14.5.dp, height = 3.5.dp)
            .graphicsLayer { translationY = 1.dp.toPx() },
    ) {
        val d = 3.5.dp.toPx()
        val gap = 2.dp.toPx()
        val lift = 2.5.dp.toPx()
        for (index in 0 until 3) {
            val crest = time?.let { TypingWave.crest(it.value, index) } ?: 1.0
            val x = index * (d + gap) + d / 2
            val y = size.height / 2 - if (time == null) 0f else (lift * crest).toFloat()
            drawCircle(color.copy(alpha = color.alpha * (0.4f + 0.6f * crest.toFloat())), radius = d / 2, center = Offset(x, y))
        }
    }
}

/** Three small meter bars for [TypingLabel] while the peer records (`TypingIndicatorBubble.swift:311-337`). */
@Composable
private fun RecordingBars(color: Color, time: State<Double>?) {
    Canvas(Modifier.size(width = 9.dp, height = 9.dp)) {
        val w = 2.dp.toPx()
        val gap = 1.5.dp.toPx()
        val tallest = size.height
        for (index in 0 until 3) {
            val h = (tallest * RecordingWave.level(time?.value, index)).toFloat()
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (w + gap), (tallest - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

/**
 * Timing shared by the typing ink and the small dots, in step with the web client (`typing-ink`
 * and `typing-dot` in index.css): a dot swells over the first quarter of a 1.35 s cycle, settles by
 * 56 % and rests; the next follows 0.17 s behind (`TypingIndicatorBubble.swift:120-142`). Public
 * so the conversation's typing bubble (W3-THREAD-LIST) rides the same wave.
 */
object TypingWave {
    const val PERIOD = 1.35
    const val STAGGER = 0.17

    /** SwiftUI `UnitCurve.bezier((0.45, 0), (0.35, 1))`. */
    private val curve = CubicBezierEasing(0.45f, 0f, 0.35f, 1f)

    /** 0 at rest, 1 at the crest, for dot [index] at [t] seconds. */
    fun crest(t: Double, index: Int): Double {
        var phase = ((t - index * STAGGER) % PERIOD) / PERIOD
        if (phase < 0) phase += 1
        if (phase < 0.24) return curve.transform((phase / 0.24).toFloat()).toDouble()
        if (phase < 0.56) return 1 - curve.transform(((phase - 0.24) / 0.32).toFloat()).toDouble()
        return 0.0
    }
}

/**
 * Timing of the recording meter, in step with the web client (`rec-bar-*`, `voice-rec-pulse`):
 * each bar walks its own loop of heights, eased between stops (`TypingIndicatorBubble.swift:237-267`).
 */
object RecordingWave {
    /** Per bar: loop length in seconds, and heights (fraction of the tallest) at 0/25/50/75/100 %. */
    val bars: List<Pair<Double, DoubleArray>> = listOf(
        1.05 to doubleArrayOf(0.35, 0.9, 0.5, 0.75, 0.35),
        0.9 to doubleArrayOf(0.8, 0.4, 1.0, 0.55, 0.8),
        1.2 to doubleArrayOf(0.5, 1.0, 0.65, 0.3, 0.5),
        0.95 to doubleArrayOf(0.9, 0.55, 0.8, 0.45, 0.9),
        1.1 to doubleArrayOf(0.4, 0.7, 0.35, 0.95, 0.4),
    )
    const val BLINK_PERIOD = 1.4

    /** SwiftUI `UnitCurve.easeInOut`. */
    private val easeInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Height of bar [index] at [t] seconds; its first stop when [t] is null (reduce motion). */
    fun level(t: Double?, index: Int): Double {
        val (period, stops) = bars[index.mod(bars.size)]
        if (t == null) return stops[0]
        var phase = (t % period) / period
        if (phase < 0) phase += 1
        val segment = min(floor(phase * 4).toInt(), 3)
        val eased = easeInOut.transform((phase * 4 - segment).toFloat()).toDouble()
        return stops[segment] + (stops[segment + 1] - stops[segment]) * eased
    }

    /** The recorder's red dot: full, down to 30 % half-way through the blink, and back. */
    fun blink(t: Double?): Double = if (t == null) 1.0 else 0.65 + 0.35 * cos(2 * PI * t / BLINK_PERIOD)
}

@Preview(name = "Typing label", widthDp = 412)
@Composable
private fun TypingLabelPreview() {
    ShroudTheme(dark = false) {
        Column(
            Modifier.background(ShroudTheme.colors.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TypingLabel(ChatPeerActivity.Typing)
            TypingLabel(ChatPeerActivity.Recording, inter(14f))
        }
    }
}
