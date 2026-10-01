package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.crypto.CryptoFixtures.bytes
import de.corespace.shroud.core.crypto.CryptoFixtures.sequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety numbers (iOS `ios/shroudTests/IdentitySafetyNumberTests.swift`; crypto spec §17.3, §16.3;
 * contacts spec §4.9). The fixed vectors are printed by `gen_message_crypto_vectors.mjs` (Node's
 * OpenSSL SHA-256) next to this file and match crypto spec §16.3 and contacts spec §4.9.
 */
class IdentitySafetyNumberTest {
    private fun number(a: ByteArray, b: ByteArray): String {
        val left = IdentitySafetyNumber.displayString(a, b)
        assertEquals("symmetric", left, IdentitySafetyNumber.displayString(b, a))
        return left
    }

    /** `IdentitySafetyNumberTests.swift:7-16`. */
    @Test
    fun fingerprintIsSymmetric() {
        val a = TestIdentity.random().public
        val b = TestIdentity.random().public
        val left = IdentitySafetyNumber.displayString(a, b)
        val right = IdentitySafetyNumber.displayString(b, a)
        assertEquals(left, right)
        val groups = left.split(" ")
        assertEquals(12, groups.size)
        assertTrue(groups.all { it.length == 5 && it.all(Char::isDigit) })
        assertEquals(71, left.length)
    }

    /** `IdentitySafetyNumberTests.swift:18-26`. */
    @Test
    fun differentKeysProduceDifferentNumbers() {
        val local = TestIdentity.random().public
        val peerA = TestIdentity.random().public
        val peerB = TestIdentity.random().public
        assertNotEquals(IdentitySafetyNumber.displayString(local, peerA), IdentitySafetyNumber.displayString(local, peerB))
    }

    /** Crypto spec §16.3: the two fixed identities (`0x11 × 32`, `0x22 × 32`). */
    @Test
    fun theFixedIdentitiesMatchTheVector() {
        val alice = TestIdentity.filled(0x11).public
        val bob = TestIdentity.filled(0x22).public
        assertEquals("31988 96179 94514 40486 97680 44349 56357 86940 89163 43635 86683 49866", number(alice, bob))
    }

    /**
     * Crypto spec §16.3, risk R7: `0x80…` is negative as a Kotlin `Byte`. With a signed compare the
     * keys would hash in the other order and give another number.
     */
    @Test
    fun theKeyOrderIsUnsigned() {
        val low = sequence(0x01, 32)
        val high = sequence(0x80, 32)
        assertEquals("73973 08899 45243 61171 10525 32783 47853 25287 62674 77996 39692 99672", number(low, high))
        val signedOrder = Primitives.sha256(high + low)
        val unsignedOrder = Primitives.sha256(low + high)
        assertNotEquals(signedOrder.hex(), unsignedOrder.hex())
    }

    /** Contacts spec §4.9: the golden vectors cross-checked against the iOS source. */
    @Test
    fun theContactsVectorsMatch() {
        assertEquals("39936 00420 36095 80875 11472 14538 50394 55836 81423 99087 38599 17095", number(sequence(0x01, 32), sequence(0x21, 32)))
        assertEquals("98683 78184 46324 56350 82390 90107 00987 21869 25032 71750 76963 61577", number(bytes(0x00, 32), bytes(0xff, 32)))
        // RFC 7748 §6.1 public keys.
        val h1 = hexToBytes("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val h2 = hexToBytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertEquals("52249 84593 58473 87782 33310 55290 67694 46749 50759 35105 99615 07906", number(h1, h2))
        // A key with itself (equal keys: a ‖ b).
        assertEquals("29696 81578 32876 91411 15478 21467 89245 24174 87371 59194 49089 78176", number(sequence(0x01, 32), sequence(0x01, 32)))
    }
}
