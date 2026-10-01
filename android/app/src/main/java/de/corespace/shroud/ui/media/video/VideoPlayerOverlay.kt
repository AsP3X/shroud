package de.corespace.shroud.ui.media.video

import androidx.compose.runtime.Composable
import de.corespace.shroud.core.media.video.VideoSource

/**
 * The full-screen video player (conversation-compose-media §12.3; iOS `VideoPlayerOverlay`), on a
 * `ChatVideoPlayer` from `VideoModule.newPlayer()`, which plays the same [VideoSource]
 * (`Message` = a message's sealed video, `Content` = a picked or captured file).
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-MEDIA-VIEW**, which replaces the body.
 * Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VideoPlayerOverlay(source: VideoSource, title: String, subtitle: String, onClose: () -> Unit) {
}
