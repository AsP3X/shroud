package de.corespace.shroud.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Mirrors `ios/shroudTests/BIP39SeedTests.swift`, `EncryptionPhraseGeneratorTests.swift` and
 * `EncryptionPhraseParserTests.swift` (crypto spec §16.1, §17.1).
 */
class Bip39Test {
    private val bip39 = TestWordlist.bip39
    private val abandonAbout = List(11) { "abandon" } + "about"

    @Test
    fun wordlistIsTheStandardEnglishList() {
        val file = listOf("src/main/assets/bip39-english.txt", "app/src/main/assets/bip39-english.txt").map(::File).first { it.exists() }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        // SHA-256 of the canonical english.txt from the BIP39 repository.
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", digest)
    }

    @Test
    fun zeroEntropyGivesAbandonAbout() {
        assertEquals(abandonAbout, bip39.fromEntropy(ByteArray(16)))
    }

    @Test
    fun abandonAboutValidatesAndSeedsToTheBip39Vector() {
        assertEquals(abandonAbout, bip39.validate(abandonAbout))
        val seed = bip39.seed(abandonAbout)
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            seed.hex(),
        )
    }

    @Test
    fun normalisesCaseAndWhitespace() {
        val messy = List(11) { " ABANDON " } + "About"
        assertEquals(abandonAbout, bip39.validate(messy))
    }

    @Test(expected = Bip39.PhraseException.InvalidChecksum::class)
    fun invalidChecksumRejected() {
        bip39.validate(List(12) { "abandon" })
    }

    @Test(expected = Bip39.PhraseException.UnknownWord::class)
    fun unknownWordRejected() {
        bip39.validate(List(11) { "abandon" } + "notaword")
    }

    @Test(expected = Bip39.PhraseException.InvalidWordCount::class)
    fun shortPhraseRejected() {
        bip39.validate(List(11) { "abandon" })
    }

    @Test
    fun generatedPhrasesValidate() {
        repeat(20) {
            val words = bip39.generate()
            assertEquals(12, words.size)
            assertEquals(words, bip39.validate(words))
        }
    }

    @Test
    fun parseTakesTheFirstTwelveWordsOfAPaste() {
        assertEquals(abandonAbout, bip39.parse("  " + abandonAbout.joinToString("\n") + "  extra words"))
        assertNull(bip39.parse("abandon abandon"))
        assertNull(bip39.parse(List(12) { "abandon" }.joinToString(" ")))
        assertTrue(bip39.isWord("Zoo"))
    }

    /**
     * `generatedPhraseUsesWordlistEntries` (`EncryptionPhraseGeneratorTests.swift:15-26`), pinned to
     * the computed words of crypto spec §16.1 as the stronger vector.
     */
    @Test
    fun knownEntropyGivesTheLeisureClawVector() {
        val entropy = hexToBytes("7FA542109C33886D14C25E9103B8476A")
        assertEquals(
            "leisure claw loud debris decade custom fantasy envelope much build balcony stairs".split(" "),
            bip39.fromEntropy(entropy),
        )
    }

    /** `liveGenerationProducesTwelveUniqueOrValidWords` (`EncryptionPhraseGeneratorTests.swift:28-35`). */
    @Test
    fun liveGenerationHasAtLeastEightDistinctWords() {
        val words = bip39.generate()
        assertEquals(12, words.size)
        assertTrue(words.all { it.isNotEmpty() })
        assertTrue(words.toSet().size >= 8)
    }

    /** `parseNormalizesCaseAndExtraWhitespace` (`EncryptionPhraseParserTests.swift:15-20`). */
    @Test
    fun parseNormalisesCaseAndExtraWhitespace() {
        val phrase = "  Abandon   ABANDON  abandon abandon abandon abandon abandon abandon abandon abandon abandon about  "
        assertEquals(abandonAbout, bip39.parse(phrase))
    }

    /** Swift splits on `.whitespacesAndNewlines` (Unicode): a paste with no-break spaces or U+2028 still parses (crypto §17.1). */
    @Test
    fun parseSplitsOnUnicodeWhitespace() {
        assertEquals(abandonAbout, bip39.parse(abandonAbout.joinToString("\u00A0")))
        assertEquals(abandonAbout, bip39.parse(abandonAbout.joinToString("\u2028")))
        assertEquals(abandonAbout, bip39.parse(abandonAbout.joinToString("\u3000\t")))
        assertEquals(abandonAbout, bip39.parse(abandonAbout.joinToString("\u0085")))
    }

    /** `parseRejectsFewerThanTwelveWords`, `parseRejectsInvalidChecksum` (`EncryptionPhraseParserTests.swift:22-31`). */
    @Test
    fun parseRejectsShortPhrasesAndBadChecksums() {
        assertNull(bip39.parse("one two three"))
        assertNull(bip39.parse(List(12) { "abandon" }.joinToString(" ")))
    }

    /** `parseLenientDoesNotRequireChecksum` (`EncryptionPhraseParserTests.swift:33-40`). */
    @Test
    fun parseLenientDoesNotRequireChecksum() {
        val words = bip39.parseLenient("one two three four five six seven eight nine ten eleven twelve thirteen")
        assertEquals(12, words?.size)
        assertEquals("twelve", words?.last())
        assertNull(bip39.parseLenient("one two"))
        assertEquals(List(12) { "abandon" }, bip39.parseLenient(List(12) { "ABANDON" }.joinToString("\u00A0")))
    }

    @Test
    fun phraseErrorsNeverQuoteAWord() {
        val error = runCatching { bip39.validate(List(11) { "abandon" } + "hunter2") }.exceptionOrNull()
        assertTrue(error is Bip39.PhraseException.UnknownWord)
        assertTrue(!error!!.message!!.contains("hunter2"))
    }
}
