package de.corespace.shroud.core.media.capture

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.camera.core.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.media.ImageEncodeException
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.storage.SensitiveTempFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/**
 * Captures land in `cacheDir/shroud-*`. The capturer is fake; a real CameraX still on a device
 * is owed. This path must not insert into MediaStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraCaptureTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val temps = SensitiveTempFiles(app.cacheDir)
    private val owner = object : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry(this)
    }
    private val preview = Preview.SurfaceProvider { _ -> }

    @Test
    fun takePhotoWritesAPrivateTempFileAndDoesNotInsertIntoMediaStore() = runBlocking {
        val probe = InsertProbe()
        probe.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = false })
        ShadowContentResolver.registerProviderInternal("media", probe)
        val session = FakeSession()
        val capture = capture(session, unlocked = true, audio = false)
        capture.bind(owner, preview, front = false, video = false)

        val source = capture.takePhoto() as MediaImageSource.ContentUri
        val path = requireNotNull(source.uri.path)
        assertTrue(path, path.contains("/cache/") || path.contains("no_backup"))
        assertTrue(path, path.contains("shroud-cam-"))
        val file = File(path)
        assertEquals(app.cacheDir, file.parentFile)
        assertTrue(file.isFile)
        assertEquals(listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), file.readBytes().take(3))
        assertEquals(0, probe.inserts)
        assertEquals(1, session.captures)
    }

    @Test
    fun takePhotoWhileLockedThrowsAndDoesNotCapture() = runBlocking {
        val session = FakeSession()
        val capture = capture(session, unlocked = false, audio = true)
        capture.bind(owner, preview, front = false, video = false)
        try {
            capture.takePhoto()
            fail("expected CryptoError.Locked")
        } catch (e: CryptoError) {
            assertEquals(CryptoError.Locked, e)
        }
        assertEquals(0, session.captures)
    }

    @Test
    fun bindFailureClearsBothCameraFlags() {
        val session = FakeSession().apply { failBind = true }
        val capture = capture(session, unlocked = true, audio = true)
        capture.bind(owner, preview, front = true, video = true)
        assertFalse(capture.hasFrontCamera)
        assertFalse(capture.hasBackCamera)
        assertFalse(session.bound)
    }

    @Test
    fun aBoundCameraReportsTheLensesTheSessionHas() {
        val session = FakeSession().apply { front = false; back = true }
        val capture = capture(session, unlocked = true, audio = true)
        capture.bind(owner, preview, front = false, video = false)
        assertFalse(capture.hasFrontCamera)
        assertTrue(capture.hasBackCamera)
    }

    @Test
    fun recordingWithoutAMicrophoneRefusesAudioAndASilentClipStaysPrivate() = runBlocking {
        val session = FakeSession()
        val capture = capture(session, unlocked = true, audio = false)
        capture.bind(owner, preview, front = false, video = true)
        assertFalse(capture.startRecording(withAudio = true))
        assertNull(session.recordFile)
        assertTrue(capture.startRecording(withAudio = false))
        val clip = requireNotNull(session.recordFile)
        assertEquals(false, session.recordedAudio)
        assertTrue(clip.absolutePath.contains("/cache/") || clip.absolutePath.contains("no_backup"))
        assertTrue(clip.name.startsWith("shroud-cam-"))
        val stopped = requireNotNull(capture.stopRecording())
        assertEquals(clip, stopped.file)
        assertEquals(1_500L, stopped.durationMs)
        assertTrue(stopped.file.length() > 0L)
    }

    @Test
    fun takePhotoWithoutABindThrowsImageEncodeException() = runBlocking {
        val capture = capture(FakeSession(), unlocked = true, audio = true)
        try {
            capture.takePhoto()
            fail("expected ImageEncodeException")
        } catch (_: ImageEncodeException) {
        }
    }

    private fun capture(session: FakeSession, unlocked: Boolean, audio: Boolean) = ShroudCameraCapture(
        temps = temps,
        unlocked = { unlocked },
        session = session,
        audioGranted = { audio },
        uriFor = { Uri.parse(it.toURI().toString()) },
    )

    private class FakeSession : CameraSession {
        var bound = false
        var failBind = false
        var front = true
        var back = true
        var captures = 0
        var recordFile: File? = null
        var recordedAudio: Boolean? = null

        override val isBound: Boolean get() = bound
        override val hasFrontCamera: Boolean get() = front
        override val hasBackCamera: Boolean get() = back

        override fun bind(owner: LifecycleOwner, preview: Preview.SurfaceProvider, front: Boolean, video: Boolean) {
            if (failBind) throw IllegalStateException("no camera")
            bound = true
        }

        override fun unbind() {
            bound = false
        }

        override suspend fun captureStill(): Bitmap {
            captures += 1
            return Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        }

        override fun startRecording(file: File, withAudio: Boolean) {
            if (!bound) throw IllegalStateException("not bound")
            file.writeBytes(byteArrayOf(0, 0, 0, 24))
            recordFile = file
            recordedAudio = withAudio
        }

        override suspend fun stopRecording(): Long = 1_500

        override fun setTorch(on: Boolean) = Unit

        override fun setZoom(ratio: Float) = Unit
    }

    private class InsertProbe : ContentProvider() {
        var inserts = 0
        override fun onCreate(): Boolean = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts += 1
            return uri
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
    }
}
