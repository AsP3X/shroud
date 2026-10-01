package de.corespace.shroud.core.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import de.corespace.shroud.core.media.scrub.ImageHeader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo could not be decoded or encoded (iOS `MediaCrypto.MediaError.imageEncodeFailed`, `MediaCrypto.swift:33-37`). */
class ImageEncodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The platform half of image handling: decoding, scaling and JPEG compression. [MediaImages] is the
 * real one ([ImageDecoder]); [ImageEncoder] depends on this seam so its rules can be tested with a fake.
 */
interface ImageCodec {
    /**
     * [source] decoded upright (EXIF orientation applied) with its longest pixel edge at most
     * [maxEdge] — scaled down at decode time, never decoded at full size first and never scaled up.
     * Throws [ImageEncodeException] when it cannot be decoded; an [OutOfMemoryError] passes through
     * so the caller can retry smaller.
     */
    fun decode(source: MediaImageSource, maxEdge: Int): Bitmap

    /** JPEG at [quality] (0…100). Throws [ImageEncodeException]. */
    fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray

    /** The encoded bytes behind [uri], or null when there are more than [limit]. Throws [ImageEncodeException] when unreadable. */
    fun readOriginal(uri: Uri, limit: Long): ByteArray?

    /** Pixel size as drawn, from the platform decoder's header pass — for formats [ImageHeader] does not read. */
    fun boundsSize(data: ByteArray): Pair<Int, Int>?
}

/**
 * Decoding for the compose screen, the viewer and the encoder: the Android side of iOS
 * `MediaCrypto.previewImage(from:maxEdge:)` / `fullResolutionImage(from:maxEdge:)` / the private
 * `decodedImage`/`downsampled`/`scaledImage` (`ios/shroud/Services/Crypto/MediaCrypto.swift:125-139,
 * :214-280`; media-voice-links §4.1–4.2; conversation-compose-media §8.2).
 *
 * `ImageDecoder` (API 28+) stands in for ImageIO's thumbnail decode: the target size is set in the
 * header callback, so a 48 MP original is never materialised at full size just to be shown or sent
 * smaller; EXIF orientation is applied by the decoder; the source colour space is kept (Display P3
 * stays P3 — `Bitmap.compress` embeds the profile). Bitmaps are software-allocated so they can be
 * compressed and edited. Never logs anything about the image.
 *
 * Known platform difference: Android's decoders ignore a PNG `eXIf` orientation (iOS applies it). PNGs
 * from cameras do not carry one; a re-encoded PNG with one would be sent unrotated.
 */
