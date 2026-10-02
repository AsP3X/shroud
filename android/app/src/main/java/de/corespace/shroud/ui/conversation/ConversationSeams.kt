package de.corespace.shroud.ui.conversation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.messaging.reactions.ReactionMerge
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
 * **Seam (W2-INT), owner W3-THREAD-LIST in wave 3.** W3-THREAD-LIST added [accessibilityActions]
 * (defaulted, source-compatible): the row's actions, which only the bubble's merged node can carry.
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

    /**
     * A reaction chip's bounds in the root, for reaction flights. While a flight is on its way to
     * [messageId], the chip holding the flying emoji draws that emoji hidden and reports **the
     * emoji's** frame here, as it lays out (iOS `ReactionFlightFrameKey`, `MessageReactionChips.swift:
     * 96-103, 179-189`); the conversation lands the flight on it (`ReactionFlightState.land`).
     */
    fun reportChipBounds(messageId: UUID, chipId: String, boundsInRoot: Rect)

    /**
     * The row's TalkBack actions for [message], in this order: "Reply" (the swipe), "Message options"
     * (the long-press menu), "Copy", "Copy Link", "Delete" (`ConversationView.swift:880-883, 2243-2254`;
     * `MessageLongPressGesture.swift:132-134`; `SwipeToReply.swift:236-240`; conversation-thread §17).
     *
     * The bubble draws one merged accessibility node (its own actions — reactions, playback, links —
     * included), and a merged node keeps a single list of custom actions, so the bubble appends these
     * to its own: `customActions = bubbleActions + context.accessibilityActions(message)`. Empty for
     * the menu hero. Added by W3-THREAD-LIST (defaulted, so callers compile unchanged).
     */
    fun accessibilityActions(message: ChatMessage): List<CustomAccessibilityAction> = emptyList()
}

/**
 * Builds the thread's [MessageRowModel]s (iOS `rowKey(for:quoted:)`, `replyContent(for:quoted:)` and
 * `reactionChips(for:)`, `ConversationView.swift:1254-1263, 2091-2113, 2179-2191`;
 * conversation-thread §3.7). Pure: everything a bubble is drawn from comes in as a value, so a row
 * model changes — and its bubble redraws — only when its own message, quote, transfer, chips,
 * transcript tail or highlight changed.
 */
object MessageRows {
    /**
     * The row of [message]. [quoted] holds every quoted message still in the thread
     * ([Timeline.quotedMessages]); [transcriptTail] the voice notes whose transcripts unfold unasked
     * ([Timeline.transcriptTail]); [highlightedId] the row a jump landed on.
     */
    fun model(
        message: ChatMessage,
        isNotes: Boolean,
        peerName: String,
        myUserId: UUID?,
        quoted: Map<UUID, ChatMessage>,
        transfers: Map<UUID, MediaTransfer>,
        transcriptTail: Collection<UUID>,
        highlightedId: UUID?,
    ): MessageRowModel = MessageRowModel(
        message = message,
        isNotes = isNotes,
        peerName = peerName,
        replyQuote = replyQuote(message, quoted, peerName, myUserId),
        transfer = transfers[message.id],
        reactionChips = chips(message, myUserId),
        showsTranscriptTail = message.id in transcriptTail,
        highlighted = message.id == highlightedId,
    )

    /**
     * The header of a bubble that quotes something; null for an ordinary message
     * (`replyContent(for:quoted:)`, CV:1254-1265): the original when it is still here, else what
     * the sender sealed with the reply.
     */
    fun replyQuote(message: ChatMessage, quoted: Map<UUID, ChatMessage>, peerName: String, myUserId: UUID?): ReplyQuoteContent? {
        val reference = message.replyTo ?: return null
        return ReplyQuoteContent.make(reference, quoted[reference.messageId], peerName, myUserId)
    }

    /** One chip per person (`ReactionMerge.chips`); none on a tombstone (`reactionChips(for:)`, CV:2095-2096). */
    fun chips(message: ChatMessage, myUserId: UUID?): List<ReactionChip> =
        if (message.deleted || message.reactions.isEmpty()) emptyList() else ReactionMerge.chips(message.reactions, myUserId)

    /** The long-press menu's copy of [row]: the same bubble, inert, its chips no landing place (CV:2063-2072). */
    fun hero(row: MessageRowModel): MessageRowModel = row.copy(isMenuHero = true, highlighted = false)
}

/**
 * The width a message row offers its bubble: the thread's width less 16 dp on each side, measured by
 * the screen and provided to the list **and** the long-press menu's hero, so both size a bubble
 * identically (`chatRowWidth`, `ConversationView.swift:261-264`; conversation-thread §1.2). Bubbles
 * size themselves from it (`maxBubbleWidth`, thread §4.1); outside a conversation it is
 * [ChatRowWidth.Fallback] (288 dp, `MessageBubbleView.swift:123-133`).
 *
 * **Seam, owner W3-THREAD-LIST.** The bubbles (W3-THREAD-BUBBLES) read their own
 * `bubble.LocalChatRowWidth` (unspecified outside a thread, so a bubble then measures its row); the
 * conversation screen provides the same measured width through both.
 */
val LocalChatRowWidth: ProvidableCompositionLocal<Dp> = compositionLocalOf { ChatRowWidth.Fallback }

/** The numbers behind [LocalChatRowWidth]. */
object ChatRowWidth {
    /** The row width when the thread has not been measured (`fallbackRowWidth`, MBV:123-133). */
    val Fallback: Dp = 288.dp

    /** Horizontal inset of the message list (`threadHorizontalInset`, CV:460-461). */
    val ThreadInset: Dp = 16.dp

    /** `max(0, threadWidth − 2·16)` (CV:264). */
    fun of(threadWidth: Dp): Dp = (threadWidth - ThreadInset * 2).coerceAtLeast(0.dp)
}
