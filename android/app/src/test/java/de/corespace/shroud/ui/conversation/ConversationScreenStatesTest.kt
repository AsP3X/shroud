package de.corespace.shroud.ui.conversation

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.transcription.VoiceTranscription
import de.corespace.shroud.core.voice.VoicePlaybackCoordinator
import de.corespace.shroud.core.voice.VoicePlayer
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.conversation.ConversationFixtures.PEER
import de.corespace.shroud.ui.conversation.ConversationFixtures.message
import de.corespace.shroud.ui.conversation.bubble.BubbleServices
import de.corespace.shroud.ui.conversation.bubble.LocalBubbleServices
import de.corespace.shroud.ui.conversation.menu.MESSAGE_MENU_PANE_TITLE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/**
 * The conversation screen's states on a fake engine, read the way TalkBack reads them
 * (conversation-thread §1.5, §2.3–§2.5, §3.9, §16; design `Conversation` hc3Jf, `— Notes` nPQKZ,
 * `— Typing` NOg29): the first page loading, an empty chat, the failed first load, Notes, offline,
 * typing, rows with older history above, and the long-press menu opened from TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationScreenStatesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hosts = ArrayList<ComposeHarness>()
    private val frame = mutableIntStateOf(0)

    /** See `ChatsScreenTest.tearDown`: dispose the compositions so no delayed post outlives the test. */
    @After
    fun tearDown() {
        scope.cancel()
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
        Thread.sleep(SETTLE_REAL_MS)
        hosts.firstOrNull()?.idle()
    }

    private fun screen(
        backend: FakeConversationBackend,
        peer: UUID = PEER,
        username: String = "ana",
    ): Pair<ComposeHarness, ConversationViewModel> {
        val vm = ConversationViewModel(peer, username, backend, scope)
        val bubbles = FakeBubbleServices(RuntimeEnvironment.getApplication(), scope)
        val host = ComposeHarness {
            frame.intValue
            OverlayHost {
                CompositionLocalProvider(LocalBubbleServices provides bubbles) {
                    ConversationContent(
                        vm = vm,
                        onBack = {},
                        onOpenProfile = {},
                        coversComposer = false,
                        isRecording = false,
                        isSendingMedia = false,
                        composer = { onHeight ->
                            LaunchedEffect(Unit) { onHeight(COMPOSER_HEIGHT.dp) }
                            Box(Modifier.fillMaxWidth().height(COMPOSER_HEIGHT.dp))
                        },
                    )
                }
            }
        }
        hosts += host
        host.settle()
        return host to vm
    }

    private fun ComposeHarness.settle() {
        frame.intValue++
        idle()
        idle()
    }

    private fun ComposeHarness.described(text: String): List<SemanticsNode> =
        nodes().filter { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(text) } == true }

    private fun ComposeHarness.shows(text: String): Boolean = described(text).isNotEmpty() || nodesWithText(text).isNotEmpty()

    private fun ComposeHarness.assertShows(vararg texts: String) {
        for (text in texts) assertTrue("expected \"$text\": ${describe()}", shows(text))
    }

    private fun ComposeHarness.assertHides(vararg texts: String) {
        for (text in texts) assertTrue("unexpected \"$text\": ${describe()}", !shows(text))
    }

    @Test
    fun aChatWithNothingHereSpinsWhileItsFirstPageLoads() {
        val backend = FakeConversationBackend()
        val page = CompletableDeferred<Unit>()
        backend.onLoad = { page.await() }
        val (host, vm) = screen(backend)
        assertTrue(vm.loadingFirstPage)
        host.assertShows(ThreadListCopy.LOADING, "ana", ConversationHeaderCopy.BACK, ConversationHeaderCopy.VIDEO_CALL, ConversationHeaderCopy.CALL)
        host.assertHides(E2E_NOTICE, ThreadListCopy.LOAD_ERROR_TITLE)
        assertEquals("the chat is shown: marked read", PEER, backend.activePeerId.value)
        page.complete(Unit)
        host.settle()
        host.assertHides(ThreadListCopy.LOADING)
    }

    @Test
    fun anEmptyChatShowsTodayAndTheEncryptionNotice() {
        val (host, _) = screen(FakeConversationBackend())
        host.assertShows(ThreadListCopy.TODAY, E2E_NOTICE, ConversationPresence.UNKNOWN)
        host.assertHides(ThreadListCopy.LOADING, JumpToLatestMetrics.LABEL)
    }

    @Test
    fun aFailedFirstLoadOffersTryAgain() {
        val backend = FakeConversationBackend()
        backend.onLoad = { backend.lastError.value = "You're offline." }
        val (host, _) = screen(backend)
        host.assertShows(ThreadListCopy.LOAD_ERROR_TITLE, "You're offline.", "Try again")
        host.assertHides(E2E_NOTICE)
    }

    @Test
    fun notesHasTheBookmarkTheMoreMenuAndNoCalls() {
        val backend = FakeConversationBackend()
        val (host, vm) = screen(backend, peer = NOTES_PEER_ID, username = "Notes to me")
        assertTrue(vm.isNotes)
        host.assertShows("Notes to me", ConversationPresence.NOTES, ConversationHeaderCopy.MORE, E2E_NOTICE)
        host.assertHides(ConversationHeaderCopy.CALL, ConversationHeaderCopy.VIDEO_CALL)
    }

    @Test
    fun offlineTheHeaderSaysTheChatIsALocalCopy() {
        val backend = FakeConversationBackend()
        backend.isOffline.value = true
        val (host, _) = screen(backend)
        host.assertShows(ConversationPresence.OFFLINE_LOCAL_COPY)
    }

    @Test
    fun rowsShowWithTheOlderHistoryRowAboveAndItsSpinnerWhileAPageLoads() {
        val backend = FakeConversationBackend()
        backend.setThread(PEER, listOf(message(0, text = "first words"), message(1, mine = true, text = "and mine")))
        val (host, _) = screen(backend)
        host.assertShows("first words", "and mine")
        host.assertHides(E2E_NOTICE, ThreadListCopy.LOADING_EARLIER)

        backend.loadingOlderPeerIds.value = setOf(PEER)
        host.settle()
        host.assertShows(ThreadListCopy.LOADING_EARLIER)

        // Nothing older on the server: the start of the chat shows its notice instead.
        backend.loadingOlderPeerIds.value = emptySet()
        backend.olderHistoryExhausted.value = setOf(PEER)
        host.settle()
        host.assertShows(E2E_NOTICE)
        host.assertHides(ThreadListCopy.LOADING_EARLIER)
    }

    @Test
    fun theHeaderAndTheThreadShowThePeerTyping() {
        val backend = FakeConversationBackend()
        backend.setThread(PEER, listOf(message(0)))
        val (host, _) = screen(backend)
        backend.peerActivities.value = mapOf(PEER to ChatPeerActivity.Typing)
        host.settle()
        host.assertShows("typing")
    }

    @Test
    fun messageOptionsOpensTheMenuWhoseDeleteAsksFirst() {
        val m = message(0, mine = true, text = "to be deleted")
        val backend = FakeConversationBackend()
        backend.setThread(PEER, listOf(m))
        val (host, vm) = screen(backend)
        val bubble = host.described("to be deleted").first()
        val options = bubble.config.getOrNull(SemanticsActions.CustomActions).orEmpty().first { it.label == ConversationViewModel.ACTION_OPTIONS }
        host.activity.runOnUiThread { options.action() }
        host.settle()
        assertTrue(vm.menu.isOpen)
        val pane = host.nodes().filter { it.config.getOrNull(SemanticsProperties.PaneTitle) == MESSAGE_MENU_PANE_TITLE }
        assertEquals(1, pane.size)
        host.assertShows("Reply", "Copy", "Pin", "Forward", "Select", "Delete")

        vm.onMenuAction(de.corespace.shroud.ui.conversation.menu.MessageMenuAction.Delete, m)
        host.settle()
        host.assertShows(DeleteMessageCopy.MESSAGE_TITLE, "Delete for everyone", "Delete for me", "Cancel")
    }

    private companion object {
        const val COMPOSER_HEIGHT = 56
        const val SETTLE_REAL_MS = 30L
    }
}

