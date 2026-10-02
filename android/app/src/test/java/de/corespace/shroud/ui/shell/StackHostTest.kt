package de.corespace.shroud.ui.shell

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stack's back handling and the predictive back storyboard (iOS `InteractivePopGestureTests.swift:68-119`,
 * ported per shell-chats §4.8 and §14; design `Predictive Back — Chat to Chats`, I3RNnl):
 *
 * - a pushed screen can be popped by back (the swipe starts on a pushed chat);
 * - a screen that holds its gate off keeps back from the stack; switching it back on hands back over;
 * - nothing to pop at the root: the stack's handler is off and back goes to the system (P12b);
 * - closing a screen that held the gate off leaves the next pushed screen enabled;
 * - a screen pushed above the chat can pop;
 * - a second pop while the first animates is refused;
 * - the swipe previews (scale 1 − 0.1·p, corner 32·p, drift toward the swiped edge) and a cancel
 *   keeps the screen, a release pops it once.
 *
 * The 30 pt edge band and "both recognisers taken over" have no Android counterpart (§4.8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp")
class StackHostTest {
    @After
    fun tearDown() = ShellUiHarness.disposeAll()

    // ---- Pure rules ----

    @Test
    fun everyScreenGetsItsOwnGateStartingEnabled() {
        val gates = StackGates<String>()
        val chat = gates.gate("chat")
        assertTrue(chat.allowsBack)
        chat.setEnabled(false)
        assertFalse(gates.gate("chat").allowsBack)
        assertSame(chat, gates.gate("chat"))
        assertTrue("a second screen starts enabled", gates.gate("profile").allowsBack)
    }

    @Test
    fun aScreenRemovedWhileHoldingBackOffLeavesTheNextOneEnabled() {
        val gates = StackGates<Any>()
        gates.gate(0 to "chat").setEnabled(false)
        gates.retain(emptyList())
        assertEquals(0, gates.size)
        val next = gates.gate(0 to "chat")
        assertTrue("iOS viewDidDisappear re-enables (`InteractivePopGesture.swift:61-67`)", next.allowsBack)
    }

    @Test
    fun theStackPopsOnlyWithSomethingToPopNoTransitionAndAnOpenGate() {
        assertFalse("nothing to pop at the root", StackBackRules.canPop(depth = 0, animating = false, topGateEnabled = true))
        assertTrue(StackBackRules.canPop(depth = 1, animating = false, topGateEnabled = true))
        assertFalse("a second pop while the first animates", StackBackRules.canPop(depth = 2, animating = true, topGateEnabled = true))
        assertFalse("the top screen holds back", StackBackRules.canPop(depth = 1, animating = false, topGateEnabled = false))
    }

    @Test
    fun predictiveBackStoryboardFollowsTheFinger() {
        // I3RNnl: scale 1 − 0.1·p, corner radius 32 dp · p, drift sign·(0.05·W − 8 dp)·p.
        assertEquals(1f, PredictiveBackMotion.scale(0f), 0f)
        assertEquals(0.95f, PredictiveBackMotion.scale(0.5f), 1e-6f)
        assertEquals(0.9f, PredictiveBackMotion.scale(1f), 1e-6f)
        assertEquals(0.dp, PredictiveBackMotion.cornerRadius(0f))
        assertEquals(16.dp, PredictiveBackMotion.cornerRadius(0.5f))
        assertEquals(32.dp, PredictiveBackMotion.cornerRadius(1f))
        val density = 2.625f
        val width = 412 * density
        // From the left edge the screen moves right: (20.6 − 8) dp at full progress on a 412 dp phone.
        assertEquals(12.6f * density, PredictiveBackMotion.translationX(1f, 1f, width, density), 0.01f)
        assertEquals(-6.3f * density, PredictiveBackMotion.translationX(0.5f, -1f, width, density), 0.01f)
        assertEquals(1f, PredictiveBackMotion.edgeSign(BackEventCompat.EDGE_LEFT), 0f)
        assertEquals(-1f, PredictiveBackMotion.edgeSign(BackEventCompat.EDGE_RIGHT), 0f)
    }

    // ---- The host on a back dispatcher ----

    private class Stack(reduceMotion: Boolean = true) {
        val routes = mutableStateListOf<String>()
        val gates = mutableMapOf<String, BackGate>()
        var pops = 0
        val ui = ShellUiHarness(reduceMotion) {
            StackHost(
                routes = routes.toList(),
                onPop = {
                    pops++
                    routes.removeAt(routes.lastIndex)
                },
                root = { Box(Modifier.fillMaxSize().semantics { contentDescription = "root" }) },
                entry = { route ->
                    val gate = LocalPushedBackGate.current
                    SideEffect { gates[route] = gate }
                    Box(Modifier.fillMaxSize().semantics { contentDescription = route })
                },
            )
        }
        val dispatcher: OnBackPressedDispatcher get() = ui.activity.onBackPressedDispatcher

        fun push(route: String) {
            routes.add(route)
            ui.idle()
        }

        fun back() {
            dispatcher.onBackPressed()
            ui.idle()
        }
    }

    @Test
    fun backOnAPushedChatPopsIt() {
        val stack = Stack()
        stack.push("chat")
        assertTrue(stack.dispatcher.hasEnabledCallbacks())
        assertTrue(stack.ui.has("chat"))
        stack.back()
        assertEquals(1, stack.pops)
        assertTrue(stack.routes.isEmpty())
        assertFalse(stack.ui.has("chat"))
        assertTrue(stack.ui.has("root"))
    }

    @Test
    fun nothingToPopAtTheRootLeavesBackToTheSystem() {
        val stack = Stack()
        assertFalse("at a tab root back leaves the app (P12b)", stack.dispatcher.hasEnabledCallbacks())
    }

    @Test
    fun aGateHeldOffKeepsBackFromTheStackUntilItIsOnAgain() {
        val stack = Stack()
        stack.push("chat")
        stack.gates.getValue("chat").setEnabled(false)
        stack.ui.idle()
        assertFalse(stack.dispatcher.hasEnabledCallbacks())
        stack.gates.getValue("chat").setEnabled(true)
        stack.ui.idle()
        assertTrue(stack.dispatcher.hasEnabledCallbacks())
        stack.back()
        assertEquals(1, stack.pops)
    }

    @Test
    fun closingAChatThatHeldTheGateOffLeavesTheNextScreenEnabled() {
        val stack = Stack()
        stack.push("chat")
        val held = stack.gates.getValue("chat")
        held.setEnabled(false)
        stack.ui.idle()
        // The chat goes another way (its own close button), still holding back off.
        stack.routes.clear()
        stack.ui.idle()
        stack.push("chat")
        assertNotSame("the new chat is a new screen with its own gate", held, stack.gates.getValue("chat"))
        assertTrue(stack.dispatcher.hasEnabledCallbacks())
    }

    @Test
    fun aScreenPushedAboveTheChatCanPop() {
        val stack = Stack()
        stack.push("chat")
        stack.push("profile")
        stack.back()
        assertEquals(listOf("chat"), stack.routes.toList())
        assertTrue(stack.ui.has("chat"))
        assertTrue(stack.dispatcher.hasEnabledCallbacks())
    }

    @Test
    fun noSecondPopWhileThePushStillAnimates() {
        val stack = Stack(reduceMotion = false)
        stack.routes.add("chat")
        stack.ui.idle(48)
        assertFalse("the push is still sliding in", stack.dispatcher.hasEnabledCallbacks())
        stack.ui.idle(1_000)
        assertTrue(stack.dispatcher.hasEnabledCallbacks())
    }

    @Test
    fun aSwipePreviewsTheListUnderTheShrinkingChatAndACancelKeepsIt() {
        val stack = Stack(reduceMotion = false)
        stack.push("chat")
        stack.ui.idle(1_000)
        val full = stack.ui.boundsDp(stack.ui.node("chat"))
        stack.dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
        stack.dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 400f, 0.5f, BackEventCompat.EDGE_LEFT))
        stack.ui.idle(48)
        val preview = stack.ui.boundsDp(stack.ui.node("chat"))
        // 95 % of the width, drifted (0.05·412 − 8)·0.5 = 6.3 dp toward the right.
        assertEquals(full.width * 0.95f, preview.width, 1f)
        assertEquals(full.left + full.width * 0.025f + 6.3f, preview.left, 1f)
        assertTrue("the list shows under the chat", stack.ui.has("root"))
        stack.dispatcher.dispatchOnBackCancelled()
        stack.ui.idle(1_000)
        assertEquals(0, stack.pops)
        assertEquals(full.width, stack.ui.boundsDp(stack.ui.node("chat")).width, 0.5f)
        assertTrue(stack.dispatcher.hasEnabledCallbacks())
    }

    @Test
    fun aReleasedSwipePopsOnce() {
        val stack = Stack(reduceMotion = false)
        stack.push("chat")
        stack.ui.idle(1_000)
        stack.dispatcher.dispatchOnBackStarted(BackEventCompat(400f, 400f, 0f, BackEventCompat.EDGE_RIGHT))
        stack.dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, 0.8f, BackEventCompat.EDGE_RIGHT))
        stack.ui.idle(48)
        stack.dispatcher.onBackPressed()
        stack.ui.idle(1_000)
        assertEquals(1, stack.pops)
        assertTrue(stack.routes.isEmpty())
        assertFalse(stack.ui.has("chat"))
        assertFalse(stack.dispatcher.hasEnabledCallbacks())
    }
}
