package de.corespace.shroud.ui.conversation.gestures

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometry and thresholds of swipe to reply, matching Telegram (`SwipeToReplyMetrics`,
 * `SwipeToReply.swift:4-35`; conversation-thread §15.1). Pure; dp.
 */
object SwipeToReplyMetrics {
    /** How far an incoming bubble travels before the gesture arms. */
    const val INCOMING_THRESHOLD = 45f

    /** Outgoing bubbles start further from the right edge, so they ask for a longer pull. */
    const val OUTGOING_THRESHOLD = 60f

    /** Past the threshold the row keeps moving, with progressively more resistance. */
    const val BAND_RANGE = 100f
    const val BAND_COEFFICIENT = 0.4f

    /** Hard stop, so a long drag never pushes the bubble off screen. */
    const val MAX_TRAVEL = 180f
    const val ICON_SIDE = 33f

    /** Row trailing edge → icon centre at rest (incoming rows). */
    const val INCOMING_ICON_INSET = 8.5f

    /** Outgoing rows keep the icon further out, where the bubble was before it moved. */
    const val OUTGOING_ICON_INSET = 42.5f

    /** Movement (dp) before the recogniser decides (`SwipeToReplyGestureRecognizer`, `:72-87`). */
    const val DECISION_DISTANCE = 2f

    fun threshold(isMine: Boolean): Float = if (isMine) OUTGOING_THRESHOLD else INCOMING_THRESHOLD

    fun iconInset(isMine: Boolean): Float = if (isMine) OUTGOING_ICON_INSET else INCOMING_ICON_INSET

    /** Rubber banding past the threshold — 1:1 until then, asymptotic after (`:30-35`). */
    fun banded(distance: Float, threshold: Float): Float {
        if (distance <= threshold) return max(0f, distance)
        val beyond = distance - threshold
        val eased = (1f - (1f / ((beyond * BAND_COEFFICIENT / BAND_RANGE) + 1f))) * BAND_RANGE
        return min(MAX_TRAVEL, threshold + eased)
    }

    /**
     * The recogniser's call on a movement of ([dx], [dy]) dp from the touch-down (`:66-87`):
     * rightward belongs to back navigation, mostly vertical to the list's scroll; a clearly leftward
     * movement starts the swipe; anything smaller waits.
     */
    fun decide(dx: Float, dy: Float): Decision = when {
        dx > DECISION_DISTANCE -> Decision.Fail
        abs(dy) > DECISION_DISTANCE && abs(dy) > abs(dx) * 2 -> Decision.Fail
        abs(dx) > DECISION_DISTANCE && abs(dy) * 2 < abs(dx) -> Decision.Begin
        else -> Decision.Wait
    }

    enum class Decision { Wait, Begin, Fail }
}

/**
 * Telegram's swipe-to-reply for one thread row (`SwipeToReplyModifier`, `SwipeToReply.swift:209-284`;
 * conversation-thread §15.2–§15.3).
 *
 * Human: the row follows the finger 1:1 to the threshold and then rubber-bands; a ring fills around
 * the reply glyph, and a heavy haptic marks the point where letting go actually replies (once per
 * crossing in, never on the way back). Releasing springs the row home whether or not it armed.
 * Becoming disabled mid-swipe (the menu opened, the message was deleted) settles without replying.
 *
 * Agent: CALLS [onReply] once, on release past the threshold (the raw translation decides, not the
 * banded one). Decides on the first 2 dp exactly as the iOS recogniser: right → fail (the system
 * back gesture), vertical → fail without consuming so the list scrolls, left → takes the drag and
 * consumes its horizontal movement. Marks [press] as swiping so a hold can't open the menu under it.
 * TalkBack's "Reply" is a row action (`BubbleContext.accessibilityActions`). The swipe owns its own
 * offset state, so a drag never recomposes the thread.
 */
