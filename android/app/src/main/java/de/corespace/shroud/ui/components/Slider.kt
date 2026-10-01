package de.corespace.shroud.ui.components

import androidx.compose.ui.tooling.preview.Preview
import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A continuous slider drawn by the app (no Material `Slider`) — the look of the iOS system slider
 * (`MediaFilterStrip.swift:28`, filter intensity; conversation-compose-media §14): a 4 dp track,
 * filled in [tint] up to the value, and a 28 dp white thumb with a soft shadow.
 *
 * Human: Drag the thumb, or touch anywhere on the track to jump there and keep dragging. TalkBack
 * reads [label] and the value as a percentage and adjusts it with the volume keys or swipes.
 *
 * Agent: [value] is clamped into [range]; [onValueChange] gets values inside it; [onValueChangeFinished]
 * runs when a drag or tap ends. The control is 48 dp high (touch target) and as wide as its
 * modifier allows. [trackColor] is the unfilled track (iOS `systemFill`); pass a light one on the
 * always-dark media surfaces.
 */
// Parameter order is the binding signature of 00-plan §1.7.12 (modifier after it).
@SuppressLint("ModifierParameter")
@Composable
fun ShroudSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = ShroudTheme.colors.accent,
    trackColor: Color = SliderDefaults.trackColor(ShroudTheme.colors.isDark),
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val current = value.coerceIn(range.start, range.endInclusive)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val latest by rememberUpdatedState(current)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(SliderDefaults.TouchHeight)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(current, range)
                if (enabled) {
                    setProgress { target ->
                        val clamped = target.coerceIn(range.start, range.endInclusive)
                        if (clamped != latest) currentOnChange(clamped)
                        currentOnFinished?.invoke()
                        true
                    }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val thumbPx = with(density) { SliderDefaults.ThumbSize.toPx() }
        val widthPx = constraints.maxWidth.toFloat()
        val travelPx = (widthPx - thumbPx).coerceAtLeast(1f)
        val fraction = SliderMath.fraction(current, range)
        // Drawn left to right; `offset` and `CenterStart` mirror it under right-to-left. Touches
        // arrive unmirrored, so only the touch maths takes `rtl`.
        val thumbLeft = fraction * travelPx
        val drag = remember { SliderDrag() }
        val fromTouch: (Float) -> Float = { x -> SliderMath.valueAt(x - thumbPx / 2f, travelPx, range, rtl) }
        val gestures = if (enabled) {
            Modifier
                .pointerInput(range, travelPx, rtl) {
                    detectTapGestures(
                        onTap = { offset ->
                            currentOnChange(fromTouch(offset.x))
                            currentOnFinished?.invoke()
                        },
                    )
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = (drag.value + SliderMath.valueDelta(delta, travelPx, range, rtl))
                            .coerceIn(range.start, range.endInclusive)
                        if (next != drag.value) {
                            drag.value = next
                            currentOnChange(next)
                        }
                    },
                    onDragStarted = { start ->
                        drag.value = latest
                        // Away from the thumb, the thumb jumps under the finger first.
                        val thumbCenter = SliderMath.touchX(SliderMath.fraction(latest, range), travelPx, rtl) + thumbPx / 2f
                        if (abs(start.x - thumbCenter) > thumbPx) {
                            drag.value = fromTouch(start.x)
                            currentOnChange(drag.value)
                        }
                    },
                    onDragStopped = { currentOnFinished?.invoke() },
                )
        } else {
            Modifier
        }
        Box(Modifier.matchParentSize().then(gestures))
        // Track: between the thumb's centres at both ends.
        val trackShape = RoundedCornerShape(SliderDefaults.TrackHeight / 2)
        Box(
            Modifier
                .padding(horizontal = SliderDefaults.ThumbSize / 2)
                .fillMaxWidth()
                .height(SliderDefaults.TrackHeight)
                .clip(trackShape)
                .background(trackColor),
        )
        Box(
            Modifier
                .offset { IntOffset((thumbPx / 2f).roundToInt(), 0) }
                .size(with(density) { thumbLeft.toDp() }, SliderDefaults.TrackHeight)
                .clip(trackShape)
                .background(if (enabled) tint else tint.copy(alpha = 0.45f)),
        )
        Box(
            Modifier
                .offset { IntOffset(thumbLeft.roundToInt(), 0) }
                .size(SliderDefaults.ThumbSize)
                .dropShadow(CircleShape, Shadow(radius = 8.dp, color = Color(0x26000000), offset = DpOffset(0.dp, 3.dp)))
                .clip(CircleShape)
                .background(Color.White)
                .border(0.5.dp, Color(0x0A000000), CircleShape),
        )
    }
}

/** The value a running drag has reached (ahead of recomposition). */
private class SliderDrag {
    var value = 0f
}

/** Sizes and colours of the iOS system slider. */
object SliderDefaults {
    val TrackHeight = 4.dp
    val ThumbSize = 28.dp
    val TouchHeight = 48.dp

    /** iOS `systemFill`-like unfilled track: #787880 at 20 % (light) / 36 % (dark). */
    fun trackColor(dark: Boolean): Color = if (dark) Color(0x5C787880) else Color(0x33787880)
}

/** Value ↔ position, pure (px). */
internal object SliderMath {
    fun fraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
        val span = range.endInclusive - range.start
        if (span <= 0f) return 0f
        return ((value - range.start) / span).coerceIn(0f, 1f)
    }

    /** Left edge of the thumb in touch (unmirrored) coordinates for [fraction] along [travelPx]. */
    fun touchX(fraction: Float, travelPx: Float, rtl: Boolean): Float =
        (if (rtl) 1f - fraction else fraction) * travelPx

    /** The value whose thumb's left edge is at [thumbLeftPx]. */
    fun valueAt(thumbLeftPx: Float, travelPx: Float, range: ClosedFloatingPointRange<Float>, rtl: Boolean): Float {
        val raw = (thumbLeftPx / travelPx).coerceIn(0f, 1f)
        val fraction = if (rtl) 1f - raw else raw
        return range.start + fraction * (range.endInclusive - range.start)
    }

    /** The value change of a horizontal drag of [deltaPx]. */
    fun valueDelta(deltaPx: Float, travelPx: Float, range: ClosedFloatingPointRange<Float>, rtl: Boolean): Float {
        val span = range.endInclusive - range.start
        val step = deltaPx / travelPx * span
        return if (rtl) -step else step
    }
}

@Preview(name = "Slider", widthDp = 412)
@Composable
private fun SliderPreview() {
    ShroudTheme(dark = false) {
        Box(Modifier.background(ShroudTheme.colors.background).padding(20.dp)) {
            ShroudSlider(value = 0.6f, onValueChange = {}, label = "Filter intensity")
        }
    }
}
