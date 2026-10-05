package de.corespace.shroud.ui.media.pdf

import android.graphics.RectF
import android.os.Build
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.util.LruCache
import de.corespace.shroud.core.media.pdf.PdfDocument
import de.corespace.shroud.core.media.pdf.PdfOpenResult
import de.corespace.shroud.core.media.pdf.PdfPageSize
import de.corespace.shroud.core.media.pdf.PdfRenderThread
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the viewer's document (`media.pdf.openDocument`); tests pass a fake. */
internal fun interface PdfViewerServices {
    suspend fun open(messageId: UUID, password: String?): PdfOpenResult
}

/** One page bitmap: drawn [widthPx] wide (fit width × density), stretched over the page box. */
internal class PageRender(val image: ImageBitmap, val widthPx: Int)

/**
 * The sharp re-render of a zoomed page's visible part (§10.2): [region] in the page box's px at
 * 1× ([widthPx] wide), drawn [scale] × denser, so after the column's zoom it is 1:1 with the screen.
 */
internal class PageDetail(val image: ImageBitmap, val region: Rect, val scale: Float, val widthPx: Int)

/** One search match: its page and its rectangles in page points. */
internal class SearchMatch(val page: Int, val rects: List<RectF>)

/**
 * The PDF viewer's model (docs/file-sharing.md §10.2): the open document, the page sizes as they
 * are read, the page bitmaps (visible ±1, about six kept), the zoom, the sharp re-renders of a
 * settled zoom, and the search. Every PDF call runs on [PdfRenderThread]; this object is
 * main-confined. [dispose] closes the document and with it the descriptor and the SHRM1 reader.
 */
