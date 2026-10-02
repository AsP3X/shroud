package de.corespace.shroud.ui.media.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What one finger-down on a photo turned into. */
private enum class PhotoGesture { Undecided, Pinch, Pan, Dismiss, Pager }

/**
 * A pinch / pan / double-tap zoomable photo (`ZoomableImageView` + `ZoomImageScrollView`,
 * `ios/shroud/ShroudUI/Components/ZoomableImageView.swift:51-309`; conversation-compose-media §18.4),
 * hand-rolled because zoom libraries cannot open at a filled base scale while still allowing a
 * pinch out to the whole photo. The maths is [ZoomMath].
 *
 * Gesture split, as on iOS:
 * - at the opening scale a sideways drag is left to the pager (nothing consumed here);
 * - at the opening scale a vertical-dominant drag is the dismiss drag: reported through
 *   [onDismissDrag] / [onDismissEnd] (px, px / s), never applied to the photo;
 * - zoomed in, every drag pans the photo (with a fling) and dismiss is off;
 * - two fingers pinch about their centroid at any time, rubber-banding past the limits and
 *   settling back with [Motion.standard];
 * - a double tap zooms toward the tapped point, or puts the photo back; a single tap (after the
 *   double-tap timeout) is [onSingleTap].
 *
 * [isActive] false (a page swiped away) snaps back to the opening scale without animation.
 * [onZoomChange] reports transitions only.
 */
