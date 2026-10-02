package de.corespace.shroud.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException

/**
 * Applies a colour theme change to everything inside as a cross-fade of the whole window, the way
 * iOS switches the window's `overrideUserInterfaceStyle` inside `UIView.transition(with: window,
 * duration: 0.3, options: [.transitionCrossDissolve, .allowUserInteraction])`
 * (`ios/shroud/ShroudUI/Theme/ColorThemePreference.swift:104-126`; settings-lock §8.2).
 *
 * [content] is drawn in the theme it is handed, which follows [dark] one frame late: when [dark]
 * changes, the last frame drawn in the old theme is kept as a picture, the content switches, and
 * the picture fades out over it in [ThemeCrossfadeMath.DURATION_MS]. Every colour changes at once and nothing
 * recomposes per frame (only the picture's alpha is drawn anew), so the fade costs one bitmap.
 * Touches keep reaching the content meanwhile. Nothing moves, so the fade stays under Reduce
 * Motion, as on iOS: it runs on the frame clock rather than an animation spec, which the system's
 * "Remove animations" would cut to nothing. The first composition draws in [dark] straight away —
 * no fade at launch (`ColorThemePreference.swift:107-111`). Where the picture cannot be taken the
 * theme switches without a fade.
 */
@Composable
fun ThemeCrossfade(dark: Boolean, modifier: Modifier = Modifier, content: @Composable (dark: Boolean) -> Unit) =
    ThemeCrossfade(dark, modifier, nextFrameNanos = { withFrameNanos { it } }, content)

/** [ThemeCrossfade] on a given frame clock; tests step [nextFrameNanos] by hand. */
@Composable
internal fun ThemeCrossfade(
    dark: Boolean,
    modifier: Modifier,
    nextFrameNanos: suspend () -> Long,
    content: @Composable (dark: Boolean) -> Unit,
) {
    var applied by remember { mutableStateOf(dark) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    val alpha = remember { mutableFloatStateOf(0f) }
    val layer = rememberGraphicsLayer()

    LaunchedEffect(dark) {
        if (dark == applied) return@LaunchedEffect
        val old = if (layer.size == IntSize.Zero) {
            null
        } else {
            try {
                layer.toImageBitmap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
        }
        picture = old
        alpha.floatValue = 1f
        applied = dark
        if (old == null) return@LaunchedEffect
        try {
            val start = nextFrameNanos()
            while (true) {
                val elapsed = nextFrameNanos() - start
                val progress = ThemeCrossfadeMath.pictureAlpha(elapsed)
                alpha.floatValue = progress
                if (progress == 0f) break
            }
        } finally {
            // A newer switch has already put its own picture up: leave that one.
            if (picture === old) picture = null
        }
    }

    Box(
        modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            picture?.let { drawImage(it, alpha = alpha.floatValue) }
        },
        propagateMinConstraints = true,
    ) {
        content(applied)
    }
}

/** The fade's timing (pure, so it is tested). */
object ThemeCrossfadeMath {
    /** iOS's `duration: 0.3` (`ColorThemePreference.swift:121`). */
    const val DURATION_MS = 300L

    /** The old picture's opacity [elapsedNanos] after the switch: linear 1 → 0, like a cross-dissolve. */
    fun pictureAlpha(elapsedNanos: Long): Float {
        val t = elapsedNanos / (DURATION_MS * 1_000_000f)
        return (1f - t).coerceIn(0f, 1f)
    }
}
