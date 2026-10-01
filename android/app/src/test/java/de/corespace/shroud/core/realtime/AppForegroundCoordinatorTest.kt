package de.corespace.shroud.core.realtime

import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.lifecycle.AppPhaseMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scene-phase glue of `RootView.swift:244-300, 330-343` on [AppPhaseMonitor]
 * (api-realtime §11.13, plan §1.4, §1.7.3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppForegroundCoordinatorTest {
    private val monitor = AppPhaseMonitor(postToMain = { it.run() })
    private val activity = Any()

    private class FakeMessaging : MessagingForeground {
        val calls = ArrayList<String>()

        override fun handleAppBecameActive() {
            calls += "active"
        }

        override suspend fun leaveForeground(keepSocket: Boolean) {
            calls += "leave(keepSocket=$keepSocket)"
        }
    }

    private class FakeCalls(override var hasActiveCall: Boolean = false) : ActiveCallProbe

    private val messaging = FakeMessaging()
    private val calls = FakeCalls()
    private var unlocked = true
    private var backgroundHeld = false

    private fun TestScope.coordinator(withMessaging: Boolean = true) = AppForegroundCoordinator(
        messaging = { if (withMessaging) messaging else null },
        calls = { calls },
        isUnlocked = { unlocked },
        phase = monitor,
        scope = backgroundScope,
        backgroundConnectionHeld = { backgroundHeld },
    ).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.toFront() {
        monitor.onStarted(activity)
        monitor.onResumed(activity)
        runCurrent()
    }

    private fun TestScope.toBack() {
        monitor.onPaused(activity)
        monitor.onStopped(activity, changingConfigurations = false)
        runCurrent()
    }

    @Test
    fun frontAndBackDriveMessaging_RootView_254_294() = runTest {
        coordinator()
        // The phase the process starts in is not a departure.
        assertEquals(emptyList<String>(), messaging.calls)
        toFront()
        assertEquals(listOf("active"), messaging.calls)
        toBack()
        assertEquals(listOf("active", "leave(keepSocket=false)"), messaging.calls)
    }

    @Test
    fun inactiveChangesNothingAndEveryReturnCounts() = runTest {
        coordinator()
        toFront()
        // A system dialog over the app (BiometricPrompt, permission sheet).
        monitor.onPaused(activity)
        runCurrent()
        assertEquals(AppPhase.Inactive, monitor.phase.value)
        assertEquals(listOf("active"), messaging.calls)
        monitor.onResumed(activity)
        runCurrent()
        assertEquals(listOf("active", "active"), messaging.calls)
    }

    @Test
    fun lockedChatsLeaveTheSocketAlone() = runTest {
        unlocked = false
        coordinator()
        toFront()
        toBack()
        assertTrue(messaging.calls.isEmpty())
    }

    @Test
    fun aCallKeepsTheSocket_RootView_334() = runTest {
        coordinator()
        toFront()
        calls.hasActiveCall = true
        toBack()
        assertEquals("leave(keepSocket=true)", messaging.calls.last())
    }

    @Test
    fun theBackgroundConnectionKeepsTheSocket() = runTest {
        coordinator()
        toFront()
        backgroundHeld = true
        toBack()
        assertEquals("leave(keepSocket=true)", messaging.calls.last())
    }

    @Test
    fun theBackgroundHolderOfTheRealClientKeepsTheSocket() = runTest {
        // The module's wiring: keepSocket reads RealtimeClient.isHeld(Holder.Background). The base
        // URL is refused before any request, so no network is touched.
        val client = RealtimeClient(
            baseUrl = { "http://api.example.com/api/v1" },
            json = Json,
            baseHttp = OkHttpClient(),
            scope = backgroundScope,
            authOutcomes = { null },
            isForeground = { false },
        )
        AppForegroundCoordinator(
            messaging = { messaging },
            calls = { calls },
            isUnlocked = { unlocked },
            phase = monitor,
            scope = backgroundScope,
            backgroundConnectionHeld = { client.isHeld(RealtimeClient.Holder.Background) },
        ).start()
        runCurrent()
        toFront()
        toBack()
        client.hold(RealtimeClient.Holder.Background, "tok")
        toFront()
        toBack()
        assertEquals(listOf("active", "leave(keepSocket=false)", "active", "leave(keepSocket=true)"), messaging.calls)
    }

    @Test
    fun aCallEndingInTheBackgroundStepsAwayAgain_RootView_244_253() = runTest {
        val coordinator = coordinator()
        toFront()
        calls.hasActiveCall = true
        toBack()
        calls.hasActiveCall = false
        coordinator.onCallEnded()
        runCurrent()
        assertEquals(listOf("active", "leave(keepSocket=true)", "leave(keepSocket=false)"), messaging.calls)
    }

    @Test
    fun theBackgroundConnectionEndingInTheBackgroundStepsAwayAgain() = runTest {
        val coordinator = coordinator()
        toFront()
        backgroundHeld = true
        toBack()
        // Switched off (or its service stopped) while the app is away: the socket may close now.
        backgroundHeld = false
        coordinator.onBackgroundConnectionEnded()
        runCurrent()
        assertEquals(listOf("active", "leave(keepSocket=true)", "leave(keepSocket=false)"), messaging.calls)
        // A call still keeps it.
        toFront()
        backgroundHeld = true
        toBack()
        backgroundHeld = false
        calls.hasActiveCall = true
        coordinator.onBackgroundConnectionEnded()
        runCurrent()
        assertEquals("leave(keepSocket=true)", messaging.calls.last())
    }

    @Test
    fun theBackgroundConnectionEndingInFrontDoesNothing() = runTest {
        val coordinator = coordinator()
        toFront()
        coordinator.onBackgroundConnectionEnded()
        runCurrent()
        assertEquals(listOf("active"), messaging.calls)
    }

    @Test
    fun aCallEndingInFrontOrLockedDoesNothing() = runTest {
        val coordinator = coordinator()
        toFront()
        coordinator.onCallEnded()
        runCurrent()
        assertEquals(listOf("active"), messaging.calls)
        toBack()
        unlocked = false
        coordinator.onCallEnded()
        runCurrent()
        assertEquals(listOf("active", "leave(keepSocket=false)"), messaging.calls)
    }

    @Test
    fun withoutMessagingNothingHappens() = runTest {
        val coordinator = coordinator(withMessaging = false)
        toFront()
        toBack()
        coordinator.onCallEnded()
        runCurrent()
        assertTrue(messaging.calls.isEmpty())
    }

    @Test
    fun startTwiceFollowsThePhaseOnce() = runTest {
        val coordinator = coordinator()
        coordinator.start()
        runCurrent()
        toFront()
        assertEquals(listOf("active"), messaging.calls)
    }

    @Test
    fun startingInFrontCountsAsBecomingActive() = runTest {
        monitor.onStarted(activity)
        monitor.onResumed(activity)
        coordinator()
        assertEquals(listOf("active"), messaging.calls)
    }
}
