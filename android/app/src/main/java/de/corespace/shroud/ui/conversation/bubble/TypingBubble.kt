package de.corespace.shroud.ui.conversation.bubble

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.ui.components.RecordingWave
import de.corespace.shroud.ui.components.TypingWave
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.MotionTransition
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * The peer's message taking shape — iOS `TypingIndicatorBubble` (`TypingIndicatorBubble.swift:41-86`;
 * conversation-thread §12.2; design *Conversation — Typing*). Three ink dots swell and rise in turn
 * inside an incoming bubble; drawn through a blur and a steep alpha curve (a metaball), so a swelling
 * dot pulls a liquid bridge from its neighbour. While the peer records a voice note the same bubble
 * shows the recorder's look instead: a blinking red dot beside a live level meter. A switch between
 * the two shrinks the old glyph away as the new one grows in (scale 0.6 + fade, `Motion.snappy`; a fade
 * under reduce motion).
 *
 * The thread (W3-THREAD-LIST) shows and hides it with [TypingBubbleTransition] (it grows out of its
 * tail corner). The metaball needs `RenderEffect` (API 31+); on API 30 the dots are plain (no bridges,
 * conversation-thread §23.7). Under reduce motion everything holds still. TalkBack: "Typing" /
 * "Recording a voice message".
 */
@Composable
fun TypingIndicatorBubble(activity: ChatPeerActivity, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val shape = BubbleShapes.tail(isMine = false)
    Box(modifier.fillMaxWidth().padding(end = MessageBubbleMetrics.oppositeGutter), contentAlignment = Alignment.BottomStart) {
        Box(
            Modifier
                .dropShadow(shape, Shadow(radius = 3.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.04f))
                .clip(shape)
                .background(colors.bubbleIncoming)
                .clearAndSetSemantics { contentDescription = activity.bubbleLabel }
                .padding(start = 13.dp, end = 13.dp, top = 8.dp, bottom = 9.dp),
        ) {
            AnimatedContent(
                targetState = activity,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(Motion.reduced()) togetherWith fadeOut(Motion.reduced())
                    } else {
                        val spec: FiniteAnimationSpec<Float> = Motion.snappy()
                        (scaleIn(spec, initialScale = 0.6f) + fadeIn(spec)) togetherWith (scaleOut(spec, targetScale = 0.6f) + fadeOut(spec))
                    }
                },
                contentAlignment = Alignment.Center,
                label = "typingActivity",
            ) { shown ->
                Box(Modifier.size(TypingInkMetrics.width, TypingInkMetrics.height)) {
                    when (shown) {
                        ChatPeerActivity.Typing -> TypingInk(animated = !reduceMotion)
                        ChatPeerActivity.Recording -> RecordingInk(animated = !reduceMotion)
                    }
                }
            }
        }
    }
}

/**
 * How the typing bubble arrives and leaves: scale 0.4 out of its bottom-leading (tail) corner with a
 * fade, or a plain fade under reduce motion (`TypingIndicatorBubble.swift:79-82`).
 */
object TypingBubbleTransition {
    fun of(reduceMotion: Boolean): MotionTransition {
        if (reduceMotion) return Motion.reducedTransition
        val tail = TransformOrigin(0f, 1f)
        val spec: FiniteAnimationSpec<Float> = Motion.snappy()
        return MotionTransition(
            enter = scaleIn(spec, initialScale = 0.4f, transformOrigin = tail) + fadeIn(spec),
            exit = scaleOut(spec, targetScale = 0.4f, transformOrigin = tail) + fadeOut(spec),
        )
    }
}

/** The ink's 40×20 box, the web client's SVG (`TypingIndicatorBubble.swift:147-153`). */
internal object TypingInkMetrics {
    val width = 40.dp
    val height = 20.dp
    val centers = floatArrayOf(9f, 20f, 31f)
    const val BASELINE = 11f
    const val RADIUS = 3.6f

    /** Dot radius and centre y (in the 40×20 box's units) at [crest] 0…1; null crest = still (`:195-201`). */
    fun dot(crest: Double?): Pair<Float, Float> {
        val scale = crest?.let { 0.72 + 0.54 * it } ?: 1.0
        return (RADIUS * scale).toFloat() to (BASELINE - 4 * (crest ?: 0.0)).toFloat()
    }

    /** Alpha threshold of the metaball (`.alphaThreshold(min: 0.42)`). */
    const val THRESHOLD = 0.42f

    /** Steepness of the threshold ramp in the colour matrix. */
    const val STEEPNESS = 40f

