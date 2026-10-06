package de.corespace.shroud.core.media.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random

/**
 * The sender's §11.2 read (docs/file-sharing.md): what the payload carries from the retriever's
 * strings, the cover's ladder and crop, and intake attaching it to the audio picks only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioMetadataTest {
    @Test
    fun theDurationIsRoundedToWholeMsAtLeastOneAndTheTagsAreCleaned() {
        val read = AudioFileMetadata.of(243_400.4, "  Midnight\tCity ", "M83​", null)
        assertEquals(243_400, read.durationMs)
        assertEquals("Midnight City", read.title)
        assertEquals("M83", read.artist)
        assertEquals(1, AudioFileMetadata.of(0.6, null, null, null).durationMs)
        assertNull(AudioFileMetadata.of(0.0, null, null, null).durationMs)
        assertNull(AudioFileMetadata.of(Double.NaN, null, null, null).durationMs)
        assertTrue(AudioFileMetadata.of(null, "   ", "", null).isEmpty)
    }

    @Test
    fun theLadderShrinksByFourFifthsAndLowersTheQualityUntilItFits() {
        val tries = ArrayList<Pair<Int, Int>>()
        val result = AudioCoverThumb.ladder { side, quality ->
            tries += side to quality
            ByteArray(if (side > 100) 7_000 else 5_000)
        }
        assertEquals(listOf(160 to 70, 128 to 60, 102 to 50, 81 to 40), tries)
        assertEquals(81, result!!.second)
    }

    @Test
    fun theLadderGivesUpBelow64Px() {
        val tries = ArrayList<Int>()
        assertNull(AudioCoverThumb.ladder { side, _ -> tries += side; ByteArray(7_000) })
        assertEquals(listOf(160, 128, 102, 81, 64), tries)
        assertNull(AudioCoverThumb.ladder { _, _ -> null })
    }

    @Test
    fun theCoverIsTheCentredSquare() {
        assertEquals(Triple(100, 0, 300), AudioCoverThumb.centreSquare(500, 300))
        assertEquals(Triple(0, 50, 200), AudioCoverThumb.centreSquare(200, 300))
        assertEquals(Triple(0, 0, 160), AudioCoverThumb.centreSquare(160, 160))
    }

    @Test
    fun anEmbeddedPictureBecomesASquareJpegOfAtMost6Kb() {
        val random = Random(7)
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        for (x in 0 until 640) for (y in 0 until 400) bitmap.setPixel(x, y, Color.rgb(random.nextInt(256), x % 256, y % 256))
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val cover = requireNotNull(AudioCoverThumb.encode(png))
        assertTrue(cover.jpeg.size <= AudioCoverThumb.MAX_BYTES)
        assertEquals(cover.width, cover.height)
        assertTrue(cover.width in AudioCoverThumb.MIN_SIZE..AudioCoverThumb.START_SIZE)
        val decoded = BitmapFactory.decodeByteArray(cover.jpeg, 0, cover.jpeg.size)
        assertEquals(cover.width, decoded.width)
        assertEquals(cover.height, decoded.height)
        // JPEG's SOI marker.
        assertEquals(0xFF.toByte(), cover.jpeg[0])
        assertEquals(0xD8.toByte(), cover.jpeg[1])
    }

    @Test
    fun aPictureThatDoesNotDecodeHasNoCover() {
        assertNull(AudioCoverThumb.encode(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun intakeReadsTheAudioPicksOnlyAndSideBySide() = runTest {
        val song = Uri.parse("content://docs/song.mp3")
        val report = Uri.parse("content://docs/report.pdf")
        val podcast = Uri.parse("content://docs/podcast.m4a")
        fun pick(name: String, uri: Uri?) = PickedFile(name, 1_000, FileTypes.forName(name)!!, uri) { InputStream.nullInputStream() }
        val noUri = pick("memo.wav", null)
        val result = FileIntake.Result(listOf(pick("song.mp3", song), pick("report.pdf", report), pick("podcast.m4a", podcast), noUri), listOf("refused"))
        val gate = CompletableDeferred<Unit>()
        val asked = ArrayList<Uri>()
        val metadata = AudioFileMetadata(durationMs = 243_400, title = "Midnight City", artist = "M83")
        val job = async {
            FileIntake.withAudioMetadata(result) { uri ->
                asked += uri
                gate.await()
                if (uri == song) metadata else throw IllegalStateException("unreadable")
            }
        }
        runCurrent()
        // Both audio picks are being read at once.
        assertEquals(listOf(song, podcast), asked)
        gate.complete(Unit)
        val out = job.await()
        assertEquals(listOf("song.mp3", "report.pdf", "podcast.m4a", "memo.wav"), out.files.map { it.name })
        assertSame(metadata, out.files[0].audio)
        assertNull(out.files[1].audio)
        assertNull("a read that throws sends the file without", out.files[2].audio)
        assertNull(out.files[3].audio)
        assertEquals(song, out.files[0].uri)
        assertNotNull(out.files[0].open())
        assertEquals(listOf("refused"), out.refusals)
    }
}
