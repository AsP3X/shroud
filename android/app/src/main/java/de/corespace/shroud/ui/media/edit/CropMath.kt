package de.corespace.shroud.ui.media.edit

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The crop editor's geometry, pure (conversation-compose-media §11.3–§11.4; iOS
 * `MediaCropEditor.swift:150-192, 285-457`). Crop rects are normalised to the rotated photo
 * (0…1 on each axis) and computed in `Double` like iOS's `CGFloat`, so the vectors match it;
 * [toRect] / [of] convert at the boundary to the `Float` [Rect] that [de.corespace.shroud.core.media.edit.MediaEdits]
 * stores. Screen-space inputs (points, frames, slop) are in px, whatever the caller measures in.
 */
object CropMath {
    /** Smallest crop window, as a fraction of each axis (`MediaCropEditor.swift:26`). */
    const val MIN_SIDE = 0.12

    /** Corner and edge grabs win within this radius, in dp (`MediaCropEditor.swift:310`). */
    const val HANDLE_SLOP_DP = 34f

    /** A drag that starts this far outside the window still moves it, in dp (`MediaCropEditor.swift:328`). */
    const val MOVE_OUTSET_DP = 12f

    /** The full photo. */
    val UNIT = NormRect(0.0, 0.0, 1.0, 1.0)

    /** What a drag grabs (`MediaCropEditor.swift:43-47`). */
    enum class Handle { Move, TopLeft, TopRight, BottomLeft, BottomRight, Top, Bottom, Leading, Trailing }

    /** Width ÷ height presets in chip order; [value] null is unconstrained (`MediaCropEditor.swift:49-76`). */
    enum class AspectPreset(val label: String, val value: Double?) {
        Free("Free", null),
        Square("Square", 1.0),
        Portrait3x4("3:4", 3.0 / 4.0),
        Landscape4x3("4:3", 4.0 / 3.0),
        Portrait9x16("9:16", 9.0 / 16.0),
        Landscape16x9("16:9", 16.0 / 9.0),
    }

    /** A rect as iOS's `CGRect` holds it: origin and size. */
    data class NormRect(val x: Double, val y: Double, val width: Double, val height: Double) {
        val minX: Double get() = x
        val minY: Double get() = y
        val maxX: Double get() = x + width
        val maxY: Double get() = y + height
        val midX: Double get() = x + width / 2
        val midY: Double get() = y + height / 2

        fun toRect(): Rect = Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
    }

    /** A screen rect in px (the photo's frame, the crop window). */
    data class Frame(val x: Float, val y: Float, val width: Float, val height: Float) {
        val maxX: Float get() = x + width
        val maxY: Float get() = y + height
        val midX: Float get() = x + width / 2f
        val midY: Float get() = y + height / 2f

        fun contains(px: Float, py: Float): Boolean = px >= x && px <= maxX && py >= y && py <= maxY
    }

    /** [MediaEdits.cropRect][de.corespace.shroud.core.media.edit.MediaEdits.cropRect] as a [NormRect]. */
    fun of(rect: Rect): NormRect =
        NormRect(rect.left.toDouble(), rect.top.toDouble(), (rect.right - rect.left).toDouble(), (rect.bottom - rect.top).toDouble())

