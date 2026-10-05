package de.corespace.shroud.core.media.share

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import de.corespace.shroud.core.media.SealedMediaReader
import de.corespace.shroud.core.media.files.FileContentCheck
import de.corespace.shroud.core.media.files.FileCopy
import de.corespace.shroud.core.media.files.FileNames
import de.corespace.shroud.core.media.files.FileTypes
import de.corespace.shroud.core.model.AppClock
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
}

/** A share grant and the MIME type of the bytes behind it. */
data class ShareTarget(val uri: Uri, val mime: String)

/**
 * Share and save of media that is already decrypted (conversation-compose-media §18.5).
 * [shareUri] is a `content://` grant over bytes kept in memory — no plaintext file. The share
 * sheet itself is built by the UI. [revokeAll] drops those bytes. [saveToGallery] inserts a new
 * MediaStore row with `IS_PENDING` set, writes, then clears the flag. Metadata is already scrubbed
 * by the time the bytes are in the local cache.
 */
interface MediaSharing {
    /** Null when that message's bytes are not loaded. The path segment is random, not the message id. */
    suspend fun shareUri(messageId: UUID): Uri?

    /** [shareUri] plus the MIME type already stored for that grant. Null in the same cases. */
    suspend fun shareTarget(messageId: UUID): ShareTarget?

    fun revokeAll()

    /**
     * Drops the grants over [messageIds]' media, photo and file alike, and closes the readers file
     * grants opened: a deleted message stops reading even through a descriptor another app already
     * holds (docs/file-sharing.md §8; iOS closes Quick Look on delete). Messaging's purges call it.
     */
    fun revoke(messageIds: Collection<UUID>)

    /** A user sentence in [SaveOutcome.Failed]; this does not throw. */
    suspend fun saveToGallery(messageId: UUID): SaveOutcome
}

/**
 * [MediaSharing] for photos and videos (bytes in memory) and [FileSharing] for files (a reader per
 * open, [openReader]); one registry, so [revokeAll] drops both kinds of grant.
 */
