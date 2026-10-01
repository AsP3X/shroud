package de.corespace.shroud.core.media

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The size label of a media download chip, a video's "1.2 MB / 4.8 MB" readout and the wipe
 * overlay's media summary (plan C34) — a hand port of iOS `MediaCrypto.byteCountLabel`
 * (`ios/shroud/Services/Crypto/MediaCrypto.swift:174-181`): Foundation's `ByteCountFormatter` with
 * `allowedUnits = [.useKB, .useMB, .useGB]`, `countStyle = .file`, `isAdaptive = true`
 * (media-voice-links §4.3, crypto §13.2). Not `Formatter.formatShortFileSize`, which writes "kB",
 * rounds differently and localises the units.
 *
 * The rules, read off Foundation itself (the vectors in `ByteCountLabelTest` were produced by
 * `ByteCountFormatter` with exactly this configuration on macOS — iOS's Foundation):
 * - decimal units (1 KB = 1 000 bytes); bytes are never shown, so 1…499 bytes read "0 KB";
 * - adaptive fraction digits: KB none, MB one, GB two; trailing zeros dropped ("1 MB", "1.5 MB",
 *   "2.15 GB");
 * - rounding half up on the exact decimal value (1 500 B → "2 KB", 1 005 000 000 B → "1.01 GB");
 * - the unit grows when the rounded value reaches 1 000 (999 500 B → "1 MB", 999 950 000 B →
 *   "1 GB"); GB is the largest unit, so larger sizes group their digits ("1,234.57 GB");
 * - zero reads "Zero KB" (`allowsNonnumericFormatting`); iOS clamps negatives to zero first.
 *
 * The decimal and grouping separators follow [locale] as Foundation's do (an iPhone set to a
 * German region shows "1,5 MB"); the words stay English like the rest of the app.
 */
object ByteCountLabel {
    /** iOS `byteCountLabel(_:)` for [bytes] in [locale]'s number format. */
    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val count = bytes.coerceAtLeast(0)
        if (count == 0L) return ZERO
        var unitIndex = 0
        var value = scaled(count, unitIndex)
        while (value >= THOUSAND && unitIndex < UNITS.lastIndex) {
            unitIndex++
            value = scaled(count, unitIndex)
        }
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val pattern = if (UNITS[unitIndex].fractionDigits == 0) "#,##0" else "#,##0." + "#".repeat(UNITS[unitIndex].fractionDigits)
        val text = DecimalFormat(pattern, symbols).apply {
            roundingMode = RoundingMode.HALF_UP
            isGroupingUsed = true
        }.format(value)
        return "$text ${UNITS[unitIndex].symbol}"
    }

    /** "Zero KB" — Foundation's non-numeric zero for these units. */
    const val ZERO = "Zero KB"

    private class Unit(val symbol: String, val divisor: Long, val fractionDigits: Int)

    private val UNITS = listOf(
        Unit("KB", 1_000L, 0),
        Unit("MB", 1_000_000L, 1),
        Unit("GB", 1_000_000_000L, 2),
    )

    private val THOUSAND = BigDecimal(1_000)

    /** [bytes] in the unit at [index], rounded half up to that unit's fraction digits (exact decimal arithmetic). */
    private fun scaled(bytes: Long, index: Int): BigDecimal {
        val unit = UNITS[index]
        return BigDecimal(bytes).divide(BigDecimal(unit.divisor)).setScale(unit.fractionDigits, RoundingMode.HALF_UP)
    }
}
