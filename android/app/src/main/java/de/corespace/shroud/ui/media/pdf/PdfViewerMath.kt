package de.corespace.shroud.ui.media.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The PDF viewer's words (docs/file-sharing.md §10.2), word for word. */
object PdfViewerCopy {
    const val CLOSE = "Close"
    const val SEARCH = "Search"
    const val PAGES = "Pages"
    const val MORE = "More"
    const val SHARE = "Share"
    const val SAVE_TO_DOWNLOADS = "Save to Downloads"
    const val OPEN_IN_ANOTHER_APP = "Open in Another App"
    const val LOADING = "Loading…"
    const val SEARCH_PLACEHOLDER = "Search in PDF"
    const val PREVIOUS_RESULT = "Previous result"
    const val NEXT_RESULT = "Next result"
    const val DONE = "Done"
    const val NO_RESULTS = "No results"
    const val PROTECTED_TITLE = "This PDF is protected"
    const val PROTECTED_BODY = "Enter its password to open it."
    const val PASSWORD = "Password"
    const val OPEN = "Open"
    const val WRONG_PASSWORD = "Wrong password. Try again."
    const val PROTECTED_ELSEWHERE = "This PDF is protected with a password. Open it in another app to read it."
    const val DAMAGED_TITLE = "Shroud can't show this PDF."
    const val DAMAGED_BODY = "It may be damaged or use features Shroud can't display."

    /** The subtitle: `Loading…` before the count, `1 page` for one page, else `Page {n} of {count}` (1-based [page]). */
    fun subtitle(page: Int, count: Int?): String = when {
        count == null || count < 1 -> LOADING
        count == 1 -> "1 page"
        else -> "Page ${page.coerceIn(1, count)} of $count"
    }

    /** Each page's one TalkBack node. */
    fun pageLabel(page: Int, count: Int): String = "Page $page of $count"

    /** `{i} of {n}` of the search field (1-based [index]). */
    fun searchPosition(index: Int, count: Int): String = "$index of $count"
}

/** The viewer's sizes (§10.2), in dp. */
object PdfViewerMetrics {
    const val PAGE_MARGIN = 12f
    const val PAGE_GAP = 12f
    const val MAX_PAGE_WIDTH = 920f
    const val PAGE_RADIUS = 2f
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 6f
    const val DOUBLE_TAP_ZOOM = 2.5f
    const val CHROME_FADE_MS = 180

    /** Pages rendered beyond the ones in view, each side. */
    const val PREFETCH = 1

    /** Page bitmaps kept (about six pages). */
    const val PAGE_CACHE = 6

    /** Page sizes read per hop to the renderer thread, so a 500-page PDF never holds it long. */
    const val SIZE_BATCH = 24

    /** From this window width (dp) the pages list is a sidebar beside the pages; below, a drawer. */
    const val SIDEBAR_MIN_WINDOW = 840f
    const val SIDEBAR_WIDTH = 200f
    const val SIDEBAR_SEPARATOR = 1f
    /** The sidebar's and the drawer's slide, on the curve in `PdfViewer.kt` (`slideSpec`). */
    const val SIDEBAR_ANIMATION_MS = 280
    const val DRAWER_MAX_WIDTH = 280f
    const val DRAWER_FRACTION = 0.8f
    const val DRAWER_SCRIM_ALPHA = 0.3f
    const val THUMB_WIDTH = 128f
    const val THUMB_MAX_HEIGHT = 182f
    const val THUMB_LABEL_GAP = 6f
    const val THUMB_ROW_GAP = 20f
    const val THUMB_LIST_PADDING = 16f
    const val THUMB_OUTLINE = 2f
    const val THUMB_OUTLINE_GAP = 3f

    /** Thumbnails rendered beyond the rows in view, each side. */
    const val THUMB_AHEAD = 3

    /** Thumbnail renders queued on the renderer thread at once. */
    const val THUMB_PENDING = 2

    /** Bytes of finished thumbnails kept for the viewer's life (`Bitmap.allocationByteCount`). */
    const val THUMB_CACHE_BYTES = 12 * 1024 * 1024

    /** A thumbnail's size (dp): 128 wide at the page's aspect, no taller than 182 (a tall page narrows). */
    fun thumbnailSize(aspect: Float): Pair<Float, Float> {
        val safe = if (aspect > 0f) aspect else 1.414f
        val height = THUMB_WIDTH * safe
        return if (height <= THUMB_MAX_HEIGHT) THUMB_WIDTH to height else THUMB_MAX_HEIGHT / safe to THUMB_MAX_HEIGHT
    }

    /** The drawer's width: `min(280, 80 %)` of the window. */
    fun drawerWidth(windowWidth: Float): Float = min(DRAWER_MAX_WIDTH, windowWidth * DRAWER_FRACTION)

    /** Fit to width with a 12 dp margin each side, at most 920 dp wide. */
    fun pageWidth(containerWidth: Float): Float = max(0f, min(containerWidth - 2 * PAGE_MARGIN, MAX_PAGE_WIDTH))
}

