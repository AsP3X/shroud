package de.corespace.shroud.ui.conversation.composer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.video.VideoSource
import de.corespace.shroud.ui.camera.CameraCaptureScreen
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.isOverlayUp
import de.corespace.shroud.ui.components.rememberOverlayTransition
import de.corespace.shroud.ui.media.ComposeDraft
import de.corespace.shroud.ui.media.VideoComposeDraft
import de.corespace.shroud.ui.media.compose.MediaComposeScreen
import de.corespace.shroud.ui.media.video.VideoComposeScreen
import de.corespace.shroud.ui.media.video.VideoPlayerOverlay
import de.corespace.shroud.ui.media.viewer.MediaImageViewer
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.util.UUID

/**
 * The conversation's full-screen media layers and the "Sending media…" card (iOS `mediaViewerLayer`,
 * `photoComposeLayer`, `videoComposeLayer`, `videoPlayerLayer`, `sendingMediaLayer`,
 * `ConversationView.swift:224-228, 477-610`; conversation-compose-media §9.1, §15.1, §19, §20).
 *
 * They cover the chat's header, composer and status bar like iOS's `.ignoresSafeArea()` overlays,
 * so they draw in the app's [OverlayLayer] (one window, `FLAG_SECURE` intact), stacked in iOS's
 * z-order: viewer (50) → player (55) → photo compose (60) → video compose (61) → the in-app camera
 * (iOS presents its camera as a full-screen cover above all of them). While one is up the chat
 * below is hidden from TalkBack (`isModal`), and toasts the composer raises land on top of it at
 * the screen's bottom edge (`toastBottomInset = 0` while `coversComposer`, CV:208-212).
 *
 * Back closes the top surface first (§20): each layer registers its own handler as it appears, so
 * the newest wins, and the screens' own handlers (an open editor, W3-MEDIA-EDIT / W3-MEDIA-VIEW),
 * composed inside them afterwards, win over these.
 *
 * Transitions (CV:256-260, 501, 544, 576, 595): the viewer grows from 0.94 with a fade; the compose
 * screens rise from the bottom with a fade (in 200 ms, out 200 ms or 150 ms after a send); the
 * player fades; the camera slides up like a sheet. Reduce Motion fades everything.
 */
@Composable
internal fun ComposeMediaLayers(controller: ComposeController) {
    val reduce = ShroudTheme.reduceMotion

    val viewer = rememberOverlayTransition(controller.viewingMedia != null)
    val player = rememberOverlayTransition(controller.viewingVideo != null)
    val photoCompose = rememberOverlayTransition(controller.composeDraft != null)
    val videoCompose = rememberOverlayTransition(controller.videoDraft != null)
    val camera = rememberOverlayTransition(controller.showsCamera)

    // What a leaving layer keeps drawing while it animates out.
    var lastViewing by remember { mutableStateOf<UUID?>(null) }
    var lastVideo by remember { mutableStateOf<ComposeController.ViewingVideo?>(null) }
    var lastDraft by remember { mutableStateOf<ComposeDraft?>(null) }
    var lastVideoDraft by remember { mutableStateOf<VideoComposeDraft?>(null) }
    controller.viewingMedia?.let { lastViewing = it }
    controller.viewingVideo?.let { lastVideo = it }
    controller.composeDraft?.let { lastDraft = it }
    controller.videoDraft?.let { lastVideoDraft = it }

    val anyUp = viewer.isOverlayUp || player.isOverlayUp || photoCompose.isOverlayUp || videoCompose.isOverlayUp || camera.isOverlayUp
    OverlayLayer(active = anyUp) {
        Box(Modifier.fillMaxSize()) {
            MediaLayer(viewer, MediaLayerMotion.viewer(reduce)) {
                lastViewing?.let { id ->
                    BackHandler(enabled = controller.viewingMedia != null) { controller.closeMediaViewer() }
                    val thread = controller.thread
                    val items = remember(thread, controller.peerName) { controller.viewerItems() }
                    MediaImageViewer(
                        items = items,
                        initialId = id,
                        onClose = controller::closeMediaViewer,
                        // Only downloaded photos are listed: the viewer never fetches one itself (CV:2305-2310).
                        onLoad = null,
                        onDelete = controller::requestDeleteFromViewer,
                    )
                }
            }
            MediaLayer(player, MediaLayerMotion.player(reduce)) {
                lastVideo?.let { video ->
                    BackHandler(enabled = controller.viewingVideo != null) { controller.closeVideoPlayer() }
                    VideoPlayerOverlay(
                        source = VideoSource.Message(video.id),
                        title = video.title,
                        subtitle = video.dateLine,
                        onClose = controller::closeVideoPlayer,
                    )
                }
            }
            MediaLayer(photoCompose, MediaLayerMotion.compose(reduce, exitMs = if (controller.photoComposeSent) SEND_EXIT_MS else CLOSE_MS)) {
                lastDraft?.let { draft ->
                    BackHandler(enabled = controller.composeDraft != null) { controller.cancelMediaCompose() }
                    MediaComposeScreen(
                        draft = draft,
                        onSend = controller::sendComposedPhotos,
                        onAddMore = controller::openPickerToAppend,
                        onRemove = controller::removeComposePhoto,
                        onClose = controller::cancelMediaCompose,
                    )
                }
            }
            MediaLayer(videoCompose, MediaLayerMotion.compose(reduce, exitMs = CLOSE_MS)) {
                lastVideoDraft?.let { draft ->
                    BackHandler(enabled = controller.videoDraft != null) { controller.cancelVideoCompose() }
                    VideoComposeScreen(
                        draft = draft,
                        onSend = controller::sendComposedVideos,
                        onAddMore = controller::openPickerToAppend,
                        onRemove = controller::removeComposeVideo,
                        onClose = controller::cancelVideoCompose,
                    )
                }
            }
            MediaLayer(camera, MediaLayerMotion.camera(reduce)) {
                BackHandler(enabled = controller.showsCamera) { controller.closeCamera() }
                CameraCaptureScreen(
                    onPhoto = controller::onCameraPhoto,
                    onVideo = controller::onCameraVideo,
                    onClose = controller::closeCamera,
                )
            }
            // Toasts stay readable over a layer: at the screen's bottom edge, the composer being hidden.
            ToastHost(controller.coveredToasts, bottomInset = 0.dp)
        }
    }

    SendingMediaCard(controller.isSendingMedia)
}

