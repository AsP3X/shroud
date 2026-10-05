package de.corespace.shroud.core.media.files

import de.corespace.shroud.core.crypto.MediaCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * The SHRF1 blob of docs/file-sharing.md §3 against the vectors `node scripts/gen_file_vectors.mjs`
 * prints (§9, Node's own OpenSSL — independent of the JCA): what Android uploads opens on iOS and
 * the web, and what they upload opens here. Then every refusal the format promises.
 */
class FileBlobTest {
    private val key = ByteArray(32) { it.toByte() } // 00 01 … 1f
    private val prefix = byteArrayOf(0xa0.toByte(), 0xa1.toByte(), 0xa2.toByte(), 0xa3.toByte(), 0xa4.toByte(), 0xa5.toByte(), 0xa6.toByte())

    private fun pattern(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun seal(plain: ByteArray, k: ByteArray = key, p: ByteArray = prefix): ByteArray =
        ByteArrayOutputStream().also { Shrf1.seal(plain.inputStream(), plain.size.toLong(), it, k, p) }.toByteArray()

    private fun open(blob: ByteArray, size: Long, k: ByteArray = key): ByteArray =
        ByteArrayOutputStream().also { Shrf1.open(blob.inputStream(), size, it, k) }.toByteArray()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    // ---- §9 vectors ----

    @Test
    fun helloIsTheCrossClientVector() {
        val blob = seal("hello".toByteArray(Charsets.UTF_8))
        assertEquals(37, blob.size)
        assertEquals("5348524631a0a1a2a3a4a5a6000100001f042572d7a195d96a199b78b66a618521c6b35bc9", hex(blob))
        assertEquals("hello", String(open(blob, 5), Charsets.UTF_8))
    }

    @Test
    fun patternBlobsAreTheCrossClientVectors() {
        val rows = listOf(
            Triple(65_536, 65_568, "440b505128af2e20c12d50b1009d4113697e1ecb8b9791171608c1e3fc83d74e"),
            Triple(65_537, 65_585, "6caead1d0580c871ba5d374b0ba5ff1482ebaf769370a637c6597b832d5f3b08"),
            Triple(200_000, 200_080, "b59a0b0206599c2fb139a31cc5a82d0feb6e8e6a715f5e4dbc03081275f924a5"),
        )
        for ((n, sealedLength, digest) in rows) {
            val blob = seal(pattern(n))
            assertEquals("pattern($n)", sealedLength, blob.size)
            assertEquals("pattern($n)", sealedLength.toLong(), Shrf1.sealedSize(n.toLong()))
            assertEquals("pattern($n)", digest, sha256(blob))
            assertArrayEquals("pattern($n)", pattern(n), open(blob, n.toLong()))
        }
    }

    @Test
    fun sealedSizeIsTheSpecsFormula() {
        assertEquals(32L, Shrf1.sealedSize(0)) // one empty last segment
        assertEquals(37L, Shrf1.sealedSize(5))
        assertEquals(16L + 65_536 + 16, Shrf1.sealedSize(65_536))
        assertEquals(16L + 65_537 + 32, Shrf1.sealedSize(65_537))
        val max = FileLimits.MAX_PLAINTEXT_BYTES
        assertEquals(16 + max + 16 * ((max + 65_535) / 65_536), Shrf1.sealedSize(max))
        // 2 GiB − 1 MiB of plaintext stays under the server's 2 GiB.
        assertTrue(Shrf1.sealedSize(max) < MediaCrypto.MAX_SEALED_BYTES)
    }

    // ---- Round trips ----

    @Test
    fun anySizeRoundTripsThroughTrickleStreams() {
        for (n in listOf(0, 1, 16, 65_535, 65_536, 65_537, 131_072, 300_001)) {
            val plain = Random(n).nextBytes(n)
            val out = ByteArrayOutputStream()
            Shrf1.seal(Trickle(plain.inputStream()), n.toLong(), out, key, prefix)
            val blob = out.toByteArray()
            assertEquals("n=$n", Shrf1.sealedSize(n.toLong()), blob.size.toLong())
            val opened = ByteArrayOutputStream()
            Shrf1.open(Trickle(blob.inputStream()), n.toLong(), opened, key)
            assertArrayEquals("n=$n", plain, opened.toByteArray())
        }
    }

    @Test
    fun theSameKeyPrefixAndBytesAlwaysSealTheSame() {
        val plain = pattern(150_000)
        assertArrayEquals(seal(plain), seal(plain))
    }

    @Test
    fun aSourceThatIsNotTheDeclaredSizeIsRefused() {
        assertThrows(IOException::class.java) { Shrf1.seal(pattern(10).inputStream(), 11, ByteArrayOutputStream(), key, prefix) }
        assertThrows(IOException::class.java) { Shrf1.seal(pattern(12).inputStream(), 11, ByteArrayOutputStream(), key, prefix) }
        assertThrows(IOException::class.java) { Shrf1.seal(pattern(65_537).inputStream(), 65_536, ByteArrayOutputStream(), key, prefix) }
    }

    // ---- Refusals (§3) ----

    @Test
    fun aWrongKeyOrABadKeyLengthIsRefused() {
        val blob = seal(pattern(1000))
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(blob, 1000, ByteArray(32) { 7 }) }
        assertThrows(MediaCrypto.MediaError.InvalidKey::class.java) { open(blob, 1000, ByteArray(16)) }
        assertThrows(MediaCrypto.MediaError.InvalidKey::class.java) { seal(pattern(10), ByteArray(31)) }
    }

    @Test
    fun aWrongMagicOrSegmentSizeIsRefused() {
        val blob = seal(pattern(100))
        val magic = blob.copyOf().also { it[4] = '2'.code.toByte() }
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(magic, 100) }
        val segment = blob.copyOf().also { it[13] = 0x02 } // 65536 → 131072
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(segment, 100) }
        assertNull(Shrf1.noncePrefix(segment.copyOf(16)))
    }

    @Test
    fun aBlobOfTheWrongLengthForSIsRefused() {
        val blob = seal(pattern(200_000))
        // Truncated by a byte, by the last segment, appended to; and an `s` that does not fit.
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(blob.copyOf(blob.size - 1), 200_000) }
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(blob + byteArrayOf(0), 200_000) }
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(blob, 199_999) }
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(blob, 200_001) }
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(ByteArray(0), 0) }
    }

    @Test
    fun aTamperedSegmentOrHeaderIsRefused() {
        val blob = seal(pattern(200_000))
        for (at in listOf(16, 16 + 65_552 + 10, blob.size - 1, 8)) {
            val bad = blob.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertThrows("byte $at", MediaCrypto.MediaError.DecryptFailed::class.java) { open(bad, 200_000) }
        }
    }

    @Test
    fun reorderedSegmentsAreRefused() {
        val blob = seal(pattern(200_000))
        val sealedSegment = 65_536 + 16
        val swapped = blob.copyOf()
        blob.copyInto(swapped, 16, 16 + sealedSegment, 16 + 2 * sealedSegment)
        blob.copyInto(swapped, 16 + sealedSegment, 16, 16 + sealedSegment)
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(swapped, 200_000) }
    }

    @Test
    fun aDroppedLastSegmentIsRefusedEvenWithAMatchingSize() {
        // Three full segments of a four-segment blob, claimed as a 196 608-byte file: segment 2 is
        // now the last one, but it was not sealed with the last flag.
        val blob = seal(pattern(200_000))
        val kept = blob.copyOf(16 + 3 * (65_536 + 16))
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(kept, 3L * 65_536) }
    }

    @Test
    fun onlyTheFinalSegmentMayCarryTheLastFlag() {
        val plain = pattern(65_537)
        val header = Shrf1.header(prefix)
        // Segment 0 flagged last, segment 1 not: a splice of two "complete" blobs.
        val forged = header + segment(header, 0, last = true, plain.copyOfRange(0, 65_536)) +
            segment(header, 1, last = false, plain.copyOfRange(65_536, 65_537))
        assertEquals(Shrf1.sealedSize(65_537), forged.size.toLong())
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(forged, 65_537) }
        // The honest flags open.
        val honest = header + segment(header, 0, last = false, plain.copyOfRange(0, 65_536)) +
            segment(header, 1, last = true, plain.copyOfRange(65_536, 65_537))
        assertArrayEquals(plain, open(honest, 65_537))
    }

    @Test
    fun aSegmentFromAnotherBlobIsRefused() {
        val a = seal(pattern(200_000))
        val b = seal(Random(1).nextBytes(200_000), p = ByteArray(7) { 9 })
        val spliced = a.copyOf()
        b.copyInto(spliced, 16 + 65_552, 16 + 65_552, 16 + 2 * 65_552)
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { open(spliced, 200_000) }
    }

    @Test
    fun noPlaintextOfASegmentIsReleasedBeforeItsTag() {
        // A tampered first segment: nothing at all comes out.
        val blob = seal(pattern(200_000))
        blob[20] = (blob[20].toInt() xor 1).toByte()
        val out = ByteArrayOutputStream()
        assertThrows(MediaCrypto.MediaError.DecryptFailed::class.java) { Shrf1.open(blob.inputStream(), 200_000, out, key) }
        assertEquals(0, out.size())
    }

    private fun segment(header: ByteArray, index: Long, last: Boolean, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, Shrf1.nonce(prefix, index, last)))
        cipher.updateAAD(header)
        return cipher.doFinal(plain)
    }

    /** Hands out at most 1000 bytes per read: segment boundaries never line up with reads. */
    private class Trickle(input: InputStream) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1000))
    }
}
