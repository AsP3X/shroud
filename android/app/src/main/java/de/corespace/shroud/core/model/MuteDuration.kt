package de.corespace.shroud.core.model

import de.corespace.shroud.core.net.ChatMuteDto
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How long a chat stays muted, as Telegram offers it (`MuteDuration`, `NotificationModels.swift:124-153`;
 * notifications-push §7.1). Order, seconds and menu titles are iOS's; the web's sentence case is not
 * used. Home is `core/model` (plan C14); seam published by W1-INT.
 */
enum class MuteDuration(
    /** Seconds for the server (`PUT /conversations/{peer}/mute {"seconds": n|null}`); null = until unmuted. */
    val seconds: Long?,
    val title: String,
) {
    Hour(3_600, "For 1 Hour"),
    EightHours(8 * 3_600, "For 8 Hours"),
    Day(24 * 3_600, "For 1 Day"),
    Week(7 * 24 * 3_600, "For 7 Days"),
    Forever(null, "Until I Turn It Back On"),
    ;

    companion object {
        /** Closer than this, the label names the weekday; farther, the date (`NotificationModels.swift:162`). */
        private val WEEKDAY_HORIZON: Duration = Duration.ofDays(6)

        /**
         * "Muted", "Muted until 14:30", "Muted until Fri 14:30", "Muted until 3 Oct" — or null when
         * the chat is not muted (`NotificationModels.swift:155-166`). A mute whose time has passed is
         * over before the next chat list says so ([ChatMuteDto.isActive]).
         *
         * `{time}` is the short time in the user's 12/24-hour setting ([is24h], from
         * `android.text.format.DateFormat.is24HourFormat`); `{Wkd}` the abbreviated weekday; the date is
         * day + abbreviated month in [locale]'s order (`DateFormat.getBestDateTimePattern(locale, "dMMM")`).
         * Callers show `label ?: "Muted"` in lists and `label ?: "On"` on the profile.
         */
        fun label(mute: ChatMuteDto?, now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): String? {
            if (mute == null || !mute.isActive(now)) return null
            val until = mute.until ?: return "Muted"
            val local = until.atZone(zone)
            val time = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale).format(local)
            if (local.toLocalDate() == now.atZone(zone).toLocalDate()) return "Muted until $time"
            if (Duration.between(now, until) < WEEKDAY_HORIZON) {
                return "Muted until ${DateTimeFormatter.ofPattern("EEE", locale).format(local)} $time"
            }
            val dayMonth = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM")
            return "Muted until ${DateTimeFormatter.ofPattern(dayMonth, locale).format(local)}"
        }
    }
}
