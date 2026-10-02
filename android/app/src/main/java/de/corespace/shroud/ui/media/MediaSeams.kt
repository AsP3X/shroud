package de.corespace.shroud.ui.media

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.scale
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.video.VideoProbe
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Shared types of the media screens (conversation-compose-media §5–§12; plan §1.7.13, C26, C27).
 * **Seam (W2-INT), owner W3-COMPOSER in wave 3**; the screens are one stub file each, owned by
 * W3-MEDIA-EDIT and W3-MEDIA-VIEW. Everything here is memory only and dropped when the chat closes
 * or locks (conversation-compose-media §22).
 */

/**
 * A photo picked or captured for sending (iOS `PickedPhoto`, `MediaComposeOverlay.swift:4-41`): a
 * screen-sized [preview] (≤ [PREVIEW_MAX_EDGE] px, orientation baked in) for the compose screen and
 * the [source] read again at send time — a content URI from the picker or the Recents strip, never
 * the full original in memory (C27), or the full bitmap of a camera capture. [id] is the photo's
 * stable identity: edits follow the photo, not its index.
 */
class PickedPhoto(val id: UUID = UUID.randomUUID(), val preview: Bitmap, val source: MediaImageSource) {
    /** Never prints the source (a URI names a file on the phone). */
    override fun toString(): String = "PickedPhoto(${preview.width}x${preview.height})"

    companion object {
        /** The compose screen's preview edge, as the library path decodes it (`ConversationView.swift:1765`). */
        const val PREVIEW_MAX_EDGE = 2048

        /**
         * A camera capture or another in-memory image, which has no original file to keep
         * (`PickedPhoto.init(image:)`, `MediaComposeOverlay.swift:14-35`): the full [image] is the
         * [source], the preview is it scaled to [PREVIEW_MAX_EDGE] on the long edge with rounded
         * dimensions, opaque. An image that already fits is its own preview.
         */
        fun fromImage(image: Bitmap): PickedPhoto {
            val (width, height) = previewSize(image.width, image.height)
            val preview = if (width == image.width && height == image.height) {
                image
            } else {
                image.scale(width, height, filter = true).also { it.setHasAlpha(false) }
            }
            return PickedPhoto(preview = preview, source = MediaImageSource.Decoded(image))
        }

        /**
         * The preview size of a `width × height` capture: unchanged up to [PREVIEW_MAX_EDGE], else
         * scaled so the long edge is 2048 and both sides rounded (`MediaComposeOverlay.swift:18-27`).
         */
        fun previewSize(width: Int, height: Int): Pair<Int, Int> {
            val longEdge = max(width, height)
            if (longEdge <= PREVIEW_MAX_EDGE) return width to height
            val scale = PREVIEW_MAX_EDGE.toDouble() / longEdge
            return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
        }
    }
}

/**
 * A picked or recorded video. Picked clips stay content URIs, readable for the life of the process
 * (C26; no temp copy as on iOS, conversation-compose-media §8.5); a camera recording owns its
 * `cacheDir/shroud-*` file, which [cleanup] deletes once the clip was sent or dropped.
 */
class PickedMovie(val uri: Uri, private val ownedFile: File? = null) {
    /** Deletes the owned capture file; nothing for picker URIs (`PickedMovie.swift:35-38`). Safe to call twice. */
    fun cleanup() {
        ownedFile?.delete()
    }

    /** Never prints the URI. */
    override fun toString(): String = if (ownedFile != null) "PickedMovie(capture)" else "PickedMovie(picked)"
}

/** A picked movie plus what the compose screen needs before it can draw anything (`PickedMovie.swift:41-55`). */
data class PickedVideo(val id: UUID = UUID.randomUUID(), val movie: PickedMovie, val probe: VideoProbe, val poster: Bitmap?) {
    val uri: Uri get() = movie.uri
}

/**
 * What the photo compose screen opens with (iOS `ComposeDraft`, `ConversationView.swift:655-659`).
 * [peerName] is the recipient the compose screen names ("Sending to {peer}", MCO:229-277); empty when
 * the host does not know it.
 */
data class ComposeDraft(
    val photos: List<PickedPhoto>,
    val caption: String = "",
    val quality: MediaComposeQuality = MediaComposeQuality.Original,
    val peerName: String = "",
)

/** What the video compose screen opens with (`ConversationView.swift:661-665`); [peerName] as in [ComposeDraft]. */
data class VideoComposeDraft(val videos: List<PickedVideo>, val caption: String = "", val peerName: String = "")

/**
 * One page of the photo viewer: [title] is the sender ("You" or the name), [dateLine] the sent time,
 * [isLoaded] whether the full image is on this phone yet (else the viewer asks `onLoad`).
 */
data class ViewerItem(val id: UUID, val title: String, val dateLine: String, val caption: String?, val aspect: Float, val isLoaded: Boolean)

/** An album holds at most ten items, photos or videos (Telegram's cap, `ConversationView.swift:667-668`). */
const val MAX_MEDIA_PER_SEND = 10
