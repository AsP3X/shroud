package de.corespace.shroud.core.media.library

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * One photo the recents strip can offer. Videos are not listed: the composer queried
 * `MediaStore.Images` only (P9, w3-composer `RecentPhotos`), so [isVideo] is false and [durationMs]
 * is null. [dateTaken] is `DATE_TAKEN` when the row has one.
 *
 * Never prints the URI.
 */
data class LibraryItem(
    val uri: Uri,
    val isVideo: Boolean,
    val dateTaken: Instant?,
    val durationMs: Long?,
) {
    override fun toString(): String = "LibraryItem(isVideo=$isVideo)"
}

/**
 * What the process may read from the photo library (conversation-compose-media §7.4).
 * [Partial] is Android 14+ "select photos" (`READ_MEDIA_VISUAL_USER_SELECTED` and not full images).
 */
sealed interface LibraryAccess {
    data object Full : LibraryAccess

    data object Partial : LibraryAccess

    data object None : LibraryAccess
}

/**
 * The phone's photos for the recents strip. [recent] is newest `DATE_ADDED` first, off the main
 * thread, and returns an empty list when [access] is [LibraryAccess.None] or the query fails —
 * it does not throw. [thumbnail] is null when the image cannot be decoded.
 */
interface PhotoLibrary {
    fun access(): LibraryAccess

    suspend fun recent(limit: Int): List<LibraryItem>

    suspend fun thumbnail(uri: Uri, maxEdge: Int): Bitmap?
}

/**
 * [PhotoLibrary] on MediaStore. Images only, external volume, the same query arguments as
 * w3-composer `RecentPhotos`: `DATE_ADDED` descending, a limit, no `ACCESS_MEDIA_LOCATION` (the
 * platform then redacts GPS). Partial access lists only the photos the user selected, because
 * that is what MediaStore returns.
 */
class MediaStorePhotoLibrary internal constructor(
    private val context: Context,
    private val images: (Int) -> List<LibraryItem>,
    private val thumbs: (Uri, Int) -> Bitmap?,
    private val io: CoroutineDispatcher,
) : PhotoLibrary {
    constructor(context: Context) : this(
        context,
        images = { limit -> MediaStoreQueries.recentImages(context.contentResolver, limit) },
        thumbs = { uri, edge -> MediaStoreQueries.thumbnail(context.contentResolver, uri, edge) },
        io = Dispatchers.IO,
    )

    override fun access(): LibraryAccess = access(Build.VERSION.SDK_INT) { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    internal fun access(sdkInt: Int, granted: (String) -> Boolean): LibraryAccess = when {
        sdkInt >= 34 -> when {
            granted(READ_MEDIA_IMAGES) -> LibraryAccess.Full
            granted(READ_MEDIA_VISUAL_USER_SELECTED) -> LibraryAccess.Partial
            else -> LibraryAccess.None
        }
        sdkInt >= 33 -> if (granted(READ_MEDIA_IMAGES)) LibraryAccess.Full else LibraryAccess.None
        else -> if (granted(READ_EXTERNAL_STORAGE)) LibraryAccess.Full else LibraryAccess.None
    }

    override suspend fun recent(limit: Int): List<LibraryItem> = withContext(io) {
        if (limit <= 0 || access() == LibraryAccess.None) return@withContext emptyList()
        try {
            images(limit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun thumbnail(uri: Uri, maxEdge: Int): Bitmap? = withContext(io) {
        if (maxEdge <= 0) return@withContext null
        try {
            thumbs(uri, maxEdge)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        internal const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
        internal const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        internal const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    }
}

/** The MediaStore read. Call it off the main thread; the caller catches failures. */
internal object MediaStoreQueries {
    fun recentImages(resolver: ContentResolver, limit: Int): List<LibraryItem> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        val items = ArrayList<LibraryItem>(limit.coerceAtLeast(0))
        resolver.query(collection, projection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext() && items.size < limit) {
                val taken = if (takenColumn >= 0 && !cursor.isNull(takenColumn)) {
                    cursor.getLong(takenColumn).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
                } else {
                    null
                }
                items += LibraryItem(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                    isVideo = false,
                    dateTaken = taken,
                    durationMs = null,
                )
            }
        }
        return items
    }

    fun thumbnail(resolver: ContentResolver, uri: Uri, maxEdge: Int): Bitmap? {
        if (maxEdge <= 0) return null
        return resolver.loadThumbnail(uri, Size(maxEdge, maxEdge), null)
    }
}