/**
 * The zoom of the page column (§10.2): the column is drawn scaled by [scale] about the viewport's
 * centre and moved by [offset] (px). Pure. Horizontally the offset pans the zoomed column;
 * vertically the column scrolls, and the offset only takes what scrolling cannot (the first page's
 * top and the last page's bottom at a zoom above 1).
 */
object PdfZoomMath {
    /** How far the column may move at [scale] in a [viewport]: none at 1×. */
    fun maxOffset(viewport: Size, scale: Float): Offset =
        Offset(max(0f, viewport.width * (scale - 1f) / 2f), max(0f, viewport.height * (scale - 1f) / 2f))

    fun clamp(offset: Offset, viewport: Size, scale: Float): Offset {
        val limit = maxOffset(viewport, scale)
        return Offset(offset.x.coerceIn(-limit.x, limit.x), offset.y.coerceIn(-limit.y, limit.y))
    }

    /**
     * The offset after going from [scale] to [newScale] about [anchor] (viewport px from its top
     * left), moved by [pan]: the column point under the anchor stays under it.
     */
    fun offsetAfterZoom(offset: Offset, scale: Float, newScale: Float, anchor: Offset, viewport: Size, pan: Offset = Offset.Zero): Offset {
        if (!(scale > 0f)) return offset
        val centre = Offset(viewport.width / 2f, viewport.height / 2f)
        val fromCentre = anchor - centre
        return fromCentre - (fromCentre - offset) * (newScale / scale) + pan
    }

    /** A pinch past the limits gives way less and less, settling back on release. */
    fun rubberBand(scale: Float): Float = when {
        scale > PdfViewerMetrics.MAX_ZOOM -> PdfViewerMetrics.MAX_ZOOM + (scale - PdfViewerMetrics.MAX_ZOOM) * RUBBER
        scale < PdfViewerMetrics.MIN_ZOOM -> PdfViewerMetrics.MIN_ZOOM - (PdfViewerMetrics.MIN_ZOOM - scale) * RUBBER
        else -> scale
    }

    /** Double tap: 1× ↔ 2.5×. */
    fun doubleTapTarget(scale: Float): Float = if (scale > PdfViewerMetrics.MIN_ZOOM + 0.01f) PdfViewerMetrics.MIN_ZOOM else PdfViewerMetrics.DOUBLE_TAP_ZOOM

    /**
     * The column band (column px, `top until bottom`) the viewport shows at [scale] and [offsetY]:
     * all of it at 1×, the middle part at more.
     */
    fun visibleBand(viewportHeight: Float, scale: Float, offsetY: Float): ClosedFloatingPointRange<Float> {
        val centre = viewportHeight / 2f
        val top = centre + (0f - centre - offsetY) / scale
        val bottom = centre + (viewportHeight - centre - offsetY) / scale
        return top..bottom
    }

    /** The same horizontally. */
    fun visibleColumns(viewportWidth: Float, scale: Float, offsetX: Float): ClosedFloatingPointRange<Float> = visibleBand(viewportWidth, scale, offsetX)

    /** The column rect the viewport shows. */
    fun visibleRect(viewport: Size, scale: Float, offset: Offset): Rect {
        val x = visibleColumns(viewport.width, scale, offset.x)
        val y = visibleBand(viewport.height, scale, offset.y)
        return Rect(x.start, y.start, x.endInclusive, y.endInclusive)
    }

    /**
     * Splits a vertical pan [dy] (screen px) between the offset and the column's scroll: a pan back
     * toward an offset of 0 spends it first, the rest scrolls (via [scrollBy], which takes column px
     * and returns what it scrolled), and what scrolling cannot take moves the offset within its
     * limit. Returns the new vertical offset.
     */
    fun panVertically(dy: Float, offsetY: Float, maxOffsetY: Float, scale: Float, scrollBy: (Float) -> Float): Float {
        var remaining = dy
        var y = offsetY
        if (y != 0f && remaining != 0f && (remaining > 0f) != (y > 0f)) {
            val take = if (abs(remaining) < abs(y)) remaining else -y
            y += take
            remaining -= take
        }
        if (remaining != 0f && scale > 0f) {
            val scrolled = scrollBy(-remaining / scale)
            remaining += scrolled * scale
        }
        if (abs(remaining) > 0.01f) y = (y + remaining).coerceIn(-maxOffsetY, maxOffsetY)
        return y
    }

    /** Which visible page fills most of the band: its index, or [fallback] when none shows. */
    fun mostVisible(items: List<Triple<Int, Float, Float>>, band: ClosedFloatingPointRange<Float>, fallback: Int): Int {
        var best = fallback
        var bestShown = 0f
        for ((index, top, bottom) in items) {
            val shown = min(bottom, band.endInclusive) - max(top, band.start)
            if (shown > bestShown) {
                bestShown = shown
                best = index
            }
        }
        return best
    }

    private const val RUBBER = 0.35f
}
