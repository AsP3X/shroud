package de.corespace.shroud.ui.conversation.reactions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.model.ReactionChip
import de.corespace.shroud.ui.components.Avatar
import de.corespace.shroud.ui.components.AvatarPalette
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.util.Locale
import java.util.UUID

/**
 * What one reaction chip shows: one person's emoji and their face — or both faces when the two picked
 * exactly the same emoji (iOS `ReactionChipContent`, `MessageReactionChips.swift:3-31`;
 * conversation-thread §14.1).
 *
 * @property myEmojis our own set on the message (any chip): the other person's emoji we have already
 *   are not added again.
 * @property messageId the message the chip belongs to, so a reaction flying in can find it; null where
 *   no flight should land (the long-press hero).
 */
@Immutable
data class ReactionChipContent(
    val emojis: List<String>,
    val reactors: List<Reactor>,
    val includesMe: Boolean,
    val myEmojis: List<String> = emptyList(),
    val messageId: UUID? = null,
) {
    /** A face on the chip: the display name it draws initials of; "you" for TalkBack when [isMe]. */
    @Immutable
    data class Reactor(val id: UUID, val name: String, val isMe: Boolean = false) {
        override fun toString(): String = "Reactor(isMe=$isMe)"
    }

    /** Stable identity: the user ids joined with "+" (`:26`; the same as [ReactionChip.id]). */
    val id: String get() = reactors.joinToString("+") { Ids.wire(it.id) }

    /** The reactors as a spoken list, "you" for us (`:28-30`). */
    val spokenNames: String get() = ReactionSpeech.join(reactors.map { if (it.isMe) "you" else it.name })

    /** Never prints the emoji or the names. */
    override fun toString(): String = "ReactionChipContent(emoji=${emojis.size}, reactors=${reactors.size}, includesMe=$includesMe)"

    companion object {
        /**
         * Chips for a bubble from the merged reactions (conversation-thread §14.1,
         * `ConversationView.swift:2091-2113`): our face named `myUsername ?: "You"`, theirs [peerName].
         */
        fun from(
            chips: List<ReactionChip>,
            myUserId: UUID?,
            myUsername: String?,
            peerName: String,
            myEmojis: List<String>,
            messageId: UUID?,
        ): List<ReactionChipContent> = chips.map { chip ->
            ReactionChipContent(
                emojis = chip.emojis,
                reactors = chip.userIds.map { id ->
                    if (myUserId != null && id == myUserId) Reactor(id, myUsername ?: "You", isMe = true) else Reactor(id, peerName)
                },
                includesMe = chip.includesMe,
                myEmojis = myEmojis,
                messageId = messageId,
            )
        }
    }
}

/** Spoken lists of names; ICU's list format on the phone (`ListFormatter.localizedString`, `:29`). */
object ReactionSpeech {
    /** Replaceable for JVM tests, which have no `android.icu`. */
    @Volatile
    var joiner: (List<String>) -> String = { names ->
        android.icu.text.ListFormatter.getInstance(Locale.getDefault()).format(names)
    }

    fun join(names: List<String>): String = joiner(names)
}

/** The quick reaction of a double tap and of the bubble's TalkBack action (`MessageReactionBar.quickReaction`). */
const val QUICK_REACTION = "❤️"

/** "Reactions: anna ❤️ 🔥, you 👍" — for a bubble TalkBack reads as one node (`:33-39`). */
fun List<ReactionChipContent>.spokenSummary(): String? {
    if (isEmpty()) return null
    return "Reactions: " + joinToString(", ") { chip -> "${chip.spokenNames} ${chip.emojis.joinToString(" ")}" }
}

/** Every emoji on the bubble, once, in chip order, and whether it is ours (`:41-48`). */
fun List<ReactionChipContent>.emojiActions(): List<Pair<String, Boolean>> {
    val mine = filter { it.includesMe }.flatMap { it.emojis }.toSet()
    val seen = HashSet<String>()
    return flatMap { it.emojis }.mapNotNull { emoji -> if (seen.add(emoji)) emoji to (emoji in mine) else null }
}

/**
 * The chips as named actions on the bubble, which hides their buttons — and the quick reaction —
 * when reacting is possible (`MessageReactionChips.swift:51-73`; conversation-thread §14.5).
 */
fun reactionAccessibilityActions(chips: List<ReactionChipContent>, onTap: ((String) -> Unit)?): List<CustomAccessibilityAction> {
    if (onTap == null) return emptyList()
    val actions = chips.emojiActions().map { (emoji, isMine) ->
        CustomAccessibilityAction(if (isMine) "Remove your $emoji reaction" else "React with $emoji") {
            onTap(emoji)
            true
        }
    }
    val quick = if (chips.none { QUICK_REACTION in it.emojis }) {
        listOf(
            CustomAccessibilityAction("React with $QUICK_REACTION") {
                onTap(QUICK_REACTION)
                true
            },
        )
    } else {
        emptyList()
    }
    return actions + quick
}

