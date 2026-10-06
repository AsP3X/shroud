package de.corespace.shroud.core.calls.media

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Which part of our camera goes out (docs/calls.md, "Framing and Center Stage").
 *
 * The picture is cut to the shape of the area the other side shows it in (their `media_state`
 * `view`), from the camera's full resolution, so a phone that fills its screen with our picture
 * gets every pixel it shows instead of enlarging the middle of a wide frame. With Center Stage on,
 * the cut also follows the faces in it: it pans to keep them in the middle and zooms in on them,
 * as far as the camera has pixels to spare.
 *
 * Pure geometry, in upright picture pixels (as the picture is seen, rotation applied). The web
 * (`framing.ts`) and the iPhone (`CallFraming.swift`) run the same numbers; no Android here, so
 * the JVM tests drive it.
 */

/** A picture's size in pixels. */
data class FrameSize(val width: Int, val height: Int)

/** A part of a picture, in pixels (fractional while it glides). */
data class FrameRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** A point: in picture pixels, or 0…1 on each axis of a picture (a focus). */
data class FramePoint(val x: Double, val y: Double) {
    companion object {
        /** The middle of a picture, as a focus: where our own small picture centres without faces. */
        val Middle = FramePoint(0.5, 0.5)
    }
}

/** Where an aspect-filled picture goes in its box ([coverOffset]): its top left, and its scale. */
data class CoverOffset(val x: Double, val y: Double, val scale: Double)

/** The most pixels the output's longer side gets: the camera ladder's top rung (1080p). */
const val FRAME_MAX_LONG = 1920

/** The narrowest and the widest shape we cut to (width / height): a tall phone, a wide window. */
private const val MIN_ASPECT = 0.4
private const val MAX_ASPECT = 2.5

/** A new view shape is taken only when it differs this much: a window being dragged is not. */
private const val SHAPE_CHANGE = 0.03

/** How much the faces fill the cut: their height a third of it (head and shoulders). */
private const val FACE_HEIGHT_SHARE = 0.3

/** Their width at most this much of it, for several people side by side. */
private const val FACE_WIDTH_SHARE = 0.6

/** Where the faces' middle sits from the top of the cut. */
private const val FACE_FROM_TOP = 0.42

/** The cut is never enlarged more than this into the output (so it never turns soft). */
private const val MAX_UPSCALE = 1.25

/** Nor zoomed in more than this on the whole picture. */
private const val MAX_ZOOM = 2.5

/** A new target closer than this to the last one (share of the cut's size) is not followed. */
private const val DEADBAND = 0.06

/** How fast the cut follows (seconds to cover about two thirds of the way): pan, then zoom. */
private const val PAN_SECONDS = 0.35
private const val ZOOM_SECONDS = 0.6

/** No face for this long: the cut goes back to the whole picture. */
private const val LOST_MS = 1_500L

/** JavaScript's `Math.round` (halves up), so every client lands on the same pixel. */
private fun roundHalfUp(value: Double): Double = floor(value + 0.5)

private fun even(value: Double): Int = max(2, (roundHalfUp(value / 2) * 2).toInt())

private fun clampAspect(aspect: Double): Double = min(MAX_ASPECT, max(MIN_ASPECT, aspect))

/** The largest rectangle of [aspect] inside [size], in its middle. */
fun largestInside(size: FrameSize, aspect: Double): FrameRect {
    var width = size.width.toDouble()
    var height = width / aspect
    if (height > size.height) {
        height = size.height.toDouble()
        width = height * aspect
    }
    return FrameRect((size.width - width) / 2, (size.height - height) / 2, width, height)
}

/**
 * The output picture: the shape of their [view] (or the camera's own, when they did not say), as
 * large as the camera can fill without enlarging, at most 1080p on its longer side. Even sides.
 */
fun outputSize(capture: FrameSize, view: FrameSize?): FrameSize {
    val raw = if (view != null && view.width > 0 && view.height > 0) {
        view.width.toDouble() / view.height
    } else {
        capture.width.toDouble() / capture.height
    }
    val aspect = clampAspect(raw)
    val base = largestInside(capture, aspect)
    val long = min(FRAME_MAX_LONG.toDouble(), max(base.width, base.height))
    return if (aspect >= 1) FrameSize(even(long), even(long / aspect)) else FrameSize(even(long * aspect), even(long))
}

/** Whether a newly reported view shape is far enough from the one in use to cut to it. */
fun shapeChanged(current: FrameSize?, next: FrameSize?): Boolean {
    if (current == null || next == null) return current != next
    val a = current.width.toDouble() / current.height
    val b = next.width.toDouble() / next.height
    return abs(a - b) / a > SHAPE_CHANGE
}

