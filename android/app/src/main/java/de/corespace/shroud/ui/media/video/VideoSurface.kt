package de.corespace.shroud.ui.media.video

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW

/**
 * The picture of a [de.corespace.shroud.core.media.video.ChatVideoPlayer]'s [player], aspect-fit
 * into [modifier]'s box (iOS `PlayerLayerView` with `.resizeAspect`, `VideoPlayerOverlay.swift:374-401`).
 *
 * A `TextureView`, not a `SurfaceView`: the player overlay scales, moves and fades the stage while
 * it is dragged away, and only a texture composes with those layer transforms reliably
 * (conversation-compose-media §16). No shutter: the black surface behind shows until the first frame.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    ContentFrame(
        player = player,
        modifier = modifier,
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        contentScale = ContentScale.Fit,
        keepContentOnReset = false,
        shutter = { Box(Modifier) },
    )
}
