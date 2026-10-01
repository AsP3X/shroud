package de.corespace.shroud.core.media.scrub

import de.corespace.shroud.core.media.scrub.MediaFixtures.ascii
import de.corespace.shroud.core.media.scrub.MediaFixtures.pngChunk
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PNG scrubber (media-voice-links §5.2): the chunk list is rebuilt from an allow-list, the
 * pixels are copied untouched, and the orientation survives in a minimal `eXIf`.
 */
class PngScrubberTest {
    @Test
    fun textTimeExifAndUnknownChunksGoOrientationStays() {
        val original = MediaFixtures.taggedPng(orientation = 6)
        val before = MediaMetadataScrubber.leftoverMetadata(original)
        for (name in listOf("PNG.tEXt", "PNG.iTXt", "PNG.zTXt", "PNG.tIME", "GPS", "MakerNote", "PNG.caBX", "PNG.trailing")) {
            assertTrue("$name in $before", before.contains(name))
        }

        val clean = requireNotNull(MediaMetadataScrubber.scrubImage(original)) { "not cleaned" }

        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
        assertEquals(emptyList<String>(), MediaFixtures.survivingSecrets(clean, MediaFixtures.SECRETS + "trailing"))
        val chunks = PngScrubber.chunks(clean)!!
        assertEquals(listOf("IHDR", "eXIf"), chunks.take(2).map { it.type })
        val exif = chunks[1]
        assertEquals(6, MinimalTiff.orientation(clean, exif.data, exif.data + exif.length))
        assertEquals("minimal TIFF", 26, exif.length)
        val crc = CRC32().apply { update(clean, exif.start + 4, 4 + exif.length) }.value
        assertEquals("eXIf CRC", crc, clean.u32be(exif.data + exif.length))
        assertEquals(clean.size, chunks.last().end)
        val idat = { data: ByteArray -> PngScrubber.chunks(data)!!.filter { it.type == "IDAT" }.flatMap { data.copyOfRange(it.start, it.end).toList() } }
        assertEquals(idat(original), idat(clean))
        assertArrayEquals(MediaFixtures.pixels(original), MediaFixtures.pixels(clean))
        assertEquals(30 to 40, ImageHeader.pixelSize(clean))
    }

    @Test
    fun uprightPngsGetNoExifAtAll() {
        val clean = requireNotNull(MediaMetadataScrubber.scrubImage(MediaFixtures.taggedPng(orientation = 1))) { "not cleaned" }
        assertTrue(PngScrubber.chunks(clean)!!.none { it.type == "eXIf" })
        assertEquals(40 to 30, ImageHeader.pixelSize(clean))
    }

    @Test
    fun anUnknownCriticalChunkIsRefused() {
        val plain = MediaFixtures.taggedPng(orientation = 1)
        val withCritical = plain.copyOfRange(0, 33) + pngChunk("ABCD", ascii("Test Phone")) + plain.copyOfRange(33, plain.size)
        assertNull(MediaMetadataScrubber.scrubImage(withCritical))
        assertTrue(MediaMetadataScrubber.leftoverMetadata(withCritical).contains("PNG.ABCD"))
    }

    @Test
    fun anUnreadableExifOrBrokenStructureIsRefused() {
        val plain = MediaFixtures.taggedPng(orientation = 1)
        assertNull(MediaMetadataScrubber.scrubImage(plain.copyOfRange(0, 33) + pngChunk("eXIf", ascii("not a tiff")) + plain.copyOfRange(33, plain.size)))
        assertNull("no IEND", PngScrubber.scrub(plain.copyOfRange(0, 40)))
        assertNull("IHDR not first", PngScrubber.chunks(plain.copyOfRange(0, 8) + pngChunk("tEXt", ascii("a\u0000b")) + plain.copyOfRange(8, plain.size)))
    }
}
