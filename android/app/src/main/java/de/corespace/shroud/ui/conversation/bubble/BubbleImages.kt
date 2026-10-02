package de.corespace.shroud.ui.conversation.bubble

import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Decoding for the bubbles: in memory only, never through a library with a disk cache (decrypted
 * media, conversation-thread §19). [ImageDecoder] applies the EXIF orientation the scrubbers keep.
 *
 * Small payloads (envelope previews, link thumbnails, ≤ [SMALL_BYTES]) decode synchronously — a few
 * hundred microseconds, and the bubble lays out at once with its picture instead of jumping when it
 * arrives (iOS decodes in the view, `ImageMessageBubble.swift:89-97`). Full photos decode off the main
 * thread, sampled down to what the bubble draws.
 */
internal object BubbleImages {
    /** Envelope previews and link thumbnails are capped at 6 KiB (`MediaCrypto.maxEnvelopePreviewBytes`). */
    const val SMALL_BYTES = 16 * 1024

    /** Decodes a small JPEG/PNG at full size; null when it does not decode. */
    fun decodeSmall(bytes: ByteArray): ImageBitmap? = decode(bytes, maxEdgePx = 0)

    /** Decodes [bytes] off the main thread, sampled so the longer edge is about [maxEdgePx] (0 = full size). */
    suspend fun decodeSampled(bytes: ByteArray, maxEdgePx: Int): ImageBitmap? =
        withContext(Dispatchers.Default) { decode(bytes, maxEdgePx) }

    private fun decode(bytes: ByteArray, maxEdgePx: Int): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (maxEdgePx > 0) {
                    val edge = max(info.size.width, info.size.height)
                    decoder.setTargetSampleSize(sampleSize(edge, maxEdgePx))
                }
            }.asImageBitmap()
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** The largest power of two that keeps the decoded edge at or above [targetEdge]. */
    fun sampleSize(sourceEdge: Int, targetEdge: Int): Int {
        if (targetEdge <= 0 || sourceEdge <= targetEdge) return 1
        var sample = 1
        while (sourceEdge / (sample * 2) >= targetEdge) sample *= 2
        return sample
    }
}
