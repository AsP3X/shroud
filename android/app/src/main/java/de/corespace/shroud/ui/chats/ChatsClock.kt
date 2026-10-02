package de.corespace.shroud.ui.chats

import java.time.Instant
import java.time.ZoneId

/**
 * When the chat list's time-dependent text next changes without any engine event: a mute running
 * out (its bell and grey badge go — `ChatMute.isActive`, NM:60-64, "an expired mute is over before
 * the next list says so") and local midnight ("9:41" becomes "Yesterday", `ChatListFormatting.swift:32-42`).
 *
 * iOS re-evaluates both whenever SwiftUI redraws the list; Android re-reads the snapshot at the
 * moment returned here, so an idle list is right too. Pure, for tests.
 */
object ChatsClock {
    /** Never wait less than this: a mute ending "now" re-reads on the next tick, not in a busy loop. */
    const val MIN_DELAY_MS = 250L

    /**
     * The earliest of: the end of a running mute in [mutedUntil] (null = until turned back on, never
     * ends by itself) and the next local midnight in [zone] after [now].
     */
    fun nextChange(now: Instant, mutedUntil: Collection<Instant?>, zone: ZoneId): Instant {
        val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val muteEnd = mutedUntil.asSequence().filterNotNull().filter { it.isAfter(now) }.minOrNull()
        return if (muteEnd != null && muteEnd.isBefore(midnight)) muteEnd else midnight
    }

    /** Milliseconds to wait from [now] until [next], at least [MIN_DELAY_MS]. */
    fun delayMillis(now: Instant, next: Instant): Long =
        maxOf(MIN_DELAY_MS, next.toEpochMilli() - now.toEpochMilli())
}
