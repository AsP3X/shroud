package de.corespace.shroud.core.net.wire

import de.corespace.shroud.core.messaging.reactions.ReactionSet
import com.ibm.icu.util.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * The sealed reaction payload, shared with the web client. Ports the wire part of
 * `ios/shroudTests/MessageReactionTests.swift:34-67` (3 cases) and the wire part of
 * `web/src/reactions.selftest.ts:31-67`; vectors verbatim. The sealed web vector
 * (`webSealedReaction`) needs the tagged envelope and belongs to W1-CRYPTO's tests.
 */
class MessageReactionPayloadTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val message = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    private val peer = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002")

    private fun parse(json: String, id: UUID = message) = MessageReactionPayload.parse(json.toByteArray(), id)

    @Test
    fun payloadRoundTripsAndBindsTheMessage() { // iOS :36-46, web :51-54
        val data = MessageReactionPayload.make(listOf("🔥", "👍"), message).encoded()
        val json = String(data)
        // Byte for byte what the web's reactionPayload writes, from an upper-case id too.
        assertEquals("""{"t":"reaction","r":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","e":["🔥","👍"]}""", json)
        assertEquals(listOf("🔥", "👍"), MessageReactionPayload.parse(data, message))
        // A genuine record moved onto another message by the server is dropped.
        assertNull(MessageReactionPayload.parse(data, peer))
        assertNull(MessageReactionPayload.parse(data, UUID.randomUUID()))
    }

    @Test
    fun webPayloadParses() { // iOS :48-57, web :55-60
        assertEquals(listOf("❤️", "👍"), parse("""{"t":"reaction","r":"$message","e":["❤️","👍","❤️","ok"]}"""))
        val flood = MessageReactionPayload.make(ReactionSet.all, message).encoded()
        assertEquals(375, ReactionSet.all.size)
        val parsed = MessageReactionPayload.parse(flood, message)!!
        assertEquals(MessageReactionPayload.READER_CAP, parsed.size)
        assertEquals(ReactionSet.all.take(20), parsed)
        assertNull(parse("""{"t":"transcript","r":"x","c":"y"}"""))
        assertNull(parse("""{"t":"transcript","r":"$message","c":"x"}"""))
        assertNull(parse("not json"))
    }

    @Test
    fun anUpperCaseRecordIdStillBindsTheMessage() {
        assertEquals(listOf("🔥"), parse("""{"t":"reaction","r":"${message.toString().uppercase()}","e":["🔥"]}"""))
    }

    @Test
    fun strictLikeJsonDecoder() {
        // t, r and e are required, e is an array of strings — one stray entry and iOS's JSONDecoder
        // drops the record (the web skips the entry; iOS is the reference).
        assertNull(parse("""{"r":"$message","e":["🔥"]}"""))
        assertNull(parse("""{"t":"reaction","e":["🔥"]}"""))
        assertNull(parse("""{"t":"reaction","r":"$message"}"""))
        assertNull(parse("""{"t":"reaction","r":"$message","e":"🔥"}"""))
        assertNull(parse("""{"t":"reaction","r":"$message","e":["🔥",1]}"""))
        assertNull(parse("""{"t":"reaction","r":"$message","e":["🔥",null]}"""))
        assertNull(parse("""{"t":"reaction","r":" $message","e":["🔥"]}"""))
        assertEquals(emptyList<String>(), parse("""{"t":"reaction","r":"$message","e":[]}"""))
        assertEquals(emptyList<String>(), parse("""{"t":"reaction","r":"$message","e":["ok"],"x":1}"""))
    }

    @Test
    fun onlySingleEmojiAreAccepted() { // iOS :59-67, web :62-67
        for (emoji in listOf("❤️", "🔥", "👍🏽", "❤️‍🔥", "👨‍💻", "🇩🇪", "1️⃣", "🫡")) {
            assertTrue(emoji, MessageReactionPayload.isSingleEmoji(emoji))
        }
        for (text in listOf("", "a", "1", "ok", "🔥🔥", "❤", " 👍", "👍".repeat(9))) {
            assertFalse("\"$text\"", MessageReactionPayload.isSingleEmoji(text))
        }
    }

    @Test
    fun theWebSealedSetReadsAsBothClientsRead() {
        // The emoji of MessageReactionTests.swift:122-125 with the strings the comment says the web
        // sealed alongside ("ok", "🔥🔥", a bare "❤" and a repeat) — the plaintext rule, not the
        // envelope (that vector is W1-CRYPTO's).
        val expected = listOf("👍🏽", "🏳️‍🌈", "🇩🇪", "❤️", "❤️‍🔥", "1️⃣", "👨‍👩‍👧")
        val sealed = expected + listOf("ok", "🔥🔥", "❤", "👍🏽")
        assertEquals(expected, MessageReactionPayload.parse(MessageReactionPayload.make(sealed, message).encoded(), message))
    }

    @Test
    fun theByteCapComesBeforeTheSegmenter() {
        // 👨‍👩‍👧‍👦 + skin tones would be one grapheme, but more than 32 bytes is never an emoji we show.
        val long = "👨🏽‍👩🏽‍👧🏽‍👦🏽"
        assertTrue(WireText.utf8Count(long) > MessageReactionPayload.MAX_EMOJI_BYTES)
        assertFalse(MessageReactionPayload.isSingleEmoji(long))
        assertTrue(MessageReactionPayload.isSingleEmoji("👨‍👩‍👧‍👦"))
    }

    /**
     * The Android rule for older ICUs (api-realtime §7.5): on API 30 (ICU 66, Unicode 13) `🫡` and
     * other Unicode 14+ emoji are unassigned, and only the unassigned-in-U+1F000–U+1FAFF rule keeps
     * them. Simulated here; `MessageReactionPayloadDeviceTest` checks the real ICU on API 30.
     */
    @Test
    fun unicode14EmojiStillCountOnAUnicode13Icu() {
        val unicode13 = UnicodeVersionTextUnits(VersionInfo.getInstance(13, 0))
        val previous = TextUnits.current
        TextUnits.current = unicode13
        try {
            assertTrue(unicode13.isUnassigned(0x1FAE1))
            assertFalse(unicode13.isEmojiPresentation(0x1FAE1))
            for (emoji in listOf("🫡", "🫠", "🩷", "🫨", "🫱🏽", "❤️", "👍🏽", "🇩🇪", "1️⃣")) {
                assertTrue(emoji, MessageReactionPayload.isSingleEmoji(emoji))
            }
            for (text in listOf("a", "ok", "1", "❤", "🫡🫡", " 🫡")) {
                assertFalse("\"$text\"", MessageReactionPayload.isSingleEmoji(text))
            }
            // Outside the emoji blocks an unknown code point is not an emoji.
            assertTrue(unicode13.isUnassigned(0x1FFF0))
            assertFalse(MessageReactionPayload.isSingleEmoji(String(Character.toChars(0x1FFF0))))
        } finally {
            TextUnits.current = previous
        }
    }

    @Test
    fun withoutTheRuleAnOldIcuWouldDropThem() {
        val unicode13 = UnicodeVersionTextUnits(VersionInfo.getInstance(13, 0))
        val withoutRule = object : TextUnits by unicode13 {
            override fun drawsAsEmoji(codePoint: Int): Boolean = isEmojiPresentation(codePoint)
        }
        val previous = TextUnits.current
        TextUnits.current = withoutRule
        try {
            assertFalse(MessageReactionPayload.isSingleEmoji("🫡"))
        } finally {
            TextUnits.current = previous
        }
    }

    @Test
    fun toStringNeverShowsTheEmoji() {
        assertFalse(MessageReactionPayload.make(listOf("🔥"), message).toString().contains("🔥"))
    }
}
