package de.corespace.shroud.core.net.wire

import android.icu.lang.UCharacter
import android.icu.lang.UCharacterCategory
import android.icu.lang.UProperty
import android.icu.text.BreakIterator

/**
 * Grapheme and emoji queries behind one seam (api-realtime §7.6, plan conflict C35).
 *
 * Swift counts and cuts strings by `Character` (an extended grapheme cluster) and reads emoji
 * properties from `Unicode.Scalar.Properties`; the sealed shapes depend on both — snippet and
 * preview clamps (`MessageReplyReference.swift:62-80`, `LinkPreview.swift:180-199`), the transcript
 * cut (`MessageAnnotation.swift:30-44`) and the single-emoji rule of reactions
 * (`MessageReaction.swift:49-58`). On a device that is `android.icu` ([AndroidIcuTextUnits]); JVM unit
 * tests have no `android.icu` (and `isReturnDefaultValues = true` would quietly answer null and
 * false), so they set [current] to the test-side `Icu4jTextUnits` (same calls on `com.ibm.icu`).
 */
interface TextUnits {
    /** Extended grapheme clusters of [text], in order: Swift `Array(text)`. */
    fun graphemes(text: String): List<String>

    /** Swift `text.count`. */
    fun graphemeCount(text: String): Int = graphemes(text).size

    /** Unicode `Emoji_Presentation` (Swift `properties.isEmojiPresentation`). */
    fun isEmojiPresentation(codePoint: Int): Boolean

    /** Unicode `Emoji` (Swift `properties.isEmoji`). */
    fun isEmoji(codePoint: Int): Boolean

    /** General category `Cn` in this ICU's Unicode version: a code point it does not know yet. */
    fun isUnassigned(codePoint: Int): Boolean

    /**
     * Drawn as an emoji on its own: `Emoji_Presentation`, or — Android's addition (api-realtime §7.5,
     * plan C35, risk table "Emoji/ICU differences") — a code point this ICU still reports as
     * unassigned inside [UNASSIGNED_EMOJI_BLOCKS]. API 30 ships ICU 66 (Unicode 13), where `🫡`
     * (U+1FAE1, Unicode 14) is unassigned; iOS and the web know it, so without this rule an Android
     * 11 phone would drop reactions the others show. New emoji are assigned in this range; the rare
     * later non-emoji symbol there is accepted too on an old ICU, which only shows a glyph the
     * sender's client chose to send — harmless.
     */
    fun drawsAsEmoji(codePoint: Int): Boolean =
        isEmojiPresentation(codePoint) || (codePoint in UNASSIGNED_EMOJI_BLOCKS && isUnassigned(codePoint))

    companion object {
        /** Mahjong Tiles … Symbols and Pictographs Extended-A: where new emoji are assigned. */
        val UNASSIGNED_EMOJI_BLOCKS: IntRange = 0x1F000..0x1FAFF

        /**
         * The implementation every wire helper uses. Production never changes it; JVM tests set it
         * to `Icu4jTextUnits` (or use `Icu4jTextUnitsRule`).
         */
        @Volatile
        var current: TextUnits = AndroidIcuTextUnits
    }
}

/**
 * [TextUnits] on the platform ICU (`android.icu`, API 24+; `UProperty.EMOJI*` API 28+).
 *
 * A new [BreakIterator] per call: instances are not thread-safe, and `getCharacterInstance()` clones
 * a cached prototype, so it is cheap. On the JVM (unit tests with stubbed `android.jar`) every call
 * fails loudly instead of answering null/false, which would read as "no graphemes, no emoji".
 */
object AndroidIcuTextUnits : TextUnits {
    private const val STUBBED =
        "android.icu is stubbed here (JVM unit test?): set TextUnits.current = Icu4jTextUnits"

    /** 🔥 is `Emoji_Presentation` in every ICU; a stubbed `android.jar` answers false. */
    private val icuWorks: Boolean by lazy { UCharacter.hasBinaryProperty(0x1F525, UProperty.EMOJI_PRESENTATION) }

    private fun requireIcu() = check(icuWorks) { STUBBED }

    private fun characterIterator(): BreakIterator {
        val iterator: BreakIterator? = BreakIterator.getCharacterInstance()
        return checkNotNull(iterator) { STUBBED }
    }

