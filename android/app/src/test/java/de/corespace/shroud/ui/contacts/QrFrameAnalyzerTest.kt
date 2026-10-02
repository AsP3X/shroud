package de.corespace.shroud.ui.contacts

import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import de.corespace.shroud.core.contacts.ContactInviteParser
import de.corespace.shroud.core.contacts.QrMatrix
import de.corespace.shroud.core.net.ServerConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The scanner's frame reader (iOS `QRCodeScannerView.swift:202-217`; contacts §9 *QrFrameAnalyzerTest*):
 * a code rendered into a camera-like luminance plane — rows padded past the width, as CameraX
 * hands them — decodes, and only the first read is emitted.
 */
class QrFrameAnalyzerTest {
    private val official = ContactInviteParser.shareUrl("ABCD234567", ServerConfiguration.official)

    @Test
    fun aCodeInAPaddedLuminancePlaneDecodesAndEmitsOnce() {
        val emitted = mutableListOf<String>()
        val analyzer = QrFrameAnalyzer { emitted += it }
        val frame = Frame.of(official, scale = 6, rowPadding = 24)

        assertTrue(analyzer.process(frame.buffer(), frame.rowStride, 1, frame.width, frame.height))
        // Later frames of the same code (the camera keeps running until it is unbound) are dropped.
        assertFalse(analyzer.process(frame.buffer(), frame.rowStride, 1, frame.width, frame.height))
        assertFalse(analyzer.process(frame.buffer(), frame.rowStride, 1, frame.width, frame.height))

        assertEquals(listOf(official), emitted)
        assertTrue(analyzer.hasEmitted)
    }

    @Test
    fun theLastRowMayBeShortAsInARealPlane() {
        val emitted = mutableListOf<String>()
        val analyzer = QrFrameAnalyzer { emitted += it }
        val frame = Frame.of(official, scale = 4, rowPadding = 32)
        // CameraX planes end after the last row's pixels, not after its padding.
        val trimmed = frame.bytes.copyOf(frame.bytes.size - frame.rowPadding)

        assertTrue(analyzer.process(ByteBuffer.wrap(trimmed), frame.rowStride, 1, frame.width, frame.height))
        assertEquals(listOf(official), emitted)
    }

    @Test
    fun interleavedPixelsAreReadWithTheirStride() {
        val emitted = mutableListOf<String>()
        val analyzer = QrFrameAnalyzer { emitted += it }
        val frame = Frame.of(official, scale = 5, rowPadding = 0)
        // Every pixel followed by a chroma byte (pixel stride 2), rows padded by 8.
        val stride = frame.width * 2 + 8
        val interleaved = ByteArray(stride * frame.height)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) interleaved[y * stride + 2 * x] = frame.bytes[y * frame.rowStride + x]
        }

        assertTrue(analyzer.process(ByteBuffer.wrap(interleaved), stride, 2, frame.width, frame.height))
        assertEquals(listOf(official), emitted)
    }

    @Test
    fun aFrameWithoutACodeWaitsForTheNextOne() {
        val emitted = mutableListOf<String>()
        val analyzer = QrFrameAnalyzer { emitted += it }
        val blank = ByteArray(320 * 240) { 0x7F }

        assertFalse(analyzer.process(ByteBuffer.wrap(blank), 320, 1, 320, 240))
        assertFalse(analyzer.hasEmitted)

        val frame = Frame.of(official, scale = 5, rowPadding = 16)
        assertTrue(analyzer.process(frame.buffer(), frame.rowStride, 1, frame.width, frame.height))
        assertEquals(listOf(official), emitted)
    }

    @Test
    fun aBufferShorterThanItsGeometryIsSkipped() {
        val analyzer = QrFrameAnalyzer { error("nothing to emit") }
        assertFalse(analyzer.process(ByteBuffer.wrap(ByteArray(100)), 64, 1, 64, 64))
        assertFalse(analyzer.process(ByteBuffer.wrap(ByteArray(100)), 10, 1, 64, 1))
    }

    @Test
    fun everyImageIsClosedEvenAfterTheCodeWasRead() {
        val emitted = mutableListOf<String>()
        val analyzer = QrFrameAnalyzer { emitted += it }
        val frame = Frame.of(official, scale = 6, rowPadding = 8)
        val images = List(3) { FakeImage(frame) }

        images.forEach(analyzer::analyze)

        assertEquals(listOf(official), emitted)
        assertTrue(images.all { it.closed })
        // Only the first frame was read: the later ones were closed unread.
        assertEquals(listOf(1, 0, 0), images.map { it.planeReads })
    }

    /** A Y plane: the QR modules at [scale] px each inside a 4-module white margin, rows padded by [rowPadding]. */
    private class Frame(val bytes: ByteArray, val width: Int, val height: Int, val rowStride: Int, val rowPadding: Int) {
        fun buffer(): ByteBuffer = ByteBuffer.wrap(bytes)

        companion object {
            fun of(payload: String, scale: Int, rowPadding: Int): Frame {
                val matrix = QrMatrix.encode(payload)!!
                val margin = 4 * scale
                val side = matrix.size * scale + 2 * margin
                val stride = side + rowPadding
                val bytes = ByteArray(stride * side) { 0xFF.toByte() }
                for (y in 0 until side) {
                    for (x in 0 until side) {
                        val mx = (x - margin) / scale
                        val my = (y - margin) / scale
                        val inside = x >= margin && y >= margin && mx < matrix.size && my < matrix.size
                        if (inside && matrix[mx, my]) bytes[y * stride + x] = 0
                    }
                    // The padding bytes hold junk on real cameras.
                    for (p in 0 until rowPadding) bytes[y * stride + side + p] = 0x33
                }
                return Frame(bytes, side, side, stride, rowPadding)
            }
        }
    }

    /** Only what the analyzer touches: the size, the first plane and close(). */
    private class FakeImage(private val frame: Frame) : ImageProxy {
        var closed = false
        var planeReads = 0

        override fun close() {
            closed = true
        }

        override fun getCropRect(): Rect = throw UnsupportedOperationException()
        override fun setCropRect(rect: Rect?) = throw UnsupportedOperationException()
        override fun getFormat(): Int = 35 // ImageFormat.YUV_420_888
        override fun getHeight(): Int = frame.height
        override fun getWidth(): Int = frame.width
        override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException()
        override fun getImage(): Image? = null

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
            planeReads++
            return arrayOf(
                object : ImageProxy.PlaneProxy {
                    override fun getRowStride(): Int = frame.rowStride
                    override fun getPixelStride(): Int = 1
                    override fun getBuffer(): ByteBuffer = frame.buffer()
                },
            )
        }
    }
}
