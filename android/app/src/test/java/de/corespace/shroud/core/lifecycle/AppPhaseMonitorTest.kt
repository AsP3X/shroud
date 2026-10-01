package de.corespace.shroud.core.lifecycle

import android.app.Activity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scene phase of iOS `RootView.swift:254-298` (`.active` / `.inactive` / `.background`) as
 * shell-chats §3.6 maps it onto our activities' lifecycle (00-plan §1.4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppPhaseMonitorTest {
    private class Tracked : Activity()

    private class Untracked : Activity()

    /** Main-thread messages posted by the monitor, run by [runPosted]. */
    private val posted = ArrayList<Runnable>()

    private val monitor = AppPhaseMonitor(tracks = { it is Tracked }, postToMain = { posted += it })

    private fun runPosted() {
        val queue = posted.toList()
        posted.clear()
        queue.forEach { it.run() }
    }

    /** Every phase published from now on (the collector runs eagerly, so none is conflated away). */
    private fun TestScope.record(): List<AppPhase> {
        val seen = ArrayList<AppPhase>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.phase.toList(seen) }
        return seen
    }

    /** The full launch, as the framework delivers it. */
    private fun AppPhaseMonitor.launch(activity: Any) {
        onStarted(activity)
        onResumed(activity)
    }

    @Test
    fun startsInTheBackground() {
        assertEquals(AppPhase.Background, monitor.phase.value)
        assertFalse(monitor.isResumed)
        assertFalse(monitor.isStarted)
        assertNull(monitor.topActivity)
    }

    @Test
    fun walksActiveInactiveBackground_RootView_254_298() {
        val main = Any()
        monitor.onStarted(main)
        assertEquals(AppPhase.Inactive, monitor.phase.value) // started, not yet resumed (RV:295)
        monitor.onResumed(main)
        assertEquals(AppPhase.Active, monitor.phase.value) // RV:275
        assertTrue(monitor.isResumed)
        monitor.onPaused(main)
        assertEquals(AppPhase.Inactive, monitor.phase.value) // a dialog or BiometricPrompt on top
        assertTrue(monitor.isStarted)
        monitor.onStopped(main, changingConfigurations = false)
        assertEquals(AppPhase.Background, monitor.phase.value) // RV:257, no 700 ms delay
        monitor.onDestroyed(main)
        assertEquals(AppPhase.Background, monitor.phase.value)
    }

    @Test
    fun movingBetweenOurActivitiesNeverPassesThroughBackground() = runTest {
        val main = Any()
        val call = Any()
        monitor.launch(main)
        val seen = record()
        // CallActivity over MainActivity: A pauses, B starts and resumes, A stops.
        monitor.onPaused(main)
        monitor.launch(call)
        monitor.onStopped(main, changingConfigurations = false)
        // And back: B finishes.
        monitor.onPaused(call)
        monitor.launch(main)
        monitor.onStopped(call, changingConfigurations = false)
        monitor.onDestroyed(call)
        assertFalse(AppPhase.Background in seen)
        assertEquals(AppPhase.Active, monitor.phase.value)
    }

    @Test
    fun aConfigurationChangeRecreationStaysInactive() = runTest {
        val old = Any()
        val new = Any()
        monitor.launch(old)
        val seen = record()
        // ActivityThread.handleRelaunchActivity: pause, stop (isChangingConfigurations), destroy,
        // then the new instance starts and resumes — all inside one main-thread message.
        monitor.onPaused(old)
        monitor.onStopped(old, changingConfigurations = true)
        monitor.onDestroyed(old)
        assertEquals(AppPhase.Inactive, monitor.phase.value)
        monitor.launch(new)
        runPosted()
        assertEquals(listOf(AppPhase.Active, AppPhase.Inactive, AppPhase.Active), seen)
    }

    @Test
    fun aRecreationThatNeverStartsEndsInTheBackground() {
        val old = Any()
        monitor.launch(old)
        monitor.onPaused(old)
        monitor.onStopped(old, changingConfigurations = true)
        monitor.onDestroyed(old)
        assertEquals(AppPhase.Inactive, monitor.phase.value)
        // The end of the main-thread message: no new instance came, so the app is in the
        // background (and the auto-lock is not held off).
        runPosted()
        assertEquals(AppPhase.Background, monitor.phase.value)
    }

    @Test
    fun aLateBridgeMessageDoesNotUndoALaterStop() {
        val old = Any()
        val new = Any()
        monitor.launch(old)
        monitor.onPaused(old)
        monitor.onStopped(old, changingConfigurations = true)
        monitor.launch(new)
        // The user leaves before the posted message runs.
        monitor.onPaused(new)
        monitor.onStopped(new, changingConfigurations = false)
        assertEquals(AppPhase.Background, monitor.phase.value)
        runPosted()
        assertEquals(AppPhase.Background, monitor.phase.value)
    }

    @Test
    fun aStopWithoutAStartDoesNotOpenABridge() {
        // A stopped activity relaunched in the background is destroyed without being started.
        val main = Any()
        monitor.onStopped(main, changingConfigurations = true)
        assertEquals(AppPhase.Background, monitor.phase.value)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun resumeWithoutAStartStillCounts() {
        // Installed late (or a missed callback): a resumed activity is a started one.
        val main = Any()
        monitor.onResumed(main)
        assertEquals(AppPhase.Active, monitor.phase.value)
        monitor.onPaused(main)
        assertEquals(AppPhase.Inactive, monitor.phase.value)
    }

    @Test
    fun publishesOnlyChanges() = runTest {
        val main = Any()
        val seen = record()
        monitor.onStarted(main)
        monitor.onStarted(main)
        monitor.onResumed(main)
        monitor.onResumed(main)
        assertEquals(listOf(AppPhase.Background, AppPhase.Inactive, AppPhase.Active), seen)
    }

    @Test
    fun topActivityIsTheLastResumedElseTheLastStarted() {
        val main = Tracked()
        val call = Tracked()
        monitor.onActivityStarted(main)
        assertSame(main, monitor.topActivity)
        monitor.onActivityResumed(main)
        monitor.onActivityStarted(call)
        assertSame(main, monitor.topActivity) // started, but main is still the resumed one
        monitor.onActivityResumed(call)
        assertSame(call, monitor.topActivity)
        monitor.onActivityPaused(call)
        assertSame(main, monitor.topActivity)
        monitor.onActivityPaused(main)
        assertSame(call, monitor.topActivity) // none resumed: the last started
        monitor.onActivityStopped(call)
        monitor.onActivityStopped(main)
        assertNull(monitor.topActivity)
    }

    @Test
    fun ignoresActivitiesItDoesNotTrack() {
        val other = Untracked()
        monitor.onActivityStarted(other)
        monitor.onActivityResumed(other)
        assertEquals(AppPhase.Background, monitor.phase.value)
        assertNull(monitor.topActivity)

        val main = Tracked()
        monitor.onActivityStarted(main)
        monitor.onActivityResumed(main)
        assertEquals(AppPhase.Active, monitor.phase.value)
        monitor.onActivityPaused(other)
        monitor.onActivityStopped(other)
        monitor.onActivityDestroyed(other)
        assertEquals(AppPhase.Active, monitor.phase.value)
    }

    @Test
    fun theActivityCallbacksDriveTheStateMachine() {
        // Through the real callback methods; the stub Activity reports no configuration change.
        val main = Tracked()
        monitor.onActivityCreated(main, null)
        monitor.onActivityStarted(main)
        monitor.onActivityResumed(main)
        assertEquals(AppPhase.Active, monitor.phase.value)
        monitor.onActivityPaused(main)
        monitor.onActivityStopped(main)
        assertEquals(AppPhase.Background, monitor.phase.value)
        assertTrue(posted.isEmpty())
        monitor.onActivityDestroyed(main)
        assertEquals(AppPhase.Background, monitor.phase.value)
    }
}
