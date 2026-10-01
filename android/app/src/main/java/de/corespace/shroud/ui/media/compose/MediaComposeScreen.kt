package de.corespace.shroud.ui.media.compose

import androidx.compose.runtime.Composable
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.ui.media.ComposeDraft

/**
 * The photo compose screen: pages, caption, quality, crop / filter / draw / text editors, send
 * (conversation-compose-media §10; iOS `MediaComposeOverlay`). [onSend] gets one [MediaEdits] per photo.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-MEDIA-EDIT**, which replaces the body.
 * Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MediaComposeScreen(
    draft: ComposeDraft,
    onSend: (caption: String, quality: MediaComposeQuality, edits: List<MediaEdits>) -> Unit,
    onAddMore: () -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
) {
}
