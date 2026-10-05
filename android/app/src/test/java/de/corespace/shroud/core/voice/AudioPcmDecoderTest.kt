package de.corespace.shroud.core.voice

import de.corespace.shroud.core.crypto.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * The pure parts of [AudioPcmDecoder] (media-voice-links §9.8): the web's band-limited resampler
 * (`web/src/voice/wav.ts`), channel downmix, and the web WAV reader. Vectors computed by running
 * `web/src/voice/wav.ts`. The MediaCodec path (AAC, Opus) is exercised on a device
 * (`AacM4aWriterDeviceTest`).
 */
class AudioPcmDecoderTest {
    /** `encodeWav([0, 0.5, −0.5, 1, −1, 0.25], 22050)` from the web client, byte for byte. */
    private val webWav = hexToBytes(
        "524946463000000057415645666d742010000000010001002256000044ac000002001000646174610c0000000000ff3f00c0ff7f0080ff1f",
    )

    @Test
    fun readsTheWebClientsWav() {
        val decoded = AudioPcmDecoder.parseWav(webWav)
        assertNotNull(decoded)
        decoded!!
        assertEquals(22_050, decoded.sampleRate)
        assertArrayEquals(
            floatArrayOf(0f, 0.4999847412109375f, -0.5f, 1f, -1f, 0.24997711181640625f),
            decoded.samples,
            0f,
        )
        assertEquals(6.0 / 22_050, decoded.durationSeconds, 1e-12)
    }

    @Test
    fun wavDecodingNeedsNoCodecAndHonoursTheCap() {
        val decoded = AudioPcmDecoder.decodeMono(webWav, "audio/wav")
        assertEquals(6, decoded.samples.size)
        val capped = AudioPcmDecoder.decodeMono(webWav, null, maxSeconds = 0)
        assertEquals("at least one second's worth stays", 6, capped.samples.size)
    }

    @Test
    fun rejectsWhatIsNotSixteenBitMonoPcmWav() {
        assertNull(AudioPcmDecoder.parseWav(ByteArray(0)))
        assertNull(AudioPcmDecoder.parseWav(ByteArray(64)))
        // Stereo: channel count 2 at byte 22.
        val stereo = webWav.copyOf().also { it[22] = 2 }
        assertNull(AudioPcmDecoder.parseWav(stereo))
        // 8-bit: bits per sample at byte 34.
        val eightBit = webWav.copyOf().also { it[34] = 8 }
        assertNull(AudioPcmDecoder.parseWav(eightBit))
        // Not PCM (format 3, float).
        val float = webWav.copyOf().also { it[20] = 3 }
        assertNull(AudioPcmDecoder.parseWav(float))
        // A data chunk claiming more than the file holds is cut to what is there.
        val truncated = webWav.copyOf(webWav.size - 4)
        assertEquals(4, AudioPcmDecoder.parseWav(truncated)!!.samples.size)
    }

    @Test
    fun durationOfAWavComesFromTheHeader() {
        val second = wav(FloatArray(22_050), 22_050)
        assertEquals(1000, AudioPcmDecoder.durationMs(second))
    }

    @Test
    fun resampleMatchesTheWeb() {
        val input = floatArrayOf(0f, 1f, 0f, -1f, 0.5f, 0.25f, -0.25f, 0.75f, 1f, 0f)
        assertArrayEquals(
            floatArrayOf(0.441580683f, -0.0473401099f, 0.0318375751f, 0.578580081f),
            AudioPcmDecoder.resample(input, 44_100.0, 17_640.0),
            1e-6f,
        )
        assertArrayEquals(
            floatArrayOf(0.403395355f, -0.0869536847f, 0.198266789f),
            AudioPcmDecoder.resample(input, 44_100.0, 16_000.0),
            1e-6f,
        )
        assertArrayEquals(
            floatArrayOf(0.204766229f, 0.702111900f, 0.823446155f, 0.372569948f, -0.395200640f, -0.883982599f, -0.883982599f),
            AudioPcmDecoder.resample(floatArrayOf(0f, 1f, -1f), 3.0, 7.0),
            1e-6f,
        )
    }

