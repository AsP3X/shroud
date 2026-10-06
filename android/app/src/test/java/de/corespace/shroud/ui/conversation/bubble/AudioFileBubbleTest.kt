package de.corespace.shroud.ui.conversation.bubble

import android.app.Application
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.files.FileTypes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.ME
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.at
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.message
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.row
import de.corespace.shroud.ui.conversation.menu.MessageActions
import de.corespace.shroud.ui.theme.ShroudIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/** The audio bubble's rules and what TalkBack gets (docs/file-sharing.md §11.4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioFileBubbleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hosts = ArrayList<ComposeHarness>()

    @After
    fun tearDown() {
        scope.cancel()
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
        BubbleRenderFixtures.flushSnapshotWrites()
    }

    private val mp3 = FileTypes.forExtension("mp3")!!
    private val transfer = MediaTransfer(MediaTransfer.Phase.Transferring, isUpload = false, fraction = 0.5, totalBytes = 9_700_000)

    private fun ComposeHarness.labelled(part: String): SemanticsNode = nodes().single { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(part) } == true
    }

    private fun song(mine: Boolean = false, full: Boolean = true, title: String? = "Midnight City", artist: String? = "M83", name: String = "track01.mp3"): ChatMessage =
        message("", mine = mine, time = at(18, 9), kind = ChatMessageKind.File, mediaObjectId = UUID.randomUUID(), hasFullMedia = full, mediaByteCount = 9_700_000, durationMs = 243_400)
            .copy(fileName = name, audioTitle = title, audioArtist = artist)

    @Test
    fun theDetailLineFollowsTheState() {
        fun line(
            failed: Boolean = false,
            transfer: MediaTransfer? = null,
            active: Boolean = false,
            elapsed: Long = 0,
            duration: Long? = 243_400,
            artist: String? = "M83",
            onDevice: Boolean = true,
        ) = AudioBubbleMath.detailLine(mp3, failed, transfer, active, elapsed, duration, artist, 9_700_000, onDevice)

        assertEquals("4.9 MB of 9.7 MB", line(transfer = MediaTransfer(MediaTransfer.Phase.Transferring, false, 0.5, 9_700_000)))
        assertEquals("Not sent", line(failed = true))
        assertEquals("1:05 / 4:03", line(active = true, elapsed = 65_000))
        assertEquals("1:05", line(active = true, elapsed = 65_000, duration = null))
        assertEquals("M83 · 4:03", line())
        assertEquals("M83 · 4:03 · 9.7 MB", line(onDevice = false))
        assertEquals("M83 · 9.7 MB", line(onDevice = false, duration = null))
        assertEquals("4:03 · 9.7 MB · MP3", line(artist = null))
        assertEquals("9.7 MB · MP3", line(artist = " ", duration = null))
    }

    @Test
    fun theCoverSaysTheState() {
        assertEquals(AudioGlyph.Download, AudioBubbleMath.glyph(FileTileState.Download, isPlaying = false))
        assertEquals(AudioGlyph.Transferring, AudioBubbleMath.glyph(FileTileState.Transferring, isPlaying = true))
        assertEquals(AudioGlyph.Retry, AudioBubbleMath.glyph(FileTileState.Retry, isPlaying = false))
        assertEquals(AudioGlyph.Play, AudioBubbleMath.glyph(FileTileState.Ready, isPlaying = false))
        assertEquals(AudioGlyph.Pause, AudioBubbleMath.glyph(FileTileState.Ready, isPlaying = true))
        assertEquals(ShroudIcons.ArrowDownBold to 18.dp, AudioBubbleMath.icon(AudioGlyph.Download))
        assertEquals(ShroudIcons.StopFill to 14.dp, AudioBubbleMath.icon(AudioGlyph.Transferring))
        assertEquals(ShroudIcons.PlayFill to 18.dp, AudioBubbleMath.icon(AudioGlyph.Play))
        assertEquals(ShroudIcons.PauseFill to 18.dp, AudioBubbleMath.icon(AudioGlyph.Pause))
        assertEquals(ShroudIcons.ArrowClockwiseBold to 18.dp, AudioBubbleMath.icon(AudioGlyph.Retry))
    }

    @Test
    fun theBubbleIsAtMost300Wide() {
        assertEquals(300.dp, AudioBubbleMath.width(324.dp))
        assertEquals(232.dp, AudioBubbleMath.width(232.dp))
        assertEquals(243_400L, AudioBubbleMath.durationMs(243_400, 1_000))
        assertEquals(1_000L, AudioBubbleMath.durationMs(null, 1_000))
        assertNull(AudioBubbleMath.durationMs(0, null))
    }

    @Test
    fun talkBackHearsAudioTitleArtistAndDuration() {
        val label = AudioBubbleMath.accessibilityLabel(
            isMine = false, replyAuthor = null, replyText = null, title = "Midnight City", artist = "M83", durationMs = 243_400,
            caption = null, failed = false, sendError = null, transfer = null, needsDownload = true, isPlaying = false,
            chipsSummary = null, time = "18:09", receipt = ReceiptStatus.Sent,
        )
        assertEquals("Them, Audio, Midnight City, M83, 4:03, Not downloaded, 18:09", label)
        val bare = AudioBubbleMath.accessibilityLabel(
            isMine = true, replyAuthor = null, replyText = null, title = "memo.wav", artist = null, durationMs = null,
            caption = "for you", failed = false, sendError = null, transfer = null, needsDownload = false, isPlaying = true,
            chipsSummary = null, time = "18:10", receipt = ReceiptStatus.Read,
        )
        assertEquals("You, Audio, memo.wav, for you, Playing, 18:10, Read", bare)
    }

    @Test
    fun aTapPlaysThroughTheRowAndThePlayerDrivesTheBubble() {
        val track = song()
        val context = RecordingBubbleContext()
        val services = RenderBubbleServices(RuntimeEnvironment.getApplication(), scope)
        val host = ComposeHarness {
            CompositionLocalProvider(LocalBubbleServices provides services, LocalChatRowWidth provides 380.dp) { MessageBubble(row(track), context) }
        }
        hosts += host
        val node = host.labelled("Audio, Midnight City, M83, 4:03")
        assertEquals("Play", node.config.getOrNull(SemanticsActions.OnClick)!!.label)
        host.activity.runOnUiThread { node.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke() }
        host.idle()
        assertEquals(listOf("tapMedia"), context.log)
        assertTrue(MessageActions.hasFileActions(track))

        // Active and paused part-way (a playing file redraws every frame, which a paused looper never idles through).
        host.activity.runOnUiThread {
            services.audioFiles.play(track.id)
            services.audioFiles.pause()
        }
        host.idle()
        val paused = host.labelled("Audio, Midnight City")
        assertEquals("Play", paused.config.getOrNull(SemanticsActions.OnClick)!!.label)
        assertEquals(track.id, services.audioFiles.state.value.activeId)
        assertTrue(!paused.config.getOrNull(SemanticsProperties.ContentDescription)!!.single().contains("Playing"))
    }

    @Test
    fun aFileThePlayerCannotOpenFallsBackToThePlainFileBubble() {
        val track = song(name = "demo.aiff", title = null, artist = null)
        val context = RecordingBubbleContext()
        val services = RenderBubbleServices(RuntimeEnvironment.getApplication(), scope)
        val host = ComposeHarness {
            CompositionLocalProvider(LocalBubbleServices provides services, LocalChatRowWidth provides 380.dp) { MessageBubble(row(track), context) }
        }
        hosts += host
        host.labelled("Audio, demo.aiff")
        host.activity.runOnUiThread {
            services.audioFiles.play(track.id)
            services.audioPlayer.failUnsupported()
        }
        host.idle()
        val node = host.labelled("File, demo.aiff, 9.7 MB")
        assertTrue(node.config.getOrNull(SemanticsProperties.ContentDescription)!!.single().contains("Can't play on this phone"))
        host.activity.runOnUiThread { node.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke() }
        host.idle()
        assertEquals("its tap is a file's tap", listOf("tapMedia"), context.log)
    }

    @Test
    fun aFailedSendRetriesAndADownloadStops() {
        val failed = song(mine = true).copy(receipt = ReceiptStatus.Failed, sendError = "Waiting for connection…", pendingSync = true)
        val downloading = song(full = false)
        val context = RecordingBubbleContext(ME)
        val services = RenderBubbleServices(RuntimeEnvironment.getApplication(), scope)
        val host = ComposeHarness {
            CompositionLocalProvider(LocalBubbleServices provides services) {
                androidx.compose.foundation.layout.Column {
                    MessageBubble(row(failed), context)
                    MessageBubble(row(downloading, transfer = transfer), context)
                }
            }
        }
        hosts += host
        val failedNode = host.nodes().single { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("You, Audio") } == true
        }
        assertTrue(failedNode.config.getOrNull(SemanticsProperties.ContentDescription)!!.single().contains("Not sent"))
        val retry = failedNode.config.getOrNull(SemanticsActions.CustomActions)!!.first { it.label == "Retry" }
        host.activity.runOnUiThread { retry.action() }
        host.idle()
        assertTrue(context.log.contains("retry"))
        val downloadNode = host.nodes().single { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Them, Audio") } == true
        }
        assertNull("a download in flight has no default action", downloadNode.config.getOrNull(SemanticsActions.OnClick))
        val cancel = downloadNode.config.getOrNull(SemanticsActions.CustomActions)!!.first { it.label == "Cancel download" }
        host.activity.runOnUiThread { cancel.action() }
        host.idle()
        assertTrue(context.log.contains("cancel"))
    }

    @Test
    fun aQuotedAudioFileReadsItsDisplayTitle() {
        val track = song()
        val quote = ReplyQuoteContent.make(track, "Jane", ME)
        assertEquals("Midnight City – M83", quote.text)
        assertEquals(ShroudIcons.MusicNotesFill, quote.symbol)
        val reference = MessageReplyReference(UUID.randomUUID(), track.senderUserId, MessageReplyReference.Kind.Audio, "")
        assertEquals("Audio", ReplyQuoteContent.make(reference, null, "Jane", ME).text)
    }
}
