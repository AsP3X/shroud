package de.corespace.shroud.core.crypto

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer

/**
 * BIP39 English, 12 words (128-bit entropy + 4-bit checksum) — the encryption phrase.
 * Byte-compatible with `BIP39Seed.swift` and `web/src/crypto/bip39.ts`.
 */
class Bip39(private val wordlist: List<String>) {
    init {
        require(wordlist.size == 2048) { "BIP39 wordlist must hold 2048 words" }
    }

    private val index: Map<String, Int> = wordlist.withIndex().associate { (i, w) -> w to i }

    sealed class PhraseException(message: String) : Exception(message) {
        class InvalidWordCount : PhraseException("Enter all 12 words of your encryption phrase.")
        class UnknownWord(val word: String) : PhraseException("Unknown word: $word")
        class InvalidChecksum : PhraseException("That encryption phrase checksum is invalid.")
    }

    fun generate(random: SecureRandom = SecureRandom()): List<String> =
        fromEntropy(ByteArray(16).also(random::nextBytes))

    fun fromEntropy(entropy: ByteArray): List<String> {
        require(entropy.size == 16)
        val bits = bits(entropy) + checksumBits(entropy)
        return List(WORD_COUNT) { i ->
            var value = 0
            for (bit in 0 until 11) if (bits[i * 11 + bit]) value = value or (1 shl (10 - bit))
            wordlist[value]
        }
    }

    /** Trims and lowercases, then checks count, words and checksum. Returns the normalised words. */
    fun validate(words: List<String>): List<String> {
        val normalized = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalized.size != WORD_COUNT) throw PhraseException.InvalidWordCount()
        val bits = ArrayList<Boolean>(WORD_COUNT * 11)
        for (word in normalized) {
            val i = index[word] ?: throw PhraseException.UnknownWord(word)
            for (shift in 10 downTo 0) bits += (i shr shift) and 1 == 1
        }
        val entropy = ByteArray(16)
        for (byte in 0 until 16) {
            var v = 0
            for (bit in 0 until 8) if (bits[byte * 8 + bit]) v = v or (1 shl (7 - bit))
            entropy[byte] = v.toByte()
        }
        if (bits.subList(128, 132) != checksumBits(entropy)) throw PhraseException.InvalidChecksum()
        return normalized
    }

    /** PBKDF2-HMAC-SHA512(NFKD(words), "mnemonic", 2048) → 64 bytes. */
    fun seed(words: List<String>, passphrase: String = ""): ByteArray {
        val mnemonic = Normalizer.normalize(validate(words).joinToString(" "), Normalizer.Form.NFKD)
        val salt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD)
        val generator = PKCS5S2ParametersGenerator(SHA512Digest())
        generator.init(mnemonic.toByteArray(Charsets.UTF_8), salt.toByteArray(Charsets.UTF_8), 2048)
        return (generator.generateDerivedParameters(512) as KeyParameter).key
    }

    fun isWord(word: String) = index.containsKey(word.trim().lowercase())

    /**
     * A pasted phrase (`EncryptionPhraseParser.swift`): split on whitespace, first 12 words,
     * validated. Null when it is not a valid phrase.
     */
    fun parse(text: String): List<String>? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(WORD_COUNT)
        return runCatching { validate(words) }.getOrNull()
    }

    private fun bits(data: ByteArray): List<Boolean> =
        data.flatMap { b -> (7 downTo 0).map { (b.toInt() shr it) and 1 == 1 } }

    private fun checksumBits(entropy: ByteArray): List<Boolean> {
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        return (0 until 4).map { (hash[0].toInt() shr (7 - it)) and 1 == 1 }
    }

    companion object {
        const val WORD_COUNT = 12
    }
}
