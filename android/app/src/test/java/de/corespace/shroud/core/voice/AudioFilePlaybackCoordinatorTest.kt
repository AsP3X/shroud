package de.corespace.shroud.core.voice

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
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
import java.time.Instant
import java.util.UUID

/** A hand-driven [AudioFilePlayer]. */
class FakeAudioFilePlayer : AudioFilePlayer {
    override var listener: AudioFilePlayer.Listener? = null
    val loads = ArrayList<UUID>()
    var playing = false
    var currentSpeed = 1f
    var stops = 0
    override var positionMs: Long = 0L
    override val isAdvancing: Boolean get() = playing
    val seeks = ArrayList<Long>()

    override fun load(messageId: UUID) {
        loads += messageId
        playing = false
        positionMs = 0
    }

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        this.positionMs = positionMs
    }

    override fun setSpeed(rate: Float) {
        currentSpeed = rate
    }

    override fun stop() {
        stops++
        playing = false
    }

    fun ready(durationMs: Long?) = listener!!.onReady(durationMs)

    fun end() = listener!!.onEnded()

    fun fail(unsupported: Boolean) = listener!!.onError(unsupported)

    fun systemPause() {
        playing = false
        listener!!.onPausedBySystem()
    }
}

/**
 * [AudioFilePlaybackCoordinator]'s rules (docs/file-sharing.md §11.5) against [FakeAudioFilePlayer]:
 * one file at a time and one sound at a time, auto-advance, stop and pause rules, speed for long
 * files only, unplayable files, the clock-estimated playhead, and the messaging sink.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioFilePlaybackCoordinatorTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val c = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val player = FakeAudioFilePlayer()
    private var clockNs = 0L
    private var voiceStops = 0
    private val queue = HashMap<UUID, UUID?>()

    private fun TestScope.coordinator() = AudioFilePlaybackCoordinator(
        player = player,
        scope = backgroundScope,
        nextAfter = { queue[it] },
        onStarting = { voiceStops++ },
        nanoTime = { clockNs },
    )

    private fun advanceClock(ms: Long) {
        clockNs += ms * 1_000_000
    }

    @Test
    fun playLoadsTheFileByIdAndPlaysWhenReady() = runTest {
        val files = coordinator()
        files.toggle(a)
        assertEquals(listOf(a), player.loads)
        assertTrue(files.isPlaying(a))
        assertFalse("nothing plays before the player is ready", player.playing)
        player.ready(243_400)
        assertTrue(player.playing)
        assertEquals(243.4, files.state.value.duration, 0.0)
        assertEquals("a voice note stops when a file starts", 1, voiceStops)
    }

    @Test
    fun oneFileAtATime() = runTest {
        val files = coordinator()
        files.play(a)
        player.ready(60_000)
        files.play(b)
        assertEquals(b, files.state.value.activeId)
        assertEquals(listOf(a, b), player.loads)
        // The second file plays once it is ready, not before.
        assertFalse(player.playing)
        player.ready(30_000)
        assertTrue(player.playing)
        assertEquals(30.0, files.state.value.duration, 0.0)
    }

    @Test
    fun toggleOnTheActiveFilePausesAndResumes() = runTest {
        val files = coordinator()
        files.toggle(a)
        player.ready(60_000)
        player.positionMs = 5_000
        files.toggle(a)
        assertFalse(player.playing)
        assertFalse(files.state.value.isPlaying)
        assertEquals(a, files.state.value.activeId)
        assertEquals(5.0, files.state.value.currentTime, 0.0)
        val starts = files.playStarts
        files.toggle(a)
        assertTrue(player.playing)
        assertEquals(starts + 1, files.playStarts)
        assertEquals(2, voiceStops)
    }

    @Test
    fun aVoiceNoteStartingStopsTheFileAndCountsAsAStart() = runTest {
        val files = coordinator()
        files.play(a)
        player.ready(60_000)
        val starts = files.playStarts
        files.stopForOtherSound()
        assertNull(files.state.value.activeId)
        assertEquals(1, player.stops)
        assertEquals(starts + 1, files.playStarts)
    }

    @Test
    fun theEndPlaysTheNextPlayableFileBelow() = runTest {
        val files = coordinator()
        queue[a] = b
        queue[b] = c
        files.play(a)
        player.ready(10_000)
        files.artifactSink.onPurged(emptyList())
        // b could not be opened earlier this session: it is skipped.
        files.play(b)
        player.fail(unsupported = true)
        assertTrue(files.isUnplayable(b))
        files.play(a)
        player.ready(10_000)
        player.end()
        assertEquals(c, files.state.value.activeId)
        assertTrue(files.isPlaying(c))
        assertEquals(c, player.loads.last())
    }

    @Test
    fun theEndWithNothingBelowStops() = runTest {
        val files = coordinator()
        files.play(a)
        player.ready(10_000)
        player.end()
        assertNull(files.state.value.activeId)
        assertFalse(files.state.value.isPlaying)
        assertEquals(1, player.stops)
    }

    @Test
    fun anUnsupportedFileIsUnplayableForTheSessionAnIoErrorIsNot() = runTest {
        val files = coordinator()
        files.play(a)
        player.fail(unsupported = false)
        assertNull(files.state.value.activeId)
        assertFalse(files.isUnplayable(a))
        files.play(a)
        player.fail(unsupported = true)
        assertTrue(files.isUnplayable(a))
        val loads = player.loads.size
        files.play(a)
        assertEquals("an unplayable file never reaches the player again", loads, player.loads.size)
    }

    @Test
    fun backgroundAndSystemPausesKeepTheFileActive() = runTest {
        val files = coordinator()
        files.play(a)
        player.ready(60_000)
        files.pauseForBackground()
        assertFalse(player.playing)
        assertEquals(a, files.state.value.activeId)
        assertFalse(files.state.value.isPlaying)
        files.resume()
        player.systemPause()
        assertEquals(a, files.state.value.activeId)
        assertFalse(files.state.value.isPlaying)
        // Pausing what is not playing does nothing.
        files.pauseForBackground()
        assertEquals(a, files.state.value.activeId)
    }

    @Test
    fun speedAppliesOnlyToFilesOfTenMinutesOrMoreAndHoldsForTheSession() = runTest {
        val files = coordinator()
        files.play(a)
        player.ready(9 * 60_000L)
        assertFalse(files.state.value.speedApplies)
        files.cycleRate()
        assertEquals(1.5f, files.state.value.rate)
        assertEquals("a short file keeps 1×", 1f, player.currentSpeed)
        files.play(b)
        assertEquals(1f, player.currentSpeed)
        player.ready(45 * 60_000L)
        assertTrue(files.state.value.speedApplies)
        assertEquals("the chosen speed holds", 1.5f, player.currentSpeed)
        files.cycleRate()
        assertEquals(2f, player.currentSpeed)
        files.cycleRate()
        assertEquals(1f, player.currentSpeed)
        files.cycleRate()
        files.stop()
        assertEquals("stop keeps the speed", 1.5f, files.state.value.rate)
    }

    @Test
    fun thePlayheadIsEstimatedBetweenThePlayersReports() = runTest {
        val files = coordinator()
        files.play(a)
        player.ready(10_000)
        player.positionMs = 1_000
        assertEquals(0.1, files.liveProgress(a), 1e-9)
        advanceClock(200)
        // ExoPlayer has not reported again: the clock moves the playhead on.
        assertEquals(0.12, files.liveProgress(a), 1e-9)
        advanceClock(5_000)
        assertEquals("never more than a second past the report", 0.2, files.liveProgress(a), 1e-9)
        assertEquals(0.0, files.liveProgress(b), 0.0)
    }

    @Test
    fun theTickerMovesTheElapsedTimeWhilePlaying() = runTest {
        val files = coordinator()
        files.play(a)
        player.ready(10_000)
        player.positionMs = 2_500
        advanceTimeBy(AudioFilePlaybackCoordinator.TICK_MS + 1)
        runCurrent()
        assertEquals(2.5, files.state.value.currentTime, 1e-9)
        files.pause()
        player.positionMs = 9_000
        advanceTimeBy(AudioFilePlaybackCoordinator.TICK_MS * 3)
        assertEquals(2.5, files.state.value.currentTime, 1e-9)
    }

    @Test
    fun seekMovesTheActiveFileOnly() = runTest {
        val files = coordinator()
        files.play(a)
        files.seek(a, 0.5)
        player.ready(10_000)
        assertEquals("a seek before ready lands when ready", listOf(5_000L), player.seeks)
        files.seek(a, 0.25)
        assertEquals(2.5, files.state.value.currentTime, 1e-9)
        files.seek(b, 0.9)
        assertEquals(listOf(5_000L, 2_500L), player.seeks)
    }

    @Test
    fun purgeLockAndRekey() = runTest {
        val files = coordinator()
        files.markChecked(a)
        files.play(a)
        player.ready(10_000)
        files.artifactSink.onMessageRekeyed(a, b)
        assertEquals(b, files.state.value.activeId)
        assertTrue(files.isChecked(b))
        assertFalse(files.isChecked(a))
        files.artifactSink.onPurged(listOf(c))
        assertEquals(b, files.state.value.activeId)
        files.artifactSink.onPurged(listOf(b))
        assertNull(files.state.value.activeId)
        assertFalse(files.isChecked(b))

        files.play(c)
        player.fail(unsupported = true)
        files.markChecked(a)
        files.play(a)
        files.artifactSink.onSensitiveMemoryLocked()
        assertNull(files.state.value.activeId)
        assertFalse(files.isUnplayable(c))
        assertFalse(files.isChecked(a))
    }

    @Test
    fun theQueueFindsTheNextAudioFileOnThisPhoneBelow() {
        val peer = UUID.randomUUID()
        fun msg(id: UUID, name: String?, full: Boolean = true, deleted: Boolean = false) = ChatMessage(
            id = id, peerUserId = peer, senderUserId = peer, text = "", createdAt = Instant.EPOCH, isMine = false,
            kind = if (name == null) ChatMessageKind.Text else ChatMessageKind.File, fileName = name, hasFullMedia = full, deleted = deleted,
        )
        val d = UUID.randomUUID()
        val e = UUID.randomUUID()
        val thread = listOf(
            msg(a, "one.mp3"), msg(b, null), msg(c, "notes.pdf"), msg(d, "two.flac", full = false), msg(e, "three.ogg"),
        )
        val threads = mapOf(peer to thread)
        assertEquals(e, AudioFileQueue.nextAfter(threads, a))
        assertNull(AudioFileQueue.nextAfter(threads, e))
        assertNull(AudioFileQueue.nextAfter(threads, UUID.randomUUID()))
        assertNull(AudioFileQueue.nextAfter(mapOf(peer to thread.map { if (it.id == e) it.copy(deleted = true) else it }), a))
    }
}
