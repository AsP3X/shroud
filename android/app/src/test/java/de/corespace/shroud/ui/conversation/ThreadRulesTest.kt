package de.corespace.shroud.ui.conversation

import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.ui.conversation.ConversationFixtures.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/**
 * The thread's scroll, paging and timeline rules as pure functions (conversation-thread §2, §3;
 * `ConversationView.swift:846-865, 995-1146, 1161-1197, 2457-2489`): when the list counts as at the
 * bottom, when an older page is asked for (600 dp from the top), who the list follows, when the
 * jump control shows, which rows the reversed list holds, and which messages animate in.
 */
class ThreadRulesTest {
    /** Reply snippets are clamped by grapheme (ICU4J on the JVM). */
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    // ---- Position (CV:996-1001, 2457-2464) -----------------------------------------------------------

    @Test
    fun atTheBottomAllowsTwoDpOfSlack() {
        val tolerance = ThreadScrollMetrics.AT_BOTTOM_TOLERANCE_DP * 2.625f
        assertTrue(ThreadScrollMetrics.atBottom(0, 0, tolerance))
        assertTrue(ThreadScrollMetrics.atBottom(0, 5, tolerance))
        assertFalse(ThreadScrollMetrics.atBottom(0, 6, tolerance))
        assertFalse("the bottom spacer has scrolled out", ThreadScrollMetrics.atBottom(1, 0, tolerance))
    }

    @Test
    fun theDistanceToTheTopIsExactOnceTheOldestRowIsLaidOut() {
        // Bottom spacer, a row, the header row; the top bar's clearance past the header.
        val visible = listOf(ItemExtent(0, 0, 1), ItemExtent(1, 1, 300), ItemExtent(2, 301, 40))
        val remaining = ThreadScrollMetrics.remainingToTop(visible, totalItems = 3, viewportEndOffset = 2_000, afterContentPadding = 120)
        assertEquals(301f + 40 + 120 - 2_000, remaining, 0f)
        assertTrue(ThreadScrollMetrics.nearTop(remaining, slackPx = 600f * 2.625f))
    }

    @Test
    fun theDistanceToTheTopIsEstimatedFromTheLaidOutRowsOtherwise() {
        val visible = listOf(ItemExtent(0, 0, 100), ItemExtent(1, 100, 200), ItemExtent(2, 300, 300))
        // 600 px past the viewport's edge, then 7 rows not laid out at the average 200 px.
        val remaining = ThreadScrollMetrics.remainingToTop(visible, totalItems = 10, viewportEndOffset = 0, afterContentPadding = 0)
        assertEquals(600f + 7 * 200f, remaining, 0f)
        assertEquals(0f, ThreadScrollMetrics.remainingToTop(emptyList(), 0, 0, 0), 0f)
    }

    @Test
    fun olderMessagesAreFetchedSixHundredDpFromTheTop() {
        val density = 3f
        val slack = ThreadScrollMetrics.REVEAL_SLACK_DP * density
        assertEquals(600f, ThreadScrollMetrics.REVEAL_SLACK_DP, 0f)
        assertTrue(ThreadScrollMetrics.nearTop(slack - 1, slack))
        assertFalse(ThreadScrollMetrics.nearTop(slack, slack))
    }

    // ---- Following and the jump control (CV:1056-1065, 1119-1146) --------------------------------------

    @Test
    fun newMessagesMoveOnlyAReaderAtTheBottomOrTheirOwnSend() {
        val before = UUID.randomUUID()
        assertTrue("the first messages shown", ThreadScrollMetrics.follows(null, settled = true, atBottom = false, newestIsMine = false))
        assertTrue("before the opening pin settled", ThreadScrollMetrics.follows(before, settled = false, atBottom = false, newestIsMine = false))
        assertTrue("at the bottom", ThreadScrollMetrics.follows(before, settled = true, atBottom = true, newestIsMine = false))
        assertTrue("our own send", ThreadScrollMetrics.follows(before, settled = true, atBottom = false, newestIsMine = true))
        assertFalse("reading history", ThreadScrollMetrics.follows(before, settled = true, atBottom = false, newestIsMine = false))
    }