@Stable
internal class PdfViewerState(
    val messageId: UUID,
    private val services: PdfViewerServices,
    private val scope: CoroutineScope,
    initialPage: Int,
) {
    sealed interface Phase {
        data object Loading : Phase

        /** API 35+: a password field; [wrong] after a password that did not open it. */
        data class Password(val wrong: Boolean, val unlocking: Boolean) : Phase

        /** Below API 35: protected, and `PdfRenderer` cannot unlock it here. */
        data object PasswordUnsupported : Phase
        data object Damaged : Phase
        data object Ready : Phase
    }

    var phase: Phase by mutableStateOf(Phase.Loading)
        private set

    private var document: PdfDocument? = null
    private var disposed = false

    /** The page count once the document is parsed. */
    var pageCount: Int? by mutableStateOf(null)
        private set

    /** Each page's size once read (null before: an A4 placeholder). */
    val sizes = mutableStateListOf<PdfPageSize?>()

    val listState = LazyListState(firstVisibleItemIndex = initialPage.coerceAtLeast(0))

    /** Base page bitmaps by page index. */
    val pages = mutableStateMapOf<Int, PageRender>()
    private val pageOrder = LinkedHashMap<Int, Unit>(16, 0.75f, true)
    private val pageJobs = HashMap<Int, Job>()
    private val pageJobWidths = HashMap<Int, Int>()

    /** Sharp re-renders of the visible parts while zoomed. */
    val details = mutableStateMapOf<Int, PageDetail>()

    /** True once the first page bitmap landed: the spinner goes. */
    var firstPageDrawn: Boolean by mutableStateOf(false)
        private set

    // ---- zoom ----

    var scale: Float by mutableFloatStateOf(1f)
        private set
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    /** A finger is down or a zoom / fling animation runs: no sharp re-render yet. */
    var interacting: Boolean by mutableStateOf(false)

    /** The page column's viewport, px. */
    var viewport: Size by mutableStateOf(Size.Zero)

    /** The page box's width, px (fit width × density). */
    var pageWidthPx: Int by mutableIntStateOf(0)

    val isZoomed: Boolean get() = scale > PdfViewerMetrics.MIN_ZOOM + 0.01f

    // ---- search ----

    var searching: Boolean by mutableStateOf(false)
        private set
    var query: String by mutableStateOf("")
    var searchedQuery: String by mutableStateOf("")
        private set
    val matches = mutableStateListOf<SearchMatch>()
    var currentMatch: Int by mutableIntStateOf(-1)
        private set
    var searchFinished: Boolean by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    /** Search needs `PdfRenderer.Page.searchText` (API 35); the button is hidden below. */
    val canSearch: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    // ---- thumbnails (the pages list) ----

    /** Finished thumbnails by page, as the list draws them (mirrors [thumbnailCache]). */
    val thumbnails = mutableStateMapOf<Int, ImageBitmap>()

    /** About 12 MB of thumbnails by `allocationByteCount`, kept for the viewer's life. */
    private val thumbnailCache = object : LruCache<Int, ImageBitmap>(PdfViewerMetrics.THUMB_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: ImageBitmap, newValue: ImageBitmap?) {
            if (newValue == null) thumbnails.remove(key) else thumbnails[key] = newValue
        }
    }
    private val thumbJobs = HashMap<Int, Job>()
    private var thumbWanted: List<Int> = emptyList()
    private var thumbWidth: (Int) -> Int = { 0 }

    /** A finished thumbnail: shown at once, and kept until the cache's bytes run out. */
    private fun storeThumbnail(page: Int, image: ImageBitmap) {
        thumbnails[page] = image
        thumbnailCache.put(page, image)
    }

    // ---- opening ----

    /** Opens the document ([password] for a protected one, API 35+). */
    fun open(password: String? = null) {
        if (disposed) return
        if (password != null) phase = Phase.Password(wrong = false, unlocking = true)
        scope.launch {
            val result = services.open(messageId, password)
            if (disposed) {
                (result as? PdfOpenResult.Opened)?.document?.let { closeDetached(it) }
                return@launch
            }
            phase = when (result) {
                is PdfOpenResult.Opened -> {
                    adopt(result.document)
                    Phase.Ready
                }
                PdfOpenResult.NeedsPassword -> Phase.Password(wrong = false, unlocking = false)
                PdfOpenResult.WrongPassword -> Phase.Password(wrong = true, unlocking = false)
                PdfOpenResult.PasswordUnsupported -> Phase.PasswordUnsupported
                PdfOpenResult.Damaged -> Phase.Damaged
            }
        }
    }

    private fun adopt(opened: PdfDocument) {
        document = opened
        val count = opened.pageCount
        if (count < 1) {
            phase = Phase.Damaged
            return
        }
        sizes.clear()
        repeat(count) { sizes += null }
        pageCount = count
        if (listState.firstVisibleItemIndex >= count) scope.launch { listState.scrollToItem(count - 1) }
        // Sizes in batches, starting where the reader is, so the pages in view get theirs first.
        scope.launch {
            val start = listState.firstVisibleItemIndex.coerceIn(0, count - 1)
            val order = (start until count) + (0 until start)
            for (batch in order.chunked(PdfViewerMetrics.SIZE_BATCH)) {
                val range = batch.first()..batch.last()
                val read = try {
                    opened.pageSizes(range)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch
                }
                read.forEachIndexed { offset, size -> sizes[range.first + offset] = size }
            }
        }
    }

    /** The page's aspect (height / width): read, else A4. */
    fun aspect(page: Int): Float = (sizes.getOrNull(page) ?: PdfPageSize.A4).aspect

    // ---- page renders ----

    /**
     * Renders the pages in [wanted] (visible ±1) at [widthPx] and cancels the ones that left it;
     * about [PdfViewerMetrics.PAGE_CACHE] bitmaps stay, the least recently shown going first.
     */
    fun updateRenders(wanted: Set<Int>, widthPx: Int) {
        val doc = document ?: return
        if (widthPx <= 0) return
        pageJobs.keys.filter { it !in wanted }.forEach { pageJobs.remove(it)?.cancel() }
        for (page in wanted.sorted()) {
            val held = pages[page]
            if (held != null && held.widthPx == widthPx) {
                pageOrder[page] = Unit
                continue
            }
            if (pageJobs[page]?.isActive == true && pageJobWidths[page] == widthPx) continue
            pageJobs.remove(page)?.cancel()
            pageJobWidths[page] = widthPx
            pageJobs[page] = scope.launch {
                val size = sizes.getOrNull(page) ?: try {
                    doc.pageSizes(page..page).firstOrNull()?.also { if (page < sizes.size) sizes[page] = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: return@launch
                val height = (widthPx * size.aspect).roundToInt().coerceAtLeast(1)
                val bitmap = doc.render(page, widthPx, height, widthPx / size.width) ?: return@launch
                if (disposed) return@launch
                pages[page] = PageRender(bitmap.asImageBitmap(), widthPx)
                pageOrder[page] = Unit
                firstPageDrawn = true
                trimPages(wanted)
            }
        }
    }

    private fun trimPages(keep: Set<Int>) {
        while (pageOrder.size > maxOf(PdfViewerMetrics.PAGE_CACHE, keep.size)) {
            val eldest = pageOrder.keys.firstOrNull { it !in keep } ?: break
            pageOrder.remove(eldest)
            pages.remove(eldest)
        }
    }

    /**
     * The sharp re-render once a zoom settled (§10.2): for each page in [visible] (index, top px,
     * height px in the column), the part the viewport shows, drawn at the zoom. Back at 1× they go.
     */
    suspend fun renderDetails(visible: List<Triple<Int, Float, Float>>) {
        val doc = document ?: return
        val zoom = scale
        val widthPx = pageWidthPx
        if (zoom <= PdfViewerMetrics.MIN_ZOOM + 0.01f || widthPx <= 0 || viewport.width <= 0f) {
            details.clear()
            return
        }
        val seen = visible.map { it.first }.toSet()
        details.keys.filter { it !in seen }.forEach { details.remove(it) }
        val shown = PdfZoomMath.visibleRect(viewport, zoom, offset)
        val left = (viewport.width - widthPx) / 2f
        for ((page, top, height) in visible) {
            val size = sizes.getOrNull(page) ?: continue
            val box = Rect(left, top, left + widthPx, top + height)
            val part = box.intersect(shown)
            if (part.width <= 1f || part.height <= 1f) {
                details.remove(page)
                continue
            }
            val region = part.translate(-left, -top)
            val held = details[page]
            if (held != null && held.scale == zoom && held.widthPx == widthPx && held.region.covers(region)) continue
            val bitmapWidth = ceil(region.width * zoom).toInt()
            val bitmapHeight = ceil(region.height * zoom).toInt()
            val pxPerPoint = widthPx * zoom / size.width
            val bitmap = doc.render(page, bitmapWidth, bitmapHeight, pxPerPoint, region.left * zoom, region.top * zoom) ?: continue
            if (disposed) return
            details[page] = PageDetail(bitmap.asImageBitmap(), Rect(region.left, region.top, region.left + bitmapWidth / zoom, region.top + bitmapHeight / zoom), zoom, widthPx)
        }
    }

    /**
     * Renders the pages list's thumbnails for [wanted] (the rows in view first, then a few ahead) at
     * [widthPx] wide each: at most [PdfViewerMetrics.THUMB_PENDING] queued on the renderer thread,
     * the ones whose rows left cancelled. Finished ones stay in [thumbnailCache] for the viewer's life.
     */
    fun updateThumbnails(wanted: List<Int>, widthFor: (Int) -> Int) {
        thumbWanted = wanted
        thumbWidth = widthFor
        val keep = wanted.toSet()
        thumbJobs.keys.filter { it !in keep }.forEach { page -> thumbJobs.remove(page)?.cancel() }
        pumpThumbnails()
    }

    private fun pumpThumbnails() {
        val doc = document ?: return
        if (disposed) return
        for (page in thumbWanted) {
            if (thumbJobs.size >= PdfViewerMetrics.THUMB_PENDING) break
            val width = thumbWidth(page)
            if (width <= 0 || thumbJobs.containsKey(page) || thumbnails[page]?.width == width) continue
            lateinit var job: Job
            job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val size = sizes.getOrNull(page) ?: doc.pageSizes(page..page).firstOrNull() ?: return@launch
                    val height = (width * size.aspect).roundToInt().coerceAtLeast(1)
                    val rendered = doc.render(page, width, height, width / size.width) ?: return@launch
                    // Pages are white: 16 bits a pixel halve the memory and look the same at this size.
                    val small = rendered.copy(android.graphics.Bitmap.Config.RGB_565, false) ?: rendered
                    if (small !== rendered) rendered.recycle()
                    if (!disposed) storeThumbnail(page, small.asImageBitmap())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    if (thumbJobs[page] === job) thumbJobs.remove(page)
                    if (!disposed) pumpThumbnails()
                }
            }
            thumbJobs[page] = job
            job.start()
        }
    }

    // ---- zoom ----

    /** Pinch: [zoom] about [centroid] (viewport px) and the fingers' [pan]; past the limits it rubber-bands. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float, scrollBy: (Float) -> Float) {
        val target = PdfZoomMath.rubberBand(scale * zoom).coerceIn(0.5f, PdfViewerMetrics.MAX_ZOOM * 1.5f)
        setScale(target, centroid, pan, scrollBy)
    }

    /** Sets the zoom to [target] about [anchor], keeping the column point under it in place. */
    fun setScale(target: Float, anchor: Offset, pan: Offset = Offset.Zero, scrollBy: (Float) -> Float) {
        val moved = PdfZoomMath.offsetAfterZoom(offset, scale, target, anchor, viewport, pan)
        scale = target
        val clamped = PdfZoomMath.clamp(moved, viewport, maxOf(target, PdfViewerMetrics.MIN_ZOOM))
        // What the offset cannot take vertically, the column's scroll takes.
        val excess = moved.y - clamped.y
        if (excess != 0f && target > 0f) scrollBy(-excess / target)
        offset = clamped
    }

    /** A one-finger pan while zoomed: sideways moves the column, up and down scroll it. */
    fun pan(delta: Offset, scrollBy: (Float) -> Float) {
        val limit = PdfZoomMath.maxOffset(viewport, scale)
        val x = (offset.x + delta.x).coerceIn(-limit.x, limit.x)
        val y = PdfZoomMath.panVertically(delta.y, offset.y, limit.y, scale, scrollBy)
        offset = Offset(x, y)
    }

    fun setOffsetX(x: Float) {
        val limit = PdfZoomMath.maxOffset(viewport, scale)
        offset = Offset(x.coerceIn(-limit.x, limit.x), offset.y)
    }

    // ---- search ----

    fun beginSearch() {
        if (!canSearch || phase != Phase.Ready) return
        searching = true
    }

    fun endSearch() {
        searching = false
        searchJob?.cancel()
        query = ""
        searchedQuery = ""
        matches.clear()
        currentMatch = -1
        searchFinished = false
    }

    /**
     * Searches every page for [text], page by page on the renderer thread; the first match at or
     * after [fromPage] becomes current (else the first one) and [onCurrent] scrolls it into view.
     */
    fun search(text: String, fromPage: Int, onCurrent: (SearchMatch) -> Unit) {
        val doc = document ?: return
        searchJob?.cancel()
        matches.clear()
        currentMatch = -1
        searchFinished = false
        searchedQuery = text
        if (text.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            searchFinished = text.isNotBlank()
            return
        }
        searchJob = scope.launch {
            val count = doc.pageCount
            for (page in 0 until count) {
                val found = doc.search(page, text)
                for (rects in found) {
                    matches += SearchMatch(page, rects)
                    if (currentMatch < 0 && page >= fromPage) {
                        currentMatch = matches.lastIndex
                        onCurrent(matches[currentMatch])
                    }
                }
            }
            if (currentMatch < 0 && matches.isNotEmpty()) {
                currentMatch = 0
                onCurrent(matches[0])
            }
            searchFinished = true
        }
    }

    /** Next (+1) or previous (−1) match, wrapping around. */
    fun step(by: Int, onCurrent: (SearchMatch) -> Unit) {
        if (matches.isEmpty()) return
        val next = ((if (currentMatch < 0) 0 else currentMatch + by) % matches.size + matches.size) % matches.size
        currentMatch = next
        onCurrent(matches[next])
    }

    // ---- closing ----

    /** Closes the document after the queued calls; bitmaps leave with this object. Idempotent. */
    fun dispose() {
        if (disposed) return
        disposed = true
        searchJob?.cancel()
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        document?.let(::closeDetached)
        document = null
        pages.clear()
        details.clear()
        thumbJobs.values.forEach { it.cancel() }
        thumbJobs.clear()
        thumbnailCache.evictAll()
        thumbnails.clear()
    }

    private fun closeDetached(doc: PdfDocument) {
        CoroutineScope(PdfRenderThread.dispatcher).launch { withContext(NonCancellable) { doc.close() } }
    }

    private fun Rect.covers(other: Rect): Boolean =
        left <= other.left + 0.5f && top <= other.top + 0.5f && right >= other.right - 0.5f && bottom >= other.bottom - 0.5f


}
