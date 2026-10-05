package de.corespace.shroud.core.media.files

import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.crypto.Primitives
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The SHRF1 blob a file message uploads (docs/file-sharing.md §3): the SHRM1 cache's segmented
 * construction, keyed directly with the message's fresh random `k`, so every client seals and opens
 * it one 64 KiB segment at a time with a plain one-shot AES-GCM call (here the JCA: Conscrypt on a
 * phone, hardware AES):
 *
 * ```
 * header  = "SHRF1" (5) ‖ noncePrefix (7, random) ‖ segmentSize u32 BE (65536)   // 16 bytes
 * nonce_i = noncePrefix ‖ u32 BE(i) ‖ (last ? 0x01 : 0x00)
 * ct_i    = AES-256-GCM(k, nonce_i, plaintext[i·64K ..< min((i+1)·64K, n)], aad = header) ‖ tag (16)
 * blob    = header ‖ ct_0 ‖ … ‖ ct_last
 * sealedSize(n) = 16 + n + 16 · max(1, ⌈n / 65536⌉)
 * ```
 *
 * The header is every segment's AAD; the index and the last flag sit in the nonce, so reordering,
 * dropping, truncating, appending or splicing segments fails a tag. [open] refuses a blob unless the
 * magic is `SHRF1`, the segment size is 65536, the length is [sealedSize] of the payload's `s`, every
 * tag checks and only the final segment carries the last flag. It hands out each segment's
 * plaintext only after that segment's tag passed; callers write into an uncommitted SHRM1 writer and
 * commit once [open] returned, so no byte is shown before the last tag passed. Never logs keys or
 * content.
 */
object Shrf1 {
    /** `"SHRF1"`. */
    val MAGIC = byteArrayOf(0x53, 0x48, 0x52, 0x46, 0x31)
    const val NONCE_PREFIX_BYTES = 7
    const val HEADER_BYTES = 5 + NONCE_PREFIX_BYTES + 4
    const val SEGMENT_BYTES = 65_536
    const val TAG_BYTES = Primitives.GCM_TAG_BYTES
    private const val SEALED_SEGMENT_BYTES = SEGMENT_BYTES + TAG_BYTES

    /** `u32` segment indices: far beyond 2 GiB of plaintext. */
    private const val MAX_SEGMENTS = 0xFFFF_FFFFL + 1

    /** `max(1, ⌈n / 65536⌉)`: an empty file is one empty last segment. */
    fun segmentCount(plainSize: Long): Long {
        require(plainSize >= 0) { "negative size" }
        return maxOf(1L, (plainSize + SEGMENT_BYTES - 1) / SEGMENT_BYTES)
    }

    /** `16 + n + 16 · max(1, ⌈n / 65536⌉)`. */
    fun sealedSize(plainSize: Long): Long = HEADER_BYTES + plainSize + TAG_BYTES * segmentCount(plainSize)

    /** `"SHRF1" ‖ noncePrefix ‖ 65536`. */
    fun header(noncePrefix: ByteArray): ByteArray {
        require(noncePrefix.size == NONCE_PREFIX_BYTES) { "SHRF1 nonce prefixes are 7 bytes" }
        val header = ByteArray(HEADER_BYTES)
        MAGIC.copyInto(header, 0)
        noncePrefix.copyInto(header, MAGIC.size)
        putU32(header, HEADER_BYTES - 4, SEGMENT_BYTES.toLong())
        return header
    }

    /** The nonce prefix of a valid header (magic and the one segment size), else null. */
    fun noncePrefix(header: ByteArray): ByteArray? {
        if (header.size != HEADER_BYTES) return null
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return null
        if (u32(header, HEADER_BYTES - 4) != SEGMENT_BYTES.toLong()) return null
        return header.copyOfRange(MAGIC.size, MAGIC.size + NONCE_PREFIX_BYTES)
    }

    /** `noncePrefix ‖ u32 BE(index) ‖ last` (12 bytes). */
    fun nonce(noncePrefix: ByteArray, index: Long, last: Boolean): ByteArray {
        require(index in 0 until MAX_SEGMENTS)
        val nonce = ByteArray(Primitives.GCM_NONCE_BYTES)
        noncePrefix.copyInto(nonce, 0)
        putU32(nonce, NONCE_PREFIX_BYTES, index)
        nonce[NONCE_PREFIX_BYTES + 4] = if (last) 1 else 0
        return nonce
    }

