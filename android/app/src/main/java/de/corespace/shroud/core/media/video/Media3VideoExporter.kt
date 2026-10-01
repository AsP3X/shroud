package de.corespace.shroud.core.media.video

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import de.corespace.shroud.core.model.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One export with Media3 Transformer 1.11.1 (media-voice-links §6.4 steps 2–6, 8; D6):
 *
 * - **Passthrough** ([VideoOutgoingPlan.passthrough]): no effects, no clipping, no requested codec
 *   or encoder settings, so Transformer transmuxes the H.264/AAC streams into a fresh MP4 — iOS's
 *   `AVAssetExportPresetPassthrough` remux (`VideoMedia.swift:241`).
 * - **Re-encode**: the kept range as a `ClippingConfiguration`; `Presentation` scaled to fit the
 *   plan's display size; `FrameDropEffect` when the plan lowers the frame rate; H.264 at the plan's
 *   video bitrate (at least 40 kbps) with a key frame every 2 s, AAC at the plan's audio bitrate (at
 *   least 32 kbps) or no audio — iOS `writeBudget` (`VideoMedia.swift:783-929`: `max(40_000, …)`,
 *   `AVVideoMaxKeyFrameIntervalKey: frameRate × 2`, `max(32_000, …)`). Requested encoder settings
 *   also make Transformer re-encode the audio, so its bitrate follows the plan. HDR sources are
 *   tone-mapped to SDR, so the output is 8-bit H.264 every recipient decodes.
 *
 * Both write through [MetadataClearingMuxer] (media §5.3). Progress is polled every 150 ms like the
 * iOS export monitor (`session.states(updateInterval: 0.15)`, `VideoMedia.swift:757-765`).
 * Transformer lives on the main looper (it calls back on the thread that built it); the work itself
 * runs on Media3's own threads. A cancelled caller cancels the export (`transformer.cancel()`).
 */
@OptIn(UnstableApi::class)
internal class Media3VideoExporter(
    private val context: Context,
    private val clock: AppClock,
) : VideoExporter {
    override suspend fun export(request: VideoExportRequest, output: File, onProgress: (Double) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            val finished = CompletableDeferred<Unit>()
            val transformer = try {
                buildTransformer(request.plan)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            finished.complete(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            finished.completeExceptionally(VideoException(VideoException.Reason.ExportFailed, exportException))
                        }
                    })
                    .build()
            } catch (e: RuntimeException) {
                throw VideoException(VideoException.Reason.ExportFailed, e)
            }
            try {
                transformer.start(composition(request), output.absolutePath)
            } catch (e: RuntimeException) {
                throw VideoException(VideoException.Reason.ExportFailed, e)
            }
            val poller = launch {
                val holder = ProgressHolder()
                while (true) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100.0)
                    }
                    delay(PROGRESS_INTERVAL_MS)
                }
            }
            try {
                finished.await()
                onProgress(1.0)
            } catch (e: CancellationException) {
                transformer.cancel()
                throw e
            } finally {
                poller.cancel()
            }
        }
    }

    private fun buildTransformer(plan: VideoOutgoingPlan): Transformer.Builder {
        val builder = Transformer.Builder(context).setMuxerFactory(MetadataClearingMuxer.factory(clock))
        if (plan.passthrough) return builder
        val video = VideoEncoderSettings.Builder()
            .setBitrate(maxOf(MIN_VIDEO_BITRATE, plan.videoBitrate))
            .setiFrameIntervalSeconds(KEY_FRAME_INTERVAL_S)
            .build()
        val encoders = DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(video)
        if (plan.audioBitrate > 0) {
            encoders.setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder().setBitrate(maxOf(MIN_AUDIO_BITRATE, plan.audioBitrate)).build(),
            )
        }
        return builder
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders.build())
    }

    /**
     * The one item as a composition. A re-encode tone-maps HDR sources to SDR (OpenGL): the output
     * is plain 8-bit H.264 like iOS's (its H.264 presets and `writeBudget` render SDR), not the
     * 10-bit H.264 that Media3 would keep where an encoder offers it and that browsers and most
     * phones cannot decode. SDR sources are unaffected; a remux decodes nothing.
     */
    private fun composition(request: VideoExportRequest): Composition {
        val builder = Composition.Builder(EditedMediaItemSequence.Builder(editedItem(request)).build())
        if (!request.plan.passthrough) builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        return builder.build()
    }

    private fun editedItem(request: VideoExportRequest): EditedMediaItem {
        val item = MediaItem.Builder().setUri(request.source)
        val clip = request.clip
        if (clip != null && !request.plan.passthrough) {
            item.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(Math.round(maxOf(0.0, clip.start) * 1000))
                    .setEndPositionMs(Math.round(clip.effectiveEnd * 1000))
                    .build(),
            )
        }
        val edited = EditedMediaItem.Builder(item.build())
        if (request.plan.passthrough) return edited.build()
        val effects = ArrayList<Effect>()
        effects += Presentation.createForWidthAndHeight(request.plan.width, request.plan.height, Presentation.LAYOUT_SCALE_TO_FIT)
        if (request.plan.frameRate > 0) effects += FrameDropEffect.createDefaultFrameDropEffect(request.plan.frameRate.toFloat())
        return edited
            .setRemoveAudio(request.removeAudio || request.plan.audioBitrate == 0)
            .setEffects(Effects(emptyList(), effects))
            .build()
    }

    private companion object {
        /** iOS export monitor interval (`VideoMedia.swift:759`). */
        const val PROGRESS_INTERVAL_MS = 150L

        /** `AVVideoMaxKeyFrameIntervalKey: frameRate × 2` frames = 2 s (`VideoMedia.swift:863`). */
        const val KEY_FRAME_INTERVAL_S = 2f

        /** `AVVideoAverageBitRateKey: max(40_000, videoBitrate)` (`VideoMedia.swift:861`). */
        const val MIN_VIDEO_BITRATE = 40_000

        /** `AVEncoderBitRateKey: max(32_000, audioBitrate)` (`VideoMedia.swift:884`). */
        const val MIN_AUDIO_BITRATE = 32_000
    }
}
