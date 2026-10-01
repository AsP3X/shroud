package de.corespace.shroud.core.model

import android.os.SystemClock
import java.time.Instant

/**
 * Time, injectable so controllers and tests share one source (iOS passes `now: Date = Date()`
 * parameters instead, e.g. `NotificationModels.swift:61, 157`).
 *
 * - [nowMillis] / [now]: wall clock — timestamps, mute ends, "last seen".
 * - [elapsedMillis]: monotonic since boot (`SystemClock.elapsedRealtime`, keeps counting in deep
 *   sleep) — durations that must not jump with a clock change, such as the auto-lock delay
 *   (plan C12).
 */
interface AppClock {
    fun nowMillis(): Long
    fun elapsedMillis(): Long
    fun now(): Instant = Instant.ofEpochMilli(nowMillis())
}

object SystemAppClock : AppClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
    override fun now(): Instant = Instant.now()
}
