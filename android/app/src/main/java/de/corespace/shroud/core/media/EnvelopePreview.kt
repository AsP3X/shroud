package de.corespace.shroud.core.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import de.corespace.shroud.core.crypto.MediaCrypto
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Size budgets of a media message's envelope (`MessagingController.swift:3248-3330`;
 * media-voice-links §1.6). The sealing and the "drop `th`, then reseal once" ladder are the send
 * pipeline's (W2-MSG-SEND); the numbers live here once.
 */
object MediaEnvelopeBudget {
    /** `maxSealedEnvelopeBytes`: the server refuses envelopes over 64 KiB decoded (`messages.rs:28`); 4 KiB headroom. */
    const val MAX_SEALED = 60 * 1024

    /** `maxMediaPayloadPlaintextBytes` (`MessagingController.swift:3252`): above this the payload drops `th`. */
    const val MAX_PAYLOAD = 12 * 1024

    /** `MediaCrypto.maxEnvelopePreviewBytes` (`MediaCrypto.swift:146`): the JPEG bytes of `th`, not its Base64. */
    const val MAX_THUMB = MediaCrypto.MAX_ENVELOPE_PREVIEW_BYTES
}

/**
 * The tiny JPEG sealed into a photo, video or large-link message as `th`: what a recipient's
 * bubble shows until the full blob is downloaded — iOS `MediaCrypto.chatPreviewJPEG`
 * (`ios/shroud/Services/Crypto/MediaCrypto.swift:151-171`), web `envelopePreview`
 * (`web/src/media/envelopePreview.ts:16-28`); media-voice-links §4.4, crypto §13.2.
 *
 * The ladder: start at a 160 px longest edge and quality 0.42; up to five tries, each clamped to
 * quality 0.15…0.85, accepting the first JPEG of at most [MediaEnvelopeBudget.MAX_THUMB] bytes;
 * after each miss the edge shrinks by 0.7 (never below 80 px) and the quality by 0.08 (never
 * below 0.15). If all five miss, iOS returns one last attempt at 80 px and 0.15 **even when it is
 * still too big** — the send pipeline then drops `th` (`MessagingController.swift:3315`). The web
 * returns null there instead; iOS is the reference, so Android follows iOS.
 *
 * Edges are pixels (`kCGImageSourceThumbnailMaxPixelSize` takes `Int(edge)`: 160, 112, 80, 80, 80);
 * a picture smaller than the edge is never scaled up. Blocking and CPU-bound: call it on
 * `Dispatchers.Default`. Never logs image content.
 */
object EnvelopePreview {
    /** `chatPreviewJPEG(maxEdge:)` default (`MediaCrypto.swift:153`). */
    const val START_EDGE = 160.0

    /** `chatPreviewJPEG(quality:)` default (`MediaCrypto.swift:154`). */
    const val START_QUALITY = 0.42

    const val TRIES = 5
    const val MIN_EDGE = 80.0
    const val EDGE_FACTOR = 0.7
    const val QUALITY_STEP = 0.08
    const val MIN_QUALITY = 0.15
    const val MAX_QUALITY = 0.85

    /** The last resort's settings (`MediaCrypto.swift:169-170`). */
    const val LAST_RESORT_EDGE = 80
    const val LAST_RESORT_QUALITY = 0.15

    /**
     * Draws a picture with its longest edge at most `edge` pixels and encodes it as JPEG at
     * `quality` (0…1); null when the picture cannot be decoded or encoded.
     */
    fun interface JpegEncoder {
        fun encode(edge: Int, quality: Double): ByteArray?
    }

    /**
     * The ladder of `MediaCrypto.swift:156-170` over [encoder]: null when any try cannot draw
     * the picture (iOS returns nil there too), else the first fitting JPEG or the last resort.
     */
    fun ladder(encoder: JpegEncoder): ByteArray? {
        var edge = START_EDGE
        var quality = START_QUALITY
        repeat(TRIES) {
            val jpeg = encoder.encode(edgePixels(edge), min(MAX_QUALITY, max(MIN_QUALITY, quality))) ?: return null
            if (jpeg.size <= MediaEnvelopeBudget.MAX_THUMB) return jpeg
            edge = max(MIN_EDGE, edge * EDGE_FACTOR)
            quality = max(MIN_QUALITY, quality - QUALITY_STEP)
        }
        return encoder.encode(LAST_RESORT_EDGE, LAST_RESORT_QUALITY)
    }

    /**
     * `th` for encoded image bytes (a photo as sent, a video poster JPEG, a link image): decoded
     * downsampled (`ImageDecoder`, EXIF orientation applied, never the full-size bitmap) and
     * re-encoded through [ladder]. Null when the bytes are not an image this phone decodes.
     */
    fun chatPreviewJpeg(image: ByteArray): ByteArray? = ladder { edge, quality -> encodeJpeg(image, edge, quality) }

    /**
     * Longest-edge fit of `width × height` into [edge] pixels, never upscaled, each side at least
     * 1 (web `fitEdge`, `envelopePreview.ts:31-37`; ImageIO's thumbnail rounds the same way).
     */
    fun fitEdge(width: Int, height: Int, edge: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= 0) return 1 to 1
        val scale = min(1.0, edge.toDouble() / longest)
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** `Int(edge)` as ImageIO's `kCGImageSourceThumbnailMaxPixelSize` takes it (`MediaCrypto.swift:233`). */
    internal fun edgePixels(edge: Double): Int = max(1, edge.toInt())

    private fun encodeJpeg(image: ByteArray, edge: Int, quality: Double): ByteArray? {
        val bitmap = decodeDownsampled(image, edge) ?: return null
        return try {
            val out = ByteArrayOutputStream(MediaEnvelopeBudget.MAX_THUMB)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt().coerceIn(0, 100), out)) return null
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** The picture with its longest (oriented) edge at most [edge] pixels, in software memory. */
    private fun decodeDownsampled(image: ByteArray, edge: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(image))) { decoder, info, _ ->
            val (width, height) = fitEdge(info.size.width, info.size.height, edge)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        // DecodeException, IOException, an unsupported format or an OOM-guard refusal: no preview.
        null
    }
}
