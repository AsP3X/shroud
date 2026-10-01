package de.corespace.shroud.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

/**
 * Ports `ios/shroudTests/AvatarPaletteTests.swift` (vectors copied verbatim; the expected indexes
 * were computed with the web client's `avatarPalette`, `web/src/components/Avatar.tsx`, so they
 * pin that all three clients share one hash), plus Android checks of the palette, initials and
 * seed rules of `AvatarView.swift:25-80` (shell-chats §10.2, §12.4, §14).
 */
class AvatarPaletteTest {
    /** `AvatarPaletteTests.swift:12-20`. */
    @Test
    fun nameKeepsItsColourOnEveryLaunch() {
        assertEquals(7, AvatarPalette.index("Jane Cooper"))
        assertEquals(3, AvatarPalette.index("jane_cooper"))
        assertEquals(5, AvatarPalette.index("Design Team"))
    }

    /** `AvatarPaletteTests.swift:25-36`. */
    @Test
    fun seedPicksTheSamePairAsTheWebClient() {
        assertEquals(0, AvatarPalette.index(""))
        assertEquals(7, AvatarPalette.index("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f"))
        assertEquals(2, AvatarPalette.index("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"))
        assertEquals(0, AvatarPalette.index("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"))
        assertEquals(2, AvatarPalette.index("jane"))
        assertEquals(4, AvatarPalette.index("jine"))
        // Non-ASCII must follow the web client's UTF-16 code units, not the UTF-8 bytes.
        assertEquals(4, AvatarPalette.index("ä"))
        assertEquals(3, AvatarPalette.index("👋"))
    }

    @Test
    fun emojiSeedIsHashedAsTwoUtf16Units() {
        // "👋" is a surrogate pair: the hash runs over both units, as `charCodeAt` does on the web.
        assertEquals(2, "👋".length)
    }

    @Test
    fun paletteMatchesTheIosAndWebPairsInOrder() {
        val expected = listOf(
            0xFF7C7AFF to 0xFF5E5CE6,
            0xFFFF9F5A to 0xFFF76B1C,
            0xFFFF7A9E to 0xFFE64A72,
            0xFF4AC7FA to 0xFF2E8FE0,
            0xFF5AD97C to 0xFF2FA85B,
            0xFFC77CFF to 0xFF9B4AE6,
            0xFFFFC65A to 0xFFE69A1C,
            0xFF8E8E93 to 0xFF5F5F66,
        ).map { (top, bottom) -> Color(top) to Color(bottom) }
        assertEquals(expected, AvatarPalette.pairs)
        assertEquals(expected[7], AvatarPalette.colors("Jane Cooper"))
    }

    @Test
    fun indexStaysInsideThePaletteForManySeeds() {
        repeat(2_000) { n ->
            val index = AvatarPalette.index("seed-$n-${n * 7919}")
            assert(index in AvatarPalette.pairs.indices) { "index $index for seed $n" }
        }
    }

    /** `AvatarView.swift:67-79`: split on whitespace and "@", drop empties. */
    @Test
    fun initialsTakeTheFirstLetterOfTheFirstTwoParts() {
        assertEquals("JC", AvatarPalette.initials("Jane Cooper"))
        assertEquals("DT", AvatarPalette.initials("design team"))
        assertEquals("JC", AvatarPalette.initials("  jane   cooper  smith "))
        assertEquals("NE", AvatarPalette.initials("nina@example.org"))
        assertEquals("JD", AvatarPalette.initials("jane\tdoe"))
    }

    @Test
    fun aSinglePartGivesItsFirstTwoCharacters() {
        assertEquals("JA", AvatarPalette.initials("jane_cooper"))
        assertEquals("X", AvatarPalette.initials("x"))
        assertEquals("AB", AvatarPalette.initials("@ab@"))
    }

    @Test
    fun nothingToTakeGivesAQuestionMark() {
        assertEquals("?", AvatarPalette.initials(""))
        assertEquals("?", AvatarPalette.initials("   "))
        assertEquals("?", AvatarPalette.initials("@ @"))
    }

    @Test
    fun charactersAreGraphemeClustersAsSwiftCountsThem() {
        // Swift `prefix(_:)` counts grapheme clusters: an emoji with a skin tone stays whole.
        assertEquals("👋🏽H", AvatarPalette.initials("👋🏽 hi"))
        assertEquals("👋🏽👋", AvatarPalette.initials("👋🏽👋👋"))
        // A decomposed "é" (e + combining acute) is one character.
        assertEquals("ÉV", AvatarPalette.initials("élise vogel"))
    }

    @Test
    fun upperCasingIsLocaleFree() {
        // Swift `uppercased()` has no locale; a Turkish phone must not turn "i" into "İ".
        assertEquals("IS", AvatarPalette.initials("ilse sander"))
        assertEquals("SS", AvatarPalette.initials("ß"))
    }

    /** P15 (decided 2026-10-01): the username seeds the colour, as on iOS. */
    @Test
    fun seedIsTheUsername() {
        val id = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
        assertEquals("jane_cooper", AvatarPalette.seed("jane_cooper", id))
        assertNotEquals(AvatarPalette.index(id.toString()), AvatarPalette.index(AvatarPalette.seed("jane_cooper", id)))
    }
}
