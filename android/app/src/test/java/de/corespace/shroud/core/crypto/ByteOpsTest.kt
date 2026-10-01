package de.corespace.shroud.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte helpers of crypto spec §1.1. The two traps this pins: Kotlin's signed `Byte` (the ratchet
 * root salt and safety numbers compare unsigned, `ios/shroud/Services/Crypto/DoubleRatchet.swift:289-297`)
 * and `java.util.Base64`'s leniency next to Swift's `Data(base64Encoded:)`.
 */
class ByteOpsTest {
    // ---- Base64 ----

    @Test
    fun encodeIsStandardPaddedBase64WithoutLineBreaks() {
        assertEquals("", B64.encode(ByteArray(0)))
        assertEquals("AQ==", B64.encode(byteArrayOf(1)))
        assertEquals("AQI=", B64.encode(byteArrayOf(1, 2)))
        assertEquals("AQID", B64.encode(byteArrayOf(1, 2, 3)))
        // '+' and '/' (not the URL-safe alphabet): alicePub of crypto spec §16.3.
        assertEquals("e06Qm75//kTEZaIgA31gjuNYl9Me+XLwf3SJLLD3PxM=", B64.encode(hexToBytes(ALICE_PUB)))
        val long = B64.encode(ByteArray(200) { it.toByte() })
        assertFalse(long.contains('\n') || long.contains('\r'))
    }

    @Test
    fun decodeStrictRoundTripsValidInput() {
        assertArrayEquals(ByteArray(0), B64.decodeStrict(""))
        assertArrayEquals(byteArrayOf(1), B64.decodeStrict("AQ=="))
        assertArrayEquals(byteArrayOf(1, 2), B64.decodeStrict("AQI="))
        assertArrayEquals(byteArrayOf(1, 2, 3), B64.decodeStrict("AQID"))
        assertArrayEquals(hexToBytes(ALICE_PUB), B64.decodeStrict("e06Qm75//kTEZaIgA31gjuNYl9Me+XLwf3SJLLD3PxM="))
        val bytes = ByteArray(257) { (it * 7).toByte() }
        assertArrayEquals(bytes, B64.decodeStrict(B64.encode(bytes)))
    }

    @Test
    fun decodeStrictRejectsUnpaddedInput() {
        // java.util.Base64.getDecoder() accepts these; Swift's Data(base64Encoded:) does not.
        assertNotNull(java.util.Base64.getDecoder().decode("AQ"))
        assertNull(B64.decodeStrict("AQ"))
        assertNull(B64.decodeStrict("AQI"))
        assertNull(B64.decodeStrict("e06Qm75//kTEZaIgA31gjuNYl9Me+XLwf3SJLLD3PxM"))
    }

    @Test
    fun decodeStrictRejectsWhitespaceAndLineBreaks() {
        assertNull(B64.decodeStrict(" AQ=="))
        assertNull(B64.decodeStrict("AQ== "))
        assertNull(B64.decodeStrict("AQ\n=="))
        assertNull(B64.decodeStrict("AQID\r\nAQID"))
        assertNull(B64.decodeStrict("AQ\t=="))
        // Length is a multiple of 4 here, so only the character rule refuses it.
        assertNull(B64.decodeStrict("AQ I"))
    }

    @Test
    fun decodeStrictRejectsOtherMalformedInput() {
        assertNull(B64.decodeStrict("AQ-_")) // URL-safe alphabet
        assertNull(B64.decodeStrict("AQ==AQ==")) // data after the padding
        assertNull(B64.decodeStrict("A===")) // one character cannot carry a byte
        assertNull(B64.decodeStrict("===="))
        assertNull(B64.decodeStrict("AQ=")) // length % 4 != 0
        assertNull(B64.decodeStrict("not base64"))
        assertNull(B64.decodeStrict("AQé="))
    }

    // ---- hex ----

