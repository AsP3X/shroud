package de.corespace.shroud.core.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Declared image sizes from headers alone (web `imageDimensions`, `linkPreview.selftest.ts:162-191`)
 * and the decompression-bomb rule (`LinkPageMetadataParserTests.swift:165-171`,
 * `LinkPreviewFetcher.isDecodableImageSize`). Pure JVM: nothing is decoded.
 */
class LinkImageHeaderTest {
    private fun png(width: Int, height: Int): ByteArray {
        val bytes = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        bytes.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        bytes.position(12)
        bytes.put("IHDR".toByteArray())
        bytes.putInt(16, width)
        bytes.putInt(20, height)
        return bytes.array()
    }

    /** `linkPreview.selftest.ts:171-176`: SOI, APP0 (length 16), SOF0 with the size at its end. */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) + ByteArray(14) +
            byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0, 0, 0, 0)
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(head.size - 4, height.toShort())
        buffer.putShort(head.size - 2, width.toShort())
        return head
    }

    @Test
    fun pngSize() = assertEquals(LinkImageHeader.Size(1200, 630), LinkImageHeader.dimensions(png(1200, 630)))

    @Test
    fun jpegSize() = assertEquals(LinkImageHeader.Size(800, 400), LinkImageHeader.dimensions(jpeg(800, 400)))

    @Test
    fun gifSize() {
        val gif = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        gif.put("GIF89a".toByteArray())
        gif.putShort(6, 320)
        gif.putShort(8, 240)
        assertEquals(LinkImageHeader.Size(320, 240), LinkImageHeader.dimensions(gif.array()))
    }

    @Test
    fun webpSizes() {
        fun riff(kind: String, fill: (ByteBuffer) -> Unit): ByteArray {
            val b = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray())
            b.position(8)
            b.put("WEBP".toByteArray())
            b.put(kind.toByteArray())
            fill(b)
            return b.array()
        }
        // VP8X: 24-bit width − 1 at 24, height − 1 at 27.
        val vp8x = riff("VP8X") { b ->
            b.put(24, (1919 and 0xFF).toByte()); b.put(25, (1919 shr 8).toByte()); b.put(26, 0)
            b.put(27, (1079 and 0xFF).toByte()); b.put(28, (1079 shr 8).toByte()); b.put(29, 0)
        }
        assertEquals(LinkImageHeader.Size(1920, 1080), LinkImageHeader.dimensions(vp8x))
        // VP8 (lossy): 14-bit sizes at 26 and 28.
        val vp8 = riff("VP8 ") { b -> b.putShort(26, 640); b.putShort(28, (480 or 0x4000).toShort()) }
        assertEquals(LinkImageHeader.Size(640, 480), LinkImageHeader.dimensions(vp8))
        // VP8L (lossless): width − 1 and height − 1 packed in 14 bits each from byte 21.
        val bits = (99) or (49 shl 14)
        val vp8l = riff("VP8L") { b -> b.putInt(21, bits) }
        assertEquals(LinkImageHeader.Size(100, 50), LinkImageHeader.dimensions(vp8l))
        assertNull(LinkImageHeader.dimensions(riff("ALPH") {}))
    }

    @Test
    fun jpegSkipsFillBytesAndStandaloneMarkers() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xD0.toByte()) +
            byteArrayOf(0xFF.toByte(), 0xC2.toByte(), 0x00, 0x11, 0x08, 0x01, 0x00, 0x02, 0x00)
        assertEquals(LinkImageHeader.Size(512, 256), LinkImageHeader.dimensions(bytes))
    }

    @Test
    fun unknownAndTruncatedHeadersHaveNoSize() {
        assertNull(LinkImageHeader.dimensions("not an image at all, really not".toByteArray()))
        assertNull(LinkImageHeader.dimensions(png(1, 1).copyOf(20)))
        assertNull(LinkImageHeader.dimensions(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertNull(LinkImageHeader.dimensions(ByteArray(0)))
        // A JPEG whose marker chain breaks.
        assertNull(LinkImageHeader.dimensions(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x00) + ByteArray(20)))
    }

    @Test
    fun pngSizesAreUnsigned() {
        assertEquals(LinkImageHeader.Size(0xFFFF_FFFFL, 1), LinkImageHeader.dimensions(png(-1, 1)))
    }

    @Test
    fun decompressionBombsAreRefusedByTheirDeclaredSize() { // LinkPageMetadataParserTests.swift:165-171
        assertTrue(LinkPreviewImages.isDecodableImageSize(1200, 630))
        assertTrue(LinkPreviewImages.isDecodableImageSize(8000, 5000))
        assertFalse(LinkPreviewImages.isDecodableImageSize(20000, 20000))
        assertFalse(LinkPreviewImages.isDecodableImageSize(0, 630))
        assertFalse(LinkPreviewImages.isDecodableImageSize(Long.MAX_VALUE, 2))
    }
}
