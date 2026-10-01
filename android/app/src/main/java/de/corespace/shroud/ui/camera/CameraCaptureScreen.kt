package de.corespace.shroud.ui.camera

import androidx.compose.runtime.Composable
import de.corespace.shroud.ui.media.PickedMovie
import de.corespace.shroud.ui.media.PickedPhoto

/**
 * The in-app camera: photo and video capture with CameraX (conversation-compose-media §9; iOS
 * `CameraCaptureView`). A recorded clip is a [PickedMovie] owning its `cacheDir/shroud-*` file.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-MEDIA-VIEW**, which replaces the body.
 * Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CameraCaptureScreen(onPhoto: (PickedPhoto) -> Unit, onVideo: (PickedMovie) -> Unit, onClose: () -> Unit) {
}
