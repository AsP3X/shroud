package de.corespace.shroud.ui.conversation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReactionChip
import de.corespace.shroud.ui.conversation.bubble.ReplyQuoteContent
import java.util.UUID

/**
 * One row of the message list as the bubble draws it (conversation-thread §3; plan §1.7.13). Built
 * by the list (W3-THREAD-LIST) from the thread, the transfers and the reactions; drawn by
 * `MessageBubble` (W3-THREAD-BUBBLES). Immutable, so a row recomposes only when its own data changed.
 *
 * **Seam (W2-INT), owner W3-THREAD-LIST in wave 3.**
 *
 * @property isNotes the row is in Saved Messages (no receipts, to-dos allowed).
 * @property replyQuote the quoted message's header, resolved against the thread (null: no reply).
 * @property transfer the running upload or download of this message's media.
 * @property showsTranscriptTail a voice note whose transcript is disclosed below it.
 * @property highlighted the row a jump landed on (the brief highlight, iOS `highlightedMessageID`).
 * @property isMenuHero the copy lifted into the long-press menu (draws without its own menu affordances).
 */
@Immutable
data class MessageRowModel(
    val message: ChatMessage,
    val isNotes: Boolean,
    val peerName: String,
    val replyQuote: ReplyQuoteContent?,
    val transfer: MediaTransfer?,
    val reactionChips: List<ReactionChip>,
    val showsTranscriptTail: Boolean,
    val highlighted: Boolean,
    val isMenuHero: Boolean = false,
)

/**
 * What a bubble may ask of the screen around it (conversation-thread §4–§6; plan §1.7.13).
 * Implemented by the conversation screen (W3-THREAD-LIST); bubbles (W3-THREAD-BUBBLES) call it.
 *
 * Two memory notes shape it: a long press's release fires inner controls unless they ask
 * [allowsInnerTaps] first (*Hold release fires bubble controls*), and the row's own tap runs before
 * an inner control's, so inner controls report [claimTap] (*Row tap fires before inner controls*).
 *
 * **Seam (W2-INT), owner W3-THREAD-LIST in wave 3.**
 */
@Stable
interface BubbleContext {
    val myUserId: UUID?

    /** False while a long press on [messageId] is opening or held: inner controls ignore the release. */
    fun allowsInnerTaps(messageId: UUID): Boolean

    /** An inner control consumed the tap on [messageId]; the row's own tap action is skipped. */
    fun claimTap(messageId: UUID)

    fun onTapMedia(message: ChatMessage)
    fun onCancelDownload(message: ChatMessage)
    fun onRetry(message: ChatMessage)
    fun onTapQuote(messageId: UUID)
    fun onOpenLink(url: String)
    fun onToggleReaction(emoji: String, message: ChatMessage)
    fun onToggleTodo(message: ChatMessage)
    fun onTranscriptToggled(message: ChatMessage, expanded: Boolean)

    /** The bubble's bounds in the root, for the menu hero and reaction flights. */
    fun reportBubbleBounds(messageId: UUID, boundsInRoot: Rect)

    /** A reaction chip's bounds in the root, for reaction flights. */
    fun reportChipBounds(messageId: UUID, chipId: String, boundsInRoot: Rect)
}