    @Test
    fun resamplePassesSpeechAndRemovesWhatWouldFoldIntoIt() {
        assertEquals(0.0, level(1_000.0, 48_000, 16_000), 0.05)
        assertEquals(0.0, level(6_000.0, 48_000, 16_000), 0.5)
        assertEquals(0.0, level(6_000.0, 44_100, 16_000), 0.5)
        // Linear interpolation passed these at 0 dB, folded to 6, 4 and 1.6 kHz.
        assertTrue(level(10_000.0, 48_000, 16_000) < -70)
        assertTrue(level(12_000.0, 48_000, 16_000) < -70)
        assertTrue(level(14_400.0, 44_100, 16_000) < -70)
        assertEquals(0.0, level(1_000.0, 8_000, 16_000), 0.05)
    }

    @Test
    fun resampleKeepsAConstantUpToBothEdges() {
        val ones = AudioPcmDecoder.resample(FloatArray(4_800) { 1f }, 48_000.0, 16_000.0)
        assertTrue(ones.all { abs(it - 1f) < 1e-4f })
    }

    @Test
    fun resampleKeepsTheSameArrayWhenNothingChangesAndNeverReturnsEmpty() {
        val input = floatArrayOf(0.1f, 0.2f)
        assertSame(input, AudioPcmDecoder.resample(input, 16_000.0, 16_000.0))
        val empty = FloatArray(0)
        assertSame(empty, AudioPcmDecoder.resample(empty, 44_100.0, 16_000.0))
        assertEquals(1, AudioPcmDecoder.resample(floatArrayOf(0.3f), 44_100.0, 16_000.0).size)
    }

    @Test
    fun aMinuteAt44kBecomesAMinuteAt16k() {
        val minute = FloatArray(44_100 * 60)
        assertEquals(16_000 * 60, AudioPcmDecoder.resample(minute, 44_100.0, 16_000.0).size)
    }

    @Test
    fun downmixAveragesTheChannels() {
        val stereo = floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0f), AudioPcmDecoder.downmix(stereo, 2), 0f)
        val mono = floatArrayOf(0.1f)
        assertSame(mono, AudioPcmDecoder.downmix(mono, 1))
    }

    @Test
    fun memoryDataSourceReadsWithinBounds() {
        val source = ByteArrayMediaDataSource(byteArrayOf(1, 2, 3, 4, 5))
        val buffer = ByteArray(4)
        assertEquals(5L, source.size)
        assertEquals(3, source.readAt(2, buffer, 1, 3))
        assertArrayEquals(byteArrayOf(0, 3, 4, 5), buffer)
        assertEquals(1, source.readAt(4, buffer, 0, 4))
        assertEquals(-1, source.readAt(5, buffer, 0, 4))
        assertEquals(0, source.readAt(0, buffer, 0, 0))
    }

    /** The web's `encodeWav` (`wav.ts:58-82`) for test input. */
    private fun wav(samples: FloatArray, rate: Int): ByteArray {
        val n = samples.size
        val out = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        out.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(n * 2)
        for (s in samples) {
            val c = s.coerceIn(-1f, 1f)
            out.putShort((if (c < 0) c * 0x8000 else c * 0x7fff).toInt().toShort())
        }
        return out.array()
    }

    /** Level of a resampled full-scale sine against full scale, in dB, over the middle of the signal. */
    private fun level(hz: Double, from: Int, to: Int): Double {
        val sine = FloatArray(from) { sin(2 * PI * hz * it / from).toFloat() }
        val out = AudioPcmDecoder.resample(sine, from.toDouble(), to.toDouble())
        val start = out.size / 5
        val end = out.size * 4 / 5
        var power = 0.0
        for (i in start until end) power += out[i].toDouble() * out[i]
        return 10 * log10(power / (end - start) / 0.5 + 1e-30)
    }
}