    @Test
    fun hexIsLowerCaseTwoDigitsPerByte() {
        assertEquals("", ByteArray(0).hex())
        assertEquals("00017f80ff", byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte()).hex())
        assertEquals(ALICE_PUB, hexToBytes(ALICE_PUB).hex())
    }

    @Test
    fun hexToBytesAcceptsBothCasesAndRoundTrips() {
        assertArrayEquals(byteArrayOf(0xab.toByte(), 0xcd.toByte(), 0xef.toByte()), hexToBytes("ABcdEF"))
        assertArrayEquals(ByteArray(0), hexToBytes(""))
        val bytes = ByteArray(256) { it.toByte() }
        assertArrayEquals(bytes, hexToBytes(bytes.hex()))
    }

    @Test
    fun hexToBytesRejectsOddLengthAndNonHex() {
        assertThrows(IllegalArgumentException::class.java) { hexToBytes("abc") }
        assertThrows(IllegalArgumentException::class.java) { hexToBytes("zz") }
        assertThrows(IllegalArgumentException::class.java) { hexToBytes("0x00") }
        assertThrows(IllegalArgumentException::class.java) { hexToBytes(" 0a") }
        // A non-ASCII digit (Arabic-Indic three) is not a hex digit.
        assertThrows(IllegalArgumentException::class.java) { hexToBytes("٣٣") }
    }

    @Test
    fun utf8EncodesUtf8() {
        assertArrayEquals(byteArrayOf(0x61, 0xe2.toByte(), 0x80.toByte(), 0x94.toByte()), utf8("a—"))
        assertArrayEquals(hexToBytes("f09f9492"), utf8("🔒"))
    }

    // ---- constant-time equality ----

    @Test
    fun ctEqualsComparesContentAndLength() {
        assertTrue(ctEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertTrue(ctEquals(ByteArray(0), ByteArray(0)))
        assertFalse(ctEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(ctEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(ctEquals(byteArrayOf(0), ByteArray(0)))
    }

    // ---- unsigned order ----

    @Test
    fun lexLessComparesBytesUnsigned() {
        val low = byteArrayOf(0x7f)
        val high = byteArrayOf(0x80.toByte())
        // A signed compare says 0x80 (-128) < 0x7f; Swift and the web say 0x7f < 0x80.
        assertTrue(high[0] < low[0])
        assertTrue(lexLess(low, high))
        assertFalse(lexLess(high, low))
        assertTrue(lexLess(byteArrayOf(0x00), byteArrayOf(0xff.toByte())))
        assertTrue(lexLess(byteArrayOf(1, 0x7f), byteArrayOf(1, 0x80.toByte())))
        assertFalse(lexLess(byteArrayOf(2, 0), byteArrayOf(1, 0xff.toByte())))
    }

    @Test
    fun lexLessPutsAProperPrefixFirstAndIsIrreflexive() {
        assertTrue(lexLess(byteArrayOf(1), byteArrayOf(1, 0)))
        assertFalse(lexLess(byteArrayOf(1, 0), byteArrayOf(1)))
        assertTrue(lexLess(ByteArray(0), byteArrayOf(0)))
        assertFalse(lexLess(ByteArray(0), ByteArray(0)))
        val same = byteArrayOf(5, 0x90.toByte())
        assertFalse(lexLess(same, same.copyOf()))
    }

    @Test
    fun sortedConcatOrdersUnsigned() {
        val alice = hexToBytes(ALICE_PUB)
        val bob = hexToBytes(BOB_PUB)
        // The ratchet root salt of crypto spec §16.3: bobPub ‖ alicePub, because 0x0f < 0x7b.
        assertArrayEquals(bob + alice, sortedConcat(alice, bob))
        assertArrayEquals(bob + alice, sortedConcat(bob, alice))

        val low = byteArrayOf(0x7f, 1)
        val high = byteArrayOf(0x80.toByte(), 0)
        assertArrayEquals(low + high, sortedConcat(high, low))
        assertArrayEquals(low + high, sortedConcat(low, high))
    }

    @Test
    fun sortedConcatOfEqualArraysIsFirstThenSecond() {
        val a = byteArrayOf(9, 9)
        val b = byteArrayOf(9, 9)
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), sortedConcat(a, b))
    }

    private companion object {
        // crypto spec §16.3: X25519 publics of 0x11 × 32 and 0x22 × 32.
        const val ALICE_PUB = "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"
        const val BOB_PUB = "0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20"
    }
}
