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
    /** The array must not change while the upload runs (a retried request body re-reads it). */
    class InMemory(val data: ByteArray) : PlainSource {
        override fun toString(): String = "InMemory(${data.size} bytes)"
    }

    /** Already in the sealed local media cache under this message id. */
    data class LocalMedia(val messageId: UUID) : PlainSource

    /** A `cacheDir/shroud-*` file (`SensitiveTempFiles`); the caller deletes it after the upload. */
    data class TempFile(val file: File) : PlainSource
}

/**
 * Random access into one decrypted SHRM1 file (players read through it; no decrypted temp files,
 * C7). [length] is authenticated (the last segment's tag checked) before the reader is handed out.
 * Thread-safe; close it when done.
 */
interface SealedMediaReader : Closeable {
    val length: Long

    /**
     * Like `RandomAccessFile.read`: up to [size] bytes from [position] into [buffer] at [offset],
     * −1 at or past the end, 0 for [size] 0. A segment that does not authenticate, a closed
     * reader, or chats locked since it opened → `IOException` (cause `CryptoError.Locked` for the lock).
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
}

/**
 * Streams plaintext into one SHRM1 file; nothing is visible until [commit], which atomically
 * replaces any earlier file of the id. Closing without a commit is an [abort]. After chats lock,
 * [write] and [commit] throw `CryptoError.Locked` and the partial file is already gone.
 */
interface SealedMediaWriter : Closeable {
    fun write(buffer: ByteArray, offset: Int, size: Int)
    fun commit()
    fun abort()
}

/**
 * Upload and download of sealed blobs; implemented by `MediaTransferService` (W2-MEDIA-STORE).
 * `onProgress` gets 0…1 on an I/O thread: throttled (100 ms, 0.005 steps), never backwards, `1.0`
 * once at the end.
 */
interface MediaTransfers {
    /**
     * Seals [source] under a fresh key, `POST media/uploads` with the sealed size, `PUT` the blob.
     * A sealed size over 2 GiB fails before any request with `ApiError.Server(VALIDATION_ERROR)` and
     * iOS's text; a source that cannot be read → `FileNotFoundException`. Server and transport
     * failures are `ApiError`s.
     */
    suspend fun upload(source: PlainSource, token: String, onProgress: ((Double) -> Unit)? = null): UploadedBlob

    /**
     * `GET media/{id}/content`, opened with [keyBase64] (the payload's `k`) and committed to the
     * local cache under [targetMessageId] only once the whole blob authenticated. A bad key →
     * `MediaCrypto.MediaError.InvalidKey` and chats locked → `CryptoError.Locked`, both before any
     * request; a blob that does not open → `MediaCrypto.MediaError.DecryptFailed`; a
     * [LocalMediaStore.remove] of [targetMessageId] meanwhile → `IOException`, nothing stored.
     * Cancellable.
     */
    suspend fun downloadInto(
        mediaObjectId: UUID,
        keyBase64: String,
        token: String,
        targetMessageId: UUID,
        onProgress: ((Double) -> Unit)? = null,
    )
}

/**
 * The sealed local media cache, SHRM1 segmented (plan §1.5, C7); implemented by `LocalMediaCache`
 * (W2-MEDIA-STORE). Everything needs the history key: while chats are locked reads find nothing,
 * [has] is false, [writer] throws `CryptoError.Locked`, [save] does nothing (iOS), and [rename] /
 * [remove] are applied at the next unlock. While the store is sealed for a wipe nothing is
 * written. Blocking members: call them off the main thread ([has] is a stat and may run anywhere).
 */
interface LocalMediaStore {
    /** The whole plaintext; null when missing, locked, unreadable or over 256 MiB (use [openReader]). */
    suspend fun readAll(messageId: UUID): ByteArray?
    fun has(messageId: UUID): Boolean

    /** Replaces the id's media; silent when locked, sealed for a wipe, or the disk refuses (iOS). */
    suspend fun save(messageId: UUID, data: ByteArray)
    fun writer(messageId: UUID): SealedMediaWriter
    fun openReader(messageId: UUID): SealedMediaReader?

    /** The server re-keyed a sent message: [from]'s file becomes [to]'s. */
    fun rename(from: UUID, to: UUID)

    /**
     * Deletes the media of [messageIds]; an unfinished [writer] of one of them fails its commit,
     * so a download racing a delete for everyone leaves nothing. Later writers work normally.
     */
    fun remove(messageIds: Collection<UUID>)

    /** Closes open readers and writers and deletes every media file (works while locked). */
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
    /**
     * Throws [de.corespace.shroud.core.media.video.VideoException] (Unreadable, ExportFailed,
     * TooLarge, Cancelled — the last only for a cancel the platform reports); a cancelled caller gets
     * a `CancellationException`.
     */
    suspend fun encode(plan: VideoSendPlan, onProgress: ((Double) -> Unit)?): EncodedVideo
    suspend fun posterJpegFromLocal(messageId: UUID, maxEdgePx: Int = 720): ByteArray?

    /**
     * Length of the sealed local media for [messageId], in milliseconds. Null when there is no
     * local source, it cannot be opened, or the container's duration is missing or not positive
     * (`VoiceMessageBubble.swift:694-708`: a payload duration under 300 ms is replaced by the
     * file's own length). The source and the retriever are closed.
     */
    suspend fun durationMs(messageId: UUID): Int?

    val maxSealedBytes: Long
}
