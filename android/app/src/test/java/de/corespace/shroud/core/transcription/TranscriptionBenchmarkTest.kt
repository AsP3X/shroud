package de.corespace.shroud.core.transcription

import de.corespace.shroud.core.voice.AudioPcmDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The benchmark's JVM-testable parts: WAV → 16 kHz mono, the memory readings, and the P7 gate (00-plan §3). The run itself is `TranscriptionBenchmarkDeviceTest`
 * (androidTest).
 */
class TranscriptionBenchmarkTest {
    @Test
    fun pcm16MonoAt16kReadsSampleForSample() {
        val samples = shortArrayOf(0, 16384, -16384, 32767, Short.MIN_VALUE)

        val pcm = Pcm16k.fromWav(wav(format = 1, channels = 1, rate = 16_000, bits = 16, data = shorts(samples)))

        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f, 32767 / 32768f, -1f), pcm, 0f)
    }

    @Test
    fun stereoIsAveraged() {
        val pcm = Pcm16k.fromWav(wav(format = 1, channels = 2, rate = 16_000, bits = 16, data = shorts(shortArrayOf(16384, 0, -16384, -16384))))

        assertArrayEquals(floatArrayOf(0.25f, -0.5f), pcm, 0f)
    }

    /** Another rate goes through the app's resampler ([AudioPcmDecoder.resample]). */
    @Test
    fun otherRatesAreResampledLikeAVoiceNote() {
        val samples = ShortArray(441) { (it * 30).toShort() }
        val pcm = Pcm16k.fromWav(wav(format = 1, channels = 1, rate = 44_100, bits = 16, data = shorts(samples)))
        val expected = AudioPcmDecoder.resample(FloatArray(441) { samples[it] / 32768f }, 44_100.0, 16_000.0)
        assertEquals(160, pcm.size)
        assertArrayEquals(expected, pcm, 0f)
    }

    /** G.711 μ-law: 0xFF and 0x7F are silence, 0x80 / 0x00 the extremes (±32124). */
    @Test
    fun muLawDecodesTheG711Table() {
        assertEquals(0f, Pcm16k.muLaw(0xFF.toByte()), 0f)
        assertEquals(0f, Pcm16k.muLaw(0x7F.toByte()), 0f)
        assertEquals(32124 / 32768f, Pcm16k.muLaw(0x80.toByte()), 0f)
        assertEquals(-32124 / 32768f, Pcm16k.muLaw(0x00.toByte()), 0f)
        assertEquals(8 / 32768f, Pcm16k.muLaw(0xFE.toByte()), 0f)
    }

    @Test
    fun floatAndExtensibleWavsAreRead() {
        val floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).putFloat(-1f).array()
        assertArrayEquals(floatArrayOf(0.25f, -1f), Pcm16k.fromWav(wav(format = 3, channels = 1, rate = 16_000, bits = 32, data = floats)), 0f)

        val extensible = wav(format = 0xFFFE, channels = 1, rate = 16_000, bits = 16, data = shorts(shortArrayOf(8192)), subFormat = 1)
        assertArrayEquals(floatArrayOf(0.25f), Pcm16k.fromWav(extensible), 0f)
    }

    /** afconvert pads with an `FLLR` chunk; odd-sized chunks carry a pad byte. */
    @Test
    fun unknownAndOddSizedChunksAreSkipped() {
        val bytes = wav(format = 1, channels = 1, rate = 16_000, bits = 16, data = shorts(shortArrayOf(16384)), extraChunk = ByteArray(3))

        assertArrayEquals(floatArrayOf(0.5f), Pcm16k.fromWav(bytes), 0f)
    }

    @Test
    fun whatIsNotAWavIsRefused() {
        refused("not a RIFF/WAVE file") { Pcm16k.fromWav(ByteArray(40)) }
        refused("unsupported WAV encoding 2/4") { Pcm16k.fromWav(wav(format = 2, channels = 1, rate = 16_000, bits = 4, data = ByteArray(4))) }
        val noData = wav(format = 1, channels = 1, rate = 16_000, bits = 16, data = ByteArray(0)).copyOf(36)
        refused("no data chunk") { Pcm16k.fromWav(noData) }
    }

    /**
     * The androidTest fixtures parse to their lengths: whisper.cpp's `samples/jfk.wav` (11 s PCM16) and
     * the two 60 s μ-law LibriVox excerpts (`TranscriptionBenchmarkDeviceTest` names the sources).
     */
    @Test
    fun theDeviceFixturesParse() {
        val dir = File("src/androidTest/assets/whisper")
        assertEquals(176_000, Pcm16k.fromWav(File(dir, "jfk.wav").readBytes()).size)
        assertEquals(960_000, Pcm16k.fromWav(File(dir, "de-grimm-60s.wav").readBytes()).size)
        assertEquals(960_000, Pcm16k.fromWav(File(dir, "en-magi-60s.wav").readBytes()).size)
    }

    @Test
    fun headAndJoinBuildClips() {
        val one = FloatArray(16_000) { 1f }

        assertEquals(8_000, Pcm16k.head(one, 0.5).size)
        assertEquals(16_000, Pcm16k.head(one, 5.0).size)
        val joined = Pcm16k.join(listOf(one, one), gapSeconds = 0.25)
        assertEquals(36_000, joined.size)
        assertEquals(0f, joined[16_000], 0f)
        assertEquals(1f, joined[20_000], 0f)
    }

    @Test
    fun procStatusIsRead() {
        val status = "Name:\tde.corespace.shroud\nVmHWM:\t  312340 kB\nVmRSS:\t  250112 kB\n"

        assertEquals(312_340L, ProcessMemory.statusKb("VmHWM", status))
        assertEquals(250_112L, ProcessMemory.statusKb("VmRSS", status))
        assertNull(ProcessMemory.statusKb("VmSwap", status))
        assertNull(ProcessMemory.statusKb("VmRSS", null))
    }

    /** P7: base needs RTF ≤ 0.3 and peak RSS ≤ 400 MB; small RTF ≤ 1.0 on ≥ 6 GB RAM. */
    @Test
    fun theP7GateReadsTheWorstClip() {
        val base = result(WhisperModelFile.BaseQ5_1, rtfs = listOf(0.12, 0.28), peak = 380.0)
        val slowBase = result(WhisperModelFile.BaseQ5_1, rtfs = listOf(0.12, 0.31), peak = 380.0)
        val heavyBase = result(WhisperModelFile.BaseQ5_1, rtfs = listOf(0.2), peak = 401.0)
        val small = result(WhisperModelFile.SmallQ5_1, rtfs = listOf(0.9), peak = 700.0)

        assertTrue(report(8_192).passesGate(base))
        assertFalse(report(8_192).passesGate(slowBase))
        assertFalse(report(8_192).passesGate(heavyBase))
        assertTrue(report(6_144).passesGate(small))
        assertFalse("small needs 6 GB RAM", report(4_096).passesGate(small))
        assertFalse(report(8_192).passesGate(result(WhisperModelFile.SmallQ5_1, rtfs = listOf(1.01), peak = 700.0)))
        assertFalse("no memory reading, no pass", report(8_192).passesGate(base.copy(peakRssMb = null)))
    }

    @Test
    fun reportLinesLeaveTranscriptsOutUnlessAsked() {
        val report = report(8_192, listOf(result(WhisperModelFile.BaseQ5_1, rtfs = listOf(0.2), peak = 300.0)))

        val plain = report.lines()
        val withText = report.lines(includeText = true)

        assertTrue(plain[0].contains("EMULATOR"))
        assertTrue(plain[1].startsWith("ggml-base-q5_1.bin: load 900 ms, 4 threads"))
        assertTrue(plain[1].endsWith("P7 gate PASS"))
        assertTrue(plain[2].contains("detect 200 ms [de 0.97 ✓]"))
        assertFalse(plain.any { "geheim" in it })
        assertTrue(withText.any { "geheim" in it })
    }

    private fun report(ramMb: Long, results: List<TranscriptionBenchmark.ModelResult> = emptyList()) =
        TranscriptionBenchmark.Report(
            DeviceProfile("Google", "sdk_gphone64_arm64", "ranchu", "arm64-v8a", 4, ramMb, 30, true, "libggml-cpu-android_armv8.6_1.so", "whisper.cpp 1.9.4"),
            results,
        )

    private fun result(model: WhisperModelFile, rtfs: List<Double>, peak: Double) = TranscriptionBenchmark.ModelResult(
        model = model, threads = 4, loadMs = 900, rssBeforeLoadMb = 100.0, rssAfterLoadMb = 200.0, peakRssMb = peak, peakWasReset = true,
        clips = rtfs.mapIndexed { index, rtf ->
            TranscriptionBenchmark.ClipResult(
                clip = "clip$index", audioSeconds = 10.0, detected = WhisperLanguage("de", 0.97), expectedLanguage = "de",
                detectMs = 200, transcribeMs = (rtf * 10_000).toLong() - 200, text = "geheim", confidence = 0.8, timings = null,
            )
        },
    )

    private fun refused(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal: $message")
        } catch (expected: Pcm16k.WavFormatException) {
            assertEquals(message, expected.message)
        }
    }

    private fun shorts(values: ShortArray): ByteArray =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it) } }.array()

    private fun wav(format: Int, channels: Int, rate: Int, bits: Int, data: ByteArray, extraChunk: ByteArray? = null, subFormat: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun short(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        out.write("RIFF".toByteArray()); int(0); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(if (subFormat != null) 40 else 16)
        short(format); short(channels); int(rate); int(rate * channels * bits / 8); short(channels * bits / 8); short(bits)
        if (subFormat != null) { short(22); short(bits); int(0); short(subFormat); out.write(ByteArray(14)) }
        if (extraChunk != null) {
            out.write("FLLR".toByteArray()); int(extraChunk.size); out.write(extraChunk)
            if (extraChunk.size % 2 == 1) out.write(0)
        }
        out.write("data".toByteArray()); int(data.size); out.write(data)
        return out.toByteArray()
    }
}
