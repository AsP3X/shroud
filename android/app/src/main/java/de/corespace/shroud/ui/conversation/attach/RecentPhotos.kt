package de.corespace.shroud.ui.conversation.attach

import android.graphics.Bitmap
import android.net.Uri
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.library.LibraryItem
import de.corespace.shroud.ui.media.PickedPhoto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A tile of the Recents strip: a cheap thumbnail and the image it came from (`ChatAttachSheet.swift:12-19`). */
class RecentPhoto(val uri: Uri, val thumbnail: Bitmap) {
    /** Never prints the URI. */
    override fun toString(): String = "RecentPhoto"
}

/**
 * The newest images on the phone for the Recents strip (`loadRecentPhotos`,
 * `ChatAttachSheet.swift:225-270`; conversation-compose-media §7.4), from core's photo library (K4
 * `media.photoLibrary`: images only, newest `DATE_ADDED` first, no `ACCESS_MEDIA_LOCATION`, only the
 * chosen images under partial access, empty when it cannot be read): at most [LIMIT], 192 px
 * thumbnails.
 */
object RecentPhotos {
    /** `fetchLimit = 12` (`ChatAttachSheet.swift:238`). */
    const val LIMIT = 12

    /** `targetSize 192` (`ChatAttachSheet.swift:250`). */
    const val THUMBNAIL_PX = 192

    /**
     * The newest images with their thumbnails ([recent] = `PhotoLibrary.recent`, [thumbnail] =
     * `PhotoLibrary.thumbnail`, both off main in core). One that cannot be thumbnailed is skipped.
     */
    suspend fun load(
        recent: suspend (limit: Int) -> List<LibraryItem>,
        thumbnail: suspend (uri: Uri, maxEdge: Int) -> Bitmap?,
        limit: Int = LIMIT,
    ): List<RecentPhoto> {
        val items = recent(limit)
        val photos = ArrayList<RecentPhoto>(items.size)
        for (item in items) {
            currentCoroutineContext().ensureActive()
            thumbnail(item.uri, THUMBNAIL_PX)?.let { photos += RecentPhoto(item.uri, it) }
        }
        return photos
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
