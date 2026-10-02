package de.corespace.shroud.ui.media.video

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few Phosphor glyphs the camera and the video compose screen need that `ShroudIcons` does not
 * have yet (@phosphor-icons/core 2.1.1, MIT; each path is the SVG's own, 256-unit fills — the same
 * recipe as `ShroudIcons`). Kept here so this item does not regenerate the shared generated file;
 * the INT pass can move them into `gen_shroud_icons.py` (and `assets/licenses/icons.txt`) unchanged.
 */
internal object MediaViewIcons {
    /** Phosphor `arrow-counter-clockwise` — Reset trim (conversation-compose-media §15.3; iOS `arrow.counterclockwise`). */
    val ArrowCounterClockwise: ImageVector by lazy {
        phosphorIcon(
            "ArrowCounterClockwise",
            "M224,128a96,96,0,0,1-94.71,96H128A95.38,95.38,0,0,1,62.1,197.8a8,8,0,0,1,11-11.63A80,80,0,1,0,71.43,71.39a3.07,3.07,0,0,1-.26.25L44.59,96H72a8,8,0,0,1,0,16H24a8,8,0,0,1-8-8V56a8,8,0,0,1,16,0V85.8L60.25,60A96,96,0,0,1,224,128Z",
        )
    }

    /** Phosphor `lightning-fill` — the camera's torch, on. */
    val LightningFill: ImageVector by lazy {
        phosphorIcon(
            "LightningFill",
            "M213.85,125.46l-112,120a8,8,0,0,1-13.69-7l14.66-73.33L45.19,143.49a8,8,0,0,1-3-13l112-120a8,8,0,0,1,13.69,7L153.18,90.9l57.63,21.61a8,8,0,0,1,3,12.95Z",
        )
    }

    /** Phosphor `lightning-slash-fill` — the camera's torch, off. */
    val LightningSlashFill: ImageVector by lazy {
        phosphorIcon(
            "LightningSlashFill",
            "M105.72,67.81a4,4,0,0,1,0-5.42l48.39-51.85a8,8,0,0,1,13.7,7L153.18,90.9l57.43,21.53a8.24,8.24,0,0,1,4.22,3.4,8,8,0,0,1-1,9.63l-25.27,27.07a4,4,0,0,1-5.88,0Zm27.76,54.32L53.92,34.62A8,8,0,1,0,42.08,45.38L81.34,88.56l-39,41.83A8.15,8.15,0,0,0,40,135.31a8,8,0,0,0,5.16,8.18l57.63,21.61L88.16,238.43a8,8,0,0,0,13.69,7l61.86-66.28,38.37,42.2a8,8,0,1,0,11.84-10.76Z",
        )
    }
}

/** A Phosphor fill glyph (256-unit viewport), tinted by `ShroudIcon`. */
private fun phosphorIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 256f,
        viewportHeight = 256f,
    ).apply {
        for (data in paths) addPath(pathData = addPathNodes(data), fill = SolidColor(Color.Black))
    }.build()
