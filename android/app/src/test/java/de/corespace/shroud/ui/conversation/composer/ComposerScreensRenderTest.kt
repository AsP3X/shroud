package de.corespace.shroud.ui.conversation.composer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.links.LinkPreviewDraft
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.library.LibraryAccess
import de.corespace.shroud.core.media.library.LibraryItem
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.voice.VoiceRecorder
import de.corespace.shroud.ui.conversation.attach.ChatAttachOption
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.PEER
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.message
import de.corespace.shroud.ui.media.PickedPhoto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/**
 * Renders every state of the composer (W3-COMPOSER) to a PNG under
 * `android/app/build/outputs/c11-screens/`, for the design pass (C16) to lay next to the frames
 * `hc3Jf` (Conversation), `tBB5Y` (Keyboard Open), `RueI9` (Replying), `aMbL0` / `k81Ye` / `yYKYM`
 * (link strip), `r3Ij1X` (link options), `UM352` (locked recording), `nPQKZ` (Notes Todo bar),
 * `w4lZ1` / `vZsy8` / `Sy9qO` (attach sheet), `u3il8T` (microphone denied), `wFOwa` (dark) and
 * `t2Jue` (360). The finger-down recording bar with its lock pill has no Android frame yet
 * (conversation-compose-media "Not designed"). The pictures are not committed; each test checks
 * what TalkBack reads, which proves the state drew.
 *
 * The bar sits where `ConversationContent` puts it ([ComposerStage]): over the chat background,
 * above the design's 24 dp gesture bar, under its 52 dp status bar. 2× density, so a PNG is the
 * 412 × 915 dp frame at 824 × 1830 px. Motion is reduced, so every animation has settled and the
 * recording dot is steady. Software rendering draws no backdrop blur: glass shows its fill only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerScreensRenderTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hosts = ArrayList<ComposerTestHost>()
    private lateinit var services: FakeComposeServices
    private lateinit var controller: ComposeController
    private lateinit var stageHost: StageComposeHost

    @After
    fun tearDown() {
        hosts.forEach { it.close() }
        scope.cancel()
    }

    /** A chat with Jane Cooper (or Notes), set up by [configure] before the stage is drawn. */
    private fun prepare(isNotes: Boolean = false, configure: FakeComposeServices.() -> Unit = {}) {
        services = FakeComposeServices(scope).apply(configure)
        stageHost = StageComposeHost()
        controller = ComposeController(if (isNotes) NOTES_PEER_ID else PEER, isNotes, services, scope, stageHost, if (isNotes) "" else "Jane Cooper")
    }

    private fun stage(dark: Boolean = false, keyboard: Boolean = false): ComposerTestHost {
        if (!::controller.isInitialized) prepare()
        val ui = ComposerTestHost(dark) { ComposerStage(controller, stageHost.toasts, keyboard = keyboard) }
        hosts += ui
        ui.insets(navigationDp = NAV, statusDp = STATUS)
        return ui
    }

    private fun type(ui: ComposerTestHost, text: String) {
        controller.draft.setTextAndPlaceCursorAtEnd(text)
        ui.settle()
    }

    private fun focusField(ui: ComposerTestHost) {
        val field = ui.nodes().first { SemanticsActions.SetText in it.config }
        field.config[SemanticsActions.RequestFocus].action!!.invoke()
        ui.settle()
    }

    /** A take running for [seconds] with a speaking voice's levels. */
    private fun startTake(ui: ComposerTestHost, seconds: Double = 7.32) {
        controller.gesture.pointer(0f, 0f)
        services.recorderState.value = VoiceRecorder.RecState(recording = true, elapsedSeconds = seconds, liveLevels = levels(), levelCount = 44)
        ui.settle()
    }

    private fun levels(): List<Float> = List(44) { i -> (0.25f + 0.6f * kotlin.math.abs(sin(i * 0.55f))).coerceIn(0f, 1f) }

    private fun library(count: Int) = List(count) { i ->
        LibraryItem(Uri.parse("content://media/external/images/media/${100 + i}"), isVideo = false, dateTaken = null, durationMs = null)
    }

    private fun FakeComposeServices.colourfulTiles() {
        val palette = intArrayOf(0xFF8FB8DE.toInt(), 0xFFE9B872.toInt(), 0xFF9BC59D.toInt(), 0xFFD98C8C.toInt(), 0xFFA99BD6.toInt(), 0xFF7FC8C4.toInt())
        thumbnailColor = { uri -> palette[(uri.lastPathSegment?.toIntOrNull() ?: 0) % palette.size] }
    }

    private val question = message(text = "Are we still on for tomorrow?")

    // ---- The row (hc3Jf, tBB5Y, RueI9, wFOwa, t2Jue) ----

    @Test
    fun idle() {
        val ui = stage()
        ui.render("composer-idle")
        assertTrue(ui.has("Attach"))
        assertTrue(ui.has("Record voice message"))
    }

    @Test
    fun idleDark() {
        val ui = stage(dark = true)
        ui.render("composer-idle-dark")
        assertTrue(ui.has("Record voice message"))
    }

    @Test
    fun idleAt360() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-xhdpi")
        val ui = stage()
        type(ui, "See you at eight")
        ui.render("composer-draft-360")
        assertTrue(ui.has("Send"))
    }

    @Test
    fun draftShowsSend() {
        val ui = stage()
        type(ui, "See you at eight")
        ui.render("composer-draft")
        assertTrue(ui.has("Send"))
        assertTrue(!ui.has("Record voice message"))
    }

    @Test
    fun fiveLinesThenTheFieldScrolls() {
        val ui = stage()
        type(ui, List(7) { "Line ${it + 1} of a long message" }.joinToString("\n"))
        ui.render("composer-draft-five-lines")
        assertTrue(ui.has("Send"))
    }

    @Test
    fun keyboardOpen() {
        prepare()
        val ui = stage(keyboard = true)
        focusField(ui)
        ui.insets(imeDp = KEYBOARD, navigationDp = NAV, statusDp = STATUS)
        type(ui, "On my way")
        ui.render("composer-keyboard-open")
        val send = ui.node("Send").boundsInRoot
        // The bar sits on the keyboard: 8 dp of padding and half the 44 dp slot above it.
        assertEquals(ui.root.height - ui.px(KEYBOARD + 8f + 22f), send.center.y, 1.5f)
    }

    @Test
    fun replying() {
        val ui = stage()
        controller.startReply(question)
        type(ui, "Yes, 8 pm at the usual place")
        ui.render("composer-reply")
        assertTrue(ui.has("Cancel reply"))
        assertTrue(ui.has("Reply to Jane Cooper"))
    }

    @Test
    fun replyingDark() {
        val ui = stage(dark = true)
        controller.startReply(question)
        ui.render("composer-reply-dark")
        assertTrue(ui.has("Cancel reply"))
    }

    // ---- The link strip (aMbL0, k81Ye, yYKYM, r3Ij1X) ----

    @Test
    fun linkLoading() {
        prepare { previewGate = CompletableDeferred() }
        val ui = stage()
        type(ui, "Look at this https://www.komoot.com/tour/1398273")
        ui.render("composer-link-loading")
        assertTrue(ui.has("Loading link preview for"))
    }

    @Test
    fun linkReadyAndItsOptions() {
        prepare {
            previews["https://www.komoot.com/tour/1398273"] = LinkPreviewDraft(
                preview = LinkPreview(
                    url = "https://www.komoot.com/tour/1398273",
                    siteName = "komoot",
                    title = "Ridge walk above the lake",
                    summary = "A 14 km loop with 600 m of climbing",
                ),
                largeImage = null,
                largeImageWidth = null,
                largeImageHeight = null,
                prefersLargeImage = false,
            )
        }
        val ui = stage()
        type(ui, "Look at this https://www.komoot.com/tour/1398273")
        ui.render("composer-link-ready")
        val block = ui.node("Link preview: Ridge walk above the lake, A 14 km loop with 600 m of climbing")
        ui.click(block)
        ui.render("composer-link-options")
        assertTrue(ui.nodesWithText("Show Above Text").isNotEmpty())
        assertTrue(ui.nodesWithText("Remove Preview").isNotEmpty())
    }

    // ---- Recording (UM352; the finger-down bar is not designed yet) ----

    @Test
    fun recordingFingerDown() {
        val ui = stage()
        startTake(ui)
        ui.render("composer-recording")
        assertTrue(ui.has("Recording, 7 seconds. Release to send, slide left to cancel."))
    }

    @Test
    fun recordingSlidingTowardCancel() {
        val ui = stage()
        startTake(ui)
        controller.gesture.pointer(-66f, 0f)
        ui.render("composer-recording-slide-cancel")
        assertEquals(0.6f, controller.gesture.phase.value.cancelProgress, 0.01f)
    }

    @Test
    fun recordingHalfwayToTheLock() {
        val ui = stage()
        startTake(ui)
        controller.gesture.pointer(0f, -38f)
        ui.render("composer-recording-lock-half")
        assertEquals(0.5f, controller.gesture.phase.value.lockProgress, 0.01f)
    }

    @Test
    fun recordingKeepsTheReply() {
        val ui = stage()
        controller.startReply(question)
        ui.settle()
        startTake(ui)
        ui.render("composer-recording-reply")
        assertTrue(ui.has("Cancel reply"))
    }

    @Test
    fun locked() {
        val ui = stage()
        startTake(ui, seconds = 12.08)
        controller.gesture.pointer(0f, -80f)
        ui.render("composer-locked")
        assertTrue(ui.has("Discard recording"))
        assertTrue(ui.has("Send recording"))
    }

    @Test
    fun lockedDark() {
        val ui = stage(dark = true)
        startTake(ui, seconds = 12.08)
        controller.gesture.pointer(0f, -80f)
        ui.render("composer-locked-dark")
        assertTrue(ui.has("Send recording"))
    }

    @Test
    fun aTakeTooShortExplainsTheGesture() {
        prepare { finishOutcome = { null } }
        val ui = stage()
        startTake(ui, seconds = 0.3)
        controller.gesture.pointerUp()
        ui.render("toast-hold-to-record")
        assertTrue(ui.nodesWithText("Hold to record, release to send").isNotEmpty())
    }

    // ---- Notes (nPQKZ) ----

    @Test
    fun notesTodoBar() {
        prepare(isNotes = true)
        val ui = stage()
        type(ui, "Buy oat milk")
        ui.render("notes-todo")
        assertTrue(ui.has("Add as todo"))
    }

    @Test
    fun notesTodoWithoutADraft() {
        prepare(isNotes = true)
        val ui = stage()
        ui.click("Add as todo")
        ui.render("notes-todo-empty")
        assertTrue(ui.nodesWithText("Type a todo, then tap Todo.").isNotEmpty())
    }

    // ---- Permission toasts (u3il8T; P14 Q12) ----

    @Test
    fun microphoneOff() {
        val ui = stage()
        controller.showPermissionToast(MICROPHONE_OFF) {}
        ui.render("toast-microphone-off")
        assertTrue(ui.nodesWithText(MICROPHONE_OFF).isNotEmpty())
        assertTrue(ui.nodesWithText(SETTINGS_ACTION).isNotEmpty())
    }

    @Test
    fun cameraOff() {
        val ui = stage()
        controller.showPermissionToast(CAMERA_OFF) {}
        ui.render("toast-camera-off")
        assertTrue(ui.nodesWithText(CAMERA_OFF).isNotEmpty())
    }

    // ---- The attach sheet (w4lZ1, vZsy8, Sy9qO) ----

    @Test
    fun attachLoading() {
        prepare {
            libraryAccess = LibraryAccess.Full
            recentsGate = CompletableDeferred()
        }
        val ui = stage()
        controller.openAttachSheet()
        ui.render("attach-loading")
        assertTrue(ui.has("Loading recent photos"))
    }

    @Test
    fun attachRecents() {
        prepare {
            libraryAccess = LibraryAccess.Full
            library += library(12)
            colourfulTiles()
        }
        val ui = stage()
        controller.openAttachSheet()
        ui.render("attach-recents")
        assertTrue(ui.has("Recent photo 1 of 12"))
        assertTrue(ui.nodesWithText("All Photos").isNotEmpty())
    }

    @Test
    fun attachRecentsDark() {
        prepare {
            libraryAccess = LibraryAccess.Full
            library += library(12)
            colourfulTiles()
        }
        val ui = stage(dark = true)
        controller.openAttachSheet()
        ui.render("attach-recents-dark")
        assertTrue(ui.has("Recent photo 1 of 12"))
    }

    @Test
    fun attachTileLoadingItsOriginal() {
        prepare {
            libraryAccess = LibraryAccess.Full
            library += library(12)
            colourfulTiles()
            decodeGate = CompletableDeferred()
        }
        val ui = stage()
        controller.openAttachSheet()
        ui.settle()
        ui.click("Recent photo 2 of 12")
        ui.render("attach-tile-loading")
        assertTrue(ui.nodes().any { it.config.getOrNull(SemanticsProperties.StateDescription) == "Loading" })
    }

    @Test
    fun attachEmpty() {
        prepare { libraryAccess = LibraryAccess.Full }
        val ui = stage()
        controller.openAttachSheet()
        ui.render("attach-empty")
        assertTrue(ui.nodesWithText("No recent photos").isNotEmpty())
    }

    @Test
    fun attachDenied() {
        prepare {
            libraryAccess = LibraryAccess.None
            photoAccessAsked = true
        }
        val ui = stage()
        controller.openAttachSheet()
        ui.render("attach-denied")
        assertTrue(ui.nodesWithText("Recent photos are hidden").isNotEmpty())
        assertTrue(ui.nodesWithText("Allow Access").isNotEmpty())
    }

    @Test
    fun attachSelectedPhotosOnly() {
        prepare {
            libraryAccess = LibraryAccess.Partial
            library += library(3)
            colourfulTiles()
        }
        val ui = stage()
        controller.openAttachSheet()
        ui.render("attach-partial")
        assertTrue(ui.nodesWithText("SELECTED PHOTOS").isNotEmpty())
        assertTrue(ui.nodesWithText("Manage").isNotEmpty())
    }

    @Test
    fun attachOptionComingSoon() {
        val ui = stage()
        controller.handleAttach(ChatAttachOption.Location)
        ui.render("toast-coming-soon")
        assertTrue(ui.nodesWithText("Location coming soon").isNotEmpty())
    }

    @Test
    fun attachCameraUnavailable() {
        prepare { camera = false }
        val ui = stage()
        controller.handleAttach(ChatAttachOption.Camera)
        ui.render("toast-camera-unavailable")
        assertTrue(ui.nodesWithText("Camera is not available on this device.").isNotEmpty())
    }

    // ---- "Sending media…" (CV:600-610) ----

    @Test
    fun sendingMedia() {
        prepare { imageGate = CompletableDeferred() }
        // Staged and sent before the stage draws: the compose screen (C12) never composes here.
        val preview = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        controller.presentMediaCompose(listOf(PickedPhoto(preview = preview, source = MediaImageSource.ContentUri(Uri.parse("content://media/picker/0/1")))))
        controller.sendComposedPhotos("", MediaComposeQuality.Original, emptyList())
        assertTrue(controller.isSendingMedia)
        val ui = stage()
        ui.render("sending-media")
        assertNotNull(ui.node(SENDING_MEDIA))
    }

    private companion object {
        /** The design's status bar and gesture bar (eVLXT, hc3Jf). */
        const val STATUS = 52f
        const val NAV = 24f

        /** A Gboard-sized keyboard (tBB5Y). */
        const val KEYBOARD = 336f
    }
}
