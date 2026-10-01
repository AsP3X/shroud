package de.corespace.shroud.ui.media

import android.graphics.Bitmap
import android.net.Uri
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.video.VideoProbe
import java.io.File
import java.util.UUID

/*
 * Shared types of the media screens (conversation-compose-media §5–§12; plan §1.7.13, C26, C27).
 * **Seam (W2-INT), owner W3-COMPOSER in wave 3**; the screens are one stub file each, owned by
 * W3-MEDIA-EDIT and W3-MEDIA-VIEW.
 */

/**
 * A photo picked or captured for sending: a small [preview] for the compose screen and the
 * [source] read again at send time (a content URI, never the full image in memory, C27).
 */
class PickedPhoto(val id: UUID = UUID.randomUUID(), val preview: Bitmap, val source: MediaImageSource)

/**
 * A picked or recorded video. Picked clips stay content URIs (C26); a camera recording owns its
 * `cacheDir/shroud-*` file, which [cleanup] deletes once the clip was sent or dropped.
 */
class PickedMovie(val uri: Uri, private val ownedFile: File? = null) {
    fun cleanup() {
        ownedFile?.delete()
    }
}

data class PickedVideo(val id: UUID = UUID.randomUUID(), val movie: PickedMovie, val probe: VideoProbe, val poster: Bitmap?)

/** What the photo compose screen opens with (iOS `MediaComposeDraft`). */
data class ComposeDraft(val photos: List<PickedPhoto>, val caption: String = "", val quality: MediaComposeQuality = MediaComposeQuality.Original)

/** What the video compose screen opens with. */
data class VideoComposeDraft(val videos: List<PickedVideo>, val caption: String = "")

/**
 * One page of the photo viewer: [title] is the sender ("You" or the name), [dateLine] the sent time,
 * [isLoaded] whether the full image is on this phone yet (else the viewer asks `onLoad`).
 */
data class ViewerItem(val id: UUID, val title: String, val dateLine: String, val caption: String?, val aspect: Float, val isLoaded: Boolean)

/** What the video player plays: a message's sealed media (read through `SealedMediaDataSource`, C7) or a local clip. */
sealed interface VideoSource {
    data class Message(val messageId: UUID) : VideoSource

    data class Local(val uri: Uri) : VideoSource
}
