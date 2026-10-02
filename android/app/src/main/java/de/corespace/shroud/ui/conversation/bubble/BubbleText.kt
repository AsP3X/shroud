package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.ui.conversation.links.MessageLinkText
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** The time a body keeps room for at the end of its last line (`MessageBubbleView.swift:499-504`). */
@Immutable
data class MetaSpec(val time: String, val showsReceipt: Boolean)

/** Padding around a bubble's text, start/top/end/bottom. */
@Immutable
data class TextPadding(val start: Dp = 0.dp, val top: Dp = 0.dp, val end: Dp = 0.dp, val bottom: Dp = 0.dp)

/**
 * Measures a bubble's body the way iOS lays it out (decision D3): wrapped at a width, hugging its
 * longest line rather than snapping to the width (`MessageBubbleView.swift:596-622`), and — when
 * [meta] is set — keeping room on the last line for the time drawn over its end. iOS appends invisible
 * meta-font zeros (`MessageBubbleMetrics.metaReservation`, `:177-191`); Android reserves the same width
 * arithmetically ([MessageBubbleMetrics.reservationDigits]): it sits on the last line when it fits,
 * otherwise the body grows by one meta line, exactly as the iOS text engine breaks the reservation.
 *
 * Shared by the body's own layout and the bubble layouts that need its hugging width before they
 * measure ([hugWidth], iOS `sizeThatFits(width: cap)`). Greedy line breaking ([LineBreak.Simple]) so a
 * width at or above the longest line keeps every break.
 */
