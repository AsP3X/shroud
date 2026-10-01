package de.corespace.shroud.core.media

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.crypto.ScriptedEntropy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Media risk R1 on a real device: the transfers' streaming GCM ([StreamingGcm], the platform's
 * AES/CTR — Conscrypt, ARMv8 AES instructions — plus BouncyCastle's table GHASH) against
 * BouncyCastle's all-Java GCM (`MediaCrypto.sealStream`/`openStream`, 44–45 MB/s on the API 37
 * emulator in wave 1; **target ≥ 50 MB/s**). Both seal the same [TOTAL_BYTES] under one key and
 * nonce to a `shroud-` file in the cache directory; the sealed files must be byte-identical (SHA-256)
 * and open back to the plaintext, so the device's own Conscrypt is checked too, not just the JVM's
 * provider of the unit tests.
 *
 * ```
 * adb shell am instrument -w -e class de.corespace.shroud.core.media.StreamingGcmBenchmarkTest \
 *     de.corespace.shroud.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Rates appear in the instrumentation output (`INSTRUMENTATION_STATUS: streaming_seal_mb_s=…`) and
 * in logcat under `StreamingGcmBenchmark`; they are recorded, not asserted. Only sizes and rates
 * are logged — never keys or content.
 */
@RunWith(AndroidJUnit4::class)
class StreamingGcmBenchmarkTest {
    private val key = ByteArray(32) { 0x31 }
    private val nonce = ByteArray(12) { 0x32 }

    @Test
    fun streamingGcmMatchesBouncyCastleAndReportsBothRates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cacheDir = instrumentation.targetContext.cacheDir
        val ours = File(cacheDir, "shroud-gcm-streaming.sealed")
        val theirs = File(cacheDir, "shroud-gcm-bouncycastle.sealed")
        try {
            // Warm-up so the JIT has compiled both loops before the timed runs.
            ours.outputStream().use { StreamingGcm.seal(PatternInputStream(32L * MIB), it, key, nonce) }
            theirs.outputStream().use { MediaCrypto.sealStream(PatternInputStream(32L * MIB), it, ScriptedEntropy(key, nonce)).fill(0) }

            val plainDigest = MessageDigest.getInstance("SHA-256")
            val oursSeal = timed { ours.outputStream().buffered(MIB.toInt()).use { StreamingGcm.seal(PatternInputStream(TOTAL_BYTES, plainDigest), it, key, nonce) } }
            val theirsSeal = timed {
                theirs.outputStream().buffered(MIB.toInt()).use { MediaCrypto.sealStream(PatternInputStream(TOTAL_BYTES), it, ScriptedEntropy(key, nonce)).fill(0) }
            }
            assertEquals(TOTAL_BYTES + MediaCrypto.SEALED_OVERHEAD_BYTES, ours.length())
            assertArrayEquals("byte-identical to BouncyCastle's GCM", digest(theirs), digest(ours))

            val oursOpened = DigestOutputStream()
            val oursOpen = timed { ours.inputStream().buffered(MIB.toInt()).use { StreamingGcm.open(it, oursOpened, key) } }
            val theirsOpened = DigestOutputStream()
            val theirsOpen = timed { ours.inputStream().buffered(MIB.toInt()).use { MediaCrypto.openStream(it, theirsOpened, key) } }
            val expected = plainDigest.digest()
            assertEquals(TOTAL_BYTES, oursOpened.count)
            assertArrayEquals(expected, oursOpened.digest.digest())
            assertArrayEquals(expected, theirsOpened.digest.digest())

            val line = ("StreamingGcm (AES/CTR + GHASH), ${TOTAL_BYTES / MIB} MiB: seal %.1f MB/s, open %.1f MB/s; " +
                "BouncyCastle GCM: seal %.1f MB/s, open %.1f MB/s (target ≥ 50 MB/s)")
                .format(rate(oursSeal), rate(oursOpen), rate(theirsSeal), rate(theirsOpen))
            Log.i(TAG, line)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("streaming_seal_mb_s", "%.1f".format(rate(oursSeal)))
                    putString("streaming_open_mb_s", "%.1f".format(rate(oursOpen)))
                    putString("bouncycastle_seal_mb_s", "%.1f".format(rate(theirsSeal)))
                    putString("bouncycastle_open_mb_s", "%.1f".format(rate(theirsOpen)))
                    putString("stream", line)
                },
            )
        } finally {
            ours.delete()
            theirs.delete()
        }
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return SystemClock.elapsedRealtimeNanos() - start
    }

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(MIB.toInt())
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    /** Decimal MB/s (10^6 bytes), as storage and network rates are quoted. */
    private fun rate(nanos: Long): Double = TOTAL_BYTES / 1e6 / (nanos / 1e9)

    /** [size] bytes of a fixed pseudo-random pattern, digested as they are read. */
    private class PatternInputStream(private val size: Long, private val digest: MessageDigest? = null) : InputStream() {
        private val pattern = ByteArray(PATTERN_BYTES).also { java.util.Random(7).nextBytes(it) }
        private var position = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= size) return -1
            val at = (position % PATTERN_BYTES).toInt()
            val n = minOf(len.toLong(), size - position, (PATTERN_BYTES - at).toLong()).toInt()
            pattern.copyInto(b, off, at, at + n)
            digest?.update(b, off, n)
            position += n
            return n
        }
    }

    /** Counts and digests what is written; keeps nothing. */
    private class DigestOutputStream : OutputStream() {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var count = 0L
            private set

        override fun write(b: Int) {
            digest.update(b.toByte())
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            digest.update(b, off, len)
            count += len
        }
    }

    private companion object {
        const val TAG = "StreamingGcmBenchmark"
        const val MIB = 1024L * 1024
        const val TOTAL_BYTES = 256L * MIB
        const val PATTERN_BYTES = 1024 * 1024 + 13
    }
}
