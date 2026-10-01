package de.corespace.shroud.core.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * `ByteCountLabel` ≡ iOS `MediaCrypto.byteCountLabel` (`ios/shroud/Services/Crypto/MediaCrypto.swift:174-181`;
 * media-voice-links §4.3, crypto §13.2, risk R9).
 *
 * The expected strings were not computed by hand: they are the output of Foundation's
 * `ByteCountFormatter` with exactly the iOS configuration (`allowedUnits = [.useKB, .useMB, .useGB]`,
 * `countStyle = .file`, `includesUnit = true`, `isAdaptive = true`), run with `swift` on macOS 27
 * (2026-10-01) — once with `-AppleLocale en_US` and once in a German region (`en_US@rg=dezzzz`,
 * which keeps English words but German separators). Copied verbatim from that output.
 */
class ByteCountLabelTest {
    @Test
    fun theFiveValuesOfTheCard() {
        // W2-MEDIA-STORE card: 0, 999, 1 000, 1 500 000, 2 500 000 000.
        assertEquals("Zero KB", ByteCountLabel.format(0, Locale.US))
        assertEquals("1 KB", ByteCountLabel.format(999, Locale.US))
        assertEquals("1 KB", ByteCountLabel.format(1_000, Locale.US))
        assertEquals("1.5 MB", ByteCountLabel.format(1_500_000, Locale.US))
        assertEquals("2.5 GB", ByteCountLabel.format(2_500_000_000, Locale.US))
    }

    @Test
    fun foundationVectorsEnUs() {
        for ((bytes, expected) in FOUNDATION_EN_US) {
            assertEquals("$bytes bytes", expected, ByteCountLabel.format(bytes, Locale.US))
        }
    }

    @Test
    fun foundationVectorsGermanSeparators() {
        for ((bytes, expected) in FOUNDATION_DE_REGION) {
            assertEquals("$bytes bytes", expected, ByteCountLabel.format(bytes, Locale.GERMANY))
        }
    }

    @Test
    fun negativeCountsReadZeroAsIosClampsThem() {
        // iOS formats `Int64(max(0, bytes))` (MediaCrypto.swift:180).
        assertEquals("Zero KB", ByteCountLabel.format(-5, Locale.US))
        assertEquals("Zero KB", ByteCountLabel.format(Long.MIN_VALUE, Locale.US))
    }

    private companion object {
        /** `swift bcf.swift -AppleLocale en_US`. */
        val FOUNDATION_EN_US = listOf(
            0L to "Zero KB",
            1L to "0 KB",
            49L to "0 KB",
            499L to "0 KB",
            500L to "1 KB",
            501L to "1 KB",
            999L to "1 KB",
            1000L to "1 KB",
            1001L to "1 KB",
            1499L to "1 KB",
            1500L to "2 KB",
            1501L to "2 KB",
            2500L to "3 KB",
            3500L to "4 KB",
            9999L to "10 KB",
            10000L to "10 KB",
            48000L to "48 KB",
            99499L to "99 KB",
            99500L to "100 KB",
            999499L to "999 KB",
            999500L to "1 MB",
            999999L to "1 MB",
            1000000L to "1 MB",
            1049999L to "1 MB",
            1050000L to "1.1 MB",
            1150000L to "1.2 MB",
            1200000L to "1.2 MB",
            1249999L to "1.2 MB",
            1250000L to "1.3 MB",
            1350000L to "1.4 MB",
            1450000L to "1.5 MB",
            1500000L to "1.5 MB",
            9950000L to "10 MB",
            9949999L to "9.9 MB",
            99950000L to "100 MB",
            999949999L to "999.9 MB",
            999950000L to "1 GB",
            999999999L to "1 GB",
            1000000000L to "1 GB",
            1004999999L to "1 GB",
            1005000000L to "1.01 GB",
            1015000000L to "1.02 GB",
            1025000000L to "1.03 GB",
            2150000000L to "2.15 GB",
            2145000000L to "2.15 GB",
            2155000000L to "2.16 GB",
            2500000000L to "2.5 GB",
            2147483648L to "2.15 GB",
            999995000000L to "1,000 GB",
            1000000000000L to "1,000 GB",
            1234567890123L to "1,234.57 GB",
            5000000000000000L to "5,000,000 GB",
            9223372036854775807L to "9,223,372,036.85 GB",
            2146435072L to "2.15 GB",
            2147483676L to "2.15 GB",
            6144L to "6 KB",
            65536L to "66 KB",
        )

        /** The same run in a German region (`en_US@rg=dezzzz`). */
        val FOUNDATION_DE_REGION = listOf(
            0L to "Zero KB",
            999L to "1 KB",
            1050000L to "1,1 MB",
            1500000L to "1,5 MB",
            9949999L to "9,9 MB",
            999949999L to "999,9 MB",
            1005000000L to "1,01 GB",
            2147483648L to "2,15 GB",
            2500000000L to "2,5 GB",
            999995000000L to "1.000 GB",
            1234567890123L to "1.234,57 GB",
            5000000000000000L to "5.000.000 GB",
        )
    }
}
