package de.corespace.shroud.ui.conversation.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared bubble numbers (`MessageBubbleView.swift:135-233`; conversation-thread §4.1) and the
 * text-bubble layout rules (§5), as pure functions — the new Android tests of thread §21.
 */
class BubbleMetricsTest {
    // ---- normalizedForDisplay (`:193-232`) ----

    @Test
    fun normalizedForDisplayCollapsesPastedWhitespace() {
        assertEquals("a b", MessageBubbleMetrics.normalizedForDisplay("a\t\tb"))
        assertEquals("a\n\nb", MessageBubbleMetrics.normalizedForDisplay("a\n\n\n\nb"))
        assertEquals("a", MessageBubbleMetrics.normalizedForDisplay("  a  "))
        assertEquals("a\nb", MessageBubbleMetrics.normalizedForDisplay("a\r\nb"))
        assertEquals("Key:\nMetric Value", MessageBubbleMetrics.normalizedForDisplay("Key:\nMetric\tValue"))
    }

    @Test
    fun normalizedForDisplayKeepsOtherWhitespaceAndEdges() {
        assertEquals("", MessageBubbleMetrics.normalizedForDisplay(""))
        assertEquals("", MessageBubbleMetrics.normalizedForDisplay(" \n\t "))
        // A no-break space is not collapsed (only tabs and spaces are).
        assertEquals("a  b", MessageBubbleMetrics.normalizedForDisplay("a  b"))
        // Line and paragraph separators count as newlines; a space before a newline vanishes.
        assertEquals("a\nb", MessageBubbleMetrics.normalizedForDisplay("a  b"))
        assertEquals("a\n\nb", MessageBubbleMetrics.normalizedForDisplay("a   b"))
        // A lone carriage return is dropped, not turned into a break.
        assertEquals("ab", MessageBubbleMetrics.normalizedForDisplay("a\rb"))
    }

    @Test
    fun anExplicitBreakForcesTheWrappingLayout() {
        assertTrue(MessageBubbleMetrics.isMultiline("a\nb"))
        assertTrue(MessageBubbleMetrics.isMultiline("a b"))
        assertFalse(MessageBubbleMetrics.isMultiline("a b"))
    }

    // ---- Widths (`:415-420`) ----

    @Test
    fun maxBubbleWidthLeavesTheOppositeGutter() {
        assertEquals(324f, MessageBubbleMetrics.maxBubbleWidth(380f))
        assertEquals(272f, MessageBubbleMetrics.maxBubbleWidth(328f))
        // A row narrower than the minimum bubble: never wider than the row.
        assertEquals(200f, MessageBubbleMetrics.maxBubbleWidth(200f))
        // Not measured yet: the 288 fallback row → 240.
        assertEquals(240f, MessageBubbleMetrics.maxBubbleWidth(0f))
        // iPhone 17 Pro: 402 − 32 = 370 → 314.
        assertEquals(314f, MessageBubbleMetrics.maxBubbleWidth(370f))
    }

    @Test
    fun mediaWidthCapIs268WithinTheBudget() {
        assertEquals(268f, MessageBubbleMetrics.mediaWidthCap(380f))
        assertEquals(240f, MessageBubbleMetrics.mediaWidthCap(0f))
        assertEquals(200f, MessageBubbleMetrics.mediaWidthCap(200f))
    }

    // ---- Meta and its reservation (`:165-191`) ----

    @Test
    fun metaWidthIsTheTimeRoundedUpPlusTheTicks() {
        assertEquals(31f, MessageBubbleMetrics.metaWidth(30.2f, showsReceipt = false))
        assertEquals(31f + 3 + 14, MessageBubbleMetrics.metaWidth(30.2f, showsReceipt = true))
    }

