package de.corespace.shroud.core.media.video

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * `ChatVideoPlayer` (`ios/shroud/Services/Video/ChatVideoPlayer.swift`; conversation-compose-media
 * §16) against a scripted engine under virtual time: the 8 s ready wait, autoplay, the 1/30 s
 * playhead, loop wrapping, rewinding on replay, clamped seeks, and a teardown that resets
 * everything and stops an in-flight start from attaching. ExoPlayer itself plays on a device
 * (`ChatVideoPlayerDeviceTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatVideoPlayerTest {
    private class FakeEngine(override var durationSeconds: Double = 10.0) : PlaybackEngine {
        override val player: Player? = null
        val statusFlow = MutableStateFlow(PlaybackEngine.Status.Preparing)
        override val status: StateFlow<PlaybackEngine.Status> = statusFlow
        override var positionSeconds = 0.0
        override var isPlaying = false
        override var onEnded: (() -> Unit)? = null
        val seeks = ArrayList<Pair<Double, Boolean>>()
        var plays = 0
        var lastVolume = 1f
        var released = false

        override fun play() {
            plays++
            isPlaying = true
        }

        override fun pause() {
            isPlaying = false
        }

        override fun seekTo(seconds: Double, precise: Boolean) {
            seeks += seconds to precise
            positionSeconds = seconds
        }

        override fun setVolume(volume: Float) {
            lastVolume = volume
        }

        override fun release() {
            released = true
        }
    }

    private class Engines(vararg engines: FakeEngine?) : PlaybackEngine.Factory {
        private val queue = ArrayDeque(engines.toList())
        val sources = ArrayList<VideoSource>()

        override fun create(source: VideoSource): PlaybackEngine? {
            sources += source
            return queue.removeFirst()
        }
    }

    private val clip = UUID.fromString("6f9619ff-8b86-d011-b42d-00cf4fc964ff")

    private fun TestScope.player(engines: Engines) = ChatVideoPlayer(engines, backgroundScope)

    /** Starts [player] and makes [engine] ready, as `start(messageId)` from a screen would. */
    private fun TestScope.startReady(player: ChatVideoPlayer, engine: FakeEngine) {
        launch { player.start(clip) }
        runCurrent()
        engine.statusFlow.value = PlaybackEngine.Status.Ready
        runCurrent()
    }

    @Test
    fun startPlaysOnceTheItemIsReady() = runTest {
        val engine = FakeEngine()
        val engines = Engines(engine)
        val player = player(engines)
        launch { player.start(clip) }
        runCurrent()
        assertFalse(player.state.value.isReady)
        assertEquals(0, engine.plays)

        engine.statusFlow.value = PlaybackEngine.Status.Ready
        runCurrent()
        val state = player.state.value
        assertTrue(state.isReady)
        assertTrue(state.isPlaying)
        assertEquals(10.0, state.duration, 0.0)
        assertEquals(1, engine.plays)
        assertEquals(listOf<VideoSource>(VideoSource.Message(clip)), engines.sources)
        player.teardown()
    }

    @Test
    fun anItemThatIsNotReadyAfter8SecondsFails() = runTest {
        val engine = FakeEngine()
        val engines = Engines(engine)
        val player = player(engines)
        launch { player.start(clip) }
        advanceTimeBy(ChatVideoPlayer.READY_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(player.state.value.failed)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(player.state.value.failed)
        assertFalse(player.state.value.isReady)
        assertEquals(0, engine.plays)

        // Safe to call twice: a no-op once failed (`ChatVideoPlayer.swift:40, 55`).
        player.start(clip)
        assertEquals(1, engines.sources.size)
        player.teardown()
        assertTrue(engine.released)
    }

    @Test
    fun aFailedItemFails() = runTest {
        val engine = FakeEngine()
        val player = player(Engines(engine))
        launch { player.start(clip) }
        runCurrent()
        engine.statusFlow.value = PlaybackEngine.Status.Failed
        runCurrent()
        assertTrue(player.state.value.failed)
    }

    @Test
    fun aClipWithoutLocalMediaFails() = runTest {
        val player = player(Engines(null))
        player.start(clip)
        assertTrue(player.state.value.failed)
        assertNull(player.player.value)
    }

    @Test
    fun teardownResetsFailedSoTheNextClipCanStart() = runTest {
        val good = FakeEngine(durationSeconds = 4.0)
        val engines = Engines(null, good)
        val player = player(engines)
        player.start(clip)
        assertTrue(player.state.value.failed)

        player.teardown()
        assertEquals(ChatVideoPlayer.State(), player.state.value)
        startReady(player, good)
        assertTrue(player.state.value.isReady)
        assertEquals(4.0, player.state.value.duration, 0.0)
        player.teardown()
    }

    @Test
    fun thePlayheadFollowsPlaybackButNotAScrubbingFinger() = runTest {
        val engine = FakeEngine()
        val player = player(Engines(engine))
        startReady(player, engine)

        engine.positionSeconds = 1.25
        advanceTimeBy(ChatVideoPlayer.TICK_MS + 1)
        assertEquals(1.25, player.state.value.currentTime, 0.0)

        player.isScrubbing = true
        engine.positionSeconds = 3.0
        advanceTimeBy(ChatVideoPlayer.TICK_MS * 3)
        assertEquals(1.25, player.state.value.currentTime, 0.0)

        player.isScrubbing = false
        advanceTimeBy(ChatVideoPlayer.TICK_MS + 1)
        assertEquals(3.0, player.state.value.currentTime, 0.0)
        assertEquals(0.3, player.state.value.progress, 1e-9)
        player.teardown()
    }

    @Test
    fun aLoopWrapsToItsStartJustBeforeItsEnd() = runTest {
        val engine = FakeEngine()
        val player = player(Engines(engine))
        player.loopRange = 2.0..5.0
        startReady(player, engine)
        // Autoplay outside the window jumped to its start, exactly (`:158-159`).
        assertEquals(2.0 to true, engine.seeks.last())

        engine.positionSeconds = 4.96
        advanceTimeBy(ChatVideoPlayer.TICK_MS + 1)
        assertEquals(4.96, player.state.value.currentTime, 0.0)

        engine.positionSeconds = 4.98
        advanceTimeBy(ChatVideoPlayer.TICK_MS + 1)
        assertEquals(2.0 to false, engine.seeks.last())
        assertEquals(2.0, player.state.value.currentTime, 0.0)
        player.teardown()
    }

    @Test
    fun playingAgainAfterTheEndRewinds() = runTest {
        val engine = FakeEngine()
        val player = player(Engines(engine))
        startReady(player, engine)

        engine.isPlaying = false
        engine.onEnded!!.invoke()
        assertFalse(player.state.value.isPlaying)
        assertEquals(10.0, player.state.value.currentTime, 0.0)

        player.toggle()
        assertEquals(0.0 to true, engine.seeks.last())
        assertTrue(player.state.value.isPlaying)
        assertEquals(2, engine.plays)
        player.teardown()
    }

    @Test
    fun seeksClampAndPublishAtOnce() = runTest {
        val engine = FakeEngine()
        val player = player(Engines(engine))
        player.seek(3.0) // No player yet: nothing happens.
        assertTrue(engine.seeks.isEmpty())
        startReady(player, engine)

        player.seek(-2.0)
        assertEquals(0.0 to false, engine.seeks.last())
        player.seek(99.0, precise = true)
        assertEquals(10.0 to true, engine.seeks.last())
        assertEquals(10.0, player.state.value.currentTime, 0.0)
        assertEquals(1.0, player.state.value.progress, 0.0)
        player.seek(Double.NaN)
        assertEquals(10.0, player.state.value.currentTime, 0.0)
        player.teardown()
    }

    @Test
    fun pauseAndToggleFollowThePlayState() = runTest {
        val engine = FakeEngine()
        val player = player(Engines(engine))
        startReady(player, engine)
        player.toggle()
        assertFalse(player.state.value.isPlaying)
        assertFalse(engine.isPlaying)
        player.toggle()
        assertTrue(player.state.value.isPlaying)
        player.teardown()
    }

    @Test
    fun aTeardownDuringStartNeverAttaches() = runTest {
        val engine = FakeEngine()
        val player = player(Engines(engine))
        launch { player.start(clip) }
        runCurrent()
        player.teardown()
        engine.statusFlow.value = PlaybackEngine.Status.Ready
        runCurrent()

        assertTrue(engine.released)
        assertEquals(0, engine.plays)
        assertEquals(ChatVideoPlayer.State(), player.state.value)
        assertNull(player.player.value)
    }

    @Test
    fun muteFollowsThePlayerUntilTeardown() = runTest {
        val first = FakeEngine()
        val second = FakeEngine()
        val player = player(Engines(first, second))
        player.setMuted(true)
        startReady(player, first)
        assertEquals(0f, first.lastVolume)
        player.setMuted(false)
        assertEquals(1f, first.lastVolume)
        player.setMuted(true)

        player.teardown()
        assertNull(player.loopRange)
        startReady(player, second)
        assertEquals(1f, second.lastVolume)
        player.teardown()
    }

    @Test
    fun timeLabelsAreMinutesAndSecondsRoundedDown() {
        assertEquals("0:00", ChatVideoPlayer.timeLabel(0.0))
        assertEquals("0:59", ChatVideoPlayer.timeLabel(59.99))
        assertEquals("1:01", ChatVideoPlayer.timeLabel(61.0))
        assertEquals("60:00", ChatVideoPlayer.timeLabel(3600.0))
        assertEquals("0:00", ChatVideoPlayer.timeLabel(-1.0))
        assertEquals("0:00", ChatVideoPlayer.timeLabel(Double.NaN))
        assertEquals("0:00", ChatVideoPlayer.timeLabel(Double.POSITIVE_INFINITY))
    }

    @Test
    fun progressIsClampedAndZeroWithoutADuration() {
        assertEquals(0.0, ChatVideoPlayer.State(duration = 0.0, currentTime = 3.0).progress, 0.0)
        assertEquals(1.0, ChatVideoPlayer.State(duration = 2.0, currentTime = 3.0).progress, 0.0)
        assertEquals(0.25, ChatVideoPlayer.State(duration = 4.0, currentTime = 1.0).progress, 0.0)
    }
}
