package de.corespace.shroud.core.links

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import de.corespace.shroud.core.net.wire.LinkPreview
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The two JPEGs built from a page's image, and the size it was decoded at (iOS `PreparedImages`, `LinkPreviewFetcher.swift:343-349`). */
class PreparedLinkImages(
    /** JPEG for the large layout (longest edge ≤ [LinkPreviewImages.LARGE_IMAGE_MAX_EDGE]). */
    val large: ByteArray?,
    /** Square JPEG for the small layout, ≤ [LinkPreview.MAX_THUMBNAIL_BYTES]; sealed inline as `th`. */
    val thumbnail: ByteArray?,
    /** Decoded (downsampled) size: the large JPEG's size and the preview's `w`/`h`. */
    val width: Int,
    val height: Int,
) {
    override fun toString(): String = "PreparedLinkImages(${width}x$height, large=${large?.size}, thumbnail=${thumbnail?.size})"
}

/** Turns a fetched image into [PreparedLinkImages]; the seam lets the fetcher's JVM tests run without `Bitmap`. */
fun interface LinkImagePreparer {
    /** Null for undecodable data, icons and canvases over the pixel cap. Never throws for bad input. */
    fun prepare(bytes: ByteArray): PreparedLinkImages?
}

/**
 * The preview's pictures, made on the sender's phone from the page's image — iOS
 * `LinkPreviewFetcher.prepareImages` (`ios/shroud/Services/Links/LinkPreviewFetcher.swift:351-452`),
 * media-voice-links §10.5:
 *
 * - refused from the header alone when the declared canvas is over [MAX_IMAGE_PIXELS] or a side is
 *   0 (a "decompression bomb" would take the app down while the user is still typing) —
 *   [LinkImageHeader] first, then `ImageDecoder`'s header callback before any pixel is decoded;
 * - decoded downsampled with the EXIF orientation applied so the longest edge is ≤ 1024, refused
 *   when the shorter decoded edge is under [MINIMUM_IMAGE_EDGE] (icons, tracking pixels);
 * - flattened onto white (JPEG has no alpha; a transparent logo would otherwise come out black);
 * - large JPEG at quality 0.72; square centre-crop thumbnail at 160 px / 0.7, shrinking by 24 px
 *   and 0.1 (to at least 96 px / 0.35) up to five times until it fits the 6 KiB envelope budget.
 *
 * The large layout's blurred placeholder is made at send time from the large JPEG
 * (`chatPreviewJpeg`, media-voice-links §3.4), as on iOS.
 */
object LinkPreviewImages : LinkImagePreparer {
    /** Longest edge of the large-layout JPEG (≈ 3× a phone bubble's width). */
    const val LARGE_IMAGE_MAX_EDGE = 1024

    /** Edge of the square thumbnail (54 pt at 3×). */
    const val THUMBNAIL_EDGE = 160

    /** Images smaller than this are icons or tracking pixels, not previews. */
    const val MINIMUM_IMAGE_EDGE = 80

    /** Largest canvas decoded, in pixels (40 MP ≈ 8000 × 5000 — far above any real card image). */
    const val MAX_IMAGE_PIXELS = 40_000_000L

    private const val LARGE_QUALITY = 72
    private const val THUMBNAIL_QUALITY = 70
    private const val THUMBNAIL_MIN_EDGE = 96
    private const val THUMBNAIL_MIN_QUALITY = 35
    private const val THUMBNAIL_TRIES = 5

    /**
     * True when an image of this declared size is safe to decode (`LinkPreviewFetcher.swift:351-358`).
     * Multiplied in `Double`, so `Long.MAX_VALUE × 2` cannot overflow into "small".
     */
    fun isDecodableImageSize(width: Long, height: Long): Boolean =
        width > 0 && height > 0 && width.toDouble() * height.toDouble() <= MAX_IMAGE_PIXELS.toDouble()

    override fun prepare(bytes: ByteArray): PreparedLinkImages? {
        LinkImageHeader.dimensions(bytes)?.let { declared ->
            if (!isDecodableImageSize(declared.width, declared.height)) return null
        }
        val decoded = decode(bytes) ?: return null
        try {
            if (min(decoded.width, decoded.height) < MINIMUM_IMAGE_EDGE) return null
            val width = decoded.width
            val height = decoded.height
            val large = flattened(decoded, null, width, height)?.let { jpeg(it, LARGE_QUALITY) }

            // Centre square crop, then down to the thumbnail edge.
            val side = min(width, height)
            val crop = Rect((width - side) / 2, (height - side) / 2, (width - side) / 2 + side, (height - side) / 2 + side)
            var thumbnail: ByteArray? = null
            var edge = THUMBNAIL_EDGE
            var quality = THUMBNAIL_QUALITY
            repeat(THUMBNAIL_TRIES) {
                if (thumbnail == null) {
                    val data = flattened(decoded, crop, edge, edge)?.let { jpeg(it, quality) }
                    if (data != null && data.size <= LinkPreview.MAX_THUMBNAIL_BYTES) {
                        thumbnail = data
                    } else {
                        edge = max(THUMBNAIL_MIN_EDGE, edge - 24)
                        quality = max(THUMBNAIL_MIN_QUALITY, quality - 10)
                    }
                }
            }
            if (large == null && thumbnail == null) return null
            return PreparedLinkImages(large = large, thumbnail = thumbnail, width = width, height = height)
        } finally {
            decoded.recycle()
        }
    }

    /** Thrown from the header callback to stop before any pixel is decoded. */
    private class RefusedImage : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * Software bitmap, sRGB, EXIF orientation applied (`ImageDecoder` does that for JPEG and HEIF),
     * longest edge ≤ [LARGE_IMAGE_MAX_EDGE] — ImageIO's `CreateThumbnailWithTransform` +
     * `ThumbnailMaxPixelSize`. Animated GIF/WebP give their first frame.
     */
    private fun decode(bytes: ByteArray): Bitmap? =
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (!isDecodableImageSize(w.toLong(), h.toLong())) throw RefusedImage()
                val scale = min(1.0, LARGE_IMAGE_MAX_EDGE.toDouble() / max(w, h))
                if (scale < 1.0) decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (_: Exception) {
            null // RefusedImage, DecodeException, IOException, an unsupported format
        } catch (_: OutOfMemoryError) {
            null // a hostile image must cost the preview, not the app
        }

    /** [source] (or its [crop]) drawn onto an opaque white `width × height` canvas (`LinkPreviewFetcher.swift:416-435`). */
    private fun flattened(source: Bitmap, crop: Rect?, width: Int, height: Int): Bitmap? =
        try {
            val target = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(target)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(source, crop, Rect(0, 0, target.width, target.height), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            target
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }

    private fun jpeg(image: Bitmap, quality: Int): ByteArray? =
        try {
            val out = ByteArrayOutputStream()
            if (image.compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
        } finally {
            image.recycle()
        }
}