    @Test
    fun theJumpControlShowsOnlyWhenTheReaderLeftTheBottomThemselves() {
        assertTrue(ThreadScrollMetrics.isAway(settled = true, pinning = 0, headingToBottom = false, atBottom = false, scrollable = true))
        assertFalse(ThreadScrollMetrics.isAway(settled = false, pinning = 0, headingToBottom = false, atBottom = false, scrollable = true))
        assertFalse(ThreadScrollMetrics.isAway(settled = true, pinning = 1, headingToBottom = false, atBottom = false, scrollable = true))
        assertFalse(ThreadScrollMetrics.isAway(settled = true, pinning = 0, headingToBottom = true, atBottom = false, scrollable = true))
        assertFalse(ThreadScrollMetrics.isAway(settled = true, pinning = 0, headingToBottom = false, atBottom = true, scrollable = true))
        assertFalse("a thread too short to scroll", ThreadScrollMetrics.isAway(true, 0, false, atBottom = false, scrollable = false))
    }

    @Test
    fun pagingWaitsForTheOpeningPin() {
        assertTrue(ThreadScrollMetrics.mayRevealOlder(settled = true, pinning = 0, nearTop = true))
        assertFalse(ThreadScrollMetrics.mayRevealOlder(settled = false, pinning = 0, nearTop = true))
        assertFalse(ThreadScrollMetrics.mayRevealOlder(settled = true, pinning = 1, nearTop = true))
        assertFalse(ThreadScrollMetrics.mayRevealOlder(settled = true, pinning = 0, nearTop = false))
    }

    @Test
    fun aQuotedMessageIsCentredOnTheScreen() {
        // Logical offsets grow upward: a row 1,000 px up, 100 tall, in an 800 px viewport.
        assertEquals(1_050f - 400f, ThreadScrollMetrics.centeringDelta(ItemExtent(9, 1_000, 100), 0, 800), 0f)
        assertEquals(-350f, ThreadScrollMetrics.centeringDelta(ItemExtent(1, 0, 100), 0, 800), 0f)
    }

    @Test
    fun aForcedPinReappliesAfterTheFirstLayoutPasses() {
        assertEquals(listOf(16L, 50L, 120L), ThreadScrollMetrics.PIN_RETRY_DELAYS_MS.toList())
        assertEquals(250, ThreadScrollMetrics.FOLLOW_MS)
    }