internal class MemoryMediaSharing(
    private val context: Context,
    private val load: suspend (UUID) -> ByteArray?,
    private val clock: AppClock,
    private val authority: String = "${context.packageName}.media",
    private val openReader: (UUID) -> SealedMediaReader? = { null },
) : MediaSharing, FileSharing {
    override suspend fun shareUri(messageId: UUID): Uri? = shareTarget(messageId)?.uri

    override suspend fun shareTarget(messageId: UUID): ShareTarget? {
        val bytes = try {
            load(messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        if (bytes.isEmpty()) return null
        val id = UUID.randomUUID().toString()
        val kind = MediaKind.of(bytes)
        SharedMediaRegistry.put(id, messageId, bytes, kind.mime)
        val uri = Uri.Builder().scheme("content").authority(authority).appendPath(id).build()
        return ShareTarget(uri, kind.mime)
    }

    override fun revokeAll() {
        SharedMediaRegistry.clear()
    }

    override fun revoke(messageIds: Collection<UUID>) = SharedMediaRegistry.revoke(messageIds)

    override suspend fun saveToGallery(messageId: UUID): SaveOutcome = withContext(Dispatchers.IO) {
        val bytes = try {
            load(messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext SaveOutcome.Failed(PHOTO)
        if (bytes.isEmpty()) return@withContext SaveOutcome.Failed(PHOTO)
        val kind = MediaKind.of(bytes)
        val failure = if (kind.video) VIDEO else PHOTO
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName(kind.extension))
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (kind.video) "Movies/Shroud" else "Pictures/Shroud")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (kind.video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val uri = try {
            context.contentResolver.insert(collection, values)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext SaveOutcome.Failed(failure)
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw java.io.IOException("no stream")
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (context.contentResolver.update(uri, published, null, null) == 0) throw java.io.IOException("pending")
            SaveOutcome.Saved
        } catch (e: CancellationException) {
            abandon(uri)
            throw e
        } catch (_: Exception) {
            abandon(uri)
            SaveOutcome.Failed(failure)
        }
    }

    override suspend fun openTarget(messageId: UUID, fileName: String): FileOpenOutcome = withContext(Dispatchers.IO) {
        val name = FileNames.clean(fileName)
        val extension = FileNames.extension(name)
        val type = FileTypes.forExtension(extension)
        if (type == null || extension == null || !type.canOpen) return@withContext FileOpenOutcome.Refused(FileCopy.COULD_NOT_OPEN)
        val head = readHead(messageId) ?: return@withContext FileOpenOutcome.Refused(FileCopy.COULD_NOT_OPEN)
        try {
            if (!FileContentCheck.matches(type, head.first, head.second)) return@withContext FileOpenOutcome.Refused(FileCopy.mismatch(extension))
        } finally {
            head.first.fill(0)
        }
        val target = grantFile(messageId, name, type.mime) ?: return@withContext FileOpenOutcome.Refused(FileCopy.COULD_NOT_OPEN)
        FileOpenOutcome.Ready(target, extension)
    }

    override suspend fun fileShareTarget(messageId: UUID, fileName: String): ShareTarget? = withContext(Dispatchers.IO) {
        val name = FileNames.clean(fileName)
        val type = FileTypes.forName(name) ?: return@withContext null
        grantFile(messageId, name, type.mime)
    }

    override suspend fun saveToDownloads(messageId: UUID, fileName: String): SaveOutcome = withContext(Dispatchers.IO) {
        val name = FileNames.clean(fileName)
        val type = FileTypes.forName(name) ?: return@withContext SaveOutcome.Failed(FileCopy.COULD_NOT_SAVE)
        val reader = openReaderOrNull(messageId) ?: return@withContext SaveOutcome.Failed(FileCopy.COULD_NOT_SAVE)
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Shroud")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = try {
                context.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@withContext SaveOutcome.Failed(FileCopy.COULD_NOT_SAVE)
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    val buffer = ByteArray(COPY_CHUNK_BYTES)
                    try {
                        var position = 0L
                        while (true) {
                            val read = reader.read(position, buffer, 0, buffer.size)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            position += read
                        }
                        if (position != reader.length) throw IOException("short read")
                    } finally {
                        buffer.fill(0)
                    }
                } ?: throw IOException("no stream")
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (context.contentResolver.update(uri, published, null, null) == 0) throw IOException("pending")
                SaveOutcome.Saved
            } catch (e: CancellationException) {
                abandon(uri)
                throw e
            } catch (_: Exception) {
                abandon(uri)
                SaveOutcome.Failed(FileCopy.COULD_NOT_SAVE)
            }
        } finally {
            closeQuietly(reader)
        }
    }

    /** A file grant whose reads open a fresh reader each time; null when the file is not on this phone. */
    private fun grantFile(messageId: UUID, name: String, mime: String): ShareTarget? {
        val size = openReaderOrNull(messageId)?.let { reader -> reader.length.also { closeQuietly(reader) } } ?: return null
        val id = UUID.randomUUID().toString()
        SharedMediaRegistry.putFile(id, SharedMediaRegistry.SealedFile(messageId, name, size, mime) { openReaderOrNull(messageId) })
        val uri = Uri.Builder().scheme("content").authority(authority).appendPath(id).build()
        return ShareTarget(uri, mime)
    }

    /** The file's first [FileContentCheck.HEAD_BYTES] (and how many there are), or null when it is not here. */
    private fun readHead(messageId: UUID): Pair<ByteArray, Int>? {
        val reader = openReaderOrNull(messageId) ?: return null
        return try {
            val head = ByteArray(FileContentCheck.HEAD_BYTES)
            var filled = 0
            while (filled < head.size) {
                val read = reader.read(filled.toLong(), head, filled, head.size - filled)
                if (read <= 0) break
                filled += read
            }
            head to filled
        } catch (_: IOException) {
            null
        } finally {
            closeQuietly(reader)
        }
    }

    private fun openReaderOrNull(messageId: UUID): SealedMediaReader? = try {
        openReader(messageId)
    } catch (_: Exception) {
        null
    }

    private fun closeQuietly(reader: SealedMediaReader) {
        try {
            reader.close()
        } catch (_: IOException) {
        }
    }

    private fun abandon(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** `Shroud yyyy-MM-dd HH.mm.ss.ext` — a timestamp, never a filename the user typed. */
    private fun displayName(extension: String): String {
        val local = clock.now().atZone(ZoneId.systemDefault()).toLocalDateTime()
        return "Shroud ${STAMP.format(local)}.$extension"
    }

    private companion object {
        const val PHOTO = "Could not save that photo."
        const val VIDEO = "Could not save that video."
        const val COPY_CHUNK_BYTES = 64 * 1024
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss", Locale.US)
    }
}
