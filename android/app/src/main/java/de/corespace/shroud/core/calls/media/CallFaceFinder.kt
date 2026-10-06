package de.corespace.shroud.core.calls.media

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import org.webrtc.VideoFrame
import java.nio.ShortBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Center Stage's face detector on Android (docs/calls.md, "Framing and Center Stage"): the
 * platform's `android.media.FaceDetector`, on the device, on its own thread. Nothing about the
 * faces leaves this class except boxes for the [Framer].
 *
 * The camera's thread offers a frame at most every [GAP_MS] while no detection runs ([offer]): a
 * small copy of it ([SMALL_WIDTH] pixels wide in the buffer's own orientation, its shape kept,
 * scaled on the GPU and read back as I420) goes to the detector's thread, which turns its luma upright ([uprightGray565]),
 * finds the faces and leaves their boxes, in upright full-frame pixels, for the camera's thread to
 * pick up ([take]). Each detection carries the camera's generation it was offered in, so the
 * camera drops one that a camera switch overtook; [reset] forgets what was found and when.
 */
internal class CallFaceFinder {
    /**
     * What the detector saw, in which picture and in which of the camera's generations (`CallCamera`
     * bumps it for each start and camera switch); replaced by each detection, taken once.
     */
    class Found(val capture: FrameSize, val faces: List<FrameRect>, val generation: Int)

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "shroud-faces").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val busy = AtomicBoolean(false)
    private val found = AtomicReference<Found?>(null)

    /** When the last detection started (ms), null before the first. Camera thread only. */
    private var lastOffer: Long? = null

    /** Detector thread only: one detector per picture size (it allocates its tables up front). */
    private var detector: FaceDetector? = null
    private var detectorWidth = 0
    private var detectorHeight = 0

    @Volatile private var closed = false

    /**
     * On the camera's thread: start a detection on [frame] (upright [capture], the camera's
     * [generation]) when none runs and the last one started [GAP_MS] ago or more. Never blocks for
     * the detection itself.
     */
    fun offer(frame: VideoFrame, capture: FrameSize, generation: Int, nowMs: Long) {
        if (closed || busy.get()) return
        lastOffer?.let { last -> if (nowMs - last < GAP_MS) return }
        val buffer = frame.buffer
        val bufferWidth = buffer.width
        val bufferHeight = buffer.height
        if (bufferWidth < 2 || bufferHeight < 2) return
        val smallWidth = evenAtLeast2(minOf(SMALL_WIDTH, bufferWidth).toDouble())
        val smallHeight = evenAtLeast2(smallWidth.toDouble() * bufferHeight / bufferWidth)
        lastOffer = nowMs
        busy.set(true)
        val i420 = try {
            val small = buffer.cropAndScale(0, 0, bufferWidth, bufferHeight, smallWidth, smallHeight)
            try {
                small.toI420()
            } finally {
                small.release()
            }
        } catch (_: RuntimeException) {
            null
        }
        if (i420 == null) {
            busy.set(false)
            return
        }
        val rotation = frame.rotation
        try {
            worker.execute { detect(i420, rotation, capture, generation) }
        } catch (_: RejectedExecutionException) {
            i420.release()
            busy.set(false)
        }
    }

    /** On the camera's thread: the last detection's faces, once. */
    fun take(): Found? = found.getAndSet(null)

    /**
     * On the camera's thread, for another camera (or a new start): what was found is dropped and
     * the next frame may be offered at once. A detection still running finishes under its old
     * generation, which the camera ignores.
     */
    fun reset() {
        found.set(null)
        lastOffer = null
    }

    /** No more detections; one running finishes and is dropped. */
    fun close() {
        closed = true
        found.set(null)
        worker.shutdown()
    }

    private fun detect(i420: VideoFrame.I420Buffer, rotation: Int, capture: FrameSize, generation: Int) {
        try {
            val width = i420.width
            val height = i420.height
            val pixels = try {
                uprightGray565(i420.dataY, i420.strideY, width, height, rotation)
            } finally {
                i420.release()
            }
            if (closed) return
            val upright = bufferOriented(FrameSize(width, height), rotation)
            val faces = findFaces(pixels, upright)
            val scaleX = capture.width.toDouble() / upright.width
            val scaleY = capture.height.toDouble() / upright.height
            val boxes = faces.map { (mid, eyes) -> faceBoxFromEyes(mid.x.toDouble(), mid.y.toDouble(), eyes, scaleX, scaleY) }
            if (!closed) found.set(Found(capture, boxes, generation))
        } catch (_: RuntimeException) {
            // A detection that fails leaves the cut where it is.
        } finally {
            busy.set(false)
        }
    }

    /** The faces in an upright grey picture: the point between the eyes and their distance. */
    private fun findFaces(pixels: ShortArray, size: FrameSize): List<Pair<PointF, Double>> {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.RGB_565)
        try {
            bitmap.copyPixelsFromBuffer(ShortBuffer.wrap(pixels))
            val detector = detectorFor(size)
            val slots = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val count = detector.findFaces(bitmap, slots)
            val out = ArrayList<Pair<PointF, Double>>(count)
            for (index in 0 until count) {
                val face = slots[index] ?: continue
                if (face.confidence() < MIN_CONFIDENCE) continue
                val mid = PointF()
                face.getMidPoint(mid)
                out += mid to face.eyesDistance().toDouble()
            }
            return out
        } finally {
            bitmap.recycle()
        }
    }

    private fun detectorFor(size: FrameSize): FaceDetector {
        val held = detector
        if (held != null && detectorWidth == size.width && detectorHeight == size.height) return held
        val made = FaceDetector(size.width, size.height, MAX_FACES)
        detector = made
        detectorWidth = size.width
        detectorHeight = size.height
        return made
    }

    private fun evenAtLeast2(value: Double): Int = maxOf(2, (value / 2).toInt() * 2)

    companion object {
        /** About five times a second. */
        const val GAP_MS = 200L

        /** The small copy's width, in the buffer's orientation: plenty for a face across a room. */
        const val SMALL_WIDTH = 320

        /** The platform detector's own limit per picture, and ours: a few people side by side. */
        const val MAX_FACES = 4

        /** Below this the platform's guess is too loose to frame. */
        const val MIN_CONFIDENCE = 0.3f
    }
}