/** The labels of [reactionAccessibilityActions], for tests and the bubble labels. */
fun reactionActionLabels(chips: List<ReactionChipContent>): List<String> =
    chips.emojiActions().map { (emoji, isMine) -> if (isMine) "Remove your $emoji reaction" else "React with $emoji" } +
        if (chips.none { QUICK_REACTION in it.emojis }) listOf("React with $QUICK_REACTION") else emptyList()

/**
 * A reaction flying in (the thread's `ReactionFlight`, W3-THREAD-LIST): while it is under way the chip
 * keeps that emoji invisible and reports where it is, so the flight lands on it (iOS
 * `reactionFlightTarget`, `MessageReactionChips.swift:96-103, 179-189`).
 */
@Immutable
data class ReactionFlightTarget(val messageId: UUID, val emoji: String)

/** Provided by the conversation screen while a flight is pending. */
val LocalReactionFlightTarget = compositionLocalOf<ReactionFlightTarget?> { null }

/** Chip geometry (`MessageReactionChips.swift:85-94`). */
object ReactionChipMetrics {
    val height = 30.dp
    const val EMOJI_FONT_SIZE = 17f
    val emojiBox = 20.dp
    val avatarSize = 24.dp
    val avatarStep = 12.dp
    const val MAX_AVATARS = 3

    /** Ours taken back; theirs added unless we have it already (`:157-166`). */
    fun acts(chip: ReactionChipContent, emoji: String, hasHandler: Boolean): Boolean =
        hasHandler && (chip.includesMe || emoji !in chip.myEmojis)

    /** The chip's fill on our (accent) bubble or theirs, ours or theirs (`:105-112`). */
    @Composable
    fun fill(onOutgoingBubble: Boolean, includesMe: Boolean): Color {
        val colors = ShroudTheme.colors
        return when {
            onOutgoingBubble && includesMe -> Color.White
            onOutgoingBubble -> Color.White.copy(alpha = 0.2f)
            includesMe -> colors.accent
            else -> colors.accent.copy(alpha = 0.14f)
        }
    }
}

/**
 * A reaction chip inside a bubble — iOS `ReactionChipView` (`MessageReactionChips.swift:75-204`;
 * conversation-thread §14.2): one person's emoji, then their face (Telegram 1:1 shows faces, not
 * counts), filled when it is ours. Each emoji is its own tap target: ours are taken back, theirs are
 * added to ours. A set wider than the bubble wraps inside the chip ([ReactionEmojiFlow]).
 *
 * Every touch on the chip is the chip's: [onClaim] runs as the finger lands on an emoji (the row
 * reads the release first otherwise) and on any tap of the faces or padding, so the photo underneath
 * never opens. [enabled] false (a long press holding the bubble) ignores the release.
 */
