package de.corespace.shroud.ui.components

import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Toast timing (`ios/shroud/ShroudUI/Components/ToastBanner.swift:8-37`, `:105-111`;
 * shell-chats §10.15): 1.8 s for success and info, 2.4 s for failures, 4 s for the action toast;
 * a new toast restarts the timer and an old timer never clears its successor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToastTest {
    @Test
    fun durationsMatchToastBanner() {
        assertEquals(1_800L, Toast("Copied").durationMillis)
        assertEquals(Toast.Style.Success, Toast("Copied").style)
        assertEquals(1_800L, Toast.success("Copied").durationMillis)
        assertEquals(1_800L, Toast.info("Drawing coming soon").durationMillis)
        assertEquals(Toast.Style.Info, Toast.info("x").style)
        assertEquals(2_400L, Toast.failure("Could not reach the server.").durationMillis)
        assertEquals(Toast.Style.Failure, Toast.failure("x").style)
        assertEquals(2_400L, Toast.info("Signed out", 2_400).durationMillis)
    }

    @Test
    fun actionToastStaysFourSecondsAndCarriesItsButton() {
        var opened = 0
        val toast = Toast.withAction("Microphone access is off", "Settings", onAction = { opened++ })
        assertEquals(4_000L, toast.durationMillis)
        val action = requireNotNull(toast.action)
        assertEquals("Settings", action.title)
        action.onAction()
        assertEquals(1, opened)
        assertNull(Toast.success("x").action)
    }

    @Test
    fun toastClearsAfterItsDuration() = runTest {
        val state = ToastState()
        state.show(Toast.success("Copied"))
        val timer = launch { state.runTimer() }
        advanceTimeBy(1_799)
        runCurrent()
        assertNotNull(state.current)
        advanceTimeBy(1)
        runCurrent()
        assertNull(state.current)
        timer.join()
    }

    @Test
    fun failureStaysLonger() = runTest {
        val state = ToastState()
        state.show(Toast.failure("Couldn’t copy phrase — try again"))
        launch { state.runTimer() }
        advanceTimeBy(2_000)
        runCurrent()
        assertNotNull(state.current)
        advanceTimeBy(400)
        runCurrent()
        assertNull(state.current)
    }

    @Test
    fun aNewToastRestartsTheTimer() = runTest {
        // The host restarts its timer on every show (`.task(id: toast?.id)`): the first is cancelled.
        val state = ToastState()
        state.show(Toast.success("Copied"))
        val first = launch { state.runTimer() }
        advanceTimeBy(1_000)
        val second = Toast.success("Copied") // equal text: still a new toast with its own timer
        state.show(second)
        first.cancel()
        launch { state.runTimer() }
        advanceTimeBy(1_000)
        runCurrent()
        assertSame(second, state.current) // 2.0 s after the first show, 1.0 s after the second
        advanceTimeBy(800)
        runCurrent()
        assertNull(state.current)
    }

    @Test
    fun anOldTimerNeverClearsItsSuccessor() = runTest {
        val state = ToastState()
        state.show(Toast.success("Chat deleted"))
        launch { state.runTimer() } // not cancelled on purpose
        advanceTimeBy(1_000)
        val next = Toast.failure("Could not delete the chat.")
        state.show(next)
        launch { state.runTimer() }
        advanceTimeBy(800)
        runCurrent()
        assertSame(next, state.current) // the first timer fired at 1.8 s and left the successor alone
        advanceTimeBy(1_600)
        runCurrent()
        assertNull(state.current) // 2.4 s after it was shown
    }

    @Test
    fun dismissClearsAtOnceAndALateTimerIsHarmless() = runTest {
        val state = ToastState()
        state.show(Toast.info("Saved"))
        val generation = state.generation
        state.dismiss()
        assertNull(state.current)
        state.expire(generation)
        assertNull(state.current)
        state.show(Toast.info("Again"))
        state.expire(generation)
        assertNotNull(state.current)
    }

    @Test
    fun runTimerWithoutAToastReturns() = runTest {
        val state = ToastState()
        state.runTimer()
        assertNull(state.current)
    }

    @Test
    fun bottomPaddingLiftsOverTheTabBarOrTheSystemInset() {
        // 20 + (tab bar clearance when > 0, else the navigation bar / keyboard) + the host's inset.
        assertEquals(44.dp, toastBottomPadding(tabBarClearance = 0.dp, systemBottom = 24.dp, bottomInset = 0.dp))
        assertEquals(104.dp, toastBottomPadding(tabBarClearance = 84.dp, systemBottom = 24.dp, bottomInset = 0.dp))
        assertEquals(100.dp, toastBottomPadding(tabBarClearance = 0.dp, systemBottom = 24.dp, bottomInset = 56.dp))
        assertEquals(20.dp, TOAST_BOTTOM_GAP)
    }

    @Test
    fun accessibilityTimeoutDefaultsToTheDuration() {
        assertEquals(1_800L, recommendedToastTimeout(Toast.success("x"), accessibility = null))
        assertEquals(
            AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT,
            toastContentFlags(Toast.success("x")),
        )
        assertEquals(
            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
            toastContentFlags(Toast.withAction("Camera access is off", "Settings", {})),
        )
    }
}
