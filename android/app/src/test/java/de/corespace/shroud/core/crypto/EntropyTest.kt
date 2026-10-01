package de.corespace.shroud.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Injected randomness (crypto spec §1.3): the system source, and the scripted one golden vectors use. */
class EntropyTest {
    @Test
    fun systemEntropyReturnsTheRequestedSize() {
        for (size in listOf(0, 1, 12, 32, 1000)) assertEquals(size, SystemEntropy.bytes(size).size)
        assertThrows(IllegalArgumentException::class.java) { SystemEntropy.bytes(-1) }
    }

    @Test
    fun systemEntropyDiffersBetweenDraws() {
        val a = SystemEntropy.bytes(32)
        val b = SystemEntropy.bytes(32)
        assertFalse(a.contentEquals(b))
        assertFalse(a.contentEquals(ByteArray(32)))
    }

    @Test
    fun scriptedEntropyHandsOutChunksInOrder() {
        val entropy = ScriptedEntropy(ByteArray(32) { 0x77 }, ByteArray(12) { 0x88.toByte() })
        assertEquals(2, entropy.remaining)
        assertArrayEquals(ByteArray(32) { 0x77 }, entropy.bytes(32))
        assertArrayEquals(ByteArray(12) { 0x88.toByte() }, entropy.bytes(12))
        assertEquals(0, entropy.remaining)
    }

    @Test
    fun scriptedEntropyFailsLoudlyOnAWrongDrawOrder() {
        // A vector built on the wrong draw order must fail, never fall back to real randomness.
        val entropy = ScriptedEntropy(ByteArray(32), ByteArray(12))
        assertThrows(IllegalStateException::class.java) { entropy.bytes(12) }
        val exhausted = ScriptedEntropy(ByteArray(12))
        exhausted.bytes(12)
        assertThrows(IllegalStateException::class.java) { exhausted.bytes(12) }
    }

    @Test
    fun scriptedEntropyCopiesItsChunks() {
        val chunk = ByteArray(12) { 1 }
        val entropy = ScriptedEntropy(chunk)
        chunk.fill(9)
        assertArrayEquals(ByteArray(12) { 1 }, entropy.bytes(12))
    }
}
