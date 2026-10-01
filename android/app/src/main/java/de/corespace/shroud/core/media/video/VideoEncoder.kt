package de.corespace.shroud.core.media.video

import android.net.Uri
import de.corespace.shroud.core.media.EncodedVideo
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.storage.SensitiveTempFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/**
 * Prepares a picked or captured movie for sending: applies the compose trim and mute, re-encodes
 * at the plan's size and bitrate or remuxes a fitting H.264/AAC MP4, and always writes a fresh MP4
 * without the source's metadata (`VideoMedia.encode`, `ios/shroud/Services/Crypto/VideoMedia.swift:183-364`;
 * media-voice-links §6.4, D6, D7).
 *
 * Order, as on iOS:
 * 1. Probe the source (no video track → [VideoException.Reason.Unreadable], `:200-203`).
 * 2. A trim that covers the whole clip is no trim (`:213-217`); `durationMs = max(1, round(kept × 1000))`.
 * 3. Poster from the first frame the recipient sees: JPEG 72 %, 720 px, at the trim start (`:219-224`).
 * 4. Passthrough when the plan allows it (`:226-255`): a remux through the metadata-clearing muxer;
 *    if that remux fails or comes out over the cap, the clip is re-encoded like any other.
 * 5. Re-encode at the plan (iOS `exportAtPlanBitrate`, `:366-422`; Android skips iOS's presets,
 *    media D6). An output over [maxPlaintextBytes] (encoder overshoot) gets **one** retry at the next
 *    rung of the ladder; still too big, or no smaller rung (Original) → [VideoException.Reason.TooLarge].
 *    A plan that cannot fit at all is `TooLarge` too (`:398-400`).
 *
 * Progress: each export attempt fills [VideoPlanner.progressWindow] of its attempt number, the
 * reported value never goes backwards, and 1.0 is reported only for the accepted output
 * (`VideoMediaEncodeTests.encodeReportsProgressThatNeverGoesBackwards`).
 *
 * Files: each attempt writes `cacheDir/shroud-export-*.mp4` ([SensitiveTempFiles], swept at launch
 * and on lock). A rejected or failed attempt deletes its file at once; the accepted one is
 * [EncodedVideo.file], owned by the caller, who deletes it once it is sealed (plan §1.5, C28). A
 * cancelled caller gets its `CancellationException` and no file is left behind.
 */