    /**
     * The colour matrix that thresholds a blurred silhouette's alpha at [THRESHOLD] and paints it in
     * [color] (memory *SwiftUI metaball colour fringe*: threshold the alpha, colour on top).
     */
    fun thresholdMatrix(color: Color): FloatArray = floatArrayOf(
        0f, 0f, 0f, 0f, color.red * 255f,
        0f, 0f, 0f, 0f, color.green * 255f,
        0f, 0f, 0f, 0f, color.blue * 255f,
        0f, 0f, 0f, STEEPNESS, -STEEPNESS * THRESHOLD * 255f,
    )
}

/** Seconds on the frame clock while composed; null under reduce motion (everything still). */
@Composable
private fun frameSeconds(animated: Boolean): State<Double?> = produceState<Double?>(null, animated) {
    if (!animated) {
        value = null
        return@produceState
    }
    while (true) withFrameNanos { value = it / 1_000_000_000.0 }
}

/**
 * The three ink dots (`TypingIndicatorBubble.swift:147-202`): the silhouette (blurred dots through an
 * alpha threshold, in 42 % accent over the bubble) and, on top, each cresting dot in accent at its crest.
 */
@Composable
private fun TypingInk(animated: Boolean) {
    val colors = ShroudTheme.colors
    val time = frameSeconds(animated)
    // The web's `color-mix(in srgb, accent 42%, bubble)` (`:167-168`); still: plain accent.
    val rest = if (animated) lerp(colors.accent, colors.bubbleIncoming, 0.58f) else colors.accent
    val metaball = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(Modifier.fillMaxSize()) {
        // The silhouette layer: dots, blurred, thresholded and recoloured by the layer.
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (metaball) Modifier.metaballLayer(rest) else Modifier),
        ) {
            val unit = size.width / 40f
            val t = time.value
            for (index in 0 until 3) {
                val crest = t?.let { TypingWave.crest(it, index) }
                val (r, y) = TypingInkMetrics.dot(crest)
                // iOS fills the silhouette black; the threshold matrix repaints it in [rest] from the
                // alpha alone, so filling it in [rest] already draws the same — and keeps the dots
                // coloured wherever the layer's effect is not applied (a software-drawn capture).
                drawCircle(rest, radius = r * unit, center = Offset(TypingInkMetrics.centers[index] * unit, y * unit))
            }
        }
        // The ink: each dot darkens toward the accent as it crests — after the threshold, since
        // colour blurred through it leaves fringes (`:184-191`).
        if (animated) {
            Canvas(Modifier.fillMaxSize()) {
                val unit = size.width / 40f
                val t = time.value ?: return@Canvas
                for (index in 0 until 3) {
                    val crest = TypingWave.crest(t, index)
                    if (crest <= 0.0) continue
                    val (r, y) = TypingInkMetrics.dot(crest)
                    drawCircle(
                        colors.accent.copy(alpha = crest.toFloat().coerceIn(0f, 1f)),
                        radius = (r + 0.3f) * unit,
                        center = Offset(TypingInkMetrics.centers[index] * unit, y * unit),
                    )
                }
            }
        }
    }
}

/** Blur 2 dp, then the alpha threshold in [color] (API 31+). */
private fun Modifier.metaballLayer(color: Color): Modifier = graphicsLayer {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val radius = 2.dp.toPx()
        val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
        val threshold = ColorMatrixColorFilter(ColorMatrix(TypingInkMetrics.thresholdMatrix(color)))
        renderEffect = RenderEffect.createColorFilterEffect(threshold, blur).asComposeRenderEffect()
    }
}

/**
 * The recording glyph in the same 40×20 box (`TypingIndicatorBubble.swift:271-309`): the recorder's
 * red dot breathing beside five bars of a level meter, centred.
 */
@Composable
private fun RecordingInk(animated: Boolean) {
    val colors = ShroudTheme.colors
    val time = frameSeconds(animated)
    Canvas(Modifier.fillMaxSize()) {
        val t = time.value
        val dot = 6.dp.toPx()
        val bar = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val tallest = 16.dp.toPx()
        val count = RecordingWave.bars.size
        val width = dot + 5.dp.toPx() + count * bar + (count - 1) * gap
        var x = (size.width - width) / 2f
        val midY = size.height / 2f
        drawCircle(
            colors.danger.copy(alpha = RecordingWave.blink(t).toFloat().coerceIn(0f, 1f)),
            radius = dot / 2f,
            center = Offset(x + dot / 2f, midY),
        )
        x += dot + 5.dp.toPx()
        for (index in 0 until count) {
            val height = (tallest * RecordingWave.level(t, index)).toFloat()
            drawRoundRect(colors.accent, topLeft = Offset(x, midY - height / 2f), size = Size(bar, height), cornerRadius = CornerRadius(bar / 2f))
            x += bar + gap
        }
    }
}
