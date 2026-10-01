package de.corespace.shroud.core.transcription

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.ShroudApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * whisper.cpp on a device (W2-WHISPER acceptance): the native libraries load and pick a CPU
 * variant, JNI transcribes fixtures, a run can be cancelled, and the P7 benchmark (00-plan §3)
 * reports RTF and memory per model.
 *
 * The model comes through the app's real [WhisperModelStore] (`TranscriptionModule`): downloaded
 * from the pinned Hugging Face revision on first run (60 MB base, 190 MB small), then kept in the
 * app's `no_backup/whisper/`. Run:
 *
 * ```
 * adb shell am instrument -w -e class de.corespace.shroud.core.transcription.TranscriptionBenchmarkDeviceTest \
 *     [-e models base,small] [-e threads 4] de.corespace.shroud.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The report appears in the instrumentation output (`INSTRUMENTATION_STATUS: line_N=…`) and in
 * logcat under `WhisperBenchmark`. Speed and memory are recorded, not asserted — a slow phone is a
 * P7 finding, not a red build; correctness (language, words) is asserted.
 *
 * Fixtures (`androidTest/assets/whisper/`, public domain):
 * - `jfk.wav` — whisper.cpp v1.9.4 `samples/jfk.wav`, 11 s of J. F. Kennedy's 1961 inaugural
 *   address (a US government work), 16 kHz PCM16.
 * - `de-grimm-60s.wav` — LibriVox "Märchen 2" (Brüder Grimm), *Das Totenhemdchen*, read by Alex
 *   Foster (archive.org `grimm_maerchen2_librivox`, `grimm_018_totenhemdchen_64kb.mp3`, 30.6–90.6 s),
 *   16 kHz μ-law.
 * - `en-magi-60s.wav` — LibriVox *The Gift of the Magi* (O. Henry), read by Betsy Bush (archive.org
 *   `giftofmagi`, `gift_of_the_magi_henry_blb_64kb.mp3`, 18.9–78.9 s), 16 kHz μ-law.
 * The short German clip is the first 13.4 s of the 60 s one (one sentence group).
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionBenchmarkDeviceTest {
    @Test
    fun aCpuBackendRunsOnThisDevice() {
        assertTrue("libshroud_whisper.so loads", WhisperNative.isLoaded)
        val backend = WhisperNative.cpuBackend()
        assertNotNull("a ggml CPU variant runs here", backend)
        if (android.os.Build.SUPPORTED_ABIS.first() == "arm64-v8a") {
            assertTrue(backend!!, backend.startsWith("libggml-cpu-android_armv"))
        }
        val info = WhisperNative.systemInfo()
        assertTrue(info, info.startsWith("whisper.cpp 1.9.4 (927cfce34f31707e17f2bff35c349632fb9e2c3a)"))
        report("backend", "$backend | $info")
    }

    @Test
    fun transcribesTheKennedyFixture() {
        WhisperContext.load(baseModel).use { whisper ->
            val pcm = fixture("jfk.wav")

            val language = whisper.detectLanguage(pcm)
            val run = whisper.transcribe(pcm, WhisperDecodeOptions.voiceNote(language?.code))

            assertEquals("en", language?.code)
            assertEquals("en", run.language)
            val words = normalized(run.text)
            assertTrue(run.text, "ask not what your country can do for you" in words)
            assertTrue(run.text, "ask what you can do for your country" in words)
            assertTrue("confidence ${run.confidence}", run.confidence > 0.5 && run.confidence <= 1.0)
            assertTrue("segments stay inside the 11 s clip", run.segments.all { it.t1Ms <= 11_000 })
            report("jfk", "${run.text} (confidence %.2f)".format(Locale.ROOT, run.confidence))
        }
    }

    @Test
    fun detectsAndTranscribesGerman() {
        WhisperContext.load(baseModel).use { whisper ->
            val pcm = Pcm16k.head(fixture("de-grimm-60s.wav"), GERMAN_SHORT_SECONDS)

            val language = whisper.detectLanguage(pcm)
            val run = whisper.transcribe(pcm, WhisperDecodeOptions.voiceNote(language?.code))

            assertEquals("de", language?.code)
            val words = normalized(run.text)
            assertTrue(run.text, "mutter" in words)
            assertTrue(run.text, "sieben jahren" in words)
            assertTrue(run.text, "auf der welt" in words)
        }
    }

    /** Whisper's own detection inside the run (language null) reaches the same answer. */
    @Test
    fun aRunWithoutALanguageDetectsItItself() {
        WhisperContext.load(baseModel).use { whisper ->
            val run = whisper.transcribe(fixture("jfk.wav"), WhisperDecodeOptions.voiceNote(language = null))

            assertEquals("en", run.language)
            assertTrue(run.text, "your country" in normalized(run.text))
        }
    }

    @Test
    fun theLanguageProbeOnlyReadsTheOpening() {
        WhisperContext.load(baseModel).use { whisper ->
            val run = whisper.transcribe(fixture("en-magi-60s.wav"), WhisperDecodeOptions.languageProbe())

            assertTrue("the probe stops at 8 s", run.segments.all { it.t1Ms <= 8_000 })
            assertEquals("en", run.language)
            // "…and sixty cents of it was in pennies" is said at 11–13 s: the probe must not hear it.
            assertTrue(run.text, "magi" in normalized(run.text) && "pennies" !in normalized(run.text))
        }
    }

    /** media §9.9 "Cancellation": the abort callback stops a 60 s pass well before its end. */
    @Test
    fun aCancelledRunStopsEarly() {
        WhisperContext.load(baseModel).use { whisper ->
            val pcm = fixture("en-magi-60s.wav")
            val full = SystemClock.elapsedRealtime().let { start ->
                whisper.transcribe(pcm, WhisperDecodeOptions.voiceNote("en"))
                SystemClock.elapsedRealtime() - start
            }
            val cancellation = WhisperCancellation()
            val runner = Executors.newSingleThreadExecutor()
            try {
                val pending = runner.submit<Throwable?> {
                    try {
                        whisper.transcribe(pcm, WhisperDecodeOptions.voiceNote("en"), cancellation)
                        null
                    } catch (e: Throwable) {
                        e
                    }
                }
                Thread.sleep(full / 5)
                val cancelledAt = SystemClock.elapsedRealtime()
                cancellation.cancel()
                val outcome = pending.get(full * 2, TimeUnit.MILLISECONDS)
                val stopMs = SystemClock.elapsedRealtime() - cancelledAt

                assertTrue("aborted: $outcome", outcome is WhisperAbortedException)
                assertTrue("stopped $stopMs ms after the cancel (a full pass takes $full ms)", stopMs < full / 2)
            } finally {
                runner.shutdownNow()
            }

            val early = WhisperCancellation().apply { cancel() }
            try {
                whisper.transcribe(pcm, WhisperDecodeOptions.voiceNote("en"), early)
                fail("a run cancelled before it starts must not run")
            } catch (_: WhisperAbortedException) {
            }
            // The context stays usable after an abort.
            assertTrue("your country" in normalized(whisper.transcribe(fixture("jfk.wav"), WhisperDecodeOptions.voiceNote("en")).text))
        }
    }

    @Test
    fun aClosedContextRefusesWork() {
        val whisper = WhisperContext.load(baseModel)
        whisper.close()
        whisper.close()
        try {
            whisper.transcribe(fixture("jfk.wav"), WhisperDecodeOptions.voiceNote("en"))
            fail("a closed context must refuse")
        } catch (_: IllegalStateException) {
        }
        whisper.abort()  // harmless once closed
    }

    @Test
    fun aFileThatIsNoModelIsRefused() {
        val bogus = java.io.File(context.cacheDir, "shroud-not-a-model.bin").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        try {
            WhisperContext.load(bogus)
            fail("whisper must refuse a file that is no model")
        } catch (_: WhisperException) {
        } finally {
            bogus.delete()
        }
    }

    /**
     * The P7 benchmark: every model in `-e models` (default `base`) over a 10–13 s and a 60 s note in
     * English and German. Recorded, not asserted (see the class comment); the languages are asserted.
     */
    @Test
    fun benchmark() = runBlocking {
        val models = (arguments.getString("models") ?: "base").split(',').map {
            requireNotNull(WhisperModelFile.forId(it.trim())) { "unknown model $it" }
        }
        val threads = arguments.getString("threads")?.toInt() ?: WhisperContext.defaultThreads()
        val german = fixture("de-grimm-60s.wav")
        val clips = listOf(
            TranscriptionBenchmark.Clip("en-jfk-11s", fixture("jfk.wav"), "en"),
            TranscriptionBenchmark.Clip("de-grimm-13s", Pcm16k.head(german, GERMAN_SHORT_SECONDS), "de"),
            TranscriptionBenchmark.Clip("en-magi-60s", fixture("en-magi-60s.wav"), "en"),
            TranscriptionBenchmark.Clip("de-grimm-60s", german, "de"),
        )

        val result = container.transcription.benchmark.run(models, clips, threads) { Log.i(TAG, it) }

        val lines = result.lines(includeText = true)
        lines.forEachIndexed { index, line -> report("line_$index", line) }
        for (model in result.results) {
            for (clip in model.clips) {
                assertEquals("${model.model.fileName} ${clip.clip}", clip.expectedLanguage, clip.detected?.code)
            }
        }
    }

    private fun report(key: String, line: String) {
        Log.i(TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, line) })
    }

    private fun normalized(text: String) =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun fixture(name: String): FloatArray =
        Pcm16k.fromWav(InstrumentationRegistry.getInstrumentation().context.assets.open("whisper/$name").use { it.readBytes() })

    companion object {
        private const val TAG = "WhisperBenchmark"
        private const val GERMAN_SHORT_SECONDS = 13.4

        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        private val arguments: Bundle get() = InstrumentationRegistry.getArguments()
        private val container get() = (context.applicationContext as ShroudApplication).container

        private lateinit var baseModel: java.io.File

        /** Downloads base q5_1 once through the app's store (no-op when it is already there). */
        @JvmStatic
        @BeforeClass
        fun downloadBase() = runBlocking {
            baseModel = container.transcription.whisperModels.ensure(WhisperModelFile.BaseQ5_1)
        }
    }
}