@Composable
fun SwipeToReplyRow(
    enabled: Boolean,
    isMine: Boolean,
    press: RowPress,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var progress by remember { mutableStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    var swiping by remember { mutableStateOf(false) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnReply by rememberUpdatedState(onReply)
    val threshold = SwipeToReplyMetrics.threshold(isMine)

    fun settle(triggerReply: Boolean) {
        val spec = Motion.respecting(reduceMotion, Motion.snappy<Float>())
        scope.launch { offset.animateTo(0f, spec) }
        progress = 0f
        armed = false
        swiping = false
        if (triggerReply) currentOnReply()
    }

    // A row that stops being swipeable mid-gesture must not stay parked off to the left (`:241-245`).
    LaunchedEffect(enabled) {
        if (!enabled && swiping) settle(triggerReply = false)
    }

    Box(
        modifier.pointerInput(isMine) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentEnabled) return@awaitEachGesture
                // Before deciding: observe only, consume nothing.
                var decided = false
                while (!decided) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed || change.isConsumed || event.changes.count { it.pressed } > 1) return@awaitEachGesture
                    val delta = change.position - down.position
                    when (SwipeToReplyMetrics.decide(delta.x.toDp().value, delta.y.toDp().value)) {
                        SwipeToReplyMetrics.Decision.Fail -> return@awaitEachGesture
                        SwipeToReplyMetrics.Decision.Begin -> decided = true
                        SwipeToReplyMetrics.Decision.Wait -> Unit
                    }
                }
                if (press.longPressed || !currentEnabled) return@awaitEachGesture
                press.swiping = true
                swiping = true
                var translation = 0f
                var cancelled = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !currentEnabled) {
                        cancelled = true
                        break
                    }
                    translation = (change.position.x - down.position.x).toDp().value
                    change.consume()
                    if (!change.pressed) break
                    val distance = SwipeToReplyMetrics.banded(-translation, threshold)
                    scope.launch { offset.snapTo(-distance) }
                    progress = min(1f, distance / threshold)
                    val nowArmed = -translation >= threshold
                    if (nowArmed != armed) {
                        armed = nowArmed
                        // Telegram fires once, on the way in — crossing back and forth stays quiet (`:262-263`).
                        if (nowArmed) haptic(Haptic.Heavy)
                    }
                }
                if (swiping) settle(triggerReply = !cancelled && -translation >= threshold)
            }
        },
    ) {
        Box(Modifier.offset { IntOffset(offset.value.dp.roundToPx(), 0) }) { content() }
        AnimatedVisibility(
            visible = swiping,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset {
                    val x = SwipeToReplyMetrics.iconInset(isMine) + SwipeToReplyMetrics.ICON_SIDE / 2 + offset.value
                    IntOffset(x.dp.roundToPx(), 0)
                },
            enter = fadeIn(Motion.respecting(reduceMotion, Motion.snappy())) +
                scaleIn(Motion.respecting(reduceMotion, Motion.snappy()), initialScale = 0.2f),
            exit = fadeOut(Motion.respecting(reduceMotion, Motion.snappy())) +
                scaleOut(Motion.respecting(reduceMotion, Motion.snappy()), targetScale = 0.2f),
        ) {
            SwipeReplyIcon(progress = progress, isArmed = armed)
        }
    }
}

/**
 * The circle that slides in from the trailing edge while a row is swiped (`SwipeReplyIcon`,
 * `SwipeToReply.swift:154-202`): 33 dp, `accentSoft` filling to `accent` once armed; a 2 dp progress
 * ring (accent @ 0.85) from 12 o'clock; the reply glyph (design Lucide `reply`) in accent, white
 * once armed, revealed with the pull. Pops to full size with `Motion.bouncy` on arming. Decorative.
 */
@Composable
fun SwipeReplyIcon(progress: Float, isArmed: Boolean, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val clamped = progress.coerceIn(0f, 1f)
    val reveal = min(1f, clamped * 1.2f)
    val restingScale = 0.65f + reveal * 0.35f
    val scale by animateFloatAsState(
        if (isArmed) 1f else restingScale,
        Motion.respecting(reduceMotion, Motion.bouncy()),
        label = "swipeReplyScale",
    )
    val ring = colors.accent.copy(alpha = if (isArmed) 0f else 0.85f)
    val fill = if (isArmed) colors.accent else colors.accentSoft
    Box(
        modifier
            .clearAndSetSemantics {}
            .size(SwipeToReplyMetrics.ICON_SIDE.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = min(1f, clamped * 2f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(SwipeToReplyMetrics.ICON_SIDE.dp)) {
            drawCircle(fill)
            // `.padding(1)` around a 2 dp stroke centred on the path (STR:174-180).
            val inset = 1.dp.toPx()
            drawArc(
                color = ring,
                startAngle = -90f,
                sweepAngle = 360f * clamped,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        ShroudIcon(
            ShroudIcons.Reply,
            (if (isArmed) Color.White else colors.accent).copy(alpha = reveal),
            size = 18.dp,
        )
    }
}
