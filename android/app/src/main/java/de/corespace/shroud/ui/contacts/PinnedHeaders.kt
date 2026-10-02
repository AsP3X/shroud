package de.corespace.shroud.ui.contacts

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import kotlin.math.max
import kotlin.math.min

/**
 * Section headers pinned **under** the glass bar, as iOS pins them (`ContactsView.swift:95`,
 * `LazyVStack(pinnedViews: .sectionHeaders)` inside a scroll view the bar insets; contacts §5.1
 * [AND]).
 *
 * Compose's `stickyHeader` pins at the viewport's top — under the status bar and the glass bar,
 * because `MainScrollScreen` lets the list scroll under them with a top content padding (Compose
 * 1.12 `StickToTopPlacement` clamps at `-beforeContentPadding`). This moves the one header that
 * should be pinned down to the padding line (offset 0 of the lazy layout, right under the bar), and
 * lets the next header push it up as it arrives, the way iOS hands over from "A" to "B".
 *
 * Pure, so it is tested on the JVM: offsets are `LazyListItemInfo.offset` (0 = the padding line,
 * negative = under the bar), in px.
 */
object PinnedHeaders {
    /** `contentType` of every section header item, so the layout info can tell them apart. */
    const val CONTENT_TYPE = "contacts.sectionHeader"

    /** One section header as the lazy layout placed it. */
    data class Placed(val index: Int, val offset: Int, val size: Int)

    /**
     * How far down to move the header at [index] from where Compose placed it.
     *
     * The pinned header is the last one at or above the padding line (offset ≤ 0, the section the
     * bar's lower edge is in). It sits at the padding line, or right above the next header once
     * that one comes closer than its own height. Every other header stays where Compose put it:
     * in the list, or (Compose's own pinned one, when it is not ours) under the bar.
     *
     * [headers] are the visible header items in index order (Compose's pinned one included, at the
     * offset it gave it). Returns 0 for a header that is not visible.
     */
    fun translation(index: Int, headers: List<Placed>): Int {
        val position = headers.indexOfFirst { it.index == index }
        if (position < 0) return 0
        val header = headers[position]
        if (header.offset > 0) return 0
        val next = headers.getOrNull(position + 1)
        // Not ours to pin: a later header is also at or above the line.
        if (next != null && next.offset <= 0) return 0
        val wanted = if (next != null) min(next.offset - header.size, 0) else 0
        return max(0, wanted - header.offset)
    }

    /** The visible headers of [info], in index order. */
    fun placed(info: LazyListLayoutInfo): List<Placed> =
        info.visibleItemsInfo
            .filter { it.contentType == CONTENT_TYPE }
            .map { Placed(it.index, it.offset, it.size) }
            .sortedBy { it.index }
}

/**
 * Applies [PinnedHeaders.translation] to the header item at [index] of [state]'s list, read in the
 * draw phase (no recomposition while scrolling), and draws headers above the rows they slide over.
 */
internal fun Modifier.pinnedUnderBar(state: LazyListState, index: Int): Modifier = this
    .zIndex(1f)
    .graphicsLayer {
        translationY = PinnedHeaders.translation(index, PinnedHeaders.placed(state.layoutInfo)).toFloat()
    }
