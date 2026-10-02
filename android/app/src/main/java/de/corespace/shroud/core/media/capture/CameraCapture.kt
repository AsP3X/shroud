package de.corespace.shroud.core.media.capture

import android.net.Uri
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.media.ImageEncodeException
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.storage.SensitiveTempFiles
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a [CameraCapture.bind] attempt is. [Unbound] is the state before the first bind and after
 * [CameraCapture.unbind]. [Binding] lasts until the provider is ready, including the path that
 * finishes on the main thread after [CameraCapture.bind] has returned.
 */
sealed interface CameraBindState {
    data object Unbound : CameraBindState
    data object Binding : CameraBindState
    data class Bound(val hasFront: Boolean, val hasBack: Boolean) : CameraBindState
    data object Failed : CameraBindState
}

/**
 * In-app camera (conversation-compose-media §8.3). Photos and clips go to [SensitiveTempFiles]
 * (`cacheDir/shroud-*`), never MediaStore, so a capture does not appear in the gallery.
 * Needs the chats unlocked: [takePhoto] throws [CryptoError.Locked] when they are not.
 * A bind that throws leaves [hasFrontCamera] and [hasBackCamera] false and [bindState] at [CameraBindState.Failed].
 */
interface CameraCapture {
    fun bind(owner: LifecycleOwner, preview: Preview.SurfaceProvider, front: Boolean, video: Boolean)
    fun unbind()
    val bindState: StateFlow<CameraBindState>
    val hasFrontCamera: Boolean
    val hasBackCamera: Boolean

    /** Null until a camera is bound. The range CameraX reports for the open camera. */
    val zoomRange: ClosedFloatingPointRange<Float>?

    /** False until a camera is bound, and when that camera has no flash. */
    val hasFlashUnit: Boolean

    /** JPEG in a private temp file, as a content [MediaImageSource.ContentUri]. Never MediaStore. */
    suspend fun takePhoto(): MediaImageSource

    /** False when [withAudio] is set and `RECORD_AUDIO` is missing, or the camera cannot record. */
    fun startRecording(withAudio: Boolean): Boolean

    suspend fun stopRecording(): PickedMovieFile?
    fun setTorch(on: Boolean)
    fun setZoom(ratio: Float)
}

data class PickedMovieFile(val file: File, val durationMs: Long)

/** The hardware behind [ShroudCameraCapture]. Tests pass a fake; production uses CameraX. */
internal interface CameraSession {
    val isBound: Boolean
    val bindState: StateFlow<CameraBindState>
    val hasFrontCamera: Boolean
    val hasBackCamera: Boolean
    val zoomRange: ClosedFloatingPointRange<Float>?
    val hasFlashUnit: Boolean
    fun bind(owner: LifecycleOwner, preview: Preview.SurfaceProvider, front: Boolean, video: Boolean)
    fun unbind()

    /** One listener. [ShroudCameraCapture] uses it because a bind started on the main thread can finish later. */
    fun setBindListener(listener: (CameraBindState) -> Unit)

    /** Ownership of the returned bitmap passes to the caller, which recycles it. Already upright. */
    suspend fun captureStill(): android.graphics.Bitmap
    fun startRecording(file: File, withAudio: Boolean)
    suspend fun stopRecording(): Long
    fun setTorch(on: Boolean)
    fun setZoom(ratio: Float)
}

