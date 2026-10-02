package de.corespace.shroud.ui.conversation.composer

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.conversation.composer.DeviceComposeServices.Companion.PEER
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.math.max

/**
 * The composer's device journeys for C17 (W3-COMPOSER acceptance, plan §2.4; conversation-compose-media
 * §3.6, §3.9, §7, §21.3; `ChatComposerView.swift:305-309`), with the real soft keyboard and real
 * touches over recording stand-ins for the engines ([DeviceComposeServices]):
 *
 * - **IME following:** on every frame the keyboard animates, the bar's height is its content plus the
 *   keyboard as it stood in that frame — it rides the keyboard's own animation, nothing of its own
 *   lags behind (design tBB5Y) — and it settles with the mic right on the keyboard, then back down.
 * - **Record gestures:** a hold records and its release sends; a take started over the keyboard drops
 *   the keyboard and the bar slides ~300 dp down under the still finger without locking itself
 *   (window coordinates); a slide up locks and the locked bar's Send sends; a slide left cancels on the
 *   crossing.
 * - **Attach sheet:** "+" drops the keyboard and raises the sheet with the Recents strip; Cancel, Back
 *   and an option close it ("coming soon" for the stubs); a Recents tile opens the photo compose.
 *
 * The window is edge-to-edge like `MainActivity` (`enableEdgeToEdge()`; the manifest's `adjustResize`
 * does not resize an edge-to-edge window on API 30+, the app takes the insets) — here with
 * `SOFT_INPUT_ADJUST_NOTHING`, so the test activity is never panned instead. Needs a soft keyboard on
 * screen: the project's AVDs run with `hw.keyboard=no`.
 *
 * Owed to C17, which runs it: `ANDROID_SERIAL=emulator-<port> gw :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=de.corespace.shroud.ui.conversation.composer.ComposerDeviceTest`
 * on `shroud_api30` and `shroud_api37` (Gboard; a Samsung keyboard is OPEN-6).
 */
