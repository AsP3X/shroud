package de.corespace.shroud.core.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recording focus (iOS `ChatAudioSession` `.voiceRecord` → `.mixedPlayback`, media-voice-links §8.5)
 * and the call-media signal (iOS `.shroudCallMediaStarting`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioFocusCoordinatorTest {
    /** Keeps every loss callback, so a test can fire a stale one. */
    private class RecordingPort(var grant: Boolean = true) : AudioFocusCoordinator.FocusPort {
        val losses = ArrayList<() -> Unit>()
        var abandons = 0

        override fun request(onLoss: () -> Unit): Boolean {
            losses += onLoss
            return grant
        }

        override fun abandon() {
            abandons++
        }
    }

    @Test
    fun recordingTakesAndGivesBackFocus() {
        val port = RecordingPort()
        val focus = AudioFocusCoordinator(port)
        assertTrue(focus.requestRecording())
        assertTrue(focus.isHoldingRecordingFocus)
        focus.abandonRecording()
        focus.abandonRecording()
        assertFalse(focus.isHoldingRecordingFocus)
        assertEquals("abandoned once", 1, port.abandons)
    }

    @Test
    fun aRefusedRequestHoldsNothing() {
        val port = RecordingPort(grant = false)
        val focus = AudioFocusCoordinator(port)
        assertFalse(focus.requestRecording())
        assertFalse(focus.isHoldingRecordingFocus)
        focus.abandonRecording()
        assertEquals(0, port.abandons)
    }

    @Test
    fun losingFocusTellsTheRecorderOnce() {
        val port = RecordingPort()
        val focus = AudioFocusCoordinator(port)
        var lost = 0
        focus.requestRecording { lost++ }
        port.losses.single().invoke()
        port.losses.single().invoke()
        assertEquals(1, lost)
        assertFalse(focus.isHoldingRecordingFocus)
        assertEquals(1, port.abandons)
    }

    /** A loss queued for an earlier take must not end the next one. */
    @Test
    fun aStaleLossDoesNotEndTheNextTake() {
        val port = RecordingPort()
        val focus = AudioFocusCoordinator(port)
        var firstLost = 0
        var secondLost = 0
        focus.requestRecording { firstLost++ }
        focus.abandonRecording()
        focus.requestRecording { secondLost++ }
        port.losses[0].invoke()
        assertEquals(0, firstLost)
        assertEquals(0, secondLost)
        assertTrue(focus.isHoldingRecordingFocus)
        port.losses[port.losses.size - 1].invoke()
        assertEquals(1, secondLost)
    }

    @Test
    fun callMediaStartingReachesCollectors() = runTest(UnconfinedTestDispatcher()) {
        val focus = AudioFocusCoordinator(RecordingPort())
        var heard = 0
        backgroundScope.launch { focus.callMediaStarting.collect { heard++ } }
        focus.notifyCallMediaStarting()
        focus.notifyCallMediaStarting()
        assertEquals(2, heard)
        val next = backgroundScope.launch { focus.callMediaStarting.first() }
        focus.notifyCallMediaStarting()
        assertTrue(next.isCompleted)
    }
}
