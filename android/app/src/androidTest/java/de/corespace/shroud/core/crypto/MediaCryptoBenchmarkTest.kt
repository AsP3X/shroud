package de.corespace.shroud.core.crypto

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Streaming AES-256-GCM throughput on a real device (W1-CRYPTO acceptance; media spec risk R1:
 * BouncyCastle's GCM in Java, est. 50–100 MB/s on ARM64, **target ≥ 50 MB/s**). Seals
 * [TOTAL_BYTES] of random-looking data to a `shroud-` file in the cache directory, opens it again,
 * checks the round trip by SHA-256 and reports both rates:
 *
 * ```
 * adb shell am instrument -w -e class de.corespace.shroud.core.crypto.MediaCryptoBenchmarkTest \
 *     de.corespace.shroud.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The rates appear in the instrumentation output (`INSTRUMENTATION_STATUS: seal_mb_s=…`) and in
 * logcat under `MediaCryptoBenchmark`. They are recorded, not asserted: a slow phone is a finding
 * for media R1 (AES/CTR in hardware + BouncyCastle GHASH, byte-identical), not a red build. Only
 * sizes and rates are logged — never keys or content.
 */
@RunWith(AndroidJUnit4::class)
class MediaCryptoBenchmarkTest {
    @Test
    fun streamingGcmThroughput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "shroud-gcm-benchmark.sealed")
        try {
            // Warm-up so the JIT has compiled the GCM loop before the timed runs.
            file.outputStream().use { MediaCrypto.sealStream(PatternInputStream(32L * MIB), it).fill(0) }

            val plainDigest = MessageDigest.getInstance("SHA-256")
            val sealStart = SystemClock.elapsedRealtimeNanos()
            val key = file.outputStream().buffered(MIB.toInt()).use { out ->
                MediaCrypto.sealStream(PatternInputStream(TOTAL_BYTES, plainDigest), out)
            }
            val sealNanos = SystemClock.elapsedRealtimeNanos() - sealStart
            assertEquals(TOTAL_BYTES + MediaCrypto.SEALED_OVERHEAD_BYTES, file.length())

            val openDigest = DigestOutputStream()
            val openStart = SystemClock.elapsedRealtimeNanos()
            file.inputStream().buffered(MIB.toInt()).use { MediaCrypto.openStream(it, openDigest, key) }
            val openNanos = SystemClock.elapsedRealtimeNanos() - openStart
            key.fill(0)

            assertEquals(TOTAL_BYTES, openDigest.count)
            assertArrayEquals(plainDigest.digest(), openDigest.digest.digest())

            val sealRate = rate(sealNanos)
            val openRate = rate(openNanos)
            val line = "MediaCrypto streaming GCM, ${TOTAL_BYTES / MIB} MiB: seal %.1f MB/s, open %.1f MB/s (target ≥ 50 MB/s)"
                .format(sealRate, openRate)
            Log.i(TAG, line)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("seal_mb_s", "%.1f".format(sealRate))
                    putString("open_mb_s", "%.1f".format(openRate))
                    putString("stream", line)
                },
            )
        } finally {
            file.delete()
        }
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
            val n = minOf(len.toLong(), size - position, (PATTERN_BYTES - (position % PATTERN_BYTES))).toInt()
            pattern.copyInto(b, off, (position % PATTERN_BYTES).toInt(), (position % PATTERN_BYTES).toInt() + n)
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
        const val TAG = "MediaCryptoBenchmark"
        const val MIB = 1024L * 1024
        const val TOTAL_BYTES = 256L * MIB
        const val PATTERN_BYTES = 1024 * 1024 + 13
    }
}
