package de.corespace.shroud.ui.media.viewer

import androidx.compose.runtime.Composable
import de.corespace.shroud.ui.media.ViewerItem
import java.util.UUID

/**
 * The photo viewer: paging, zoom, swipe to dismiss, share and delete (conversation-compose-media
 * §12.1; iOS `MediaImageViewer`). [onLoad] fetches an item that is not on this phone yet; [onDelete]
 * is null where deleting is not offered.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-MEDIA-VIEW**, which replaces the body.
 * Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MediaImageViewer(items: List<ViewerItem>, initialId: UUID, onClose: () -> Unit, onLoad: ((UUID) -> Unit)?, onDelete: ((UUID) -> Unit)?) {
}
