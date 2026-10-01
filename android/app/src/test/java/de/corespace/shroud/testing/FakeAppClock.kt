package de.corespace.shroud.testing

import java.time.Instant

/**
 * A hand-driven clock with the shape of `core/model/AppClock` (W0-B, 00-plan §1.7.1): wall time
 * ([nowMillis], [now]) and monotonic time since boot ([elapsedMillis]) move independently, so a
 * test can change the wall clock without time passing (the auto-lock must not care, plan C12).
 *
 * Starts at 1 790 000 000 000 ms = 2026-09-21T14:13:20Z, a Monday (the instant of iOS
 * `NotificationSettingsTests.testMuteLabels`), with 1 000 s of uptime.
 *
 * W1-INT makes it implement `AppClock` once W0-B's interface and this kit share a branch
 * (`: AppClock`, `override` on the three functions).
 */
class FakeAppClock(wallMillis: Long = START_WALL_MILLIS, elapsedMillis: Long = START_ELAPSED_MILLIS) {
    private var wall = wallMillis
    private var elapsed = elapsedMillis

    fun nowMillis(): Long = wall

    fun elapsedMillis(): Long = elapsed

    fun now(): Instant = Instant.ofEpochMilli(wall)

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