    override fun graphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = characterIterator()
        iterator.setText(text)
        val out = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out += text.substring(start, end)
            start = end
            end = iterator.next()
        }
        return out
    }

    override fun graphemeCount(text: String): Int {
        if (text.isEmpty()) return 0
        val iterator = characterIterator()
        iterator.setText(text)
        var count = 0
        iterator.first()
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    override fun isEmojiPresentation(codePoint: Int): Boolean {
        requireIcu()
        return UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION)
    }

    override fun isEmoji(codePoint: Int): Boolean {
        requireIcu()
        return UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI)
    }

    override fun isUnassigned(codePoint: Int): Boolean {
        requireIcu()
        return UCharacter.getType(codePoint) == UCharacterCategory.UNASSIGNED.toInt()
    }
}

/**
 * Whitespace, trimming and clamping exactly as the iOS wire code does it, independent of the ICU
 * version (the sets below are fixed by Unicode's stability policy for `White_Space`).
 *
 * - Swift `CharacterSet.whitespacesAndNewlines` and `Character.isWhitespace || isNewline` (decided by
 *   the first scalar) are both Unicode `White_Space`: [isWhitespaceOrNewline]. Kotlin's
 *   `Char.isWhitespace()` differs (it takes U+001C–U+001F and misses U+0085), and the web's `trim()`
 *   also strips U+FEFF; neither is used for sealed shapes.
 * - `CharacterSet.whitespaces` is general category Zs plus U+0009: [isHorizontalWhitespace].
 */
object WireText {
    /** Unicode `White_Space` (25 code points). */
    fun isWhitespaceOrNewline(codePoint: Int): Boolean = when (codePoint) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** `CharacterSet.whitespaces`: Zs and CHARACTER TABULATION. */
    fun isHorizontalWhitespace(codePoint: Int): Boolean = when (codePoint) {
        0x09, 0x20, 0xA0, 0x1680, in 0x2000..0x200A, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** `trimmingCharacters(in: .whitespacesAndNewlines)`. Every member is in the BMP, so chars suffice. */
    fun trimWhitespacesAndNewlines(text: String): String = text.trim { isWhitespaceOrNewline(it.code) }

    /** `trimmingCharacters(in: .whitespaces)`. */
    fun trimWhitespaces(text: String): String = text.trim { isHorizontalWhitespace(it.code) }

    /**
     * Swift `text.utf8.count` without allocating. A lone surrogate (which a Swift string cannot hold;
     * bridging turns it into U+FFFD) counts as the three bytes of U+FFFD.
     */
    fun utf8Count(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code < 0x80 -> bytes += 1
                c.code < 0x800 -> bytes += 2
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    bytes += 4
                    i++
                }
                else -> bytes += 3
            }
            i++
        }
        return bytes
    }

    /**
     * One line of text: every run of whitespace (a grapheme whose first scalar is `White_Space`)
     * becomes one space, none at the start or the end — the loop of `clampSnippet`
     * (`MessageReplyReference.swift:62-76`) and `LinkPreview.clean` (`LinkPreview.swift:180-195`).
     */
    fun collapseWhitespace(raw: String, units: TextUnits = TextUnits.current): String {
        val collapsed = StringBuilder(raw.length)
        var pendingSpace = false
        for (character in units.graphemes(raw)) {
            if (isWhitespaceOrNewline(character.codePointAt(0))) {
                pendingSpace = collapsed.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                collapsed.append(' ')
                pendingSpace = false
            }
            collapsed.append(character)
        }
        return collapsed.toString()
    }

    /**
     * [text] itself when it has at most [maxCharacters] graphemes, else its first `max − 1`
     * graphemes with trailing [isHorizontalWhitespace] trimmed and `…` appended — the tail of
     * `clampSnippet` (`MessageReplyReference.swift:77-79`) and `clean` (`LinkPreview.swift:197-198`).
     */
    fun clampCharacters(text: String, maxCharacters: Int, units: TextUnits = TextUnits.current): String {
        val characters = units.graphemes(text)
        if (characters.size <= maxCharacters) return text
        val kept = characters.subList(0, maxOf(0, maxCharacters - 1)).joinToString("")
        return trimWhitespaces(kept) + ELLIPSIS
    }

    /** U+2026, the cut marker of every clamp (3 UTF-8 bytes). */
    const val ELLIPSIS = "…"
}
