package de.corespace.shroud.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.LifecycleOwner
import de.corespace.shroud.core.media.ImageEncodeException
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.capture.CameraCapture
import de.corespace.shroud.core.media.capture.PickedMovieFile
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.media.PickedMovie
import de.corespace.shroud.ui.media.PickedPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The camera screen on a scripted `CameraCapture` (K9; conversation-compose-media §8.3, P8): it
 * binds the back lens for photos, hands a capture out as a `PickedPhoto` on the FileProvider URI,
 * asks for the microphone only when switching to video and records silently without it, hands a
 * clip out as a `PickedMovie` owning its file, flips and lights the torch, says when there is no
 * camera, shows the designed denied state, and unbinds when it leaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraCaptureStateTest {
    private class FakeCamera : CameraCapture {
        val binds = ArrayList<Pair<Boolean, Boolean>>()
        var unbinds = 0
        override var hasFrontCamera = true
        override var hasBackCamera = true
        var photo: MediaImageSource = MediaImageSource.ContentUri(Uri.parse("content://de.corespace.shroud.cache/cache/shroud-cam-1.jpg"))
        var photoError: Exception? = null
        var recordedWithAudio: Boolean? = null
        var clip: PickedMovieFile? = PickedMovieFile(File("/data/cache/shroud-cam-2.mp4"), 3_000)
        val torch = ArrayList<Boolean>()
        val zoom = ArrayList<Float>()

        override fun bind(owner: LifecycleOwner, preview: Preview.SurfaceProvider, front: Boolean, video: Boolean) {
            binds += front to video
        }

        override fun unbind() {
            unbinds++
        }

        override suspend fun takePhoto(): MediaImageSource {
            photoError?.let { throw it }
            return photo
        }

        override fun startRecording(withAudio: Boolean): Boolean {
            recordedWithAudio = withAudio
            return true
        }

        override suspend fun stopRecording(): PickedMovieFile? = clip

        override fun setTorch(on: Boolean) {
            torch += on
        }

        override fun setZoom(ratio: Float) {
            zoom += ratio
        }
    }

    private class FakeServices(override val camera: FakeCamera = FakeCamera()) : CameraServices {
        val decoded = ArrayList<Pair<MediaImageSource, Int>>()

        override suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? {
            decoded += source to maxEdge
            return Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        }
    }

    private fun grantCamera() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    }

    private fun ComposeHarness.described(description: String) =
        nodes().single { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(description) }

    private fun ComposeHarness.click(description: String) {
        described(description).config[SemanticsActions.OnClick].action!!.invoke()
        idle()
    }

    private fun ComposeHarness.clickText(text: String) {
        nodesWithText(text).single { SemanticsActions.OnClick in it.config }.config[SemanticsActions.OnClick].action!!.invoke()
        idle()
    }

    @Test
    fun aPhotoIsHandedOutOnTheCapturesOwnUri() {
        grantCamera()
        val services = FakeServices()
        val photos = ArrayList<PickedPhoto>()
        val ui = ComposeHarness(dark = true) {
            CameraCaptureContent(onPhoto = { photos += it }, onVideo = {}, onClose = {}, services = services)
        }
        ui.idle()
        assertEquals(listOf(false to false), services.camera.binds) // back lens, photo mode
        ui.click("Take photo")
        val photo = photos.single()
        assertEquals(services.camera.photo, photo.source)
        assertEquals(listOf(services.camera.photo to 2048), services.decoded)
        assertEquals(4, photo.preview.width)
    }

    @Test
    fun aLostPhotoSaysSoAndTheCameraStaysOpen() {
        grantCamera()
        val services = FakeServices().apply { camera.photoError = ImageEncodeException("Could not load that photo.") }
        var photos = 0
        val ui = ComposeHarness(dark = true) {
            CameraCaptureContent(onPhoto = { photos++ }, onVideo = {}, onClose = {}, services = services)
        }
        ui.idle()
        ui.click("Take photo")
        assertEquals(0, photos)
        assertTrue(ui.describe(), ui.nodesWithText("Could not load that photo.").isNotEmpty())
        assertNotNull(ui.described("Take photo"))
    }

    @Test
    fun videoAsksForTheMicrophoneAndRecordsSilentlyWithoutIt() {
        grantCamera()
        val services = FakeServices()
        val clips = ArrayList<PickedMovie>()
        val ui = ComposeHarness(dark = true) {
            CameraCaptureContent(onPhoto = {}, onVideo = { clips += it }, onClose = {}, services = services)
        }
        ui.idle()
        ui.clickText("VIDEO")
        // P8: the microphone is asked for only now.
        assertEquals(Manifest.permission.RECORD_AUDIO, shadowOf(ui.activity).lastRequestedPermission?.requestedPermissions?.single())
        assertEquals(false to true, services.camera.binds.last())
        assertTrue(ui.describe(), ui.nodesWithText("NO SOUND").isNotEmpty())
        ui.click("Start recording")
        assertEquals(false, services.camera.recordedWithAudio)
        assertTrue(ui.nodesWithText("0:00").isNotEmpty())
        // No flip and no mode switch mid-clip.
        assertTrue(ui.nodesWithText("PHOTO").isEmpty())
        ui.click("Stop recording")
        val clip = clips.single()
        assertEquals(Uri.fromFile(File("/data/cache/shroud-cam-2.mp4")), clip.uri)
    }

    @Test
    fun aRecordingThatIsLostSaysSo() {
        grantCamera()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val services = FakeServices().apply { camera.clip = null }
        val ui = ComposeHarness(dark = true) {
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = {}, services = services)
        }
        ui.idle()
        ui.clickText("VIDEO")
        assertTrue(ui.nodesWithText("NO SOUND").isEmpty())
        ui.click("Start recording")
        assertEquals(true, services.camera.recordedWithAudio)
        ui.click("Stop recording")
        assertTrue(ui.describe(), ui.nodesWithText("Could not load that video.").isNotEmpty())
    }

    @Test
    fun torchFlipAndClose() {
        grantCamera()
        val services = FakeServices()
        var closes = 0
        val ui = ComposeHarness(dark = true) {
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = { closes++ }, services = services)
        }
        ui.idle()
        ui.click("Torch off")
        assertEquals(listOf(true), services.camera.torch)
        assertNotNull(ui.described("Torch on"))
        ui.click("Switch camera")
        assertEquals(true to false, services.camera.binds.last())
        // The front lens has no torch control.
        assertTrue(ui.nodes().none { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { d -> d.startsWith("Torch") } })
        ui.click("Close camera")
        assertEquals(1, closes)
    }

    @Test
    fun noCameraIsSaidOnlyAfterTheBindHadItsChance() {
        grantCamera()
        val services = FakeServices().apply {
            camera.hasBackCamera = false
            camera.hasFrontCamera = false
        }
        val ui = ComposeHarness(dark = true) {
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = {}, services = services)
        }
        ui.idle()
        assertTrue(ui.nodesWithText("No camera available").isEmpty())
        repeat(9) { ui.idle() }
        assertTrue(ui.describe(), ui.nodesWithText("No camera available").isNotEmpty())
        assertTrue(SemanticsProperties.Disabled in ui.described("Take photo").config)
    }

    @Test
    fun aRefusedCameraShowsTheDeniedStateWithSettings() {
        val services = FakeServices()
        val ui = ComposeHarness(dark = true) {
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = {}, services = services)
        }
        ui.idle()
        val request = requireNotNull(shadowOf(ui.activity).lastRequestedPermission)
        assertEquals(listOf(Manifest.permission.CAMERA), request.requestedPermissions.toList())
        // The system answers "Don't allow" without our activity pausing: Android will not ask again.
        ui.activity.activityResultRegistry.dispatchResult(
            request.requestCode,
            Activity.RESULT_OK,
            Intent()
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.CAMERA))
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(PackageManager.PERMISSION_DENIED)),
        )
        ui.idle()
        assertTrue(ui.describe(), ui.nodesWithText("Camera access is off").isNotEmpty())
        assertTrue(services.camera.binds.isEmpty())
        ui.click("Open Settings")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(ui.activity).nextStartedActivity.action)
        assertFalse(ui.nodesWithText("Shroud needs the camera").isEmpty())
    }
}
