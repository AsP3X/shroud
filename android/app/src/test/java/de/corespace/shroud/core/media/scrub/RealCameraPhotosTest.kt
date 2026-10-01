package de.corespace.shroud.core.media.scrub

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real camera photos, checked byte by byte (media-voice-links §5.2; memory note "ImageIO metadata strip
 * gotchas": unit fixtures once passed while every real camera JPEG silently fell back to re-encoding).
 *
 * Camera files carry personal data, so none are committed. Point `SHROUD_REAL_PHOTOS` at a directory
 * of them to run this — the plan's matrix is a Pixel (Ultra HDR JPEG, Motion Photo), a Samsung (JPEG,
 * HEIC, motion photo) and an iPhone (HEIC, JPEG); Apple's stock simulator photos
 * (`…/CoreSimulator/Devices/<id>/data/Media/DCIM/100APPLE`) are a ready iPhone/camera set:
 *
 *     SHROUD_REAL_PHOTOS=/path/to/photos gw :app:testDebugUnitTest --tests '*RealCameraPhotosTest'
 *
 * Every JPEG/PNG/HEIC there must take the passthrough path (the scrubber cleans it; no re-encode),
 * keep its image data byte for byte, lose every camera string its Exif named, and verify clean.
 */
class RealCameraPhotosTest {
    @Test
    fun realCameraPhotosPassThroughCleanWithTheirPixels() {
        val directory = System.getenv("SHROUD_REAL_PHOTOS")?.let(::File)
        assumeTrue("set SHROUD_REAL_PHOTOS to a directory of camera photos", directory != null && directory.isDirectory)
        val photos = directory!!.listFiles().orEmpty().filter { it.isFile && ImageHeader.container(it.readBytes())?.allowsPassthrough == true }
        assumeTrue("no JPEG/PNG/HEIC files in $directory", photos.isNotEmpty())

        for (photo in photos.sortedBy { it.name }) {
            val original = photo.readBytes()
            val clean = MediaMetadataScrubber.scrubImage(original)
            assertNotNull("${photo.name} fell back to re-encoding: ${MediaMetadataScrubber.leftoverMetadata(original)}", clean)
            clean!!
            assertEquals("${photo.name} leftovers", emptyList<String>(), MediaMetadataScrubber.leftoverMetadata(clean))
            assertEquals("${photo.name} drawn size", ImageHeader.pixelSize(original), ImageHeader.pixelSize(clean))
            val secrets = cameraStrings(original)
            assertEquals("${photo.name} camera strings left", emptyList<String>(), MediaFixtures.survivingSecrets(clean, secrets))
            assertTrue("${photo.name} image data changed", imageData(original) == imageData(clean))
        }
    }

    /** Model, software, dates, serials, lens, owner — the ASCII values of the source's Exif (≥ 6 chars). */
    private fun cameraStrings(data: ByteArray): List<String> {
        val blocks = ArrayList<Pair<Int, Int>>()
        when (ImageHeader.container(data)) {
            ImageContainer.Jpeg -> JpegScrubber.parse(data, 0, data.size)?.segments
                ?.filter { it.marker == 0xE1 && data.hasAscii(it.payload, "Exif\u0000\u0000") }
                ?.forEach { blocks += (it.payload + 6) to it.end }
            ImageContainer.Png -> PngScrubber.chunks(data)?.filter { it.type == "eXIf" }?.forEach { blocks += it.data to (it.data + it.length) }
            else -> IsoBmff.parseHeif(data)?.let { heif ->
                for (item in heif.items.filter { it.type == "Exif" }) {
                    val extent = heif.locations[item.id]?.extents?.singleOrNull() ?: continue
                    val start = extent.first.toInt()
                    blocks += (start + 4 + data.u32be(start).toInt()) to (extent.last.toInt() + 1)
                }
            }
        }
        val tags = setOf(0x0110, 0x0131, 0x0132, 0x013B, 0x013C, 0x8298, 0x9003, 0x9004, 0xA420, 0xA430, 0xA431, 0xA434, 0xA435)
        val found = LinkedHashSet<String>()
        for ((start, end) in blocks) collectAscii(data, start, end, tags, found)
        return found.filter { it.trim().length >= 6 }
    }

    private fun collectAscii(data: ByteArray, start: Int, end: Int, tags: Set<Int>, out: MutableSet<String>) {
        val tiff = MinimalTiff.Reader.open(data, start, end) ?: return
        val queue = ArrayDeque(listOf(tiff.firstIfd))
        val seen = HashSet<Long>()
        while (queue.isNotEmpty()) {
            val offset = queue.removeFirst()
            if (!seen.add(offset)) continue
            val ifd = tiff.readIfd(offset) ?: continue
            for (entry in ifd.entries) {
                if (entry.tag == 0x8769) queue += tiff.u32(entry.valueField)
                if (entry.tag !in tags || entry.type != 2 || entry.count < 6 || entry.count > 256) continue
                val at = tiff.u32(entry.valueField)
                if (at + entry.count > tiff.length) continue
                out += String(data, start + at.toInt(), entry.count.toInt() - 1, Charsets.ISO_8859_1).trimEnd('\u0000')
            }
        }
    }

    /** The coded image data: JPEG scans (all bytes after the first SOS), PNG image chunks, HEIF image items. */
    private fun imageData(data: ByteArray): String {
        val out = java.io.ByteArrayOutputStream()
        when (ImageHeader.container(data)) {
            ImageContainer.Jpeg -> {
                val layout = JpegScrubber.parse(data, 0, data.size)!!
                val sos = layout.segments.first { it.marker == 0xDA }
                out.write(data, sos.start, layout.eoiEnd - sos.start)
            }
            ImageContainer.Png -> PngScrubber.chunks(data)!!.filter { it.type == "IDAT" || it.type == "fdAT" }
                .forEach { out.write(data, it.start, it.end - it.start) }
            else -> {
                val heif = IsoBmff.parseHeif(data)!!
                heif.items.filter { it.type != "Exif" && it.type != "mime" }
                    .flatMap { item -> heif.locations[item.id]?.extents.orEmpty() }
                    .forEach { out.write(data, it.first.toInt(), (it.last - it.first + 1).toInt()) }
            }
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