/** The smallest box around every face (each in upright pixels), or null for none. */
fun faceUnion(faces: List<FrameRect>): FrameRect? {
    if (faces.isEmpty()) return null
    var left = Double.POSITIVE_INFINITY
    var top = Double.POSITIVE_INFINITY
    var right = Double.NEGATIVE_INFINITY
    var bottom = Double.NEGATIVE_INFINITY
    for (face in faces) {
        left = min(left, face.x)
        top = min(top, face.y)
        right = max(right, face.x + face.width)
        bottom = max(bottom, face.y + face.height)
    }
    return FrameRect(left, top, right - left, bottom - top)
}

/**
 * Where the cut should be: the whole picture in the output's shape, or, with faces, a cut around
 * them (head and shoulders, the faces a little above the middle), no tighter than the output's
 * pixels allow, and always inside the picture.
 */
fun targetCrop(capture: FrameSize, output: FrameSize, faces: FrameRect?): FrameRect {
    val aspect = output.width.toDouble() / output.height
    val base = largestInside(capture, aspect)
    if (faces == null) return base
    val fromHeight = faces.height / FACE_HEIGHT_SHARE
    val fromWidth = faces.width / FACE_WIDTH_SHARE / aspect
    val least = max(output.height / MAX_UPSCALE, base.height / MAX_ZOOM)
    val height = min(base.height, maxOf(fromHeight, fromWidth, least))
    val width = height * aspect
    val centerX = faces.x + faces.width / 2
    val centerY = faces.y + faces.height / 2
    val x = min(capture.width - width, max(0.0, centerX - width / 2))
    val y = min(capture.height - height, max(0.0, centerY - height * FACE_FROM_TOP))
    return FrameRect(x, y, width, height)
}

private fun far(a: FrameRect, b: FrameRect): Boolean {
    val size = max(a.width, a.height)
    val moved = hypot(a.x + a.width / 2 - (b.x + b.width / 2), a.y + a.height / 2 - (b.y + b.height / 2))
    return moved > size * DEADBAND || abs(a.height - b.height) > a.height * DEADBAND
}

/** One step of [from] toward [to] after [seconds], covering about two thirds of it every [tau]. */
private fun ease(from: Double, to: Double, seconds: Double, tau: Double): Double =
    from + (to - from) * (1 - exp(-seconds / tau))

/**
 * Where to put a picture of [picture]'s size, filled into a [box] (aspect fill: scaled to cover it
 * and cropped), so that [focus] (a point in the picture, 0…1 on each axis) lands as near the box's
 * middle as the picture allows: the offset of the scaled picture's top left inside the box, never
 * past an edge. Our own small picture uses it to keep the faces in view (docs/calls.md, "Framing
 * and Center Stage"); a box and a picture of one shape need no offset.
 */
fun coverOffset(box: FrameSize, picture: FrameSize, focus: FramePoint): CoverOffset {
    if (box.width <= 0 || box.height <= 0 || picture.width <= 0 || picture.height <= 0) return CoverOffset(0.0, 0.0, 1.0)
    val scale = max(box.width.toDouble() / picture.width, box.height.toDouble() / picture.height)
    fun axis(boxSide: Int, pictureSide: Int, at: Double): Double {
        val shown = pictureSide * scale
        if (shown - boxSide < 0.5) return (boxSide - shown) / 2
        return min(0.0, max(boxSide - shown, boxSide / 2.0 - at * shown))
    }
    return CoverOffset(axis(box.width, picture.width, focus.x), axis(box.height, picture.height, focus.y), scale)
}

/**
 * The cut over time, for one camera: [faces] gives it what the detector saw, [next] where the cut
 * is for a frame. It glides (pans quicker than it zooms), ignores small jitter, holds on a face
 * that is briefly lost, and goes back to the whole picture once none has been seen for a while.
 *
 * Not thread-safe: the camera's thread owns it (`CallCamera`), and the detector's faces reach it
 * through a hand-off there.
 */
class Framer {
    private var capture = FrameSize(0, 0)
    private var output = FrameSize(0, 0)
    private var current: FrameRect? = null
    private var target: FrameRect? = null
    private var lastFace: Long? = null
    private var lastStep: Long? = null
    private var follow = true

    /** The faces' middle the cut aims at (upright pixels), or null: none to follow. */
    private var faceTarget: FramePoint? = null

    /** The point our own picture centres on, gliding like the cut: the faces' middle, else the cut's. */
    private var faceAt: FramePoint? = null

