package de.corespace.shroud.core.media

import android.graphics.Bitmap
import android.net.Uri
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.model.Bytes
import java.io.Closeable
import java.io.File
import java.util.UUID

// Media types and ports (plan §1.7.9, C7, C8, C26–C28). Published by W1-INT with the final
// signatures; W2-MEDIA-STORE implements MediaTransfers and LocalMediaStore (SHRM1), W2-MEDIA-IMAGE
// ImagePipeline, W2-VIDEO VideoPipeline, W3-MEDIA-EDIT MediaEditBaker.

/** Where a picked or captured photo comes from (C27: a content URI is read at send time). */
sealed interface MediaImageSource {
    /** Encoded bytes already in memory (camera capture, paste). */
    class FileBytes(val bytes: ByteArray) : MediaImageSource {
        override fun toString(): String = "FileBytes(${bytes.size} bytes)"
    }

    data class ContentUri(val uri: Uri) : MediaImageSource

    class Decoded(val bitmap: Bitmap) : MediaImageSource {
        override fun toString(): String = "Decoded(${bitmap.width}x${bitmap.height})"
    }
}

/** The photo quality the compose screen offers (conversation-compose-media §6). */
enum class MediaComposeQuality(val label: String, val maxEdge: Int, val jpegQuality: Int, val allowsPassthrough: Boolean) {
    Original("Original", 16_384, 100, true),
    HD("HD", 2560, 85, false),
}

/** An encoded photo, metadata already scrubbed. */
data class EncodedImage(val data: Bytes, val width: Int, val height: Int, val mime: String)

/** An encoded video, file-backed (C28); uploads read it through [PlainSource.TempFile]. */
data class EncodedVideo(
    val file: File,
    val width: Int,
    val height: Int,
    val durationMs: Int,
    val mime: String,
    val posterJpeg: Bytes?,
    val sizeBytes: Long,
)

/** A sealed blob on the server and what opens it. [toString] never prints the key. */
data class UploadedBlob(val mediaObjectId: UUID, val keyBase64: String, val plainSize: Long, val sealedSize: Long) {
    override fun toString(): String = "UploadedBlob(id=$mediaObjectId, plain=$plainSize, sealed=$sealedSize)"
}

/** The plaintext an upload seals (C28). */
sealed interface PlainSource {
    class InMemory(val data: ByteArray) : PlainSource {
        override fun toString(): String = "InMemory(${data.size} bytes)"
    }

    /** Already in the sealed local media cache under this message id. */
    data class LocalMedia(val messageId: UUID) : PlainSource

    /** A `cacheDir/shroud-*` file (`SensitiveTempFiles`). */
    data class TempFile(val file: File) : PlainSource
}

/** Random access into one decrypted SHRM1 file (players read through it; no decrypted temp files, C7). */
interface SealedMediaReader : Closeable {
    val length: Long

    /** Like `RandomAccessFile.read`: −1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
}

/** Streams plaintext into one SHRM1 file; nothing is visible until [commit]. */
interface SealedMediaWriter : Closeable {
    fun write(buffer: ByteArray, offset: Int, size: Int)
    fun commit()
    fun abort()
}

/** Upload and download of sealed blobs; implemented by `MediaTransferService` (W2-MEDIA-STORE). */
interface MediaTransfers {
    suspend fun upload(source: PlainSource, token: String, onProgress: ((Double) -> Unit)? = null): UploadedBlob

    suspend fun downloadInto(
        mediaObjectId: UUID,
        keyBase64: String,
        token: String,
        targetMessageId: UUID,
        onProgress: ((Double) -> Unit)? = null,
    )
}

/** The sealed local media cache, SHRM1 segmented (plan §1.5, C7); implemented by `LocalMediaCache` (W2-MEDIA-STORE). */
interface LocalMediaStore {
    suspend fun readAll(messageId: UUID): ByteArray?
    fun has(messageId: UUID): Boolean
    suspend fun save(messageId: UUID, data: ByteArray)
    fun writer(messageId: UUID): SealedMediaWriter
    fun openReader(messageId: UUID): SealedMediaReader?
    fun rename(from: UUID, to: UUID)
    fun remove(messageIds: Collection<UUID>)
    fun clearAll()

    /** Files and bytes, for the wipe overlay's summary. */
    fun inventory(): Pair<Int, Long>
}

/** Photo encoding; implemented by `ImageEncoder` (W2-MEDIA-IMAGE). Results are already scrubbed of metadata. */
interface ImagePipeline {
    suspend fun encode(source: MediaImageSource, quality: MediaComposeQuality, edits: MediaEdits): EncodedImage
    fun chatPreviewJpeg(image: ByteArray): ByteArray?
    fun pixelSize(image: ByteArray): Pair<Int, Int>?
    fun mimeType(image: ByteArray): String
}

/** Bakes edits into a bitmap. Identity until W3-MEDIA-EDIT registers `MediaEditRenderer`. */
fun interface MediaEditBaker {
    fun render(image: Bitmap, edits: MediaEdits): Bitmap

    companion object {
        /** Returns the input unchanged. */
        val Identity: MediaEditBaker = MediaEditBaker { image, _ -> image }
    }
}

/** Video encoding; implemented by `VideoMedia` (W2-VIDEO). */
interface VideoPipeline {
    /** Throws `VideoException` (Unreadable, ExportFailed, TooLarge, Cancelled). */
    suspend fun encode(plan: VideoSendPlan, onProgress: ((Double) -> Unit)?): EncodedVideo
    suspend fun posterJpegFromLocal(messageId: UUID, maxEdgePx: Int = 720): ByteArray?
    val maxSealedBytes: Long
}
