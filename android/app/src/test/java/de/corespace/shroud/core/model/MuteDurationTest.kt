package de.corespace.shroud.core.model

import de.corespace.shroud.core.net.ChatMuteDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * Ports `NotificationPayloadTests.testMuteLabels` (`ios/shroudTests/NotificationPayloadTests.swift:152-168`)
 * with the exact strings of notifications-push §7.1 (UTC, `Locale.UK`, 24 h). Robolectric for
 * `DateFormat.getBestDateTimePattern`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MuteDurationTest {
    /** 2026-09-21T14:13:20Z, a Monday. */
    private val now = Instant.ofEpochSecond(1_790_000_000)

    private fun label(mute: ChatMuteDto?, is24h: Boolean = true, locale: Locale = Locale.UK) =
        MuteDuration.label(mute, now, ZoneOffset.UTC, locale, is24h)

    @Test
    fun testMuteLabels() {
        assertNull(label(null))
        assertEquals("Muted", label(ChatMuteDto(until = null)))
        assertNull(label(ChatMuteDto(until = now.minusSeconds(60))))
        assertTrue(label(ChatMuteDto(until = now.plusSeconds(3 * 86_400)))!!.startsWith("Muted until "))
        assertNull(MuteDuration.Forever.seconds)
        assertEquals(28_800L, MuteDuration.EightHours.seconds)
    }

    @Test
    fun sameDayShowsTheTime() {
        assertEquals("Muted until 16:13", label(ChatMuteDto(until = now.plusSeconds(2 * 3_600))))
    }

    @Test
    fun withinSixDaysShowsTheWeekday() {
        assertEquals("Muted until Thu 14:13", label(ChatMuteDto(until = now.plusSeconds(3 * 86_400))))
    }

    @Test
    fun laterShowsDayAndMonth() {
        assertEquals("Muted until 1 Oct", label(ChatMuteDto(until = now.plusSeconds(10 * 86_400))))
    }

    @Test
    fun twelveHourClockAndLocaleOrder() {
        assertEquals("Muted until 4:13 PM", label(ChatMuteDto(until = now.plusSeconds(2 * 3_600)), is24h = false, locale = Locale.US))
        assertEquals("Muted until Oct 1", label(ChatMuteDto(until = now.plusSeconds(10 * 86_400)), locale = Locale.US))
    }

    @Test
    fun theFiveDurationsInMenuOrder() {
        assertEquals(
            listOf("For 1 Hour" to 3_600L, "For 8 Hours" to 28_800L, "For 1 Day" to 86_400L, "For 7 Days" to 604_800L, "Until I Turn It Back On" to null),
            MuteDuration.entries.map { it.title to it.seconds },
        )
    }
}
