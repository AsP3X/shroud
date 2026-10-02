package de.corespace.shroud.ui.conversation.menu

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import de.corespace.shroud.core.model.ReceiptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press menu follows Telegram: reaction bar on the bubble, action card under it, and the
 * bubble itself moves when that stack would not fit where it is (`MessageMenuLayoutTests.swift`, all
 * 11 cases; conversation-thread §20.2). iPhone 17 Pro numbers: 402 × 874, 62 status bar, 34 bottom.
 */
class MessageMenuLayoutTest {
    private val screen = Size(402f, 874f)
    private val safeArea = MenuInsets(top = 62f, bottom = 34f)
    private val reaction = Size(334f, 50f)
    private val peerCard = Size(250f, 6 * 44f + 5)
    private val ownCard = Size(250f, 7 * 44f + 6)

    /** Highest a reaction bar may sit: 8 under the status bar. */
    private val reactionTop = 62f + 8

    /** Lowest a card may reach: 10 above the home indicator. */
    private val cardBottom = 874f - 34 - 10

    private class Result(val plan: MessageMenuLayout.Plan, val reactions: Rect, val card: Rect)

    private fun layout(
        source: Rect,
        isMine: Boolean = false,
        card: Size? = null,
        container: Size? = null,
        safeArea: MenuInsets? = null,
    ): Result {
        val metrics = MessageMenuLayout.Metrics(reactionSize = reaction, cardSize = card ?: if (isMine) ownCard else peerCard)
        val plan = MessageMenuLayout.plan(source, container ?: screen, safeArea ?: this.safeArea, metrics)
        // Where it is drawn when the menu opens (a scrolling stack opens at its bottom).
        val heroOnScreen = plan.hero.translate(0f, -plan.initialOffset)
        val chrome = MessageMenuLayout.chrome(heroOnScreen, isMine, plan, metrics)
        return Result(plan, chrome.reactions, chrome.card)
    }

    @Test
    fun messageWithRoomStaysWhereItIs() {
        val source = rect(16f, 300f, 200f, 40f)
        val result = layout(source)
        assertRect(source, result.plan.hero)
        assertFalse(result.plan.scrolls)
        assertEquals(source.top - 10, result.reactions.bottom, EPS)
        assertEquals(source.bottom + 10, result.card.top, EPS)
        assertEquals(16f, result.reactions.left, EPS)
        assertEquals(16f, result.card.left, EPS)
    }

    @Test
    fun messageAboveTheComposerLiftsUntilItsCardFitsBelow() {
        val source = rect(16f, 760f, 180f, 40f)
        val result = layout(source)
        assertEquals(16f, result.plan.hero.left, EPS)
        assertEquals(source.size, result.plan.hero.size)
        assertEquals(result.plan.hero.bottom + 10, result.card.top, EPS)
        assertEquals(cardBottom, result.card.bottom, EPS)
        assertEquals("the reactions ride on the bubble", result.plan.hero.top - 10, result.reactions.bottom, EPS)
        assertFalse(result.plan.scrolls)
    }

    @Test
    fun ownMessageLiftsAndLinesItsChromeUpOnTheRight() {
        val source = rect(266f, 780f, 120f, 40f)
        val result = layout(source, isMine = true)
        assertEquals(cardBottom - ownCard.height - 10 - 40, result.plan.hero.top, EPS)
        assertEquals(source.right, result.card.right, EPS)
        assertEquals(source.right, result.reactions.right, EPS)
        assertEquals(cardBottom, result.card.bottom, EPS)
    }

    @Test
    fun messageUnderTheHeaderDropsJustEnoughForTheReactionBar() {
        val source = rect(16f, 90f, 200f, 40f)
        val result = layout(source)
        assertEquals(reactionTop + reaction.height + 10, result.plan.hero.top, EPS)
        assertEquals(reactionTop, result.reactions.top, EPS)
        assertEquals(result.plan.hero.bottom + 10, result.card.top, EPS)
    }

    @Test
    fun theKeyboardIsTheBottomWhileItIsUp() {
        val keyboardUp = safeArea.copy(bottom = 336f)
        val source = rect(16f, 480f, 200f, 40f)
        val result = layout(source, safeArea = keyboardUp)
        assertEquals(874f - 336 - 10, result.card.bottom, EPS)
        assertEquals(result.plan.hero.top - 10, result.reactions.bottom, EPS)
        assertTrue(result.reactions.top >= reactionTop)
    }