internal class ShroudCameraCapture(
    private val temps: SensitiveTempFiles,
    private val unlocked: () -> Boolean,
    private val session: CameraSession,
    private val audioGranted: () -> Boolean,
    private val uriFor: (File) -> Uri,
) : CameraCapture {
    private var failed = false
    private var clip: File? = null

    /** False while [unbind] is turning a thrown bind into [CameraBindState.Failed]. */
    @Volatile
    private var accepting = false
    private val bindStateFlow = MutableStateFlow<CameraBindState>(CameraBindState.Unbound)

    init {
        session.setBindListener { state ->
            if (!accepting) return@setBindListener
            when (state) {
                CameraBindState.Failed -> {
                    failed = true
                    accepting = false
                    bindStateFlow.value = state
                }
                is CameraBindState.Bound -> {
                    failed = false
                    accepting = false
                    bindStateFlow.value = state
                }
                CameraBindState.Unbound -> {
                    accepting = false
                    bindStateFlow.value = state
                }
                CameraBindState.Binding -> bindStateFlow.value = state
            }
        }
    }

    override val bindState: StateFlow<CameraBindState> = bindStateFlow.asStateFlow()
    override val hasFrontCamera: Boolean get() = !failed && session.hasFrontCamera
    override val hasBackCamera: Boolean get() = !failed && session.hasBackCamera
    override val zoomRange: ClosedFloatingPointRange<Float>? get() = if (failed) null else session.zoomRange
    override val hasFlashUnit: Boolean get() = !failed && session.hasFlashUnit

    override fun bind(owner: LifecycleOwner, preview: Preview.SurfaceProvider, front: Boolean, video: Boolean) {
        accepting = true
        bindStateFlow.value = CameraBindState.Binding
        try {
            session.bind(owner, preview, front, video)
            failed = false
            val state = session.bindState.value
            if (state !is CameraBindState.Binding) {
                bindStateFlow.value = state
                accepting = false
            }
        } catch (_: Exception) {
            failed = true
            accepting = false
            runCatching { session.unbind() }
            bindStateFlow.value = CameraBindState.Failed
        }
    }

    override fun unbind() {
        accepting = false
        val leftover = clip
        clip = null
        runCatching { session.unbind() }
        leftover?.delete()
        bindStateFlow.value = CameraBindState.Unbound
    }

    override suspend fun takePhoto(): MediaImageSource {
        if (!unlocked()) throw CryptoError.Locked
        if (failed || !session.isBound) throw ImageEncodeException("Could not load that photo.")
        val bitmap = try {
            session.captureStill()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CryptoError) {
            throw e
        } catch (e: ImageEncodeException) {
            throw e
        } catch (e: Exception) {
            throw ImageEncodeException("Could not load that photo.", e)
        }
        val file = try {
            temps.create("cam", "jpg")
        } catch (e: IOException) {
            recycle(bitmap)
            throw ImageEncodeException("Could not load that photo.", e)
        }
        try {
            writeJpeg(file, bitmap)
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: ImageEncodeException) {
            file.delete()
            throw e
        } catch (e: Exception) {
            file.delete()
            throw ImageEncodeException("Could not load that photo.", e)
        } finally {
            recycle(bitmap)
        }
        return MediaImageSource.ContentUri(uriFor(file))
    }

    override fun startRecording(withAudio: Boolean): Boolean {
        if (!unlocked() || failed || !session.isBound) return false
        if (withAudio && !audioGranted()) return false
        if (clip != null) return false
        val file = try {
            temps.create("cam", "mp4")
        } catch (_: IOException) {
            return false
        }
        return try {
            session.startRecording(file, withAudio)
            clip = file
            true
        } catch (_: Exception) {
            file.delete()
            false
        }
    }

    override suspend fun stopRecording(): PickedMovieFile? {
        val file = clip ?: return null
        clip = null
        val duration = try {
            session.stopRecording()
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (_: Exception) {
            file.delete()
            return null
        }
        if (!file.isFile || file.length() == 0L) {
            file.delete()
            return null
        }
        return PickedMovieFile(file, duration.coerceAtLeast(0L))
    }

    override fun setTorch(on: Boolean) {
        if (!failed) session.setTorch(on)
    }

    override fun setZoom(ratio: Float) {
        if (!failed) session.setZoom(ratio)
    }

    private fun writeJpeg(file: File, bitmap: android.graphics.Bitmap) {
        val software = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
            bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                ?: throw ImageEncodeException("Could not load that photo.")
        } else {
            bitmap
        }
        try {
            FileOutputStream(file).use { out ->
                if (!software.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw ImageEncodeException("Could not load that photo.")
                }
            }
        } finally {
            if (software !== bitmap) software.recycle()
        }
    }

    private fun recycle(bitmap: android.graphics.Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private companion object {
        const val JPEG_QUALITY = 95
    }
}
