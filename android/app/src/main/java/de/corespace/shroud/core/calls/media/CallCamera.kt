package de.corespace.shroud.core.calls.media

import android.content.Context
import android.content.res.Configuration
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device camera into a call's video source. Camera2 when the device has it, otherwise Camera1,
 * front camera first (calls §5, iOS `CallCamera`). Capture is the format nearest 1920×1080.
 *
 * Every frame is cut on its way into the source (docs/calls.md, "Framing and Center Stage"): to
 * the shape the other side shows our picture in ([peerView]; our own shape while they sent none),
 * at most 1920 pixels on its longer side ([outputSize]), and with [centerStage] on round the faces
 * [CallFaceFinder] sees, gliding there ([Framer]). The cut is a `cropAndScale` of the camera's
 * buffer, no copy. The encoder then sends a rung of the ladder below that output ([CameraQuality],
 * "Camera quality"), so [captureSize] is the output's size as the frames give it. Each start and
 * each camera switch begins a new generation of the cut ([CameraCut]): the zoom and the faces of
 * one camera never carry over into the next. After each cut it publishes [focus], where the faces
 * are in what went out, for our own small picture to centre on.
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
    /** [captureSize] changed with the frames, on the camera's thread. */
    private val onCaptureSize: () -> Unit,
) {
    /** The size of the area the other side shows our picture in (their `view`); null: our own shape. */
    @Volatile var peerView: FrameSize? = null

    /** Center Stage: the cut follows the faces in the picture. */
    @Volatile var centerStage: Boolean = true

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
     * The picture the encoder gets from this camera, null until it first starts: what the format
     * [start] chose should give cut for [peerView], then the cut the frames themselves go out in
     * ([outputSize] of their size and [peerView]), which also covers [switchCamera] (the capturer
     * asks the other camera for the first one's size, and it may open at another) and a new
     * [peerView]. The encoder's later shrinking (WebRTC's own adaptation within a rung) must not
     * lower the ladder's ceiling, so this is measured before the source.
     */
    @Volatile var captureSize: FrameSize? = null
        private set

    /**
     * Where the faces are in the picture that last went out (0…1 on each axis, upright, before any
     * mirroring), gliding like the cut ([Framer.focus]): our own small picture centres on it
     * (`SelfView`). The middle with Center Stage off, without faces, and before the first frame.
     * Written on the camera's thread after each cut, read on main; one immutable value, so a
     * reader never sees half of an update.
     */
    @Volatile var focus: FramePoint = FramePoint.Middle
        private set

    /**
     * Bumped by each [start] and each finished camera switch; the camera's thread starts the cut
     * and the face detector afresh when it sees a new one ([CameraCut]).
     */
    private val generation = AtomicInteger()

    // The cut, on the camera's thread only.
    private val cut = CameraCut()
    private val faces = CallFaceFinder()

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
            capturer.initialize(helper, appContext, framing(source.capturerObserver))
            initialized = true
        }
        stopping = false
        generation.incrementAndGet()
        focus = FramePoint.Middle
        // Before the first frame can arrive, so the frames' own cut has the last word: the format
        // as it will stand upright on this screen, cut for their view.
        val portrait = appContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        captureSize = framedSize(uprightCapture(choice.width, choice.height, portrait), peerView)
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
        faces.close()
        focus = FramePoint.Middle
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
                    // Another camera: the cut and its faces start again ([framing]), and its
                    // frames may come at another size, which [framing] notes too.
                    generation.incrementAndGet()
                }

                override fun onCameraSwitchError(errorDescription: String?) = Unit
            },
            next,
        )
    }

    /**
     * Passes every frame on to the source, cut (docs/calls.md, "Framing and Center Stage"). On the
     * camera's thread. The cut is worked out in upright pixels ([Framer]) and taken out of the
     * buffer in its own orientation ([uprightToBuffer]); the frame keeps its rotation, so the
     * encoder and our own picture turn it upright as before.
     */
    private fun framing(observer: CapturerObserver) = object : CapturerObserver {
        override fun onCapturerStarted(success: Boolean) = observer.onCapturerStarted(success)

        override fun onCapturerStopped() = observer.onCapturerStopped()

        override fun onFrameCaptured(frame: VideoFrame) {
            val capture = FrameSize(frame.rotatedWidth, frame.rotatedHeight)
            if (capture.width < 2 || capture.height < 2) {
                observer.onFrameCaptured(frame)
                return
            }
            val output = outputSize(capture, peerView)
            val follow = centerStage
            val camera = generation.get()
            noteOutput(output)
            val now = System.nanoTime() / 1_000_000
            if (cut.frame(camera, capture, output, follow)) faces.reset()
            faces.take()?.let { found -> cut.faces(found.faces, found.capture, found.generation, now) }
            val rect = cut.next(now)
            focus = cut.focus()
            if (follow) faces.offer(frame, capture, camera, now)

            val buffer = frame.buffer
            val bufferWidth = buffer.width
            val bufferHeight = buffer.height
            val crop = evenCrop(uprightToBuffer(rect, frame.rotation, bufferWidth, bufferHeight), bufferWidth, bufferHeight)
            val scaled = bufferOriented(output, frame.rotation)
            val whole = crop.x == 0 && crop.y == 0 && crop.width == bufferWidth && crop.height == bufferHeight
            if (whole && scaled.width == bufferWidth && scaled.height == bufferHeight) {
                observer.onFrameCaptured(frame)
                return
            }
            val cropped = try {
                buffer.cropAndScale(crop.x, crop.y, crop.width, crop.height, scaled.width, scaled.height)
            } catch (_: RuntimeException) {
                observer.onFrameCaptured(frame)
                return
            }
            val next = VideoFrame(cropped, frame.rotation, frame.timestampNs)
            try {
                observer.onFrameCaptured(next)
            } finally {
                next.release()
            }
        }
    }

    /** The cut's size is the ladder's ceiling and the encoder's shrink: a change re-tunes them. */
    private fun noteOutput(output: FrameSize) {
        if (output.width <= 0 || output.height <= 0 || output == captureSize) return
        captureSize = output
        try {
            onCaptureSize()
        } catch (_: RuntimeException) {
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
