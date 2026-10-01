package de.corespace.shroud.core.media.scrub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Container sniffing and the drawn pixel size from headers: iOS `MediaCrypto.mimeType`/`pixelSize`
 * (`MediaCrypto.swift:109-123, :203-212`), with the web's vectors copied from
 * `web/src/media/media.selftest.ts:14-74` and `web/src/linkPreview/linkPreview.selftest.ts:165-191`.
 */
class ImageHeaderTest {
    /** `media.selftest.ts:14-20` `heifLike(brand)`: a 12-byte `ftyp` header. */
    private fun heifLike(brand: String): ByteArray {
        val out = ByteArray(12)
        out[3] = 12
        "ftyp".toByteArray().copyInto(out, 4)
        brand.toByteArray().copyInto(out, 8)
        return out
    }

    /** `media.selftest.ts:45-60` `jpegHeader(width, height, orientation?)`. */
    private fun jpegHeader(width: Int, height: Int, orientation: Int? = null): ByteArray {
        val parts = arrayListOf(0xff, 0xd8)
        if (orientation != null && orientation != 0) {
            val tiff = listOf(
                0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
                orientation, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            )
            val body = listOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + tiff
            val length = body.size + 2
            parts += listOf(0xff, 0xe1, length shr 8, length and 0xff) + body
        }
        parts += listOf(0xff, 0xc0, 0x00, 0x0b, 0x08, height shr 8, height and 0xff, width shr 8, width and 0xff, 0x01, 0x11, 0x00)
        return ByteArray(parts.size) { parts[it].toByte() }
    }

    /** `linkPreview.selftest.ts:167-174`. */
    private fun png(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(bytes, 0)
        "IHDR".toByteArray().copyInto(bytes, 12)
        bytes.putU32be(16, width.toLong())
        bytes.putU32be(20, height.toLong())
        return bytes
    }

    /** `linkPreview.selftest.ts:175-181`. */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x00, 0x10) + ByteArray(14) +
            byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x00, 0x11, 0x08, 0, 0, 0, 0)
        bytes.putU16be(bytes.size - 4, height)
        bytes.putU16be(bytes.size - 2, width)
        return bytes
    }

    @Test
    fun displayPixelSizeVectorsOfTheWeb() {
        assertEquals("stored size", 32 to 16, ImageHeader.pixelSize(jpegHeader(32, 16)))
        assertEquals("orientation 6 swaps axes", 16 to 32, ImageHeader.pixelSize(jpegHeader(32, 16, 6)))
        assertNull("not an image", ImageHeader.pixelSize(byteArrayOf(0, 1, 2, 3)))
        for (orientation in 1..8) {
            val expected = if (orientation in 5..8) 16 to 32 else 32 to 16
            assertEquals("orientation $orientation", expected, ImageHeader.pixelSize(jpegHeader(32, 16, orientation)))
        }
    }

    @Test
    fun imageDimensionsVectorsOfTheWeb() {
        assertEquals("png size", 1200 to 630, ImageHeader.pixelSize(png(1200, 630)))
        assertEquals("jpeg size", 800 to 400, ImageHeader.pixelSize(jpeg(800, 400)))
        val gif = ByteArray(10)
        "GIF89a".toByteArray().copyInto(gif, 0)
        gif[6] = (320 and 0xFF).toByte(); gif[7] = (320 ushr 8).toByte()
        gif[8] = (240 and 0xFF).toByte(); gif[9] = (240 ushr 8).toByte()
        assertEquals("gif size", 320 to 240, ImageHeader.pixelSize(gif))
        assertNull("unknown format", ImageHeader.pixelSize("not an image at all, really not".toByteArray()))
    }

    @Test
    fun heifSniffingVectorsOfTheWeb() {
        assertEquals("heic brand", ImageContainer.Heic, ImageHeader.container(heifLike("heic")))
        assertEquals("mif1", ImageContainer.Heif, ImageHeader.container(heifLike("mif1")))
        assertNull("jpeg brand must fail", ImageHeader.container(heifLike("jpeg")))
        assertEquals("jpeg", ImageContainer.Jpeg, ImageHeader.container(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        for (brand in listOf("heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs")) assertEquals(brand, ImageContainer.Heic, ImageHeader.container(heifLike(brand)))
        assertEquals(ImageContainer.Heif, ImageHeader.container(heifLike("msf1")))
        assertEquals(ImageContainer.Avif, ImageHeader.container(heifLike("avif")))
    }

    @Test
    fun compatibleBrandsDecideForAGenericMajorBrand() {
        fun ftyp(major: String, vararg compatible: String): ByteArray {
            val size = 16 + 4 * compatible.size
            val out = ByteArray(size)
            out.putU32be(0, size.toLong())
            "ftyp".toByteArray().copyInto(out, 4)
            major.toByteArray().copyInto(out, 8)
            compatible.forEachIndexed { i, brand -> brand.toByteArray().copyInto(out, 16 + 4 * i) }
            return out
        }
        assertEquals(ImageContainer.Heic, ImageHeader.container(ftyp("mif1", "miaf", "heic")))
        assertEquals(ImageContainer.Avif, ImageHeader.container(ftyp("mif1", "miaf", "avif")))
        assertEquals(ImageContainer.Heif, ImageHeader.container(ftyp("mif1", "miaf")))
        assertNull(ImageHeader.container(ftyp("isom", "mp41")))
    }

    @Test
    fun mimeTypesAndPassthroughContainers() {
        assertEquals("image/jpeg", ImageHeader.mimeType(byteArrayOf(1, 2, 3, 4)))
        assertEquals("image/png", ImageHeader.mimeType(png(1, 1)))
        assertEquals("image/heic", ImageHeader.mimeType(heifLike("heic")))
        assertEquals("image/heif", ImageHeader.mimeType(heifLike("mif1")))
        assertEquals("image/gif", ImageHeader.mimeType("GIF87a----".toByteArray()))
        assertEquals("image/webp", ImageHeader.mimeType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        // iOS `passthroughTypes` (`MediaCrypto.swift:64`): JPEG, PNG, HEIC, HEIF only.
        assertEquals(
            listOf(ImageContainer.Jpeg, ImageContainer.Png, ImageContainer.Heic, ImageContainer.Heif),
            ImageContainer.entries.filter { it.allowsPassthrough },
        )
        assertFalse(ImageContainer.Avif.allowsPassthrough)
        assertTrue(ImageContainer.Heic.allowsPassthrough)
    }

    @Test
    fun fixturesReportTheSizeTheRecipientDraws() {
        // `originalSendCarriesNoMetadata` (`MediaMetadataScrubberTests.swift:89-90`): 64×48 at orientation 6 is 48×64.
        for (name in listOf("tagged.jpg", "tagged.heic", "tagged.png")) {
            assertEquals(name, 48 to 64, ImageHeader.pixelSize(MediaFixtures.resource("media/imageio/$name")))
        }
        assertEquals(128 to 96, ImageHeader.pixelSize(MediaFixtures.resource("media/imageio/hdr-gainmap.heic")))
        assertEquals(128 to 96, ImageHeader.pixelSize(MediaFixtures.resource("media/imageio/hdr-gainmap.jpg")))
        assertEquals(32 to 32, ImageHeader.pixelSize(MediaFixtures.resource("media/imageio/animated.png")))
    }
}
