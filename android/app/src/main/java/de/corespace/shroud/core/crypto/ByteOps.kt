package de.corespace.shroud.core.crypto

import java.security.MessageDigest
import java.util.Base64

/**
 * Standard Base64 (RFC 4648 §4: `+` and `/`, padded, no line breaks) — the only Base64 the
 * envelopes, ratchet messages, sealed names and server `ciphertext` fields use.
 *
 * Never `android.util.Base64`: JVM unit tests run with `isReturnDefaultValues = true`, where it
 * returns null (crypto spec §1.1).
 */
object B64 {
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    /** iOS `Data.base64EncodedString()`, web `bytesToB64` (`web/src/crypto/bytes.ts:15-19`). */
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /**
     * Swift `Data(base64Encoded:)` semantics (used by iOS for box fields, ratchet `dh`/`ct`,
     * sealed device names: `ios/shroud/Services/Crypto/MessageCrypto.swift:541-543`, `:609-610`,
     * `DoubleRatchet.swift:186-187`): the length must be a multiple of 4 (padding required),
     * no whitespace or line breaks, no URL-safe characters, no data after the padding. Null on
     * any of those — `java.util.Base64.getDecoder()` alone accepts unpadded input where Swift
     * does not. Unused low bits in the last group are not checked (neither CryptoKit's
     * Foundation nor the web's `atob` checks them).
     */
    fun decodeStrict(s: String): ByteArray? {
        if (s.length % 4 != 0) return null
        // The JDK decoder refuses every character outside the alphabet (whitespace, '-', '_')
        // and anything after the padding; the length check above adds the padding rule.
        return try {
            decoder.decode(s)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lower-case hex, two digits per byte (web `bytesToHex`, `web/src/crypto/bytes.ts:1-3`). */
fun ByteArray.hex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
    }
    return String(out)
}

/**
 * Hex → bytes. Accepts ASCII `0-9`, `a-f`, `A-F` only; throws [IllegalArgumentException] on an
 * odd length or any other character, whitespace included (stricter than the web's `hexToBytes`,
 * which trims and turns junk into zeros — `web/src/crypto/bytes.ts:5-13`). Meant for constants and
 * tests, never for wire input.
 */
fun hexToBytes(s: String): ByteArray {
    require(s.length % 2 == 0) { "hex string has an odd length" }
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        val hi = hexDigit(s[i * 2])
        val lo = hexDigit(s[i * 2 + 1])
        require(hi >= 0 && lo >= 0) { "not a hex string" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/** ASCII hex digit value, or -1. Not `Character.digit`, which also takes non-ASCII digits (`'٣'` → 3). */
private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** UTF-8 bytes of [s] (web `utf8`, `web/src/crypto/bytes.ts:46-48`). */
fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

/**
 * Constant-time equality (CryptoKit `isValidAuthenticationCode`, web `bytesEqual`,
 * `web/src/crypto/bytes.ts:39-44`). Different lengths are unequal; the length itself is not
 * secret.
 */
fun ctEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

/**
 * Swift `Data.lexicographicallyPrecedes`: bytes compared **unsigned** (0…255), a proper prefix
 * comes first, equal arrays do not precede each other. Kotlin `Byte` is signed, so a plain
 * compare would put `0x80` before `0x7f` and silently break the ratchet root salt
 * (`ios/shroud/Services/Crypto/DoubleRatchet.swift:289-297`) and safety numbers
 * (`IdentitySafetyNumber.swift:13`) for about half of all key pairs (crypto spec §1.1).
 */
fun lexLess(a: ByteArray, b: ByteArray): Boolean {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a[i].toInt() and 0xFF
        val y = b[i].toInt() and 0xFF
        if (x != y) return x < y
    }
    return a.size < b.size
}

/**
 * The two arrays concatenated in [lexLess] order; `a ‖ b` when they are equal.
 * iOS `DoubleRatchet.sortedConcat` (`ios/shroud/Services/Crypto/DoubleRatchet.swift:289-297`),
 * web `sortedConcat` (`web/src/crypto/bytes.ts:55-63`).
 */
fun sortedConcat(a: ByteArray, b: ByteArray): ByteArray = if (lexLess(b, a)) b + a else a + b
