package de.corespace.shroud.core.media.share

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import de.corespace.shroud.core.model.AppClock
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

    /** A user sentence in [SaveOutcome.Failed]; this does not throw. */
    suspend fun saveToGallery(messageId: UUID): SaveOutcome
}

internal class MemoryMediaSharing(
    private val context: Context,
    private val load: suspend (UUID) -> ByteArray?,
    private val clock: AppClock,
    private val authority: String = "${context.packageName}.media",
) : MediaSharing {
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
        SharedMediaRegistry.put(id, bytes, kind.mime)
        val uri = Uri.Builder().scheme("content").authority(authority).appendPath(id).build()
        return ShareTarget(uri, kind.mime)
    }

    override fun revokeAll() {
        SharedMediaRegistry.clear()
    }

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
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss", Locale.US)
    }
}
