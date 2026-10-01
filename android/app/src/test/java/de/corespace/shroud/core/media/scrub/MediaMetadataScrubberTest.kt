package de.corespace.shroud.core.media.scrub

import de.corespace.shroud.core.media.scrub.MediaFixtures.resource
import de.corespace.shroud.core.media.scrub.MediaFixtures.survivingSecrets
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of iOS `MediaMetadataScrubberTests` (photo parts, `ios/shroudTests/MediaMetadataScrubberTests.swift:13-141`):
 * photos leave the device without location, capture time or device details, and "Original" still
 * means the original pixels. The fixtures are ImageIO files built exactly like the iOS test builds
 * them (`app/src/test/resources/media/imageio/generate.swift`). The video cases belong to W2-VIDEO.
 */
class MediaMetadataScrubberTest {
    private val tagged = listOf("tagged.jpg", "tagged.heic", "tagged.png")

    private fun fixture(name: String) = resource("media/imageio/$name")

    /** `removesLocationCameraAndDates` (`:15-27`), for JPEG, HEIC and PNG. */
    @Test
    fun removesLocationCameraAndDates() {
        for (name in tagged) {
            val original = fixture(name)
            assertFalse("$name: the fixture carries metadata", MediaMetadataScrubber.leftoverMetadata(original).isEmpty())
            assertTrue("$name: the fixture carries the secrets", survivingSecrets(original).size >= 4)

            val clean = assertNotNull(name, MediaMetadataScrubber.scrubImage(original))

            assertEquals("$name leftovers", emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
            assertEquals("$name secrets", emptyList<String>(), survivingSecrets(clean))
        }
    }

    /** `keepsPixelsAndOrientation` (`:29-37`): orientation 6, identical pixels, same container type. */
    @Test
    fun keepsPixelsAndOrientation() {
        for (name in tagged) {
            val original = fixture(name)
            val clean = assertNotNull(name, MediaMetadataScrubber.scrubImage(original))

            assertEquals("$name orientation", 6, orientation(clean))
            assertEquals("$name container", ImageHeader.container(original), ImageHeader.container(clean))
            assertEquals("$name drawn size", 48 to 64, ImageHeader.pixelSize(clean))
            if (name.endsWith(".heic")) {
                assertArrayEquals("$name image items", imageItemBytes(original), imageItemBytes(clean))
            } else {
                assertArrayEquals("$name pixels", assertNotNull(MediaFixtures.pixels(original)), MediaFixtures.pixels(clean))
            }
        }
    }

    /** `keepsTheHDRGainMap` (`:39-47`): the HEIC's gain map (auxiliary image + ISO `tmap` in `idat`) survives untouched. */
    @Test
    fun keepsTheHeicGainMap() {
        val original = fixture("hdr-gainmap.heic")
        val heif = assertNotNull(IsoBmff.parseHeif(original))
        assertTrue("fixture has a tmap item", heif.items.any { it.type == "tmap" })
        assertEquals("tmap lives in idat", 1, heif.locations.getValue(heif.items.first { it.type == "tmap" }.id).constructionMethod)

        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))

