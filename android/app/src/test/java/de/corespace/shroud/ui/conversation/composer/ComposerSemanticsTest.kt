package de.corespace.shroud.ui.conversation.composer

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.voice.VoiceRecorder
import de.corespace.shroud.core.voice.VoiceTimeFormat
import de.corespace.shroud.ui.components.ComposeHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What TalkBack gets from the composer (conversation-compose-media §3.10; iOS accessibility lines in
 * `ChatComposerView.swift:201, 257, 297-300`, `VoiceRecordingUI.swift:64-67, 129, 155-170`,
 * `ChatReplyBar.swift:39`, `ChatLinkBar.swift:65-78`, `ConversationView.swift:1716`) and the field's
 * keyboard flags (plan P5: no personalised learning; Enter is a newline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerSemanticsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private class Host : ComposerGesture.Host {
        val start = CompletableDeferred<Boolean>()
        var starts = 0
        var sends = 0
        var cancels = 0

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

        override fun haptic(haptic: Haptic) = Unit
    }

    @Composable
    private fun Composer(
        draft: TextFieldState,
        gesture: ComposerGesture,
        recorder: VoiceRecorder.RecState = VoiceRecorder.RecState(),
        linkBar: ChatLinkBarState? = null,
        onAttach: () -> Unit = {},
        onSend: () -> Unit = {},
        onRemoveLink: () -> Unit = {},
    ) {
        val phase by gesture.phase.collectAsState()
        ChatComposer(
            draft = draft,
            recorder = recorder,
            phase = phase,
            gesture = gesture,
            onAttach = onAttach,
            onSend = onSend,
            reply = null,
            onTapReply = null,
            onCancelReply = {},
            focusToken = 0,
            linkBar = linkBar,
            linkShowsAboveText = false,
            linkCanToggleImageSize = false,
            linkUsesLargeImage = false,
            onToggleLinkAboveText = {},
            onToggleLinkImageSize = {},
            onRemoveLinkPreview = onRemoveLink,
        )
    }

    @Test
    fun idleComposerOffersAttachTheFieldAndTheMicWithItsHandsFreeAction() {
        val host = Host()
        val gesture = ComposerGesture(scope, host)
        var attaches = 0
        val ui = ComposeHarness { Composer(TextFieldState(), gesture, onAttach = { attaches++ }) }

        val attach = ui.node("Attach")
        assertEquals(Role.Button, attach.config[SemanticsProperties.Role])
        attach.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, attaches)

        val field = ui.nodes().first { SemanticsActions.SetText in it.config }
        val hint = field.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        assertTrue("the empty field reads its hint: ${ui.describe()}", hint?.contains("Message") == true)

        val mic = ui.node("Record voice message")
        assertEquals(Role.Button, mic.config[SemanticsProperties.Role])
        assertEquals("Starts a hands-free recording. Send or discard it when you’re done.", mic.config[SemanticsActions.OnClick].label)
        assertTrue("no Send without a draft", ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Send") == true })

        // TalkBack cannot hold and slide: its activation starts a hands-free take.
        mic.config[SemanticsActions.OnClick].action!!.invoke()
        host.start.complete(true)
        ui.idle()
        assertEquals(1, host.starts)
        assertEquals(ComposerPhase.Locked, gesture.phase.value)
        assertNotNull(ui.node("Discard recording"))
    }

    @Test
    fun aDraftTurnsTheMicIntoSend() {
        val gesture = ComposerGesture(scope, Host())
        val draft = TextFieldState("Hello")
        var sends = 0
        val ui = ComposeHarness { Composer(draft, gesture, onSend = { sends++ }) }
        val send = ui.node("Send")
        assertEquals(Role.Button, send.config[SemanticsProperties.Role])
        send.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, sends)
        assertTrue(ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Record voice message") == true })
    }

    @Test
    fun theComposerFieldNeverTeachesTheKeyboardAndEnterIsANewline() {
        val gesture = ComposerGesture(scope, Host())
        val ui = ComposeHarness { Composer(TextFieldState(), gesture) }
        val field = ui.nodes().first { SemanticsActions.SetText in it.config }
        (field.config.getOrNull(SemanticsActions.RequestFocus) ?: field.config[SemanticsActions.OnClick]).action!!.invoke()
        ui.idle()
        val info = EditorInfo()
        assertNotNull("the focused field opened an input connection", ui.root.onCreateInputConnection(info))
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertEquals(EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES, info.inputType and EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertEquals(EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE, info.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)
    }

    @Test
    fun theFingerDownBarIsOneSpokenNode() {
        val host = Host()
        val gesture = ComposerGesture(scope, host)
        val ui = ComposeHarness { Composer(TextFieldState(), gesture, recorder = VoiceRecorder.RecState(recording = true, elapsedSeconds = 7.32)) }
        gesture.pointer(0f, 0f)
        host.start.complete(true)
        ui.idle()
        assertTrue(gesture.phase.value is ComposerPhase.Recording)
        val bar = ui.node("Recording, 7 seconds. Release to send, slide left to cancel.")
        assertTrue("the timer is folded into the bar: ${ui.describe()}", ui.nodesWithText("0:07").isEmpty())
        assertTrue(bar.config.getOrNull(SemanticsProperties.Text) == null)
        // The attach button and the field are gone while the finger is down.
        assertTrue(ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Attach") == true })
    }

    @Test
    fun theLockedBarNamesItsButtonsAndReadsTheElapsedTimeAsItsValue() {
        val host = Host()
        val gesture = ComposerGesture(scope, host)
        val ui = ComposeHarness {
            Composer(TextFieldState(), gesture, recorder = VoiceRecorder.RecState(recording = true, elapsedSeconds = 95.0, liveLevels = listOf(0.2f, 0.5f)))
        }
        gesture.startLocked()
        host.start.complete(true)
        ui.idle()
        val readout = ui.node("Recording")
        assertEquals(VoiceTimeFormat.spoken(95.0), readout.config[SemanticsProperties.StateDescription])
        assertFalse("not a live region (VRU 155-157)", SemanticsProperties.LiveRegion in readout.config)
        assertEquals(Role.Button, ui.node("Discard recording").config[SemanticsProperties.Role])
        val send = ui.node("Send recording")
        send.config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertEquals(1, host.sends)
        assertEquals(ComposerPhase.Idle, gesture.phase.value)
    }

    @Test
    fun theLinkStripNamesThePreviewItsOptionsAndItsClose() {
        val gesture = ComposerGesture(scope, Host())
        var removed = 0
        val ui = ComposeHarness {
            Composer(TextFieldState("see komoot.com/tour/1"), gesture, linkBar = ChatLinkBarState.Loading("komoot.com/tour/1"), onRemoveLink = { removed++ })
        }
        val preview = ui.node("Loading link preview for komoot.com/tour/1")
        assertEquals("Shows link preview options", preview.config[SemanticsActions.OnClick].label)
        val close = ui.node("Remove link preview")
        assertEquals(Role.Button, close.config[SemanticsProperties.Role])
        close.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, removed)
    }

    @Test
    fun theReplyStripCloseIsCancelReply() {
        var cancels = 0
        val ui = ComposeHarness {
            ChatReplyBar(
                content = de.corespace.shroud.ui.conversation.bubble.ReplyQuoteContent("Jane", "Where?", isStandIn = false, thumbnail = null, symbol = null),
                onTapPreview = {},
                onCancel = { cancels++ },
            )
        }
        val close = ui.node("Cancel reply")
        assertEquals(Role.Button, close.config[SemanticsProperties.Role])
        close.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, cancels)
    }

    @Test
    fun notesTodoIsOneButtonCalledAddAsTodo() {
        var todos = 0
        val ui = ComposeHarness { Column { NotesTodoBar(onTodo = { todos++ }) } }
        val todo = ui.node("Add as todo")
        assertEquals(Role.Button, todo.config[SemanticsProperties.Role])
        assertTrue("the label replaces the visible word: ${ui.describe()}", ui.nodesWithText("Todo").isEmpty())
        todo.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, todos)
    }
}