    /** The picture coming in and the output going out; a change starts again from the whole picture. */
    fun configure(capture: FrameSize, output: FrameSize, follow: Boolean) {
        val same = capture == this.capture && output == this.output
        this.follow = follow
        if (same && current != null) {
            if (!follow) {
                target = targetCrop(capture, output, null)
                faceTarget = null
            }
            return
        }
        this.capture = capture
        this.output = output
        val whole = targetCrop(capture, output, null)
        current = whole
        target = whole
        faceTarget = null
        faceAt = null
        lastStep = null
    }

    /**
     * Another camera (or the same one started again): everything about the last picture is
     * forgotten, the cut, where it was heading, the last face and our own picture's focus; the next [configure] starts from
     * the whole picture.
     */
    fun reset() {
        capture = FrameSize(0, 0)
        output = FrameSize(0, 0)
        current = null
        target = null
        lastFace = null
        lastStep = null
        faceTarget = null
        faceAt = null
    }

    /** What the detector saw in the picture at [now] (ms): faces in upright pixels, maybe none. */
    fun faces(faces: List<FrameRect>, now: Long) {
        if (current == null) return
        val union = if (follow) faceUnion(faces) else null
        if (union != null) {
            lastFace = now
        } else {
            val seen = lastFace
            if (seen != null && now - seen < LOST_MS) return
        }
        val next = targetCrop(capture, output, union)
        val held = target
        if (held == null || far(held, next)) {
            target = next
            faceTarget = union?.let { FramePoint(it.x + it.width / 2, it.y + it.height / 2) }
        }
    }

    /** Where the cut is for a frame at [now] (ms). */
    fun next(now: Long): FrameRect {
        val c = current
        val t = target
        if (c == null || t == null) return FrameRect(0.0, 0.0, capture.width.toDouble(), capture.height.toDouble())
        val last = lastStep
        val seconds = if (last == null) 0.0 else max(0.0, min(0.25, (now - last) / 1000.0))
        lastStep = now
        val height = ease(c.height, t.height, seconds, ZOOM_SECONDS)
        val width = height * (output.width.toDouble() / output.height)
        val centerX = ease(c.x + c.width / 2, t.x + t.width / 2, seconds, PAN_SECONDS)
        val centerY = ease(c.y + c.height / 2, t.y + t.height / 2, seconds, PAN_SECONDS)
        val x = min(capture.width - width, max(0.0, centerX - width / 2))
        val y = min(capture.height - height, max(0.0, centerY - height / 2))
        val moved = FrameRect(x, y, width, height)
        current = moved
        // Our own picture's focus glides with the pan: toward the faces' middle, or without
        // faces to follow toward the cut's own, which reads as the picture's middle in [focus].
        val aim = faceTarget ?: FramePoint(x + width / 2, y + height / 2)
        val at = faceAt ?: aim
        faceAt = FramePoint(ease(at.x, aim.x, seconds, PAN_SECONDS), ease(at.y, aim.y, seconds, PAN_SECONDS))
        return moved
    }

    /**
     * Where the faces are in the cut that last went out (0…1 on each axis), for our own small
     * picture to centre on; the middle when there are none to follow.
     */
    fun focus(): FramePoint {
        val cut = current
        val at = faceAt
        if (cut == null || at == null || cut.width <= 0 || cut.height <= 0) return FramePoint.Middle
        return FramePoint(((at.x - cut.x) / cut.width).coerceIn(0.0, 1.0), ((at.y - cut.y) / cut.height).coerceIn(0.0, 1.0))
    }
}

/**
 * The cut for one camera's frames, across camera switches (`CallCamera`, on the camera's thread).
 * Each frame carries the camera's generation, bumped for each start and each switch: front and
 * back cameras give pictures of the same size, so without it the zoomed cut and the faces found
 * in the last camera's picture would carry over into the next one's. A new generation starts the
 * [Framer] again from the whole picture, and faces found in another generation or another picture
 * are dropped.
 */
class CameraCut {
    private val framer = Framer()
    private var generation: Int? = null
    private var capture = FrameSize(0, 0)

    /**
     * A frame of [capture] from the camera's [generation], going out as [output]; true when it is
     * the first of a new generation (the caller forgets its detector's results too).
     */
    fun frame(generation: Int, capture: FrameSize, output: FrameSize, follow: Boolean): Boolean {
        val fresh = generation != this.generation
        if (fresh) {
            this.generation = generation
            framer.reset()
        }
        this.capture = capture
        framer.configure(capture, output, follow)
        return fresh
    }

    /** Faces the detector found in [capture] of [generation] at [now] (ms); false when dropped as stale. */
    fun faces(faces: List<FrameRect>, capture: FrameSize, generation: Int, now: Long): Boolean {
        if (generation != this.generation || capture != this.capture) return false
        framer.faces(faces, now)
        return true
    }

