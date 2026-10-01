package de.corespace.shroud.core.media.video

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4TimestampData
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.core.media.EncodedVideo
import de.corespace.shroud.core.model.SystemAppClock
import de.corespace.shroud.core.storage.SensitiveTempFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The encode cases of `VideoMediaEncodeTests` (`ios/shroudTests/VideoMediaEncodeTests.swift:179-271`)
 * and the video cases of `MediaMetadataScrubberTests` (`MediaMetadataScrubberTests.swift:143-165`)
 * on a real Media3 Transformer (media-voice-links §12.6, §5.3), plus the Android rules: an HEVC MP4
 * never passes through (D7), a rotated clip keeps its display orientation, and nothing but the
 * accepted output is left in the temp directory. Fixtures come from [TestMovies]; only sizes and
 * flags are asserted, nothing is logged.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoEncoderDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixtures: File
    private lateinit var exports: File
    private lateinit var media: VideoMedia

    @Before
    fun setUp() {
        fixtures = File(context.cacheDir, "video-test-fixtures").apply { deleteRecursively(); mkdirs() }
        exports = File(context.cacheDir, "video-test-exports").apply { deleteRecursively(); mkdirs() }
        media = VideoMedia.create(context, SensitiveTempFiles(exports), SystemAppClock, SealedVideoSources.Unavailable)
    }

    @After
    fun tearDown() {
        fixtures.deleteRecursively()
        exports.deleteRecursively()
    }

    private fun movie(name: String, spec: TestMovies.Spec): Uri = Uri.fromFile(TestMovies.write(File(fixtures, name), spec))

    private fun encode(uri: Uri, progress: ((Double) -> Unit)? = null, plan: (Uri) -> VideoSendPlan = { VideoSendPlan(it) }): EncodedVideo =
        runBlocking { media.encode(plan(uri), progress) }

    private fun tracks(file: File): Map<String, MediaFormat> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount).associate { i ->
                val format = extractor.getTrackFormat(i)
                format.getString(MediaFormat.KEY_MIME)!!.substringBefore('/') to format
            }
        } finally {
            extractor.release()
        }
    }

    private fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        } finally {
            retriever.release()
        }
    }

    private fun exportsLeft(): List<String> = exports.listFiles().orEmpty().map { it.name }.sorted()

    // MARK: - Probe and stills (VideoMedia.swift:126-181)

    @Test
    fun probeReadsDurationSizeAndCodecs() {
        val file = File(fixtures, "probe.mp4")
        val uri = Uri.fromFile(TestMovies.write(file, TestMovies.Spec(2.0, 640, 360, audio = true)))
        val probe = runBlocking { media.probe(uri) }!!
        assertEquals(2.0, probe.durationSeconds, 0.15)
        assertEquals(640 to 360, probe.width to probe.height)
        assertEquals(file.length(), probe.fileSizeBytes)
        assertTrue(probe.hasAudio)
        assertEquals("mp4", probe.fileExtension)
        assertEquals(MimeTypes.VIDEO_H264, probe.videoMime)
        assertEquals(MimeTypes.AUDIO_AAC, probe.audioMime)
        assertTrue(VideoPlanner.previewPlan(probe, trim = null, removeAudio = false, quality = VideoUploadQuality.High).passthrough)

        val rotated = runBlocking { media.probe(movie("rotated.mp4", TestMovies.Spec(1.0, 640, 360, rotation = 90))) }!!
        assertEquals(360 to 640, rotated.width to rotated.height)
        assertFalse(rotated.hasAudio)
        assertEquals(null, rotated.audioMime)
    }

    @Test
    fun anUnreadableSourceHasNoProbe() {
        val junk = File(fixtures, "junk.mp4").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        assertEquals(null, runBlocking { media.probe(Uri.fromFile(junk)) })
        try {
            encode(Uri.fromFile(junk))
            fail("expected Unreadable")
        } catch (e: VideoException) {
            assertEquals(VideoException.Reason.Unreadable, e.reason)
        }
    }

    @Test
    fun posterAndFilmstripAreSmallStills() {
        val uri = movie("stills.mp4", TestMovies.Spec(2.0, 1280, 720))
        val poster = runBlocking { media.poster(uri) }!!
        assertEquals(640, maxOf(poster.width, poster.height))
        val frames = runBlocking { media.filmstrip(uri, count = 14).toList() }
        assertEquals(14, frames.size)
        assertTrue(frames.all { maxOf(it.width, it.height) <= 160 })
    }

    // MARK: - VideoMediaEncodeTests (:179-271)

    @Test
    fun encodeReportsProgressThatNeverGoesBackwards() {
        val values = ArrayList<Double>()
        val uri = movie("progress.mp4", TestMovies.Spec(2.0, 1280, 720, audio = true))
        val out = encode(uri, progress = { synchronized(values) { values += it } }) { VideoSendPlan(it, quality = VideoUploadQuality.Medium) }

        assertTrue(out.sizeBytes > 0)
        assertTrue(out.sizeBytes <= VideoPlanner.MAX_PLAINTEXT_BYTES)
        assertEquals("video/mp4", out.mime)
        assertEquals(1.0, values.last(), 0.0)
        assertTrue(values.toString(), values.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(960 to 540, out.width to out.height)
        assertEquals(MimeTypes.VIDEO_H264, tracks(out.file)["video"]!!.getString(MediaFormat.KEY_MIME))
        assertEquals(MimeTypes.AUDIO_AAC, tracks(out.file)["audio"]!!.getString(MediaFormat.KEY_MIME))
        assertEquals(listOf(out.file.name), exportsLeft())
        out.file.delete()
    }

    @Test
    fun smallQualityExportStaysInsideTheSmallBox() {
        val out = encode(movie("small.mp4", TestMovies.Spec(1.0, 640, 360))) { VideoSendPlan(it, quality = VideoUploadQuality.Small) }
        assertTrue(maxOf(out.width, out.height) <= 640)
        assertTrue(minOf(out.width, out.height) <= 360)
        out.file.delete()
    }

    @Test
    fun smallQualityExportOfA4x3ClipFitsThe640x480Preset() {
        val out = encode(movie("four-three.mp4", TestMovies.Spec(1.0, 1440, 1080))) { VideoSendPlan(it, quality = VideoUploadQuality.Small) }
        assertEquals(640, out.width)
        assertEquals(480, out.height)
        out.file.delete()
    }

    @Test
    fun trimmedAndMutedEncodeFinishes() {
        val uri = movie("trim.mp4", TestMovies.Spec(3.0, 640, 360, audio = true))
        val out = encode(uri) { VideoSendPlan(it, trim = VideoTrim(0.5, 2.0), removeAudio = true) }
        assertTrue(out.sizeBytes > 0)
        assertEquals(1500, out.durationMs)
        assertEquals(null, tracks(out.file)["audio"])
        assertTrue(abs(durationMs(out.file) - 1500) <= 200)
        out.file.delete()
    }

    /** `budgetEncodeWritesAnMp4` (`:179-198`): the plan-bitrate export writes the plan's size. */
    @Test
    fun aPlanBitrateEncodeWrites320x180() {
        val uri = movie("budget.mp4", TestMovies.Spec(1.0, 640, 360))
        val output = File(exports, "shroud-export-budget.mp4").apply { createNewFile() }
        val plan = VideoOutgoingPlan(320, 180, 10_000, "180p", passthrough = false, videoBitrate = 80_000, audioBitrate = 0, frameRate = 15)
        runBlocking { Media3VideoExporter(context, SystemAppClock).export(VideoExportRequest(uri, null, false, plan), output) {} }

        assertTrue(output.length() > 0)
        val info = runBlocking { AndroidVideoInspector(context).outputInfo(output) }!!
        assertEquals(320 to 180, info.width to info.height)
        output.delete()
    }

    // MARK: - MediaMetadataScrubberTests, video (:143-165)

    @Test
    fun compressedVideoCarriesNoLocation() {
        val file = TestMovies.write(File(fixtures, "tagged-large.mp4"), TestMovies.Spec(1.0, 1280, 960, tagged = true))
        val source = Mp4BoxScanner(file.readBytes())
        assertTrue("the fixture is tagged", source.identifyingMetadata(TestMovies.LEAKS).size >= 3)
        assertEquals(TestMovies.TAGGED_CREATION_MP4_SECONDS, source.creationTime)

        val started = System.currentTimeMillis()
        val out = encode(Uri.fromFile(file)) { VideoSendPlan(it, quality = VideoUploadQuality.High) }
        assertEquals(960 to 720, out.width to out.height)

        val scan = Mp4BoxScanner(out.file.readBytes())
        assertEquals(emptyList<String>(), scan.identifyingMetadata(TestMovies.LEAKS))
        // Fresh times, as iOS exports write (media §5.3).
        assertTrue(scan.creationTime!! >= Mp4TimestampData.unixTimeToMp4TimeSeconds(started) - 2)
        out.file.delete()
    }

    @Test
    fun passthroughMp4IsRewrittenWithoutLocation() {
        val file = TestMovies.write(File(fixtures, "tagged.mp4"), TestMovies.Spec(1.0, 320, 240, tagged = true))
        val sourceBytes = file.readBytes()
        assertTrue("the fixture is tagged", Mp4BoxScanner(sourceBytes).identifyingMetadata(TestMovies.LEAKS).isNotEmpty())
        val probe = runBlocking { media.probe(Uri.fromFile(file)) }!!
        assertTrue(VideoPlanner.previewPlan(probe, trim = null, removeAudio = false, quality = VideoUploadQuality.Original).passthrough)

        val out = encode(Uri.fromFile(file)) { VideoSendPlan(it, quality = VideoUploadQuality.Original) }

        val outBytes = out.file.readBytes()
        assertFalse("the source file went out as it is", sourceBytes.contentEquals(outBytes))
        assertEquals(emptyList<String>(), Mp4BoxScanner(outBytes).identifyingMetadata(TestMovies.LEAKS))
        assertEquals(320 to 240, out.width to out.height)
        out.file.delete()
    }

    /**
     * Control for the two cases above: Media3's own muxer, without the provider, does carry the
     * source's location over — so an empty finding above is the provider's work.
     */
    @Test
    fun withoutTheProviderMedia3KeepsTheLocation() {
        val file = TestMovies.write(File(fixtures, "tagged-control.mp4"), TestMovies.Spec(1.0, 320, 240, tagged = true))
        val output = File(exports, "shroud-export-control.mp4")
        runBlocking {
            withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context)
                    .setMuxerFactory(InAppMp4Muxer.Factory())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.complete(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            done.completeExceptionally(exportException)
                        }
                    })
                    .build()
                transformer.start(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).build(), output.absolutePath)
                done.await()
            }
        }
        val leaks = Mp4BoxScanner(output.readBytes()).identifyingMetadata(TestMovies.LEAKS)
        assertTrue(leaks.toString(), leaks.any { it.contains("xyz") || it.contains("52.5200") })
        output.delete()
    }

    // MARK: - Android rules (media D7, §6.4)

    @Test
    fun anHevcMp4NeverPassesThrough() {
        assumeTrue("no HEVC encoder on this device", TestMovies.hasEncoder(MimeTypes.VIDEO_H265))
        val uri = movie("hevc.mp4", TestMovies.Spec(1.0, 640, 360, videoMime = MimeTypes.VIDEO_H265))
        val probe = runBlocking { media.probe(uri) }!!
        assertEquals(MimeTypes.VIDEO_H265, probe.videoMime)
        assertFalse(VideoPlanner.previewPlan(probe, trim = null, removeAudio = false, quality = VideoUploadQuality.Small).passthrough)

        val out = encode(uri) { VideoSendPlan(it, quality = VideoUploadQuality.Small) }
        assertEquals(MimeTypes.VIDEO_H264, tracks(out.file)["video"]!!.getString(MediaFormat.KEY_MIME))
        assertEquals(640 to 360, out.width to out.height)
        out.file.delete()
    }

    @Test
    fun aRotatedClipKeepsItsDisplayOrientation() {
        val uri = movie("portrait.mp4", TestMovies.Spec(1.0, 1280, 720, rotation = 90))
        val out = encode(uri) { VideoSendPlan(it, quality = VideoUploadQuality.Small) }
        assertEquals(360 to 640, out.width to out.height)
        out.file.delete()
    }

    @Test
    fun thePosterIsAJpegOfTheFirstKeptFrame() {
        val uri = movie("poster.mp4", TestMovies.Spec(3.0, 1280, 720))
        val out = encode(uri) { VideoSendPlan(it, trim = VideoTrim(1.0, 2.5)) }
        val jpeg = out.posterJpeg!!.toByteArray()
        assertArrayEquals(byteArrayOf(-1, -40), jpeg.copyOf(2))
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertNotNull(bitmap)
        assertEquals(720, maxOf(bitmap.width, bitmap.height))
        out.file.delete()
    }

    @Test
    fun cancellingAnEncodeLeavesNoFile() {
        val uri = movie("cancel.mp4", TestMovies.Spec(3.0, 1920, 1080))
        runBlocking {
            val progressed = CompletableDeferred<Unit>()
            val job = async(Dispatchers.Default) {
                media.encode(VideoSendPlan(uri, quality = VideoUploadQuality.Medium)) { if (it > 0.0) progressed.complete(Unit) }
            }
            progressed.await()
            assertEquals(1, exportsLeft().size)
            job.cancel()
            try {
                job.await()
                fail("cancelled")
            } catch (_: CancellationException) {
                // Kotlin's cancellation, not a VideoException.
            }
        }
        assertEquals(emptyList<String>(), exportsLeft())
    }
}
