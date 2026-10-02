package de.corespace.shroud.ui.conversation.menu

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/**
 * The overlay's safe area as the menu layout needs it (iOS `EdgeInsets`, only top and bottom count):
 * the status bar on top; at the bottom the larger of the navigation bar and the **keyboard**
 * (`WindowInsets.systemBars.union(ime)`, conversation-thread §16.4, §23.6).
 */
@Immutable
data class MenuInsets(val top: Float, val bottom: Float)

/**
 * Where the long-press menu's parts go, by Telegram's rules (`MessageMenuLayout`,
 * `MessageActionMenu.swift:530-627`; conversation-thread §16.4). A pure port; every number is in dp.
 *
 * Human: The reaction bar always sits right on top of the bubble and the action card right under
 * it. When that stack does not fit where the bubble is, the *bubble* moves — down from under the
 * header, up from the composer — just far enough, and the dimmed thread behind it stays where it
 * was. A message too tall to show together with its card starts under the reaction bar and the
 * stack scrolls, opening at its bottom so the card is in reach.
 *
 * Agent: pure geometry in the overlay's (full-window) coordinates. Margins are Telegram's: 8 dp
 * under the status bar, 10 dp above the bottom inset, 12 dp from the sides.
 */
object MessageMenuLayout {
    /** The fixed parts of the stack (`MessageActionMenu.swift:545-556`). */
    @Immutable
    data class Metrics(
        val reactionSize: Size,
        val cardSize: Size,
        /** Between the reaction bar and the bubble, and between the bubble and the card. */
        val spacing: Float = 10f,
        /** Closest the bar or the card may come to the screen's sides. */
        val sideInset: Float = 12f,
        /** Below the status bar. */
        val topMargin: Float = 8f,
        /** Above the navigation bar or the keyboard. */
        val bottomMargin: Float = 10f,
    )

    /** The resting stack for one bubble (`MessageActionMenu.swift:559-574`). */
    @Immutable
    data class Plan(
        /** The lifted bubble at rest, in scroll-content coordinates — screen coordinates unless [scrolls]. */
        val hero: Rect,
        /** Height of the scrollable stack: the container's own unless the message is too tall for its card. */
        val contentHeight: Float,
        val containerSize: Size,
        /** Highest the reaction bar may sit, in screen coordinates. */
        val reactionMinY: Float,
    ) {
        /** The message and its card are taller than the screen allows. */
        val scrolls: Boolean get() = contentHeight > containerSize.height + 0.5f

        /** Scroll offset that shows the stack's bottom — where a scrolling stack opens. */
        val initialOffset: Float get() = max(0f, contentHeight - containerSize.height)
    }

    /** The reaction bar and the card around the bubble as drawn right now. */
    @Immutable
    data class Chrome(val reactions: Rect, val card: Rect)

    /** The resting stack for a bubble whose list slot is [source] (`MessageActionMenu.swift:577-602`). */
    fun plan(source: Rect, container: Size, safeArea: MenuInsets, metrics: Metrics): Plan {
        val reactionMinY = safeArea.top + metrics.topMargin
        val bottomLimit = container.height - safeArea.bottom - metrics.bottomMargin
        // Highest the bubble may go: the reaction bar has to fit above it.
        val highest = reactionMinY + metrics.reactionSize.height + metrics.spacing
        val belowBubble = metrics.spacing + metrics.cardSize.height

        var y = max(source.top, highest)
        val overshoot = y + source.height + belowBubble - bottomLimit
        if (overshoot > 0) y -= overshoot
        y = max(y, highest)

        val hero = Rect(source.left, y, source.left + source.width, y + source.height)
        val stackBottom = hero.bottom + belowBubble
        return Plan(
            hero = hero,
            contentHeight = max(container.height, stackBottom + container.height - bottomLimit),
            containerSize = container,
            reactionMinY = reactionMinY,
        )
    }

    /**
     * Reaction bar and card around the bubble as it is drawn right now ([hero] in screen
     * coordinates — mid-flight, or scrolled), on the bubble's side and inside the screen's sides
     * (`MessageActionMenu.swift:609-626`). The bar never rises above [Plan.reactionMinY]: over a
     * scrolled tall message it stays pinned there.
     */
    fun chrome(hero: Rect, isMine: Boolean, plan: Plan, metrics: Metrics): Chrome {
        val width = plan.containerSize.width
        fun minX(itemWidth: Float): Float {
            val preferred = if (isMine) hero.right - itemWidth else hero.left
            val upper = max(metrics.sideInset, width - metrics.sideInset - itemWidth)
            return min(max(preferred, metrics.sideInset), upper)
        }
        val reactionY = max(plan.reactionMinY, hero.top - metrics.spacing - metrics.reactionSize.height)
        val reactionX = minX(metrics.reactionSize.width)
        val cardX = minX(metrics.cardSize.width)
        val cardY = hero.bottom + metrics.spacing
        return Chrome(
            reactions = Rect(reactionX, reactionY, reactionX + metrics.reactionSize.width, reactionY + metrics.reactionSize.height),
            card = Rect(cardX, cardY, cardX + metrics.cardSize.width, cardY + metrics.cardSize.height),
        )
    }

    /**
     * Where the long-press menu's bubble lifts from: the drawn bubble's frame when it is a frame of
     * this row, otherwise the bubble's size at the row's place (`ConversationView.menuSourceFrame`,
     * `ConversationView.swift:1939-1955`; conversation-thread §13.3).
     *
     * Human: The bubble reports its frame as it lays out, and the last report can be one made
     * mid-way through the chat's opening transition — half a screen off. The row's frame comes fresh
     * from the press itself, so it is the judge: a stored bubble frame that does not sit inside the
     * row (with 2 dp of slack) is stale, and the bubble is placed at the row's leading (theirs) or
     * trailing (ours) edge, never larger than the row.
     */
    fun sourceFrame(bubble: Rect?, row: Rect, isMine: Boolean): Rect {
        if (bubble == null) return row
        if (row.inflate(2f).containsRect(bubble)) return bubble
        val width = min(bubble.width, row.width)
        val height = min(bubble.height, row.height)
        val left = if (isMine) row.right - width else row.left
        return Rect(left, row.top, left + width, row.top + height)
    }

    /** Linear interpolation of two rects (`MessageMenuOverlay.lerp`, `MessageActionMenu.swift:776-783`). */
    fun lerp(a: Rect, b: Rect, t: Float): Rect {
        val left = a.left + (b.left - a.left) * t
        val top = a.top + (b.top - a.top) * t
        val width = a.width + (b.width - a.width) * t
        val height = a.height + (b.height - a.height) * t
        return Rect(left, top, left + width, top + height)
    }

    /** CoreGraphics `CGRect.contains(CGRect)`: [inner] lies wholly inside, edges included. */
    private fun Rect.containsRect(inner: Rect): Boolean =
        left <= inner.left && top <= inner.top && right >= inner.right && bottom >= inner.bottom
}
