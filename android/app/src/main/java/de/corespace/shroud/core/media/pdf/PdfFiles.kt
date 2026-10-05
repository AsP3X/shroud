package de.corespace.shroud.core.media.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import de.corespace.shroud.core.media.SealedMediaReader
import de.corespace.shroud.core.media.files.FileContentCheck
import de.corespace.shroud.core.media.files.FileTypes
import de.corespace.shroud.core.media.share.SealedReaderProxy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** A PDF's `th` and `pg` (docs/file-sharing.md §1, §10.1): the JPEG, its pixel size, the page count. */
class PdfEnvelopePreview(val jpeg: ByteArray?, val width: Int, val height: Int, val pageCount: Int) {
    override fun toString(): String = "PdfEnvelopePreview(${width}x$height, pages=$pageCount, preview=${jpeg != null})"
}

/** The local render of a PDF card (§10.1): page 1 at the card's pixel width, its top 2:1, and the page count. */
class PdfCardRender(val bitmap: Bitmap, val pageCount: Int)

/**
 * The PDFs of messages and picks (docs/file-sharing.md §10): the sender's `th`, the bubble card's
 * local render and the viewer's document, all through [PdfRenderer][android.graphics.pdf.PdfRenderer]
 * on [PdfRenderThread]. A message's file is read through a seekable proxy descriptor over its SHRM1
 * reader ([SealedReaderProxy]): segments are decrypted as the renderer reads them, and no plaintext
 * touches the disk. Nothing here caches; the callers keep their renders in memory only.
 */
class PdfFiles(private val context: Context, private val openReader: (UUID) -> SealedMediaReader?) {
    /**
     * A seekable descriptor over [messageId]'s file, or null when it is not on this phone, the chats
     * are locked, or the platform cannot make a proxy descriptor. Closing it closes the reader.
     */
    fun openDescriptor(messageId: UUID): ParcelFileDescriptor? {
        val reader = readerOrNull(messageId) ?: return null
        val storage = context.getSystemService(StorageManager::class.java)
        val descriptor = storage?.let { SealedReaderProxy.open(it, reader, PdfRenderThread.proxyHandler) { closeQuietly(reader) } }
        if (descriptor == null) closeQuietly(reader)
        return descriptor
    }

    /** [messageId]'s PDF, opened ([password] for a protected one on API 35+); [PdfOpenResult.Damaged] when it is not here. */
    suspend fun openDocument(messageId: UUID, password: String? = null): PdfOpenResult {
        val descriptor = openDescriptor(messageId) ?: return PdfOpenResult.Damaged
        return PdfDocument.open(descriptor, password)
    }

    /**
     * The card's local render (§10.1): page 1 at [widthPx], cropped to 2:1 from the top (the whole
     * page when it is wider). Null when the file is not here, fails §4's `%PDF-` check, is
     * password-protected or does not parse — the bubble keeps `th` then.
     */
    suspend fun cardRender(messageId: UUID, widthPx: Int): PdfCardRender? {
        if (widthPx <= 0 || !passesContentCheck(messageId)) return null
        val document = (openDocument(messageId) as? PdfOpenResult.Opened)?.document ?: return null
        return try {
            val bitmap = renderTop(document, widthPx) ?: return null
            PdfCardRender(bitmap, document.pageCount)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            document.close()
        }
    }

    /**
     * The sender's `th` and `pg` for a picked PDF (§10.1) from [open] (the `ContentResolver`
     * descriptor), or null: the provider gives no seekable descriptor, the PDF does not parse or is
     * protected, or it all takes longer than [PdfEnvelopeThumb.BUDGET_MS]. A PDF that parses but whose
     * first page does not draw small enough still gives its page count.
     */
    suspend fun envelopePreview(open: () -> ParcelFileDescriptor?): PdfEnvelopePreview? {
        // A render cannot be interrupted, so the work runs detached and the send stops waiting at
        // the budget; a late render closes its document and is dropped.
        val work = budgetScope.async { makeEnvelopePreview(open) }
        val result = withTimeoutOrNull(PdfEnvelopeThumb.BUDGET_MS) { work.await() }
        if (result == null) work.cancel()
        return result
    }

