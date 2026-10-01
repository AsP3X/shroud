package de.corespace.shroud.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID

/**
 * Avatar colours and initials, bit-identical to iOS `AvatarView` (`AvatarView.swift:25-80`) and
 * the web's `avatarPalette` (`web/src/components/Avatar.tsx:4-30`): a seed names the same pair on
 * every launch, on every device and in every client (shell-chats §10.2, §12.4).
 */
object AvatarPalette {
    /**
     * Top-to-bottom pairs, in the order of the web's `PALETTE` (`AvatarView.swift:28-37`): an index
     * names the same colours on all clients. Pair 0 is `Theme.brandGradient`.
     */
    val pairs: List<Pair<Color, Color>> = listOf(
        Color(0xFF7C7AFF) to Color(0xFF5E5CE6),
        Color(0xFFFF9F5A) to Color(0xFFF76B1C),
        Color(0xFFFF7A9E) to Color(0xFFE64A72),
        Color(0xFF4AC7FA) to Color(0xFF2E8FE0),
        Color(0xFF5AD97C) to Color(0xFF2FA85B),
        Color(0xFFC77CFF) to Color(0xFF9B4AE6),
        Color(0xFFFFC65A) to Color(0xFFE69A1C),
        Color(0xFF8E8E93) to Color(0xFF5F5F66),
    )

    private val brushes: List<Brush> = pairs.map { (top, bottom) -> Brush.verticalGradient(listOf(top, bottom)) }

    /**
     * Which pair [seed] gets: FNV-1a 32 over the UTF-16 code units plus a finalizer
     * (`AvatarView.swift:47-65`). Keep the finalizer: with eight colours only the low three bits
     * count, and FNV-1a alone makes those depend only on the low bits of each unit ("jane" and
     * "jine" would always match). Kotlin `Char` is a UTF-16 unit, as `charCodeAt` is on the web.
     */
    fun index(seed: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (unit in seed) {
            hash = hash xor unit.code
            hash *= 0x01000193
        }
        hash = hash xor (hash ushr 13)
        hash *= 0x5BD1E995
        hash = hash xor (hash ushr 15)
        return (hash.toUInt() % pairs.size.toUInt()).toInt()
    }

    /** The pair for [seed] (`AvatarView.gradient(for:)`, `AvatarView.swift:40-43`). */
    fun colors(seed: String): Pair<Color, Color> = pairs[index(seed)]

    /** The top-to-bottom gradient for [seed]. */
    fun brush(seed: String): Brush = brushes[index(seed)]

    /** The gradient of palette pair [index] (0 = the brand gradient). */
    fun brushAt(index: Int): Brush = brushes[index.mod(brushes.size)]

    /**
     * Initials (`AvatarView.swift:67-79`): split on whitespace and "@", drop empties; none → "?";
     * one part → its first two characters; else the first character of each of the first two
     * parts; upper-cased without locale rules (Swift `uppercased()`). "Characters" are grapheme
     * clusters, as Swift counts them, so an emoji or an accented letter stays whole.
     */
    fun initials(name: String): String {
        val parts = name.splitWhere { it.isWhitespace() || it == '@' }
        if (parts.isEmpty()) return "?"
        if (parts.size == 1) return parts[0].graphemes(2).uppercase(Locale.ROOT)
        return (parts[0].graphemes(1) + parts[1].graphemes(1)).uppercase(Locale.ROOT)
    }

    /**
     * The seed a person's avatar is coloured by. P15 (decided 2026-10-01): the **username**, as iOS
     * seeds it (`AvatarView.gradient(for: username)`); the web seeds with the user id. Every caller
     * goes through here so the cross-client switch stays one line (memory: avatar colour seed parity).
     */
    @Suppress("UNUSED_PARAMETER")
    fun seed(username: String, userId: UUID): String = username

    private inline fun String.splitWhere(isSeparator: (Char) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (ch in this) {
            if (isSeparator(ch)) {
                if (current.isNotEmpty()) out += current.toString()
                current.setLength(0)
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** The first [count] grapheme clusters (Swift `prefix(_:)` on a `String`). */
    private fun String.graphemes(count: Int): String {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(this)
        var end = 0
        repeat(count) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return substring(0, end)
            end = next
        }
        return substring(0, end)
    }
}
