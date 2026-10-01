package de.corespace.shroud.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-lock after leaving the app (iOS `ios/shroudTests/AutoLockDelayTests.swift:12-19`; crypto
 * spec §17.4), converted to `elapsedRealtime` millis (plan C12).
 */
class AutoLockDelayTest {
    /** `dueOnceTheDelayHasPassed` (`AutoLockDelayTests.swift:12-19`): leftAt = 1 000 000 s. */
    @Test
    fun dueOnceTheDelayHasPassed() {
        val left = 1_000_000_000L
        assertTrue(AutoLockDelay.Immediately.isDue(left, left))
        assertFalse(AutoLockDelay.OneMinute.isDue(left, left + 59_000))
        assertTrue(AutoLockDelay.OneMinute.isDue(left, left + 60_000))
        assertFalse(AutoLockDelay.FifteenMinutes.isDue(left, left + 14 * 60_000))
        assertTrue(AutoLockDelay.FifteenMinutes.isDue(left, left + 15 * 60_000))
        assertTrue(AutoLockDelay.FiveMinutes.isDue(left, left + 300_000))
        assertFalse(AutoLockDelay.FiveMinutes.isDue(left, left + 299_999))
        assertFalse(AutoLockDelay.Never.isDue(left, left + 86_400_000))
    }

    @Test
    fun rawValuesAndLabelsMatchIos() {
        // `SecurityPreferences.swift:100-117`: raw seconds and the picker copy, verbatim.
        assertEquals(
            listOf(0 to "Immediately", 60 to "After 1 minute", 300 to "After 5 minutes", 900 to "After 15 minutes", -1 to "Never"),
            AutoLockDelay.entries.map { it.seconds to it.label },
        )
        assertEquals(AutoLockDelay.FiveMinutes, AutoLockDelay.fromSeconds(300))
        assertEquals(null, AutoLockDelay.fromSeconds(42))
    }
}
