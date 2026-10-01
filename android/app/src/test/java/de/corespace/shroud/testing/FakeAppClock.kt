package de.corespace.shroud.testing

import de.corespace.shroud.core.model.AppClock
import java.time.Instant

/**
 * A hand-driven [AppClock] (W0-B, 00-plan §1.7.1): wall time
 * ([nowMillis], [now]) and monotonic time since boot ([elapsedMillis]) move independently, so a
 * test can change the wall clock without time passing (the auto-lock must not care, plan C12).
 *
 * Starts at 1 790 000 000 000 ms = 2026-09-21T14:13:20Z, a Monday (the instant of iOS
 * `NotificationSettingsTests.testMuteLabels`), with 1 000 s of uptime.
 */
class FakeAppClock(wallMillis: Long = START_WALL_MILLIS, elapsedMillis: Long = START_ELAPSED_MILLIS) : AppClock {
    private var wall = wallMillis
    private var elapsed = elapsedMillis

    override fun nowMillis(): Long = wall

    override fun elapsedMillis(): Long = elapsed

    override fun now(): Instant = Instant.ofEpochMilli(wall)

    /** Time passes: both clocks move by [millis]. */
    fun advanceBy(millis: Long) {
        require(millis >= 0) { "time does not run backwards; use setWall for a clock change" }
        wall += millis
        elapsed += millis
    }

    /** The user (or the network) changes the wall clock; uptime does not move. */
    fun setWall(millis: Long) {
        wall = millis
    }

    companion object {
        const val START_WALL_MILLIS = 1_790_000_000_000L
        const val START_ELAPSED_MILLIS = 1_000_000L
    }
}
