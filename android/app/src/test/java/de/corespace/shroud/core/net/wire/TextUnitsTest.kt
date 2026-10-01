package de.corespace.shroud.core.net.wire

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UCharacterCategory
import com.ibm.icu.lang.UProperty
import com.ibm.icu.util.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The grapheme/emoji seam (api-realtime §7.6, plan C35) and the whitespace, trimming and clamping
 * helpers every sealed shape shares ([WireText]), checked against ICU4J's Unicode data.
 */
class TextUnitsTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    // --- graphemes (Swift Character) ---------------------------------------------------------------

    @Test
    fun graphemesAreExtendedClustersLikeSwiftCharacters() {
        val units = Icu4jTextUnits
        assertEquals(emptyList<String>(), units.graphemes(""))
        assertEquals(listOf("a", "b"), units.graphemes("ab"))
        // CR LF is one Character in Swift.
        assertEquals(listOf("a", "\r\n", "b"), units.graphemes("a\r\nb"))
        // ZWJ families, skin tones, flags, keycaps, tag sequences, combining marks: one each.
        for (one in listOf("👩‍👩‍👧‍👦", "👍🏽", "🇩🇪", "1️⃣", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "é", "❤️‍🔥", "🏳️‍🌈")) {
            assertEquals(one, listOf(one), units.graphemes(one))
            assertEquals(one, 1, units.graphemeCount(one))
        }
        assertEquals(listOf("🇩🇪", "🇫🇷"), units.graphemes("🇩🇪🇫🇷"))
        assertEquals(2, units.graphemeCount("🔥🔥"))
    }

    @Test
    fun emojiPropertiesComeFromIcu() {
        val units = Icu4jTextUnits
        assertTrue(units.isEmojiPresentation(0x1F525)) // 🔥
        assertFalse(units.isEmojiPresentation(0x2764)) // ❤ (text by default)
        assertTrue(units.isEmoji(0x2764))
        assertTrue(units.isEmoji('1'.code)) // keycap base
        assertFalse(units.isEmoji('a'.code))
        assertFalse(units.isUnassigned(0x1FAE1)) // 🫡 is assigned in ICU4J 78
        assertTrue(units.isUnassigned(0x1FFFE)) // a noncharacter: general category Cn forever
    }

    /**
     * The Android rule (api-realtime §7.5): a code point this ICU does not know inside
     * U+1F000–U+1FAFF draws as an emoji, so an API 30 phone (ICU 66, Unicode 13) keeps 🫡 (U+1FAE1,
     * Unicode 14) and 🩷 (U+1FA77, Unicode 15).
     */
    @Test
    fun unassignedCodePointsInTheEmojiBlocksDrawAsEmoji() {
        val unicode13 = UnicodeVersionTextUnits(VersionInfo.getInstance(13, 0))
        for (codePoint in listOf(0x1FAE1, 0x1FA77, 0x1FAE0)) {
            assertFalse(unicode13.isEmojiPresentation(codePoint))
            assertTrue(unicode13.isUnassigned(codePoint))
            assertTrue(Integer.toHexString(codePoint), unicode13.drawsAsEmoji(codePoint))
        }
        // Known code points keep their real property.
        assertTrue(unicode13.drawsAsEmoji(0x1F525))
        assertFalse(unicode13.drawsAsEmoji(0x2764))
        // Assigned non-emoji inside the blocks (a mahjong tile without Emoji_Presentation) stay text.
        assertFalse(unicode13.drawsAsEmoji(0x1F000))
        // Outside the blocks an unknown code point is never an emoji.
        assertFalse(unicode13.drawsAsEmoji(0x1FB00 + 0xFF))
        assertFalse(unicode13.drawsAsEmoji(0xE0000 + 0x80))
        assertEquals(0x1F000..0x1FAFF, TextUnits.UNASSIGNED_EMOJI_BLOCKS)
    }

    @Test
    fun theSimulatedOldIcuMatchesUnicode13() {
        val unicode13 = UnicodeVersionTextUnits(VersionInfo.getInstance(13, 0))
        assertFalse(unicode13.isUnassigned(0x1FA79)) // 🩹 is Unicode 12
        assertTrue(unicode13.isEmojiPresentation(0x1FA79))
        assertTrue(unicode13.isUnassigned(0x1FAE1))
    }

    /** On the JVM `android.icu` is a stub; the production seam must fail loudly, never answer false. */
    @Test
    fun thePlatformSeamFailsLoudlyOutsideADevice() {
        assertThrows(IllegalStateException::class.java) { AndroidIcuTextUnits.graphemes("a") }
        assertThrows(IllegalStateException::class.java) { AndroidIcuTextUnits.isEmojiPresentation(0x1F525) }
        assertThrows(IllegalStateException::class.java) { AndroidIcuTextUnits.isUnassigned(0x1FAE1) }
        assertEquals(emptyList<String>(), AndroidIcuTextUnits.graphemes(""))
    }

    // --- whitespace ---------------------------------------------------------------------------------

    /** Swift `Character.isWhitespace` and `CharacterSet.whitespacesAndNewlines` are Unicode White_Space. */
    @Test
    fun whitespaceOrNewlineIsExactlyUnicodeWhiteSpace() {
        for (codePoint in 0..0x10FFFF) {
            assertEquals(
                "U+${Integer.toHexString(codePoint)}",
                UCharacter.hasBinaryProperty(codePoint, UProperty.WHITE_SPACE),
                WireText.isWhitespaceOrNewline(codePoint),
            )
        }
    }

    /** `CharacterSet.whitespaces`: general category Zs plus U+0009. */
    @Test
    fun horizontalWhitespaceIsSpaceSeparatorsAndTab() {
        for (codePoint in 0..0xFFFF) {
            val expected = codePoint == 0x09 || UCharacter.getType(codePoint) == UCharacterCategory.SPACE_SEPARATOR.toInt()
            assertEquals("U+${Integer.toHexString(codePoint)}", expected, WireText.isHorizontalWhitespace(codePoint))
        }
    }

    @Test
    fun trimmingFollowsFoundationNotKotlin() {
        // NEL, NBSP and LINE SEPARATOR are trimmed; U+001C (Kotlin whitespace) and U+FEFF (JS trim) are not.
        assertEquals("a b", WireText.trimWhitespacesAndNewlines("\u0085  a b \n"))
        assertEquals("\u001Ca\u001C", WireText.trimWhitespacesAndNewlines("\u001Ca\u001C"))
        assertEquals("﻿a", WireText.trimWhitespacesAndNewlines(" ﻿a "))
        // .whitespaces keeps newlines.
        assertEquals("\na\n", WireText.trimWhitespaces(" \t\na\n　"))
    }

    // --- byte counts --------------------------------------------------------------------------------

    @Test
    fun utf8CountMatchesTheEncodedLength() {
        for (text in listOf("", "a", "é", "語", "🔥", "👩‍👩‍👧‍👦", "a\u0000b", "Herzogstand – Heimgarten")) {
            assertEquals(text, text.toByteArray(Charsets.UTF_8).size, WireText.utf8Count(text))
        }
        // A lone surrogate counts as U+FFFD, the replacement a Swift string would hold.
        assertEquals(3, WireText.utf8Count("\uD83D"))
        assertEquals(4, WireText.utf8Count("a\uDC00"))
        assertEquals(3, WireText.utf8Count(WireText.ELLIPSIS))
    }

    // --- collapse and clamp -------------------------------------------------------------------------

    @Test
    fun collapseMakesOneLine() {
        assertEquals("two lines of text", WireText.collapseWhitespace("  two   lines\nof   text \n"))
        assertEquals("", WireText.collapseWhitespace(" \n\t "))
        // CR LF is one whitespace Character.
        assertEquals("a b", WireText.collapseWhitespace("a\r\nb"))
        // Whitespace is decided by a Character's first scalar: a space with a combining mark is one.
        assertEquals("a b", WireText.collapseWhitespace("a ́b"))
    }

    @Test
    fun clampCutsAtGraphemesAndTrimsBeforeTheEllipsis() {
        assertEquals("abc", WireText.clampCharacters("abc", 3))
        assertEquals("ab…", WireText.clampCharacters("abcd", 3))
        assertEquals("a…", WireText.clampCharacters("a bcd", 3))
        assertEquals("🇩🇪🇩🇪…", WireText.clampCharacters("🇩🇪".repeat(5), 3))
        // A newline before the cut stays: the tail trims .whitespaces only, as Swift does.
        assertEquals("a\n…", WireText.clampCharacters("a\nbcd", 3))
    }
}