class VideoEncoder internal constructor(
    private val inspector: VideoInspector,
    private val exporter: VideoExporter,
    private val tempFiles: SensitiveTempFiles,
    private val maxPlaintextBytes: Long = VideoPlanner.MAX_PLAINTEXT_BYTES,
) {
    suspend fun encode(plan: VideoSendPlan, onProgress: ((Double) -> Unit)?): EncodedVideo {
        val probe = inspector.probe(plan.sourceUri) ?: throw VideoException(VideoException.Reason.Unreadable)
        val trim = plan.trim?.takeUnless { it.isFullRange(probe.durationSeconds) }
        val keptSeconds = trim?.duration ?: probe.durationSeconds
        val durationMs = maxOf(1L, Math.round(keptSeconds * 1000)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val poster = inspector.posterJpeg(plan.sourceUri, atSeconds = trim?.start ?: 0.0, maxEdgePx = POSTER_EDGE_PX)
        currentCoroutineContext().ensureActive()

        val progress = MonotonicProgress(onProgress)
        var attempt = 0

        // Runs one export into a fresh temp file; the file is the caller's on success only. An empty
        // output counts as a failed export.
        suspend fun export(request: VideoExportRequest): File {
            val window = VideoPlanner.progressWindow(attempt++)
            val output = try {
                tempFiles.create(EXPORT_STEM, "mp4")
            } catch (e: IOException) {
                throw VideoException(VideoException.Reason.ExportFailed, e)
            }
            try {
                exporter.export(request, output) { fraction ->
                    val f = if (fraction.isNaN()) 0.0 else fraction.coerceIn(0.0, 1.0)
                    progress.report(window.start + f * (window.endInclusive - window.start))
                }
                currentCoroutineContext().ensureActive()
                if (output.length() <= 0L) throw VideoException(VideoException.Reason.ExportFailed)
                return output
            } catch (e: CancellationException) {
                output.delete()
                throw e
            } catch (e: VideoException) {
                output.delete()
                throw e
            } catch (e: Exception) {
                output.delete()
                throw VideoException(VideoException.Reason.ExportFailed, e)
            }
        }

        suspend fun accept(output: File, fallbackWidth: Int, fallbackHeight: Int): EncodedVideo {
            try {
                val info = inspector.outputInfo(output)
                currentCoroutineContext().ensureActive()
                val size = output.length()
                progress.finish()
                return EncodedVideo(
                    file = output,
                    width = info?.width ?: fallbackWidth,
                    height = info?.height ?: fallbackHeight,
                    durationMs = durationMs,
                    mime = VideoFormats.MIME_MP4,
                    posterJpeg = poster?.let(Bytes::adopt),
                    sizeBytes = size,
                )
            } catch (t: Throwable) {
                output.delete()
                throw t
            }
        }

        val first = planOrTooLarge {
            VideoPlanner.previewPlan(probe, probe.fileExtension, trim, plan.removeAudio, plan.quality)
        }
        if (first.passthrough) {
            val remuxed = try {
                export(VideoExportRequest(plan.sourceUri, clip = null, removeAudio = false, plan = first))
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                null // `try? await export(…passthrough…)`: fall through to the re-encode (`:241`).
            }
            if (remuxed != null) {
                if (fits(remuxed)) return accept(remuxed, probe.width, probe.height)
                remuxed.delete()
            }
        }

        val ladder = planOrTooLarge { VideoPlanner.encodeLadder(probe, trim, plan.removeAudio, plan.quality) }
        for (step in ladder.take(1 + SIZE_RETRIES)) {
            val output = export(VideoExportRequest(plan.sourceUri, clip = trim, removeAudio = plan.removeAudio, plan = step))
            if (fits(output)) return accept(output, step.width, step.height)
            output.delete()
        }
        throw VideoException(VideoException.Reason.TooLarge)
    }

    /** Under the cap (`data.count <= maxPlaintextBytes`, `VideoMedia.swift:244, 319, 412`). */
    private fun fits(output: File): Boolean = output.length() <= maxPlaintextBytes

    private inline fun <T> planOrTooLarge(block: () -> T): T = try {
        block()
    } catch (e: VideoPlanError) {
        throw VideoException(VideoException.Reason.TooLarge, e)
    }

    /** Forwards only values that move the ring forward; 1.0 is kept for the accepted output. */
    private class MonotonicProgress(private val sink: ((Double) -> Unit)?) {
        private var last = -1.0

        fun report(value: Double) {
            val v = value.coerceIn(0.0, MAX_BEFORE_ACCEPT)
            if (v <= last) return
            last = v
            sink?.invoke(v)
        }

        fun finish() {
            last = 1.0
            sink?.invoke(1.0)
        }
    }

    companion object {
        /** Poster edge of an outgoing video (`VideoMedia.swift:220-224`, `maxEdge: 720`). */
        const val POSTER_EDGE_PX = 720

        /** Retries after an encoder overshoot (media §6.4 step 7). */
        private const val SIZE_RETRIES = 1

        private const val EXPORT_STEM = "export"

        /** Until an export is accepted the ring stays under 1 (`VideoMedia.swift:688-691`). */
        private const val MAX_BEFORE_ACCEPT = 0.999
    }
}

/** One export the encoder asks for. [plan] passthrough → remux only; else re-encode at the plan. */
internal data class VideoExportRequest(
    val source: Uri,
    /** The kept range, or null for the whole clip. */
    val clip: VideoTrim?,
    val removeAudio: Boolean,
    val plan: VideoOutgoingPlan,
) {
    /** Never prints the source. */
    override fun toString(): String = "VideoExportRequest(clip=$clip, removeAudio=$removeAudio, plan=$plan)"
}

/** Media3 Transformer on a device ([Media3VideoExporter]); scripted in the JVM tests. */
internal interface VideoExporter {
    /**
     * Writes [request] into [output] (an existing empty file), reporting 0…1. Throws
     * [VideoException] with [VideoException.Reason.ExportFailed] on failure; a cancelled caller
     * cancels the export and gets its `CancellationException`.
     */
    suspend fun export(request: VideoExportRequest, output: File, onProgress: (Double) -> Unit)
}

/** Display size of a finished export (`VideoMedia.swift:323-334`). */
internal data class ExportedVideoInfo(val width: Int, val height: Int)

/** Metadata and stills without decoding whole clips (`MediaMetadataRetriever` on a device). */
internal interface VideoInspector {
    /** Duration, display size, size on disk, audio and codecs; null when there is no video track. */
    suspend fun probe(uri: Uri): VideoProbe?

    /** One frame at [atSeconds] (±0.3 s on iOS) as JPEG 72 %, at most [maxEdgePx] on its long edge. */
    suspend fun posterJpeg(uri: Uri, atSeconds: Double, maxEdgePx: Int): ByteArray?

    /** The display size of an export, read back from the file. */
    suspend fun outputInfo(file: File): ExportedVideoInfo?
}
