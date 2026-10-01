package de.corespace.shroud.core.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * `VoicePlaybackCoordinator` against a hand-driven player (`ios/shroud/Services/Voice/VoicePlaybackCoordinator.swift`;
 * media-voice-links §8.3): one note at a time, toggle/pause/resume, seek that loads paused, sticky
 * rates `1× → 1.5× → 2×`, the 33 ms playhead, rewind at the end, stop on a broken note, and the
 * messaging sink (purge, lock, re-key).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlaybackCoordinatorTest {
    private val a = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    private val b = UUID.fromString("c56a4180-65aa-42ec-a945-5fd21dec0538")
    private val noteA = byteArrayOf(1, 2, 3)
    private val noteB = byteArrayOf(4, 5, 6)
    private val player = FakeVoicePlayer()

    private fun TestScope.coordinator() = VoicePlaybackCoordinator(player, backgroundScope)

    @Test
    fun toggleLoadsTheNoteAndPlaysItWhenReady() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        assertEquals(a, playback.state.value.activeId)
        assertTrue(playback.isPlaying(a))
        assertEquals(1, player.loads)
        assertFalse("nothing plays before the player is ready", player.playing)
        assertFalse(playback.hasPlayed(a))

        player.ready(4_000)
        assertTrue(player.playing)
        assertEquals(4.0, playback.state.value.duration, 0.0)
        assertTrue(playback.hasPlayed(a))
        assertEquals(1f, player.currentSpeed)
    }

    @Test
    fun thePlayheadFollowsThePlayerEvery33Ms() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        player.positionMs = 1_000
        advanceTimeBy(VoicePlaybackCoordinator.TICK_MS + 1)
        assertEquals(1.0, playback.state.value.currentTime, 0.0)
        assertEquals(0.25, playback.progress(a), 0.0)
        assertEquals(0.0, playback.progress(b), 0.0)
        assertEquals(1.0, playback.displayTime(a, fallbackMs = 9_999), 0.0)
        assertEquals(4.2, playback.displayTime(b, fallbackMs = 4_200), 0.0)
    }

    @Test
    fun toggleTheActiveNotePausesAndResumes() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        player.positionMs = 1_500

        playback.toggle(a, noteA)
        assertFalse(playback.isPlaying(a))
        assertFalse(player.playing)
        assertEquals(1.5, playback.state.value.currentTime, 0.0)
        assertEquals("the note stays loaded while paused", a, playback.state.value.activeId)

        playback.toggle(a, noteA)
        assertTrue(playback.isPlaying(a))
        assertTrue(player.playing)
        assertEquals(1, player.loads)
    }

    @Test
    fun startingAnotherNoteStopsThePreviousOne() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        playback.toggle(b, noteB)
        assertEquals(b, playback.state.value.activeId)
        assertEquals(2, player.loads)
        assertTrue(player.loaded.contentEquals(noteB))
        assertFalse(playback.isPlaying(a))
        assertEquals(0.0, playback.progress(a), 0.0)
        player.ready(2_000)
        assertTrue(playback.isPlaying(b))
        assertEquals(setOf(a, b), playback.state.value.playedIds)
    }

    /** Scrubbing a note that is not loaded loads it paused at that point (`:92-99`). */
    @Test
    fun seekingAnInactiveNoteLoadsItPausedThere() = runTest {
        val playback = coordinator()
        playback.seek(b, noteB, 0.5)
        assertEquals(b, playback.state.value.activeId)
        assertFalse(playback.isPlaying(b))
        player.ready(2_000)
        assertEquals(listOf(1_000L), player.seeks)
        assertEquals(1.0, playback.state.value.currentTime, 0.0)
        assertFalse(player.playing)
        assertTrue(playback.hasPlayed(b))
        assertEquals(0.5, playback.progress(b), 0.0)
    }

    @Test
    fun seekingTheActiveNoteMovesThePlayer() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        playback.seek(a, noteA, 0.25)
        assertEquals(1_000L, player.seeks.last())
        assertEquals(1.0, playback.state.value.currentTime, 0.0)
        playback.seek(a, noteA, 7.0)
        assertEquals("fractions clamp to 0…1", 4_000L, player.seeks.last())
        playback.seek(a, noteA, -1.0)
        assertEquals(0L, player.seeks.last())
    }

    @Test
    fun aSeekBeforeTheNoteIsReadyAppliesWhenItIs() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        playback.seek(a, noteA, 0.75)
        assertTrue(player.seeks.isEmpty())
        player.ready(4_000)
        assertEquals(listOf(3_000L), player.seeks)
        assertTrue(player.playing)
    }

    @Test
    fun pausingWhileLoadingKeepsTheNoteQuiet() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        playback.toggle(a, noteA)
        player.ready(4_000)
        assertFalse(player.playing)
        assertFalse(playback.isPlaying(a))
    }

    @Test
    fun ratesCycleAndStickAcrossNotes() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        playback.cycleRate()
        assertEquals(1.5f, playback.state.value.rate)
        assertEquals("applied live", 1.5f, player.currentSpeed)
        playback.cycleRate()
        assertEquals(2f, playback.state.value.rate)
        playback.toggle(b, noteB)
        player.ready(2_000)
        assertEquals("sticky across notes", 2f, player.currentSpeed)
        playback.cycleRate()
        assertEquals(1f, playback.state.value.rate)
        assertEquals(listOf("1×", "1.5×", "2×"), VoicePlaybackCoordinator.RATES.map(VoicePlaybackCoordinator::rateLabel))
    }

    /** End of the note: rewind so the next tap replays from the start (`:192-197`). */
    @Test
    fun reachingTheEndRewinds() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        player.positionMs = 4_000
        player.end()
        val state = playback.state.value
        assertFalse(state.isPlaying)
        assertEquals(0.0, state.currentTime, 0.0)
        assertEquals(a, state.activeId)
        assertEquals(0L, player.seeks.last())
        assertFalse(player.playing)

        playback.toggle(a, noteA)
        assertTrue(player.playing)
        assertEquals(1, player.loads)
    }

    /** Corrupt or still-encrypted bytes: everything clears (`:141-145`); the note never counts as played. */
    @Test
    fun aBrokenNoteStopsPlayback() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.fail()
        assertNull(playback.state.value.activeId)
        assertFalse(playback.state.value.isPlaying)
        assertFalse(playback.hasPlayed(a))
        assertEquals(1, player.stops)
    }

    @Test
    fun aLateReadyAfterStopIsIgnored() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        playback.stop()
        player.ready(4_000)
        assertNull(playback.state.value.activeId)
        assertFalse(player.playing)
    }

    @Test
    fun theSystemPausingPlaybackShowsPaused() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        player.positionMs = 2_000
        player.systemPause()
        assertFalse(playback.isPlaying(a))
        assertEquals(2.0, playback.state.value.currentTime, 0.0)
        player.positionMs = 3_000
        advanceTimeBy(200)
        assertEquals("the ticker stopped", 2.0, playback.state.value.currentTime, 0.0)
    }

    @Test
    fun unknownDurationStillPlaysButCannotSeek() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(null)
        assertTrue(player.playing)
        assertEquals(0.0, playback.state.value.duration, 0.0)
        assertEquals(0.0, playback.progress(a), 0.0)
        playback.seek(a, noteA, 0.5)
        assertTrue(player.seeks.isEmpty())
    }

    @Test
    fun stopIfActiveOnlyStopsThatNote() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        playback.stopIfActive(b)
        assertEquals(a, playback.state.value.activeId)
        playback.stopIfActive(a)
        assertNull(playback.state.value.activeId)
    }

    @Test
    fun stopKeepsTheRateAndThePlayedMarks() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        playback.cycleRate()
        playback.stop()
        val state = playback.state.value
        assertEquals(VoicePlaybackState(rate = 1.5f, playedIds = setOf(a)), state)
        runCurrent()
    }

    // ---- MessageArtifactSinks (plan §1.7.7; `MessagingController.swift:1969`) ----

    @Test
    fun aPurgedNoteStopsAndForgetsItWasPlayed() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        playback.artifactSink.onPurged(listOf(b))
        assertEquals(a, playback.state.value.activeId)
        playback.artifactSink.onPurged(listOf(a))
        assertNull(playback.state.value.activeId)
        assertFalse(playback.hasPlayed(a))
    }

    @Test
    fun lockingStopsPlayback() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        playback.artifactSink.onSensitiveMemoryLocked()
        assertNull(playback.state.value.activeId)
        assertFalse(player.playing)
    }

    @Test
    fun aRekeyedNoteKeepsPlayingUnderItsServerId() = runTest {
        val playback = coordinator()
        playback.toggle(a, noteA)
        player.ready(4_000)
        playback.artifactSink.onMessageRekeyed(a, b)
        assertTrue(playback.isPlaying(b))
        assertTrue(playback.hasPlayed(b))
        assertFalse(playback.hasPlayed(a))
    }
}
