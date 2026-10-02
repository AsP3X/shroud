package de.corespace.shroud.ui.calls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.IntSize
import de.corespace.shroud.ui.theme.Motion
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The zoom rules of their shared screen (iOS `SharedScreenContainer`, `SharedScreenView.swift:46-164`; calls §8.9). Pure, px. */
object SharedScreenZoom {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f

    /** A double tap zooms in this far round the point tapped (:132-138). */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** Zoomed beyond this, a double tap goes back to the whole picture (:128). */
    const val ZOOMED = 1.01f

    /** The whole [picture] in [view], as large as it fits, rounded (`fittedSize`, :95-102); the view's size before the first frame. */
    fun fittedSize(picture: Size, view: Size): Size {
        if (picture.width <= 0f || picture.height <= 0f || view.width <= 0f || view.height <= 0f) return view
        val scale = min(view.width / picture.width, view.height / picture.height)
        return Size((picture.width * scale).roundToInt().toFloat(), (picture.height * scale).roundToInt().toFloat())
    }

    /** The same picture at another resolution (within 1 %) keeps the zoom; a new shape is fitted whole again (:150-159). */
    fun keepsZoom(old: Size, new: Size): Boolean {
        if (old.width <= 0f || old.height <= 0f || new.width <= 0f || new.height <= 0f) return false
        return abs((new.width / new.height) / (old.width / old.height) - 1f) < 0.01f
    }

    /** How far the content may move each way at [scale]: none while it fits ([content] centred in [view]). */
    fun maxOffset(content: Size, view: Size, scale: Float): Offset = Offset(
        max(0f, (content.width * scale - view.width) / 2f),
        max(0f, (content.height * scale - view.height) / 2f),
    )

    fun clamp(offset: Offset, content: Size, view: Size, scale: Float): Offset {
        val limit = maxOffset(content, view, scale)
        return Offset(offset.x.coerceIn(-limit.x, limit.x), offset.y.coerceIn(-limit.y, limit.y))
    }

    /**
     * The offset that keeps the point under [focus] (view coordinates) in place while the scale
     * goes from [scale] to [newScale], moved by [pan].
     */
    fun zoomed(offset: Offset, scale: Float, newScale: Float, focus: Offset, view: Size, pan: Offset = Offset.Zero): Offset {
        val center = Offset(view.width / 2f, view.height / 2f)
        val fromCenter = focus - center
        return fromCenter - (fromCenter - offset) * (newScale / scale) + pan
    }
}

/**
 * Their shared screen: all of it on black, pinch or double tap to zoom (1…4×), drag while zoomed,
 * a single tap shows or hides the call's controls (iOS `SharedScreenView`, `SharedScreenView.swift:15-164`).
 * The texture follows the frame's own size, so zoom stays sharp up to the source pixels. TalkBack:
 * one image, "Their shared screen", whose click toggles the controls.
 */
@Composable
internal fun SharedScreen(
    track: VideoTrack,
    eglContext: () -> EglBase.Context?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTap)
    var view by remember { mutableStateOf(Size.Zero) }
    var picture by remember { mutableStateOf(Size.Zero) }
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val fitted = SharedScreenZoom.fittedSize(picture, view)

    fun reset() {
        scope.launch { scale.snapTo(1f) }
        scope.launch { offset.snapTo(Offset.Zero) }
    }

    // Only while shown: hidden (fading in or out) it takes no touch and TalkBack skips it.
    val gestures = Modifier
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val current = scale.value
                val next = (current * zoom).coerceIn(SharedScreenZoom.MIN_SCALE, SharedScreenZoom.MAX_SCALE)
                val moved = SharedScreenZoom.zoomed(offset.value, current, next, centroid, view, pan)
                val content = SharedScreenZoom.fittedSize(picture, view)
                scope.launch {
                    scale.snapTo(next)
                    offset.snapTo(SharedScreenZoom.clamp(moved, content, view, next))
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { point ->
                    val content = SharedScreenZoom.fittedSize(picture, view)
                    val target = if (scale.value > SharedScreenZoom.ZOOMED) 1f else SharedScreenZoom.DOUBLE_TAP_SCALE
                    val targetOffset = if (target == 1f) {
                        Offset.Zero
                    } else {
                        SharedScreenZoom.clamp(SharedScreenZoom.zoomed(Offset.Zero, 1f, target, point, view), content, view, target)
                    }
                    scope.launch { scale.animateTo(target, Motion.standard()) }
                    scope.launch { offset.animateTo(targetOffset, Motion.standard()) }
                },
                onTap = { currentOnTap() },
            )
        }
        .clearAndSetSemantics {
            contentDescription = "Their shared screen"
            role = Role.Image
            onClick(label = "show or hide the call controls") {
                currentOnTap()
                true
            }
        }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                val newView = Size(size.width.toFloat(), size.height.toFloat())
                // A new shape (turned): fitted whole again (:76-83).
                if (newView != view) reset()
                view = newView
            }
            .then(if (interactive) gestures else Modifier.clearAndSetSemantics {}),
        contentAlignment = Alignment.Center,
    ) {
        val widthDp = with(density) { fitted.width.toDp() }
        val heightDp = with(density) { fitted.height.toDp() }
        CallVideo(
            track = track,
            eglContext = eglContext,
            matchBufferToFrame = true,
            onFrameSize = { size: IntSize ->
                val newPicture = Size(size.width.toFloat(), size.height.toFloat())
                if (!SharedScreenZoom.keepsZoom(picture, newPicture)) reset()
                picture = newPicture
            },
            modifier = Modifier
                .size(widthDp, heightDp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.value.x
                    translationY = offset.value.y
                },
        )
    }
}
