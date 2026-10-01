package de.corespace.shroud.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §1.7.1 and conflict C9: value equality, cached hash, no aliasing through [Bytes.of]. */
class BytesTest {
    @Test
    fun equalContentIsEqualWithTheSameHash() {
        val a = Bytes.of(byteArrayOf(1, 2, 3))
        val b = Bytes.adopt(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(setOf(a), setOf(b))
    }

    @Test
    fun differentContentOrLengthIsNotEqual() {
        val a = Bytes.of(byteArrayOf(1, 2, 3))
        assertNotEquals(a, Bytes.of(byteArrayOf(1, 2, 4)))
        assertNotEquals(a, Bytes.of(byteArrayOf(1, 2)))
        assertNotEquals(a, Bytes.of(byteArrayOf(1, 2, 3, 0)))
        assertFalse(a.equals(byteArrayOf(1, 2, 3)))
        assertFalse(a.equals(null))
    }

    @Test
    fun emptyBytesAreEqual() {
        val empty = Bytes.of(ByteArray(0))
        assertEquals(0, empty.size)
        assertEquals(empty, Bytes.adopt(ByteArray(0)))
        assertEquals(empty.hashCode(), Bytes.of(ByteArray(0)).hashCode())
    }

    @Test
    fun ofCopiesSoLaterChangesDoNotLeakIn() {
        val source = byteArrayOf(1, 2, 3)
        val bytes = Bytes.of(source)
        val hash = bytes.hashCode()
        source[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
        assertEquals(hash, bytes.hashCode())
    }

    @Test
    fun adoptTakesTheArrayWithoutCopying() {
        val source = byteArrayOf(1, 2, 3)
        val bytes = Bytes.adopt(source)
        source[0] = 9
        assertArrayEquals(byteArrayOf(9, 2, 3), bytes.toByteArray())
    }

    @Test
    fun toByteArrayHandsOutACopy() {
        val bytes = Bytes.of(byteArrayOf(1, 2, 3))
        val first = bytes.toByteArray()
        first[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
        assertNotSame(bytes.toByteArray(), bytes.toByteArray())
    }

    @Test
    fun inputStreamReadsTheContent() {
        val bytes = Bytes.of(byteArrayOf(5, 6, 7))
        assertArrayEquals(byteArrayOf(5, 6, 7), bytes.inputStream().readBytes())
        assertEquals(3, bytes.size)
    }

    @Test
    fun hashIsStableAcrossCalls() {
        val bytes = Bytes.of(ByteArray(64) { it.toByte() })
        assertEquals(bytes.hashCode(), bytes.hashCode())
        assertEquals(ByteArray(64) { it.toByte() }.contentHashCode(), bytes.hashCode())
    }

    @Test
    fun contentWhoseHashIsZeroStillCompares() {
        // contentHashCode of [0] is 31 * 1 + 0 = 31; [-31] gives 31 * 1 - 31 = 0.
        val zero = Bytes.of(byteArrayOf(-31))
        assertEquals(0, zero.hashCode())
        assertEquals(0, zero.hashCode())
        assertEquals(zero, Bytes.of(byteArrayOf(-31)))
        assertNotEquals(zero, Bytes.of(byteArrayOf(-30)))
    }

    @Test
    fun toStringNeverPrintsTheContent() {
        val text = Bytes.of(byteArrayOf(0x41, 0x42, 0x43)).toString()
        assertEquals("Bytes(size=3)", text)
        assertTrue(!text.contains("65") && !text.contains("ABC"))
    }
}
