package de.corespace.shroud.core.media.scrub

import de.corespace.shroud.core.media.scrub.MediaFixtures.ascii
import de.corespace.shroud.core.media.scrub.MediaFixtures.u16
import de.corespace.shroud.core.media.scrub.MediaFixtures.u32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HEIF scrubber on a Samsung-layout HEIC (Exif and XMP items in `mdat`, an `mpvd` motion video
 * after it) and every structure it must refuse (media-voice-links §5.2). The ImageIO HEICs (iPhone
 * layout, gain maps in `idat`) are covered by [MediaMetadataScrubberTest].
 */
class HeifScrubberTest {
    /** A HEIC built box by box; every knob is a way real files differ (or should be refused). */
    private data class Heic(
        val majorBrand: String = "heic",
        val handler: String = "pict",
        val irot: Int = 3,
        val exifMethod: Int = 0, // 0 = mdat, 1 = idat, 2 = item reference
        val extraItem: Pair<String, String?>? = null,
        val exifProtected: Boolean = false,
        val extraMetaChild: String? = null,
        val boxBeforeMdat: String? = null,
        val motionVideo: Boolean = true,
        val itemName: String = "Primary Test Phone",
        val handlerName: String = "Test Phone HEIF handler",
        /** An extra item property in `ipco` (not associated with any item). */
        val extraProperty: String? = null,
        /** null: no `dinf`; true: a self-contained `url `; false: a `url ` naming another file. */
        val selfContainedDinf: Boolean? = null,
    ) {
        val image = ByteArray(96) { (it * 7 + 3).toByte() }
        val exif = u32(6) + ascii("Exif\u0000\u0000") + MediaFixtures.cameraTiff(orientation = 6)
        val xmp = MediaFixtures.pixelPrimaryXmp(motionPhoto = true, gainMapLength = 0, videoLength = 0).toByteArray(Charsets.UTF_8)
        val unreferenced = ascii("unreferenced Test Photographer")

        fun build(): ByteArray {
            var layout = assemble(mdatPayloadAt = 0)
            val mdatAt = layout.indexOfBox("mdat")
            layout = assemble(mdatPayloadAt = mdatAt + 8)
            check(layout.indexOfBox("mdat") == mdatAt)
            return layout
        }

        private fun assemble(mdatPayloadAt: Int): ByteArray {
            val items = ArrayList<ByteArray>()
            items += infe(1, "hvc1", itemName)
            items += infe(2, "Exif", "", protection = if (exifProtected) 1 else 0)
            items += infe(3, "mime", "", contentType = "application/rdf+xml")
            extraItem?.let { (type, contentType) -> items += infe(4, type, "", contentType = contentType) }
            val iinf = fullBox("iinf", 0, 0, u16(items.size) + items.reduce { a, b -> a + b })
            val ispe = fullBox("ispe", 0, 0, u32(64) + u32(48))
            val irotBox = box("irot", byteArrayOf(irot.toByte()))
            val extraPropertyBox = extraProperty?.let { box(it, ByteArray(4) + ascii("en\u0000Reykjavik\u00002026-09-01\u0000")) } ?: ByteArray(0)
            val ipco = box("ipco", ispe + irotBox + extraPropertyBox)
            val ipma = fullBox("ipma", 0, 0, u32(1) + u16(1) + byteArrayOf(2, 0x81.toByte(), 0x02))
            val iprp = box("iprp", ipco + ipma)
            val idat = if (exifMethod == 1) box("idat", exif) else ByteArray(0)
            val imageAt = mdatPayloadAt
            val exifAt = imageAt + image.size
            val xmpAt = (if (exifMethod == 0) exifAt + exif.size else exifAt)
            val entries = ArrayList<ByteArray>()
            entries += ilocEntry(1, 0, imageAt, image.size)
            entries += when (exifMethod) {
                0 -> ilocEntry(2, 0, exifAt, exif.size)
                1 -> ilocEntry(2, 1, 0, exif.size)
                else -> ilocEntry(2, 2, 1, exif.size)
            }
            entries += ilocEntry(3, 0, xmpAt, xmp.size)
            val iloc = fullBox("iloc", 1, 0, byteArrayOf(0x44, 0x00) + u16(entries.size) + entries.reduce { a, b -> a + b })
            val hdlr = fullBox("hdlr", 0, 0, u32(0) + ascii(handler) + ByteArray(12) + ascii(handlerName) + byteArrayOf(0))
            val dinf = when (selfContainedDinf) {
                null -> ByteArray(0)
                true -> box("dinf", fullBox("dref", 0, 0, u32(1) + fullBox("url ", 0, 1, ByteArray(0))))
                false -> box("dinf", fullBox("dref", 0, 0, u32(1) + fullBox("url ", 0, 0, ascii("file:///sdcard/DCIM/Test Phone.heic\u0000"))))
            }
            val pitm = fullBox("pitm", 0, 0, u16(1))
            val extra = extraMetaChild?.let { box(it, ascii("<x:xmpmeta>Reykjavik</x:xmpmeta>")) } ?: ByteArray(0)
            val meta = fullBox("meta", 0, 0, hdlr + dinf + pitm + iinf + iprp + idat + iloc + extra)
            val ftyp = box("ftyp", ascii(majorBrand) + u32(0) + ascii("mif1") + ascii("heic"))
            // A `uuid` box carries its 16-byte extended type first.
            val before = boxBeforeMdat?.let { box(it, ascii("0123456789abcdef") + ascii("Test Phone")) } ?: ByteArray(0)
            val mdatPayload = image + (if (exifMethod == 0) exif else ByteArray(0)) + xmp + unreferenced
            val mdat = box("mdat", mdatPayload)
            val mpvd = if (motionVideo) box("mpvd", MediaFixtures.motionVideo()) else ByteArray(0)
            return ftyp + meta + before + mdat + mpvd
        }

        private fun ByteArray.indexOfBox(type: String): Int = IsoBmff.boxes(this, 0, size)!!.first { it.type == type }.start

        private fun infe(id: Int, type: String, name: String, protection: Int = 0, contentType: String? = null): ByteArray {
            val tail = ascii(name) + byteArrayOf(0) + (contentType?.let { ascii(it) + byteArrayOf(0) } ?: ByteArray(0))
            return fullBox("infe", 2, 0, u16(id) + u16(protection) + ascii(type) + tail)
        }

        private fun ilocEntry(id: Int, method: Int, offset: Int, length: Int): ByteArray =
            u16(id) + u16(method) + u16(0) + u16(1) + u32(offset) + u32(length)

        private fun box(type: String, payload: ByteArray): ByteArray = u32(8 + payload.size) + ascii(type) + payload

        private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray =
            box(type, byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()) + payload)
    }

    @Test
    fun samsungHeicIsCleanedAndItsMotionVideoCut() {
        val fixture = Heic()
        val original = fixture.build()
        val before = MediaMetadataScrubber.leftoverMetadata(original)
        for (name in listOf("GPS", "MakerNote", "TIFF.Model", "XMP.photoshop:DateCreated", "XMP.Container:Directory", "HEIF.mpvd", "HEIF.itemName", "HEIF.handlerName", "HEIF.unreferenced")) {
            assertTrue("$name in $before", before.contains(name))
        }
        val mpvdAt = IsoBmff.boxes(original, 0, original.size)!!.first { it.type == "mpvd" }.start

        val clean = requireNotNull(MediaMetadataScrubber.scrubImage(original)) { "not cleaned" }

        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
        assertEquals("cut before mpvd", mpvdAt, clean.size)
        assertEquals(emptyList<String>(), MediaFixtures.survivingSecrets(clean, MediaFixtures.SECRETS + listOf("+52.5200", "Primary")))
        assertArrayEquals("image item untouched", original.copyOfRange(0, mpvdAt).imageItem(), clean.imageItem())
        assertEquals(ImageContainer.Heic, ImageHeader.container(clean))
        assertEquals(48 to 64, ImageHeader.pixelSize(clean))
        // The Exif item now holds its prefix and the orientation-only TIFF.
        val heif = IsoBmff.parseHeif(clean)!!
        val exif = heif.locations.getValue(2).extents.single()
        val at = exif.first.toInt()
        assertEquals(6L, clean.u32be(at))
        assertTrue(clean.hasAscii(at + 4, "Exif\u0000\u0000"))
        assertEquals(6, MinimalTiff.orientation(clean, at + 10, exif.last.toInt() + 1))
    }

    @Test
    fun exifInIdatIsScrubbedToo() {
        val original = Heic(exifMethod = 1).build()
        assertTrue(MediaMetadataScrubber.leftoverMetadata(original).contains("GPS"))
        val clean = requireNotNull(MediaMetadataScrubber.scrubImage(original)) { "not cleaned" }
        assertEquals(emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
        assertEquals(emptyList<String>(), MediaFixtures.survivingSecrets(clean))
    }

    @Test
    fun withoutAMotionVideoNothingIsCut() {
        val original = Heic(motionVideo = false).build()
        val clean = requireNotNull(MediaMetadataScrubber.scrubImage(original)) { "not cleaned" }
        assertEquals(original.size, clean.size)
        assertFalse(MediaFixtures.text(clean).contains("unreferenced"))
    }

    @Test
    fun structuresThatCannotBeProvenCleanAreRefused() {
        val refused = mapOf(
            "Exif built from other items" to Heic(exifMethod = 2),
            "uri item" to Heic(extraItem = "uri " to null),
            "non-XMP mime item" to Heic(extraItem = "mime" to "application/json"),
            "unknown item type" to Heic(extraItem = "zzzz" to null),
            "protected item" to Heic(exifProtected = true),
            "XML box in meta" to Heic(extraMetaChild = "xml "),
            "unknown box before the data" to Heic(boxBeforeMdat = "uuid"),
            "image sequence" to Heic(boxBeforeMdat = "moov"),
            "not a picture handler" to Heic(handler = "vide"),
            "user description property" to Heic(extraProperty = "udes"),
            "creation time property" to Heic(extraProperty = "crtt"),
            "modification time property" to Heic(extraProperty = "mdft"),
            "accessibility text property" to Heic(extraProperty = "altt"),
            "unknown property" to Heic(extraProperty = "zzzz"),
            "data in another file" to Heic(selfContainedDinf = false),
        )
        for ((label, fixture) in refused) {
            assertNull(label, MediaMetadataScrubber.scrubImage(fixture.build()))
            assertFalse("$label is reported", MediaMetadataScrubber.leftoverMetadata(fixture.build()).isEmpty())
        }
        assertNull("truncated", MediaMetadataScrubber.scrubImage(Heic().build().copyOf(100)))
    }

    @Test
    fun aSelfContainedDataReferenceAndDecodingPropertiesAreKept() {
        for (property in listOf("clap", "pasp", "imir", "clli", "mdcv")) {
            val original = Heic(selfContainedDinf = true, extraProperty = property).build()
            val clean = requireNotNull(MediaMetadataScrubber.scrubImage(original)) { "$property: not cleaned" }
            assertEquals(property, emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
            val before = IsoBmff.parseHeif(original)!!
            val after = IsoBmff.parseHeif(clean)!!
            val iprp = { data: ByteArray, heif: IsoBmff.Heif -> heif.metaChildren.first { it.type == "iprp" }.let { data.copyOfRange(it.start, it.end) } }
            assertArrayEquals("$property: properties untouched", iprp(original, before), iprp(clean, after))
        }
    }

    @Test
    fun theHandlerNameBecomesSpaces() {
        val original = Heic(motionVideo = false).build()
        val clean = requireNotNull(MediaMetadataScrubber.scrubImage(original)) { "not cleaned" }
        val hdlr = IsoBmff.parseHeif(clean)!!.handlerBox!!
        val name = hdlr.payload + IsoBmff.HANDLER_NAME_OFFSET
        assertEquals("pict", clean.fourCc(hdlr.payload + 8))
        assertTrue(clean.allEqual(name, name + "Test Phone HEIF handler".length, SPACE))
        assertEquals(0, clean.u8(hdlr.end - 1))
        assertEquals(original.size, clean.size)
    }

    @Test
    fun avifIsNotAPassthroughContainer() {
        val avif = Heic(majorBrand = "avif").build()
        assertEquals(ImageContainer.Avif, ImageHeader.container(avif))
        assertFalse(ImageHeader.container(avif)!!.allowsPassthrough)
    }

    private fun ByteArray.imageItem(): ByteArray {
        val heif = IsoBmff.parseHeif(this)!!
        val extent = heif.locations.getValue(1).extents.single()
        return copyOfRange(extent.first.toInt(), extent.last.toInt() + 1)
    }
}
