package de.corespace.shroud.ui.conversation.composer

import android.app.Application
import de.corespace.shroud.core.media.files.FileCopy
import de.corespace.shroud.core.media.share.FileOpenOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.voice.AudioFilePlaybackCoordinator
import de.corespace.shroud.core.voice.FakeAudioFilePlayer
import de.corespace.shroud.testing.MainDispatcherRule
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.PEER
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * A tap on an audio bubble through the composer (docs/file-sharing.md §11.4): §4's check once per
 * session before the player, play / pause, download then play unless something else started or the
 * chat closed, a download's stop, and the plain file tap for a file this phone can't play.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeControllerAudioTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class Env(val services: FakeComposeServices, val host: FakeComposeHost, val controller: ComposeController, val player: FakeAudioFilePlayer, val audio: AudioFilePlaybackCoordinator)

    private fun TestScope.env(configure: FakeComposeServices.() -> Unit = {}): Env {
        val player = FakeAudioFilePlayer()
        val audio = AudioFilePlaybackCoordinator(player, backgroundScope)
        val services = FakeComposeServices(backgroundScope).apply {
            audioFiles = audio
            configure()
        }
        val host = FakeComposeHost()
        val controller = ComposeController(PEER, false, services, backgroundScope, host, "jane")
        return Env(services, host, controller, player, audio)
    }

    private fun song(name: String = "track01.mp3", hasFullMedia: Boolean = true, id: UUID = UUID.randomUUID()): ChatMessage =
        message(id = id, text = "", kind = ChatMessageKind.File, hasFullMedia = hasFullMedia, mediaObjectId = UUID.randomUUID())
            .copy(fileName = name, mediaByteCount = 9_700_000)

    @Test
    fun `a tap checks the file once, then plays and pauses it`() = runTest(main.dispatcher) {
        val track = song()
        val env = env { threads.value = mapOf(PEER to listOf(track)) }
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        assertEquals(listOf("check:track01.mp3"), env.services.fileActions)
        assertEquals(listOf(track.id), env.player.loads)
        assertTrue(env.audio.isPlaying(track.id))
        env.player.ready(243_400)
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        assertFalse(env.audio.isPlaying(track.id))
        assertEquals(track.id, env.audio.state.value.activeId)
        env.audio.stop()
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        assertEquals("checked once per session", listOf("check:track01.mp3"), env.services.fileActions)
        assertEquals(listOf(track.id, track.id), env.player.loads)
    }

    @Test
    fun `a file that fails the content check says so and never reaches the player`() = runTest(main.dispatcher) {
        val track = song()
        val env = env {
            threads.value = mapOf(PEER to listOf(track))
            openRefusal = FileCopy.mismatch("mp3")
        }
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        assertTrue(env.player.loads.isEmpty())
        assertEquals(listOf(Toast.failure("This file doesn't match its .mp3 type, so Shroud won't open it.")), env.host.toasts)
    }

    @Test
    fun `a file that is not here downloads, then plays`() = runTest(main.dispatcher) {
        val track = song(hasFullMedia = false)
        val env = env {
            threads.value = mapOf(PEER to listOf(track))
            downloadable += track.id
        }
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        assertEquals(listOf(track.id), env.services.downloads)
        assertEquals(listOf(track.id), env.player.loads)
    }

    @Test
    fun `a download does not play when something else started meanwhile`() = runTest(main.dispatcher) {
        val track = song(hasFullMedia = false)
        val other = song("other.flac")
        val gate = CompletableDeferred<Unit>()
        val env = env {
            threads.value = mapOf(PEER to listOf(other, track))
            downloadable += track.id
            downloadGate = gate
        }
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        env.audio.markChecked(other.id)
        env.audio.play(other.id)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(other.id), env.player.loads)
        assertEquals(other.id, env.audio.state.value.activeId)
    }

    @Test
    fun `a voice note starting meanwhile also keeps the download from playing`() = runTest(main.dispatcher) {
        val track = song(hasFullMedia = false)
        val gate = CompletableDeferred<Unit>()
        val env = env {
            threads.value = mapOf(PEER to listOf(track))
            downloadable += track.id
            downloadGate = gate
        }
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        env.audio.stopForOtherSound()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(env.player.loads.isEmpty())
    }

    @Test
    fun `a download does not play once the chat closed`() = runTest(main.dispatcher) {
        val track = song(hasFullMedia = false)
        val gate = CompletableDeferred<Unit>()
        val env = env {
            threads.value = mapOf(PEER to listOf(track))
            downloadable += track.id
            downloadGate = gate
        }
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        env.controller.onLeave(profilePushed = false)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(env.player.loads.isEmpty())
    }

    @Test
    fun `a tap during its download stops it`() = runTest(main.dispatcher) {
        val track = song(hasFullMedia = false)
        val env = env {
            threads.value = mapOf(PEER to listOf(track))
            downloadable += track.id
            downloadGate = CompletableDeferred()
        }
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        assertEquals(listOf(track.id), env.services.cancelledDownloads)
        assertTrue(env.player.loads.isEmpty())
        assertTrue("stopping is no failure", env.host.toasts.isEmpty())
    }

    @Test
    fun `a file this phone can't play taps like any other file`() = runTest(main.dispatcher) {
        val track = song("demo.aiff")
        val env = env {
            threads.value = mapOf(PEER to listOf(track))
            openOutcome = FileOpenOutcome.Refused(FileCopy.COULD_NOT_OPEN)
        }
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        env.player.fail(unsupported = true)
        assertTrue(env.audio.isUnplayable(track.id))
        env.services.fileActions.clear()
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        assertEquals(listOf("open:demo.aiff"), env.services.fileActions)
    }

    @Test
    fun `no tap plays while a message menu is up`() = runTest(main.dispatcher) {
        val track = song()
        val env = env { threads.value = mapOf(PEER to listOf(track)) }
        env.host.isShowingMessageMenu = true
        env.controller.handleMediaTap(track)
        advanceUntilIdle()
        assertTrue(env.player.loads.isEmpty())
        assertNull(env.audio.state.value.activeId)
    }
}
