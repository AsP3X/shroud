package de.corespace.shroud.ui.conversation.attach

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.ui.media.PickedPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** A tile of the Recents strip: a cheap thumbnail and the image it came from (`ChatAttachSheet.swift:12-19`). */
class RecentPhoto(val uri: Uri, val thumbnail: Bitmap) {
    /** Never prints the URI. */
    override fun toString(): String = "RecentPhoto"
}

/**
 * The newest images on the phone for the Recents strip (`loadRecentPhotos`,
 * `ChatAttachSheet.swift:225-270`; conversation-compose-media §7.4). MediaStore, newest
 * `DATE_ADDED` first (iOS sorts by capture date; `DATE_TAKEN` is often missing for downloads),
 * at most [LIMIT], 192 px thumbnails. Never asks for `ACCESS_MEDIA_LOCATION`, so MediaStore redacts
 * GPS from anything read here. With partial access MediaStore lists only the chosen images.
 */
object RecentPhotos {
    /** `fetchLimit = 12` (`ChatAttachSheet.swift:238`). */
    const val LIMIT = 12

    /** `targetSize 192` (`ChatAttachSheet.swift:250`). */
    const val THUMBNAIL_PX = 192

    /** The collection the strip reads. */
    val collection: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** The query: ids only, newest added first, at most [limit]. */
    fun queryArgs(limit: Int = LIMIT): Bundle = Bundle().apply {
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }

    /**
     * The newest images with their thumbnails; one that cannot be thumbnailed is skipped, and a
     * library that cannot be read (access revoked meanwhile) reads as empty. Off the main thread.
     */
    suspend fun load(resolver: ContentResolver, limit: Int = LIMIT): List<RecentPhoto> = withContext(Dispatchers.IO) {
        val uris = ArrayList<Uri>(limit)
        try {
            resolver.query(collection, arrayOf(MediaStore.Images.Media._ID), queryArgs(limit), null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && uris.size < limit) {
                    uris += ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                }
            }
        } catch (_: SecurityException) {
            return@withContext emptyList()
        } catch (_: IllegalArgumentException) {
            return@withContext emptyList()
        }
        val photos = ArrayList<RecentPhoto>(uris.size)
        for (uri in uris) {
            coroutineContext.ensureActive()
            val thumbnail = try {
                resolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (thumbnail != null) photos += RecentPhoto(uri, thumbnail)
        }
        photos
    }

    /**
     * The tapped image for compose (`originalPhoto`, `ChatAttachSheet.swift:285-313`): a 2048-px
     * preview over the image's URI, which the encoder reads once at send — "Original" means the real
     * file (C27). Falls back to the thumbnail only when the image cannot be decoded.
     */
    suspend fun original(photo: RecentPhoto, decodePreview: suspend (MediaImageSource, Int) -> Bitmap?): PickedPhoto {
        val source = MediaImageSource.ContentUri(photo.uri)
        val preview = decodePreview(source, PickedPhoto.PREVIEW_MAX_EDGE) ?: return PickedPhoto.fromImage(photo.thumbnail)
        return PickedPhoto(preview = preview, source = source)
    }
}
