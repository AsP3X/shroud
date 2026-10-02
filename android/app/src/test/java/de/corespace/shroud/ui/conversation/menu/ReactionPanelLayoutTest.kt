package de.corespace.shroud.ui.conversation.menu

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reaction bar grows in place into the full set: down from its own top edge, kept under the
 * status bar and above the home indicator — or the keyboard, once the search is typing
 * (`ReactionPanelLayoutTests.swift`, all 8 cases; conversation-thread §16.7, §20.4).
 */
class ReactionPanelLayoutTest {
    private val screen = Size(402f, 874f)
    private val safeArea = MenuInsets(top = 62f, bottom = 34f)
    private val bar = rect(12f, 300f, MessageReactionBarMetrics.barWidth, MessageReactionBarMetrics.barHeight)

    private fun frame(expanded: Boolean, bar: Rect = this.bar, safeArea: MenuInsets = this.safeArea): Rect =
        MessageReactionPanelLayout.frame(expanded, bar, screen, safeArea)

    @Test
    fun collapsedIsTheBar() {
        assertEquals(bar, frame(expanded = false))
    }

    @Test
    fun expandedGrowsDownFromTheBarsTopEdge() {
        val panel = frame(expanded = true)
        assertEquals(bar.left, panel.left, EPS)
        assertEquals(bar.width, panel.width, EPS)
        assertEquals(bar.top, panel.top, EPS)
        assertEquals(MessageReactionGridMetrics.height, panel.height, EPS)
        assertTrue(panel.height > bar.height)
        // The design's panel: 334 × 282.
        assertEquals(334f, panel.width, EPS)
        assertEquals(282f, panel.height, EPS)
    }

    @Test
    fun thePanelIsAsTallAsTheRowsASearchLeaves() {
        val one = MessageReactionGridMetrics.height(rows = 1)
        val two = MessageReactionGridMetrics.height(rows = 2)
        assertEquals(MessageReactionGridMetrics.CELL, two - one, EPS)
        // Nothing matching still shows the "no matches" line, one row tall.
        assertEquals(one, MessageReactionGridMetrics.height(rows = 0), EPS)
        // Past five and a half rows the grid scrolls instead of growing.
        assertEquals(MessageReactionGridMetrics.height, MessageReactionGridMetrics.height(rows = 6), EPS)
        assertEquals(MessageReactionGridMetrics.height, MessageReactionGridMetrics.height(rows = 10), EPS)
        assertEquals(0, MessageReactionGridMetrics.rows(0))
        assertEquals(1, MessageReactionGridMetrics.rows(8))
        assertEquals(2, MessageReactionGridMetrics.rows(9))
        // The whole set is dozens of rows: the panel shows five and a half and scrolls the rest.
        val count = MessageReactionBarMetrics.expanded.size
        assertEquals((count + 7) / 8, MessageReactionGridMetrics.rows(count))
        assertTrue(MessageReactionGridMetrics.rows(count) > 40)

        val short = frame(expanded = true).height
        val oneRow = MessageReactionPanelLayout.frame(true, bar, screen, safeArea, height = one)
        assertEquals(one, oneRow.height, EPS)
        assertTrue(oneRow.height < short)
        assertEquals(bar.top, oneRow.top, EPS)
    }

    @Test
    fun theGridFitsEightColumnsInTheBarsWidth() {
        val columns = MessageReactionGridMetrics.COLUMNS * MessageReactionGridMetrics.CELL
        assertEquals(MessageReactionBarMetrics.barWidth, columns + 2 * MessageReactionGridMetrics.sidePadding, EPS)
        assertTrue(MessageReactionGridMetrics.sidePadding >= 0)
    }

    @Test
    fun aBarNearTheBottomLiftsThePanelAboveTheHomeIndicator() {
        val low = rect(12f, 700f, bar.width, bar.height)
        val panel = frame(expanded = true, bar = low)
        assertEquals(874f - 34 - 10, panel.bottom, EPS)
        assertEquals(MessageReactionGridMetrics.height, panel.height, EPS)
    }

    @Test
    fun aBarUnderTheStatusBarStaysUnderIt() {
        val high = rect(12f, 40f, bar.width, bar.height)
        val panel = frame(expanded = true, bar = high)
        assertEquals(62f + 8, panel.top, EPS)
    }

    @Test
    fun theKeyboardLiftsAnOpenPanelAboveItself() {
        // The keyboard adds itself to the bottom inset of the overlay's safe area.
        val typing = MenuInsets(top = 62f, bottom = 34f + 336f)
        val low = rect(12f, 500f, bar.width, bar.height)
        val panel = frame(expanded = true, bar = low, safeArea = typing)
        assertEquals(874f - typing.bottom - 10, panel.bottom, EPS)
        assertEquals(MessageReactionGridMetrics.height, panel.height, EPS)
    }

    @Test
    fun aScreenTooShortForThePanelShrinksItInstead() {
        val short = MessageReactionPanelLayout.frame(
            expanded = true,
            bar = rect(12f, 60f, bar.width, bar.height),
            container = Size(874f, 300f),
            safeArea = MenuInsets(top = 0f, bottom = 21f),
        )
        assertEquals(300f - 21 - 16, short.height, EPS)
        assertEquals(8f, short.top, EPS)
    }
}
