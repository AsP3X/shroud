package de.corespace.shroud.core.media

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.crypto.ScriptedEntropy
import de.corespace.shroud.core.crypto.hex
import de.corespace.shroud.core.crypto.hexToBytes
import de.corespace.shroud.core.crypto.utf8
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random

/**
 * The hardware-CTR + GHASH streaming GCM of media transfers (media-voice-links §1.2, D2, risk R1:
 * "AES/CTR (hardware) + BC GHASH, only with byte-identical tests"; crypto §13.1, §16.3). It must
 * produce exactly the bytes of iOS `MediaCrypto.sealFile` (`ios/shroud/Services/Crypto/MediaCrypto.swift:40-53`),
 * the web's `aes.ts:29-46`, the JCA one-shot (`Primitives.aesGcmSeal`) and BouncyCastle's GCM
 * (`MediaCrypto.sealStream`), and open what they seal.
 */
class StreamingGcmTest {
    private val key = ByteArray(32) { 0x01 }
    private val nonce = ByteArray(12) { 0x02 }

    /** crypto §16.3, copied from `MediaCryptoTest` (made with the web client's own libraries). */
    private val vector = "AgICAgICAgICAgICarOtICt3o4Snqc8K7wU25kC+cfCidpttzp1L"

    /** The byte-identity sizes of media-voice-links §1.2, plus whole and broken GHASH blocks around 1 MiB chunks. */
    private val sizes = listOf(0, 1, 15, 16, 17, 65_535, 65_536, 1024 * 1024 - 1, 1024 * 1024, 1024 * 1024 + 1, 16 * 1024 * 1024 + 1)

    private fun seal(plaintext: ByteArray, input: InputStream = ByteArrayInputStream(plaintext), k: ByteArray = key, n: ByteArray = nonce): ByteArray =
        ByteArrayOutputStream().also { StreamingGcm.seal(input, it, k, n) }.toByteArray()

    private fun open(sealed: ByteArray, input: InputStream = ByteArrayInputStream(sealed), k: ByteArray = key): ByteArray =
        ByteArrayOutputStream().also { StreamingGcm.open(input, it, k) }.toByteArray()

    @Test
    fun sealsTheCrossClientVector() {
        assertEquals(vector, B64.encode(seal(utf8("media bytes"))))
        assertArrayEquals(utf8("media bytes"), open(B64.decodeStrict(vector)!!))
    }

    @Test
    fun nistGcmTestCases13To15() {
        // McGrew & Viega, "The Galois/Counter Mode of Operation", AES-256 test cases 13, 14 and 15
        // (96-bit IV, no AAD); checked against the JDK's GCM when copied.
        val zeroKey = ByteArray(32)
        val zeroIv = ByteArray(12)
        assertEquals("000000000000000000000000" + "530f8afbc74536b9a963b4f1c4cb738b", seal(ByteArray(0), k = zeroKey, n = zeroIv).hex())
        assertEquals(
            "000000000000000000000000" + "cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919",
            seal(ByteArray(16), k = zeroKey, n = zeroIv).hex(),
        )
        val k15 = hexToBytes("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val iv15 = hexToBytes("cafebabefacedbaddecaf888")
        val p15 = hexToBytes(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255",
        )
        val c15 = "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad"
        val t15 = "b094dac5d93471bdec1a502270e3cc6c"
        assertEquals("cafebabefacedbaddecaf888" + c15 + t15, seal(p15, k = k15, n = iv15).hex())
        assertArrayEquals(p15, open(hexToBytes("cafebabefacedbaddecaf888" + c15 + t15), k = k15))
    }

    @Test
    fun byteIdenticalToTheJcaOneShotAndBouncyCastleStreaming() {
        for (size in sizes) {
            val plaintext = Random(size).nextBytes(size)
            val ours = seal(plaintext)
            assertArrayEquals("JCA, size $size", Primitives.aesGcmSeal(key, nonce, plaintext), ours)
            val bc = ByteArrayOutputStream().also { MediaCrypto.sealStream(ByteArrayInputStream(plaintext), it, ScriptedEntropy(key, nonce)) }
            assertArrayEquals("BouncyCastle, size $size", bc.toByteArray(), ours)
        }
    }

    @Test
    fun anyChunkingOfTheInputGivesTheSameBytes() {
        val plaintext = Random(3).nextBytes(3 * 1024 * 1024 + 77)
        val expected = Primitives.aesGcmSeal(key, nonce, plaintext)
        for (seed in 1..3) {
            assertArrayEquals("seed $seed", expected, seal(plaintext, Trickle(plaintext, Random(seed))))
            assertArrayEquals("seed $seed", plaintext, open(expected, Trickle(expected, Random(seed))))
        }
    }

    @Test
    fun opensWhatEveryOtherFormSealed() {
        for (size in sizes) {
            val plaintext = Random(size + 1).nextBytes(size)
            assertArrayEquals("size $size", plaintext, open(Primitives.aesGcmSeal(key, nonce, plaintext)))
            assertArrayEquals("size $size", plaintext, MediaCrypto.openFile(seal(plaintext), key))
        }
    }

    @Test
    fun aFlippedBitAnywhereFailsTheTag() {
        val sealed = seal(Random(9).nextBytes(1024 * 1024 + 40))
        for (position in listOf(0, 11, 12, 13, 600_000, sealed.size - 17, sealed.size - 16, sealed.size - 1)) {
            val tampered = sealed.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() }
            assertThrows("byte $position", MediaCrypto.MediaError.DecryptFailed::class.java) { open(tampered) }
        }
    }

    @Test
    fun truncatedAppendedOrShortBlobsFail() {
        val sealed = seal(Random(10).nextBytes(70_000))
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(sealed.copyOf(sealed.size - 1)) }
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(sealed.copyOf(sealed.size - 16)) }
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(sealed + byteArrayOf(0)) }
        for (length in listOf(0, 5, 12, 27)) {
            assertThrows("$length bytes", MediaCrypto.MediaError.DecryptFailed::class.java) { open(sealed.copyOf(length)) }
        }
        // 28 bytes is the empty plaintext's blob.
        assertEquals(0, open(seal(ByteArray(0))).size)
    }

    @Test
    fun aWrongKeyFailsAndABadKeySizeIsInvalid() {
        val sealed = seal(Random(11).nextBytes(2 * 1024 * 1024))
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(sealed, k = ByteArray(32) { 0x03 }) }
        assertThrows(MediaCrypto.MediaError.InvalidKey::class.java) { open(sealed, k = ByteArray(31)) }
        assertThrows(MediaCrypto.MediaError.InvalidKey::class.java) { open(sealed, k = ByteArray(16)) }
    }

    @Test
    fun theCounterLimitIsBelowInc32Wrapping() {
        // Keystream blocks use counters 2 … 2³² − 1; the JCA's 128-bit CTR equals GCM's inc32 there.
        assertEquals((4_294_967_295L - 1) * 16, StreamingGcm.MAX_PLAINTEXT_BYTES)
        assertTrue(StreamingGcm.MAX_PLAINTEXT_BYTES > MediaCrypto.MAX_SEALED_BYTES)
    }

    /** Hands out [data] in random short reads (1 … 70 000 bytes), as sockets and pipes do. */
    private class Trickle(private val data: ByteArray, private val random: Random) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position >= data.size) -1 else data[position++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= data.size) return -1
            val count = minOf(len, data.size - position, 1 + random.nextInt(70_000))
            data.copyInto(b, off, position, position + count)
            position += count
            return count
        }
    }
}