        val after = assertNotNull(IsoBmff.parseHeif(clean))
        assertEquals(heif.items.map { it.type }, after.items.map { it.type })
        assertArrayEquals("every image item (incl. the gain map and tmap)", imageItemBytes(original), imageItemBytes(clean))
        assertArrayEquals("properties (colr, ispe, auxC, hvcC…)", boxBytes(original, "iprp"), boxBytes(clean, "iprp"))
        assertArrayEquals("item references", boxBytes(original, "iref"), boxBytes(clean, "iref"))
        assertArrayEquals("alternative group", boxBytes(original, "grpl"), boxBytes(clean, "grpl"))
        assertEquals(original.size, clean.size)
    }

    /** The same ramp as a gain-map JPEG: the MPF-located gain map image and its ISO 21496-1 metadata survive. */
    @Test
    fun keepsTheJpegGainMap() {
        val original = fixture("hdr-gainmap.jpg")
        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))
        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
        val primary = assertNotNull(JpegScrubber.parse(original, 0, original.size))
        // Everything from the end of the primary image on — the gain map JPEG — is byte-identical.
        assertArrayEquals(original.copyOfRange(primary.eoiEnd, original.size), clean.copyOfRange(primary.eoiEnd, clean.size))
        val mpf = primary.segments.first { it.marker == 0xE2 && original.hasAscii(it.payload, "MPF\u0000") }
        assertArrayEquals("MPF untouched", original.copyOfRange(mpf.start, mpf.end), clean.copyOfRange(mpf.start, mpf.end))
        val iso = primary.segments.first { it.marker == 0xE2 && original.hasAscii(it.payload, "urn:iso:std:iso:ts:21496:-1") }
        assertArrayEquals("ISO gain-map version kept", original.copyOfRange(iso.start, iso.end), clean.copyOfRange(iso.start, iso.end))
        assertArrayEquals(assertNotNull(MediaFixtures.pixels(original)), MediaFixtures.pixels(clean))
    }

    /** `animatedPNGKeepsItsFramesAndTiming` (`:49-78`): 3 frames, 0.25 s each, no GPS. */
    @Test
    fun animatedPngKeepsItsFramesAndTiming() {
        val original = fixture("animated.png")
        assertTrue(MediaMetadataScrubber.leftoverMetadata(original).contains("GPS"))

        val clean = assertNotNull(MediaMetadataScrubber.scrubImage(original))

        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
        val chunks = assertNotNull(PngScrubber.chunks(clean))
        val actl = chunks.single { it.type == "acTL" }
        assertEquals("frames", 3L, clean.u32be(actl.data))
        val frames = chunks.filter { it.type == "fcTL" }
        assertEquals(3, frames.size)
        val third = frames[2]
        val delay = clean.u16be(third.data + 20).toDouble() / clean.u16be(third.data + 22)
        assertEquals(0.25, delay, 0.01)
        // The frame data is copied, never re-compressed.
        val before = assertNotNull(PngScrubber.chunks(original)).filter { it.type == "IDAT" || it.type == "fdAT" }
            .map { original.copyOfRange(it.start, it.end).toList() }
        assertEquals(before, chunks.filter { it.type == "IDAT" || it.type == "fdAT" }.map { clean.copyOfRange(it.start, it.end).toList() })
    }

    /** `blanksDisallowedXMPInPlace` (`:105-131`), verbatim. */
    @Test
    fun blanksDisallowedXmpInPlace() {
        val packet = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"XMP Core 6.0.0\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\" photoshop:City=\"Berlin\" hdrgm:Version=\"1.0\">" +
            "<photoshop:DateCreated>2026-09-01T12:00:00</photoshop:DateCreated>" +
            "<tiff:Orientation>6</tiff:Orientation>" +
            "<hdrgm:GainMapMax><rdf:Seq><rdf:li>2.3</rdf:li></rdf:Seq></hdrgm:GainMapMax>" +
            "<mwg-rs:Regions rdf:parseType=\"Resource\"><mwg-rs:Name>Alice</mwg-rs:Name><mwg-rs:Regions/></mwg-rs:Regions>" +
            "<exif:Flash exif:Fired=\"False\"/>" +
            "</rdf:Description></rdf:RDF></x:xmpmeta>"
        val data = "HEADER".toByteArray() + packet.toByteArray() + "TRAILER".toByteArray()
        val length = data.size

        MediaMetadataScrubber.blankXmp(data)

        assertEquals(length, data.size)
        val text = String(data, Charsets.UTF_8)
        for (gone in listOf("Berlin", "DateCreated", "Alice", "Regions", "exif:Flash")) assertFalse("$gone survived", text.contains(gone))
        for (kept in listOf("hdrgm:Version=\"1.0\"", "<tiff:Orientation>6</tiff:Orientation>", "<rdf:li>2.3</rdf:li>", "HEADER", "TRAILER")) {
            assertTrue("$kept was removed", text.contains(kept))
        }
        val xml = text.removePrefix("HEADER").removeSuffix("TRAILER")
        // Well-formed XML (XMLParser in iOS; namespace-unaware here as there, the packet leaves prefixes undeclared).
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals("what is left is allowed", emptyList<String>(), XmpBlanker.audit(data))
    }

    /** `allowListKeepsOnlyRenderingTags` (`:133-141`), verbatim. */
    @Test
    fun allowListKeepsOnlyRenderingTags() {
        assertTrue(MediaMetadataScrubber.isAllowedTagPath("tiff:Orientation"))
        assertTrue(MediaMetadataScrubber.isAllowedTagPath("hdrgm:GainMapMax[0]"))
        assertTrue(MediaMetadataScrubber.isAllowedTagPath("iio:hasXMP"))
        assertFalse(MediaMetadataScrubber.isAllowedTagPath("tiff:Model"))
        assertFalse(MediaMetadataScrubber.isAllowedTagPath("exif:GPSLatitude"))
        assertFalse(MediaMetadataScrubber.isAllowedTagPath("photoshop:DateCreated"))
        assertFalse(MediaMetadataScrubber.isAllowedTagPath("unprefixed"))
    }

    /** `blankJPEGIPTC` (`:212-238`): APP13 becomes a COM of spaces of the same length; nothing else moves. */
    @Test
    fun blanksJpegIptcInPlace() {
        val original = fixture("tagged.jpg")
        val data = original.copyOf()

        MediaMetadataScrubber.blankJpegIptc(data)

        assertEquals(original.size, data.size)
        val layout = assertNotNull(JpegScrubber.parse(data, 0, data.size))
        assertTrue(layout.segments.none { it.marker == 0xED })
        val before = assertNotNull(JpegScrubber.parse(original, 0, original.size))
        val app13 = before.segments.single { it.marker == 0xED }
        assertEquals(0xFE, data.u8(app13.start + 1))
        assertTrue(data.allEqual(app13.payload, app13.end, SPACE))
        assertTrue(MediaFixtures.text(original).substring(app13.start, app13.end).contains("Reykjavik"))
        assertFalse(MediaFixtures.text(data).substring(app13.start, app13.end).contains("Reykjavik"))
    }

    /** Anything that is not JPEG, PNG or HEIF is never proven clean (the caller re-encodes). */
    @Test
    fun unknownFormatsAreNeverCleanAsIs() {
        val gif = "GIF89a".toByteArray() + ByteArray(32)
        assertNull(MediaMetadataScrubber.scrubImage(gif))
        assertEquals(listOf("unreadable"), MediaMetadataScrubber.leftoverMetadata(gif))
        assertNull(MediaMetadataScrubber.scrubImage(ByteArray(0)))
    }

    private fun orientation(clean: ByteArray): Int? = when (ImageHeader.container(clean)) {
        ImageContainer.Jpeg -> {
            val layout = JpegScrubber.parse(clean, 0, clean.size)!!
            val exif = layout.segments.first { it.marker == 0xE1 && clean.hasAscii(it.payload, "Exif\u0000\u0000") }
            MinimalTiff.orientation(clean, exif.payload + 6, exif.end)
        }
        ImageContainer.Png -> {
            val exif = PngScrubber.chunks(clean)!!.first { it.type == "eXIf" }
            MinimalTiff.orientation(clean, exif.data, exif.data + exif.length)
        }
        else -> {
            // HEIF draws by `irot` (anticlockwise quarter turns): 3 quarter turns = EXIF 6.
            val heif = IsoBmff.parseHeif(clean)!!
            val irot = IsoBmff.propertiesOf(heif, heif.primaryItem!!).first { it.type == "irot" }
            if ((clean.u8(irot.payload) and 3) == 3) 6 else null
        }
    }

    /** The bytes of every image item (not Exif, not XMP), in item order. */
    private fun imageItemBytes(data: ByteArray): ByteArray {
        val heif = IsoBmff.parseHeif(data)!!
        val out = java.io.ByteArrayOutputStream()
        for (item in heif.items) {
            if (item.type == "Exif" || item.type == "mime") continue
            for (extent in heif.locations[item.id]?.extents.orEmpty()) out.write(data, extent.first.toInt(), (extent.last - extent.first + 1).toInt())
        }
        return out.toByteArray()
    }

    private fun boxBytes(data: ByteArray, type: String): ByteArray {
        val heif = IsoBmff.parseHeif(data)!!
        val box = heif.metaChildren.first { it.type == type }
        return data.copyOfRange(box.start, box.end)
    }

    private fun <T : Any> assertNotNull(value: T?): T = assertNotNull("expected a value", value)

    private fun <T : Any> assertNotNull(message: String, value: T?): T {
        org.junit.Assert.assertNotNull(message, value)
        return value!!
    }
}
