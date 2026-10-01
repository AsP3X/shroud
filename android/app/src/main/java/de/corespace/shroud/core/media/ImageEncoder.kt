package de.corespace.shroud.core.media

import android.graphics.Bitmap
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.scrub.ImageHeader
import de.corespace.shroud.core.media.scrub.MediaMetadataScrubber
import de.corespace.shroud.core.model.Bytes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Prepares photos for sending — the Android `MediaCrypto` image half
 * (`ios/shroud/Services/Crypto/MediaCrypto.swift:55-280`; media-voice-links §4; crypto §13.2) plus the
 * edit baking of `MessagingController.sendImage` (`ios/shroud/Services/Messaging/MessagingController.swift:2403-2427`).
 *
 * Human: "Original" means original *pixels* — when the source is a library file in a format every
 * client decodes (JPEG, PNG, HEIC, HEIF), its encoded image goes on the wire without being compressed
 * again. Decoding and re-encoding, even at JPEG quality 100, is a generation loss: it resamples, clips
 * wide gamut and usually inflates the file. Its metadata does not go along: location, capture time and
 * device details are removed first ([MediaMetadataScrubber]), and a file that can't be cleaned is
 * re-encoded instead. Everything that is re-encoded is a fresh JPEG that carries no metadata.
 *
 * Android-specific rules:
 * - Edited photos are baked at full resolution through the registered [MediaEditBaker] (identity
 *   until W3-MEDIA-EDIT registers `MediaEditRenderer`), decoded at most [EDITED_DECODE_CAP] px on the
 *   long edge instead of iOS's 16 384 (compose Q11: a 48 MP bitmap is ~190 MB of native memory,
 *   twice while rotating).
 * - Every decode-and-encode retries at half the edge after an [OutOfMemoryError], down to
 *   [MIN_RETRY_EDGE], instead of crashing the process.
 * - A picked photo (content URI, C27) is read into memory only when it may pass through and is at
 *   most [IN_MEMORY_ORIGINAL_LIMIT]; larger ones are decoded straight from the URI at the target size
 *   and re-encoded (the [EncodedImage] seam holds bytes in memory).
 *
 * Thread-safe and stateless; CPU work runs on [cpu], content-URI reads on [io]. Never logs.
 */
