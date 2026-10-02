package de.corespace.shroud.ui.conversation.reactions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Chips and the time at the foot of a reacted bubble (iOS `ReactionFooterLayout`,
 * `MessageReactionChips.swift:270-368`; conversation-thread §14.3): chips flow left to right and
 * wrap; the time (and ticks) sit at the trailing end of the last chip row when there is room, else on
 * a line of their own.
 *
 * Children are the chips followed by exactly one meta view (last). Given a bounded width it takes all
 * of it — wrapping at it, with the time pinned to its trailing edge — so the bubble decides the width;
 * its max intrinsic width is the one-line width the bubble measures "unproposed" (iOS
 * `LinkBubbleRole.footer`).
 */
@Composable
fun ReactionFooterLayout(
    modifier: Modifier = Modifier,
    spacing: Dp = 6.dp,
    rowSpacing: Dp = 6.dp,
    metaGap: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val policy = remember(spacing, rowSpacing, metaGap) { FooterPolicy(spacing, rowSpacing, metaGap) }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

private class FooterPolicy(private val spacing: Dp, private val rowSpacing: Dp, private val metaGap: Dp) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        if (measurables.isEmpty()) return layout(constraints.minWidth, constraints.minHeight) {}
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else null
        // A chip given the width wraps its emoji at it; otherwise it is one line (`:336-337`).
        val chipConstraints = Constraints(maxWidth = width ?: Constraints.Infinity)
        val chips = measurables.dropLast(1).map { it.measure(chipConstraints) }
        val meta = measurables.last().measure(Constraints())
        val arrangement = ReactionFooterMath.arrange(
            chips = chips.map { Size(it.width.toFloat(), it.height.toFloat()) },
            meta = Size(meta.width.toFloat(), meta.height.toFloat()),
            width = width?.toFloat(),
            spacing = spacing.toPx(),
            rowSpacing = rowSpacing.toPx(),
            metaGap = metaGap.toPx(),
        )
        // Fill an offered width: the time belongs at the bubble's trailing edge (`:348-354`).
        val laidWidth = max(ceil(arrangement.size.width).toInt(), width ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val laidHeight = ceil(arrangement.size.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(laidWidth, laidHeight) {
            // Relative placement: chips flow from the start edge, the time sits at the end (mirrored in RTL).
            chips.forEachIndexed { index, chip ->
                val frame = arrangement.frames[index]
                chip.placeRelative(frame.left.toInt(), frame.top.toInt())
            }
            val metaFrame = arrangement.frames.last()
            meta.placeRelative(laidWidth - meta.width, metaFrame.top.toInt())
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        oneLine(measurables).let { ceil(it.size.width).toInt() }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    private fun IntrinsicMeasureScope.oneLine(measurables: List<IntrinsicMeasurable>): ReactionFooterMath.Arrangement =
        arrangeIntrinsic(measurables, null)

    private fun IntrinsicMeasureScope.intrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        ceil(arrangeIntrinsic(measurables, if (width == Constraints.Infinity) null else width).size.height).toInt()

    private fun IntrinsicMeasureScope.arrangeIntrinsic(measurables: List<IntrinsicMeasurable>, width: Int?): ReactionFooterMath.Arrangement {
        if (measurables.isEmpty()) return ReactionFooterMath.Arrangement(emptyList(), Size.Zero)
        val chips = measurables.dropLast(1).map {
            val w = min(it.maxIntrinsicWidth(Constraints.Infinity), width ?: Int.MAX_VALUE)
            Size(w.toFloat(), it.maxIntrinsicHeight(w).toFloat())
        }
        val last = measurables.last()
        val metaWidth = last.maxIntrinsicWidth(Constraints.Infinity)
        val meta = Size(metaWidth.toFloat(), last.maxIntrinsicHeight(metaWidth).toFloat())
        return ReactionFooterMath.arrange(chips, meta, width?.toFloat(), spacing.toPx(), rowSpacing.toPx(), metaGap.toPx())
    }
}

/** The pure geometry of [ReactionFooterLayout] (`MessageReactionChips.swift:291-332`), unit-tested. */
object ReactionFooterMath {
    /**
     * Chip frames, then the meta frame. The meta's x assumes the hugged width; the layout pins it to
     * the trailing edge instead.
     */
    data class Arrangement(val frames: List<Rect>, val size: Size)

    fun arrange(
        chips: List<Size>,
        meta: Size,
        width: Float?,
        spacing: Float = 6f,
        rowSpacing: Float = 6f,
        metaGap: Float = 8f,
    ): Arrangement {
        val limit = width ?: Float.POSITIVE_INFINITY
        val frames = ArrayList<Rect>(chips.size + 1)
        var x = 0f
        var y = 0f
        var rowHeight = 0f
        var used = 0f
        for (chip in chips) {
            if (x > 0f && x + chip.width > limit) {
                y += rowHeight + rowSpacing
                x = 0f
                rowHeight = 0f
            }
            frames += Rect(Offset(x, y), chip)
            used = max(used, x + chip.width)
            x += chip.width + spacing
            rowHeight = max(rowHeight, chip.height)
        }

        val rowEnd = if (chips.isEmpty()) 0f else x - spacing
        val metaX = if (chips.isEmpty()) 0f else rowEnd + metaGap
        if (chips.isEmpty() || metaX + meta.width <= limit) {
            // Centred on the last chip row, a touch low like a plain bubble's time (`:321-323`).
            val rowBottom = y + max(rowHeight, meta.height)
            val metaY = min(rowBottom - meta.height, y + (max(rowHeight, meta.height) - meta.height) / 2 + 2)
            frames += Rect(Offset(metaX, metaY), meta)
            used = max(used, metaX + meta.width)
            return Arrangement(frames, Size(used, rowBottom))
        }
        val metaY = y + rowHeight + rowSpacing
        frames += Rect(Offset(0f, metaY), meta)
        used = max(used, meta.width)
        return Arrangement(frames, Size(used, metaY + meta.height))
    }
}