    /**
     * Writes the SHRF1 blob of exactly [plainSize] bytes from [input] to [output], segment by
     * segment. The same key, prefix and plaintext always give the same bytes (an HTTP body written
     * twice). [input] ending early or holding more than [plainSize] → [IOException] before the
     * segment that would be wrong is written. Neither stream is closed; buffers are zeroed.
     */
    fun seal(input: InputStream, plainSize: Long, output: OutputStream, key: ByteArray, noncePrefix: ByteArray) {
        if (key.size != MediaCrypto.KEY_BYTES) throw MediaCrypto.MediaError.InvalidKey
        val header = header(noncePrefix)
        val count = segmentCount(plainSize)
        val cipher = newCipher()
        val plain = ByteArray(SEGMENT_BYTES)
        val sealed = ByteArray(SEALED_SEGMENT_BYTES + TAG_BYTES)
        try {
            output.write(header)
            var remaining = plainSize
            for (index in 0 until count) {
                val length = minOf(remaining, SEGMENT_BYTES.toLong()).toInt()
                if (readFully(input, plain, length) != length) throw IOException("file source ended early")
                remaining -= length
                val last = index == count - 1
                if (last && input.read() >= 0) throw IOException("file source changed size")
                val produced = crypt(cipher, Cipher.ENCRYPT_MODE, key, noncePrefix, header, index, last, plain, length, sealed)
                output.write(sealed, 0, produced)
            }
        } finally {
            plain.fill(0)
            sealed.fill(0)
        }
    }

    /**
     * Opens the SHRF1 blob of a file of [plainSize] bytes from [input] into [output]. Each segment's
     * plaintext is written only after its tag checked. A key that is not 32 bytes →
     * [MediaCrypto.MediaError.InvalidKey]; a wrong magic or segment size, a blob that is not exactly
     * [sealedSize] long, a tag that fails or a last flag out of place →
     * [MediaCrypto.MediaError.DecryptFailed] (with the earlier segments already written: commit
     * nothing then). I/O errors propagate; neither stream is closed.
     */
    fun open(input: InputStream, plainSize: Long, output: OutputStream, key: ByteArray) {
        if (key.size != MediaCrypto.KEY_BYTES) throw MediaCrypto.MediaError.InvalidKey
        if (plainSize < 0) throw MediaCrypto.MediaError.DecryptFailed
        val header = ByteArray(HEADER_BYTES)
        if (readFully(input, header, HEADER_BYTES) != HEADER_BYTES) throw MediaCrypto.MediaError.DecryptFailed
        val prefix = noncePrefix(header) ?: throw MediaCrypto.MediaError.DecryptFailed
        val count = segmentCount(plainSize)
        val cipher = newCipher()
        val sealed = ByteArray(SEALED_SEGMENT_BYTES)
        val plain = ByteArray(SEALED_SEGMENT_BYTES)
        try {
            var remaining = plainSize
            for (index in 0 until count) {
                val plainLength = minOf(remaining, SEGMENT_BYTES.toLong()).toInt()
                val sealedLength = plainLength + TAG_BYTES
                if (readFully(input, sealed, sealedLength) != sealedLength) throw MediaCrypto.MediaError.DecryptFailed
                val last = index == count - 1
                // Anything after the last segment is an appended segment: refused before its plaintext goes out.
                if (last && input.read() >= 0) throw MediaCrypto.MediaError.DecryptFailed
                val produced = try {
                    crypt(cipher, Cipher.DECRYPT_MODE, key, prefix, header, index, last, sealed, sealedLength, plain)
                } catch (_: IOException) {
                    throw MediaCrypto.MediaError.DecryptFailed
                }
                if (produced != plainLength) throw MediaCrypto.MediaError.DecryptFailed
                output.write(plain, 0, produced)
                plain.fill(0, 0, produced)
                remaining -= plainLength
            }
        } finally {
            sealed.fill(0)
            plain.fill(0)
        }
    }

    /**
     * One segment through AES-256-GCM with [header] as AAD; the output length. A segment that does
     * not authenticate → [IOException], with nothing released (the JCA's GCM decrypt returns output
     * only after the tag checked). The key spec is made per call so no long-lived copy of [key] exists.
     */
    private fun crypt(
        cipher: Cipher,
        mode: Int,
        key: ByteArray,
        noncePrefix: ByteArray,
        header: ByteArray,
        index: Long,
        last: Boolean,
        input: ByteArray,
        inputLength: Int,
        output: ByteArray,
    ): Int = try {
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce(noncePrefix, index, last)))
        cipher.updateAAD(header)
        cipher.doFinal(input, 0, inputLength, output, 0)
    } catch (e: GeneralSecurityException) {
        if (mode == Cipher.DECRYPT_MODE) output.fill(0)
        throw IOException(if (mode == Cipher.ENCRYPT_MODE) "SHRF1 segment could not be sealed" else "SHRF1 segment does not open", e)
    } catch (e: IllegalStateException) {
        if (mode == Cipher.DECRYPT_MODE) output.fill(0)
        throw IOException("SHRF1 cipher failed", e)
    }

    private fun newCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    /** Reads until [length] bytes are in [buffer] or the stream ends; the count read. */
    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Int {
        var filled = 0
        while (filled < length) {
            val read = input.read(buffer, filled, length - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    private fun putU32(target: ByteArray, at: Int, value: Long) {
        target[at] = (value ushr 24).toByte()
        target[at + 1] = (value ushr 16).toByte()
        target[at + 2] = (value ushr 8).toByte()
        target[at + 3] = value.toByte()
    }

    private fun u32(source: ByteArray, at: Int): Long =
        ((source[at].toLong() and 0xFF) shl 24) or ((source[at + 1].toLong() and 0xFF) shl 16) or
            ((source[at + 2].toLong() and 0xFF) shl 8) or (source[at + 3].toLong() and 0xFF)
}
