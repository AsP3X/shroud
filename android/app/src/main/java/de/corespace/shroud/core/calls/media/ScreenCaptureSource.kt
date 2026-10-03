package de.corespace.shroud.core.calls.media

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.WindowManager
import de.corespace.shroud.core.calls.ScreenCaptureGrant
import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.Standard
import de.corespace.shroud.core.calls.screenOutput
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
 * MediaProjection scales the display into a virtual display of [screenOutput], so frames arrive
 * already at the size that goes out. A frame-rate gate drops the rest, and a still screen repeats
 * its last frame. WebRTC's `ScreenCapturerAndroid` does neither, and holding its one texture to
 * repeat would stall capture, so this source owns the `ImageReader`.
 *
 * [onFormat] runs when the outgoing size or frame rate changes, including a frame-rate-only
 * change, before frames at that rate are delivered. [onEnded] runs when the projection stops by
 * itself (the system chip, the lock, another projection). [stop] — our own Stop — does not call it.
 */
internal class ScreenCaptureSource(
    context: Context,
    private val observer: CapturerObserver,
    private val onFirstFrame: () -> Unit,
    private val onEnded: () -> Unit,
    private val onFormat: (width: Int, height: Int, frameRate: Int) -> Unit,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var quality: ScreenShareQuality = ScreenShareQuality.Standard
    @Volatile private var frameRate: Int = ScreenShareQuality.Standard.frameRate
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
    @Volatile private var released = true
    @Volatile private var stoppingSelf = false

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
        this.frameRate = quality.frameRate
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

    /**
     * A new resolution rebuilds the virtual display. A new frame rate keeps the display and still
     * reaches [onFormat], so a share that started at 15 fps can move to 60.
     */
    fun applyQuality(quality: ScreenShareQuality) {
        this.quality = quality
        this.frameRate = quality.frameRate
        val handler = handler ?: return
        if (released) return
        handler.post {
            resizeFrom(sourceWidth, sourceHeight)
            reportFormat()
        }
    }

    /**
     * Our Stop, or the call ending. Does not report [onEnded].
     *
     * The capture thread only stops delivering frames. The display is released and
     * [MediaProjection.stop] runs on the caller, after that wait. Doing either while the caller
     * is blocked makes the system kill the process: both call back onto the waiting thread.
     */
    fun stop() {
        val handler = handler
        val thread = thread
        if (handler == null || thread == null || released) {
            released = true
            return
        }
        stoppingSelf = true
        if (Looper.myLooper() == thread.looper) {
            finish(notifyEnded = false)
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            try {
                handler.removeCallbacksAndMessages(null)
                try {
                    reader?.setOnImageAvailableListener(null, null)
                } catch (_: RuntimeException) {
                }
            } finally {
                done.countDown()
            }
        }
        try {
            done.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        finish(notifyEnded = false)
    }

    private fun openDisplay(width: Int, height: Int): Boolean {
        val output = screenOutput(width, height, quality)
        if (output.width < 2 || output.height < 2) return false
        val reader = try {
            newReader(output.width, output.height)
        } catch (_: RuntimeException) {
            return false
        }
        sourceWidth = width
        sourceHeight = height
        wireWidth = output.width
        wireHeight = output.height
        reportFormat()
        reader.setOnImageAvailableListener({ imageReader -> onImage(imageReader) }, handler)
        val display = createDisplay(reader, output.width, output.height)
        if (display == null) {
            closeReaderSoon(reader)
            wireWidth = 0
            wireHeight = 0
            return false
        }
        this.reader = reader
        this.display = display
        return true
    }

    private fun resizeFrom(width: Int, height: Int) {
        if (released || stoppingSelf || width < 2 || height < 2) return
        val output = screenOutput(width, height, quality)
        sourceWidth = width
        sourceHeight = height
        if (output.width == wireWidth && output.height == wireHeight) return
        // Android 14 throws if createVirtualDisplay runs a second time on this projection.
        // The display from the consent is resized onto a new reader instead.
        val next = try {
            newReader(output.width, output.height)
        } catch (_: RuntimeException) {
            return
        }
        val current = display
        val previous = reader
        if (current == null || previous == null) {
            closeReaderSoon(next)
            return
        }
        next.setOnImageAvailableListener({ imageReader -> onImage(imageReader) }, handler)
        val dpi = appContext.resources.displayMetrics.densityDpi.coerceAtLeast(1)
        try {
            current.resize(output.width, output.height, dpi)
            current.setSurface(next.surface)
        } catch (_: RuntimeException) {
            try {
                current.resize(wireWidth, wireHeight, dpi)
                current.setSurface(previous.surface)
            } catch (_: RuntimeException) {
            }
            closeReaderSoon(next)
            return
        }
        reader = next
        wireWidth = output.width
        wireHeight = output.height
        reportFormat()
        closeReaderSoon(previous)
    }

    private fun reportFormat() {
        if (wireWidth < 2 || wireHeight < 2 || frameRate <= 0) return
        try {
            onFormat(wireWidth, wireHeight, frameRate)
        } catch (_: RuntimeException) {
        }
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ImageReader.Builder(width, height)
                .setMaxImages(MAX_IMAGES)
                .setImageFormat(PixelFormat.RGBA_8888)
                .build()
        } else {
            // ImageReader.Builder is API 33. This is the only way to open a reader on API 30–32.
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        }

    private fun createDisplay(reader: ImageReader, width: Int, height: Int): VirtualDisplay? {
        val dpi = appContext.resources.displayMetrics.densityDpi.coerceAtLeast(1)
        return try {
            projection?.createVirtualDisplay(
                "shroud-screen",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    /** The listener is cleared now. The reader itself closes on the main thread, after the display that owned its surface has released it. */
    private fun closeReaderSoon(reader: ImageReader?) {
        if (reader == null) return
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (_: RuntimeException) {
        }
        main.post {
            try {
                reader.close()
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun onImage(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (_: RuntimeException) {
            null
        } ?: return
        try {
            // A resize leaves one callback queued for the reader it just closed.
            if (released || stoppingSelf || imageReader !== reader || !accepting) return
            val now = System.nanoTime()
            if (!screenFrameDue(now, lastSentNs, frameRate)) return
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
        stopProjection(releaseCapture(notifyEnded))
    }

    /**
     * Drops the display and the reader. Returns the projection when the caller still has to stop
     * it. A system [MediaProjection.Callback.onStop] is already stopping it, so that path returns
     * null and is the only path that reports [onEnded].
     */
    private fun releaseCapture(notifyEnded: Boolean): MediaProjection? {
        val ending = synchronized(lock) {
            if (released) {
                null
            } else {
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
                val display = display
                this.display = null
                try {
                    display?.setSurface(null)
                } catch (_: RuntimeException) {
                }
                try {
                    display?.release()
                } catch (_: RuntimeException) {
                }
                val reader = reader
                this.reader = null
                closeReaderSoon(reader)
                val projection = projection
                this.projection = null
                try {
                    projection?.unregisterCallback(projectionCallback)
                } catch (_: RuntimeException) {
                }
                lastBuffer?.release()
                lastBuffer = null
                lastSentNs = 0L
                wireWidth = 0
                wireHeight = 0
                CaptureRelease(tell, if (tell) null else projection)
            }
        } ?: return null
        try {
            observer.onCapturerStopped()
        } catch (_: RuntimeException) {
        }
        thread?.quitSafely()
        thread = null
        handler = null
        if (ending.tell) onEnded()
        return ending.projection
    }

    private fun stopProjection(projection: MediaProjection?) {
        if (projection == null) return
        try {
            projection.stop()
        } catch (_: Exception) {
        }
    }

    private data class CaptureRelease(val tell: Boolean, val projection: MediaProjection?)

    companion object {
        private const val REPEAT_MS = 500L
        private const val MAX_IMAGES = 2
    }
}
