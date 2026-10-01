package de.corespace.shroud.core.net.wire

import android.icu.lang.UCharacter
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.core.messaging.reactions.ReactionSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The reaction payload rule on the phone's own ICU (`android.icu`), not ICU4J (plan W1-WIRE
 * acceptance; messaging-core D3). Run it on the API 30 emulator: Android 11 ships ICU 66
 * (Unicode 13), where `🫡` (U+1FAE1, Unicode 14) is unassigned and only the
 * unassigned-in-U+1F000–U+1FAFF rule ([TextUnits.drawsAsEmoji], api-realtime §7.5) keeps it — iOS
 * and the web show it, so an Android 11 reader must too. On newer releases the same assertions hold
 * through the real `Emoji_Presentation` property.
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.corespace.shroud.core.net.wire.MessageReactionPayloadDeviceTest`
 */
@RunWith(AndroidJUnit4::class)
class MessageReactionPayloadDeviceTest {
    private val message = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")

    @Test
    fun productionUsesThePlatformIcu() {
        assertSame(AndroidIcuTextUnits, TextUnits.current)
    }

    @Test
    fun onlySingleEmojiAreAccepted() { // MessageReactionTests.swift:59-67, web reactions.selftest.ts:62-67
        for (emoji in listOf("❤️", "🔥", "👍🏽", "❤️‍🔥", "👨‍💻", "🇩🇪", "1️⃣", "🫡")) {
            assertTrue(emoji, MessageReactionPayload.isSingleEmoji(emoji))
        }
        for (text in listOf("", "a", "1", "ok", "🔥🔥", "❤", " 👍", "👍".repeat(9))) {
            assertFalse("\"$text\"", MessageReactionPayload.isSingleEmoji(text))
        }
    }

    /** The case this test exists for: on Unicode 13 the rule, not the property, keeps 🫡. */
    @Test
    fun unicode14EmojiSurviveAnOlderIcu() {
        val unicodeMajor = UCharacter.getUnicodeVersion().major
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) assertEquals("API 30 ships Unicode 13", 13, unicodeMajor)
        assumeTrue("ICU knows Unicode $unicodeMajor: 🫡 is an assigned emoji here", unicodeMajor < 14)
        assertTrue(AndroidIcuTextUnits.isUnassigned(0x1FAE1))
        assertFalse(AndroidIcuTextUnits.isEmojiPresentation(0x1FAE1))
        assertTrue(AndroidIcuTextUnits.drawsAsEmoji(0x1FAE1))
        for (emoji in listOf("🫡", "🫠", "🫶", "🥹", "🫱🏽")) {
            assertTrue(emoji, MessageReactionPayload.isSingleEmoji(emoji))
        }
        assertFalse(MessageReactionPayload.isSingleEmoji("🫡🫡"))
    }

    /** Every emoji the picker offers is one the reader shows, on this phone's ICU. */
    @Test
    fun everyPickableEmojiIsASingleEmoji() {
        assertEquals(375, ReactionSet.all.size)
        for (emoji in ReactionSet.all) assertTrue(emoji, MessageReactionPayload.isSingleEmoji(emoji))
    }

    @Test
    fun webPayloadParses() { // MessageReactionTests.swift:48-57, web reactions.selftest.ts:51-60
        val web = """{"t":"reaction","r":"$message","e":["❤️","👍","❤️","ok"]}"""
        assertEquals(listOf("❤️", "👍"), MessageReactionPayload.parse(web.toByteArray(), message))
        val flood = MessageReactionPayload.make(ReactionSet.all, message).encoded()
        assertEquals(ReactionSet.all.take(MessageReactionPayload.READER_CAP), MessageReactionPayload.parse(flood, message))
        assertNull(MessageReactionPayload.parse(flood, UUID.randomUUID()))
        // The emoji MessageReactionTests.swift:122-125 expects from the web-sealed record.
        val expected = listOf("👍🏽", "🏳️‍🌈", "🇩🇪", "❤️", "❤️‍🔥", "1️⃣", "👨‍👩‍👧")
        val sealed = MessageReactionPayload.make(expected + listOf("ok", "🔥🔥", "❤", "👍🏽"), message).encoded()
        assertEquals(expected, MessageReactionPayload.parse(sealed, message))
    }

    @Test
    fun graphemesOnThePlatformIcu() {
        for (one in listOf("👩‍👩‍👧‍👦", "👍🏽", "🇩🇪", "1️⃣", "❤️‍🔥", "🏳️‍🌈", "\r\n")) {
            assertEquals(one, listOf(one), AndroidIcuTextUnits.graphemes(one))
            assertEquals(one, 1, AndroidIcuTextUnits.graphemeCount(one))
        }
        val family = "👩‍👩‍👧‍👦"
        assertEquals(family.repeat(119) + "…", MessageReplyReference.clampSnippet(family.repeat(130)))
        assertEquals("two lines of text", MessageReplyReference.clampSnippet("  two   lines\nof   text \n"))
    }
}