    // ---- Timeline (CV:1161-1197) ------------------------------------------------------------------------

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2027, 1, 15)

    @Test
    fun dayChipsGoBeforeTheFirstMessageOfEachLocalDay() {
        // START is 2027-01-15T08:00:00Z.
        val a = message(0)
        val b = message(60)
        val c = message(-86_400 * 2L)
        val items = Timeline.items(listOf(c, a, b), zone, today, Locale.US)
        assertEquals(
            listOf("day-2027-1-13", c.id.toString(), "day-2027-1-15", a.id.toString(), b.id.toString()),
            items.map { it.key },
        )
        assertEquals("Jan 13, 2027", (items[0] as TimelineItem.Day).label)
        assertEquals("Today", (items[2] as TimelineItem.Day).label)
    }

    @Test
    fun dayLabelsReadTodayYesterdayThenTheLocalesMediumDate() {
        assertEquals("Today", Timeline.dayLabel(today, today, Locale.US))
        assertEquals("Yesterday", Timeline.dayLabel(today.minusDays(1), today, Locale.US))
        assertEquals("Mar 3, 2026", Timeline.dayLabel(LocalDate.of(2026, 3, 3), today, Locale.US))
        assertEquals("2026-3-3", Timeline.dayKey(LocalDate.of(2026, 3, 3)))
    }

    @Test
    fun theReversedListHoldsTheSpacerTypingSlotRowsNewestFirstAndTheHeaderOnTop() {
        val a = message(0)
        val b = message(1)
        val timeline = Timeline.items(listOf(a, b), zone, today, Locale.US)
        val items = Timeline.threadItems(timeline, ChatPeerActivity.Typing, ThreadHeader.Chips(showsToday = false)) { m ->
            MessageRows.model(m, false, "ana", null, emptyMap(), emptyMap(), emptyList(), null)
        }
        assertEquals(
            listOf("thread-bottom", "typing-indicator", b.id.toString(), a.id.toString(), "day-2027-1-15", "thread-header"),
            items.map { it.key },
        )
        assertEquals(ChatPeerActivity.Typing, (items[1] as ThreadItem.Typing).activity)
    }

    @Test
    fun exactlyOneHeaderSitsAboveTheOldestMessage() {
        assertEquals(ThreadHeader.OlderHistory(showsSpinner = false), ThreadHeader.of(false, hasOlderOnServer = true, isLoadingOlder = false, loadingFirstPage = false, firstLoadError = null))
        assertEquals(ThreadHeader.OlderHistory(showsSpinner = true), ThreadHeader.of(false, hasOlderOnServer = true, isLoadingOlder = true, loadingFirstPage = false, firstLoadError = null))
        assertEquals(ThreadHeader.LoadingFirstPage, ThreadHeader.of(true, hasOlderOnServer = false, isLoadingOlder = false, loadingFirstPage = true, firstLoadError = "x"))
        assertEquals(ThreadHeader.LoadError("x"), ThreadHeader.of(true, hasOlderOnServer = false, isLoadingOlder = false, loadingFirstPage = false, firstLoadError = "x"))
        assertEquals(ThreadHeader.Chips(showsToday = true), ThreadHeader.of(true, false, false, false, null))
        assertEquals(ThreadHeader.Chips(showsToday = false), ThreadHeader.of(false, false, false, false, null))
    }

    @Test
    fun theTranscriptTailIsTheNewestTwoVoiceNotesWithNothingAfterThem() {
        val v1 = message(0, kind = ChatMessageKind.Voice)
        val v2 = message(1, kind = ChatMessageKind.Voice)
        val v3 = message(2, kind = ChatMessageKind.Voice)
        assertEquals(listOf(v3.id, v2.id), Timeline.transcriptTail(listOf(v1, v2, v3)))
        assertEquals(emptyList<UUID>(), Timeline.transcriptTail(listOf(v1, message(3))))
        assertEquals(listOf(v3.id), Timeline.transcriptTail(listOf(message(0, kind = ChatMessageKind.Voice, deleted = true), v3)))
    }

    @Test
    fun repliesResolveAgainstTheQuotedMessagesStillHere() {
        val original = message(0)
        val reply = message(1).copy(replyTo = MessageReplyReference(original.id, ConversationFixtures.PEER, MessageReplyReference.Kind.Text, "hello"))
        val quoted = Timeline.quotedMessages(listOf(original, reply))
        assertEquals(mapOf(original.id to original), quoted)
        assertTrue(Timeline.quotedMessages(listOf(original)).isEmpty())
        val row = MessageRows.model(reply, false, "ana", ConversationFixtures.ME, quoted, emptyMap(), emptyList(), highlightedId = reply.id)
        assertEquals("ana", row.replyQuote?.author)
        assertTrue(row.highlighted)
        assertFalse(MessageRows.hero(row).highlighted)
        assertTrue(MessageRows.hero(row).isMenuHero)
    }

    // ---- Arrivals (CV:961-967; conversation-thread §3.5) -------------------------------------------------

    @Test
    fun onlyMessagesArrivingAtTheBottomAnimateIn() {
        var now = 0L
        val tracker = ArrivalTracker { now }
        val a = message(0)
        val b = message(1)
        tracker.observe(listOf(a, b))
        assertFalse("the opening batch", tracker.take(b.id))

        val older = message(-10)
        tracker.observe(listOf(older, a, b))
        assertFalse("an older page landing at the top", tracker.take(older.id))

        val c = message(2)
        tracker.observe(listOf(older, a, b, c))
        assertTrue(tracker.take(c.id))
        assertFalse("once", tracker.take(c.id))

        val d = message(3)
        tracker.observe(listOf(older, a, b, c, d))
        now += ArrivalTracker.WINDOW_MS + 1
        assertFalse("drawn too late (the reader was up in the history)", tracker.take(d.id))
    }
}
