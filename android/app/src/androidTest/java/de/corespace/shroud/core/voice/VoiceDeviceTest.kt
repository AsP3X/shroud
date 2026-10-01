package de.corespace.shroud.core.voice

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.core.storage.SensitiveTempFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The platform halves of a voice note on a device (W2-VOICE acceptance, media-voice-links §8.1, D8):
 * [AacM4aWriter] writes AAC-LC 44.1 kHz mono MPEG-4 audio with no location and no device metadata
 * (the muxer's `meta`/`udta` boxes blanked by [Mp4MetadataBlanker]), [AudioPcmDecoder] reads
 * it back (and the web's WAV), [VoiceRecorder] with the real encoder yields a note, and
 * [ExoVoicePlayer] prepares it. Uses synthetic PCM, so it needs no microphone permission:
 *
 * ```
 * adb shell am instrument -w -e class de.corespace.shroud.core.voice.VoiceDeviceTest \
 *     de.corespace.shroud.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Whether iPhone and the web play the file is checked in the W2-INT engine e2e.
 */
@RunWith(AndroidJUnit4::class)
class VoiceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = VoiceFormat.SAMPLE_RATE

    private fun speech(i: Int): Short {
        val envelope = 0.5 + 0.5 * sin(2 * PI * 4.0 * i / rate)
        return (12_000 * envelope * sin(2 * PI * 220.0 * i / rate)).toInt().toShort()
    }

    private fun encode(seconds: Double): ByteArray {
        val file = SensitiveTempFiles(context.cacheDir).create(VoiceFormat.TEMP_STEM, VoiceFormat.TEMP_EXTENSION)
        try {
            val writer = AacM4aWriter(file)
            val frames = (rate * seconds).toInt()
            val chunk = ShortArray(VoiceRecorder.CHUNK_FRAMES)
            var written = 0
            while (written < frames) {
                val n = minOf(chunk.size, frames - written)
                for (i in 0 until n) chunk[i] = speech(written + i)
                writer.write(chunk, 0, n)
                written += n
            }
            writer.finish()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    @Test
    fun theWriterProducesAacLcMonoMpeg4Audio() {
        val bytes = encode(1.5)
        assertEquals("ftyp", String(bytes, 4, 4, Charsets.US_ASCII))
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(bytes))
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(rate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } finally {
            extractor.release()
        }
        val duration = AudioPcmDecoder.durationMs(bytes)
        assertNotNull(duration)
        assertTrue("duration $duration", abs(duration!! - 1500) <= 60)
        assertFalse("no location atom", containsLocation(bytes))
        assertFalse("no device metadata (com.android.version …)", contains(bytes, "com.android".toByteArray(Charsets.US_ASCII)))
        assertEquals("no meta/udta box left", 0, metadataBoxesIn(bytes))
        assertTrue("≈ 64 kbps: ${bytes.size} bytes for 1.5 s", bytes.size in 6_000..20_000)
    }

    @Test
    fun theDecoderReadsTheNoteBackForTranscription() {
        val bytes = encode(1.5)
        val decoded = AudioPcmDecoder.decodeMono(bytes, VoiceFormat.MIME)
        assertEquals(rate, decoded.sampleRate)
        assertTrue("${decoded.durationSeconds} s", abs(decoded.durationSeconds - 1.5) < 0.1)
        assertTrue(decoded.samples.any { abs(it) > 0.1f })
        val whisper = AudioPcmDecoder.decodeMono16k(bytes, VoiceFormat.MIME)
        assertTrue("${whisper.size} samples", abs(whisper.size - 24_000) < 1_600)
    }

    @Test
    fun aTakeWithTheRealEncoderIsANote() = runBlocking {
        val totalFrames = rate
        val delivered = CountDownLatch(1)
        val source = object : PcmSource {
            var position = 0

            override fun start() = Unit

            override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
                val n = minOf(size, totalFrames - position)
                if (n <= 0) {
                    delivered.countDown()
                    Thread.sleep(1)
                    return 0
                }
                for (i in 0 until n) buffer[offset + i] = speech(position + i)
                position += n
                return n
            }

            override fun stop() = Unit

            override fun release() = Unit
        }
        val temps = SensitiveTempFiles(context.cacheDir)
        val recorder = VoiceRecorder(
            scope = CoroutineScope(Dispatchers.Main.immediate),
            capture = object : VoiceCaptureFactory {
                override fun openSource(): PcmSource = source
                override fun openEncoder(output: File): VoiceEncoder = AacM4aWriter(output)
            },
            audioFocus = AudioFocusCoordinator(object : AudioFocusCoordinator.FocusPort {
                override fun request(onLoss: () -> Unit) = true
                override fun abandon() = Unit
            }),
            hasPermission = { true },
            createTempFile = { temps.create(VoiceFormat.TEMP_STEM, VoiceFormat.TEMP_EXTENSION) },
        )
        assertTrue(recorder.start())
        assertTrue(delivered.await(10, TimeUnit.SECONDS))
        val take = recorder.finish()
        assertNotNull(take)
        take!!
        assertEquals(1000, take.durationMs)
        assertTrue(VoiceWaveform.isUsable(take.waveform))
        assertEquals(rate, AudioPcmDecoder.decodeMono(take.data).sampleRate)
        assertTrue(
            "no voice temp file left",
            context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("shroud-voice-") },
        )
    }

    @Test
    fun exoPlayerPreparesAnAndroidNoteAndAWebWav() {
        assertNear(1500L, readyDuration(encode(1.5)).toDouble(), 60.0)
        val wav = webWav(seconds = 1.0)
        assertNear(1000L, readyDuration(wav).toDouble(), 5.0)
    }

    private fun readyDuration(bytes: ByteArray): Long {
        val ready = CountDownLatch(1)
        val duration = AtomicLong(-1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: ExoVoicePlayer
        instrumentation.runOnMainSync {
            player = ExoVoicePlayer(context)
            player.listener = object : VoicePlayer.Listener {
                override fun onReady(durationMs: Long?) {
                    duration.set(durationMs ?: -2)
                    ready.countDown()
                }

                override fun onEnded() = Unit

                override fun onError() {
                    ready.countDown()
                }

                override fun onPausedBySystem() = Unit
            }
            player.load(bytes)
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { player.stop() }
        return duration.get()
    }

    private fun assertNear(expected: Long, actual: Double, tolerance: Double) {
        assertTrue("expected ≈ $expected, got $actual", abs(expected - actual) <= tolerance)
    }

    /** The web client's 16-bit mono 22 050 Hz WAV (`web/src/voice/wav.ts:18-42`). */
    private fun webWav(seconds: Double): ByteArray {
        val webRate = 22_050
        val n = (webRate * seconds).toInt()
        val out = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        out.putInt(16).putShort(1).putShort(1).putInt(webRate).putInt(webRate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) out.putShort((8_000 * sin(2 * PI * 220.0 * i / webRate)).toInt().toShort())
        return out.array()
    }

    /** QuickTime `©xyz` (ISO 6709 location), the atom a phone camera writes; MediaMuxer only adds it on `setLocation`. */
    private fun containsLocation(bytes: ByteArray): Boolean =
        contains(bytes, byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()))

    /** How many `meta`/`udta` boxes the file still has (the blanker run on a copy finds none to blank). */
    private fun metadataBoxesIn(bytes: ByteArray): Int {
        val copy = SensitiveTempFiles(context.cacheDir).create("m4acheck", "m4a")
        return try {
            copy.writeBytes(bytes)
            Mp4MetadataBlanker.blank(copy)
        } finally {
            copy.delete()
        }
    }

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
