package de.corespace.shroud.core.calls.media

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.WindowManager
import de.corespace.shroud.core.calls.ScreenCaptureGrant
import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.Standard
import de.corespace.shroud.core.calls.maxSide
import de.corespace.shroud.core.calls.screenWireSize
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.YuvHelper
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The shared screen, as frames on a screencast [org.webrtc.VideoSource] (calls §7.2).
 *
 * MediaProjection scales the display into a virtual display of [screenWireSize], so frames arrive
 * already at the size that goes out. A frame-rate gate drops the rest, and a still screen repeats
 * its last frame. WebRTC's `ScreenCapturerAndroid` does neither, and holding its one texture to
 * repeat would stall capture, so this source owns the `ImageReader`.
 *
 * [onEnded] runs when the projection stops by itself (the system chip, the lock, another
 * projection). [stop] — our own Stop — does not call it.
 */
internal class ScreenCaptureSource(
    context: Context,
    private val observer: CapturerObserver,
    private val onFirstFrame: () -> Unit,
    private val onEnded: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var quality: ScreenShareQuality = ScreenShareQuality.Standard
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var lastBuffer: VideoFrame.I420Buffer? = null
    private var lastSentNs = 0L
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var wireWidth = 0
    private var wireHeight = 0
    private var accepting = true
    private var released = true
    private var stoppingSelf = false

    /** [onEnded] stays quiet until [start] has returned a projection that is actually up. */
    private var armEnded = false
    private val announcedFirst = AtomicBoolean(false)
    private var displayListener: DisplayManager.DisplayListener? = null

    private val repeat = object : Runnable {
        override fun run() {
            val handler = handler ?: return
            if (released) return
            val now = System.nanoTime()
            if (screenRepeatDue(now, lastSentNs)) repeatLast(now)
            if (!released) handler.postDelayed(this, REPEAT_MS)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (stoppingSelf || released) return
            val handler = handler
            if (handler != null && Looper.myLooper() != handler.looper) {
                handler.post { onStop() }
                return
            }
            finish(notifyEnded = true)
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            handler?.post { resizeFrom(width, height) }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            accepting = isVisible
        }
    }

    /** False when the consent cannot start a projection. Safe to call again after [stop]. */
    fun start(grant: ScreenCaptureGrant, quality: ScreenShareQuality): Boolean {
        stop()
        if (grant.resultCode != android.app.Activity.RESULT_OK) return false
        this.quality = quality
        val manager = appContext.getSystemService(MediaProjectionManager::class.java) ?: return false
        val projection = try {
            manager.getMediaProjection(grant.resultCode, grant.data)
        } catch (_: RuntimeException) {
            null
        } ?: return false
        val thread = HandlerThread("shroud-screen")
        thread.start()
        val handler = Handler(thread.looper)
        this.thread = thread
        this.handler = handler
        this.projection = projection
        released = false
        stoppingSelf = false
        armEnded = false
        announcedFirst.set(false)
        lastSentNs = 0L
        accepting = true
        try {
            projection.registerCallback(projectionCallback, handler)
        } catch (_: RuntimeException) {
            finish(notifyEnded = false)
            return false
        }
        val (width, height) = fullDisplaySize()
        if (width < 2 || height < 2 || !openDisplay(width, height)) {
            finish(notifyEnded = false)
            return false
        }
        listenForDisplayChanges()
        observer.onCapturerStarted(true)
        handler.postDelayed(repeat, REPEAT_MS)
        armEnded = true
        return true
    }

    fun applyQuality(quality: ScreenShareQuality) {
        this.quality = quality
        val handler = handler ?: return
        if (released) return
        handler.post { resizeFrom(sourceWidth, sourceHeight) }
    }

    /** Our Stop, or the call ending. Does not report [onEnded]. */
    fun stop() {
        stoppingSelf = true
        val handler = handler
        val thread = thread
        if (handler == null || thread == null) {
            released = true
            return
        }
        if (Looper.myLooper() == thread.looper) {
            finish(notifyEnded = false)
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            finish(notifyEnded = false)
            done.countDown()
        }
        try {
            done.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun openDisplay(width: Int, height: Int): Boolean {
        val (wireW, wireH) = screenWireSize(width, height, quality.resolution.maxSide)
        if (wireW < 2 || wireH < 2) return false
        sourceWidth = width
        sourceHeight = height
        val reader = try {
            ImageReader.newInstance(wireW, wireH, PixelFormat.RGBA_8888, 2)
        } catch (_: RuntimeException) {
            return false
        }
        reader.setOnImageAvailableListener({ imageReader -> onImage(imageReader) }, handler)
        val dpi = appContext.resources.displayMetrics.densityDpi.coerceAtLeast(1)
        val display = try {
            projection?.createVirtualDisplay(
                "shroud-screen",
                wireW,
                wireH,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        } catch (_: RuntimeException) {
            reader.close()
            null
        } ?: return false
        this.reader = reader
        this.display = display
        wireWidth = wireW
        wireHeight = wireH
        return true
    }

    private fun resizeFrom(width: Int, height: Int) {
        if (released || width < 2 || height < 2) return
        val (wireW, wireH) = screenWireSize(width, height, quality.resolution.maxSide)
        if (wireW == wireWidth && wireH == wireHeight) {
            sourceWidth = width
            sourceHeight = height
            return
        }
        val next = try {
            ImageReader.newInstance(wireW, wireH, PixelFormat.RGBA_8888, 2)
        } catch (_: RuntimeException) {
            return
        }
        next.setOnImageAvailableListener({ imageReader -> onImage(imageReader) }, handler)
        val dpi = appContext.resources.displayMetrics.densityDpi.coerceAtLeast(1)
        val display = display ?: run {
            next.close()
            return
        }
        try {
            display.resize(wireW, wireH, dpi)
            display.surface = next.surface
        } catch (_: RuntimeException) {
            next.close()
            return
        }
        reader?.close()
        reader = next
        sourceWidth = width
        sourceHeight = height
        wireWidth = wireW
        wireHeight = wireH
    }

    private fun onImage(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (_: RuntimeException) {
            null
        } ?: return
        try {
            if (released || !accepting) return
            val now = System.nanoTime()
            if (!screenFrameDue(now, lastSentNs, quality.frameRate)) return
            val buffer = toI420(image) ?: return
            push(buffer, now)
            lastSentNs = now
        } finally {
            image.close()
        }
    }

    private fun toI420(image: Image): VideoFrame.I420Buffer? {
        val width = image.width and 1.inv()
        val height = image.height and 1.inv()
        if (width < 2 || height < 2) return null
        val plane = image.planes.firstOrNull() ?: return null
        val i420 = JavaI420Buffer.allocate(width, height)
        val converted = try {
            val src = packed(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height)
            YuvHelper.ABGRToI420(
                src,
                if (plane.pixelStride == 4) plane.rowStride else width * 4,
                i420.dataY,
                i420.strideY,
                i420.dataU,
                i420.strideU,
                i420.dataV,
                i420.strideV,
                width,
                height,
            )
            true
        } catch (_: RuntimeException) {
            false
        }
        if (!converted) {
            i420.release()
            return null
        }
        return i420
    }

    /** RGBA tightly packed when the plane's pixel stride is not 4. Otherwise the plane buffer. */
    private fun packed(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): ByteBuffer {
        val src = buffer.duplicate()
        src.rewind()
        if (pixelStride == 4) return src
        val packed = ByteBuffer.allocateDirect(width * height * 4)
        val row = ByteArray(rowStride.coerceAtLeast(pixelStride * width))
        for (y in 0 until height) {
            val start = y * rowStride
            if (start >= src.capacity()) break
            src.position(start)
            val read = minOf(row.size, src.remaining())
            src.get(row, 0, read)
            for (x in 0 until width) {
                val at = x * pixelStride
                if (at + 3 >= read) break
                packed.put(row[at])
                packed.put(row[at + 1])
                packed.put(row[at + 2])
                packed.put(row[at + 3])
            }
        }
        packed.rewind()
        return packed
    }

    private fun push(buffer: VideoFrame.I420Buffer, timestampNs: Long) {
        buffer.retain()
        val frame = VideoFrame(buffer, 0, timestampNs)
        try {
            observer.onFrameCaptured(frame)
        } catch (_: RuntimeException) {
        } finally {
            frame.release()
        }
        synchronized(lock) {
            lastBuffer?.release()
            lastBuffer = buffer
        }
        if (announcedFirst.compareAndSet(false, true)) onFirstFrame()
    }

    private fun repeatLast(timestampNs: Long) {
        val buffer = synchronized(lock) { lastBuffer?.also { it.retain() } } ?: return
        val frame = VideoFrame(buffer, 0, timestampNs)
        try {
            observer.onFrameCaptured(frame)
        } catch (_: RuntimeException) {
        } finally {
            frame.release()
        }
        lastSentNs = timestampNs
    }

    private fun listenForDisplayChanges() {
        val displays = appContext.getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                val (width, height) = fullDisplaySize()
                handler?.post { resizeFrom(width, height) }
            }
        }
        displayListener = listener
        displays.registerDisplayListener(listener, handler)
    }

    private fun fullDisplaySize(): Pair<Int, Int> {
        val window = appContext.getSystemService(WindowManager::class.java) ?: return 0 to 0
        val bounds = window.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun finish(notifyEnded: Boolean) {
        val notify = synchronized(lock) {
            if (released) return
            released = true
            val tell = notifyEnded && !stoppingSelf && armEnded
            armEnded = false
            handler?.removeCallbacksAndMessages(null)
            val listener = displayListener
            displayListener = null
            if (listener != null) {
                try {
                    appContext.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener)
                } catch (_: RuntimeException) {
                }
            }
            try {
                display?.release()
            } catch (_: RuntimeException) {
            }
            display = null
            try {
                reader?.close()
            } catch (_: RuntimeException) {
            }
            reader = null
            val projection = projection
            this.projection = null
            try {
                projection?.unregisterCallback(projectionCallback)
            } catch (_: RuntimeException) {
            }
            if (!tell) {
                try {
                    projection?.stop()
                } catch (_: RuntimeException) {
                }
            }
            lastBuffer?.release()
            lastBuffer = null
            lastSentNs = 0L
            wireWidth = 0
            wireHeight = 0
            tell
        }
        try {
            observer.onCapturerStopped()
        } catch (_: RuntimeException) {
        }
        thread?.quitSafely()
        thread = null
        handler = null
        if (notify) onEnded()
    }

    companion object {
        private const val REPEAT_MS = 500L
    }
}
