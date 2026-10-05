package de.corespace.shroud.ui.conversation.bubble

import android.text.format.DateFormat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import de.corespace.shroud.core.messaging.ChatListFormatting
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.presentedKind
import de.corespace.shroud.ui.conversation.BubbleContext
import de.corespace.shroud.ui.conversation.MessageRowModel
import de.corespace.shroud.ui.conversation.reactions.ReactionChipContent
import de.corespace.shroud.ui.theme.rememberHaptics
import java.time.Instant
import java.time.ZoneId

/**
 * Row-level TalkBack actions the thread adds to a bubble's node — Reply, Message options, Copy, Copy
 * Link, Delete (conversation-thread §3.7 item 3, §17). The bubble is one merged node with its own
 * actions (reactions, "Show replied message", "Open link", "Retry", playback); the thread's are
 * appended so both reach TalkBack on the same node. Provided by W3-THREAD-LIST around each row.
 */
val LocalMessageRowActions = compositionLocalOf<List<CustomAccessibilityAction>> { emptyList() }

/** Which bubble a row draws (`ConversationView.swift:1520-1676`; web `rowBubble.ts`). */
enum class BubbleKind {
    Text,
    Photo,
    Video,
    Voice,
    Todo,
    File,
    ;

    companion object {
        /**
         * By the message's presented kind: a deleted photo, video or voice note draws the text
         * tombstone (`MessagingController.swift:5889-5893`). The web's "photo without bytes → text"
         * rule does not apply: iOS draws the photo bubble with its placeholder and download disc.
         */
        fun of(message: ChatMessage): BubbleKind = when (message.presentedKind) {
            ChatMessageKind.Text -> Text
            ChatMessageKind.Image -> Photo
            ChatMessageKind.Video -> Video
            ChatMessageKind.Voice -> Voice
            ChatMessageKind.Todo -> Todo
            ChatMessageKind.File -> File
        }
    }
}

/**
 * One message bubble: text, links, reply quote, media, voice, to-do, file, reactions and the meta row
 * (conversation-thread §4–§14; iOS `ConversationView.messageRow`, `ConversationView.swift:1504-1677`,
 * and the bubble views it builds). The thread (W3-THREAD-LIST) wraps it in the row: long press, swipe
 * to reply, highlight, insertion transition; this draws what is inside.
 *
 * - Fills the row's width and puts the bubble on the speaker's side; the row width comes from
 *   [LocalChatRowWidth] (the thread measures it once), else from the row's own constraints.
 * - [MessageRowModel.isMenuHero]: the copy lifted into the long-press menu draws the bubble alone,
 *   without its gutter, inert (iOS `MessageMenuHeroContent`, `MessageActionMenu.swift:906-971`).
 * - Inner controls (quote, links, preview, chips, discs, play, transcript, to-do) claim the tap
 *   ([BubbleContext.claimTap]) and act only when [BubbleContext.allowsInnerTaps] (memory: *Hold release
 *   fires bubble controls*).
 * - Reports the drawn bubble's bounds (not the row's) for the menu hero and flights.
 * - TalkBack: one node per bubble with the iOS label and actions ([BubbleAccessibility]), plus
 *   [LocalMessageRowActions].
 *
 * Owner: W3-THREAD-BUBBLES (entry point published by W2-INT, plan §1.7.13).
 */
@Composable
fun MessageBubble(row: MessageRowModel, context: BubbleContext, modifier: Modifier = Modifier) {
    val services = rememberBubbleServices()
    // Idempotent: the first bubble wires the caches to messaging and the bundled emoji font, before it draws.
    remember(services) {
        BubbleMemory.install(services)
        BubbleEmoji.install(services.context)
        services
    }
    val rowWidth = LocalChatRowWidth.current
    if (rowWidth.isSpecified) {
        BubbleBody(row, context, services, rowWidth, modifier)
    } else {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val measured = maxWidth
            CompositionLocalProvider(LocalChatRowWidth provides measured) {
                BubbleBody(row, context, services, measured, Modifier)
            }
        }
    }
}

