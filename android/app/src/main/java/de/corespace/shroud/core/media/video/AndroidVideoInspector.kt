package de.corespace.shroud.core.media.video

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `MediaMetadataRetriever` and `MediaExtractor` in place of `AVURLAsset` and
 * `AVAssetImageGenerator` (media-voice-links §6.2, §13.2): metadata without decoding frames, and
 * single stills at a time. Everything runs on [Dispatchers.IO]; nothing is logged (URIs and file
 * names can identify a clip).
 */
internal class AndroidVideoInspector(private val context: Context) : VideoInspector {
    /**
     * `VideoMedia.probe` (`VideoMedia.swift:127-146`): duration (at least 0.1 s), display size
     * (rotation applied), size on disk, audio presence; plus what Android needs for the plan —
     * the container's extension from its MIME type and the track codecs (media D7, §6.3).
     * Null when the source cannot be read or has no video track.
     */
    override suspend fun probe(uri: Uri): VideoProbe? = withContext(Dispatchers.IO) {
        val meta = retrieve { setDataSource(context, uri) } ?: return@withContext null
        val tracks = trackMimes { setDataSource(context, uri, null) }
        // The picked item's own type first (media §6.3: "from the MIME of the picked item"), then
        // what the retriever sniffed, then a file name (`file://` camera captures).
        val extension = VideoFormats.extensionForMime(resolverType(uri))
            .ifEmpty { VideoFormats.extensionForMime(meta.containerMime) }
            .ifEmpty { VideoFormats.extensionOfName(uri.lastPathSegment) }
        VideoProbe(
            durationSeconds = maxOf(0.1, meta.durationMs / 1000.0),
            width = meta.width,
            height = meta.height,
            fileSizeBytes = fileSize(uri),
            hasAudio = meta.hasAudio || tracks.audio != null,
            fileExtension = extension,
            videoMime = tracks.video,
            audioMime = tracks.audio,
        )
    }

    /** `thumbnailJPEG(from:maxEdge:at:)` (`VideoMedia.swift:438-447`) on a picked or captured clip. */
    override suspend fun posterJpeg(uri: Uri, atSeconds: Double, maxEdgePx: Int): ByteArray? = withContext(Dispatchers.IO) {
        still(atSeconds, maxEdgePx, MediaMetadataRetriever.OPTION_CLOSEST) { setDataSource(context, uri) }?.let(::jpeg)
    }

    /** The finished export's display size (`VideoMedia.swift:323-334`). */
    override suspend fun outputInfo(file: File): ExportedVideoInfo? = withContext(Dispatchers.IO) {
        retrieve { setDataSource(file.absolutePath) }?.let { ExportedVideoInfo(it.width, it.height) }
    }

    /** First frame as a bitmap for the compose screen (`VideoMedia.posterImage`, `:149-153`). */
    suspend fun posterBitmap(uri: Uri, maxEdgePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        still(0.0, maxEdgePx, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) { setDataSource(context, uri) }
    }

    /**
     * Evenly spaced stills for the trim strip (`VideoMedia.filmstrip`, `:155-181`): frame `i` at
     * `duration × (i + 0.5) / count`, snapped to the nearest sync frame (iOS allows ±0.6 s); a frame
     * that cannot be read is skipped; collection stops when the collector is cancelled.
     */
    fun filmstrip(uri: Uri, count: Int, maxEdgePx: Int): Flow<Bitmap> = flow {
        if (count <= 0) return@flow
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(context, uri)
            } catch (_: RuntimeException) {
                return@flow
            }
            val meta = retriever.metadata() ?: return@flow
            val seconds = meta.durationMs / 1000.0
            if (!seconds.isFinite() || seconds <= 0) return@flow
            val box = VideoFormats.frameBox(meta.width, meta.height, maxEdgePx)
            for (index in 0 until count) {
                currentCoroutineContext().ensureActive()
                val at = seconds * (index + 0.5) / count
                val frame = try {
                    retriever.getScaledFrameAtTime(micros(at), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box, box)
                } catch (_: RuntimeException) {
                    null
                } ?: continue
                emit(frame)
            }
        } finally {
            retriever.release()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * The poster of a video already in the sealed local cache, read through [source] (no plaintext
     * file; iOS writes `tmp/shroud-thumb-*.mp4`, `VideoMedia.swift:425-436`). Closes [source].
     */
    suspend fun posterJpeg(source: MediaDataSource, maxEdgePx: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            still(0.0, maxEdgePx, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) { setDataSource(source) }?.let(::jpeg)
        } finally {
            try {
                source.close()
            } catch (_: Exception) {
                // Already closed by the retriever.
            }
        }
    }

    private class Meta(val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean, val containerMime: String?)

    private class Tracks(val video: String?, val audio: String?)

    /** Reads [Meta] from a fresh retriever; null when unreadable or without a video track. */
    private fun retrieve(open: MediaMetadataRetriever.() -> Unit): Meta? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.open()
            retriever.metadata()
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.metadata(): Meta? {
        if (extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
        val durationMs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
        val width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (displayWidth, displayHeight) = VideoFormats.displaySize(width, height, rotation)
        return Meta(
            durationMs = maxOf(0L, durationMs),
            width = displayWidth,
            height = displayHeight,
            hasAudio = extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            containerMime = extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
        )
    }

    /** The first video and audio track's codec MIME types (media D7). */
    private fun trackMimes(open: MediaExtractor.() -> Unit): Tracks {
        val extractor = MediaExtractor()
        return try {
            extractor.open()
            var video: String? = null
            var audio: String? = null
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (video == null && mime.startsWith("video/")) video = mime
                if (audio == null && mime.startsWith("audio/")) audio = mime
            }
            Tracks(video, audio)
        } catch (_: Exception) {
            Tracks(null, null)
        } finally {
            extractor.release()
        }
    }

    /** One still at [atSeconds], scaled into a [maxEdgePx] box without upscaling. */
    private fun still(atSeconds: Double, maxEdgePx: Int, option: Int, open: MediaMetadataRetriever.() -> Unit): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.open()
            val meta = retriever.metadata() ?: return null
            val box = VideoFormats.frameBox(meta.width, meta.height, maxEdgePx)
            retriever.getScaledFrameAtTime(micros(atSeconds), option, box, box)
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    /** JPEG at 72 % (`jpegData(compressionQuality: 0.72)`, `VideoMedia.swift:446`); recycles [bitmap]. */
    private fun jpeg(bitmap: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return if (ok && out.size() > 0) out.toByteArray() else null
    }

    private fun resolverType(uri: Uri): String? = try {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) context.contentResolver.getType(uri) else null
    } catch (_: Exception) {
        null
    }

    /** Size on disk; 0 when unknown, which rules out passthrough as on iOS (`size ?? 0`). */
    private fun fileSize(uri: Uri): Long {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                if (fd.length != AssetFileDescriptor.UNKNOWN_LENGTH && fd.length >= 0) return fd.length
                val stat = fd.parcelFileDescriptor.statSize
                if (stat >= 0) return stat
            }
        } catch (_: Exception) {
            // Fall through to the provider's own column.
        }
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) maxOf(0L, cursor.getLong(0)) else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun micros(seconds: Double): Long = Math.round(maxOf(0.0, seconds) * 1_000_000)

    private companion object {
        const val JPEG_QUALITY = 72
    }
}