    @Test
    fun theReservationCountsDigitsLikeIos() {
        // ceil((48 + 1 + 8) / 6.5) = ceil(8.77) = 9.
        assertEquals(9, MessageBubbleMetrics.reservationDigits(metaWidth = 48f, digitWidth = 6.5f))
        // Never fewer than one.
        assertEquals(1, MessageBubbleMetrics.reservationDigits(metaWidth = 0f, digitWidth = 100f, padDifference = 0f, metaGap = 0f))
        assertEquals(6.5f * 9 + 3f, MessageBubbleMetrics.reservationWidth(9, 6.5f, 3f))
        assertEquals(6.5f * 9, MessageBubbleMetrics.reservationWrappedWidth(9, 6.5f))
    }

    /**
     * The meta shares the last line exactly when iOS's invisible reservation would fit on it: the same
     * decision as a pure function of widths (thread §21).
     */
    @Test
    fun theMetaSharesTheLastLineOnlyWhenTheReservationFits() {
        val reserve = MessageBubbleMetrics.reservationWidth(9, 6.5f, 3f) // 61.5
        assertTrue(MessageBubbleMetrics.metaFitsOnLastLine(lastLineRight = 200f, reservationWidth = reserve, maxWidth = 261.5f))
        assertFalse(MessageBubbleMetrics.metaFitsOnLastLine(lastLineRight = 200.1f, reservationWidth = reserve, maxWidth = 261.5f))
        assertTrue(MessageBubbleMetrics.metaFitsOnLastLine(lastLineRight = 0f, reservationWidth = reserve, maxWidth = Float.POSITIVE_INFINITY))
    }

    // ---- Layouts ----

    /** `LinkBubbleLayout`'s width rule (`:366-379`). */
    @Test
    fun aStackIsAsWideAsItsWidestRowWithinTheCap() {
        assertEquals(180, BubbleStackMath.resolvedWidth(cap = 300, fillsWidth = false, rowWidths = listOf(120, 180, 40)))
        assertEquals(300, BubbleStackMath.resolvedWidth(cap = 300, fillsWidth = false, rowWidths = listOf(500)))
        // A large link picture takes the whole cap.
        assertEquals(300, BubbleStackMath.resolvedWidth(cap = 300, fillsWidth = true, rowWidths = listOf(10)))
        assertEquals(1, BubbleStackMath.resolvedWidth(cap = 300, fillsWidth = false, rowWidths = emptyList()))
    }

    /** The compact bubble: leading 11 + text + 7 + meta + trailing 10 (`:572-592`). */
    @Test
    fun theCompactBubbleHugsTextAndMeta() {
        assertEquals(11 + 40 + 7 + 30 + 10, PlainBubbleMath.compactWidth(textWidth = 40, metaWidth = 30, leading = 11, spacing = 7, trailing = 10))
    }

    /** The meta's centre + 1 on the text's baseline; the rows grow to contain both. */
    @Test
    fun theCompactMetaSitsOnTheBaseline() {
        val rows = PlainBubbleMath.compactRows(textHeight = 20, textBaseline = 15f, metaHeight = 14, metaCenterOffset = 1f)
        // metaTop = 15 − 7 − 1 = 7: inside the text's rows.
        assertEquals(0, rows.textTop)
        assertEquals(7, rows.metaTop)
        assertEquals(21, rows.height)
        val tall = PlainBubbleMath.compactRows(textHeight = 10, textBaseline = 4f, metaHeight = 14, metaCenterOffset = 1f)
        // metaTop = 4 − 7 − 1 = −4: everything moves down by 4.
        assertEquals(4, tall.textTop)
        assertEquals(0, tall.metaTop)
        assertEquals(14, tall.height)
    }

    // ---- Transcription hints (`ConversationView.swift:1446-1453`) ----

    @Test
    fun hintsAreThePeerAndContactsDeduplicatedSortedFirstFifty() {
        val contacts = (1..80).map { "user%02d".format(it) } + "bob"
        val hints = BubbleServices.hints("bob", contacts)
        assertEquals(BubbleServices.MAX_HINTS, hints.size)
        assertEquals("bob", hints.first())
        assertEquals(hints.sorted(), hints)
        assertEquals(hints.toSet().size, hints.size)
    }
}
