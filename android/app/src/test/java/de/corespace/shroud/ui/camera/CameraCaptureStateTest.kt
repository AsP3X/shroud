package de.corespace.shroud.ui.camera

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.lifecycle.LifecycleOwner
import de.corespace.shroud.core.media.ImageEncodeException
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.capture.CameraBindState
import de.corespace.shroud.core.media.capture.CameraCapture
import de.corespace.shroud.core.media.capture.PickedMovieFile
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.media.HarnessRule
import de.corespace.shroud.ui.media.PickedMovie
import de.corespace.shroud.ui.media.PickedPhoto
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
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
 * clip out as a `PickedMovie` owning its file, flips, lights the torch only on a lens with a flash
 * unit, follows K9's `bindState` (waits through a slow bind, says "No camera available" only on a
 * failed or lens-less bind, never on a state left by an earlier bind), opens a front-only phone on its
 * front lens, shows the designed denied state, and unbinds when it leaves.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: these screens run on fakes, and ShroudApplication would start the whole
// container (network, push) for every test, which piles up in the one test JVM.
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraCaptureStateTest {
    @get:Rule
    val harness = HarnessRule()

    /**
     * K9 as `ShroudCameraCapture` publishes it: [bind] moves [bindState] to Binding at once and on
     * to [nextBind] (null: CameraX is still starting, the test answers later). The lens flags are
     * not read by the screen any more (gap #14), so reading them fails the test.
     */
    private class FakeCamera : CameraCapture {
        val binds = ArrayList<Pair<Boolean, Boolean>>()
        var unbinds = 0
        var nextBind: CameraBindState? = CameraBindState.Bound(hasFront = true, hasBack = true)

        /** Runs as a bind starts, before [bindState] moves. */
        var onBind: (() -> Unit)? = null

        /** Which lens the last bind asked for; the fake's front lens has no flash. */
        var boundFront = false
        override val hasFrontCamera: Boolean get() = throw AssertionError("the screen reads bindState, not hasFrontCamera")
        override val hasBackCamera: Boolean get() = throw AssertionError("the screen reads bindState, not hasBackCamera")
        override val bindState = MutableStateFlow<CameraBindState>(CameraBindState.Unbound)
        override var zoomRange: ClosedFloatingPointRange<Float>? = 1f..8f
        var flashOnBack = true
        override val hasFlashUnit: Boolean get() = bindState.value is CameraBindState.Bound && flashOnBack && !boundFront
        var photo: MediaImageSource = MediaImageSource.ContentUri(Uri.parse("content://de.corespace.shroud.cache/cache/shroud-cam-1.jpg"))
        var photoError: Exception? = null
        var recordedWithAudio: Boolean? = null
        var clip: PickedMovieFile? = PickedMovieFile(File("/data/cache/shroud-cam-2.mp4"), 3_000)
        val torch = ArrayList<Boolean>()
        val zoom = ArrayList<Float>()

        override fun bind(owner: LifecycleOwner, preview: Preview.SurfaceProvider, front: Boolean, video: Boolean) {
            onBind?.invoke()
            binds += front to video
            boundFront = front
            bindState.value = CameraBindState.Binding
            nextBind?.let { bindState.value = it }
        }

        override fun unbind() {
            unbinds++
            bindState.value = CameraBindState.Unbound
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
        val ui = harness.compose {
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
        val ui = harness.compose {
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
        val ui = harness.compose {
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
        val ui = harness.compose {
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
        val ui = harness.compose {
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = { closes++ }, services = services)
        }
        ui.idle()
        ui.click("Torch off")
        assertEquals(listOf(true), services.camera.torch)
        assertNotNull(ui.described("Torch on"))
        ui.click("Switch camera")
        assertEquals(true to false, services.camera.binds.last())
        // The fake's front lens has no flash unit: no torch control.
        assertTrue(ui.nodes().none { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { d -> d.startsWith("Torch") } })
        ui.click("Close camera")
        assertEquals(1, closes)
    }

    @Test
    fun torchShowsOnlyForALensWithAFlashUnit() {
        grantCamera()
        val services = FakeServices().apply { camera.flashOnBack = false }
        val ui = harness.compose {
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = {}, services = services)
        }
        ui.idle()
        assertFalse(SemanticsProperties.Disabled in ui.described("Take photo").config)
        assertTrue(ui.describe(), ui.nodes().none { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { d -> d.startsWith("Torch") } })
    }

    @Test
    fun aSlowBindNeverSaysThereIsNoCamera() {
        grantCamera()
        val services = FakeServices().apply { camera.nextBind = null }
        val ui = harness.compose {
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = {}, services = services)
        }
        assertEquals(CameraBindState.Binding, services.camera.bindState.value)
        // Past the old 4 s grace, in real time (Compose's UI dispatcher has no virtual delay): still waiting, nothing said.
        repeat(45) {
            Thread.sleep(100)
            ui.idle()
            assertTrue(ui.describe(), ui.nodesWithText("No camera available").isEmpty())
        }
        assertTrue(SemanticsProperties.Disabled in ui.described("Take photo").config)
        assertTrue(SemanticsProperties.Disabled in ui.described("Switch camera").config)
        // CameraX comes up late (an emulator misreporting its front lens took ~6 s).
        services.camera.bindState.value = CameraBindState.Bound(hasFront = true, hasBack = true)
        ui.idle()
        assertTrue(ui.describe(), ui.nodesWithText("No camera available").isEmpty())
        assertFalse(SemanticsProperties.Disabled in ui.described("Take photo").config)
        assertFalse(SemanticsProperties.Disabled in ui.described("Switch camera").config)
    }

    @Test
    fun aFailedBindSaysNoCameraAtOnceAndAnotherModeCanRecover() {
        grantCamera()
        val services = FakeServices().apply { camera.nextBind = CameraBindState.Failed }
        val ui = harness.compose {
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = {}, services = services)
        }
        assertTrue(ui.describe(), ui.nodesWithText("No camera available").isNotEmpty())
        assertTrue(SemanticsProperties.Disabled in ui.described("Take photo").config)
        // VIDEO binds again; this time CameraX answers.
        services.camera.nextBind = CameraBindState.Bound(hasFront = true, hasBack = true)
        ui.clickText("VIDEO")
        assertEquals(false to true, services.camera.binds.last())
        assertTrue(ui.describe(), ui.nodesWithText("No camera available").isEmpty())
        assertFalse(SemanticsProperties.Disabled in ui.described("Start recording").config)
    }

    @Test
    fun aBindWithNoLensSaysNoCamera() {
        grantCamera()
        val services = FakeServices().apply { camera.nextBind = CameraBindState.Bound(hasFront = false, hasBack = false) }
        val ui = harness.compose {
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = {}, services = services)
        }
        assertTrue(ui.describe(), ui.nodesWithText("No camera available").isNotEmpty())
        assertTrue(SemanticsProperties.Disabled in ui.described("Take photo").config)
        assertTrue(ui.nodes().none { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { d -> d.startsWith("Torch") } })
    }

    @Test
    fun aFailureLeftByAnEarlierBindDoesNotFlashOnOpen() {
        grantCamera()
        // The last camera screen's bind failed; this one's is still starting.
        val services = FakeServices().apply {
            camera.bindState.value = CameraBindState.Failed
            camera.nextBind = null
        }
        var view: View? = null
        val shownAtBind = ArrayList<Boolean>()
        services.camera.onBind = {
            // The frame on screen when the screen asks for its bind: still the earlier Failed.
            val owner = (requireNotNull(view) as RootForTest).semanticsOwner
            shownAtBind += owner.getAllSemanticsNodes(mergingEnabled = true).any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "No camera available" }
            }
        }
        val ui = harness.compose {
            view = LocalView.current
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = {}, services = services)
        }
        assertEquals(listOf(false), shownAtBind)
        assertTrue(ui.describe(), ui.nodesWithText("No camera available").isEmpty())
    }

    @Test
    fun aPhoneWithOnlyAFrontCameraOpensOnIt() {
        grantCamera()
        val services = FakeServices().apply { camera.nextBind = CameraBindState.Bound(hasFront = true, hasBack = false) }
        val ui = harness.compose {
            CameraCaptureContent(onPhoto = {}, onVideo = {}, onClose = {}, services = services)
        }
        ui.idle()
        assertEquals(listOf(false to false, true to false), services.camera.binds)
        assertFalse(SemanticsProperties.Disabled in ui.described("Take photo").config)
        assertTrue(SemanticsProperties.Disabled in ui.described("Switch camera").config)
    }

    @Test
    fun aRefusedCameraShowsTheDeniedStateWithSettings() {
        val services = FakeServices()
        val ui = harness.compose {
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
