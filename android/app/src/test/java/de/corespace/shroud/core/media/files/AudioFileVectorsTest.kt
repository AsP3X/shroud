package de.corespace.shroud.core.media.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11's shared rules against every row of the `== audio tags ==`, `== audio titles ==`,
 * `== audio durations ==` and `== audio sniff ==` blocks that `node scripts/gen_file_vectors.mjs`
 * prints (docs/file-sharing.md §9), inputs spelled with escapes so invisible characters stay visible.
 */
class AudioFileVectorsTest {
    private val tags = listOf<Pair<String, String?>>(
        "Midnight City" to "Midnight City",
        "  Holocene  " to "Holocene",
        "Bon  Iver" to "Bon Iver",
        "line\nbreak\ttab" to "line break tab",
        "rtl‮override" to "rtloverride",
        "zero​width﻿" to "zerowidth",
        "Beyoncé" to "Beyoncé",
        "   " to null,
        "" to null,
        "\u0000\u0007" to null,
        "T".repeat(199) + " x" to "T".repeat(199),
        "y".repeat(250) to "y".repeat(200),
        "🎵 emoji title" to "🎵 emoji title",
    )

    @Test
    fun allThirteenTagRowsClean() {
        assertEquals(13, tags.size)
        for ((input, expected) in tags) assertEquals("clean(${input.take(20)})", expected, AudioTags.clean(input))
    }

    @Test
    fun tagCleaningIsIdempotentAndNullStaysAbsent() {
        for ((_, expected) in tags) if (expected != null) assertEquals(expected, AudioTags.clean(expected))
        assertNull(AudioTags.clean(null))
    }

    @Test
    fun allFiveTitleRows() {
        val rows = listOf(
            Triple("Midnight City", "M83", "track01.mp3") to "Midnight City – M83",
            Triple("Midnight City", null, "track01.mp3") to "Midnight City",
            Triple(null, "M83", "track01.mp3") to "track01.mp3",
            Triple("  ", "  ", "Interview raw take.flac") to "Interview raw take.flac",
            Triple(null, null, "../x/Demo v3 (final mix).wav") to "Demo v3 (final mix).wav",
        )
        for ((input, expected) in rows) assertEquals(expected, AudioFileCopy.displayTitle(input.first, input.second, input.third))
    }

    @Test
    fun allElevenDurationRows() {
        val rows = listOf(
            Triple(0L, "0:00", "0:00"),
            Triple(499L, "0:00", "0:00"),
            Triple(500L, "0:01", "0:00"),
            Triple(999L, "0:01", "0:00"),
            Triple(59_499L, "0:59", "0:59"),
            Triple(59_500L, "1:00", "0:59"),
            Triple(243_400L, "4:03", "4:03"),
            Triple(3_599_499L, "59:59", "59:59"),
            Triple(3_599_500L, "1:00:00", "59:59"),
            Triple(3_600_000L, "1:00:00", "1:00:00"),
            Triple(45_296_000L, "12:34:56", "12:34:56"),
        )
        for ((ms, total, elapsed) in rows) {
            assertEquals("total $ms", total, AudioDurations.total(ms))
            assertEquals("elapsed $ms", elapsed, AudioDurations.elapsed(ms))
        }
    }

    @Test
    fun allTwentyTwoSniffRows() {
        val rows = listOf(
            Triple("mp3", "494433040000", true), Triple("mp3", "fffb9064", true), Triple("mp3", "fff15080", true), Triple("mp3", "00000020", false),
            Triple("aac", "fff15080", true), Triple("aac", "fff95080", true), Triple("aac", "fffb9064", false), Triple("aac", "494433", true),
            Triple("m4a", "0000002066747970", true), Triple("m4a", "0000002066726565", false),
            Triple("wav", "52494646244200005741564566", true), Triple("wav", "524946462442000041564920", false),
            Triple("flac", "664c614300000022", true), Triple("flac", "4944330300", true), Triple("flac", "4f676753", false),
            Triple("ogg", "4f67675300020000", true), Triple("opus", "4f67675300020000", true), Triple("ogg", "664c6143", false),
            Triple("aiff", "464f524d0000a0c641494646", true), Triple("aif", "464f524d0000a0c641494643", true),
            Triple("aiff", "464f524d0000a0c64d415220", false),
            Triple("mp3", "", false),
        )
        assertEquals(22, rows.size)
        for ((ext, hex, expected) in rows) {
            val type = requireNotNull(FileTypes.forExtension(ext))
            assertEquals("$ext $hex", expected, FileContentCheck.matches(type, hex(hex)))
        }
    }

    @Test
    fun theSniffOnlyLooksAtTheBytesItWasGiven() {
        val wav = FileTypes.forExtension("wav")!!
        val bytes = hex("52494646244200005741564566")
        assertTrue(FileContentCheck.matches(wav, bytes, 12))
        assertFalse("WAVE sits at 8…11", FileContentCheck.matches(wav, bytes, 11))
    }

    @Test
    fun theAudioRowIsInTheTableAndThePicker() {
        val expected = mapOf(
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav", "flac" to "audio/flac",
            "ogg" to "audio/ogg", "opus" to "audio/ogg", "aif" to "audio/aiff", "aiff" to "audio/aiff",
        )
        for ((ext, mime) in expected) {
            val type = requireNotNull(FileTypes.forExtension(ext))
            assertEquals(mime, type.mime)
            assertEquals(FileCategory.Audio, type.category)
            assertNull(type.warning)
            assertTrue(type.canOpen)
            assertTrue(mime in FileTypes.pickerMimeTypes)
        }
        // The names document providers report for these files are offered too.
        for (alias in listOf("audio/x-wav", "audio/x-aiff", "application/ogg", "audio/x-m4a", "audio/x-flac")) assertTrue(alias in FileTypes.pickerMimeTypes)
    }

    @Test
    fun theWordsAreTheSpecs() {
        assertEquals("Audio", AudioFileCopy.AUDIO)
        assertEquals("Can't play on this phone", AudioFileCopy.CANT_PLAY)
        assertEquals("🎵 Midnight City – M83", AudioFileCopy.preview("Midnight City – M83"))
        assertEquals("M83 · 4:03 · 9.7 MB · MP3", AudioFileCopy.composerMeta("M83", 243_400, "9.7 MB", "MP3"))
        assertEquals("4:03 · 9.7 MB · MP3", AudioFileCopy.composerMeta(null, 243_400, "9.7 MB", "MP3"))
        assertEquals("9.7 MB · MP3", AudioFileCopy.composerMeta("  ", null, "9.7 MB", "MP3"))
        assertEquals("Audio, Midnight City, M83, 4:03", AudioFileCopy.accessibilityLabel("Midnight City", "M83", 243_400))
        assertEquals("Audio, track01.mp3", AudioFileCopy.accessibilityLabel("track01.mp3", null, null))
        assertEquals("M83 · 1:05 / 4:03", AudioFileCopy.nowPlayingDetail("M83", 65_900, 243_400))
        assertEquals("1:05", AudioFileCopy.nowPlayingDetail(null, 65_900, null))
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
