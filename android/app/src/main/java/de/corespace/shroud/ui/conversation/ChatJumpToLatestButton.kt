package de.corespace.shroud.ui.conversation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/** Numbers and words of the jump-to-latest control (`ChatJumpToLatestButton.swift:13-101`; conversation-thread §3.9). */
object JumpToLatestMetrics {
    /** The composer's circle, so the control reads as part of that cluster (`:20`). */
    const val SIZE = 40f

    /** The chat list's unread badge height (12 pt text, 3 pt above and below) (`:22`). */
    const val BADGE_HEIGHT = 20f

    /** Trailing inset: centred over the composer's 44 dp trailing slot (12 dp bar inset + 2) (CV:623-624). */
    const val TRAILING = 14f

    /** Above the composer bar (CV:625). */
    const val ABOVE_COMPOSER = 8f

    const val LABEL = "Jump to latest messages"

    /** The badge's digits: "99+" past 99 (`:76`). */
    fun badge(count: Int): String = if (count > 99) "99+" else "$count"

    /** TalkBack's value: "", "1 new message", "N new messages" (`:93-99`). */
    fun spokenCount(count: Int): String = when (count) {
        0 -> ""
        1 -> "1 new message"
        else -> "$count new messages"
    }
}

/**
 * Telegram's jump-to-latest control: a 40 dp glass circle over the composer's send / mic slot while
 * the reader is up in the history, with a count of the messages that came in under them
 * (`ChatJumpToLatestButton`, `ChatJumpToLatestButton.swift:3-101`; design `Jump to Latest` Hkkg9,
 * frame SQoMF).
 *
 * Human: it shows only once the reader has scrolled away themselves; a chat following its newest
 * message never shows it. The badge counts what the other side sent since the reader left the
 * bottom and goes when they get back there. Glyph `chevron-down` 20 in accent; a light haptic on
 * press, no scale (the glass swells instead). The badge — 12 SemiBold white tabular digits on an
 * accent capsule, at least 20 × 20 — sits centred on the circle's top edge and rolls its number.
 * Both come and go with `iconSwap` on `Motion.snappy` (a fade under Reduce Motion).
 *
 * Agent: pure presentation; the caller places it and decides [isVisible]. It holds its 40 dp slot
 * while hidden (the badge has a fixed edge to sit on) and takes no touches then. Hit area 48 dp
 * (decision D13). TalkBack: "Jump to latest messages", value [JumpToLatestMetrics.spokenCount]; the
 * badge itself is hidden.
 */
@Composable
fun ChatJumpToLatestButton(isVisible: Boolean, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val swap = Motion.iconSwap.respecting(reduceMotion)
    Box(modifier.size(JumpToLatestMetrics.SIZE.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = isVisible, enter = swap.enter, exit = swap.exit) {
            // 48 dp to the finger around the 40 dp glass (iOS 44, decision D13); the glass inside
            // sees the same press and swells.
            Box(
                Modifier
                    .requiredSize(48.dp)
                    .pressable(scale = 1f, dimming = 0f, onClick = onClick)
                    .clearAndSetSemantics {
                        contentDescription = JumpToLatestMetrics.LABEL
                        stateDescription = JumpToLatestMetrics.spokenCount(count)
                        role = Role.Button
                        // `this.`: the parameter of the same name would shadow the semantics action.
                        this.onClick {
                            onClick()
                            true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(JumpToLatestMetrics.SIZE.dp)
                        .glassSurface(CircleShape, GlassStyle.Regular, interactive = true),
                    contentAlignment = Alignment.Center,
                ) {
                    ShroudIcon(ShroudIcons.ChevronDown, colors.accent, size = 20.dp)
                }
            }
        }
        // Centred on the circle's top edge (memory *Overlay alignmentGuide ignored*: offset the
        // wrapper, not the badge, so its pop scales from its own centre).
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-JumpToLatestMetrics.BADGE_HEIGHT / 2).dp)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(visible = isVisible && count > 0, enter = swap.enter, exit = swap.exit) {
                JumpBadge(count)
            }
        }
    }
}

/** The chat list's unread badge: accent capsule, white count that rolls as it changes (`:74-89`). */
@Composable
private fun JumpBadge(count: Int) {
    val colors = ShroudTheme.colors
    val fontScale = LocalDensity.current.fontScale
    Box(
        Modifier
            .defaultMinSize(minWidth = JumpToLatestMetrics.BADGE_HEIGHT.dp, minHeight = JumpToLatestMetrics.BADGE_HEIGHT.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(Motion.snappy()) { if (up) it else -it } + fadeIn(Motion.snappy())) togetherWith
                    (slideOutVertically(Motion.snappy()) { if (up) -it else it } + fadeOut(Motion.snappy()))
            },
            contentAlignment = Alignment.Center,
            label = "jumpBadgeCount",
        ) { value ->
            // Fixed size inside a fixed capsule, like the list badge.
            BasicText(
                JumpToLatestMetrics.badge(value),
                style = inter(12f / fontScale, FontWeight.SemiBold, tabularDigits = true).copy(color = Color.White),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
