package de.corespace.shroud.core.net.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * api-realtime §2.7: the server writes RFC 3339 with `Z` and 0/3/6/9 fraction digits; iOS reads
 * with and without fractions (`APIClient.swift:457-462`). Android keeps every digit.
 */
class ApiTimeTest {
    private val base = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun wholeSeconds() {
        assertEquals(base, ApiTime.parse("2026-09-24T12:00:00Z"))
    }

    @Test
    fun millisecondsMicrosecondsAndNanosecondsAreKept() {
        assertEquals(base.plusMillis(123), ApiTime.parse("2026-09-24T12:00:00.123Z"))
        assertEquals(base.plusNanos(123_456_000), ApiTime.parse("2026-09-24T12:00:00.123456Z"))
        assertEquals(base.plusNanos(123_456_789), ApiTime.parse("2026-09-24T12:00:00.123456789Z"))
    }

    @Test
    fun anyFractionLengthFromOneToNine() {
        // NotificationPayloadTests.testConversationDecodesUnreadCountAndMute: one digit.
        assertEquals(base.plusMillis(500), ApiTime.parse("2026-09-24T12:00:00.5Z"))
        assertEquals(base.plusMillis(120), ApiTime.parse("2026-09-24T12:00:00.12Z"))
        assertEquals(base.plusNanos(1), ApiTime.parse("2026-09-24T12:00:00.000000001Z"))
    }

    @Test
    fun offsetsAreAppliedToTheInstant() {
        assertEquals(base, ApiTime.parse("2026-09-24T14:00:00+02:00"))
        assertEquals(base, ApiTime.parse("2026-09-24T07:30:00-04:30"))
        assertEquals(base.plusNanos(123_456_000), ApiTime.parse("2026-09-24T12:00:00.123456+00:00"))
        assertEquals(base, ApiTime.parse("2026-09-24T12:00:00-00:00"))
    }

    @Test
    fun garbageIsNull() {
        listOf(
            "",
            "garbage",
            "2026-09-24",
            "2026-09-24T12:00Z",                  // no seconds
            "2026-09-24T12:00:00",                // no offset
            "2026-09-24T12:00:00.Z",              // dot without digits
            "2026-09-24T12:00:00.1234567891Z",    // ten digits
            "2026-09-24 12:00:00Z",               // space for T
            "2026-09-24T12:00:00+0200",           // offset without colon
            "2026-09-24T12:00:00+02",
            "2026-13-24T12:00:00Z",               // month 13
            "2026-02-30T12:00:00Z",               // no such day
            "2026-09-24T25:00:00Z",
            " 2026-09-24T12:00:00Z",
            "2026-09-24T12:00:00Z ",
            "1790000000",
        ).forEach { assertNull(it, ApiTime.parse(it)) }
    }

    @Test
    fun formatIsUtcWithAsManyDigitsAsNeeded() {
        assertEquals("2026-09-24T12:00:00Z", ApiTime.format(base))
        assertEquals("2026-09-24T12:00:00.500Z", ApiTime.format(base.plusMillis(500)))
        assertEquals("2026-09-24T12:00:00.123456Z", ApiTime.format(base.plusNanos(123_456_000)))
        assertEquals("2026-09-24T12:00:00.123456789Z", ApiTime.format(base.plusNanos(123_456_789)))
    }

    @Test
    fun formatThenParseRoundTrips() {
        listOf(base, base.plusMillis(1), base.plusNanos(123_456_000), base.plusNanos(999_999_999), Instant.EPOCH)
            .forEach { assertEquals(it, ApiTime.parse(ApiTime.format(it))) }
    }
}
