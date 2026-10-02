package de.corespace.shroud.ui.conversation.composer

import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold-to-record state machine of the composer (iOS `ChatComposerView`,
 * `ChatComposerView.swift:57-67, 144-158, 303-396`; conversation-compose-media §3.6, §21.2). No iOS
 * unit test covers it; the cases come from the code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerStateTest {
    private class Host : ComposerGesture.Host {
        var start = CompletableDeferred<Boolean>()
        var starts = 0
        var cancels = 0
        var sends = 0
        val haptics = mutableListOf<Haptic>()

        override suspend fun recordStart(): Boolean {
            starts++
            return start.await()
        }

        override fun recordCancel() {
            cancels++
        }

        override fun recordSend() {
            sends++
        }

        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }
    }

    private fun TestScope.gesture(host: Host) = ComposerGesture(this, host)

    /** Touch down and let the start resolve with [started]. */
    private fun TestScope.holdAndStart(gesture: ComposerGesture, host: Host, started: Boolean = true) {
        gesture.pointer(0f, 0f)
        host.start.complete(started)
        advanceUntilIdle()
    }

    @Test
    fun `touch-down starts a take and shows the recording bar once the recorder runs (CCV 313-318, 328-351)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        gesture.pointer(0f, 0f)
        assertTrue(gesture.isStarting)
        assertEquals(ComposerPhase.Idle, gesture.phase.value)
        host.start.complete(true)
        advanceUntilIdle()
        assertEquals(1, host.starts)
        assertFalse(gesture.isStarting)
        assertEquals(ComposerPhase.Recording(0f, 0f), gesture.phase.value)
    }

    @Test
    fun `a refused start drops back to idle (CCV 336-339)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host, started = false)
        assertEquals(ComposerPhase.Idle, gesture.phase.value)
        assertEquals(0, host.cancels)
        assertEquals(0, host.sends)
    }

    @Test
    fun `one start attempt per touch, a new touch tries again (CCV 65-67, 316-318)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host, started = false)
        // The finger is still down and moves: no second attempt.
        gesture.pointer(-5f, -5f)
        gesture.pointer(-10f, 0f)
        advanceUntilIdle()
        assertEquals(1, host.starts)
        gesture.pointerUp()
        host.start = CompletableDeferred()
        holdAndStart(gesture, host)
        assertEquals(2, host.starts)
        assertEquals(ComposerPhase.Recording(0f, 0f), gesture.phase.value)
    }

    @Test
    fun `released while the start is in flight cancels the take once and stays idle (CCV 150-158, 340-346)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        gesture.pointer(0f, 0f)
        gesture.pointerUp()
        assertTrue(gesture.abandonedDuringStart)
        host.start.complete(true)
        advanceUntilIdle()
        assertEquals(1, host.cancels)
        assertEquals(0, host.sends)
        assertFalse(gesture.abandonedDuringStart)
        assertEquals(ComposerPhase.Idle, gesture.phase.value)
    }

    @Test
    fun `a cancelled touch counts as a release (the first microphone prompt, CCV 62-64)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host)
        assertTrue(gesture.micHeld)
        // The pointer coroutine's finally reports the end of the touch the same way.
        gesture.pointerUp()
        assertFalse(gesture.micHeld)
        assertEquals(1, host.sends)
        // A second report of the same end does nothing.
        gesture.pointerUp()
        assertEquals(1, host.sends)
    }

    @Test
    fun `sliding 110 dp left cancels on crossing with the rigid tick, once (CCV 379-382, 387-396)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host)
        gesture.pointer(-60f, 0f)
        val partial = gesture.phase.value as ComposerPhase.Recording
        assertEquals(60f / 110f, partial.cancelProgress, 1e-6f)
        gesture.pointer(-110f, 0f)
        assertEquals(ComposerPhase.Idle, gesture.phase.value)
        assertEquals(1, host.cancels)
        assertEquals(listOf(Haptic.Rigid), host.haptics)
        // The finger keeps going and lifts: nothing restarts, nothing is sent.
        gesture.pointer(-140f, 0f)
        gesture.pointerUp()
        advanceUntilIdle()
        assertEquals(1, host.starts)
        assertEquals(1, host.cancels)
        assertEquals(0, host.sends)
    }

    @Test
    fun `sliding 76 dp up locks with the lock haptic and frees the touch (CCV 372-377)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host)
        gesture.pointer(0f, -38f)
        assertEquals(0.5f, gesture.phase.value.lockProgress, 1e-6f)
        gesture.pointer(0f, -76f)
        assertEquals(ComposerPhase.Locked, gesture.phase.value)
        assertFalse(gesture.attemptedThisTouch)
        assertEquals(listOf(Haptic.LockEngaged), host.haptics)
    }

    @Test
    fun `lock wins when both thresholds are crossed in one move (CCV 372)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host)
        gesture.pointer(-200f, -200f)
        assertEquals(ComposerPhase.Locked, gesture.phase.value)
        assertEquals(0, host.cancels)
    }

    @Test
    fun `releasing while recording sends, unless the hint reached the cancel threshold (CCV 155-157)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host)
        gesture.pointer(-55f, -15.2f)
        val phase = gesture.phase.value as ComposerPhase.Recording
        assertEquals(0.5f, phase.cancelProgress, 1e-6f)
        assertEquals(0.2f, phase.lockProgress, 1e-6f)
        gesture.pointerUp()
        assertEquals(1, host.sends)
        assertEquals(0, host.cancels)
        assertEquals(ComposerPhase.Idle, gesture.phase.value)
        assertTrue(host.haptics.isEmpty())
    }

    @Test
    fun `dragging back toward the mic lowers the progress again`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host)
        gesture.pointer(-100f, -70f)
        gesture.pointer(10f, 10f)
        assertEquals(ComposerPhase.Recording(0f, 0f), gesture.phase.value)
    }

    @Test
    fun `releasing after the lock does nothing, the locked bar's buttons finish (CCV 321-323)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host)
        gesture.pointer(0f, -80f)
        gesture.pointer(-200f, 0f)
        gesture.pointerUp()
        assertEquals(ComposerPhase.Locked, gesture.phase.value)
        assertEquals(0, host.sends)
        assertEquals(0, host.cancels)
        gesture.finish(send = true)
        assertEquals(1, host.sends)
        assertEquals(ComposerPhase.Idle, gesture.phase.value)
    }

    @Test
    fun `discard from the locked bar plays the rigid tick and cancels (CCV 110, 387-396)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host)
        gesture.pointer(0f, -80f)
        host.haptics.clear()
        gesture.finish(send = false)
        assertEquals(1, host.cancels)
        assertEquals(listOf(Haptic.Rigid), host.haptics)
        // Finishing an idle composer is a no-op (`guard phase.isActive`).
        gesture.finish(send = true)
        assertEquals(0, host.sends)
    }

    @Test
    fun `the recorder stopping on its own drops the bar without an outcome (CCV 145-148)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        holdAndStart(gesture, host)
        gesture.recorderStopped()
        assertEquals(ComposerPhase.Idle, gesture.phase.value)
        gesture.pointerUp()
        assertEquals(0, host.sends)
        assertEquals(0, host.cancels)
    }

    @Test
    fun `TalkBack's activation goes straight to the locked bar (CCV 297-300, 353-366)`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        gesture.startLocked()
        assertTrue(gesture.isStarting)
        // A second activation while starting is ignored.
        gesture.startLocked()
        host.start.complete(true)
        advanceUntilIdle()
        assertEquals(1, host.starts)
        assertEquals(ComposerPhase.Locked, gesture.phase.value)
    }

    @Test
    fun `a refused TalkBack start stays idle`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        gesture.startLocked()
        host.start.complete(false)
        advanceUntilIdle()
        assertEquals(ComposerPhase.Idle, gesture.phase.value)
    }

    @Test
    fun `a reset while starting throws the arriving take away`() = runTest(StandardTestDispatcher()) {
        val host = Host()
        val gesture = gesture(host)
        gesture.pointer(0f, 0f)
        gesture.reset()
        host.start.complete(true)
        advanceUntilIdle()
        assertEquals(1, host.cancels)
        assertEquals(ComposerPhase.Idle, gesture.phase.value)
    }

    @Test
    fun `toasts sit above the composer, at the screen edge while a layer covers it (CV 208-212)`() {
        assertEquals(60f, composerToastInset(108.dp, 48.dp, coversComposer = false).value)
        assertEquals(0f, composerToastInset(108.dp, 48.dp, coversComposer = true).value)
        assertEquals(0f, composerToastInset(20.dp, 48.dp, coversComposer = false).value)
    }

    @Test
    fun `progress helpers read zero outside the finger-down state`() {
        assertEquals(0f, ComposerPhase.Idle.cancelProgress)
        assertEquals(0f, ComposerPhase.Locked.lockProgress)
        assertFalse(ComposerPhase.Idle.isActive)
        assertTrue(ComposerPhase.Locked.isActive)
        assertTrue(ComposerPhase.Locked.isLocked)
        assertTrue(ComposerPhase.Recording(0.1f, 0.2f).isActive)
        assertFalse(ComposerPhase.Recording(0.1f, 0.2f).isLocked)
        assertEquals(110f, VoiceRecordingThresholds.cancel.value)
        assertEquals(76f, VoiceRecordingThresholds.lock.value)
    }
}