@Composable
internal fun ZoomableImage(
    image: ImageBitmap,
    isActive: Boolean,
    onSingleTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onDismissDrag: (Offset) -> Unit,
    onDismissEnd: (Offset, Velocity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val reduceMotion = ShroudTheme.reduceMotion
    val scope = rememberCoroutineScope()
    var viewport by remember { mutableStateOf(Size.Zero) }
    val fitted = remember(image, viewport) { ZoomMath.fittedSize(image.width.toFloat(), image.height.toFloat(), viewport) }
    val base = remember(image, viewport) { ZoomMath.baseScale(image.width.toFloat(), image.height.toFloat(), viewport) }
    val maxScale = remember(base, image, fitted) { ZoomMath.maxScale(base, image.width.toFloat(), fitted.width) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var animation by remember { mutableStateOf<Job?>(null) }

    val currentOnTap by rememberUpdatedState(onSingleTap)
    val currentOnZoom by rememberUpdatedState(onZoomChange)
    val currentOnDrag by rememberUpdatedState(onDismissDrag)
    val currentOnDragEnd by rememberUpdatedState(onDismissEnd)

    // A new image or viewport lays the photo out afresh at its opening scale (`resetLayout`, `:249-275`).
    LaunchedEffect(base, fitted) {
        animation?.cancel()
        scale = base
        offset = Offset.Zero
    }
    // Swiping a page away throws its zoom away too (`updateUIView`, `:101-105`).
    LaunchedEffect(isActive, base) {
        if (!isActive && !ZoomMath.same(scale, base)) {
            animation?.cancel()
            scale = base
            offset = Offset.Zero
        }
    }
    LaunchedEffect(base) {
        var wasZoomed = false
        snapshotFlow { ZoomMath.isZoomedIn(scale, base) }.distinctUntilChanged().collect { zoomed ->
            if (zoomed != wasZoomed) {
                wasZoomed = zoomed
                currentOnZoom(zoomed)
            }
        }
    }

    fun animateTo(targetScale: Float, targetOffset: Offset) {
        animation?.cancel()
        val fromScale = scale
        val fromOffset = offset
        animation = scope.launch {
            animate(0f, 1f, animationSpec = Motion.respecting(reduceMotion, Motion.standard())) { t, _ ->
                scale = lerp(fromScale, targetScale, t)
                offset = lerp(fromOffset, targetOffset, t)
            }
        }
    }

    fun settle() {
        val target = scale.coerceIn(ZoomMath.MIN_SCALE, maxScale)
        animateTo(target, ZoomMath.clampOffset(offset, fitted, viewport, target))
    }

    fun fling(velocity: Velocity) {
        val clamped = ZoomMath.clampOffset(offset, fitted, viewport, scale)
        if (clamped != offset) {
            animateTo(scale, clamped)
            return
        }
        animation?.cancel()
        val limit = ZoomMath.maxOffset(fitted, viewport, scale)
        animation = scope.launch {
            val running = Animatable(offset, Offset.VectorConverter)
            running.updateBounds(Offset(-limit.x, -limit.y), Offset(limit.x, limit.y))
            running.animateDecay(Offset(velocity.x, velocity.y), exponentialDecay()) { offset = value }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it.toSize() }
            .pointerInput(fitted, base, maxScale) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        val centre = Offset(viewport.width / 2f, viewport.height / 2f)
                        if (ZoomMath.doubleTapResets(scale, base)) {
                            animateTo(base, Offset.Zero)
                        } else {
                            val target = ZoomMath.doubleTapScale(fitted, viewport, base, maxScale)
                            val centred = ZoomMath.offsetCentring(offset, scale, target, position - centre)
                            animateTo(target, ZoomMath.clampOffset(centred, fitted, viewport, target))
                        }
                    },
                    onTap = { currentOnTap() },
                )
            }
            .pointerInput(fitted, base, maxScale) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val centre = Offset(viewport.width / 2f, viewport.height / 2f)
                    var mode = PhotoGesture.Undecided
                    var travel = Offset.Zero
                    // The pan before the rubber band, so the give past an edge does not compound.
                    var rawPan = Offset.Zero
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (mode == PhotoGesture.Pager) continue
                        if (mode == PhotoGesture.Undecided && event.changes.any { it.isConsumed }) {
                            // Something above took the drag first (the pager): leave it alone.
                            mode = PhotoGesture.Pager
                            continue
                        }
                        val pan = event.calculatePan()
                        if (pressed >= 2 && mode != PhotoGesture.Dismiss) {
                            if (mode != PhotoGesture.Pinch) animation?.cancel()
                            mode = PhotoGesture.Pinch
                        }
                        event.changes.lastOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        when (mode) {
                            PhotoGesture.Undecided -> {
                                travel += pan
                                if (travel.getDistance() > slop) {
                                    mode = when {
                                        ZoomMath.isZoomedIn(scale, base) -> PhotoGesture.Pan
                                        abs(travel.y) > abs(travel.x) -> PhotoGesture.Dismiss
                                        else -> PhotoGesture.Pager
                                    }
                                    when (mode) {
                                        PhotoGesture.Pan -> {
                                            animation?.cancel()
                                            rawPan = offset + travel
                                            offset = rubberPan(rawPan, fitted, viewport, scale)
                                            event.changes.forEach { it.consume() }
                                        }
                                        PhotoGesture.Dismiss -> {
                                            currentOnDrag(travel)
                                            event.changes.forEach { it.consume() }
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                            PhotoGesture.Pinch -> {
                                val zoom = event.calculateZoom()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val raw = scale * zoom
                                val next = ZoomMath.rubberBand(raw, ZoomMath.MIN_SCALE, maxScale)
                                val anchor = if (centroid.isSpecified) centroid - centre else Offset.Zero
                                offset = ZoomMath.offsetAfterZoom(offset, scale, next, anchor) + pan
                                scale = next
                                event.changes.forEach { it.consume() }
                            }
                            PhotoGesture.Pan -> {
                                rawPan += pan
                                offset = rubberPan(rawPan, fitted, viewport, scale)
                                event.changes.forEach { it.consume() }
                            }
                            PhotoGesture.Dismiss -> {
                                travel += pan
                                currentOnDrag(travel)
                                event.changes.forEach { it.consume() }
                            }
                            PhotoGesture.Pager -> Unit
                        }
                    }
                    when (mode) {
                        PhotoGesture.Pinch -> settle()
                        PhotoGesture.Pan -> fling(tracker.calculateVelocity())
                        PhotoGesture.Dismiss -> currentOnDragEnd(travel, tracker.calculateVelocity())
                        else -> Unit
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (fitted.width > 0f && fitted.height > 0f) {
            val size = with(density) { DpSize(fitted.width.toDp(), fitted.height.toDp()) }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

/** A pan past the content's edge gives way by half (rubber band); the fling settles it back. */
private fun rubberPan(offset: Offset, fitted: Size, viewport: Size, scale: Float): Offset {
    val limit = ZoomMath.maxOffset(fitted, viewport, scale)
    fun axis(value: Float, max: Float): Float = when {
        value > max -> max + (value - max) * 0.5f
        value < -max -> -max + (value + max) * 0.5f
        else -> value
    }
    return Offset(axis(offset.x, limit.x), axis(offset.y, limit.y))
}
