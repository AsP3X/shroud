package de.corespace.shroud.core.net.wire

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UCharacterCategory
import com.ibm.icu.lang.UProperty
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * [TextUnits] for JVM unit tests: the same calls as [AndroidIcuTextUnits] on ICU4J
 * (`com.ibm.icu:icu4j`, test-only; api-realtime §7.6). ICU4J 78 knows Unicode 17, newer than any
 * phone — the API 30 behaviour is pinned by [UnicodeVersionTextUnits] and the device test.
 */
object Icu4jTextUnits : TextUnits {
    override fun graphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(ULocale.ROOT)
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

    override fun isEmojiPresentation(codePoint: Int): Boolean =
        UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION)

    override fun isEmoji(codePoint: Int): Boolean = UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI)

    override fun isUnassigned(codePoint: Int): Boolean =
        UCharacter.getType(codePoint) == UCharacterCategory.UNASSIGNED.toInt()
}

/**
 * An older ICU, simulated: every code point that [Icu4jTextUnits] dates after [maxAge] (e.g.
 * Unicode 13.0 for API 30's ICU 66) is unassigned, with no properties — what `android.icu` on such a
 * phone answers. Graphemes stay ICU4J's (an old ICU already joins emoji sequences through the
 * pre-assigned `Extended_Pictographic` ranges).
 */
class UnicodeVersionTextUnits(private val maxAge: com.ibm.icu.util.VersionInfo) : TextUnits {
    private fun known(codePoint: Int): Boolean {
        if (UCharacter.getType(codePoint) == UCharacterCategory.UNASSIGNED.toInt()) return false
        return UCharacter.getAge(codePoint).compareTo(maxAge) <= 0
    }

    override fun graphemes(text: String): List<String> = Icu4jTextUnits.graphemes(text)

    override fun isEmojiPresentation(codePoint: Int): Boolean = known(codePoint) && Icu4jTextUnits.isEmojiPresentation(codePoint)

    override fun isEmoji(codePoint: Int): Boolean = known(codePoint) && Icu4jTextUnits.isEmoji(codePoint)

    override fun isUnassigned(codePoint: Int): Boolean = !known(codePoint)
}

/**
 * Sets [TextUnits.current] to [units] for one test and puts the previous one back afterwards. Every
 * JVM test that touches the sealed shapes (snippets, previews, transcripts, reactions) needs it:
 * `@get:Rule val textUnits = Icu4jTextUnitsRule()`.
 */
class Icu4jTextUnitsRule(private val units: TextUnits = Icu4jTextUnits) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val previous = TextUnits.current
            TextUnits.current = units
            try {
                base.evaluate()
            } finally {
                TextUnits.current = previous
            }
        }
    }
}
