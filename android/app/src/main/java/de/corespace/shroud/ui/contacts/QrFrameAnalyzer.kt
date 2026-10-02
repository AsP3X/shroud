package de.corespace.shroud.ui.contacts

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads QR codes from camera frames (iOS `AVCaptureMetadataOutput` with `.qr`,
 * `QRCodeScannerView.swift:105-109, 202-217`; contacts §5.5, plan decision 8: ZXing, locally, no
 * ML Kit).
 *
 * Each frame's luminance (Y) plane is wrapped — honouring its `rowStride` and `pixelStride` — in a
 * [PlanarYUVLuminanceSource], binarised with [HybridBinarizer] and handed to [QRCodeReader]. QR
 * decoding is rotation-invariant, so the buffer is never rotated. A frame without a code (or with a
 * damaged one: `NotFound`, `Checksum`, `Format`) just waits for the next.
 *
 * Emits **once**: the first non-empty text goes to [onCode] (on the analysis thread) and every later
 * frame is dropped unread (iOS `didEmit`, `:207-213`). Every [ImageProxy] is closed, decoded or not,
 * so CameraX keeps delivering (`STRATEGY_KEEP_ONLY_LATEST`). Never logs what it read.
 */
class QrFrameAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val emitted = AtomicBoolean(false)
    private val reader = QRCodeReader()

    /** Reused between frames (one analysis thread): the Y plane compacted to `width × height`. */
    private var luminance = ByteArray(0)

    /** True once a code was handed on; later frames are ignored. */
    val hasEmitted: Boolean get() = emitted.get()

    override fun analyze(image: ImageProxy) {
        try {
            if (emitted.get()) return
            val plane = image.planes.firstOrNull() ?: return
            process(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height)
        } finally {
            image.close()
        }
    }

    /**
     * Decodes one luminance plane and emits its text if it is the first. [buffer] starts at the
     * plane's first pixel; [rowStride] ≥ [width] bytes between rows (the last row may be short);
     * [pixelStride] bytes between neighbouring pixels (1 for CameraX's Y plane). Returns whether
     * this frame emitted.
     */
    fun process(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): Boolean {
        if (emitted.get() || width <= 0 || height <= 0) return false
        val pixels = compact(buffer, rowStride, pixelStride, width, height) ?: return false
        val text = decode(pixels, width, height) ?: return false
        if (text.isEmpty() || !emitted.compareAndSet(false, true)) return false
        onCode(text)
        return true
    }

    /** The plane as a tight `width × height` array (a source without row padding). */
    private fun compact(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): ByteArray? {
        if (rowStride < width * pixelStride - (pixelStride - 1) || pixelStride < 1) return null
        val size = width * height
        if (luminance.size != size) luminance = ByteArray(size)
        val source = buffer.duplicate()
        val start = source.position()
        for (y in 0 until height) {
            val rowStart = start + y * rowStride
            if (pixelStride == 1) {
                if (rowStart + width > source.limit()) return null
                source.position(rowStart)
                source.get(luminance, y * width, width)
            } else {
                val last = rowStart + (width - 1) * pixelStride
                if (last >= source.limit()) return null
                for (x in 0 until width) luminance[y * width + x] = source.get(rowStart + x * pixelStride)
            }
        }
        return luminance
    }

    private fun decode(pixels: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ChecksumException) {
            null
        } catch (_: FormatException) {
            null
        } finally {
            reader.reset()
        }
    }

    private companion object {
        val HINTS: Map<DecodeHintType, Any> = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    }
}
