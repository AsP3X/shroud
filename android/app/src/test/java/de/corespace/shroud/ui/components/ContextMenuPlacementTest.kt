package de.corespace.shroud.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Menu placement, shell-chats §8.7 and D15: 8 dp under the anchor, flipped 8 dp above it when the
 * card would cross the navigation bar (+ 10 dp), clamped 16 dp from the sides. Numbers in px at
 * density 1 on the design's 412 × 915 frame (status bar 52, gesture bar 24).
 */
class ContextMenuPlacementTest {
    private val width = 412f
    private val height = 915f
    private val safeTop = 52f
    private val safeBottom = 24f

    /** The light card: 4 rows × 44 + 12 = 188 (AqgbA with Mark as Read, Mute, Delete Chat … ). */
    private val card = ContextMenuDefaults.cardHeight(4, MenuStyle.Light).value

    private fun place(anchor: Rect, cardHeight: Float = card, decision: Float = cardHeight, bottom: Float = safeBottom) =
        ContextMenuPlacement.place(
            anchor = anchor,
            cardWidth = 250f,
            cardHeight = cardHeight,
            containerWidth = width,
            containerHeight = height,
            safeTop = safeTop,
            safeBottom = bottom,
            gap = 8f,
            sideInset = 16f,
            topMargin = 8f,
            bottomMargin = 10f,
            decisionHeight = decision,
        )

    @Test
    fun cardSitsEightBelowARowWithRoomUnderIt() {
        // A chat row (full width, 72 high) near the top of the list.
        val row = Rect(0f, 200f, 412f, 272f)
        val placement = place(row)
        assertTrue(placement.below)
        assertEquals(272f + 8f, placement.y, 0.001f)
        // Leading-aligned 16 dp from the screen edge (shell-chats §8.7).
        assertEquals(16f, placement.x, 0.001f)
        assertFalse(placement.alignEnd)
    }

    @Test
    fun cardFlipsEightAboveWhenItWouldCrossTheNavigationBar() {
        // Bottom limit = 915 − 24 − 10 = 881; a row ending at 760 leaves 121 < 8 + 188.
        val row = Rect(0f, 688f, 412f, 760f)
        val placement = place(row)
        assertFalse(placement.below)
        assertEquals(688f - 8f - card, placement.y, 0.001f)
    }

    @Test
    fun cardExactlyFittingBelowStaysBelow() {
        val lowest = height - safeBottom - 10f
        val row = Rect(0f, 500f, 412f, lowest - 8f - card)
        assertTrue(place(row).below)
        val oneMore = Rect(0f, 500f, 412f, lowest - 8f - card + 1f)
        assertFalse(place(oneMore).below)
    }

    @Test
    fun theKeyboardCountsAsTheBottomOfTheSafeArea() {
        // IME 336 high (limit 569): a row ending at 382 needs 578 below, so it flips above the
        // keyboard; over the gesture bar (limit 881) it fits below.
        val row = Rect(0f, 310f, 412f, 382f)
        assertTrue(place(row).below)
        assertFalse(place(row, bottom = 336f).below)
    }

    @Test
    fun whenNeitherSideFitsTheRoomierSideWinsAndTheCardStaysOnScreen() {
        // A very tall anchor (a long message) leaves less than a card's height on both sides.
        val tall = Rect(0f, 120f, 412f, 760f)
        val placement = place(tall)
        // Above: 120 − 60 = 60 of room; below: 881 − 760 = 121 → below, pushed up to fit.
        assertTrue(placement.below)
        assertEquals(height - safeBottom - 10f - card, placement.y, 0.001f)
        assertTrue(placement.y >= safeTop + 8f)
    }

    @Test
    fun theTallestListDecidesTheSideSoASubmenuNeverFlipsIt() {
        // The first list (4 rows) fits below, the Mute submenu (back + 5 rows = 6) would not.
        val submenu = ContextMenuDefaults.cardHeight(6, MenuStyle.Light).value
        val row = Rect(0f, 600f, 412f, 672f)
        assertTrue(place(row, cardHeight = card).below)
        val decided = place(row, cardHeight = card, decision = submenu)
        assertFalse(decided.below)
        assertFalse(place(row, cardHeight = submenu, decision = submenu).below)
    }

    @Test
    fun aTrailingAnchorAlignsTheCardsTrailingEdges() {
        // The Auto-lock picker's value at the end of a settings row.
        val picker = Rect(300f, 400f, 380f, 444f)
        val placement = place(picker)
        assertTrue(placement.alignEnd)
        assertEquals(380f - 250f, placement.x, 0.001f)
    }

    @Test
    fun theCardNeverComesCloserThanSixteenToEitherSide() {
        val nearRight = Rect(380f, 400f, 412f, 444f)
        assertEquals(width - 16f - 250f, place(nearRight).x, 0.001f)
        val nearLeft = Rect(4f, 400f, 60f, 444f)
        assertEquals(16f, place(nearLeft).x, 0.001f)
    }

    @Test
    fun cardHeightsMatchBothCards() {
        // Light card (design AqgbA): 44 per row + 6 above and below.
        assertEquals(44f * 3 + 12f, ContextMenuDefaults.cardHeight(3, MenuStyle.Light).value, 0.001f)
        // Dark card: `MessageMenuLayoutTests.cardHeightCountsItsRows` (MessageMenuLayoutTests.swift:135-144).
        assertEquals((6 * 44 + 5).dp, ContextMenuDefaults.cardHeight(6, MenuStyle.Dark))
        assertEquals((7 * 44 + 6).dp, ContextMenuDefaults.cardHeight(7, MenuStyle.Dark))
        assertEquals((8 * 44 + 7).dp, ContextMenuDefaults.cardHeight(8, MenuStyle.Dark))
        assertEquals((4 * 44 + 3).dp, ContextMenuDefaults.cardHeight(4, MenuStyle.Dark))
        assertEquals(0.dp, ContextMenuDefaults.cardHeight(0, MenuStyle.Dark))
    }

    @Test
    fun scrimTapsRightAfterALongPressOpenedTheMenuAreIgnored() {
        // ConversationView.swift:1936-1937: the release of the opening hold must not close it.
        assertFalse(tapDismisses(openedAt = 1_000, now = 1_399, guardMillis = MENU_OPEN_TAP_GUARD_MS))
        assertTrue(tapDismisses(openedAt = 1_000, now = 1_400, guardMillis = MENU_OPEN_TAP_GUARD_MS))
        assertTrue(tapDismisses(openedAt = 1_000, now = 1_000, guardMillis = 0))
    }

    @Test
    fun predictiveBackLeansSurfacesBackByAtMostTheirShare() {
        assertEquals(1f, backLeanScale(0f), 0.0001f)
        assertEquals(0.97f, backLeanScale(0.5f), 0.0001f)
        assertEquals(0.94f, backLeanScale(1f), 0.0001f)
        assertEquals(0.94f, backLeanScale(3f), 0.0001f)
        assertEquals(0.96f, backLeanScale(1f, maxShrink = 0.04f), 0.0001f)
    }
}
