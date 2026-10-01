package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.crypto.CryptoFixtures.bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Media blob crypto (iOS `ios/shroud/Services/Crypto/MediaCrypto.swift:40-53`, web `aes.ts:29-33`;
 * crypto spec §13.1, §16.3). The vector (key `0x01 × 32`, nonce `0x02 × 12`, "media bytes") is
 * printed by `gen_message_crypto_vectors.mjs` next to this file; the one-shot and streaming forms
 * must agree with it and with each other, byte for byte.
 */
class MediaCryptoTest {
    private val key = bytes(0x01, 32)
    private val vector = "AgICAgICAgICAgICarOtICt3o4Snqc8K7wU25kC+cfCidpttzp1L"

    private fun scripted() = ScriptedEntropy(bytes(0x01, 32), bytes(0x02, 12))

    private fun openStream(sealed: ByteArray, key: ByteArray, input: InputStream = ByteArrayInputStream(sealed)): ByteArray =
        ByteArrayOutputStream().also { MediaCrypto.openStream(input, it, key) }.toByteArray()

    private fun sealStream(plaintext: ByteArray, entropy: Entropy, input: InputStream = ByteArrayInputStream(plaintext)): Pair<ByteArray, ByteArray> {
        val out = ByteArrayOutputStream()
        val key = MediaCrypto.sealStream(input, out, entropy)
        return key to out.toByteArray()
    }

    @Test
    fun sealFileMatchesTheVector() {
        val entropy = scripted()
        val sealed = MediaCrypto.sealFile(utf8("media bytes"), entropy)
        assertEquals(0, entropy.remaining)
        assertArrayEquals(key, sealed.key)
        assertEquals(vector, B64.encode(sealed.sealed))
    }

    @Test
    fun sealStreamMatchesTheOneShotForm() {
        val (streamKey, sealed) = sealStream(utf8("media bytes"), scripted())
        assertArrayEquals(key, streamKey)
        assertEquals(vector, B64.encode(sealed))
    }

    @Test
    fun openFileAndOpenStreamBothReadTheVector() {
        val sealed = B64.decodeStrict(vector)!!
        assertEquals("media bytes", String(MediaCrypto.openFile(sealed, key), Charsets.UTF_8))
        assertEquals("media bytes", String(openStream(sealed, key), Charsets.UTF_8))
    }

    /** Several MiB through the 1 MiB buffers, read back in small irregular chunks: same bytes in every combination. */
    @Test
    fun largeBlobsAgreeAcrossForms() {
        val plaintext = SystemEntropy.bytes(3 * 1024 * 1024 + 77)
        val entropyBytes = SystemEntropy.bytes(32) to SystemEntropy.bytes(12)
        val oneShot = MediaCrypto.sealFile(plaintext, ScriptedEntropy(entropyBytes.first, entropyBytes.second))
        val (streamKey, streamed) = sealStream(plaintext, ScriptedEntropy(entropyBytes.first, entropyBytes.second), TrickleInputStream(plaintext))
        assertArrayEquals(oneShot.key, streamKey)
        assertArrayEquals(oneShot.sealed, streamed)
        assertEquals(plaintext.size + MediaCrypto.SEALED_OVERHEAD_BYTES, streamed.size)
        assertArrayEquals(plaintext, MediaCrypto.openFile(streamed, streamKey))
        assertArrayEquals(plaintext, openStream(oneShot.sealed, oneShot.key, TrickleInputStream(oneShot.sealed)))
    }

    /** `MediaCrypto.swift:40-46`: the empty file seals to nonce ‖ tag and opens back. */
    @Test
    fun anEmptyFileRoundTrips() {
        val sealed = MediaCrypto.sealFile(ByteArray(0))
        assertEquals(28, sealed.sealed.size)
        assertEquals(0, MediaCrypto.openFile(sealed.sealed, sealed.key).size)
        val (streamKey, streamed) = sealStream(ByteArray(0), SystemEntropy)
        assertEquals(28, streamed.size)
        assertEquals(0, openStream(streamed, streamKey).size)
    }

    /** `MediaCrypto.swift:48-53`: a key that is not 32 bytes is `invalidKey`; anything that does not open is `decryptFailed`. */
    @Test
    fun badKeysAndBlobsFail() {
        val sealed = B64.decodeStrict(vector)!!
        assertThrows(MediaCrypto.MediaError.InvalidKey::class.java) { MediaCrypto.openFile(sealed, bytes(0x01, 16)) }
        assertThrows(MediaCrypto.MediaError.InvalidKey::class.java) { openStream(sealed, bytes(0x01, 31)) }
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { MediaCrypto.openFile(sealed, bytes(0x09, 32)) }
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { openStream(sealed, bytes(0x09, 32)) }

        for (index in listOf(0, 12, sealed.size - 1)) {
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { MediaCrypto.openFile(tampered, key) }
            assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { openStream(tampered, key) }
        }
        // Truncated: shorter than nonce + tag, a missing tag byte, only part of the nonce.
        for (size in listOf(0, 5, 27, sealed.size - 1)) {
            val truncated = sealed.copyOf(size)
            assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { MediaCrypto.openFile(truncated, key) }
            assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { openStream(truncated, key) }
        }
    }

    /** I/O errors are not crypto verdicts: they propagate as they are, and a failed seal wipes its key. */
    @Test
    fun ioErrorsPropagate() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("disk gone")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("disk gone")
        }
        val drawn = bytes(0x01, 32)
        assertThrows(IOException::class.java) {
            MediaCrypto.sealStream(failing, ByteArrayOutputStream(), Entropy { count -> if (count == 32) drawn else bytes(0x02, count) })
        }
        assertArrayEquals(ByteArray(32), drawn)
        val brokenOutput = object : OutputStream() {
            override fun write(b: Int) = throw IOException("full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("full")
        }
        assertThrows(IOException::class.java) { MediaCrypto.openStream(ByteArrayInputStream(B64.decodeStrict(vector)!!), brokenOutput, key) }
    }

    @Test
    fun theLimitsMatchIos() {
        assertEquals(2L * 1024 * 1024 * 1024, MediaCrypto.MAX_SEALED_BYTES) // VideoMedia.swift:121, routes/media.rs:33
        assertEquals(2L * 1024 * 1024 * 1024 - 1024 * 1024, MediaCrypto.MAX_PLAINTEXT_BYTES) // VideoMedia.swift:119
        assertEquals(6 * 1024, MediaCrypto.MAX_ENVELOPE_PREVIEW_BYTES) // MediaCrypto.swift:146
    }

    /** Hands out at most 1…4093 bytes per read, so the stream code sees block-misaligned chunks. */
    private class TrickleInputStream(bytes: ByteArray) : FilterInputStream(ByteArrayInputStream(bytes)) {
        private var step = 0

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            step = (step * 31 + 7) % 4093
            return super.read(b, off, minOf(len, step + 1))
        }
    }
}
