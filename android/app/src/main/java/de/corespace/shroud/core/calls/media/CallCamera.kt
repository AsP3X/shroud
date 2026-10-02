package de.corespace.shroud.core.calls.media

import android.content.Context
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

/**
 * The device camera into a call's video source. Camera2 when the device has it, otherwise Camera1,
 * front camera first (calls §5, iOS `CallCamera`). Capture is the format nearest 1280×720; the
 * video source scales that to 1280×720 at 30 fps.
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
) {
    private val appContext = context.applicationContext
    private val enumerator: CameraEnumerator? = openEnumerator(appContext)
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var deviceName: String? = null
    private var initialized = false
    private var stopping = false
    private var reportedPaused = false

    @Volatile var usesFrontCamera: Boolean = true
        private set

    @Volatile var isRunning: Boolean = false
        private set

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
        val choice = nearestCapture(
            formats.map { format ->
                val rate = format.framerate
                CaptureChoice(format.width, format.height, rate?.max ?: 30)
            },
        ) ?: return false
        val capturer = this.capturer ?: enumerator.createCapturer(name, events)?.also { this.capturer = it } ?: return false
        val helper = this.helper ?: SurfaceTextureHelper.create("shroud-camera", eglContext)?.also { this.helper = it } ?: return false
        if (!initialized) {
            capturer.initialize(helper, appContext, source.capturerObserver)
            initialized = true
        }
        stopping = false
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
                }

                override fun onCameraSwitchError(errorDescription: String?) = Unit
            },
            next,
        )
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
