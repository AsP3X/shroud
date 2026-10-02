package de.corespace.shroud.ui.conversation.composer

import android.app.Application
import android.view.MotionEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.PEER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max

/**
 * The composer and the keyboard (W3-COMPOSER acceptance; conversation-compose-media §3.6, §3.9;
 * `ChatComposerView.swift:305-309`): the bar rides the keyboard's inset as it is on every frame —
 * nothing of its own animates — and reports its height with the inset, so the thread and the
 * toasts sit above it; a take drops the keyboard; and the hold-to-record gesture reads the finger
 * in window space, so the bar sliding ~300 dp down under a still finger is no slide up to lock.
 *
 * Insets are dispatched to the Compose view the way the system does for the edge-to-edge,
 * `adjustResize` window; touches are real `MotionEvent`s.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the composer runs on fakes; ShroudApplication would start the whole
// container for every test, which piles up in the one test JVM.
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerKeyboardTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hosts = ArrayList<ComposerTestHost>()
    private lateinit var services: FakeComposeServices
    private lateinit var controller: ComposeController

    @After
    fun tearDown() {
        // The scope first: work it ends (a send's `finally`) writes state the hosts' close must still drain.
        scope.cancel()
        hosts.forEach { it.close() }
    }

    private class RecordingKeyboard : SoftwareKeyboardController {
        var shows = 0
        var hides = 0

        override fun show() {
            shows++
        }

        override fun hide() {
            hides++
        }
    }

    private fun stage(keyboard: SoftwareKeyboardController? = null, onHeight: (Dp) -> Unit = {}): ComposerTestHost {
        services = FakeComposeServices(scope)
        val stageHost = StageComposeHost()
        controller = ComposeController(PEER, false, services, scope, stageHost, "Jane Cooper")
        return ComposerTestHost {
            if (keyboard != null) {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    ComposerStage(controller, stageHost.toasts, onHeight)
                }
            } else {
                ComposerStage(controller, stageHost.toasts, onHeight)
            }
        }.also { hosts += it }
    }

    private fun ComposerTestHost.micCenter(): Offset = node("Record voice message").boundsInRoot.center

    @Test
    fun theBarRidesEveryKeyboardFrameAndReportsItsHeightWithTheInset() {
        val heights = ArrayList<Dp>()
        val ui = stage(onHeight = { heights += it })
        ui.insets(navigationDp = NAV)
        // The idle bar: 4 dp above, the 44 dp trailing slot, 8 dp below (`ChatComposerView.swift:134-136, 266`).
        val content = heights.last().value - NAV
        assertEquals(56f, content, 0.5f)
        val rootHeight = ui.root.height.toFloat()

        // Frames of the keyboard rising: each inset lands as it is, the gesture-bar spacer gives
        // way to the keyboard (design tBB5Y) and the bar's controls sit right on top of it.
        for (ime in listOf(40f, 120f, 200f, 280f, KEYBOARD)) {
            ui.insets(imeDp = ime, navigationDp = NAV)
            val bottom = max(ime, NAV)
            assertEquals("reported height at a $ime dp keyboard", content + bottom, heights.last().value, 0.5f)
            // The mic's centre: 8 dp of padding and half the 44 dp slot above the keyboard.
            assertEquals("mic over a $ime dp keyboard", rootHeight - ui.px(bottom + 8f + 22f), ui.micCenter().y, 1.5f)
        }
        // The toasts lift by the bar alone; ToastHost adds the keyboard itself (CV:208-212).
        assertEquals(content, composerToastInset(heights.last(), KEYBOARD.dp, coversComposer = false).value, 0.5f)

        // And back down as the keyboard leaves.
        ui.insets(navigationDp = NAV)
        assertEquals(content + NAV, heights.last().value, 0.5f)
    }

    @Test
    fun aTakeDropsTheKeyboardWithTheField() {
        val keyboard = RecordingKeyboard()
        val ui = stage(keyboard = keyboard)
        controller.gesture.pointer(0f, 0f)
        ui.settle()
        assertTrue(controller.gesture.phase.value is ComposerPhase.Recording)
        assertEquals(1, keyboard.hides)
        controller.gesture.pointerUp()
        ui.settle()
        assertEquals("going back to idle leaves the keyboard alone", 1, keyboard.hides)
        assertEquals(1, services.voices.size)
    }

    @Test
    fun theBarSlidingDownUnderAStillFingerNeverLocksTheTake() {
        val ui = stage()
        ui.insets(imeDp = KEYBOARD, navigationDp = NAV)
        val finger = ui.micCenter()
        ui.touch(MotionEvent.ACTION_DOWN, finger)
        assertEquals(ComposerPhase.Recording(0f, 0f), controller.gesture.phase.value)

        // The take drops the keyboard: the bar slides 312 dp down while the finger stays put.
        ui.insets(navigationDp = NAV)
        assertEquals(ui.px(KEYBOARD - NAV), ui.micCenter().y - finger.y, 1.5f)
        ui.touch(MotionEvent.ACTION_MOVE, finger + Offset(0f, 1f))
        val phase = controller.gesture.phase.value
        assertTrue("still recording, not locked: $phase", phase is ComposerPhase.Recording)
        assertEquals(0f, phase.lockProgress, 0.01f)

        // The release sends, as any release while recording does.
        ui.touch(MotionEvent.ACTION_UP, finger + Offset(0f, 1f))
        assertEquals(ComposerPhase.Idle, controller.gesture.phase.value)
        assertEquals(1, services.voices.size)
        assertEquals(0, services.cancels)
    }

    @Test
    fun aRealSlideUpStillLocksAfterTheBarMoved() {
        val ui = stage()
        ui.insets(imeDp = KEYBOARD, navigationDp = NAV)
        val finger = ui.micCenter()
        ui.touch(MotionEvent.ACTION_DOWN, finger)
        ui.insets(navigationDp = NAV)
        ui.touch(MotionEvent.ACTION_MOVE, finger + Offset(0f, -ui.px(38f)))
        assertEquals(0.5f, controller.gesture.phase.value.lockProgress, 0.02f)
        ui.touch(MotionEvent.ACTION_MOVE, finger + Offset(0f, -ui.px(80f)))
        assertEquals(ComposerPhase.Locked, controller.gesture.phase.value)
        // Lifting the finger keeps the take; the locked bar's Send finishes it.
        ui.touch(MotionEvent.ACTION_UP, finger + Offset(0f, -ui.px(80f)))
        assertEquals(ComposerPhase.Locked, controller.gesture.phase.value)
        assertTrue(services.voices.isEmpty())
        ui.click("Send recording")
        assertEquals(1, services.voices.size)
        assertEquals(ComposerPhase.Idle, controller.gesture.phase.value)
    }

    @Test
    fun slidingLeftPastTheThresholdCancelsOnTheCrossing() {
        val ui = stage()
        ui.insets(navigationDp = NAV)
        val finger = ui.micCenter()
        ui.touch(MotionEvent.ACTION_DOWN, finger)
        ui.touch(MotionEvent.ACTION_MOVE, finger + Offset(-ui.px(55f), 0f))
        assertEquals(0.5f, controller.gesture.phase.value.cancelProgress, 0.02f)
        assertTrue(ui.has("Recording, 0 seconds. Release to send, slide left to cancel."))
        ui.touch(MotionEvent.ACTION_MOVE, finger + Offset(-ui.px(120f), 0f))
        assertEquals(ComposerPhase.Idle, controller.gesture.phase.value)
        assertEquals(1, services.cancels)
        ui.touch(MotionEvent.ACTION_UP, finger + Offset(-ui.px(120f), 0f))
        assertTrue(services.voices.isEmpty())
        assertEquals(listOf(true, false), services.recordingSignals)
    }

    @Test
    fun aHoldWithoutTheMicrophonePermissionAsksAndRecordsNothing() {
        val ui = stage()
        services.micGranted = false
        ui.insets(navigationDp = NAV)
        val finger = ui.micCenter()
        ui.touch(MotionEvent.ACTION_DOWN, finger)
        ui.touch(MotionEvent.ACTION_UP, finger)
        assertEquals(ComposerPhase.Idle, controller.gesture.phase.value)
        assertEquals(0, services.starts)
        assertTrue(services.recordingSignals.isEmpty())
    }

    private companion object {
        /** The design's gesture bar (tBB5Y draws it as a 24 dp spacer). */
        const val NAV = 24f

        /** A Gboard-sized keyboard. */
        const val KEYBOARD = 336f
    }
}
