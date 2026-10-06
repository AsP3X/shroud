package de.corespace.shroud.core.calls.media

import android.content.Context
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource

/**
 * The device camera into a call's video source. Camera2 when the device has it, otherwise Camera1,
 * front camera first (calls §5, iOS `CallCamera`). Capture is the format nearest 1920×1080; the
 * video source fits that into 1920×1080 at 30 fps, and the encoder sends a rung of the ladder
 * below it ([CameraQuality], docs/calls.md "Camera quality"). Each frame's size is checked on its
 * way into the source, so [captureLong] follows what the camera really delivers.
 *
 * The system taking the camera (a phone call, another app) reports paused, and the next frame
 * reports it back. Stopping the camera ourselves does not: the call controller already pauses it
 * when the app backgrounds (`CallController.onAppVisible`), and a second report would keep it from
 * starting again on return.
 */
internal class CallCamera(
    context: Context,
    private val eglContext: EglBase.Context,
    private val source: VideoSource,
    private val onPaused: (Boolean) -> Unit,
    /** [captureLong] changed with the frames, on the camera's thread. */
    private val onCaptureSize: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val enumerator: CameraEnumerator? = openEnumerator(appContext)
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var deviceName: String? = null
    private var initialized = false
    private var stopping = false

    /** Written on the camera's thread, read by the engine on main ([isPaused]). */
    @Volatile private var reportedPaused = false

    @Volatile var usesFrontCamera: Boolean = true
        private set

    @Volatile var isRunning: Boolean = false
        private set

    /**
     * The longer side of the picture the encoder gets from this camera, 0 until it first starts:
     * what the format [start] chose should give ([sentLong]), then what the frames themselves
     * give once they arrive, which also covers [switchCamera] (the capturer asks the other camera
     * for the first one's size, and it may open at another). Measured before the video source,
     * through the same crop and shrink as its 1080p limit: the source's later shrinking for the
     * encoder (WebRTC's own adaptation within a rung) must not lower the ladder's ceiling.
     */
    @Volatile var captureLong: Int = 0
        private set

    /** The last frame's size, so [captureLong] is worked out only when it changes. Camera thread. */
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    /** The system took the camera (an error, a disconnect) and no frame has come since. */
    val isPaused: Boolean get() = reportedPaused

    val isAvailable: Boolean get() = devices().isNotEmpty()

    fun start(): Boolean {
        if (isRunning) return true
        val enumerator = enumerator ?: return false
        val name = pick(usesFrontCamera) ?: return false
        val formats = try {
            enumerator.getSupportedFormats(name).orEmpty()
        } catch (_: RuntimeException) {
            return false
        }
        val choice = nearestCapture(choices(formats)) ?: return false
        val capturer = this.capturer ?: enumerator.createCapturer(name, events)?.also { this.capturer = it } ?: return false
        val helper = this.helper ?: SurfaceTextureHelper.create("shroud-camera", eglContext)?.also { this.helper = it } ?: return false
        if (!initialized) {
            capturer.initialize(helper, appContext, measuring(source.capturerObserver))
            initialized = true
        }
        stopping = false
        // Before the first frame can arrive, so the frames' own size has the last word.
        frameWidth = 0
        frameHeight = 0
        captureLong = sentLong(choice)
        return try {
            capturer.startCapture(choice.width, choice.height, captureFps(choice.maxFps))
            deviceName = name
            usesFrontCamera = enumerator.isFrontFacing(name)
            isRunning = true
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun stop() {
        if (!isRunning && capturer == null) return
        stopping = true
        reportedPaused = false
        isRunning = false
        try {
            capturer?.stopCapture()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: RuntimeException) {
        }
    }

    fun close() {
        stopping = true
        reportedPaused = false
        isRunning = false
        val capturer = capturer
        this.capturer = null
        try {
            capturer?.stopCapture()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: RuntimeException) {
        }
        try {
            capturer?.dispose()
        } catch (_: RuntimeException) {
        }
        try {
            helper?.dispose()
        } catch (_: RuntimeException) {
        }
        helper = null
        initialized = false
    }

    /** Front to back, or back to front. No other camera: stays. */
    fun switchCamera() {
        if (!isRunning) return
        val enumerator = enumerator ?: return
        val capturer = capturer ?: return
        val names = devices()
        val next = names.firstOrNull { it != deviceName && enumerator.isFrontFacing(it) != usesFrontCamera }
            ?: names.firstOrNull { it != deviceName }
            ?: return
        capturer.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                    usesFrontCamera = isFrontFacing
                    deviceName = next
                    // The other camera's frames may come at another size: [measuring] notes it.
                }

                override fun onCameraSwitchError(errorDescription: String?) = Unit
            },
            next,
        )
    }

    /** Passes every frame on to the source, noting its size first. */
    private fun measuring(observer: CapturerObserver) = object : CapturerObserver {
        override fun onCapturerStarted(success: Boolean) = observer.onCapturerStarted(success)

        override fun onCapturerStopped() = observer.onCapturerStopped()

        override fun onFrameCaptured(frame: VideoFrame) {
            val width = frame.rotatedWidth
            val height = frame.rotatedHeight
            if (width != frameWidth || height != frameHeight) {
                frameWidth = width
                frameHeight = height
                val long = sentLong(CaptureChoice(width, height, 0))
                if (long > 0 && long != captureLong) {
                    captureLong = long
                    try {
                        onCaptureSize()
                    } catch (_: RuntimeException) {
                    }
                }
            }
            observer.onFrameCaptured(frame)
        }
    }

    private fun choices(formats: List<CameraEnumerationAndroid.CaptureFormat>): List<CaptureChoice> = formats.map { format ->
        CaptureChoice(format.width, format.height, format.framerate?.max ?: 30)
    }

    private fun pick(front: Boolean): String? {
        val enumerator = enumerator ?: return null
        val names = devices()
        if (names.isEmpty()) return null
        return names.firstOrNull { if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) } ?: names.first()
    }

    private fun devices(): Array<String> {
        val enumerator = enumerator ?: return emptyArray()
        return try {
            enumerator.deviceNames ?: emptyArray()
        } catch (_: RuntimeException) {
            emptyArray()
        }
    }

    private fun reportPaused(paused: Boolean) {
        if (stopping || paused == reportedPaused) return
        reportedPaused = paused
        onPaused(paused)
    }

    private val events = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            if (isRunning) reportPaused(true)
        }

        override fun onCameraDisconnected() {
            if (isRunning) reportPaused(true)
        }

        override fun onCameraFreezed(errorDescription: String?) = Unit

        override fun onCameraOpening(cameraName: String?) = Unit

        override fun onFirstFrameAvailable() {
            if (reportedPaused) reportPaused(false)
        }

        override fun onCameraClosed() = Unit
    }

    companion object {
        fun openEnumerator(context: Context): CameraEnumerator? = try {
            if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        } catch (_: RuntimeException) {
            null
        }

        /** False when the device has no camera the engine is allowed to open. */
        fun isAvailable(context: Context): Boolean {
            val enumerator = openEnumerator(context.applicationContext) ?: return false
            return try {
                enumerator.deviceNames?.isNotEmpty() == true
            } catch (_: RuntimeException) {
                false
            }
        }
    }
}
