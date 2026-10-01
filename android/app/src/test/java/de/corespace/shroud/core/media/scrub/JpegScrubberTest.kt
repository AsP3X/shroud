package de.corespace.shroud.core.media.scrub

import de.corespace.shroud.core.media.scrub.MediaFixtures.ascii
import de.corespace.shroud.core.media.scrub.MediaFixtures.exifSegment
import de.corespace.shroud.core.media.scrub.MediaFixtures.jpeg
import de.corespace.shroud.core.media.scrub.MediaFixtures.segment
import de.corespace.shroud.core.media.scrub.MediaFixtures.survivingSecrets
import de.corespace.shroud.core.media.scrub.MediaFixtures.withSegments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JPEG scrubber on the Android camera layouts iOS never sees (media-voice-links §5.2): Pixel Ultra
 * HDR (MPF gain map), Pixel Motion Photos (MP4 after the gain map), Samsung JPEGs (IPTC, `SEFT`
 * trailer), progressive files, and every "cannot be proven clean" case that must re-encode.
 */
class JpegScrubberTest {
    @Test
    fun pixelUltraHdrKeepsItsGainMapAndLosesItsMetadata() {
        val fixture = MediaFixtures.pixelUltraHdr()
        val original = fixture.bytes
        assertTrue(MediaMetadataScrubber.leftoverMetadata(original).containsAll(listOf("GPS", "MakerNote", "TIFF.IFD1", "XMP.Container:Directory")))

        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))

        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
        // Namespace declarations (xmlns:GCamera=…) stay, as on iOS; the properties go.
        assertEquals(emptyList<String>(), survivingSecrets(clean, MediaFixtures.SECRETS + listOf("HDR+", "Google\u0000HDRP", "Container:Directory", "Item:Semantic")))
        assertEquals("same length: MPF offsets stay valid", original.size, clean.size)
        // The gain map image is untouched (its XMP only has hdrgm), and still where MPF says.
        assertArrayEquals(original.copyOfRange(fixture.primarySize, original.size), clean.copyOfRange(fixture.primarySize, clean.size))
        val text = MediaFixtures.text(clean)
        assertTrue("primary keeps hdrgm:Version", text.substring(0, fixture.primarySize).contains("hdrgm:Version=\"1.0\""))
        assertTrue(text.contains("hdrgm:GainMapMax=\"2.3\""))
        assertEquals(0xFF, clean.u8(fixture.primarySize))
        assertEquals(0xD8, clean.u8(fixture.primarySize + 1))
        // Orientation survives in the minimal Exif; the primary's pixels are the same.
        assertEquals("64×48 stored, orientation 6", 48 to 64, ImageHeader.pixelSize(clean))
        assertArrayEquals(MediaFixtures.pixels(original), MediaFixtures.pixels(clean))
    }

    @Test
    fun pixelMotionPhotoLosesItsVideo() {
        val fixture = MediaFixtures.pixelUltraHdr(motionPhoto = true)
        val original = fixture.bytes
        assertTrue(MediaFixtures.text(original).contains("+52.5200"))
        assertTrue(MediaMetadataScrubber.leftoverMetadata(original).contains("JPEG.trailing"))

        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))

        assertEquals("cut after the gain map", fixture.primarySize + fixture.gainMapSize, clean.size)
        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
        assertEquals(emptyList<String>(), survivingSecrets(clean, MediaFixtures.SECRETS + listOf("+52.5200", "GCamera:MotionPhoto", "MotionPhoto")))
        assertArrayEquals(MediaFixtures.pixels(original), MediaFixtures.pixels(clean))
    }

    /** Deviation from the spec's "no MPF + trailer → re-encode": the trailer is cut at EOI, so Samsung JPEGs keep their pixels. */
    @Test
    fun mpfImageUniqueIdsAreZeroedInPlace() {
        val fixture = MediaFixtures.pixelUltraHdr(imageUids = true)
        val original = fixture.bytes
        assertTrue(MediaMetadataScrubber.leftoverMetadata(original).contains("JPEG.MPF.ImageUIDList"))

        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))

        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
        assertEquals(emptyList<String>(), survivingSecrets(clean, listOf("UID-SERIAL")))
        assertEquals("same length: MPF offsets stay valid", original.size, clean.size)
        assertArrayEquals("the gain map is still where MPF says", original.copyOfRange(fixture.primarySize, original.size), clean.copyOfRange(fixture.primarySize, clean.size))
        assertArrayEquals(MediaFixtures.pixels(original), MediaFixtures.pixels(clean))
    }

    @Test
    fun samsungTrailerIsCutAtTheEndOfTheImage() {
        val original = MediaFixtures.samsungJpeg()
        val primary = assertNotNull(JpegScrubber.parse(original, 0, original.size))
        assertTrue(original.size > primary.eoiEnd)

        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))

        assertEquals(primary.eoiEnd, clean.size)
        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
        assertEquals(emptyList<String>(), survivingSecrets(clean, MediaFixtures.SECRETS + listOf("SEFT", "Image_UTC_Data", "MotionPhoto_Data")))
        assertArrayEquals(MediaFixtures.pixels(original), MediaFixtures.pixels(clean))
        assertEquals("orientation 1: an empty IFD0", 64 to 48, ImageHeader.pixelSize(clean))
    }

    @Test
    fun everyOtherAppSegmentBecomesACommentOfTheSameLength() {
        val app11 = segment(0xEB, ascii("JP\u0000\u0001jumbc2pa Test Phone"))
        val extendedXmp = segment(0xE1, ascii("http://ns.adobe.com/xmp/extension/\u0000") + ascii("0123456789ABCDEF0123456789ABCDEF") + ByteArray(8) + ascii("<rdf:Description photoshop:City=\"Reykjavik\"/>"))
        val flashPix = segment(0xE2, ascii("FPXR\u0000Test Phone"))
        val comment = segment(0xFE, ascii("CREATOR: Test Phone"))
        val unknownApp0 = segment(0xE0, ascii("AVI1\u0000SERIAL-0042"))
        val adobe = segment(0xEE, ascii("Adobe") + byteArrayOf(0, 100, 0, 0, 0, 0, 1))
        val original = withSegments(jpeg(32, 24), app11, extendedXmp, flashPix, comment, unknownApp0, adobe, MediaFixtures.iptcSegment())

        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))

        assertEquals(original.size, clean.size)
        assertEquals(emptyList<String>(), survivingSecrets(clean))
        val before = JpegScrubber.parse(original, 0, original.size)!!.segments
        val after = JpegScrubber.parse(clean, 0, clean.size)!!.segments
        assertEquals(before.map { it.start to it.end }, after.map { it.start to it.end })
        val markers = after.map { it.marker }
        assertTrue("Adobe kept", markers.contains(0xEE))
        assertFalse(markers.any { it in 0xE1..0xED || it == 0xEF })
        assertTrue(MediaFixtures.text(clean).contains("Adobe"))
    }

    @Test
    fun progressiveJpegIsWalkedThroughEveryScan() {
        val base = jpeg(48, 32, progressive = true)
        val layout = assertNotNull(JpegScrubber.parse(base, 0, base.size))
        val scans = layout.segments.filter { it.marker == 0xDA }
        assertTrue("several scans", scans.size > 1)
        // A comment between two scans (legal) is found and blanked too.
        val second = scans[1]
        val original = base.copyOfRange(0, second.start) + segment(0xFE, ascii("Test Photographer")) + base.copyOfRange(second.start, base.size)

        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))

        assertEquals(emptyList<String>(), survivingSecrets(clean))
        assertArrayEquals(MediaFixtures.pixels(original), MediaFixtures.pixels(clean))
    }

    @Test
    fun exifIsRewrittenInPlaceAsAMinimalTiff() {
        val tiff = MediaFixtures.cameraTiff(orientation = 8, bigEndian = false)
        val original = withSegments(jpeg(32, 24), exifSegment(tiff))
        val app1 = JpegScrubber.parse(original, 0, original.size)!!.segments.first { it.marker == 0xE1 }

        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))

        val tiffStart = app1.payload + 6
        val expected = ByteArray(app1.end - tiffStart).also { MinimalTiff.writeMinimal(it, 0, it.size, 8) }
        assertArrayEquals(expected, clean.copyOfRange(tiffStart, app1.end))
        assertArrayEquals(
            byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0),
            clean.copyOfRange(tiffStart, tiffStart + 26),
        )
        assertEquals(8, MinimalTiff.orientation(clean, tiffStart, app1.end))
        assertEquals(24 to 32, ImageHeader.pixelSize(clean))
    }

    @Test
    fun unprovableFilesAreRefused() {
        val plain = jpeg(16, 16)
        // Exif whose TIFF header is garbage: the orientation cannot be known.
        assertNull(MediaMetadataScrubber.scrubImage(withSegments(plain, segment(0xE1, ascii("Exif\u0000\u0000XX garbage")))))
        // An Exif too short for the minimal TIFF.
        assertNull(MediaMetadataScrubber.scrubImage(withSegments(plain, segment(0xE1, ascii("Exif\u0000\u0000II*\u0000") + byteArrayOf(8, 0, 0, 0, 0, 0)))))
        // No EOI.
        assertNull(MediaMetadataScrubber.scrubImage(plain.copyOf(plain.size - 2)))
        // A segment length running past the end.
        assertNull(MediaMetadataScrubber.scrubImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00, 1, 2)))
        // MPF pointing past the end of the file.
        val mpfBroken = withSegments(plain, MediaFixtures.mpfSegment(plain.size + 64, listOf(1_000_000 to 50)))
        assertNull(MediaMetadataScrubber.scrubImage(mpfBroken))
        // MPF pointing into the primary image.
        val mpfInside = withSegments(plain, MediaFixtures.mpfSegment(plain.size + 64, listOf(10 to 20)))
        assertNull(MediaMetadataScrubber.scrubImage(mpfInside))
    }

    @Test
    fun leftoversNameWhatIsThere() {
        val original = MediaFixtures.samsungJpeg()
        val found = MediaMetadataScrubber.leftoverMetadata(original)
        for (name in listOf("TIFF.Make", "TIFF.Model", "Exif.DateTimeOriginal", "Exif.BodySerialNumber", "MakerNote", "GPS", "TIFF.IFD1", "JPEG.APP13", "JPEG.trailing")) {
            assertTrue("$name in $found", found.contains(name))
        }
        // Names only, never values.
        assertTrue(found.none { it.contains("Test Phone") || it.contains("SERIAL") })
        assertEquals(listOf("JPEG.unreadable"), JpegScrubber.leftovers(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }

    @Test
    fun aMinimalExifWithDataInItsPaddingIsNotClean() {
        val padded = ByteArray(64).also { MinimalTiff.writeMinimal(it, 0, it.size, 6) }
        "Test Phone".toByteArray().copyInto(padded, 40)
        val original = withSegments(jpeg(16, 16), exifSegment(padded))
        assertTrue(MediaMetadataScrubber.leftoverMetadata(original).contains("Exif.padding"))
        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))
        assertEquals(emptyList<String>(), survivingSecrets(clean))
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