    /**
     * The photo aspect-fitted into [canvas], centred (`imageFrame(for:in:)`, `MediaCropEditor.swift:150-161`).
     * An empty image fills the canvas.
     */
    fun imageFrame(imageWidth: Float, imageHeight: Float, canvas: Frame): Frame {
        if (imageWidth <= 0f || imageHeight <= 0f) return canvas
        val scale = min(canvas.width / imageWidth, canvas.height / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return Frame(canvas.midX - width / 2f, canvas.midY - height / 2f, width, height)
    }

    /** The crop window on screen (`cropFrame(in:)`, `MediaCropEditor.swift:163-170`). */
    fun cropFrame(crop: NormRect, imageFrame: Frame): Frame = Frame(
        (imageFrame.x + crop.x * imageFrame.width).toFloat(),
        (imageFrame.y + crop.y * imageFrame.height).toFloat(),
        (crop.width * imageFrame.width).toFloat(),
        (crop.height * imageFrame.height).toFloat(),
    )

    /**
     * What a drag starting at ([px], [py]) grabs (`handle(near:in:)`, `MediaCropEditor.swift:307-329`):
     * the nearest corner or edge midpoint within [slop], else [Handle.Move] when the point lies
     * inside the window grown by [moveOutset], else nothing. Ties go to the earlier candidate
     * (corners before edges), as Swift's `min(by:)` keeps the first.
     */
    fun handle(px: Float, py: Float, window: Frame, slop: Float, moveOutset: Float): Handle? {
        val candidates = listOf(
            Handle.TopLeft to (window.x to window.y),
            Handle.TopRight to (window.maxX to window.y),
            Handle.BottomLeft to (window.x to window.maxY),
            Handle.BottomRight to (window.maxX to window.maxY),
            Handle.Top to (window.midX to window.y),
            Handle.Bottom to (window.midX to window.maxY),
            Handle.Leading to (window.x to window.midY),
            Handle.Trailing to (window.maxX to window.midY),
        )
        val nearest = candidates
            .map { (handle, point) -> handle to hypot((point.first - px).toDouble(), (point.second - py).toDouble()) }
            .minByOrNull { it.second }
        if (nearest != null && nearest.second <= slop) return nearest.first
        val grown = Frame(window.x - moveOutset, window.y - moveOutset, window.width + 2 * moveOutset, window.height + 2 * moveOutset)
        return if (grown.contains(px, py)) Handle.Move else null
    }

    /**
     * The crop after a drag of ([dx], [dy]) px on [handle] that started at [start]
     * (`apply(translation:handle:imageFrame:)`, `MediaCropEditor.swift:331-373`). Move slides the
     * window inside the photo and ignores the ratio; corners and edges move only their own sides,
     * keep [MIN_SIDE], and with a [ratio] re-shape to it around the corner not held.
     */
    fun drag(start: NormRect, handle: Handle, dx: Float, dy: Float, imageFrame: Frame, ratio: Double?, imageAspect: Double): NormRect {
        if (imageFrame.width <= 0f || imageFrame.height <= 0f) return start
        val nx = dx.toDouble() / imageFrame.width
        val ny = dy.toDouble() / imageFrame.height
        val next = when (handle) {
            Handle.Move -> return NormRect(
                min(max(0.0, start.minX + nx), 1 - start.width),
                min(max(0.0, start.minY + ny), 1 - start.height),
                start.width,
                start.height,
            )
            Handle.TopLeft -> rect(start.minX + nx, start.minY + ny, start.maxX, start.maxY)
            Handle.TopRight -> rect(start.minX, start.minY + ny, start.maxX + nx, start.maxY)
            Handle.BottomLeft -> rect(start.minX + nx, start.minY, start.maxX, start.maxY + ny)
            Handle.BottomRight -> rect(start.minX, start.minY, start.maxX + nx, start.maxY + ny)
            Handle.Top -> rect(start.minX, start.minY + ny, start.maxX, start.maxY)
            Handle.Bottom -> rect(start.minX, start.minY, start.maxX, start.maxY + ny)
            Handle.Leading -> rect(start.minX + nx, start.minY, start.maxX, start.maxY)
            Handle.Trailing -> rect(start.minX, start.minY, start.maxX + nx, start.maxY)
        }
        return if (ratio != null) constrained(next, ratio, handle, start, imageAspect) else next
    }

    /**
     * A rect from its edges, clamped to the photo with at least [MIN_SIDE] per side
     * (`rect(minX:minY:maxX:maxY:)`, `MediaCropEditor.swift:375-381`).
     */
    fun rect(minX: Double, minY: Double, maxX: Double, maxY: Double): NormRect {
        val x0 = min(max(0.0, minX), maxX - MIN_SIDE)
        val y0 = min(max(0.0, minY), maxY - MIN_SIDE)
        val x1 = max(min(1.0, maxX), x0 + MIN_SIDE)
        val y1 = max(min(1.0, maxY), y0 + MIN_SIDE)
        return NormRect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * Re-shapes a dragged rect to the locked ratio, pinning the corner the user isn't holding
     * (`constrained(_:to:anchor:)`, `MediaCropEditor.swift:383-441`). Edges drive their own axis and
     * stay centred on the other; corners follow whichever axis moved further since [start].
     * [target] is width ÷ height of the window on screen; the rect is in photo fractions, so the
     * photo's own aspect is divided back out.
     */
    fun constrained(input: NormRect, target: Double, anchor: Handle, start: NormRect, imageAspect: Double): NormRect {
        val normalised = target / imageAspect
        val drivesHeight = when (anchor) {
            Handle.Top, Handle.Bottom -> true
            Handle.Leading, Handle.Trailing -> false
            else -> abs(input.height - start.height) * normalised > abs(input.width - start.width)
        }
        var width = if (drivesHeight) input.height * normalised else input.width
        var height = width / normalised
        if (height > 1) {
            height = 1.0
            width = height * normalised
        }
        if (width > 1) {
            width = 1.0
            height = width / normalised
        }
        var x = input.minX
        var y = input.minY
        when (anchor) {
            Handle.TopLeft -> {
                x = input.maxX - width
                y = input.maxY - height
            }
            Handle.TopRight -> y = input.maxY - height
            Handle.BottomLeft -> x = input.maxX - width
            Handle.Top -> {
                x = input.midX - width / 2
                y = input.maxY - height
            }
            Handle.Bottom -> x = input.midX - width / 2
            Handle.Leading -> {
                x = input.maxX - width
                y = input.midY - height / 2
            }
            Handle.Trailing -> y = input.midY - height / 2
            Handle.BottomRight, Handle.Move -> Unit
        }
        x = min(max(0.0, x), 1 - width)
        y = min(max(0.0, y), 1 - height)
        return NormRect(x, y, width, height)
    }

    /**
     * The largest window of [preset]'s shape, centred on the photo (`applyPreset`,
     * `MediaCropEditor.swift:443-457`); null for [AspectPreset.Free], which keeps the crop.
     */
    fun presetCrop(preset: AspectPreset, imageAspect: Double): NormRect? {
        val target = preset.value ?: return null
        val normalised = target / max(0.0001, imageAspect)
        var width = 1.0
        var height = width / normalised
        if (height > 1) {
            height = 1.0
            width = height * normalised
        }
        return NormRect((1 - width) / 2, (1 - height) / 2, width, height)
    }

    /** Rotate is counter-clockwise: one quarter less, wrapped to 0…3 (`MediaCropEditor.swift:488`). */
    fun rotatedLeft(quarters: Int): Int = ((quarters - 1) % 4 + 4) % 4

    /**
     * How far the centre of each corner bracket's 26 dp box sits inside the window's corner, in dp:
     * 13 − 0.75 − 1.5, so the 3 dp L lies flush on the 1.5 dp border (`MediaCropEditor.swift:227-239`).
     */
    const val BRACKET_INSET_DP = 10.75f
}
