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

/**
 * A chip's emoji, left to right, wrapping at the offered width — a person's set wider than the
 * bubble, which only a raised server limit or a modified client makes (iOS `ReactionEmojiFlow`,
 * `MessageReactionChips.swift:226-268`; conversation-thread §14.4).
 *
 * Unlike [ReactionFooterLayout] the rows have no spacing between them: each emoji button is the
 * chip's full height already. Its max intrinsic width is the one-line width, which is what a bubble
 * measuring its reaction foot "unproposed" (iOS `LinkBubbleRole.footer`) reads.
 */
@Composable
fun ReactionEmojiFlow(modifier: Modifier = Modifier, spacing: Dp = 2.dp, content: @Composable () -> Unit) {
    val policy = remember(spacing) { EmojiFlowPolicy(spacing) }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

private class EmojiFlowPolicy(private val spacing: Dp) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        // Each emoji keeps its own size (iOS proposes `.unspecified` to every subview).
        val placeables = measurables.map { it.measure(Constraints()) }
        val limit = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else null
        val arrangement = ReactionEmojiFlowMath.arrange(
            placeables.map { Size(it.width.toFloat(), it.height.toFloat()) },
            width = limit,
            spacing = spacing.toPx(),
        )
        val width = ceil(arrangement.size.width).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = ceil(arrangement.size.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val frame = arrangement.frames[index]
                placeable.placeRelative(frame.left.toInt(), frame.top.toInt())
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        if (widths.isEmpty()) return 0
        return widths.sum() + (spacing.roundToPx() * (widths.size - 1))
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    private fun IntrinsicMeasureScope.intrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val sizes = measurables.map {
            val w = it.maxIntrinsicWidth(Constraints.Infinity)
            Size(w.toFloat(), it.maxIntrinsicHeight(w).toFloat())
        }
        val limit = if (width == Constraints.Infinity) null else width.toFloat()
        return ceil(ReactionEmojiFlowMath.arrange(sizes, limit, spacing.toPx()).size.height).toInt()
    }
}

/** The pure geometry of [ReactionEmojiFlow] (`MessageReactionChips.swift:232-251`), unit-tested. */
object ReactionEmojiFlowMath {
    /** Frames in flow order and the size they need. */
    data class Arrangement(val frames: List<Rect>, val size: Size)

    /**
     * Left to right; a frame that would cross [width] (null = unbounded) starts a new row under the
     * tallest of the row before — no row spacing. The size hugs the widest row.
     */
    fun arrange(sizes: List<Size>, width: Float?, spacing: Float = 2f): Arrangement {
        val limit = width ?: Float.POSITIVE_INFINITY
        val frames = ArrayList<Rect>(sizes.size)
        var x = 0f
        var y = 0f
        var rowHeight = 0f
        var used = 0f
        for (size in sizes) {
            if (x > 0f && x + size.width > limit) {
                y += rowHeight
                x = 0f
                rowHeight = 0f
            }
            frames += Rect(Offset(x, y), size)
            used = max(used, x + size.width)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return Arrangement(frames, Size(used, y + rowHeight))
    }
}
