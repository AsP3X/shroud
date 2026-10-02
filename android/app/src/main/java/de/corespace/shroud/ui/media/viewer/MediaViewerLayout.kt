package de.corespace.shroud.ui.media.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared rules for how a photo is presented full screen, so the zoom view and the chrome agree
 * (`MediaViewerLayout`, `ios/shroud/ShroudUI/Components/ZoomableImageView.swift:5-38`;
 * conversation-compose-media §18.4). Pure; every size is in one unit (px or dp), the caller's.
 *
 * Human: a screenshot (≈ 1.00 over a phone screen) and a 9:16 photo (≈ 1.22 on an iPhone, ≈ 1.249
 * on a 412 × 915 Pixel) open edge to edge, the way they do in Photos; a 2:3 (≈ 1.45) or 3:4 photo
 * is a different shape and opens whole.
 */
object MediaViewerLayout {
    /** How much overflow still counts as "made for a screen this shape" (`ZoomableImageView.swift:11`). */
    const val FILL_THRESHOLD = 1.25f

    /** The box an image of [aspect] (width / height) occupies when fitted into [container] (`:14-18`). */
    fun fittedSize(aspect: Float, container: Size): Size {
        if (!(aspect > 0f) || !(container.width > 0f) || !(container.height > 0f)) return container
        val height = min(container.height, container.width / aspect)
        return Size(height * aspect, height)
    }

    /** Scale from the fitted box to one that covers [container]; 1 on bad input (`:21-25`). */
    fun fillScale(aspect: Float, container: Size): Float {
        val fitted = fittedSize(aspect, container)
        if (!(fitted.width > 0f) || !(fitted.height > 0f)) return 1f
        return max(container.width / fitted.width, container.height / fitted.height)
    }

    /** The scale a photo opens at: edge to edge for screen-shaped verticals, fitted otherwise (`:28-32`). */
    fun presentationScale(aspect: Float, container: Size): Float {
        if (!(aspect > 0f) || aspect >= 1f) return 1f // landscape and square always fit
        val fill = fillScale(aspect, container)
        return if (fill <= FILL_THRESHOLD) fill else 1f
    }

    /** True when the photo opens covering the whole container (`:35-37`). */
    fun opensFullBleed(aspect: Float, container: Size): Boolean = presentationScale(aspect, container) > 1.001f

    /**
     * True when a photo of [aspect] runs under a bar [barHeight] tall (inset included), so the bar
     * needs a scrim instead of solid black (`MediaImageViewerOverlay.swift:286-294`).
     */
    fun reachesChrome(aspect: Float, container: Size, barHeight: Float): Boolean {
        if (opensFullBleed(aspect, container)) return true
        val fitted = fittedSize(aspect, container)
        val margin = (container.height - fitted.height) / 2f
        return margin < barHeight
    }
}

/**
 * The zoom model of one photo page (`ZoomImageScrollView`, `ZoomableImageView.swift:207-309`) in
 * px: the image is laid out at its fitted size, centred, and drawn scaled by `scale` about its
 * centre and moved by `offset` (the centre's distance from the viewport's centre). Pure.
 */
internal object ZoomMath {
    /** iOS rounds the fitted size (`:257-260`). */
    fun fittedSize(imageWidth: Float, imageHeight: Float, viewport: Size): Size {
        if (!(imageWidth > 0f) || !(imageHeight > 0f) || !(viewport.width > 0f) || !(viewport.height > 0f)) return Size.Zero
        val fit = min(viewport.width / imageWidth, viewport.height / imageHeight)
        return Size((imageWidth * fit).roundToInt().toFloat(), (imageHeight * fit).roundToInt().toFloat())
    }

    /** Opening scale: [MediaViewerLayout.presentationScale] of the image's own aspect (`:264-267`). */
    fun baseScale(imageWidth: Float, imageHeight: Float, viewport: Size): Float =
        if (imageWidth > 0f && imageHeight > 0f) MediaViewerLayout.presentationScale(imageWidth / imageHeight, viewport) else 1f

    /** Pinching out always reveals the whole photo (`:268-270`). */
    const val MIN_SCALE = 1f

    /** Native pixels, at least 3 × the opening scale, never past 8 (`:271-273`). */
    fun maxScale(baseScale: Float, imagePixelWidth: Float, fittedWidth: Float): Float {
        val native = imagePixelWidth / max(fittedWidth, 1f)
        return min(max(3f * baseScale, native), 8f)
    }