@RunWith(AndroidJUnit4::class)
class ComposerDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var scope: CoroutineScope
    private lateinit var services: DeviceComposeServices
    private lateinit var host: DeviceComposeHost
    private lateinit var controller: ComposeController

    /** One entry per frame the bar was drawn in: the keyboard and navigation insets of that frame and the bar's height. */
    private data class Frame(val ime: Int, val navigation: Int, val height: Int)

    private val frames: MutableList<Frame> = Collections.synchronizedList(ArrayList())

    @Volatile
    private var barHeight = 0

    @After
    fun tearDown() {
        if (::scope.isInitialized) {
            rule.runOnUiThread {
                controller.onLeave(profilePushed = false)
                scope.cancel()
            }
        }
    }

    private fun show() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShroudApplication
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            services = DeviceComposeServices(scope)
            host = DeviceComposeHost()
            controller = ComposeController(PEER, false, services, scope, host, "Jane Cooper")
        }
        rule.setContent {
            // The photo compose screen a Recents tile opens draws its previews with core's renderer.
            CompositionLocalProvider(LocalAppContainer provides app.container) {
                ShroudTheme(dark = false) {
                    OverlayHost { Stage() }
                }
            }
        }
        rule.waitForIdle()
    }

    /** The conversation's bottom as `ConversationContent` lays it out: the bar at the bottom, outside any inset padding. */
    @Composable
    private fun Stage() {
        val density = LocalDensity.current
        val ime = WindowInsets.ime
        val navigation = WindowInsets.navigationBars
        Box(Modifier.fillMaxSize().background(ShroudTheme.colors.backgroundChat)) {
            ConversationComposeHost(
                controller = controller,
                onComposerHeightChanged = {},
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { barHeight = it.height }
                    // Drawn after this frame's layout: the insets read here are the ones the bar was measured with.
                    .drawBehind { frames += Frame(ime.getBottom(density), navigation.getBottom(density), barHeight) },
            )
        }
    }

    private fun dp(value: Float): Float = with(rule.density) { value.dp.toPx() }

    private fun insets(): WindowInsetsCompat? {
        var insets: WindowInsetsCompat? = null
        rule.runOnUiThread { insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView) }
        return insets
    }

    private fun keyboardVisible(): Boolean = insets()?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun keyboardTarget(): Int = insets()?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    /** The keyboard finished moving: the last drawn frame shows it where the window says it is. */
    private fun settled(): Boolean {
        val last = synchronized(frames) { frames.lastOrNull() } ?: return false
        return last.ime == (if (keyboardVisible()) keyboardTarget() else 0)
    }

    private fun openKeyboard() {
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitUntil("a soft keyboard came up (the AVD needs hw.keyboard=no)", KEYBOARD_TIMEOUT_MS) { keyboardVisible() && settled() }
    }

    private fun closeKeyboard() {
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        rule.waitUntil("the keyboard went down", KEYBOARD_TIMEOUT_MS) { !keyboardVisible() && settled() }
    }

    private fun bounds(description: String): Rect =
        rule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().first().boundsInRoot

    private fun mic(): Rect = bounds("Record voice message")

    private fun rootHeight(): Float = rule.onRoot().fetchSemanticsNode().boundsInRoot.height

    private fun phase(): ComposerPhase = controller.gesture.phase.value

    /** Every frame drawn since [from]: the bar's height is its content plus the bottom inset of that very frame. */
    private fun assertRodeEveryFrame(from: Int, content: Int, label: String): List<Frame> {
        val drawn = synchronized(frames) { frames.drop(from) }
        assertTrue("$label: frames were drawn", drawn.isNotEmpty())
        drawn.forEachIndexed { index, frame ->
            assertNear("$label, frame $index of ${drawn.size}: $frame", content + max(frame.ime, frame.navigation), frame.height, 1)
        }
        return drawn
    }

    private fun assertNear(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$message: expected $expected ± $tolerance, was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    // ---- IME following ----

    @Test
    fun theBarRidesTheKeyboardFrameByFrameUpAndDown() {
        show()
        val rest = synchronized(frames) { frames.last() }
        val content = rest.height - max(rest.ime, rest.navigation)
        assertNear("the idle bar: 4 + 44 + 8 dp", dp(56f).toInt(), content, 2)

        val upFrom = frames.size
        openKeyboard()
        val target = keyboardTarget()
        val up = assertRodeEveryFrame(upFrom, content, "rising")
        val between = up.map { it.ime }.filter { it > rest.navigation && it < target }.distinct()
        assertTrue("the keyboard rose over several frames and the bar followed each (${between.size} in between)", between.size >= MIN_ANIMATED_FRAMES)
        // At rest the mic sits right on the keyboard: 8 dp of padding and half the 44 dp slot (design tBB5Y).
        assertEquals(rootHeight() - target - dp(8f + 22f), mic().center.y, dp(1.5f))

        val downFrom = frames.size
        closeKeyboard()
        val down = assertRodeEveryFrame(downFrom, content, "falling")
        assertTrue(
            "the keyboard fell over several frames",
            down.map { it.ime }.filter { it > rest.navigation && it < target }.distinct().size >= MIN_ANIMATED_FRAMES,
        )
        assertNear("back at rest", rest.height, synchronized(frames) { frames.last() }.height, 1)
    }

    // ---- Record gestures ----

    @Test
    fun aHoldRecordsAndItsReleaseSends() {
        show()
        val finger = mic().center
        rule.onRoot().performTouchInput { down(finger) }
        rule.waitUntil("the take started", GESTURE_TIMEOUT_MS) { phase() is ComposerPhase.Recording }
        assertTrue(rule.onAllNodes(hasContentDescription("Release to send, slide left to cancel.", substring = true)).fetchSemanticsNodes().isNotEmpty())
        rule.onRoot().performTouchInput {
            advanceEventTime(HOLD_MS)
            up()
        }
        rule.waitForIdle()
        assertEquals(ComposerPhase.Idle, phase())
        assertEquals(1, services.voices)
        assertEquals(0, services.cancels)
        assertEquals(listOf(true, false), services.recordingSignals)
    }

    @Test
    fun aTakeStartedOverTheKeyboardDropsItAndNeverLocksItself() {
        show()
        openKeyboard()
        val finger = mic().center
        rule.onRoot().performTouchInput { down(finger) }
        rule.waitUntil("the take started", GESTURE_TIMEOUT_MS) { phase() is ComposerPhase.Recording }

        // The field leaves with the take and the keyboard goes: the bar slides down under the still finger.
        rule.waitUntil("the keyboard went down with the take", KEYBOARD_TIMEOUT_MS) { !keyboardVisible() && settled() }
        val slid = mic().center.y - finger.y
        assertTrue("the mic moved ${slid}px down under the finger", slid > dp(150f))

        // A 1 px tremble: in the mic's own coordinates this would read as a slide far up past the lock.
        rule.onRoot().performTouchInput { moveTo(finger + Offset(0f, 1f)) }
        rule.waitForIdle()
        val phase = phase()
        assertTrue("still recording, not locked: $phase", phase is ComposerPhase.Recording)
        assertEquals(0f, phase.lockProgress, 0.02f)

        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(ComposerPhase.Idle, phase())
        assertEquals(1, services.voices)
    }

    @Test
    fun aSlideUpLocksTheTakeAndTheLockedBarSendsIt() {
        show()
        val finger = mic().center
        rule.onRoot().performTouchInput { down(finger) }
        rule.waitUntil("the take started", GESTURE_TIMEOUT_MS) { phase() is ComposerPhase.Recording }
        rule.onRoot().performTouchInput { moveTo(finger + Offset(0f, -dp(38f))) }
        rule.waitForIdle()
        assertEquals(0.5f, phase().lockProgress, 0.05f)
        rule.onRoot().performTouchInput { moveTo(finger + Offset(0f, -dp(90f))) }
        rule.waitForIdle()
        assertEquals(ComposerPhase.Locked, phase())

        // The finger is free once locked: lifting it keeps the take.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(ComposerPhase.Locked, phase())
        assertEquals(0, services.voices)

        rule.onNodeWithContentDescription("Send recording").performClick()
        rule.waitForIdle()
        assertEquals(ComposerPhase.Idle, phase())
        assertEquals(1, services.voices)
    }

    @Test
    fun aSlideLeftCancelsOnTheCrossing() {
        show()
        val finger = mic().center
        rule.onRoot().performTouchInput { down(finger) }
        rule.waitUntil("the take started", GESTURE_TIMEOUT_MS) { phase() is ComposerPhase.Recording }
        rule.onRoot().performTouchInput { moveTo(finger + Offset(-dp(55f), 0f)) }
        rule.waitForIdle()
        assertEquals(0.5f, phase().cancelProgress, 0.05f)
        rule.onRoot().performTouchInput { moveTo(finger + Offset(-dp(120f), 0f)) }
        rule.waitForIdle()
        // Telegram cancels the moment the finger crosses, before the release.
        assertEquals(ComposerPhase.Idle, phase())
        assertEquals(1, services.cancels)
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0, services.voices)
        assertEquals(listOf(true, false), services.recordingSignals)
    }

    // ---- Attach sheet ----

    @Test
    fun theAttachSheetDropsTheKeyboardShowsRecentsAndClosesEveryWay() {
        show()
        openKeyboard()
        rule.onNodeWithContentDescription("Attach").performClick()
        rule.waitUntil("the keyboard went down for the sheet", KEYBOARD_TIMEOUT_MS) { !keyboardVisible() }
        rule.waitUntil("the Recents strip filled", GESTURE_TIMEOUT_MS) { tileCount() == services.library.size }
        rule.onNodeWithText("RECENTS").assertExists()
        rule.onNodeWithText("All Photos").assertExists()

        // Cancel.
        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil("Cancel closed the sheet", GESTURE_TIMEOUT_MS) { tileCount() == 0 }
        assertFalse(controller.showsAttachSheet)

        // Back.
        rule.onNodeWithContentDescription("Attach").performClick()
        rule.waitUntil("the sheet came back", GESTURE_TIMEOUT_MS) { tileCount() == services.library.size }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil("Back closed the sheet", GESTURE_TIMEOUT_MS) { tileCount() == 0 }

        // An option that is not built yet: the sheet closes and says so.
        rule.onNodeWithContentDescription("Attach").performClick()
        rule.waitUntil("the sheet came back", GESTURE_TIMEOUT_MS) { tileCount() == services.library.size }
        rule.onNodeWithText("Location").performClick()
        rule.waitUntil("the sheet closed", GESTURE_TIMEOUT_MS) { tileCount() == 0 }
        assertEquals(listOf("Location coming soon"), host.toasts)
    }

    @Test
    fun aRecentsTileOpensThePhotoCompose() {
        show()
        rule.onNodeWithContentDescription("Attach").performClick()
        rule.waitUntil("the Recents strip filled", GESTURE_TIMEOUT_MS) { tileCount() == services.library.size }
        rule.onNodeWithContentDescription("Recent photo 2 of 3").performClick()
        rule.waitUntil("the photo compose opened", GESTURE_TIMEOUT_MS) { composedPhotos() == 1 }
        assertFalse(controller.showsAttachSheet)
        assertEquals(listOf("decode"), services.log)
        rule.runOnUiThread { controller.cancelMediaCompose() }
        rule.waitForIdle()
        assertEquals(0, composedPhotos())
    }

    private fun tileCount(): Int = rule.onAllNodes(hasContentDescription("Recent photo", substring = true)).fetchSemanticsNodes().size

    private fun composedPhotos(): Int {
        var count = 0
        rule.runOnUiThread { count = controller.composeDraft?.photos?.size ?: 0 }
        return count
    }

    private companion object {
        /** A keyboard's show or hide animation, generously (Gboard takes ~0.3 s; a cold IME more). */
        const val KEYBOARD_TIMEOUT_MS = 5_000L
        const val GESTURE_TIMEOUT_MS = 3_000L
        const val HOLD_MS = 600L

        /** In-between keyboard positions that prove the bar followed an animation, not a jump. */
        const val MIN_ANIMATED_FRAMES = 3
    }
}
