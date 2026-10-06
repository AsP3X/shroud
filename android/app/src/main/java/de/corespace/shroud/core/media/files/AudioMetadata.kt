package de.corespace.shroud.core.media.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the sender of an audio file read from it (docs/file-sharing.md §11.2): the duration in ms
 * (≥ 1), the cleaned title and artist tags, and the cover art as `th` ([cover]). Every part may be
 * missing; [isEmpty] when nothing was read. [toString] prints no tag text.
 */
class AudioFileMetadata(
    val durationMs: Int? = null,
    val title: String? = null,
    val artist: String? = null,
    val cover: AudioCover? = null,
) {
    val isEmpty: Boolean get() = durationMs == null && title == null && artist == null && cover == null

    override fun toString(): String =
        "AudioFileMetadata(durationMs=$durationMs, title=${title != null}, artist=${artist != null}, cover=${cover != null})"

    companion object {
        /** The parts as the payload carries them: a duration rounded to whole ms (≥ 1), tags cleaned (§11.2). */
        fun of(durationMs: Double?, title: String?, artist: String?, cover: AudioCover?): AudioFileMetadata =
            AudioFileMetadata(
                durationMs = durationMs?.takeIf { it.isFinite() && it > 0 }?.roundToLong()?.coerceIn(1, Int.MAX_VALUE.toLong())?.toInt(),
                title = AudioTags.clean(title),
                artist = AudioTags.clean(artist),
                cover = cover,
            )
    }
}

/** An audio file's `th`: a square JPEG of at most 6 KB, and its pixel size. */
class AudioCover(val jpeg: ByteArray, val width: Int, val height: Int) {
    override fun toString(): String = "AudioCover(${width}x$height, ${jpeg.size} B)"
}

/** Where the sender's metadata is read from: the picked document, or the sealed copy of a queued or failed send. */
sealed interface AudioMetadataSource {
    class Picked(val uri: Uri) : AudioMetadataSource

    class Sealed(val open: () -> MediaDataSource?) : AudioMetadataSource
}

/**
 * The sender's §11.2 read on Android: `MediaMetadataRetriever` on the picked URI (or on the SHRM1
 * copy through a [MediaDataSource]) — `METADATA_KEY_DURATION`, `_TITLE`, `_ARTIST` and
 * `embeddedPicture`. Everything has [BUDGET_MS]: a retriever cannot be interrupted, so it runs
 * detached and the caller stops waiting at the budget (a late read is dropped). Never throws; null
 * when nothing could be read in time.
 */
class AudioMetadataReader(private val context: Context) {
    suspend fun read(source: AudioMetadataSource): AudioFileMetadata? {
        val work = budgetScope.async { readNow(source) }
        val result = withTimeoutOrNull(BUDGET_MS) { work.await() }
        if (result == null) work.cancel()
        return result?.takeUnless { it.isEmpty }
    }

    private fun readNow(source: AudioMetadataSource): AudioFileMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            when (source) {
                is AudioMetadataSource.Picked -> retriever.setDataSource(context, source.uri)
                is AudioMetadataSource.Sealed -> retriever.setDataSource(source.open() ?: return null)
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.trim()?.toDoubleOrNull()
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val cover = retriever.embeddedPicture?.let { picture ->
                try {
                    AudioCoverThumb.encode(picture)
                } finally {
                    picture.fill(0)
                }
            }
            AudioFileMetadata.of(duration, title, artist, cover)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Where the reads run, so a slow one never holds the pick past its budget. */
    private val budgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** Longer than this, the file goes without what was not read (§11.2). */
        const val BUDGET_MS = 2_000L
    }
}

/**
 * §11.2's cover `th`: the embedded picture centre-cropped to a square, scaled to [START_SIZE] px,
 * JPEG; the side shrinks by 0.8 and the quality drops until it fits 6 KB, and below [MIN_SIZE] px
 * there is no `th`. The ladder is pure, so it is tested on the JVM.
 */
object AudioCoverThumb {
    const val START_SIZE = 160
    const val MIN_SIZE = 64
    const val SIZE_FACTOR = 0.8
    const val START_QUALITY = 70
    const val QUALITY_STEP = 10
    const val MIN_QUALITY = 30

    /** The JPEG bytes of `th`, not its Base64. */
    const val MAX_BYTES = 6 * 1024

    /**
     * The first JPEG [encode] (side px, quality → bytes, or null when it cannot) makes within
     * [MAX_BYTES], with its side; null once the side falls under [MIN_SIZE] or an encode fails.
     */
    fun ladder(startSize: Int = START_SIZE, encode: (side: Int, quality: Int) -> ByteArray?): Pair<ByteArray, Int>? {
        var side = startSize
        var quality = START_QUALITY
        while (side >= MIN_SIZE) {
            val jpeg = encode(side, quality) ?: return null
            if (jpeg.size <= MAX_BYTES) return jpeg to side
            side = floor(side * SIZE_FACTOR).toInt()
            quality = max(MIN_QUALITY, quality - QUALITY_STEP)
        }
        return null
    }

    /** The centred square of a [width] × [height] picture: left, top, side. */
    fun centreSquare(width: Int, height: Int): Triple<Int, Int, Int> {
        val side = min(width, height)
        return Triple((width - side) / 2, (height - side) / 2, side)
    }

    /** The cover of an embedded [picture] (any format `BitmapFactory` reads), or null. */
    fun encode(picture: ByteArray): AudioCover? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode no larger than needed: the square's side stays at least START_SIZE.
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= START_SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(picture, 0, picture.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val (left, top, side) = centreSquare(decoded.width, decoded.height)
        val square = if (left == 0 && top == 0 && side == decoded.width && side == decoded.height) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, left, top, side, side)
        }
        try {
            val result = ladder(min(START_SIZE, side)) { size, quality ->
                val scaled = if (size == square.width) square else Bitmap.createScaledBitmap(square, size, size, true)
                try {
                    val out = ByteArrayOutputStream(MAX_BYTES)
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return@ladder null
                    out.toByteArray()
                } finally {
                    if (scaled !== square) scaled.recycle()
                }
            } ?: return null
            return AudioCover(result.first, result.second, result.second)
        } finally {
            if (square !== decoded) square.recycle()
            decoded.recycle()
        }
    }
}