@Stable
class BubbleTextMeasure internal constructor(
    private val measurer: TextMeasurer,
    private val density: Density,
    val text: AnnotatedString,
    style: TextStyle,
    val padding: TextPadding,
    val meta: MetaSpec?,
    private val metaStyle: TextStyle,
) {
    val style: TextStyle = style.copy(lineBreak = LineBreak.Simple)

    /** Where the body sits in its box and how big the box is. */
    class Geometry(
        val layout: TextLayoutResult,
        val width: Int,
        val height: Int,
        /** The meta shares the last line (false: it got a line of its own). */
        val metaFits: Boolean,
    )

    private class Reservation(val width: Float, val wrappedWidth: Float, val extraLine: Float, val metaWidth: Float)

    private val reservation: Reservation? by lazy { meta?.let { reservationFor(it) } }

    private val paddingStart = with(density) { padding.start.roundToPx() }
    private val paddingEnd = with(density) { padding.end.roundToPx() }
    private val paddingTop = with(density) { padding.top.roundToPx() }
    private val paddingBottom = with(density) { padding.bottom.roundToPx() }
    val horizontalPadding: Int get() = paddingStart + paddingEnd

    /** Width of the meta row in px as iOS measures it (`metaWidth`, `:171-175`), 0 without a meta. */
    val metaWidth: Float get() = reservation?.metaWidth ?: 0f

    /** The body laid out at [maxWidth] px (padding included); unbounded = one line per paragraph. */
    fun at(maxWidth: Int): Geometry {
        val textMax = if (maxWidth == Constraints.Infinity) Constraints.Infinity else max(0, maxWidth - horizontalPadding)
        val layout = layoutAt(textMax)
        var longest = 0f
        for (line in 0 until layout.lineCount) longest = max(longest, lineWidth(layout, line))
        val last = lineWidth(layout, layout.lineCount - 1)
        val reserve = reservation
        var textWidth = longest
        var extra = 0f
        var fits = true
        if (reserve != null) {
            val limit = if (textMax == Constraints.Infinity) Float.POSITIVE_INFINITY else textMax.toFloat()
            fits = MessageBubbleMetrics.metaFitsOnLastLine(last, reserve.width, limit)
            textWidth = if (fits) max(longest, last + reserve.width) else max(longest, reserve.wrappedWidth)
            if (!fits) extra = reserve.extraLine
        }
        val width = ceil(textWidth).toInt() + horizontalPadding
        val height = layout.size.height + ceil(extra).toInt() + paddingTop + paddingBottom
        return Geometry(layout, width, height, fits)
    }

    /** The width the body hugs when it may wrap at [maxWidth] px, padding included. */
    fun hugWidth(maxWidth: Int): Int = at(maxWidth).width

    /** The body on one line, no wrapping (the compact bubble, `:572-592`). */
    fun singleLine(): TextLayoutResult = layoutAt(Constraints.Infinity)

    /** One-line width of the body alone, without padding or reservation. */
    fun singleLineTextWidth(): Float = singleLine().multiParagraph.maxIntrinsicWidth

    /** Where the body's top-left goes inside its box for [direction]. */
    fun textOrigin(direction: LayoutDirection): Offset =
        Offset((if (direction == LayoutDirection.Ltr) paddingStart else paddingEnd).toFloat(), paddingTop.toFloat())

    private fun layoutAt(textMax: Int): TextLayoutResult =
        measurer.measure(
            text = text,
            style = style,
            softWrap = true,
            constraints = Constraints(maxWidth = textMax),
            density = density,
        )

    private fun reservationFor(spec: MetaSpec): Reservation = with(density) {
        val timeWidth = intrinsic(spec.time)
        val metaWidth = MessageBubbleMetrics.metaWidth(
            timeWidth = timeWidth,
            showsReceipt = spec.showsReceipt,
            metaSpacing = MessageBubbleMetrics.metaSpacing.toPx(),
            tickWidth = MessageBubbleMetrics.tickWidth.toPx(),
        )
        val digit = intrinsic("0")
        val space = intrinsic("0 0") - intrinsic("00")
        val digits = MessageBubbleMetrics.reservationDigits(
            metaWidth = metaWidth,
            digitWidth = digit,
            padDifference = (MessageBubbleMetrics.textTrailingPad - MessageBubbleMetrics.metaTrailingPad).toPx(),
            metaGap = MessageBubbleMetrics.metaGap.toPx(),
        )
        Reservation(
            width = MessageBubbleMetrics.reservationWidth(digits, digit, space),
            wrappedWidth = MessageBubbleMetrics.reservationWrappedWidth(digits, digit),
            extraLine = MessageBubbleMetrics.META_LINE_HEIGHT.sp.toPx(),
            metaWidth = metaWidth,
        )
    }

    private fun intrinsic(string: String): Float =
        measurer.measure(AnnotatedString(string), metaStyle, softWrap = false, density = density).multiParagraph.maxIntrinsicWidth

    private fun lineWidth(layout: TextLayoutResult, line: Int): Float = layout.getLineRight(line) - layout.getLineLeft(line)
}

/** A [BubbleTextMeasure] for [text] in [style], remembered while they stay the same. */
@Composable
fun rememberBubbleTextMeasure(
    text: AnnotatedString,
    style: TextStyle,
    padding: TextPadding,
    meta: MetaSpec?,
    metaStyle: TextStyle = MessageBubbleMetrics.metaStyle,
): BubbleTextMeasure {
    val measurer = rememberTextMeasurer(cacheSize = 6)
    val density = LocalDensity.current
    return remember(measurer, density, text, style, padding, meta, metaStyle) {
        BubbleTextMeasure(measurer, density, text, style, padding, meta, metaStyle)
    }
}

/** The laid-out text a bubble draws and hit-tests, written during measure and read in draw. */
@Stable
internal class TextDrawState {
    var layout: TextLayoutResult? by mutableStateOf(null)
    var origin: Offset by mutableStateOf(Offset.Zero)
}

/** Draws [state]'s text at its origin. */
internal fun Modifier.drawBubbleText(state: TextDrawState): Modifier = drawBehind {
    val layout = state.layout ?: return@drawBehind
    drawText(layout, topLeft = state.origin)
}