@Composable
private fun BubbleBody(row: MessageRowModel, context: BubbleContext, services: BubbleServices, rowWidth: Dp, modifier: Modifier) {
    val message = row.message
    val haptics = rememberHaptics()
    val handlers = remember(context, message.id, row.isMenuHero, haptics) {
        BubbleHandlers(context, message.id, interactive = !row.isMenuHero, haptic = haptics)
    }
    val time = rememberClockTime(message.createdAt)
    val chips = rememberChips(row, context, services)
    val canReact = !row.isMenuHero && !message.deleted && services.canReact(message)
    val onReaction: ((String) -> Unit)? = if (canReact) {
        { emoji -> handlers.run { context.onToggleReaction(emoji, message) } }
    } else {
        null
    }
    val reportBounds = if (row.isMenuHero) Modifier else Modifier.onGloballyPositioned { context.reportBubbleBounds(message.id, it.boundsInRoot()) }
    val maxBubble = Dp(MessageBubbleMetrics.maxBubbleWidth(rowWidth.value))
    val parts = BubbleParts(row, handlers, chips, onReaction, time, maxBubble, rowWidth, reportBounds)
    // A reaction flying in lands on this row's chip: its emoji reports where it is (`ReactionFlightFrameKey`,
    // `MessageReactionChips.swift:179-189`). The hero's chips are no landing place.
    val flightReporter = remember(context, message.id, row.isMenuHero) {
        if (row.isMenuHero) null else context.flightReporter(message.id)
    }
    CompositionLocalProvider(LocalFlightReporter provides flightReporter) {
        when (BubbleKind.of(message)) {
            BubbleKind.Text -> TextMessageBubble(parts, services, modifier)
            BubbleKind.Photo -> PhotoMessageBubble(parts, context, services, modifier)
            BubbleKind.Video -> VideoMessageBubble(parts, context, services, modifier)
            BubbleKind.Voice -> VoiceMessageBubble(parts, context, services, modifier)
            BubbleKind.Todo -> TodoMessageBubble(parts, context, modifier)
            BubbleKind.File -> FileMessageBubble(parts, context, modifier)
        }
    }
}

/** What every bubble kind draws from: the row, the gated handlers, chips, time and widths. */
@Stable
internal class BubbleParts(
    val row: MessageRowModel,
    val handlers: BubbleHandlers,
    val chips: List<ReactionChipContent>,
    /** Toggles a reaction; null where reacting cannot happen (sending, failed, Notes, the hero). */
    val onReaction: ((String) -> Unit)?,
    val time: String,
    /** The row minus the opposite gutter (`MessageBubbleView.swift:415-420`). */
    val maxBubbleWidth: Dp,
    val rowWidth: Dp,
    /** Reports the drawn bubble's bounds; nothing in the hero. */
    val reportBounds: Modifier,
) {
    val message: ChatMessage get() = row.message
    val isMine: Boolean get() = row.message.isMine
    val embedded: Boolean get() = !row.isMenuHero
}

/**
 * The bubble's inner controls, gated: every one claims the tap so the row's own tap stays out, and
 * acts only when the thread allows inner taps — never on the release of the hold that opened the menu
 * (`MessageLongPressGesture.swift:47-62`; memory: *Hold release fires bubble controls*). Inert in the
 * long-press hero.
 */
@Stable
internal class BubbleHandlers(
    private val context: BubbleContext,
    private val messageId: java.util.UUID,
    val interactive: Boolean,
    private val haptic: (Haptic) -> Unit,
) {
    fun claim() {
        if (interactive) context.claimTap(messageId)
    }

    fun allows(): Boolean = interactive && context.allowsInnerTaps(messageId)

    /** Claims, then runs [action] when inner taps are allowed. */
    fun run(action: () -> Unit) {
        if (!interactive) return
        context.claimTap(messageId)
        if (context.allowsInnerTaps(messageId)) action()
    }

    fun haptic(kind: Haptic) = haptic.invoke(kind)

    /** Jump to the quoted message (light haptic, `ReplyQuoteView.swift:215-223`); null in the hero. */
    fun quoteTap(message: ChatMessage): (() -> Unit)? {
        val reference = message.replyTo ?: return null
        if (!interactive) return null
        return {
            run {
                haptic(Haptic.Light)
                context.onTapQuote(reference.messageId)
            }
        }
    }

    /** Opens a link through the screen's opener (which refuses while a menu is up); null in the hero. */
    val openLink: ((String) -> Unit)? = if (interactive) { url -> run { context.onOpenLink(url) } } else null
}

/**
 * The bubble's clock time: short time in the system's 12/24-hour setting (`clockTimeLabel`,
 * `ChatListFormatting.swift:67-71`; conversation-thread §4.5).
 */
@Composable
internal fun rememberClockTime(createdAt: Instant): String {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    val locale = LocalConfiguration.current.locales[0]
    return remember(createdAt, is24, locale) { ChatListFormatting.clockTimeLabel(createdAt, ZoneId.systemDefault(), locale, is24) }
}

/** The row's chips with names and our set (conversation-thread §14.1); none on a tombstone. */
@Composable
private fun rememberChips(row: MessageRowModel, context: BubbleContext, services: BubbleServices): List<ReactionChipContent> {
    val message = row.message
    if (message.deleted || row.reactionChips.isEmpty()) return emptyList()
    val myUsername = services.myUsername
    val mine = services.myReactions(message)
    return remember(row.reactionChips, context.myUserId, myUsername, row.peerName, mine, row.isMenuHero, message.id) {
        ReactionChipContent.from(
            chips = row.reactionChips,
            myUserId = context.myUserId,
            myUsername = myUsername,
            peerName = row.peerName,
            myEmojis = mine,
            // The hero's chips are not anchored: no flight lands there.
            messageId = if (row.isMenuHero) null else message.id,
        )
    }
}

/** Reports a flight-target emoji's frame to the thread (`ReactionFlightFrameKey`). */
internal fun BubbleContext.flightReporter(messageId: java.util.UUID): (String, Rect) -> Unit =
    { chipId, rect -> reportChipBounds(messageId, chipId, rect) }