    /** True while magnified past the opening scale (`:240`). */
    fun isZoomedIn(scale: Float, baseScale: Float): Boolean = scale > baseScale + 0.01f

    /** True when a double tap should put the photo back: zoomed in, or pinched out below the opening scale (`:127-131`). */
    fun doubleTapResets(scale: Float, baseScale: Float): Boolean = isZoomedIn(scale, baseScale) || scale < baseScale - 0.01f

    /** Fills the screen on the first double tap, then a fixed step past that (`:243-247`). */
    fun doubleTapScale(fitted: Size, viewport: Size, baseScale: Float, maxScale: Float): Float {
        if (!(fitted.width > 0f) || !(fitted.height > 0f)) return 2f
        val fill = max(viewport.width / fitted.width, viewport.height / fitted.height)
        return min(max(baseScale * 2f, fill * 1.4f), maxScale)
    }

    /** How far the centre may move at [scale]: none while the content is narrower than the viewport (centred, `:302-308`). */
    fun maxOffset(fitted: Size, viewport: Size, scale: Float): Offset = Offset(
        max(0f, (fitted.width * scale - viewport.width) / 2f),
        max(0f, (fitted.height * scale - viewport.height) / 2f),
    )

    fun clampOffset(offset: Offset, fitted: Size, viewport: Size, scale: Float): Offset {
        val limit = maxOffset(fitted, viewport, scale)
        return Offset(offset.x.coerceIn(-limit.x, limit.x), offset.y.coerceIn(-limit.y, limit.y))
    }

    /**
     * The offset after scaling from [scale] to [newScale] about [anchor] (relative to the viewport
     * centre): the content point under the anchor stays under it, as `UIScrollView` pinches.
     */
    fun offsetAfterZoom(offset: Offset, scale: Float, newScale: Float, anchor: Offset): Offset {
        if (!(scale > 0f)) return offset
        val ratio = newScale / scale
        return anchor - (anchor - offset) * ratio
    }

    /**
     * The offset that puts the content point under [tap] (relative to the viewport centre) in the
     * middle of the viewport at [newScale] — `zoom(to: rect)` around the tapped point (`:133-147`).
     */
    fun offsetCentring(offset: Offset, scale: Float, newScale: Float, tap: Offset): Offset {
        if (!(scale > 0f)) return offset
        val point = (tap - offset) / scale
        return -point * newScale
    }

    /** A pinch past the limits gives way less and less (rubber band), settling back on release. */
    fun rubberBand(scale: Float, min: Float, max: Float): Float = when {
        scale > max -> max + (scale - max) * RUBBER
        scale < min -> min - (min - scale) * RUBBER
        else -> scale
    }

    /** Whether two scales are the same for layout purposes. */
    fun same(a: Float, b: Float): Boolean = abs(a - b) <= 0.001f

    private const val RUBBER = 0.35f
}

/**
 * The drag-to-dismiss of the photo viewer (`MediaImageViewerOverlay.swift:539-583`), in dp. Pure.
 */
internal object ViewerDismiss {
    /** Past this the photo lets go (`:557`). */
    const val DISTANCE = 110f

    /** A flick this fast (dp / s) lets go too (`:558`). */
    const val VELOCITY = 900f

    /** The backdrop dims as the photo travels, never under 25 % (`:315`). */
    fun dimOpacity(dy: Float): Float = max(0.25f, 1f - abs(dy) / 420f)

    /** Up to 12 % smaller at 500 dp; none under Reduce Motion (`:539-543`). */
    fun dragScale(dy: Float, reduceMotion: Boolean): Float =
        if (reduceMotion) 1f else 1f - min(1f, abs(dy) / 500f) * 0.12f

    /** The chrome lets go faster than the photo (`:545-547`). */
    fun chromeOpacity(dy: Float): Float = max(0f, 1f - abs(dy) / 180f)

    /** Whether a drag ending at [dy] with vertical speed [vy] closes the viewer (`:555-559`). */
    fun shouldDismiss(dy: Float, vy: Float): Boolean = abs(dy) > DISTANCE || abs(vy) > VELOCITY

    /** Where the photo flies on close: 40 dp from rest, else 1.6 × as far as the finger took it (`:565-570`). */
    fun exitOffset(dy: Float, direction: Float): Float = if (dy == 0f) 40f * direction else dy * 1.6f

    /** The drag offset predictive back drives: up to [DISTANCE] dp down (§18.4). */
    fun backOffset(progress: Float): Float = progress.coerceIn(0f, 1f) * DISTANCE
}
