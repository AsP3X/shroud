package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Telegram's "→A" transcript toggle beside a voice note's waveform — iOS `VoiceTranscriptButton`
 * (`VoiceTranscriptButton.swift:10-64`; conversation-thread §11.6). Folded it reads "→A" (voice to
 * text); unfolded, the arrow slides off and the A's legs swing into a chevron pointing back up — one set
 * of strokes morphing ([TranscriptGlyph]), not two icons swapping. A comet laps the outline while this
 * phone is transcribing ([TranscriptLapRing]). The geometry matches the web client's SVG.
 *
 * 28 dp, radius 9, [fill] behind [ink]; half opacity when disabled; shrinks to 0.88 while pressed, no
 * haptic of its own. Hidden from TalkBack: the bubble offers "Show transcript" / "Hide transcript" /
 * "Transcribe".
 */
@Composable
fun VoiceTranscriptButton(
    isOpen: Boolean,
    isWorking: Boolean,
    ink: Color,
    fill: Color,
    isEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val progress by animateFloatAsState(
        targetValue = if (isOpen) 1f else 0f,
        animationSpec = Motion.respecting(reduceMotion, Motion.standard()),
        label = "transcriptGlyph",
    )
    val opacity by animateFloatAsState(if (isEnabled) 1f else 0.5f, Motion.snappy(), label = "transcriptEnabled")
    Box(
        modifier
            .size(VoiceTranscriptButtonDefaults.size)
            .pressable(enabled = isEnabled, scale = 0.88f, dimming = 0f, haptic = Haptic.None, onClick = onClick)
            .clearAndSetSemantics {}
            .alpha(opacity)
            .clip(RoundedCornerShape(9.dp))
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(TranscriptGlyph.SIZE)) { drawTranscriptGlyph(progress, ink) }
        AnimatedVisibility(isWorking, enter = fadeIn(Motion.snappy()), exit = fadeOut(Motion.snappy())) {
            TranscriptLapRing(ink, animated = !reduceMotion)
        }
    }
}

/** The toggle's metrics. */
object VoiceTranscriptButtonDefaults {
    /** The toggle's side (`VoiceTranscriptButton.swift:21`). */
    val size = 28.dp
}

/**
 * Strokes of the "→A" glyph in a 24-unit box (the web client's SVG viewBox,
 * `VoiceTranscriptButton.swift:66-131`), pure so the morph is unit-tested. Points are in units; the
 * glyph maps them `(x − 12)·u + centre`.
 */
object TranscriptGlyph {
    val SIZE = 20.dp

    /** The arrow: shaft and head (`:82-92`). */
    val arrow: List<List<Offset>> = listOf(
        listOf(Offset(3f, 12f), Offset(9.4f, 12f)),
        listOf(Offset(6.9f, 9.3f), Offset(9.6f, 12f), Offset(6.9f, 14.7f)),
    )

    /** The A's crossbar (`:94-104`), shrinking about its own centre (17, 14). */
    val crossbar: List<Offset> = listOf(Offset(14.3f, 14f), Offset(19.7f, 14f))
    val crossbarAnchor = Offset(17f, 14f)

    /**
     * The A's legs at [progress] 0, the chevron's arms at 1 (`:106-130`): the apex slides from (17, 6)
     * to (12, 9) while each leg turns outward from 20.556° to 45° off vertical and shortens from
     * 12.816 to 8.5 units.
     */
    fun legs(progress: Float): List<Offset> {
        val t = progress.toDouble()
        val apexX = 17 + (12 - 17) * t
        val apexY = 6 + (9 - 6) * t
        val angle = (20.556 + (45 - 20.556) * t) * PI / 180
        val length = 12.816 + (8.5 - 12.816) * t
        val dx = sin(angle) * length
        val dy = cos(angle) * length
        return listOf(
            Offset((apexX - dx).toFloat(), (apexY + dy).toFloat()),
            Offset(apexX.toFloat(), apexY.toFloat()),
            Offset((apexX + dx).toFloat(), (apexY + dy).toFloat()),
        )
    }
}

/** Draws the glyph at [progress] (0 folded, 1 open) in [ink] (`VoiceTranscriptButton.swift:44-63`). */
private fun DrawScope.drawTranscriptGlyph(progress: Float, ink: Color) {
    val unit = min(size.width, size.height) / 24f
    val centre = Offset(size.width / 2f, size.height / 2f)
    fun map(p: Offset) = Offset(centre.x + (p.x - 12f) * unit, centre.y + (p.y - 12f) * unit)
    val stroke = Stroke(width = 2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun polyline(points: List<Offset>): Path = Path().apply {
        val first = map(points.first())
        moveTo(first.x, first.y)
        for (point in points.drop(1)) map(point).let { lineTo(it.x, it.y) }
    }
    val fade = 1f - progress
    if (fade > 0f) {
        withTransform({ translate(left = -4f * unit * progress) }) {
            for (part in TranscriptGlyph.arrow) drawPath(polyline(part), ink.copy(alpha = ink.alpha * fade), style = stroke)
        }
    }
    drawPath(polyline(TranscriptGlyph.legs(progress)), ink, style = stroke)
    if (fade > 0f) {
        val pivot = map(TranscriptGlyph.crossbarAnchor)
        withTransform({ scale(scaleX = 1f - 0.7f * progress, scaleY = 1f, pivot = pivot) }) {
            drawPath(polyline(TranscriptGlyph.crossbar), ink.copy(alpha = ink.alpha * fade), style = stroke)
        }
    }
}

/**
 * The comet's lap (`VoiceTranscriptButton.swift:135-187`), pure: over 1.3 s it stretches to about a
 * third of the outline, then its tail catches up.
 */
object TranscriptLap {
    const val PERIOD_SECONDS = 1.3

    /** Start and length (fractions of the outline) at [phase] 0…1. */
    fun comet(phase: Double): Pair<Double, Double> =
        if (phase < 0.5) {
            val u = phase / 0.5
            0.25 * u to 0.005 + 0.365 * u
        } else {
            val u = (phase - 0.5) / 0.5
            0.25 + 0.75 * u to 0.37 - 0.365 * u
        }

    /** The trimmed pieces to stroke: one, or two when the comet crosses the outline's start. */
    fun segments(start: Double, length: Double): List<Pair<Double, Double>> {
        val end = start + length
        return if (end > 1) listOf(start to 1.0, 0.0 to end - 1) else listOf(start to end)
    }
}

/** A comet lapping the button's outline; a still third of it under reduce motion. */
@Composable
private fun TranscriptLapRing(color: Color, animated: Boolean) {
    val seconds by produceState(0.0, animated) {
        if (!animated) return@produceState
        while (true) withFrameNanos { value = it / 1_000_000_000.0 }
    }
    val measure = remember { PathMeasure() }
    Canvas(Modifier.fillMaxSize()) {
        val inset = 0.75.dp.toPx()
        val radius = 8.25.dp.toPx() - inset
        val outline = Path().apply {
            addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(radius, radius)))
        }
        measure.setPath(outline, forceClosed = true)
        val length = measure.length
        val pieces = if (animated) {
            val (start, len) = TranscriptLap.comet((seconds % TranscriptLap.PERIOD_SECONDS) / TranscriptLap.PERIOD_SECONDS)
            TranscriptLap.segments(start, len)
        } else {
            listOf(0.0 to 0.33)
        }
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        for ((from, to) in pieces) {
            val piece = Path()
            if (measure.getSegment((from * length).toFloat(), (to * length).toFloat(), piece, startWithMoveTo = true)) {
                drawPath(piece, color, style = stroke)
            }
        }
    }
}
