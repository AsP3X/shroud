package de.corespace.shroud.ui.conversation.links

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max

/** The top-trailing area text keeps clear of, in dp. [Zero] for none. */
@Immutable
data class Cutout(val width: Dp, val height: Dp) {
    val isZero: Boolean get() = width <= 0.dp || height <= 0.dp

    companion object {
        val Zero = Cutout(0.dp, 0.dp)
    }
}

/**
 * Text that flows around a top-trailing rectangle — the Android stand-in for iOS's TextKit exclusion
 * path (`LinkPreviewView.swift:193-326`, `CutoutTextView`; conversation-thread §6.2, decision D4).
 * Compose text has no exclusion paths, so it lays out in two passes: at `width − cutout.width` to find
 * the lines whose top lies beside the cutout, then the rest of the text at the full width under them,
 * keeping [maxLines] in total with a tail ellipsis.
 *
 * Measuring hugs the widest line (plus the cutout for the lines beside it, at least the cutout's
 * width), like iOS's `fittingSize(forWidth:)`; unbounded, every paragraph is one line — the block's
 * ideal width.
 */
@Stable
class CutoutTextMeasure internal constructor(
    private val measurer: TextMeasurer,
    private val density: Density,
    val text: AnnotatedString,
    style: TextStyle,
    val cutout: Cutout,
    val maxLines: Int,
    /** Gap between the two passes: the line spacing the trimmed lines lost. */
    private val lineGap: Dp,
) {
    private val style = style.copy(lineBreak = LineBreak.Simple)

    class Geometry(
        val beside: TextLayoutResult,
        val below: TextLayoutResult?,
        /** y of [below] within the box. */
        val belowTop: Float,
        val width: Int,
        val height: Int,
    )

    private val cutoutWidth = with(density) { cutout.width.toPx() }
    private val cutoutHeight = with(density) { cutout.height.toPx() }

    fun at(maxWidth: Int): Geometry {
        val bounded = maxWidth != Constraints.Infinity
        if (cutout.isZero) {
            val layout = layout(text, if (bounded) maxWidth else Constraints.Infinity, maxLines)
            return Geometry(layout, null, 0f, ceil(widest(layout, 0f)).toInt(), layout.size.height)
        }
        val narrow = if (bounded) max(0, maxWidth - ceil(cutoutWidth).toInt()) else Constraints.Infinity
        val all = layout(text, narrow, maxLines)
        val split = CutoutMath.besideCount(List(all.lineCount) { all.getLineTop(it) }, cutoutHeight)
        if (all.lineCount <= split || split == 0) {
            val width = max(widest(all, cutoutWidth), cutoutWidth)
            return Geometry(all, null, 0f, ceil(width).toInt(), all.size.height)
        }
        val end = all.getLineEnd(split - 1)
        val head = CutoutMath.headEnd(text.text, end)
        val beside = layout(text.subSequence(0, head), narrow, split)
        val rest = text.subSequence(end, text.length)
        val below = layout(rest, if (bounded) maxWidth else Constraints.Infinity, max(1, maxLines - split))
        val gap = with(density) { lineGap.toPx() }
        val belowTop = beside.size.height + gap
        val width = max(max(widest(beside, cutoutWidth), widest(below, 0f)), cutoutWidth)
        return Geometry(beside, below, belowTop, ceil(width).toInt(), ceil(belowTop + below.size.height).toInt())
    }

    private fun layout(string: AnnotatedString, width: Int, lines: Int): TextLayoutResult =
        measurer.measure(
            text = string,
            style = style,
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            maxLines = lines,
            constraints = Constraints(maxWidth = width),
            density = density,
        )

    private fun widest(layout: TextLayoutResult, extra: Float): Float {
        var widest = 0f
        for (line in 0 until layout.lineCount) widest = max(widest, layout.getLineRight(line) - layout.getLineLeft(line) + extra)
        return widest
    }
}

/** The pure parts of the two-pass layout, unit-tested. */
object CutoutMath {
    /** How many lines lie beside a cutout of [cutoutHeight]: those whose top is above its bottom (`:302`). */
    fun besideCount(lineTops: List<Float>, cutoutHeight: Float): Int = lineTops.count { it < cutoutHeight }

    /** The end of the first pass's text at [lineEnd]: a paragraph break ending the line stays out of it. */
    fun headEnd(text: String, lineEnd: Int): Int {
        var end = lineEnd.coerceIn(0, text.length)
        while (end > 0 && (text[end - 1] == '\n' || text[end - 1] == ' ')) end--
        return end
    }
}

@Composable
fun rememberCutoutTextMeasure(text: AnnotatedString, style: TextStyle, cutout: Cutout, maxLines: Int, lineGap: Dp): CutoutTextMeasure {
    val measurer = rememberTextMeasurer(cacheSize = 6)
    val density = LocalDensity.current
    return remember(measurer, density, text, style, cutout, maxLines, lineGap) {
        CutoutTextMeasure(measurer, density, text, style, cutout, maxLines, lineGap)
    }
}

@Stable
private class CutoutDrawState {
    var geometry: CutoutTextMeasure.Geometry? by mutableStateOf(null)
    var rtl: Boolean by mutableStateOf(false)
}

/**
 * Draws [measure]'s text around its cutout, at least [minHeight] tall. Not interactive: taps belong to
 * the block around it.
 */
@Composable
fun CutoutText(measure: CutoutTextMeasure, modifier: Modifier = Modifier, minHeight: Dp = 0.dp) {
    val state = remember { CutoutDrawState() }
    val policy = remember(measure, minHeight) { CutoutPolicy(measure, state, minHeight) }
    Layout(
        content = {},
        modifier = modifier.drawBehind {
            val geometry = state.geometry ?: return@drawBehind
            // The first pass sits beside the cutout, on the start side: right-aligned when the text
            // runs right to left (the cutout is then on the left).
            val beside = geometry.beside
            drawText(beside, topLeft = Offset(if (state.rtl) size.width - beside.size.width else 0f, 0f))
            geometry.below?.let { below ->
                drawText(below, topLeft = Offset(if (state.rtl) size.width - below.size.width else 0f, geometry.belowTop))
            }
        },
        measurePolicy = policy,
    )
}

private class CutoutPolicy(
    private val measure: CutoutTextMeasure,
    private val state: CutoutDrawState,
    private val minHeight: Dp,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val offered = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val hug = measure.at(offered)
        val width = if (constraints.hasBoundedWidth) hug.width.coerceIn(constraints.minWidth, constraints.maxWidth) else hug.width
        val geometry = if (width == offered) hug else measure.at(width)
        val height = max(geometry.height, minHeight.roundToPx()).coerceIn(constraints.minHeight, constraints.maxHeight)
        state.geometry = geometry
        state.rtl = layoutDirection == LayoutDirection.Rtl
        return layout(width, height) {}
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measure.at(Constraints.Infinity).width

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measure.cutout.width.roundToPx()

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        max(measure.at(width).height, minHeight.roundToPx())

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        max(measure.at(width).height, minHeight.roundToPx())
}
