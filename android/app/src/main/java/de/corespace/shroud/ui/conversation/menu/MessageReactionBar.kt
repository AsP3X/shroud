package de.corespace.shroud.ui.conversation.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.messaging.reactions.ReactionSet
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.ShroudIcons

/**
 * The quick reaction bar's numbers (`MessageReactionBar`, `MessageActionMenu.swift:31-71`;
 * conversation-thread §16.6). Pure; dp.
 */
object MessageReactionBarMetrics {
    /** Telegram's quick seven (`ReactionSet.quick`). */
    val reactions: List<String> get() = ReactionSet.quick

    /** Telegram's double tap (and the TalkBack action standing in for it) (`:48`). */
    const val QUICK_REACTION = "❤️"

    /** Everything the bar grows into ("More"): the quick seven first, so they keep their places (`:51`). */
    val expanded: List<String> get() = ReactionSet.all

    const val EMOJI_SIZE = 34f
    const val MORE_SIZE = 30f
    const val ITEM_SPACING = 6f
    const val HORIZONTAL_PADDING = 12f
    const val VERTICAL_PADDING = 8f

    /** Emoji glyph size in the bar and the grid (26 pt). */
    const val GLYPH_SIZE = 26f

    /** Intrinsic capsule width: 7·34 + 30 + 7·6 + 2·12 = 334 (`:59-66`). */
    val barWidth: Float
        get() {
            val count = reactions.size
            return count * EMOJI_SIZE + MORE_SIZE + count * ITEM_SPACING + HORIZONTAL_PADDING * 2
        }

    /** Intrinsic capsule height: 34 + 2·8 = 50 (`:69-71`). */
    val barHeight: Float get() = maxOf(EMOJI_SIZE, MORE_SIZE) + VERTICAL_PADDING * 2
}

/**
 * Where each emoji of the bar (or grid) is on screen, so a pick can fly from it (`ReactionPickFrames`,
 * `MessageActionMenu.swift:25-29`). Not snapshot state: writing a frame never recomposes the bar.
 */
class ReactionPickFrames {
    private val frames = HashMap<String, Rect>()

    fun report(emoji: String, boundsInRoot: Rect) {
        frames[emoji] = boundsInRoot
    }

    operator fun get(emoji: String): Rect? = frames[emoji]
}

/**
 * The quick reaction bar over the lifted bubble (`MessageReactionBar`, `MessageActionMenu.swift:31-129`;
 * conversation-thread §16.6; design `Conversation — * Message Menu`).
 *
 * Human: seven emoji at 26 in 34 dp boxes, ours on a white @ 0.18 ring (38 dp), squashing hard to
 * 0.78 under the finger — the most playful control in the app — then "More" (a 30 dp circle, caret
 * down) that grows the bar into the full set. Standalone it is a capsule (`surface` @ 0.94 with a
 * white @ 0.08 rim); inside the panel ([drawsCapsule] false) the panel draws the surface.
 *
 * Agent: [onReaction] gets the emoji and its frame in root px (the start of its flight).
 * [emojiModifier] lets the panel glide the quick seven into the grid (shared elements).
 */
@Composable
fun MessageReactionBar(
    onReaction: (String, Rect?) -> Unit,
    onMore: () -> Unit,
    selected: Set<String>,
    modifier: Modifier = Modifier,
    drawsCapsule: Boolean = true,
    emojiModifier: @Composable (String) -> Modifier = { Modifier },
) {
    val frames = remember { ReactionPickFrames() }
    val capsule = if (drawsCapsule) {
        Modifier
            .clip(CircleShape)
            .background(MessageReactionPanelLayout.surface.copy(alpha = 0.94f))
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), CircleShape)
    } else {
        Modifier
    }
    Row(
        modifier
            .then(capsule)
            .padding(
                horizontal = MessageReactionBarMetrics.HORIZONTAL_PADDING.dp,
                vertical = MessageReactionBarMetrics.VERTICAL_PADDING.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(MessageReactionBarMetrics.ITEM_SPACING.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MessageReactionBarMetrics.reactions.forEach { emoji ->
            ReactionCell(
                emoji = emoji,
                cellSize = MessageReactionBarMetrics.EMOJI_SIZE,
                ringSize = MessageReactionBarMetrics.EMOJI_SIZE + 4,
                selected = emoji in selected,
                onClick = { onReaction(emoji, frames[emoji]) },
                onPositioned = { frames.report(emoji, it) },
                glyphModifier = emojiModifier(emoji),
            )
        }
        Box(
            Modifier
                .size(MessageReactionBarMetrics.MORE_SIZE.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .pressable(scale = 0.85f, dimming = 0f, onClick = onMore)
                .semantics { contentDescription = "More reactions" },
            contentAlignment = Alignment.Center,
        ) {
            ShroudIcon(ShroudIcons.CaretDownBold, Color.White.copy(alpha = 0.85f), size = 13.dp)
        }
    }
}

/**
 * One emoji button of the bar or grid (`MessageActionMenu.swift:76-97`, `:194-213`): glyph 26 in a
 * [cellSize] box, our pick on a white @ 0.18 circle of [ringSize], press 0.78 without dimming.
 * TalkBack: the emoji, with its selected state.
 */
@Composable
internal fun ReactionCell(
    emoji: String,
    cellSize: Float,
    ringSize: Float,
    selected: Boolean,
    onClick: () -> Unit,
    onPositioned: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    glyphModifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(cellSize.dp)
            .pressable(scale = 0.78f, dimming = 0f, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = emoji
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(ringSize.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)))
        }
        EmojiGlyph(
            emoji,
            MessageReactionBarMetrics.GLYPH_SIZE,
            glyphModifier
                .size(cellSize.dp)
                .onGloballyPositioned { onPositioned(it.boundsInRoot()) },
        )
    }
}

/**
 * An emoji at a fixed design size: iOS `.system(size: 26)` does not follow Dynamic Type, and the
 * glyph sits in a fixed box, so it keeps its size at any font scale (as `Avatar` does).
 */
@Composable
internal fun EmojiGlyph(emoji: String, size: Float, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    Box(modifier, contentAlignment = Alignment.Center) {
        BasicText(
            text = emoji,
            style = TextStyle(fontSize = (size / fontScale).sp, textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
        )
    }
}