class ImageEncoder(
    private val codec: ImageCodec,
    private val editBaker: () -> MediaEditBaker = { MediaEditBaker.Identity },
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ImagePipeline {

    /**
     * `MessagingController.sendImage`'s encode step (`MessagingController.swift:2409-2423`) and
     * `MediaCrypto.encode` (`MediaCrypto.swift:80-107`). Throws [ImageEncodeException] (the caller's
     * "Could not prepare that photo.").
     */
    override suspend fun encode(source: MediaImageSource, quality: MediaComposeQuality, edits: MediaEdits): EncodedImage {
        try {
            // Passthrough only for untouched photos (`allowsPassthrough && edits.isIdentity`, :2422).
            val mayPassThrough = quality.allowsPassthrough && edits.isIdentity
            val prepared: MediaImageSource = when {
                mayPassThrough && source is MediaImageSource.ContentUri ->
                    withContext(io) { codec.readOriginal(source.uri, IN_MEMORY_ORIGINAL_LIMIT) }
                        ?.let { MediaImageSource.FileBytes(it) } ?: source
                else -> source
            }
            return withContext(cpu) {
                if (mayPassThrough && prepared is MediaImageSource.FileBytes) {
                    passthrough(prepared.bytes)?.let { return@withContext it }
                }
                if (edits.isIdentity) {
                    reencode(prepared, quality.maxEdge, quality, edits)
                } else {
                    // Crop, filters, markup and stickers are baked here, at full resolution (:2411-2417).
                    reencode(prepared, minOf(quality.maxEdge, EDITED_DECODE_CAP), quality, edits)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageEncodeException) {
            throw e
        } catch (e: Exception) {
            throw ImageEncodeException("The photo could not be prepared.", e)
        }
    }

    /**
     * The source's own encoded image with its metadata removed, when it is a shippable original
     * (`passthrough`, `MediaCrypto.swift:185-201`): within the plaintext budget, a JPEG/PNG/HEIC/HEIF
     * container, a readable pixel size, and proven clean by the scrubber. Null otherwise — the caller
     * re-encodes, which carries no metadata. The MIME type is the container's (`image/heic` stays HEIC).
     */
    fun passthrough(data: ByteArray): EncodedImage? {
        if (data.size > MediaCrypto.MAX_PLAINTEXT_BYTES) return null
        val container = ImageHeader.container(data) ?: return null
        if (!container.allowsPassthrough) return null
        val (width, height) = ImageHeader.pixelSize(data) ?: return null
        val clean = MediaMetadataScrubber.scrubImage(data) ?: return null
        return EncodedImage(Bytes.adopt(clean), width, height, container.mime)
    }

    /**
     * Decode (downsampled to [decodeEdge]), bake [edits], scale so the longest pixel edge is at most
     * the quality's cap, JPEG at the quality's level (`MediaCrypto.swift:93-106`); half the edge again
     * after running out of memory.
     */
    private fun reencode(source: MediaImageSource, decodeEdge: Int, quality: MediaComposeQuality, edits: MediaEdits): EncodedImage {
        var edge = decodeEdge
        while (true) {
            try {
                return reencodeOnce(source, edge, quality, edits)
            } catch (oom: OutOfMemoryError) {
                if (edge <= MIN_RETRY_EDGE) throw ImageEncodeException("The photo is too large to prepare.", oom)
                edge = maxOf(MIN_RETRY_EDGE, edge / 2)
            }
        }
    }

    private fun reencodeOnce(source: MediaImageSource, edge: Int, quality: MediaComposeQuality, edits: MediaEdits): EncodedImage {
        val callerBitmap = (source as? MediaImageSource.Decoded)?.bitmap
        val made = ArrayList<Bitmap>(3)
        try {
            val decoded = codec.decode(source, edge).also { if (it !== callerBitmap) made += it }
            val rendered = if (edits.isIdentity) decoded else editBaker().render(decoded, edits).also { if (it !== callerBitmap && it !in made) made += it }
            val scaled = MediaImages.scaledToFit(rendered, quality.maxEdge).also { if (it !== callerBitmap && it !in made) made += it }
            // `min(1, max(0.05, compression))` (:95) on the 0…100 scale.
            val jpeg = codec.compressJpeg(scaled, quality.jpegQuality.coerceIn(5, 100))
            return EncodedImage(Bytes.adopt(jpeg), maxOf(1, scaled.width), maxOf(1, scaled.height), JPEG_MIME)
        } finally {
            for (bitmap in made) if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * The tiny JPEG sealed into the media payload as `th` (`chatPreviewJPEG`, `MediaCrypto.swift:148-171`):
     * the [previewLadder] over downsampled decodes of [image]. The source is decoded once at the
     * ladder's first edge; the smaller tries scale that bitmap down. Null when [image] cannot be decoded.
     */
    override fun chatPreviewJpeg(image: ByteArray): ByteArray? {
        var base: Bitmap? = null
        try {
            return previewLadder { edge, quality ->
                val first = base ?: codec.decode(MediaImageSource.FileBytes(image), PREVIEW_EDGE).also { base = it }
                val sized = MediaImages.scaledToFit(first, edge)
                try {
                    codec.compressJpeg(sized, quality)
                } finally {
                    if (sized !== first) sized.recycle()
                }
            }
        } catch (_: ImageEncodeException) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        } finally {
            base?.recycle()
        }
    }

    /** Header first; the platform decoder's bounds pass for formats the header reader does not know. */
    override fun pixelSize(image: ByteArray): Pair<Int, Int>? = ImageHeader.pixelSize(image) ?: codec.boundsSize(image)

    override fun mimeType(image: ByteArray): String = ImageHeader.mimeType(image)

    companion object {
        const val JPEG_MIME = "image/jpeg"

        /** Long-edge cap for decoding a photo whose edits are baked (compose Q11, decided as recommended). */
        const val EDITED_DECODE_CAP = 8192

        /** Smallest edge an out-of-memory retry goes down to before giving up. */
        const val MIN_RETRY_EDGE = 512

        /** Largest picked original read into memory for the passthrough path (media-voice-links §4.1: 32 MiB). */
        const val IN_MEMORY_ORIGINAL_LIMIT: Long = 32L * 1024 * 1024

        /** `chatPreviewJPEG` defaults (`MediaCrypto.swift:151-155`). */
        const val PREVIEW_EDGE = 160
        const val PREVIEW_QUALITY = 0.42

        /**
         * The `th` ladder (`MediaCrypto.swift:156-170`): up to five tries, each a JPEG with its longest
         * edge at `edge` (truncated to whole pixels, as ImageIO's `Int(maxEdge)`) and quality
         * `clamp(q, 0.15, 0.85)`; the first one ≤ 6 KiB wins; else `edge = max(80, edge × 0.7)`,
         * `q = max(0.15, q − 0.08)`. Last resort: 80 px at 0.15 even if it is still over — the caller
         * may drop `th`. [render] gets the edge and the quality on the 0…100 scale; null aborts.
         */
        fun previewLadder(render: (edge: Int, quality: Int) -> ByteArray?): ByteArray? {
            var edge = PREVIEW_EDGE.toDouble()
            var q = PREVIEW_QUALITY
            repeat(5) {
                val jpeg = render(maxOf(1, edge.toInt()), percent(minOf(0.85, maxOf(0.15, q)))) ?: return null
                if (jpeg.size <= MediaCrypto.MAX_ENVELOPE_PREVIEW_BYTES) return jpeg
                edge = maxOf(80.0, edge * 0.7)
                q = maxOf(0.15, q - 0.08)
            }
            return render(80, percent(0.15))
        }

        private fun percent(quality: Double): Int = (quality * 100).roundToInt()
    }
}
