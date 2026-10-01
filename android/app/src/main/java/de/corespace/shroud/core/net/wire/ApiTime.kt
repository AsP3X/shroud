package de.corespace.shroud.core.net.wire

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Dates on the wire (api-realtime §2.7). The server (`chrono` + serde) writes RFC 3339 in UTC with
 * `Z` and 0, 3, 6 or 9 fraction digits — Postgres keeps microseconds, `2026-09-24T12:00:00.123456Z`.
 *
 * iOS tries "internet date time with fractional seconds" and then without
 * (`APIClient.swift:457-462`, `ISO8601DateFormatter.date(fromAPI:)`). [parse] accepts the same
 * shapes and keeps every digit (iOS keeps milliseconds only, which is why history cursors echo the
 * server's raw text instead — `MessageModels.swift:44-47`). It also reads the whole-second times
 * iOS drops in socket events (`apiFlexible`, `MessagingController.swift:5882-5886`).
 */
object ApiTime {
    /**
     * RFC 3339 `date-time`: seconds required, 1–9 fraction digits after a dot, `Z` or `±hh:mm`.
     * `ISO_OFFSET_DATE_TIME` alone is too lenient (it takes `12:00Z` and `12:00:00.Z`).
     */
    private val SHAPE = Regex("""^\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d{1,9})?([Zz]|[+-]\d{2}:\d{2})$""")

    /** The instant [text] names, or null for anything that is not an RFC 3339 date-time. */
    fun parse(text: String): Instant? {
        if (!SHAPE.matches(text)) return null
        return try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** UTC with `Z` and as many fraction digits as needed (0, 3, 6 or 9) — `ISO_INSTANT`. */
    fun format(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)
}
