package de.corespace.shroud.core.voice

import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * [Mp4MetadataBlanker] on hand-built MPEG-4 files shaped like `MediaMuxer`'s output: the movie-level
 * QuickTime `meta` (`com.android.version`) and any `udta` (`©xyz`) become zeroed `free` boxes of the
 * same size, while `ftyp`, `mvhd`, the track's sample tables and `mdat` stay byte for byte
 * (media-voice-links §5.3 asks the same of video).
 */
class Mp4MetadataBlankerTest {
    @get:Rule val temp = TempDirRule()

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream().apply { children.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()
    }

    private fun largeBox(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(16 + payload.size).putInt(1).put(type.toByteArray(Charsets.ISO_8859_1)).putLong(16L + payload.size).put(payload).array()

    private fun raw(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val ftyp = box("ftyp", raw("mp42"), bytes(0, 0, 0, 0), raw("isommp42"))
    private val mvhd = box("mvhd", ByteArray(100) { (it % 7).toByte() })
    private val mdia = box("mdia", box("mdhd", ByteArray(24) { 3 }), box("minf", box("stbl", box("stco", bytes(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 40)))))
    private val trakUdta = box("udta", box("name", raw("Microphone")))
    private val trak = box("trak", box("tkhd", ByteArray(84) { 1 }), mdia, trakUdta)
    private val androidMeta = box("meta", box("hdlr", ByteArray(4), raw("mdta")), box("keys", raw("com.android.version")), box("ilst", raw("16")))
    private val location = box("udta", box("©xyz", raw("+52.5200+013.4050/")))
    private val mdat = box("mdat", ByteArray(64) { (it * 3).toByte() })

    private fun write(vararg boxes: ByteArray): File {
        val file = temp.file("note.m4a")
        file.writeBytes(ByteArrayOutputStream().apply { boxes.forEach { write(it) } }.toByteArray())
        return file
    }

    private fun ByteArray.indexOf(needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun ByteArray.contains(text: String) = indexOf(raw(text)) >= 0

    private fun assertBlanked(after: ByteArray, original: ByteArray, at: Int, header: Int = 8) {
        assertEquals("free", String(after, at + 4, 4, Charsets.ISO_8859_1))
        assertArrayEquals("the size field stays", original.copyOfRange(at, at + 4), after.copyOfRange(at, at + 4))
        val end = at + ByteBuffer.wrap(original, at, 4).int.let { if (it == 1) ByteBuffer.wrap(original, at + 8, 8).long.toInt() else it }
        assertTrue("payload zeroed", after.copyOfRange(at + header, end).all { it == 0.toByte() })
    }

    @Test
    fun movieAndTrackMetadataBecomeZeroedFreeBoxesOfTheSameSize() {
        val moov = box("moov", mvhd, trak, androidMeta, location)
        val file = write(ftyp, moov, mdat)
        val before = file.readBytes()

        assertEquals(3, Mp4MetadataBlanker.blank(file))
        val after = file.readBytes()

        assertEquals(before.size, after.size)
        assertFalse(after.contains("com.android"))
        assertFalse(after.contains("+52.5200"))
        assertFalse(after.contains("Microphone"))
        assertFalse(after.contains("meta"))
        assertFalse(after.contains("udta"))

        val moovAt = ftyp.size
        val metaAt = moovAt + 8 + mvhd.size + trak.size
        assertBlanked(after, before, metaAt)
        assertBlanked(after, before, metaAt + androidMeta.size)
        assertBlanked(after, before, moovAt + 8 + mvhd.size + 8 + 92 + mdia.size)

        // Everything that plays the note is untouched.
        assertArrayEquals(ftyp, after.copyOfRange(0, ftyp.size))
        assertArrayEquals(mvhd, after.copyOfRange(moovAt + 8, moovAt + 8 + mvhd.size))
        val mdiaAt = moovAt + 8 + mvhd.size + 8 + 92
        assertArrayEquals(mdia, after.copyOfRange(mdiaAt, mdiaAt + mdia.size))
        assertArrayEquals(mdat, after.copyOfRange(after.size - mdat.size, after.size))
    }

    /** MediaMuxer may write `moov` in front of `mdat` (reserved space) or after it; both are found. */
    @Test
    fun theMovieIsFoundBeforeOrAfterTheSamples() {
        val file = write(ftyp, mdat, box("moov", mvhd, trak, androidMeta))
        assertEquals(2, Mp4MetadataBlanker.blank(file))
        assertFalse(file.readBytes().contains("com.android"))
    }

    @Test
    fun aFileWithoutMetadataIsLeftAlone() {
        val file = write(ftyp, box("moov", mvhd, box("trak", box("tkhd", ByteArray(84)), mdia)), box("free", ByteArray(16)), mdat)
        val before = file.readBytes()
        assertEquals(0, Mp4MetadataBlanker.blank(file))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun blankingTwiceChangesNothingMore() {
        val file = write(ftyp, box("moov", mvhd, trak, androidMeta), mdat)
        Mp4MetadataBlanker.blank(file)
        val once = file.readBytes()
        assertEquals(0, Mp4MetadataBlanker.blank(file))
        assertArrayEquals(once, file.readBytes())
    }

    /** 64-bit `largesize` boxes and a final size-0 ("to the end of the file") box. */
    @Test
    fun largeSizeAndToTheEndBoxesAreWalked() {
        val bigMeta = largeBox("meta", raw("com.android.version=16"))
        val moov = largeBox("moov", mvhd + bigMeta)
        val openEnded = ByteBuffer.allocate(8 + 32).putInt(0).put(raw("mdat")).put(ByteArray(32) { 9 }).array()
        val file = write(ftyp, moov, openEnded)
        val before = file.readBytes()

        assertEquals(1, Mp4MetadataBlanker.blank(file))
        val after = file.readBytes()
        assertFalse(after.contains("com.android"))
        assertBlanked(after, before, ftyp.size + 16 + mvhd.size, header = 16)
        assertArrayEquals(openEnded, after.copyOfRange(after.size - openEnded.size, after.size))
    }

    @Test
    fun aBoxThatOverrunsItsParentIsAnError() {
        val broken = ByteBuffer.allocate(16).putInt(4096).put(raw("moov")).put(ByteArray(8)).array()
        val file = write(ftyp, broken)
        try {
            Mp4MetadataBlanker.blank(file)
            fail("expected IOException")
        } catch (_: IOException) {
            // The writer fails the take.
        }
    }

    @Test
    fun aBoxSmallerThanItsHeaderIsAnError() {
        val broken = ByteBuffer.allocate(8).putInt(4).put(raw("moov")).array()
        val file = write(ftyp, broken)
        try {
            Mp4MetadataBlanker.blank(file)
            fail("expected IOException")
        } catch (_: IOException) {
            // The writer fails the take.
        }
    }
}
