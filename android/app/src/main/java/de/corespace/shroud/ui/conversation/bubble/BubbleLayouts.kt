package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

/**
 * How [BubbleStack] measures one of its rows — iOS `LinkBubbleRole` (`MessageBubbleView.swift:297-311`).
 */
enum class BubbleRole {
    /** Wraps at the bubble width; its hugging width counts (message text). */
    Wrapping,

    /** A block with a flexible width (quote, preview): its ideal width counts, then it is stretched to the bubble's. */
    Ideal,

    /** Its one-line width counts, capped by the row; then it gets the bubble's full width (reaction foot). */
    Footer,

    /** Keeps its own size, pinned to the trailing edge (time + ticks). */
    Trailing,
}

/** A row's role, and for wrapping text the width it hugs at a given cap (padding included). */
internal class BubbleRowData(val role: BubbleRole, val hug: ((Int) -> Int)?)

/** Marks a [BubbleStack] row; [hug] answers "how wide would you be at this cap" for text rows. */
fun Modifier.bubbleRole(role: BubbleRole, hug: ((Int) -> Int)? = null): Modifier =
    this.then(BubbleRoleModifier(BubbleRowData(role, hug)))

private class BubbleRoleModifier(private val data: BubbleRowData) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = data

    override fun equals(other: Any?): Boolean =
        other is BubbleRoleModifier && other.data.role == data.role && other.data.hug === data.hug

    override fun hashCode(): Int = data.role.hashCode() * 31 + (data.hug?.hashCode() ?: 0)
}

/**
 * Stacks a bubble's rows — quote, text, preview, meta, reaction foot — at one shared width: iOS
 * `LinkBubbleLayout` (`MessageBubbleView.swift:313-380`), which also stands in for
 * `QuotedBubbleLayout` (`:235-295`, the same rule with a quote and a text row).
 *
 * The bubble is as wide as its widest row — wrapping text by its hugging width at the cap, blocks by
 * their ideal width — never wider than [maxWidth] (or what the row offers); [fillsWidth] (a large
 * link picture) takes the whole cap, as a photo would. Rows are measured once at that width
 * (Compose measures each child once: widths come from [bubbleRole]'s hug and the intrinsics).
 */
@Composable
fun BubbleStack(maxWidth: Dp, fillsWidth: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val policy = remember(maxWidth, fillsWidth) { BubbleStackPolicy(maxWidth, fillsWidth) }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

private class BubbleStackPolicy(private val maxWidth: Dp, private val fillsWidth: Boolean) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val cap = min(maxWidth.roundToPx(), constraints.maxWidth).coerceAtLeast(1)
        val width = BubbleStackMath.resolvedWidth(cap, fillsWidth, measurables.map { rowWidth(it, cap) }).coerceAtLeast(constraints.minWidth)
        val placeables = measurables.map { measurable ->
            if (role(measurable) == BubbleRole.Trailing) {
                measurable.measure(Constraints())
            } else {
                measurable.measure(Constraints.fixedWidth(width))
            }
        }
        val height = placeables.sumOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            var y = 0
            measurables.forEachIndexed { index, measurable ->
                val placeable = placeables[index]
                if (role(measurable) == BubbleRole.Trailing) {
                    val x = if (layoutDirection == LayoutDirection.Ltr) width - placeable.width else 0
                    placeable.place(x, y)
                } else {
                    placeable.place(0, y)
                }
                y += placeable.height
            }
        }
    }

    private fun role(measurable: IntrinsicMeasurable): BubbleRole =
        (measurable.parentData as? BubbleRowData)?.role ?: BubbleRole.Wrapping

    private fun rowWidth(measurable: IntrinsicMeasurable, cap: Int): Int {
        val data = measurable.parentData as? BubbleRowData
        return when (data?.role ?: BubbleRole.Wrapping) {
            BubbleRole.Wrapping -> data?.hug?.invoke(cap) ?: min(measurable.maxIntrinsicWidth(Constraints.Infinity), cap)
            BubbleRole.Ideal, BubbleRole.Footer, BubbleRole.Trailing -> measurable.maxIntrinsicWidth(Constraints.Infinity)
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val cap = maxWidth.roundToPx()
        return BubbleStackMath.resolvedWidth(cap, fillsWidth, measurables.map { rowWidth(it, cap) })
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        maxIntrinsicWidth(measurables, height)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.sumOf { it.maxIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.sumOf { it.minIntrinsicHeight(width) }
}

/** The width rule of [BubbleStack] (`MessageBubbleView.swift:366-379`), unit-tested. */
object BubbleStackMath {
    /** [cap] when filling, else the widest row clamped to `1…cap`. */
    fun resolvedWidth(cap: Int, fillsWidth: Boolean, rowWidths: List<Int>): Int {
        if (fillsWidth) return max(1, cap)
        val widest = rowWidths.fold(1) { acc, w -> max(acc, w) }
        return max(1, min(cap, widest))
    }
}