class MediaImages(private val contentResolver: ContentResolver) : ImageCodec {
    /**
     * Screen-sized decode for the compose screen and the viewer (`previewImage`, `:130-139`): longest
     * edge ≤ [maxEdge], orientation baked in. Null when the source cannot be decoded (or does not fit
     * in memory) — the caller shows its "Could not load …" failure.
     */
    suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? = withContext(Dispatchers.Default) {
        try {
            decode(source, maxEdge)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    override fun decode(source: MediaImageSource, maxEdge: Int): Bitmap = when (source) {
        is MediaImageSource.FileBytes -> decodeSource(ImageDecoder.createSource(ByteBuffer.wrap(source.bytes)), maxEdge)
        is MediaImageSource.ContentUri -> decodeSource(ImageDecoder.createSource(contentResolver, source.uri), maxEdge)
        is MediaImageSource.Decoded -> scaledToFit(source.bitmap, maxEdge)
    }

    private fun decodeSource(source: ImageDecoder.Source, maxEdge: Int): Bitmap = try {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val (targetWidth, targetHeight) = fit(width, height, maxEdge)
            if (targetWidth != width || targetHeight != height) decoder.setTargetSize(targetWidth, targetHeight)
        }
    } catch (e: IOException) {
        throw ImageEncodeException("The image could not be decoded.", e)
    } catch (e: IllegalArgumentException) {
        throw ImageEncodeException("The image could not be decoded.", e)
    }

    override fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        try {
            val out = ByteArrayOutputStream()
            if (software == null || !software.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), out)) {
                throw ImageEncodeException("The image could not be encoded.")
            }
            return out.toByteArray()
        } finally {
            if (software != null && software !== bitmap) software.recycle()
        }
    }

    /**
     * The provider's declared length decides first: a photo known to be over [limit] is not read
     * at all, and one of known size is read into an array of exactly that size (no growing buffer
     * holding two or three copies of a 30 MB original). Providers that do not declare a length are
     * read in growing steps, stopping as soon as [limit] is passed.
     */
    override fun readOriginal(uri: Uri, limit: Long): ByteArray? {
        val descriptor = try {
            contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (e: Exception) {
            throw ImageEncodeException("The image could not be read.", e)
        } ?: throw ImageEncodeException("The image could not be read.")
        return try {
            // The stream closes the descriptor too; closing it again is a no-op.
            descriptor.use { afd ->
                val declared = afd.length.takeIf { it >= 0 }
                if (declared != null && declared > limit) return null
                afd.createInputStream().use { readBounded(it, declared, limit) }
            }
        } catch (e: IOException) {
            throw ImageEncodeException("The image could not be read.", e)
        }
    }

    override fun boundsSize(data: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val orientation = try {
            ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return if (orientation in 5..8) options.outHeight to options.outWidth else options.outWidth to options.outHeight
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024

        /**
         * All of [stream] when it holds at most [limit] bytes, else null. [expected] (the declared
         * length, if any) sizes the first buffer, so a correct declaration means one exact array.
         */
        internal fun readBounded(stream: InputStream, expected: Long?, limit: Long): ByteArray? {
            var buffer = ByteArray((expected ?: BUFFER_BYTES.toLong()).coerceIn(0, limit).toInt())
            var total = 0
            while (true) {
                if (total == buffer.size) {
                    val probe = stream.read()
                    if (probe < 0) return buffer
                    if (total + 1L > limit) return null
                    buffer = buffer.copyOf(minOf(limit, maxOf(total * 2L, total + BUFFER_BYTES.toLong())).toInt())
                    buffer[total++] = probe.toByte()
                    continue
                }
                val read = stream.read(buffer, total, buffer.size - total)
                if (read < 0) return if (total == buffer.size) buffer else buffer.copyOf(total)
                total += read
            }
        }

        /** Container MIME type of encoded bytes, `image/jpeg` when unknown (`MediaCrypto.swift:110-117`). */
        fun mimeType(data: ByteArray): String = ImageHeader.mimeType(data)

        /** Pixel size as the recipient draws it, from the header (`MediaCrypto.swift:120-123, :203-212`). */
        fun pixelSize(data: ByteArray): Pair<Int, Int>? = ImageHeader.pixelSize(data)

        /**
         * `width × height` scaled so the longest edge is at most [maxEdge] (`scaledImage`, `:245-268`:
         * the longest edge lands exactly on the cap, the other is rounded down, at least 1); untouched
         * when it already fits. Integer arithmetic, so the long edge never comes out one pixel short.
         */
        fun fit(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
            val cap = maxOf(1, maxEdge)
            val longest = maxOf(width, height)
            if (longest <= cap || longest <= 0) return width to height
            val scaledWidth = if (width == longest) cap.toLong() else width.toLong() * cap / longest
            val scaledHeight = if (height == longest) cap.toLong() else height.toLong() * cap / longest
            return maxOf(1, scaledWidth.toInt()) to maxOf(1, scaledHeight.toInt())
        }

        /** [bitmap] itself when it fits [maxEdge], else a filtered downscale (`scaledImage`, `:249-280`). */
        fun scaledToFit(bitmap: Bitmap, maxEdge: Int): Bitmap {
            val (width, height) = fit(bitmap.width, bitmap.height, maxEdge)
            if (width == bitmap.width && height == bitmap.height) return bitmap
            return bitmap.scale(width, height, filter = true)
        }
    }
}