@Composable
fun ReactionChipView(
    chip: ReactionChipContent,
    onOutgoingBubble: Boolean,
    onTap: ((String) -> Unit)?,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: () -> Boolean = { true },
    onFlightTargetBounds: ((Rect) -> Unit)? = null,
) {
    val fill = ReactionChipMetrics.fill(onOutgoingBubble, chip.includesMe)
    val flight = LocalReactionFlightTarget.current
    val claim by rememberUpdatedState(onClaim)
    val shape = RoundedCornerShape(ReactionChipMetrics.height / 2)
    Row(
        modifier
            .defaultMinSize(minHeight = ReactionChipMetrics.height)
            .background(fill, shape)
            // The padding around the emoji and the faces only claims, so the photo stays shut (`:152-154`).
            .pointerInput(Unit) { detectTapGestures { claim() } }
            .padding(start = 6.dp, end = if (chip.reactors.isEmpty()) 6.dp else 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ReactionEmojiFlow(spacing = 2.dp) {
            for (emoji in chip.emojis) {
                key(emoji) {
                    val isTarget = chip.includesMe && flight != null && chip.messageId != null &&
                        flight.messageId == chip.messageId && flight.emoji == emoji
                    EmojiButton(
                        emoji = emoji,
                        acts = ReactionChipMetrics.acts(chip, emoji, onTap != null),
                        hidden = isTarget,
                        onPressDown = { claim() },
                        onTap = { if (enabled()) onTap?.invoke(emoji) },
                        onBounds = if (isTarget) onFlightTargetBounds else null,
                    )
                }
            }
        }
        if (chip.reactors.isNotEmpty()) {
            Faces(chip, fill) {
                claim()
                // A one-emoji chip takes a tap on its faces as its emoji's (`:136-143`).
                val only = chip.emojis.singleOrNull()
                if (enabled() && only != null && ReactionChipMetrics.acts(chip, only, onTap != null)) onTap?.invoke(only)
            }
        }
    }
}

/** Up to three faces overlapping by half, stroked in the chip's fill; one tap target (`:122-145`). */
@Composable
private fun Faces(chip: ReactionChipContent, fill: Color, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Row(
        Modifier
            .height(ReactionChipMetrics.height)
            .pointerInput(Unit) { detectTapGestures { tap() } }
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(ReactionChipMetrics.avatarStep - ReactionChipMetrics.avatarSize),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (reactor in chip.reactors.take(ReactionChipMetrics.MAX_AVATARS)) {
            key(reactor.id) {
                val seed = AvatarPalette.seed(reactor.name, reactor.id)
                Avatar(
                    initials = AvatarPalette.initials(reactor.name),
                    size = ReactionChipMetrics.avatarSize,
                    brush = AvatarPalette.brush(seed),
                    fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                    modifier = Modifier.border(1.5.dp, fill, CircleShape),
                )
            }
        }
    }
}

/**
 * One emoji: 17 sp in a 20 dp box, the chip's full height and 2 dp either side as target. It squashes
 * to 0.88 while pressed (dims to 0.6 under reduce motion) and claims the touch as the finger lands
 * (`ReactionChipButtonStyle`, `:206-224`).
 */
@Composable
private fun EmojiButton(
    emoji: String,
    acts: Boolean,
    hidden: Boolean,
    onPressDown: () -> Unit,
    onTap: () -> Unit,
    onBounds: ((Rect) -> Unit)?,
) {
    var pressed by remember { mutableStateOf(false) }
    val reduceMotion = ShroudTheme.reduceMotion
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.88f else 1f,
        animationSpec = if (pressed) Motion.press() else Motion.release(),
        label = "emojiPress",
    )
    val alpha = if (pressed && reduceMotion) 0.6f else 1f
    val down by rememberUpdatedState(onPressDown)
    val tap by rememberUpdatedState(onTap)
    val actsNow by rememberUpdatedState(acts)
    Box(
        Modifier
            .height(ReactionChipMetrics.height)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    first.consume()
                    pressed = true
                    down()
                    val up = waitForUpOrCancellation()
                    pressed = false
                    if (up != null) {
                        up.consume()
                        // Claimed even when it does nothing: the photo underneath must not open (`:170-173`).
                        if (actsNow) tap()
                    }
                }
            }
            .padding(horizontal = 2.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            emoji,
            style = inter(ReactionChipMetrics.EMOJI_FONT_SIZE).copy(textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .size(ReactionChipMetrics.emojiBox)
                .graphicsLayer { this.alpha = if (hidden) 0f else 1f }
                .then(if (onBounds != null) Modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) } else Modifier),
        )
    }
}

/**
 * The foot of a reacted bubble: chips, then the bubble's own time and ticks — iOS `ReactionFooter`
 * (`MessageReactionChips.swift:370-394`). Inside a bubble TalkBack reads as one node the chips are
 * hidden; they come back as the bubble's actions ([reactionAccessibilityActions]).
 *
 * A chip that appears after the foot was first drawn grows in from 0.4 with a fade (opacity only
 * under reduce motion); the bubble's size change rides the row's own animation.
 */
@Composable
fun ReactionFooter(
    chips: List<ReactionChipContent>,
    onOutgoingBubble: Boolean,
    onTap: ((String) -> Unit)?,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: () -> Boolean = { true },
    onFlightTargetBounds: ((chipId: String, Rect) -> Unit)? = null,
    meta: @Composable () -> Unit,
) {
    val firstChips = remember { chips.map { it.id }.toSet() }
    val reduceMotion = ShroudTheme.reduceMotion
    ReactionFooterLayout(modifier.clearAndSetSemantics {}) {
        for (chip in chips) {
            key(chip.id) {
                val appear = remember { Animatable(if (chip.id in firstChips) 1f else 0f) }
                LaunchedEffect(Unit) {
                    if (appear.value < 1f) appear.animateTo(1f, Motion.respecting(reduceMotion, Motion.bouncy()))
                }
                ReactionChipView(
                    chip = chip,
                    onOutgoingBubble = onOutgoingBubble,
                    onTap = onTap,
                    onClaim = onClaim,
                    enabled = enabled,
                    onFlightTargetBounds = onFlightTargetBounds?.let { report -> { rect -> report(chip.id, rect) } },
                    modifier = Modifier.graphicsLayer {
                        val progress = appear.value
                        alpha = progress.coerceIn(0f, 1f)
                        if (!reduceMotion) {
                            val s = 0.4f + 0.6f * progress
                            scaleX = s
                            scaleY = s
                        }
                    },
                )
            }
        }
        meta()
    }
}
