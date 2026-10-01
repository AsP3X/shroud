package de.corespace.shroud.core.media.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The scanner itself on hand-built box trees, so a clean verdict on an export means something. */
@RunWith(AndroidJUnit4::class)
class Mp4BoxScannerTest {
    private fun box(type: String, vararg children: ByteArray, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(payload)
            children.forEach { write(it) }
        }.toByteArray()
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
    }

    private fun keys(vararg names: String): ByteArray {
        val entries = ByteArrayOutputStream()
        for (name in names) {
            val bytes = name.toByteArray()
            entries.write(ByteBuffer.allocate(8 + bytes.size).putInt(8 + bytes.size).put("mdta".toByteArray()).put(bytes).array())
        }
        val payload = ByteBuffer.allocate(8).putInt(0).putInt(names.size).array() + entries.toByteArray()
        return box("keys", payload = payload)
    }

    private fun mvhd(creation: Long): ByteArray =
        box("mvhd", payload = ByteBuffer.allocate(100).putInt(0).putInt(creation.toInt()).array())

    @Test
    fun findsLocationDeviceAndDateInUserDataAndMdtaKeys() {
        val hdlr = box("hdlr", payload = ByteArray(25))
        val file = box("ftyp", payload = "isom".toByteArray()) + box(
            "moov",
            mvhd(3_870_000_000L),
            box("udta", box("©xyz", payload = "\u0000\u0012\u0015Ç+52.5200+013.4050/".toByteArray(Charsets.ISO_8859_1))),
            // ISO FullBox `meta` (version + flags before its children).
            box("meta", hdlr, keys("com.apple.quicktime.model", "com.android.capture.fps"), box("ilst", box("\u0000\u0000\u0000\u0001", box("data", payload = "Test Phone".toByteArray()))), payload = ByteArray(4)),
        )
        val scan = Mp4BoxScanner(file)

        assertEquals(listOf("com.apple.quicktime.model", "com.android.capture.fps"), scan.mdtaKeys)
        assertEquals(3_870_000_000L, scan.creationTime)
        val leaks = scan.identifyingMetadata(listOf("+52.5200", "Test Phone", "2026-09-01"))
        assertTrue(leaks.toString(), "box moov/udta/©xyz" in leaks)
        assertTrue(leaks.toString(), "mdta key com.apple.quicktime.model" in leaks)
        assertTrue(leaks.toString(), "string \"+52.5200\"" in leaks)
        assertTrue(leaks.toString(), "string \"Test Phone\"" in leaks)
        assertTrue(leaks.toString(), leaks.none { it.contains("2026-09-01") })
        assertTrue(scan.find("data").isNotEmpty())
    }

    @Test
    fun aCleanFileReportsNothing() {
        val file = box("ftyp", payload = "isom".toByteArray()) +
            box("moov", mvhd(1L), box("trak", box("tkhd", payload = ByteArray(84)))) +
            box("mdat", payload = ByteArray(64) { 7 })
        val scan = Mp4BoxScanner(file)
        assertEquals(emptyList<String>(), scan.identifyingMetadata(listOf("+52.5200", "Test Phone", "2026-09-01")))
        assertEquals(listOf("ftyp", "moov", "moov/mvhd", "moov/trak", "moov/trak/tkhd", "mdat"), scan.boxes.map { it.path })
    }

    @Test
    fun findsAnXmpUuidBox() {
        val uuid = Mp4BoxScanner.XMP_UUID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val scan = Mp4BoxScanner(box("moov", box("uuid", payload = uuid + "<x:xmpmeta/>".toByteArray())))
        assertEquals(listOf("XMP uuid moov/uuid"), scan.identifyingMetadata())
    }
}
