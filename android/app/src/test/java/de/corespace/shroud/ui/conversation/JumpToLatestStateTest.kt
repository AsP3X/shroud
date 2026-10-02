package de.corespace.shroud.ui.conversation

import de.corespace.shroud.ui.conversation.ConversationFixtures.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The jump-to-latest badge counts what the other side sent after the reader left the bottom
 * (`JumpToLatestStateTests.swift`, all 8 cases; conversation-thread §20.1).
 */
class JumpToLatestStateTest {
    @Test
    fun atTheBottomNothingIsCounted() {
        val state = JumpToLatestState()
        val thread = listOf(message(0), message(1))
        assertFalse(state.isAway)
        assertEquals(0, state.unseenCount(thread))
    }

    @Test
    fun messagesArrivingBelowTheReaderAreCounted() {
        val state = JumpToLatestState()
        var thread = listOf(message(0), message(1))
        state.setAway(true, thread.last())
        assertEquals(0, state.unseenCount(thread))

        thread = thread + listOf(message(2), message(3))
        assertTrue(state.isAway)
        assertEquals(2, state.unseenCount(thread))
    }

    @Test
    fun ourOwnMessagesAndTombstonesDontCount() {
        val state = JumpToLatestState()
        var thread = listOf(message(0))
        state.setAway(true, thread.last())
        thread = thread + listOf(message(1, mine = true), message(2, deleted = true), message(3))
        assertEquals(1, state.unseenCount(thread))
    }

    @Test
    fun anArrivalDeletedForEveryoneDropsOffTheCount() {
        val state = JumpToLatestState()
        val first = message(0)
        state.setAway(true, first)
        val arrival = message(1)
        assertEquals(1, state.unseenCount(listOf(first, arrival)))

        val tombstone = arrival.copy(text = "", deleted = true)
        assertEquals(0, state.unseenCount(listOf(first, tombstone)))
    }

    @Test
    fun aDeletedAnchorIsFoundAgainByItsDate() {
        val state = JumpToLatestState()
        val older = message(0)
        val newest = message(1)
        state.setAway(true, newest)
        // The message they left on was deleted just for us; one older and two newer remain.
        assertEquals(2, state.unseenCount(listOf(older, message(2), message(3))))
    }

    @Test
    fun gettingBackToTheBottomResetsTheCount() {
        val state = JumpToLatestState()
        var thread = listOf(message(0))
        state.setAway(true, thread.last())
        thread = thread + message(1)
        assertEquals(1, state.unseenCount(thread))

        state.setAway(false, thread.last())
        assertFalse(state.isAway)
        assertEquals(0, state.unseenCount(thread))

        // Leaving again counts from the newest message at that moment.
        state.setAway(true, thread.last())
        assertEquals(0, state.unseenCount(thread))
        thread = thread + message(2)
        assertEquals(1, state.unseenCount(thread))
    }

    @Test
    fun leavingAgainWhileAwayKeepsTheFirstAnchor() {
        val state = JumpToLatestState()
        var thread = listOf(message(0))
        state.setAway(true, thread.last())
        thread = thread + message(1)
        // Callbacks repeat "away" on every scroll frame; the anchor must not move with them.
        state.setAway(true, thread.last())
        assertEquals(1, state.unseenCount(thread))
    }

    @Test
    fun leavingAnEmptyThreadCountsEverythingFromThem() {
        val state = JumpToLatestState()
        state.setAway(true, null)
        assertEquals(1, state.unseenCount(listOf(message(0), message(1, mine = true))))
    }
}
