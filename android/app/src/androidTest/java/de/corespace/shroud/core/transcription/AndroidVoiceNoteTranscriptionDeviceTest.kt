package de.corespace.shroud.core.transcription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.voice.AacM4aWriter
import de.corespace.shroud.core.voice.VoiceFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * G6 on a device: an Android-recorded voice note, transcribed by [VoiceTranscription].
 *
 * Android-recorded means the bytes were written by [AacM4aWriter], the production encoder, not a
 * file assembled by hand. The speech is the public-domain JFK clip in `androidTest/assets/whisper/`
 * (`jfk.wav`), decoded to PCM and resampled to the recorder's 44.1 kHz rate before that encoder.
 * The model is the app's `ggml-base-q5_1`, downloaded by [WhisperModelStore] on first use.
 *
 * An iPhone-recorded note is not in this test. The repo has no `.m4a` or `.caf` voice fixture
 * under `ios/` or anywhere else, and one is not invented here.
 */
@RunWith(AndroidJUnit4::class)
class AndroidVoiceNoteTranscriptionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShroudApplication

    @Test
    fun anAndroidRecordedKennedyNoteTranscribes() = runBlocking {
        val voice = app.container.transcription.voice
        assertTrue("whisper is not on this device", voice.isAvailable.value)
        voice.languageOverride = Locale.forLanguageTag("en")
        try {
            val note = androidNote(instrumentation.context.assets.open("whisper/jfk.wav").use { it.readBytes() })
            val text = voice.transcribe(note, VoiceFormat.MIME)
            val words = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            assertTrue(text, words.isNotEmpty() && "country" in words)
        } finally {
            voice.languageOverride = null
        }
    }

    /** PCM from the clip, then the production AAC-LC writer. The temp file does not outlive the call. */
    private fun androidNote(wav: ByteArray): ByteArray {
        val pcm16k = Pcm16k.fromWav(wav)
        val atRecorderRate = Pcm16k.resample(pcm16k, Pcm16k.SAMPLE_RATE.toDouble(), VoiceFormat.SAMPLE_RATE.toDouble())
        val pcm = ShortArray(atRecorderRate.size) { index ->
            (atRecorderRate[index].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
        }
        val file = File(app.cacheDir, "shroud-voice-tx-${System.nanoTime()}.m4a")
        try {
            val writer = AacM4aWriter(file)
            var offset = 0
            while (offset < pcm.size) {
                val size = minOf(4_096, pcm.size - offset)
                writer.write(pcm, offset, size)
                offset += size
            }
            writer.finish()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }
}
