package de.corespace.shroud.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * What the composer asks of the conversation screen (conversation-compose-media §2; plan §1.7.13).
 * Implemented by the conversation screen (W3-THREAD-LIST).
 *
 * **Seam (W2-INT), owner W3-COMPOSER in wave 3.**
 */
interface ComposeHost {
    /** A send went out: the list scrolls to the newest message. */
    fun pinToBottom()

    /** The reply bar's quote was tapped: the list jumps to [messageId]. */
    fun jumpToQuoted(messageId: UUID)

    /** The long-press menu is up: the composer keeps the keyboard down and ignores taps. */
    val isShowingMessageMenu: Boolean
    fun showToast(toast: Toast)
}

/**
 * The composer's state for one conversation: draft, reply, link preview, recording, attachments
 * (iOS `ConversationView` compose state; conversation-compose-media §2–§9; plan §1.7.13). The
 * conversation screen creates one per open chat with its own main-thread [scope] and drives it
 * through the members below; [ConversationComposeHost] draws it.
 *
 * Link previews go through W2-LINKS' existing `LinkPreviewComposer` (`container.links.newComposer(scope)`),
 * not a second interface (W2-LINKS integration note). Sends go through `container.messaging.controller`,
 * recording through `container.voice.recorder`, transcription through `container.transcription.voice`.
 *
 * **Stub (W2-INT seam), owner W3-COMPOSER**, which replaces the bodies; the members are the contract
 * W3-THREAD-LIST compiles against.
 *
 * @param peer the thread key (`NOTES_PEER_ID` for Saved Messages).
 */
@Suppress("UNUSED_PARAMETER", "unused")
class ComposeController(
    val peer: UUID,
    val isNotes: Boolean,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val host: ComposeHost,
) {
    private val recording = MutableStateFlow(false)

    /** A voice take is running (the screen keeps Back for [cancelVoiceTake] meanwhile). */
    val isRecording: StateFlow<Boolean> = recording

    /** Reply to [message]: the reply bar shows its quote, the field takes focus. */
    fun startReply(message: ChatMessage) = Unit

    fun clearReply() = Unit

    /** A media bubble was tapped while composing (retry a failed upload, or open the viewer). */
    fun handleMediaTap(message: ChatMessage) = Unit

    /** The screen goes away; [profilePushed] keeps the draft for the return from the contact's profile. */
    fun onLeave(profilePushed: Boolean) = Unit

    /** The chats locked: the draft's media and a take are discarded. */
    fun onLock() = Unit

    /** Back during a take discards it (thread D9 = compose Q10). */
    fun cancelVoiceTake() = Unit
}

/**
 * The composer bar and everything that hangs off it: reply bar, link preview strip, recording UI,
 * attach sheet and pickers (conversation-compose-media §2–§9). Reports its height so the list can
 * keep the newest message above it.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-COMPOSER**, which replaces the body.
 * Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ConversationComposeHost(controller: ComposeController, onComposerHeightChanged: (Dp) -> Unit) {
}
