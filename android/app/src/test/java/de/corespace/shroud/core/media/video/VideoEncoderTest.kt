package de.corespace.shroud.core.media.video

import android.net.Uri
import de.corespace.shroud.core.storage.SensitiveTempFiles
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The encode order of `VideoMedia.encode` (`ios/shroud/Services/Crypto/VideoMedia.swift:193-364`) on
 * Android (media-voice-links §6.4): passthrough remux first when the plan allows it, else the plan's
 * re-encode, one retry at the next rung after an overshoot, `TooLarge` after that; progress windows
 * that only move forward; no `shroud-export-*` file left behind but the accepted one. Media3 itself
 * is scripted here and exercised on a device by `VideoEncoderDeviceTest`. Robolectric for [Uri].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoEncoderTest {
    @get:Rule val temp = TempDirRule()

    private val source: Uri = Uri.parse("content://media/picker/0/video/42")

    private val h264 = VideoProbe(3.0, 640, 360, 1_000_000, true, "mp4", "video/avc", "audio/mp4a-latm")
    private val big = VideoProbe(8.0, 1920, 1080, 2_000_000, true, "mov", "video/avc", "audio/mp4a-latm")
    private val poster = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)

    private class Inspector(
        var probe: VideoProbe?,
        val poster: ByteArray?,
        val info: ExportedVideoInfo? = null,
    ) : VideoInspector {
        val posterCalls = ArrayList<Pair<Double, Int>>()

        override suspend fun probe(uri: Uri): VideoProbe? = probe

        override suspend fun posterJpeg(uri: Uri, atSeconds: Double, maxEdgePx: Int): ByteArray? {
            posterCalls += atSeconds to maxEdgePx
            return poster
        }

        override suspend fun outputInfo(file: File): ExportedVideoInfo? = info
    }

    /** One scripted export: report [fractions], then write [bytes] or throw [error] or wait for cancel. */
    private class Step(
        val bytes: Int = 100,
        val fractions: List<Double> = listOf(0.0, 0.5, 1.0),
        val error: Exception? = null,
        val hang: Boolean = false,
    )

    private class Exporter(vararg steps: Step) : VideoExporter {
        private val script = ArrayDeque(steps.toList())
        val requests = ArrayList<VideoExportRequest>()
        val outputs = ArrayList<File>()
        val started = CompletableDeferred<Unit>()

        override suspend fun export(request: VideoExportRequest, output: File, onProgress: (Double) -> Unit) {
            requests += request
            outputs += output
            assertTrue("the encoder hands over an existing empty file", output.exists() && output.length() == 0L)
            val step = script.removeFirst()
            started.complete(Unit)
            step.fractions.forEach(onProgress)
            if (step.hang) awaitCancellation()
            step.error?.let { throw it }
            output.writeBytes(ByteArray(step.bytes) { 7 })
        }
    }

    private fun encoder(inspector: Inspector, exporter: Exporter, cap: Long = 1_000) =
        VideoEncoder(inspector, exporter, SensitiveTempFiles(temp.cacheDir), maxPlaintextBytes = cap)

    private fun leftovers(): List<String> = temp.cacheDir.listFiles().orEmpty().map { it.name }.sorted()

    // MARK: - VideoMediaEncodeTests.encodeReportsProgressThatNeverGoesBackwards (:231-243)

    @Test
    fun encodeReportsProgressThatNeverGoesBackwards() = runTest {
        val values = ArrayList<Double>()
        val exporter = Exporter(Step(fractions = listOf(0.0, 0.4, 0.2, 0.7, 1.0)))
        val out = encoder(Inspector(big, poster), exporter).encode(VideoSendPlan(source), values::add)

        assertTrue(out.sizeBytes > 0)
        assertEquals("video/mp4", out.mime)
        assertEquals(1.0, values.last(), 0.0)
        assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
        assertTrue("1 only once accepted", values.dropLast(1).all { it < 1.0 })
        // Attempt 0 fills 0…0.9 (`progressWindow`).
        assertEquals(0.9 * 0.7, values[values.size - 3], 1e-9)
    }

    @Test
    fun aFittingMp4IsRemuxedNotReencoded() = runTest {
        val inspector = Inspector(h264, poster, ExportedVideoInfo(640, 360))
        val exporter = Exporter(Step(bytes = 500))
        val out = encoder(inspector, exporter).encode(VideoSendPlan(source, quality = VideoUploadQuality.Small), null)

        assertEquals(1, exporter.requests.size)
        val request = exporter.requests.single()
        assertTrue(request.plan.passthrough)
        assertNull(request.clip)
        assertFalse(request.removeAudio)
        assertSame(source, request.source)
        assertEquals(exporter.outputs.single(), out.file)
        assertEquals(500L, out.sizeBytes)
        assertEquals(640 to 360, out.width to out.height)
        assertEquals(3000, out.durationMs)
        assertArrayEquals(poster, out.posterJpeg!!.toByteArray())
        // Poster from the first frame the recipient sees, 720 px (`VideoMedia.swift:219-224`).
        assertEquals(listOf(0.0 to 720), inspector.posterCalls)
        assertEquals(listOf(out.file.name), leftovers())
        assertTrue(out.file.name.startsWith("shroud-export-") && out.file.name.endsWith(".mp4"))
    }

    @Test
    fun aFailedRemuxFallsBackToTheReencode() = runTest {
        val values = ArrayList<Double>()
        val exporter = Exporter(
            Step(fractions = listOf(0.0, 0.5), error = VideoException(VideoException.Reason.ExportFailed)),
            Step(fractions = listOf(0.0, 0.5, 1.0)),
        )
        val out = encoder(Inspector(h264, poster), exporter).encode(VideoSendPlan(source, quality = VideoUploadQuality.Small), values::add)

        assertEquals(listOf(true, false), exporter.requests.map { it.plan.passthrough })
        // The re-encode continues the ring in attempt 1's window (0.9…0.99) instead of going back to 0.
        assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(values.any { kotlin.math.abs(it - 0.945) < 1e-9 })
        assertEquals(1.0, values.last(), 0.0)
        assertEquals(listOf(out.file.name), leftovers())
        // Without an output size the plan's size stands in (`VideoMedia.swift:326-334`).
        assertEquals(640 to 360, out.width to out.height)
    }

    @Test
    fun aRemuxOverTheCapFallsBackToTheReencode() = runTest {
        val exporter = Exporter(Step(bytes = 2_000), Step(bytes = 200))
        val out = encoder(Inspector(h264, poster), exporter).encode(VideoSendPlan(source, quality = VideoUploadQuality.Small), null)
        assertEquals(listOf(true, false), exporter.requests.map { it.plan.passthrough })
        assertEquals(200L, out.sizeBytes)
        assertEquals(listOf(out.file.name), leftovers())
    }

    // MARK: - Size check and one retry (media §6.4 step 7)

    @Test
    fun anOvershootGetsOneRetryAtTheNextRung() = runTest {
        val exporter = Exporter(Step(bytes = 1_001), Step(bytes = 1_000))
        val out = encoder(Inspector(big, poster), exporter).encode(VideoSendPlan(source), null)

        assertEquals(listOf(1280 to 720, 960 to 540), exporter.requests.map { it.plan.width to it.plan.height })
        assertTrue(exporter.requests.none { it.plan.passthrough })
        assertEquals(960 to 540, out.width to out.height)
        assertEquals(listOf(out.file.name), leftovers())
    }

    @Test
    fun theRetrySkipsARungEstimatedWithinTheMarginOfTheCap() = runTest {
        // 10 000 s at High: 1280×720 is budget-bound (≈ 0.918 of the cap), 960×540 is estimated at
        // 1 848 648 000 bytes — over 85 % of the cap — so the one retry goes to 640×360
        // (`exportCandidates`, `VideoMedia.swift:666-686`).
        val long = VideoProbe(10_000.0, 1920, 1080, 2_000_000, true, "mov", "video/avc", "audio/mp4a-latm")
        val ladder = VideoPlanner.encodeLadder(long, null, false, VideoUploadQuality.High)
        assertEquals(1_848_648_000L, ladder[1].estimatedBytes)
        assertTrue(ladder[1].estimatedBytes > VideoPlanner.MAX_PLAINTEXT_BYTES * VideoPlanner.ESTIMATE_MARGIN)
        assertTrue(ladder[2].estimatedBytes <= VideoPlanner.MAX_PLAINTEXT_BYTES * VideoPlanner.ESTIMATE_MARGIN)

        val exporter = Exporter(Step(bytes = 1_001), Step(bytes = 1_000))
        val out = encoder(Inspector(long, poster), exporter).encode(VideoSendPlan(source), null)

        assertEquals(listOf(1280 to 720, 640 to 360), exporter.requests.map { it.plan.width to it.plan.height })
        assertEquals(640 to 360, out.width to out.height)
        assertEquals(listOf(out.file.name), leftovers())
    }

    @Test
    fun aSecondOvershootIsTooLarge() = runTest {
        val exporter = Exporter(Step(bytes = 1_001), Step(bytes = 1_001))
        val error = encodeError { encoder(Inspector(big, poster), exporter).encode(VideoSendPlan(source), null) }
        assertEquals(VideoException.Reason.TooLarge, error.reason)
        assertEquals(2, exporter.requests.size)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun originalHasNoSmallerRungToRetry() = runTest {
        val exporter = Exporter(Step(bytes = 1_001))
        val error = encodeError {
            encoder(Inspector(big, poster), exporter).encode(VideoSendPlan(source, quality = VideoUploadQuality.Original), null)
        }
        assertEquals(VideoException.Reason.TooLarge, error.reason)
        assertEquals(1, exporter.requests.size)
        assertEquals(1920 to 1080, exporter.requests.single().plan.width to exporter.requests.single().plan.height)
    }

    @Test
    fun aPlanThatCannotFitIsTooLargeWithoutAnExport() = runTest {
        val huge = VideoProbe(4.0 * 3600, 3840, 2160, 80_000_000, true, "mov", "video/avc", "audio/mp4a-latm")
        val exporter = Exporter()
        val error = encodeError {
            encoder(Inspector(huge, poster), exporter).encode(VideoSendPlan(source, quality = VideoUploadQuality.Original), null)
        }
        assertEquals(VideoException.Reason.TooLarge, error.reason)
        assertTrue(error.cause is VideoPlanError)
        assertTrue(exporter.requests.isEmpty())
    }

    // MARK: - Trim, mute, failures

    @Test
    fun trimmedAndMutedEncodeFinishes() = runTest {
        val inspector = Inspector(h264, poster)
        val exporter = Exporter(Step())
        val trim = VideoTrim(0.5, 2.0)
        val out = encoder(inspector, exporter).encode(VideoSendPlan(source, trim = trim, removeAudio = true), null)

        assertEquals(1500, out.durationMs)
        val request = exporter.requests.single()
        assertFalse(request.plan.passthrough)
        assertEquals(trim, request.clip)
        assertTrue(request.removeAudio)
        assertEquals(0, request.plan.audioBitrate)
        assertEquals(listOf(0.5 to 720), inspector.posterCalls)
    }

    @Test
    fun aTrimCoveringTheWholeClipIsNoTrim() = runTest {
        val exporter = Exporter(Step())
        val out = encoder(Inspector(h264, poster), exporter)
            .encode(VideoSendPlan(source, trim = VideoTrim(0.0, 3.0), quality = VideoUploadQuality.Small), null)
        assertTrue(exporter.requests.single().plan.passthrough)
        assertEquals(3000, out.durationMs)
    }

    @Test
    fun anUnreadableSourceIsUnreadable() = runTest {
        val exporter = Exporter()
        val error = encodeError { encoder(Inspector(null, poster), exporter).encode(VideoSendPlan(source), null) }
        assertEquals(VideoException.Reason.Unreadable, error.reason)
        assertTrue(exporter.requests.isEmpty())
    }

    @Test
    fun aFailedReencodeIsExportFailedAndLeavesNoFile() = runTest {
        val exporter = Exporter(Step(error = IllegalStateException("codec")))
        val error = encodeError { encoder(Inspector(big, poster), exporter).encode(VideoSendPlan(source), null) }
        assertEquals(VideoException.Reason.ExportFailed, error.reason)
        assertTrue(error.cause is IllegalStateException)
        assertEquals(1, exporter.requests.size)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun anEmptyOutputIsAFailedExport() = runTest {
        val exporter = Exporter(Step(bytes = 0))
        val error = encodeError { encoder(Inspector(big, poster), exporter).encode(VideoSendPlan(source), null) }
        assertEquals(VideoException.Reason.ExportFailed, error.reason)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun aMissingPosterStillEncodes() = runTest {
        val out = encoder(Inspector(big, null), Exporter(Step())).encode(VideoSendPlan(source), null)
        assertNull(out.posterJpeg)
    }

    @Test
    fun cancellingTheCallerCancelsTheExportAndDeletesItsFile() = runTest {
        val exporter = Exporter(Step(hang = true))
        val job = async { encoder(Inspector(big, poster), exporter).encode(VideoSendPlan(source), null) }
        exporter.started.await()
        assertEquals(1, leftovers().size)
        job.cancel()
        try {
            job.await()
            fail("cancelled")
        } catch (_: CancellationException) {
            // Kotlin's cancellation, not a VideoException.
        }
        assertEquals(emptyList<String>(), leftovers())
    }

    private suspend fun encodeError(block: suspend () -> Unit): VideoException {
        try {
            block()
        } catch (e: VideoException) {
            return e
        }
        fail("expected a VideoException")
        throw AssertionError()
    }
}
