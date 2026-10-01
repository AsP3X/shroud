package de.corespace.shroud.ui.media.video

import androidx.compose.runtime.Composable
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.ui.media.VideoComposeDraft

/**
 * The video compose screen: trim, quality, mute, caption, send (conversation-compose-media §11;
 * iOS `VideoComposeOverlay`). [onSend] gets one [VideoSendPlan] per clip.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-MEDIA-VIEW**, which replaces the body.
 * Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VideoComposeScreen(
    draft: VideoComposeDraft,
    onSend: (List<VideoSendPlan>) -> Unit,
    onAddMore: () -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
) {
}