/** Bubbles' engines with nothing behind them: no media, no playback, no transcription. */
internal class FakeBubbleServices(override val context: Context, scope: CoroutineScope) : BubbleServices {
    override val myUsername: String = "me"
    override fun canReact(message: ChatMessage): Boolean = !message.deleted
    override fun myReactions(message: ChatMessage): List<String> = emptyList()
    override suspend fun mediaBytes(messageId: UUID): ByteArray? = null
    override suspend fun ensureVoiceLoaded(message: ChatMessage) = Unit
    override suspend fun ensureLinkImageLoaded(message: ChatMessage) = Unit
    override suspend fun videoPoster(messageId: UUID): ByteArray? = null
    override suspend fun mediaDurationMs(messageId: UUID): Int? = null
    override val playback = VoicePlaybackCoordinator(SilentVoicePlayer(), scope)
    override val transcription: VoiceTranscription = VoiceTranscription.Unavailable
    override fun transcriptionHints(peerName: String): List<String> = listOf(peerName)
    override fun shareTranscript(transcript: String, voiceMessageId: UUID, peer: UUID) = Unit
    override suspend fun transcribe(message: ChatMessage, peerName: String): String = ""
    override fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable = AutoCloseable {}
}

/** A player that never plays. */
internal class SilentVoicePlayer : VoicePlayer {
    override var listener: VoicePlayer.Listener? = null
    override fun load(data: ByteArray) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setSpeed(rate: Float) = Unit
    override val positionMs: Long = 0L
    override fun stop() = Unit
}
