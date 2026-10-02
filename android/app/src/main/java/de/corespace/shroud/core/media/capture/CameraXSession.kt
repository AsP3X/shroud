package de.corespace.shroud.core.media.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Looper
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import de.corespace.shroud.core.media.ImageEncodeException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CameraX binding for [ShroudCameraCapture]. Clips use [FileOutputOptions] only — never
 * [androidx.camera.video.MediaStoreOutputOptions] — so a recording cannot land in the gallery.
 * [ProcessCameraProvider.getInstance] is not waited on the main thread (that can deadlock); a bind
 * started there finishes on the main executor when the provider is ready, and [bindFailed] stays
 * set until that attempt succeeds.
 */
internal class CameraXSession(context: Context) : CameraSession {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shroud-camera").apply { isDaemon = true }
    }
    private val main = ContextCompat.getMainExecutor(app)
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var active: Recording? = null
    private var done: CompletableDeferred<Long>? = null
    private var generation = 0

    @Volatile
    private var bindFailed = false

    override val isBound: Boolean get() = imageCapture != null
    override val hasFrontCamera: Boolean get() = !bindFailed && hasLens(CameraCharacteristics.LENS_FACING_FRONT)
    override val hasBackCamera: Boolean get() = !bindFailed && hasLens(CameraCharacteristics.LENS_FACING_BACK)

    override fun bind(owner: LifecycleOwner, preview: Preview.SurfaceProvider, front: Boolean, video: Boolean) {
        val next = ++generation
        bindFailed = true
        val future = ProcessCameraProvider.getInstance(app)
        val work = Runnable {
            if (next != generation) return@Runnable
            try {
                bindReady(future.get(), owner, preview, front, video)
                bindFailed = false
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                bindFailed = true
                releaseUseCases()
                throw e
            } catch (e: Exception) {
                bindFailed = true
                releaseUseCases()
                throw e
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper() && !future.isDone) {
            // Waiting here deadlocks: CameraX finishes init on the main looper.
            future.addListener(
                Runnable {
                    try {
                        work.run()
                    } catch (_: Exception) {
                        // bindFailed is already set. The UI reads the flags.
                    }
                },
                main,
            )
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            work.run()
            return
        }
        val latch = CountDownLatch(1)
        var error: Exception? = null
        main.execute {
            try {
                work.run()
            } catch (e: Exception) {
                error = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("camera bind timed out")
        error?.let { throw it }
    }

    private fun bindReady(
        ready: ProcessCameraProvider,
        owner: LifecycleOwner,
        surface: Preview.SurfaceProvider,
        front: Boolean,
        video: Boolean,
    ) {
        provider = ready
        stopActive()
        ready.unbindAll()
        imageCapture = null
        videoCapture = null
        camera = null
        val frontSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        val backSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val frontAvailable = runCatching { ready.hasCamera(frontSelector) }.getOrDefault(false)
        val backAvailable = runCatching { ready.hasCamera(backSelector) }.getOrDefault(false)
        val selector = when {
            front && frontAvailable -> frontSelector
            !front && backAvailable -> backSelector
            frontAvailable -> frontSelector
            backAvailable -> backSelector
            else -> throw IllegalStateException("no camera")
        }
        val preview = Preview.Builder().build().also { it.surfaceProvider = surface }
        val still = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        val bound = if (video) {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)),
                )
                .build()
            val videoUse = VideoCapture.withOutput(recorder)
            videoCapture = videoUse
            ready.bindToLifecycle(owner, selector, preview, still, videoUse)
        } else {
            videoCapture = null
            ready.bindToLifecycle(owner, selector, preview, still)
        }
        imageCapture = still
        camera = bound
    }

    override fun unbind() {
        generation++
        releaseUseCases()
    }

    override suspend fun captureStill(): Bitmap {
        val capture = imageCapture ?: throw ImageEncodeException("Could not load that photo.")
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            if (cont.isActive) cont.resume(upright(image))
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resumeWithException(ImageEncodeException("Could not load that photo.", t))
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWithException(ImageEncodeException("Could not load that photo.", exception))
                    }
                },
            )
        }
    }

    // RECORD_AUDIO is checked here. ShroudCameraCapture also refuses before it asks for audio.
    @SuppressLint("MissingPermission")
    override fun startRecording(file: File, withAudio: Boolean) {
        val capture = videoCapture ?: throw IllegalStateException("video is not bound")
        check(active == null) { "already recording" }
        if (
            withAudio &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO not granted")
        }
        val waiter = CompletableDeferred<Long>()
        var pending = capture.output.prepareRecording(app, FileOutputOptions.Builder(file).build())
        if (withAudio) pending = pending.withAudioEnabled()
        val started = pending.start(executor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                waiter.complete(event.recordingStats.recordedDurationNanos / 1_000_000L)
            }
        }
        active = started
        done = waiter
    }

    override suspend fun stopRecording(): Long {
        val started = active
        val waiter = done
        active = null
        done = null
        if (started != null) runCatching { started.stop() }
        return waiter?.await() ?: 0L
    }

    override fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    override fun setZoom(ratio: Float) {
        val control = camera?.cameraControl ?: return
        val state = camera?.cameraInfo?.zoomState?.value
        val clamped = if (state != null) ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio) else ratio
        control.setZoomRatio(clamped)
    }

    private fun releaseUseCases() {
        stopActive()
        runCatching { provider?.unbindAll() }
        imageCapture = null
        videoCapture = null
        camera = null
    }

    private fun stopActive() {
        val started = active
        active = null
        if (started != null) runCatching { started.stop() }
    }

    /** [ImageProxy.toBitmap] does not apply [ImageProxy.getImageInfo] rotation. */
    private fun upright(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val degrees = image.imageInfo.rotationDegrees
        if (degrees % 360 == 0) return raw
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val turned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (turned !== raw) raw.recycle()
        return turned
    }

    private fun hasLens(facing: Int): Boolean = try {
        val manager = app.getSystemService(CameraManager::class.java) ?: return false
        manager.cameraIdList.any { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        }
    } catch (_: Exception) {
        false
    }

}