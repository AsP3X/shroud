package de.corespace.shroud.core.media.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.media.EncodedVideo
import de.corespace.shroud.core.media.VideoPipeline
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.storage.SensitiveTempFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The video services other packages use (`VideoMedia`, `ios/shroud/Services/Crypto/VideoMedia.swift`;
 * media-voice-links §6, §14.2), built only by `VideoModule`:
 *
 * - the compose screen: [probe], [poster], [filmstrip] and [VideoPlanner.previewPlan];
 * - the send path ([VideoPipeline]): [encode], [posterJpegFromLocal] and [durationMs];
 * - playback is [ChatVideoPlayer] (`VideoModule.newPlayer()`).
 *
 * Nothing here writes decrypted media except the encoder's `cacheDir/shroud-export-*.mp4` output
 * (plan §1.1 rule 7), and nothing is logged.
 */
class VideoMedia internal constructor(
    private val inspector: AndroidVideoInspector,
    private val encoder: VideoEncoder,
    private val sealedSources: SealedVideoSources,
) : VideoPipeline {
    /** Duration, display size, size, audio and codecs, without decoding frames (`VideoMedia.swift:126-146`). */
    suspend fun probe(uri: Uri): VideoProbe? = inspector.probe(uri)

    /** First frame for compose thumbnails and the optimistic bubble (`VideoMedia.swift:148-153`). */
    suspend fun poster(uri: Uri, maxEdgePx: Int = 640): Bitmap? = inspector.posterBitmap(uri, maxEdgePx)

    /** Evenly spaced stills for the compose trim strip (`VideoMedia.swift:155-181`). */
    fun filmstrip(uri: Uri, count: Int, maxEdgePx: Int = 160): Flow<Bitmap> = inspector.filmstrip(uri, count, maxEdgePx)

    /** See [VideoEncoder]. Throws [VideoException]. */
    override suspend fun encode(plan: VideoSendPlan, onProgress: ((Double) -> Unit)?): EncodedVideo =
        encoder.encode(plan, onProgress)

    /**
     * Poster of a received or sent video already in the sealed local cache, JPEG 72 % at most
     * [maxEdgePx] (`VideoMedia.thumbnailJPEG(from:)`, `VideoMedia.swift:424-436`, called after a
     * download at `MessagingController.swift:3072` and for a missing encode poster at `:2862`). Null
     * when the message has no local media, chats are locked or no frame decodes.
     */
    override suspend fun posterJpegFromLocal(messageId: UUID, maxEdgePx: Int): ByteArray? {
        val source = sealedSources.retrieverDataSource(messageId) ?: return null
        return inspector.posterJpeg(source, maxEdgePx)
    }

    /**
     * The container's duration, read off the sealed cache the way a bubble used to
     * (`VoiceMessageBubble.swift:694-708`). Null when there is no source, the retriever cannot
     * open it, or the duration is missing or not positive. Closes the source; [retrieverDurationMs]
     * closes the retriever with [MediaMetadataRetriever.close].
     */
    override suspend fun durationMs(messageId: UUID): Int? = sealedClipDuration(sealedSources, messageId, ::retrieverDurationMs)

    /** What `PUT /media/{id}/content` accepts, ciphertext included (`VideoMedia.swift:120-121`). */
    override val maxSealedBytes: Long = MediaCrypto.MAX_SEALED_BYTES

    companion object {
        /** The production graph: `MediaMetadataRetriever` probes, Media3 Transformer exports. */
        fun create(
            context: Context,
            tempFiles: SensitiveTempFiles,
            clock: AppClock,
            sealedSources: SealedVideoSources,
        ): VideoMedia {
            val app = context.applicationContext
            val inspector = AndroidVideoInspector(app)
            val encoder = VideoEncoder(inspector, Media3VideoExporter(app, clock), tempFiles)
            return VideoMedia(inspector, encoder, sealedSources)
        }
    }
}

/**
 * [VideoPipeline.durationMs] without a retriever: [read] stands in for [retrieverDurationMs] in JVM
 * tests. Null when [sources] has nothing, [read] returns null or a duration that is not positive,
 * or [read] throws. Always closes the source.
 */
internal suspend fun sealedClipDuration(
    sources: SealedVideoSources,
    messageId: UUID,
    read: (MediaDataSource) -> Int?,
): Int? = withContext(Dispatchers.IO) {
    val source = sources.retrieverDataSource(messageId) ?: return@withContext null
    try {
        read(source)?.takeIf { it > 0 }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } finally {
        try {
            source.close()
        } catch (_: Exception) {
            // The retriever already closed a source it took.
        }
    }
}

/**
 * Positive milliseconds from [source], or null when it cannot be opened or the duration is missing
 * or not positive. Closes the retriever with [MediaMetadataRetriever.close] (API 29), which also
 * closes a source [MediaMetadataRetriever.setDataSource] accepted. Not the deprecated `release()`.
 */
internal fun retrieverDurationMs(source: MediaDataSource): Int? = try {
    MediaMetadataRetriever().use { retriever ->
        try {
            retriever.setDataSource(source)
        } catch (_: Exception) {
            return@use null
        }
        try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.toInt()
        } catch (_: Exception) {
            null
        }
    }
} catch (_: Exception) {
    null
}