    @Test
    fun messageTooTallForItsCardScrollsAndOpensAtTheCard() {
        val source = rect(16f, 100f, 300f, 700f)
        val result = layout(source)
        assertTrue(result.plan.scrolls)
        // At the top of the stack the bubble sits right under the reaction bar …
        assertEquals(reactionTop + reaction.height + 10, result.plan.hero.top, EPS)
        // … and the stack opens scrolled to its bottom, with the card fully on screen.
        assertEquals(cardBottom, result.card.bottom, EPS)
        assertEquals(result.plan.hero.bottom - result.plan.initialOffset + 10, result.card.top, EPS)
        // The bubble's top is scrolled away; the reaction bar stays pinned over it.
        assertEquals(reactionTop, result.reactions.top, EPS)
    }

    @Test
    fun chromeNeverLeavesTheScreen() {
        // Lined up with an incoming bubble that starts mid-screen, the bar would run off the right
        // edge; it stops 12 short instead, while the card keeps the bubble's edge.
        val mid = layout(rect(100f, 400f, 120f, 40f))
        assertEquals(402f - 12, mid.reactions.right, EPS)
        assertEquals(100f, mid.card.left, EPS)
        // A bubble flush with the left edge still leaves the chrome 12 of margin.
        val flush = layout(rect(0f, 400f, 120f, 40f))
        assertEquals(12f, flush.card.left, EPS)
        assertEquals(12f, flush.reactions.left, EPS)
    }

    @Test
    fun landscapeScrollsRatherThanOverlapping() {
        val landscape = Size(874f, 402f)
        val insets = MenuInsets(top = 0f, bottom = 21f)
        val result = layout(rect(80f, 200f, 200f, 40f), container = landscape, safeArea = insets)
        assertTrue(result.plan.scrolls)
        assertEquals(402f - 21 - 10, result.card.bottom, EPS)
        assertTrue(result.card.top >= result.plan.hero.bottom - result.plan.initialOffset + 10 - EPS)
    }

    @Test
    fun cardHeightCountsItsRows() {
        val all = MessageMenuAction.primary()
        val withLink = MessageMenuAction.primary(hasLink = true)
        assertEquals(6 * 44f + 5, MessageContextMenuCardMetrics.height(null, all), EPS)
        assertEquals(7 * 44f + 6, MessageContextMenuCardMetrics.height(ReceiptStatus.Read, all), EPS)
        assertEquals(8 * 44f + 7, MessageContextMenuCardMetrics.height(ReceiptStatus.Delivered, withLink), EPS)
        // A photo still sending: no receipt row, no Reply, no Copy of its "Photo" stand-in.
        val sendingPhoto = MessageMenuAction.primary(canReply = false, canCopy = false)
        assertEquals(4 * 44f + 3, MessageContextMenuCardMetrics.height(ReceiptStatus.Sending, sendingPhoto), EPS)
    }

    /** The muted row says what the ticks say, and nothing while the server hasn't confirmed the message. */
    @Test
    fun receiptRowFollowsTheTicks() {
        assertNull(MessageContextMenuCardMetrics.receiptTitle(null))
        assertNull(MessageContextMenuCardMetrics.receiptTitle(ReceiptStatus.Failed))
        assertNull(MessageContextMenuCardMetrics.receiptTitle(ReceiptStatus.Sending))
        assertEquals("sent", MessageContextMenuCardMetrics.receiptTitle(ReceiptStatus.Sent))
        assertEquals("delivered", MessageContextMenuCardMetrics.receiptTitle(ReceiptStatus.Delivered))
        assertEquals("read", MessageContextMenuCardMetrics.receiptTitle(ReceiptStatus.Read))
    }

    /** Only what the message can do, in the design's order. */
    @Test
    fun cardOffersOnlyWhatTheMessageCanDo() {
        assertEquals(
            listOf(MessageMenuAction.Reply, MessageMenuAction.Copy, MessageMenuAction.Pin, MessageMenuAction.Forward, MessageMenuAction.Delete),
            MessageMenuAction.primary(),
        )
        assertEquals(
            listOf(
                MessageMenuAction.Reply,
                MessageMenuAction.Copy,
                MessageMenuAction.CopyLink,
                MessageMenuAction.Pin,
                MessageMenuAction.Forward,
                MessageMenuAction.Delete,
            ),
            MessageMenuAction.primary(hasLink = true),
        )
        assertEquals(
            listOf(MessageMenuAction.CopyLink, MessageMenuAction.Pin, MessageMenuAction.Forward, MessageMenuAction.Delete),
            MessageMenuAction.primary(canReply = false, canCopy = false, hasLink = true),
        )
    }
}