/**
 * Taps on the link runs of [text]: a tap that starts and ends on a link opens it through [onLink] and
 * consumes the release, so the row's own taps stay out of it. Anything else passes untouched — the
 * down is never consumed, so a scroll or the row's long press starting on the text still works
 * (memory: *LongPress onChanged blocks scroll*).
 */
internal fun Modifier.bubbleLinkTaps(state: TextDrawState, text: AnnotatedString, onLink: ((String) -> Unit)?): Modifier {
    if (onLink == null || text.getStringAnnotations(MessageLinkText.URL_TAG, 0, text.length).isEmpty()) return this
    return this.then(
        Modifier.pointerInput(text) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val url = urlAt(state, text, down.position) ?: return@awaitEachGesture
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                if (urlAt(state, text, up.position) != url) return@awaitEachGesture
                up.consume()
                onLink(url)
            }
        },
    )
}

/** The link under [position], only when the finger is on the line's glyphs (not the blank after it). */
internal fun urlAt(state: TextDrawState, text: AnnotatedString, position: Offset): String? {
    val layout = state.layout ?: return null
    val local = position - state.origin
    if (local.y < 0f || local.y > layout.size.height) return null
    val line = layout.getLineForVerticalPosition(local.y)
    if (local.x < layout.getLineLeft(line) || local.x > layout.getLineRight(line)) return null
    val offset = layout.getOffsetForPosition(local)
    // The offset is the caret position nearest the finger: the character under it is either side.
    return MessageLinkText.urlAt(text, offset) ?: if (offset > 0) MessageLinkText.urlAt(text, offset - 1) else null
}

/**
 * A bubble body: [measure]'s text, and — when [meta] is given — the time drawn at the bottom-trailing
 * corner over the room reserved for it (iOS's `ZStack(alignment: .bottomTrailing) { Text(body +
 * reservation); metaRow }`, `MessageBubbleView.swift:602-617`). Takes the width it is offered,
 * hugging the text within it; a parent that already chose the bubble width passes it exactly.
 */
@Composable
fun BubbleText(
    measure: BubbleTextMeasure,
    modifier: Modifier = Modifier,
    onLink: ((String) -> Unit)? = null,
    metaEndPadding: Dp = MessageBubbleMetrics.metaTrailingPad,
    metaBottomPadding: Dp = 5.dp,
    meta: (@Composable () -> Unit)? = null,
) {
    val state = remember { TextDrawState() }
    val link by rememberUpdatedState(onLink)
    val policy = remember(measure, metaEndPadding, metaBottomPadding) { BubbleTextPolicy(measure, state, metaEndPadding, metaBottomPadding) }
    Layout(
        content = { meta?.invoke() },
        modifier = modifier
            .bubbleLinkTaps(state, measure.text, if (onLink == null) null else { url -> link?.invoke(url) })
            .drawBubbleText(state),
        measurePolicy = policy,
    )
}

private class BubbleTextPolicy(
    private val measure: BubbleTextMeasure,
    private val state: TextDrawState,
    private val metaEnd: Dp,
    private val metaBottom: Dp,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val offered = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val hug = measure.at(offered)
        val width = if (constraints.hasBoundedWidth) hug.width.coerceIn(constraints.minWidth, constraints.maxWidth) else hug.width
        // Laid out again at the final width so a right-to-left paragraph aligns to the box it is in.
        val geometry = if (width == offered) hug else measure.at(width)
        val height = geometry.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        state.layout = geometry.layout
        state.origin = measure.textOrigin(layoutDirection)
        val meta = measurables.firstOrNull()?.measure(Constraints())
        return layout(width, height) {
            if (meta != null) {
                val end = metaEnd.roundToPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) width - end - meta.width else end
                meta.place(x, height - metaBottom.roundToPx() - meta.height)
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measure.at(Constraints.Infinity).width

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measure.horizontalPadding

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measure.at(width).height

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measure.at(width).height
}

/** Rounds a px float the way the layouts do. */
internal fun Float.px(): Int = roundToInt()