/** One full-screen surface of [ComposeMediaLayers], driven by its own transition state. */
@Composable
private fun MediaLayer(state: MutableTransitionState<Boolean>, motion: MediaLayerMotion, content: @Composable () -> Unit) {
    AnimatedVisibility(visibleState = state, enter = motion.enter, exit = motion.exit, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** The layers' transitions (`ConversationView.swift:256-260, 501, 544, 576, 595`). */
private class MediaLayerMotion(val enter: EnterTransition, val exit: ExitTransition) {
    companion object {
        private fun reduced() = MediaLayerMotion(fadeIn(Motion.reduced()), fadeOut(Motion.reduced()))

        /** "Grows into place from just under full size — reads as 'zoom into the photo'" (CV:500-501). */
        fun viewer(reduce: Boolean) = if (reduce) {
            reduced()
        } else {
            MediaLayerMotion(
                scaleIn(Motion.easeOut(CLOSE_MS), initialScale = 0.94f) + fadeIn(Motion.easeOut(CLOSE_MS)),
                scaleOut(Motion.easeOut(CLOSE_MS), targetScale = 0.94f) + fadeOut(Motion.easeOut(CLOSE_MS)),
            )
        }

        /** The player fades (CV:595). */
        fun player(reduce: Boolean) = if (reduce) {
            reduced()
        } else {
            MediaLayerMotion(fadeIn(Motion.easeOut(CLOSE_MS)), fadeOut(Motion.easeOut(CLOSE_MS)))
        }

        /** "Compose is a sheet-like surface — it rises from the composer it replaces" (CV:543-544, 576). */
        fun compose(reduce: Boolean, exitMs: Int) = if (reduce) {
            reduced()
        } else {
            MediaLayerMotion(
                slideInVertically(Motion.easeOut(CLOSE_MS)) { it } + fadeIn(Motion.easeOut(CLOSE_MS)),
                slideOutVertically(Motion.easeOut(exitMs)) { it } + fadeOut(Motion.easeOut(exitMs)),
            )
        }

        /** iOS presents the camera as a full-screen cover: up like a sheet ([Motion.gentle]), down with [Motion.standard]. */
        fun camera(reduce: Boolean) = if (reduce) {
            reduced()
        } else {
            MediaLayerMotion(slideInVertically(Motion.gentle()) { it }, slideOutVertically(Motion.standard()) { it })
        }
    }
}

/**
 * "Sending media…" while staged photos go out one by one (`sendingMediaLayer`, CV:600-610;
 * conversation-compose-media §9.6): a centred glass card, spinner over the label, scaling in from
 * 0.9 with a fade ([Motion.snappy]). It covers nothing else: the composer and the thread stay usable
 * (a non-modal layer passes touches through), and TalkBack hears it once.
 */
@Composable
private fun SendingMediaCard(visible: Boolean) {
    val state = rememberOverlayTransition(visible)
    val reduce = ShroudTheme.reduceMotion
    OverlayLayer(active = state.isOverlayUp, modal = false) {
        val colors = ShroudTheme.colors
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visibleState = state,
                enter = if (reduce) fadeIn(Motion.reduced()) else scaleIn(Motion.snappy(), initialScale = 0.9f) + fadeIn(Motion.snappy()),
                exit = if (reduce) fadeOut(Motion.reduced()) else scaleOut(Motion.snappy(), targetScale = 0.9f) + fadeOut(Motion.snappy()),
            ) {
                Column(
                    Modifier
                        .glassSurface(RoundedCornerShape(14.dp), GlassStyle.Regular)
                        .padding(16.dp)
                        .clearAndSetSemantics {
                            contentDescription = SENDING_MEDIA
                            liveRegion = LiveRegionMode.Polite
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spinner(colors.textSecondary)
                    ShroudText(SENDING_MEDIA, inter(17f), colors.textPrimary, maxLines = 1)
                }
            }
        }
    }
}

/** U+2026 (CV:604). */
internal const val SENDING_MEDIA = "Sending media…"

/** `.easeOut(duration: 0.2)`: opening and closing a layer (CV:486, 513, 589, 1840, 1864). */
private const val CLOSE_MS = 200

/** `.easeOut(duration: 0.15)`: the photo compose leaving after Send (CV:521). */
private const val SEND_EXIT_MS = 150
