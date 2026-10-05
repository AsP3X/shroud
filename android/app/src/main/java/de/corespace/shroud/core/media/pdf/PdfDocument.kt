package de.corespace.shroud.core.media.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Where every [PdfRenderer] of the process runs (docs/file-sharing.md §10): `PdfRenderer` is not
 * thread-safe and allows one open page at a time, so a single thread serves the bubble cards, the
 * sender's `th` and the viewer, one call after another. Work queued for a coroutine that was
 * cancelled meanwhile (a page that scrolled away) never starts.
 */
object PdfRenderThread {
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shroud-pdf").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * Where the proxy descriptors' read callbacks run: not [dispatcher]'s thread (a render blocks on
     * the callback) and not the share provider's (a slow external app must not stall a page).
     */
    internal val proxyHandler: Handler by lazy {
        val thread = HandlerThread("shroud-pdf-proxy").apply { start() }
        Handler(thread.looper)
    }
}

/** What opening a PDF came to. */
sealed interface PdfOpenResult {
    class Opened(val document: PdfDocument) : PdfOpenResult

    /** Password-protected and no password was given (API 35+ can unlock it in place). */
    data object NeedsPassword : PdfOpenResult

    /** The password given does not open it. */
    data object WrongPassword : PdfOpenResult

    /** Password-protected below API 35: `PdfRenderer` has no way to unlock it there. */
    data object PasswordUnsupported : PdfOpenResult

    /** Not a PDF this phone can parse, or the descriptor could not be read. */
    data object Damaged : PdfOpenResult
}

/** A page's size in PDF points (1/72 in). */
data class PdfPageSize(val width: Float, val height: Float) {
    /** Height over width; A4's when the page reports nothing usable. */
    val aspect: Float get() = if (width > 0f && height > 0f) height / width else A4.aspect

    companion object {
        /** 595 × 842 pt: the placeholder of a page whose size is not read yet (§10.2 *Loading*). */
        val A4 = PdfPageSize(595f, 842f)
    }
}

/**
 * One open PDF over a seekable descriptor (§10, "Where the plaintext is"): every call hops to
 * [PdfRenderThread] and opens at most one page, closing it before it returns. [close] releases the
 * renderer and with it the descriptor — for a message's file, the SHRM1 reader behind the proxy.
 * Never logs content.
 */
class PdfDocument private constructor(private val renderer: PdfRenderer) {
    /** The page count, read when the document opened. */
    val pageCount: Int = renderer.pageCount

    @Volatile
    private var closed = false

    val isClosed: Boolean get() = closed

    /** The sizes of pages [range] (clamped to the document), each page opened and closed without drawing. */
    suspend fun pageSizes(range: IntRange): List<PdfPageSize> = onRenderer {
        if (closed) throw IOException("closed")
        val pages = range.first.coerceAtLeast(0)..range.last.coerceAtMost(pageCount - 1)
        pages.map { index ->
            if (closed) throw IOException("closed")
            renderer.openPage(index).use { page -> PdfPageSize(page.width.toFloat(), page.height.toFloat()) }
        }
    }

    /**
     * Page [index] drawn into a new [widthPx] × [heightPx] bitmap on white: page points scale by
     * [scale] px per point and the page is moved by −[left], −[top] (px, at that scale), so a bitmap
     * smaller than the page holds just that region — the top of page 1 for a card, the visible part
     * of a zoomed page. Null when the document is closed or the page does not draw.
     */
    suspend fun render(index: Int, widthPx: Int, heightPx: Int, scale: Float, left: Float = 0f, top: Float = 0f): Bitmap? = onRenderer {
        if (closed || index !in 0 until pageCount || widthPx <= 0 || heightPx <= 0) return@onRenderer null
        val bitmap = try {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return@onRenderer null
        }
        bitmap.eraseColor(Color.WHITE)
        try {
            renderer.openPage(index).use { page ->
                val matrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(-left, -top)
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            bitmap
        } catch (_: Exception) {
            bitmap.recycle()
            null
        }
    }

    /**
     * The matches of [query] on page [index], each match's rectangles in page points (API 35's
     * `Page.searchText`; pdfium matches without regard to case). Empty when nothing matches.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    suspend fun search(index: Int, query: String): List<List<RectF>> = onRenderer {
        if (closed || index !in 0 until pageCount || query.isBlank()) return@onRenderer emptyList()
        try {
            renderer.openPage(index).use { page -> page.searchText(query).map { match -> match.bounds.map(::RectF) } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Releases the renderer and its descriptor after the calls already queued; idempotent. */
    suspend fun close() {
        withContext(PdfRenderThread.dispatcher + NonCancellable) { closeNow() }
    }

    private fun closeNow() {
        if (closed) return
        closed = true
        try {
            renderer.close()
        } catch (_: Exception) {
        }
    }

    private suspend fun <T> onRenderer(block: () -> T): T = withContext(PdfRenderThread.dispatcher) { block() }

    companion object {
        /**
         * Opens [descriptor] (which the document then owns; it is closed here when opening fails).
         * [password] unlocks a protected PDF on API 35+ (`LoadParams`); below that a protected PDF
         * is [PdfOpenResult.PasswordUnsupported].
         */
        suspend fun open(descriptor: ParcelFileDescriptor, password: String? = null): PdfOpenResult =
            withContext(PdfRenderThread.dispatcher) {
                try {
                    PdfOpenResult.Opened(PdfDocument(create(descriptor, password)))
                } catch (_: SecurityException) {
                    closeQuietly(descriptor)
                    when {
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM -> PdfOpenResult.PasswordUnsupported
                        password.isNullOrEmpty() -> PdfOpenResult.NeedsPassword
                        else -> PdfOpenResult.WrongPassword
                    }
                } catch (_: Exception) {
                    closeQuietly(descriptor)
                    PdfOpenResult.Damaged
                } catch (_: OutOfMemoryError) {
                    closeQuietly(descriptor)
                    PdfOpenResult.Damaged
                }
            }

        private fun create(descriptor: ParcelFileDescriptor, password: String?): PdfRenderer =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && !password.isNullOrEmpty()) {
                createWithPassword(descriptor, password)
            } else {
                PdfRenderer(descriptor)
            }

        @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        private fun createWithPassword(descriptor: ParcelFileDescriptor, password: String): PdfRenderer =
            PdfRenderer(descriptor, LoadParams.Builder().setPassword(password).build())

        private fun closeQuietly(descriptor: ParcelFileDescriptor) {
            try {
                descriptor.close()
            } catch (_: Exception) {
            }
        }
    }
}
