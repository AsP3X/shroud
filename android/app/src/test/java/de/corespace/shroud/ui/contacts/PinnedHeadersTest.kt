package de.corespace.shroud.ui.contacts

import de.corespace.shroud.ui.contacts.PinnedHeaders.Placed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Section headers pinned under the glass bar (iOS `LazyVStack(pinnedViews: .sectionHeaders)` under
 * an inset bar, `ContactsView.swift:95`; contacts §5.1 [AND]). Offsets are Compose's: 0 is the
 * padding line under the bar, negative is under the bar; Compose itself pins the header of the item
 * at the viewport's very top at `-padding` (here 120).
 */
class PinnedHeadersTest {
    private val pad = 120
    private val h = 22

    @Test
    fun headersBelowTheBarStayWhereTheListPutsThem() {
        val headers = listOf(Placed(index = 2, offset = 60, size = h), Placed(index = 9, offset = 400, size = h))
        assertEquals(0, PinnedHeaders.translation(2, headers))
        assertEquals(0, PinnedHeaders.translation(9, headers))
    }

    @Test
    fun aHeaderAtThePaddingLineStays() {
        val headers = listOf(Placed(2, 0, h), Placed(9, 300, h))
        assertEquals(0, PinnedHeaders.translation(2, headers))
    }

    @Test
    fun composesPinnedHeaderMovesDownToTheLineUnderTheBar() {
        // Scrolled well into section 2: Compose pins its header at the viewport top (-pad).
        val headers = listOf(Placed(2, -pad, h), Placed(9, 300, h))
        assertEquals(pad, PinnedHeaders.translation(2, headers))
        assertEquals(0, PinnedHeaders.translation(9, headers))
    }

    @Test
    fun theNextHeaderPushesThePinnedOneUpUnderTheBar() {
        // The next header is 10 px below the line: the pinned one sits right above it, 12 px under the bar.
        val headers = listOf(Placed(2, -pad, h), Placed(9, 10, h))
        assertEquals(pad + (10 - h), PinnedHeaders.translation(2, headers))
    }

    @Test
    fun aHeaderInsideTheBarZoneIsTheOneToPinNotComposes() {
        // Compose pins section 2 (its rows are at the viewport top), but section 9's header has already
        // passed the line, so 9 is pinned at the line and 2 stays where Compose put it, under the bar.
        val headers = listOf(Placed(2, -pad, h), Placed(9, -50, h), Placed(15, 200, h))
        assertEquals(0, PinnedHeaders.translation(2, headers))
        assertEquals(50, PinnedHeaders.translation(9, headers))
        assertEquals(0, PinnedHeaders.translation(15, headers))
    }

    @Test
    fun aHeaderInsideTheBarZoneIsPushedByTheNextOne() {
        // 9 belongs right above 15 (at 5 - h = -17, under the bar's lower edge): moved from -50 by 33.
        val headers = listOf(Placed(2, -pad, h), Placed(9, -50, h), Placed(15, 5, h))
        assertEquals((5 - h) - (-50), PinnedHeaders.translation(9, headers))
    }

    @Test
    fun theLastSectionStaysPinnedToTheEnd() {
        val headers = listOf(Placed(30, -pad, h))
        assertEquals(pad, PinnedHeaders.translation(30, headers))
    }

    @Test
    fun anUnknownIndexIsNotMoved() {
        assertEquals(0, PinnedHeaders.translation(4, listOf(Placed(2, -pad, h))))
        assertEquals(0, PinnedHeaders.translation(4, emptyList()))
    }
}
