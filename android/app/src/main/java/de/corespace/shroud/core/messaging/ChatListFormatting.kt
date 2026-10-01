package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.net.PresenceDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/**
 * List and bubble strings (`ChatListFormatting.swift`; messaging-core §21). Pure: the caller passes
 * the clock, zone, locale and the system 12/24-hour setting (`DateFormat.is24HourFormat(context)`),
 * so every line is testable on the JVM.
 *
 * Times: iOS `.shortened` is `HH:mm` in 24-hour mode and `h:mm a` otherwise (the same rule
 * `MuteDuration.label` uses); iOS `.abbreviated` dates are the locale's medium date (`Sep 27, 2026`
 * in `en_US`, `27 Sep 2026` in `en_GB`).
 */
object ChatListFormatting {
    /**
     * The list subtitle for a chat: its last message (`ChatListFormatting.swift:5-29`). No "You:"
     * prefix (iOS wins over the web, messaging-core §29).
     */
    fun preview(peerId: UUID, threads: Map<UUID, List<ChatMessage>>, isNotes: Boolean): String {
        val last = threads[peerId]?.lastOrNull()
        if (last != null) {
            if (last.deleted) return ThreadMessageMerge.MESSAGE_DELETED
            return when (last.kind) {
                ChatMessageKind.Image -> if (last.text.isEmpty() || last.text == PHOTO) PHOTO else last.text
                ChatMessageKind.Video -> if (last.text.isEmpty() || last.text == VIDEO) VIDEO else last.text
                ChatMessageKind.Voice -> last.transcript?.takeIf { it.isNotEmpty() } ?: VOICE_MESSAGE
                ChatMessageKind.Todo -> (if (last.todoDone == true) "✓ " else "○ ") + last.text
                ChatMessageKind.Text -> last.text
            }
        }
        return if (isNotes) EMPTY_NOTES else EMPTY_CHAT
    }

    /** Today → the time, yesterday → "Yesterday", else the medium date; null → "" (`ChatListFormatting.swift:32-42`). */
    fun timeLabel(date: Instant?, now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): String {
        if (date == null) return ""
        val day = date.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        if (day == today) return shortTime(date, zone, locale, is24h)
        if (day == today.minusDays(1)) return "Yesterday"
        return mediumDate(date, zone, locale)
    }

    /** The clock time for a bubble's meta line; null → "" (`ChatListFormatting.swift:69-72`). */
    fun clockTimeLabel(date: Instant?, zone: ZoneId, locale: Locale, is24h: Boolean): String =
        if (date == null) "" else shortTime(date, zone, locale, is24h)

    /**
     * "online", "last seen 9:41", "last seen yesterday", "last seen 27 Sep 2026" or "offline" — null
     * when the server has said nothing about this user yet, so each screen falls back to its own line
     * (`ChatListFormatting.swift:48-53`). Same words as the web's `presenceLabel`.
     */
    fun presenceLabel(presence: PresenceDto?, now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): String? {
        if (presence == null) return null
        if (presence.online) return "online"
        val last = presence.lastSeenAt ?: return "offline"
        return lastSeenLabel(last, now, zone, locale, is24h)
    }

    /** Lower-case mid-sentence, unlike the list's capitalised day labels (`ChatListFormatting.swift:57-66`). */
    fun lastSeenLabel(date: Instant, now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): String {
        val day = date.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        if (day == today) return "last seen ${shortTime(date, zone, locale, is24h)}"
        if (day == today.minusDays(1)) return "last seen yesterday"
        return "last seen ${mediumDate(date, zone, locale)}"
    }

    private fun shortTime(date: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): String =
        DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale).format(date.atZone(zone))

    private fun mediumDate(date: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date.atZone(zone))

    /** Stand-in text of a captionless photo (`MessagingController.swift:2401`). */
    const val PHOTO = "Photo"

    /** Stand-in text of a captionless video (`MessagingController.swift:2577`). */
    const val VIDEO = "Video"

    /** Stand-in text of a voice note without a transcript (`MessagingController.swift:3397`). */
    const val VOICE_MESSAGE = "Voice message"

    /** `ChatListFormatting.swift:27`. */
    const val EMPTY_NOTES = "Personal notes, photos & todos"

    /** `ChatListFormatting.swift:28`. */
    const val EMPTY_CHAT = "Encrypted conversation"
}