    /** Where the cut is for the frame at [now] (ms). */
    fun next(now: Long): FrameRect = framer.next(now)

    /** Where the faces are in the last cut (0…1), for our own small picture ([Framer.focus]). */
    fun focus(): FramePoint = framer.focus()
}

/**
 * [upright], a part of the upright picture, in the camera buffer's own pixels. [rotation] is
 * WebRTC's `VideoFrame.rotation`: how far the buffer ([bufferWidth] × [bufferHeight]) turns
 * clockwise to stand upright.
 */
fun uprightToBuffer(upright: FrameRect, rotation: Int, bufferWidth: Int, bufferHeight: Int): FrameRect {
    val (ux, uy, uw, uh) = upright
    return when (normalRotation(rotation)) {
        90 -> FrameRect(uy, bufferHeight - (ux + uw), uh, uw)
        180 -> FrameRect(bufferWidth - (ux + uw), bufferHeight - (uy + uh), uw, uh)
        270 -> FrameRect(bufferWidth - (uy + uh), ux, uh, uw)
        else -> upright
    }
}

/** A buffer's cut in whole, even pixels, inside the buffer: `cropAndScale` and chroma need even. */
data class BufferCrop(val x: Int, val y: Int, val width: Int, val height: Int)

/** [rect] (buffer pixels) rounded to even sides and corner, no larger than and inside the buffer. */
fun evenCrop(rect: FrameRect, bufferWidth: Int, bufferHeight: Int): BufferCrop {
    val maxWidth = bufferWidth - bufferWidth % 2
    val maxHeight = bufferHeight - bufferHeight % 2
    val width = (roundHalfUp(rect.width / 2) * 2).toInt().coerceIn(2, max(2, maxWidth))
    val height = (roundHalfUp(rect.height / 2) * 2).toInt().coerceIn(2, max(2, maxHeight))
    val x = (roundHalfUp(rect.x / 2) * 2).toInt().coerceIn(0, max(0, bufferWidth - width))
    val y = (roundHalfUp(rect.y / 2) * 2).toInt().coerceIn(0, max(0, bufferHeight - height))
    return BufferCrop(x - x % 2, y - y % 2, width, height)
}

/** [size] (upright) in the buffer's orientation: sides swapped for a quarter turn. */
fun bufferOriented(size: FrameSize, rotation: Int): FrameSize =
    if (normalRotation(rotation) % 180 == 90) FrameSize(size.height, size.width) else size

/**
 * The luma plane of a small camera copy ([bufferWidth] × [bufferHeight], rows [stride] bytes
 * apart) as an upright grey `RGB_565` picture, row by row, for the platform face detector, which
 * only finds upright faces. Each upright pixel comes from the buffer pixel that turning the buffer
 * [rotation] degrees clockwise puts there.
 */
fun uprightGray565(luma: ByteBuffer, stride: Int, bufferWidth: Int, bufferHeight: Int, rotation: Int): ShortArray {
    val turn = normalRotation(rotation)
    val quarter = turn % 180 == 90
    val width = if (quarter) bufferHeight else bufferWidth
    val height = if (quarter) bufferWidth else bufferHeight
    val out = ShortArray(width * height)
    val base = luma.position()
    var i = 0
    for (uy in 0 until height) {
        for (ux in 0 until width) {
            val bx: Int
            val by: Int
            when (turn) {
                90 -> {
                    bx = uy
                    by = bufferHeight - 1 - ux
                }
                180 -> {
                    bx = bufferWidth - 1 - ux
                    by = bufferHeight - 1 - uy
                }
                270 -> {
                    bx = bufferWidth - 1 - uy
                    by = ux
                }
                else -> {
                    bx = ux
                    by = uy
                }
            }
            val y = luma.get(base + by * stride + bx).toInt() and 0xff
            out[i++] = (((y shr 3) shl 11) or ((y shr 2) shl 5) or (y shr 3)).toShort()
        }
    }
    return out
}

private fun normalRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

/**
 * A face the platform detector found (`android.media.FaceDetector.Face`: the point between the
 * eyes and their distance), as a box round the face in the same pixels: centred a quarter of the
 * eye distance below the eyes, 2.2 eye distances wide and 2.6 high. [scaleX] and [scaleY] take
 * it from the detector's small picture to the full upright one.
 */
fun faceBoxFromEyes(midX: Double, midY: Double, eyesDistance: Double, scaleX: Double, scaleY: Double): FrameRect {
    val centerX = midX
    val centerY = midY + 0.25 * eyesDistance
    val width = 2.2 * eyesDistance
    val height = 2.6 * eyesDistance
    return FrameRect((centerX - width / 2) * scaleX, (centerY - height / 2) * scaleY, width * scaleX, height * scaleY)
}
