package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.inter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A voice note's transcript, streamed in word after word — iOS `VoiceTranscriptText`
 * (`VoiceTranscriptText.swift:11-127`; conversation-thread §11.8). Only text revealed in front of the
 * reader streams ([streams]: unfolded by a tap, by a new note landing, or taking over from
 * "Transcribing…"); text there when the bubble first composes draws in place. Decides once, when it
 * enters composition.
 *
 * 15 sp, line spacing 2, in [color]; with [reservation] the last line keeps room for the time and ticks
 * the bubble draws over its end (the same arithmetic as the text bubbles, decision D3).
 *
 * iOS fades each piece up out of a 6 pt blur with a 3 pt rise. Compose text has no per-run blur: each
 * piece's colour alpha follows the eased progress and a same-colour shadow carries the blur; the rise is
 * dropped (decision D10). The styled string is rebuilt per frame only while the reveal runs.
 */
@Composable
fun VoiceTranscriptText(text: String, color: Color, streams: Boolean, reservation: MetaSpec?, modifier: Modifier = Modifier) {
    val style = remember(color) { TRANSCRIPT_STYLE.copy(color = color) }
    val plain = remember(text) { AnnotatedString(text) }
    val measure = rememberBubbleTextMeasure(plain, style, TextPadding(), reservation)
    val pieces = remember(text) { TranscriptReveal.pieces(text) }
    val timing = remember(pieces.size) { TranscriptReveal.Timing(pieces.size) }
    // Seconds into the reveal, while it runs; text there from the start draws in place.
    val elapsed = remember(text) { Animatable(0f) }
    val streaming = remember(text) { mutableStateOf(streams && pieces.size > 1) }
    LaunchedEffect(elapsed) {
        if (!streaming.value) return@LaunchedEffect
        try {
            elapsed.animateTo(timing.total.toFloat(), tween((timing.total * 1000).toInt(), easing = LinearEasing))
        } finally {
            streaming.value = false
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = 2)
    val state = remember { TextDrawState() }
    Layout(
        content = {},
        modifier = modifier.drawBehind {
            val layout = state.layout ?: return@drawBehind
            val seconds = elapsed.value.toDouble()
            if (!streaming.value || seconds >= timing.total) {
                drawText(layout, topLeft = state.origin)
            } else {
                val styled = TranscriptReveal.styled(pieces, color, seconds, timing.stagger, 6.dp.toPx())
                val revealed = measurer.measure(
                    styled,
                    style.copy(lineBreak = LineBreak.Simple),
                    softWrap = true,
                    constraints = Constraints(maxWidth = layout.size.width),
                    density = this,
                )
                drawText(revealed, topLeft = state.origin)
            }
        },
    ) { _, constraints ->
        val offered = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val hug = measure.at(offered)
        val width = if (constraints.hasBoundedWidth) hug.width.coerceIn(constraints.minWidth, constraints.maxWidth) else hug.width
        val geometry = if (width == offered) hug else measure.at(width)
        state.layout = geometry.layout
        state.origin = Offset.Zero
        layout(width, geometry.height.coerceIn(constraints.minHeight, constraints.maxHeight)) {}
    }
}

/** The transcript's text style: 15 sp, line spacing 2 between lines only (`VoiceTranscriptText.swift:43-57`). */
private val TRANSCRIPT_STYLE: TextStyle = inter(15f, lineSpacing = 2f)
    .copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

/** The reveal's pieces and timing (`VoiceTranscriptText.swift:59-127`), pure and unit-tested. */
object TranscriptReveal {
    /** At most this many pieces stream in; a long transcript goes in chunks of several words (`:60`). */
    const val MAX_PIECES = 160

    /** Each piece takes this long to settle (`:88`). */
    const val PIECE_SECONDS = 0.5

    /** Pieces start [stagger] apart; long transcripts close the gaps so the reveal stays near a second (`:85-96`). */
    class Timing(pieces: Int) {
        val stagger: Double = min(0.045, 0.9 / max(pieces, 1))
        val total: Double = max(pieces - 1, 0) * stagger + PIECE_SECONDS
    }

    private val WORD = Regex("\\S+\\s*")

    /**
     * The transcript as runs: a word each with its trailing space (so line breaking is unchanged), or
     * `ceil(words / 160)` words each once there are more than 160 (`:62-77`).
     */
    fun pieces(text: String): List<String> {
        val words = WORD.findAll(text).map { it.value }.toList()
        if (words.isEmpty()) return listOf(text)
        val size = (words.size + MAX_PIECES - 1) / MAX_PIECES
        return words.chunked(size) { it.joinToString("") }
    }

    /** Piece [index]'s eased progress at [elapsed] seconds: `1 − (1 − p)³`, clamped (`:108-125`). */
    fun eased(index: Int, elapsed: Double, stagger: Double): Double {
        val progress = ((elapsed - index * stagger) / PIECE_SECONDS).coerceIn(0.0, 1.0)
        return 1 - (1 - progress).pow(3)
    }

    /** The pieces styled for [elapsed]: alpha by progress, a shadow standing in for the blur. */
    fun styled(pieces: List<String>, color: Color, elapsed: Double, stagger: Double, maxBlurPx: Float): AnnotatedString =
        buildAnnotatedString {
            pieces.forEachIndexed { index, piece ->
                val e = eased(index, elapsed, stagger).toFloat()
                if (e >= 1f) {
                    append(piece)
                } else {
                    val blur = maxBlurPx * (1f - e)
                    withStyle(
                        SpanStyle(
                            color = color.copy(alpha = color.alpha * e * e),
                            shadow = if (e > 0f) Shadow(color.copy(alpha = color.alpha * e), Offset.Zero, blur) else null,
                        ),
                    ) { append(piece) }
                }
            }
        }
}
