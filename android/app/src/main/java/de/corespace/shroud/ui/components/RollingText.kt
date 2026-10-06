package de.corespace.shroud.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * A readout whose characters roll as they change, like iOS `rollingDigits` (`Motion.swift`, Rolling
 * digits: `.contentTransition(.numericText())` on a snappy spring). Each character position animates
 * on its own, so "0:41" → "0:42" moves only the last digit: upwards, or downwards when [countsDown].
 * [animated] false shows a change at once (while scrubbing); under Reduce Motion the characters
 * cross-fade. Always laid out left to right: a time or a byte count reads the same in an RTL locale.
 *
 * One line that never wraps: for short readouts (a time, a byte count, a percent). With [overflow]
 * [TextOverflow.Ellipsis], a line too wide to fit (a large font scale) is drawn as plain ellipsised text
 * instead, without the roll. For a readout that can change many times a second, pass the text through
 * [rememberPaced]; for one that follows words that may wrap, use [LabelWithRollingValue].
 */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    countsDown: Boolean = false,
    animated: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    // TalkBack reads the line as one, not character by character.
    val semantics = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        if (overflow == TextOverflow.Ellipsis) {
            BoxWithConstraints(semantics) {
                val measurer = rememberTextMeasurer()
                // The rolled line is laid out character by character, so measure it that way.
                val width = remember(text, style, measurer) { text.sumOf { measurer.measure(it.toString(), style).size.width } }
                if (width <= constraints.maxWidth) {
                    RollingLine(text, style, color, Modifier, countsDown, animated)
                } else {
                    ShroudText(text, style, color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            RollingLine(text, style, color, semantics, countsDown, animated)
        }
    }
}

@Composable
private fun RollingLine(text: String, style: TextStyle, color: Color, modifier: Modifier, countsDown: Boolean, animated: Boolean) {
    val reduceMotion = ShroudTheme.reduceMotion
    Row(modifier) {
        text.forEachIndexed { index, char ->
            // Keyed from the end: "9:59" → "10:00" keeps the seconds' positions.
            key(text.length - index) {
                if (animated) {
                    AnimatedContent(
                        targetState = char,
                        transitionSpec = { rollTransform(reduceMotion, countsDown) },
                        label = "rollingChar",
                    ) { shown ->
                        ShroudText(shown.toString(), style, color, maxLines = 1)
                    }
                } else {
                    // Plain, not an AnimatedContent with no transition: that would still keep the old
                    // character composed, on top of the new one, until its next frame.
                    ShroudText(char.toString(), style, color, maxLines = 1)
                }
            }
        }
    }
}

/** The new character rises in from half a line below as the old one leaves upwards (mirrored when counting down). */
private fun rollTransform(reduceMotion: Boolean, countsDown: Boolean): ContentTransform {
    if (reduceMotion) return fadeIn(Motion.reduced()) togetherWith fadeOut(Motion.reduced())
    val direction = if (countsDown) -1 else 1
    return (slideInVertically(Motion.snappy()) { direction * it / 2 } + fadeIn(Motion.snappy()))
        .togetherWith(slideOutVertically(Motion.snappy()) { -direction * it / 2 } + fadeOut(Motion.snappy()))
}

/**
 * [label] then a rolling [value] after it ("Downloading model… 42%"), wrapping like a sentence: word by
 * word, with the value (paced, see [rememberPaced]) after the last word, or on the next line when it
 * doesn't fit there. No [value] shows the words alone. TalkBack reads it as one line.
 */
@Composable
fun LabelWithRollingValue(label: String, value: String?, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val line = if (value != null) "$label $value" else label
    val words = remember(label) { label.split(' ') }
    FlowRow(modifier.clearAndSetSemantics { text = AnnotatedString(line) }) {
        // Each word keeps the space after it; at a line end it takes no room that shows.
        words.forEachIndexed { index, word ->
            ShroudText(if (index < words.lastIndex || value != null) "$word " else word, style, color)
        }
        if (value != null) RollingText(rememberPaced(value), style, color)
    }
}

/**
 * [value], changing at most once per [Motion.READOUT_PACE_MS] and always ending on the latest, like iOS
 * `PacedRollingText`. A [RollingText] fed every tick of a download would roll back to back; paced, each
 * roll finishes before the next begins. Pass [pacing] false once the stream ends (a transfer finished)
 * so the final value lands at once.
 */
@Composable
fun <T> rememberPaced(value: T, pacing: Boolean = true): T {
    val pacer = remember { ReadoutPacer(value) }
    LaunchedEffect(value, pacing) { pacer.show(value, pacing) }
    return pacer.shown
}

/** The state behind [rememberPaced]; [timeSource] is swappable for a test scheduler's virtual clock. */
internal class ReadoutPacer<T>(
    initial: T,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    var shown by mutableStateOf(initial)
        private set

    private var shownAt: ComparableTimeMark? = null

    /**
     * Shows [value] once a pace has passed since the last change shown. Cancelled by the next value, but
     * the deadline hangs off that last change, so a steady stream still lands one per pace.
     */
    suspend fun show(value: T, pacing: Boolean) {
        if (value == shown) return
        val last = shownAt
        if (pacing && last != null) delay(Motion.READOUT_PACE_MS.milliseconds - last.elapsedNow())
        shown = value
        shownAt = timeSource.markNow()
    }
}
