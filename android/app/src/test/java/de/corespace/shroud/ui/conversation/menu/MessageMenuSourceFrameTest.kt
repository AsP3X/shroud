package de.corespace.shroud.ui.conversation.menu

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Where the long-press menu lifts a bubble from. The bubble's own reported frame can be a stale one
 * from the chat's opening transition (half a screen off); the row's frame comes fresh from the press
 * and decides whether to trust it (`MessageMenuSourceFrameTests.swift`, all 6 cases;
 * conversation-thread §13.3, §20.3).
 */
class MessageMenuSourceFrameTest {
    private val row = rect(16f, 731f, 370f, 33f)

    @Test
    fun aBubbleInsideItsRowIsUsedAsItIs() {
        val bubble = rect(16f, 731f, 289.3f, 33f)
        assertSame(bubble, MessageMenuLayout.sourceFrame(bubble, row, isMine = false))
        val mine = rect(96.7f, 731f, 289.3f, 33f)
        assertSame(mine, MessageMenuLayout.sourceFrame(mine, row, isMine = true))
    }

    @Test
    fun aHairOfRoundingStillCounts() {
        val bubble = rect(15.5f, 730.5f, 290f, 34f)
        assertSame(bubble, MessageMenuLayout.sourceFrame(bubble, row, isMine = false))
    }

    @Test
    fun aStaleFrameIsPlacedAtTheRowsLeadingEdgeForTheirs() {
        // What the transition left behind: the right size, half a screen away.
        val stale = rect(-185f, 328f, 289.3f, 33f)
        assertRect(rect(16f, 731f, 289.3f, 33f), MessageMenuLayout.sourceFrame(stale, row, isMine = false))
    }

    @Test
    fun aStaleFrameIsPlacedAtTheRowsTrailingEdgeForOurs() {
        val stale = rect(-185f, 328f, 200f, 33f)
        assertRect(rect(386f - 200f, 731f, 200f, 33f), MessageMenuLayout.sourceFrame(stale, row, isMine = true))
    }

    @Test
    fun aStaleFrameNeverOutgrowsTheRow() {
        val stale = rect(-185f, 328f, 500f, 80f)
        assertRect(row, MessageMenuLayout.sourceFrame(stale, row, isMine = false))
    }

    @Test
    fun noBubbleFrameMeansTheRow() {
        assertSame(row, MessageMenuLayout.sourceFrame(null, row, isMine = false))
    }
}