    private suspend fun makeEnvelopePreview(open: () -> ParcelFileDescriptor?): PdfEnvelopePreview? =
        run {
            val descriptor = try {
                open()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@run null
            val document = (PdfDocument.open(descriptor) as? PdfOpenResult.Opened)?.document ?: return@run null
            try {
                val pages = document.pageCount
                if (pages < 1) return@run null
                val top = renderTop(document, PdfEnvelopeThumb.START_WIDTH)
                val encoded = top?.let { bitmap ->
                    try {
                        PdfEnvelopeThumb.encode(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                PdfEnvelopePreview(encoded?.jpeg, encoded?.width ?: 0, encoded?.height ?: 0, pages)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                document.close()
            }
        }

    /** [envelopePreview] from [messageId]'s sealed copy: a retried or queued PDF whose `th` was not kept. */
    suspend fun envelopePreview(messageId: UUID): PdfEnvelopePreview? {
        if (!passesContentCheck(messageId)) return null
        return envelopePreview { openDescriptor(messageId) }
    }

    /** Page 1 at [widthPx] wide, its top [widthPx] × [widthPx] / 2 (shorter when the page is wider than 2:1). */
    private suspend fun renderTop(document: PdfDocument, widthPx: Int): Bitmap? {
        if (document.pageCount < 1) return null
        val size = document.pageSizes(0..0).firstOrNull() ?: return null
        if (!(size.width > 0f) || !(size.height > 0f)) return null
        val scale = widthPx / size.width
        val height = PdfEnvelopeThumb.cropHeight(widthPx, size)
        return document.render(0, widthPx, height, scale)
    }

    /** §4: `%PDF-` within the first 1024 bytes. */
    private fun passesContentCheck(messageId: UUID): Boolean {
        val reader = readerOrNull(messageId) ?: return false
        val head = ByteArray(FileContentCheck.HEAD_BYTES)
        return try {
            var filled = 0
            while (filled < head.size) {
                val read = reader.read(filled.toLong(), head, filled, head.size - filled)
                if (read <= 0) break
                filled += read
            }
            FileContentCheck.matches(PDF, head, filled)
        } catch (_: IOException) {
            false
        } finally {
            head.fill(0)
            closeQuietly(reader)
        }
    }

    private fun readerOrNull(messageId: UUID): SealedMediaReader? = try {
        openReader(messageId)
    } catch (_: Exception) {
        null
    }

    private fun closeQuietly(reader: SealedMediaReader) {
        try {
            reader.close()
        } catch (_: IOException) {
        }
    }

    /** Where the sender's previews run, so a slow one never holds the send past its budget. */
    private val budgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private companion object {
        val PDF = FileTypes.forExtension("pdf")!!
    }
}

/**
 * The sender's `th` for a PDF (§10.1): page 1 on white at 480 px wide, its top 480 × 240 (the whole
 * page when it is wider than 2:1), JPEG; the width shrinks by 0.8 and the quality drops until it fits
 * 6 KB, and below 160 px wide there is no `th`. Pure apart from the encoder, so the ladder is tested
 * on the JVM.
 */
object PdfEnvelopeThumb {
    const val START_WIDTH = 480
    const val MIN_WIDTH = 160
    const val WIDTH_FACTOR = 0.8
    const val START_QUALITY = 70
    const val QUALITY_STEP = 10
    const val MIN_QUALITY = 30

    /** The JPEG bytes of `th`, not its Base64 (`MediaCrypto.MAX_ENVELOPE_PREVIEW_BYTES`). */
    const val MAX_BYTES = 6 * 1024

    /** Longer than this, the sender sends no `th` and no `pg`. */
    const val BUDGET_MS = 2_000L

    class Encoded(val jpeg: ByteArray, val width: Int, val height: Int)

    /** The 2:1 crop's height at [widthPx]: half the width, or the whole page when it is wider than 2:1. */
    fun cropHeight(widthPx: Int, page: PdfPageSize): Int {
        val full = max(1, (widthPx * page.height / page.width).roundToInt())
        return minOf(full, max(1, widthPx / 2))
    }

    /**
     * The ladder over [encode] (width px, JPEG quality 0…100 → bytes, or null when it cannot): the
     * first JPEG of at most [MAX_BYTES], with the width it was made at; null once the width falls
     * under [MIN_WIDTH] or an encode fails.
     */
    fun ladder(startWidth: Int, encode: (width: Int, quality: Int) -> ByteArray?): Pair<ByteArray, Int>? {
        var width = startWidth
        var quality = START_QUALITY
        while (width >= MIN_WIDTH) {
            val jpeg = encode(width, quality) ?: return null
            if (jpeg.size <= MAX_BYTES) return jpeg to width
            width = floor(width * WIDTH_FACTOR).toInt()
            quality = max(MIN_QUALITY, quality - QUALITY_STEP)
        }
        return null
    }

    /** [ladder] over [top] (the 480-wide crop): each try scales it down and encodes it. */
    fun encode(top: Bitmap): Encoded? {
        val aspect = top.height.toFloat() / top.width
        var size = 0 to 0
        val result = ladder(top.width) { width, quality ->
            val height = max(1, (width * aspect).roundToInt())
            val scaled = if (width == top.width) top else Bitmap.createScaledBitmap(top, width, height, true)
            try {
                val out = ByteArrayOutputStream(MAX_BYTES)
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return@ladder null
                size = width to height
                out.toByteArray()
            } finally {
                if (scaled !== top) scaled.recycle()
            }
        } ?: return null
        return Encoded(result.first, size.first, size.second)
    }
}
