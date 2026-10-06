package de.corespace.shroud.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.conversation.DeviceThread.PEER
import de.corespace.shroud.ui.conversation.bubble.LocalBubbleServices
import de.corespace.shroud.ui.conversation.menu.MessageMenuState
import de.corespace.shroud.ui.theme.Motion
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

/**
 * The thread's touch rules on a device (C9 acceptance; conversation-thread §13, §15, §1.4; memories
 * *Hold release fires bubble controls* and *LongPress onChanged blocks scroll*): a 0.45 s hold on a
 * photo, a link, a link preview, a play disc or a reaction chip opens the message menu and its release
 * fires nothing under the finger, while a plain tap on the same point does fire that control (so each
 * case proves its finger landed on the control); a drag starting on a bubble scrolls the thread;
 * swiping right past the threshold replies; Back closes the menu before the chat.
 *
 * Runs the real thread, rows and bubbles over recording fakes (no engines). Owed to C17, which runs
 * it: `ANDROID_SERIAL=emulator-<port> gw :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=de.corespace.shroud.ui.conversation.MessageHoldDeviceTest`.
 * The press points come from the bubbles' metrics (C10); if a tap assertion fails, the point missed
 * its control on that screen, not the rule.
 */
@RunWith(AndroidJUnit4::class)
class MessageHoldDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var scope: CoroutineScope
    private lateinit var backend: DeviceConversationBackend
    private lateinit var compose: DeviceConversationCompose
    private lateinit var bubbles: DeviceBubbleServices
    private lateinit var vm: ConversationViewModel
    private var backs = 0

    @After
    fun tearDown() {
        if (::vm.isInitialized) {
            rule.runOnUiThread {
                vm.close()
                scope.cancel()
            }
        }
    }

    private fun show(messages: List<ChatMessage>) {
        backend = DeviceConversationBackend().apply { threads.value = mapOf(PEER to messages) }
        compose = DeviceConversationCompose()
        rule.runOnUiThread {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            bubbles = DeviceBubbleServices(rule.activity, scope)
            vm = ConversationViewModel(PEER, "ana", backend, scope).also { it.compose = compose }
        }
        rule.setContent {
            ShroudTheme(dark = false) {
                OverlayHost {
                    CompositionLocalProvider(LocalBubbleServices provides bubbles) {
                        ConversationContent(
                            vm = vm,
                            onBack = { backs++ },
                            onOpenProfile = {},
                            coversComposer = false,
                            isRecording = false,
                            isSendingMedia = false,
                            composer = { onHeight ->
                                LaunchedEffect(Unit) { onHeight(COMPOSER_HEIGHT.dp) }
                                Box(Modifier.fillMaxWidth().height(COMPOSER_HEIGHT.dp))
                            },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun dp(value: Float): Float = with(rule.density) { value.dp.toPx() }

    /** The bubble whose TalkBack label contains [text], in root px (the first match: the list's own copy). */
    private fun bubble(text: String): Rect =
        rule.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().first().boundsInRoot

    private fun tap(point: Offset) {
        rule.onRoot().performTouchInput { click(point) }
        rule.waitForIdle()
    }

    /** Down, still for [HOLD_MS], up: the device hold of the iOS tests. */
    private fun hold(point: Offset) {
        rule.onRoot().performTouchInput {
            down(point)
            advanceEventTime(HOLD_MS)
            up()
        }
        rule.waitForIdle()
    }

    private fun menuIsOpen(): Boolean {
        var open = false
        rule.runOnIdle { open = vm.menu.isOpen }
        return open
    }

    /**
     * Runs the menu's drop to its end. The menu stays open while it drops back (`isOpen` covers the
     * close too), and its hand-over waits on a `delay` in the test clock, which [waitForIdle] doesn't
     * advance.
     */
    private fun finishMenuDrop() {
        rule.mainClock.advanceTimeBy(Motion.MENU_DROP_MS + MessageMenuState.HAND_OVER_SLACK_MS + 100)
        rule.waitForIdle()
    }

    private fun closeMenu() {
        rule.runOnIdle { vm.dismissMessageMenu() }
        finishMenuDrop()
        assertFalse(menuIsOpen())
    }

    /** Taps [point] (the control must answer: [fired]), then holds it (the menu opens, nothing else does). */
    private fun holdOpensTheMenuAndItsReleaseFiresNothing(point: Offset, fired: () -> Boolean, clear: () -> Unit) {
        tap(point)
        assertTrue("a tap on the point reaches its control", fired())
        assertFalse(menuIsOpen())
        clear()
        // Past the double-tap window and the tap claim: the hold is a press of its own.
        rule.mainClock.advanceTimeBy(1_000)
        Thread.sleep(500)

        hold(point)
        assertTrue("the hold opened the menu", menuIsOpen())
        assertFalse("the hold's release fired the control under the finger", fired())
        closeMenu()
        assertFalse(fired())
    }

    @Test
    fun holdingAPhotoOpensTheMenuWithoutOpeningThePhoto() {
        show(listOf(DeviceThread.photo(0)))
        val point = bubble("Photo").center
        holdOpensTheMenuAndItsReleaseFiresNothing(point, fired = { "mediaTap" in compose.log }, clear = { compose.log.clear() })
    }

    @Test
    fun holdingALinkOpensTheMenuWithoutOpeningTheLink() {
        show(listOf(DeviceThread.link(0)))
        val frame = bubble("fairly/long")
        val point = Offset(frame.left + dp(60f), frame.top + dp(16f))
        holdOpensTheMenuAndItsReleaseFiresNothing(point, fired = { "openLink" in backend.log }, clear = { backend.log.clear() })
    }

    @Test
    fun holdingALinkPreviewOpensTheMenuWithoutOpeningThePage() {
        show(listOf(DeviceThread.preview(0)))
        val frame = bubble("An example page")
        val point = Offset(frame.left + dp(60f), frame.top + dp(30f))
        holdOpensTheMenuAndItsReleaseFiresNothing(point, fired = { "openLink" in backend.log }, clear = { backend.log.clear() })
    }

    @Test
    fun holdingThePlayDiscOpensTheMenuWithoutPlaying() {
        show(listOf(DeviceThread.voice(0)))
        val frame = bubble("voice message")
        // The 38 dp disc sits 10 dp in from the bubble's leading edge, centred on the row.
        val point = Offset(frame.left + dp(10f + 19f), frame.center.y)
        holdOpensTheMenuAndItsReleaseFiresNothing(point, fired = { "mediaBytes" in bubbles.log }, clear = { bubbles.log.clear() })
    }

    @Test
    fun holdingAReactionChipOpensTheMenuWithoutTogglingTheReaction() {
        show(listOf(DeviceThread.reacted(0)))
        val frame = bubble("nice")
        // The chip row is the bubble's last line, starting at its leading padding.
        val point = Offset(frame.left + dp(30f), frame.bottom - dp(20f))
        holdOpensTheMenuAndItsReleaseFiresNothing(point, fired = { "toggleReaction" in backend.log }, clear = { backend.log.clear() })
    }

    @Test
    fun aDragStartingOnABubbleScrollsTheThread() {
        show(List(40) { DeviceThread.message(it.toLong(), mine = it % 3 == 0, text = "message number $it") })
        val before = bubble("message number 39")
        rule.onRoot().performTouchInput {
            swipe(start = before.center, end = before.center + Offset(0f, dp(300f)), durationMillis = 400)
        }
        rule.waitForIdle()
        val after = rule.onAllNodes(hasContentDescription("message number 39", substring = true)).fetchSemanticsNodes()
        assertTrue("the newest bubble moved down or off screen", after.isEmpty() || after.first().boundsInRoot.top > before.top + dp(100f))
        assertFalse("a drag is no hold", menuIsOpen())
    }

    @Test
    fun swipingRightPastTheThresholdReplies() {
        show(listOf(DeviceThread.message(0, text = "reply to me")))
        val frame = bubble("reply to me")
        rule.onRoot().performTouchInput {
            swipe(start = frame.center, end = frame.center + Offset(dp(30f), 0f), durationMillis = 300)
        }
        rule.waitForIdle()
        assertTrue("short of the incoming row's 60 dp threshold", compose.log.isEmpty())

        rule.onRoot().performTouchInput {
            swipe(start = frame.center, end = frame.center + Offset(dp(90f), 0f), durationMillis = 300)
        }
        rule.waitForIdle()
        assertEquals(listOf("startReply"), compose.log)
        assertFalse(menuIsOpen())
    }

    @Test
    fun backClosesTheMenuBeforeLeavingTheChat() {
        show(listOf(DeviceThread.message(0, text = "hold me")))
        hold(bubble("hold me").center)
        assertTrue(menuIsOpen())
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        finishMenuDrop()
        assertFalse(menuIsOpen())
        assertEquals("the chat stays", 0, backs)
    }

    private companion object {
        const val COMPOSER_HEIGHT = 56
        const val HOLD_MS = 450L
    }
}
