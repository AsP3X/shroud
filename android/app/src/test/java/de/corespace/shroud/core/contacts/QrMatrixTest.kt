package de.corespace.shroud.core.contacts

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share-link QR code (iOS `QRCodeImage.swift:6-17`; contacts §5.7, §9 *QrMatrixTest*): level M,
 * CoreImage's sizes with the 1-module quiet zone, and a decode round trip at 3–20 pixels per
 * module (the web's scale sweep; memory "styled QR scannability").
 */
class QrMatrixTest {
    private val official = ContactInviteParser.shareUrl("ABCD234567", ServerConfiguration.official)

    /** A self-hosted link with a port and a 16-character code. */
    private val withPort = ContactInviteParser.shareUrl(
        "ABCDEFGHJKLMNPQR",
        ServerConfiguration(ServerConnectionMode.SelfHosted, "192.168.1.20", "8080", "/api/v1", useHTTPS = false),
    )

    @Test
    fun anOfficialLinkIsVersion3WithAOneModuleQuietZone() {
        assertEquals(40, official.toByteArray().size)
        val matrix = QrMatrix.encode(official)!!
        assertEquals(29 + 2, matrix.size)
        assertQuietZone(matrix)
    }

    @Test
    fun aLinkWithAPortAndALongCodeIsVersion4() {
        assertEquals("http://192.168.1.20:8080/u/ABCDEFGHJKLMNPQR", withPort)
        val matrix = QrMatrix.encode(withPort)!!
        assertEquals(33 + 2, matrix.size)
        assertQuietZone(matrix)
    }

    @Test
    fun theCodeDecodesAtEveryScaleFrom3To20PixelsPerModule() {
        for (payload in listOf(official, withPort)) {
            val matrix = QrMatrix.encode(payload)!!
            for (scale in 3..20) {
                val side = matrix.size * scale
                val source = RGBLuminanceSource(side, side, matrix.toArgb(scale))
                val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.PURE_BARCODE to true))
                assertEquals("scale $scale", payload, result.text)
                // Without the PURE_BARCODE shortcut too: the finder patterns are found in the image.
                val (paddedSide, paddedPixels) = padded(matrix, scale)
                val located = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(paddedSide, paddedSide, paddedPixels))))
                assertEquals("scale $scale (located)", payload, located.text)
            }
        }
    }

    @Test
    fun pixelsAreDarkOnWhite() {
        val matrix = QrMatrix.encode(official)!!
        val pixels = matrix.toArgb(scale = 2)
        assertEquals(matrix.size * 2 * matrix.size * 2, pixels.size)
        assertTrue(pixels.all { it == QrMatrix.BLACK || it == QrMatrix.WHITE })
        // The top-left finder pattern starts one module in.
        assertTrue(matrix[1, 1])
        assertEquals(QrMatrix.BLACK, pixels[(1 * 2) * matrix.size * 2 + 1 * 2])
        assertEquals(QrMatrix.WHITE, pixels[0])
    }

    @Test
    fun whatCannotBeEncodedIsNull() {
        assertNull(QrMatrix.encode(""))
        assertNull(QrMatrix.encode("x".repeat(4000)))
        assertNotNull(QrMatrix.encode("https://shroud.corespace.de/u/ABCD234567"))
    }

    private fun assertQuietZone(matrix: QrMatrix) {
        for (i in 0 until matrix.size) {
            assertFalse(matrix[i, 0])
            assertFalse(matrix[0, i])
            assertFalse(matrix[i, matrix.size - 1])
            assertFalse(matrix[matrix.size - 1, i])
        }
    }

    /** The code inside a white margin of 4 modules, as a camera would see it on a card. */
    private fun padded(matrix: QrMatrix, scale: Int): Pair<Int, IntArray> {
        val inner = matrix.toArgb(scale)
        val innerSide = matrix.size * scale
        val margin = 4 * scale
        val side = innerSide + 2 * margin
        val out = IntArray(side * side) { QrMatrix.WHITE }
        for (y in 0 until innerSide) System.arraycopy(inner, y * innerSide, out, (y + margin) * side + margin, innerSide)
        return side to out
    }
}
