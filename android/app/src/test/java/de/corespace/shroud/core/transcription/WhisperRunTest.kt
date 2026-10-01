package de.corespace.shroud.core.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp

/**
 * The pure parts of the whisper.cpp wrapper: text joining, the iOS confidence rule
 * (`WhisperKitEngine.swift:197-218`), the media §9.9 parameter mapping and per-run cancellation.
 * The native calls themselves run in `TranscriptionBenchmarkDeviceTest` (androidTest).
 */
class WhisperRunTest {
    private fun segment(text: ByteArray, logprob: Float = -0.2f, tokens: Int = 3) =
        NativeSegment(text, t0Ms = 0, t1Ms = 1_000, avgTokenLogprob = logprob, textTokens = tokens, langId = 2)

    private fun segment(text: String, logprob: Float = -0.2f, tokens: Int = 3) = segment(text.toByteArray(), logprob, tokens)

    @Test
    fun segmentsJoinAndTrimLikeWhisperKitsMergedText() {
        val run = WhisperRun(listOf(segment(" And so my fellow Americans,"), segment(" ask not.")), "en")

        assertEquals("And so my fellow Americans, ask not.", run.text)
    }

    /** Byte-level tokens can split a character across segments; joining bytes first keeps it whole. */
    @Test
    fun aCharacterSplitAcrossSegmentsSurvives() {
        val bytes = " Grüße 👋".toByteArray()
        val umlaut = " Gr".toByteArray().size                   // the ü starts here (2 bytes)
        val wave = bytes.size - 4                               // the emoji (4 bytes)
        val parts = listOf(
            bytes.copyOfRange(0, umlaut + 1),
            bytes.copyOfRange(umlaut + 1, wave + 2),
            bytes.copyOfRange(wave + 2, bytes.size),
        )

        assertEquals("Grüße 👋", WhisperRun(parts.map { segment(it) }, "de").text)
    }

    @Test
    fun confidenceIsTheExponentOfTheMeanSegmentLogprob() {
        val run = WhisperRun(listOf(segment("a", -0.1f), segment("b", -0.3f)), "en")

        assertEquals(exp(-0.2), run.confidence, 1e-6)
    }

    @Test
    fun segmentsWithoutTextTokensDoNotCount() {
        val run = WhisperRun(listOf(segment("Hallo", -0.4f), segment(" ", Float.NaN, tokens = 0)), "de")

        assertEquals(exp(-0.4), run.confidence, 1e-6)
    }

    /** `segments.isEmpty → text.isEmpty ? 0 : 0.7` (`WhisperKitEngine.swift:211-212`). */
    @Test
    fun withoutScoredSegmentsConfidenceIsZeroOrTheFixedGuess() {
        assertEquals(0.0, WhisperRun(emptyList(), null).confidence, 0.0)
        assertEquals(0.7, WhisperRun(listOf(segment("Hi", Float.NaN, tokens = 0)), "en").confidence, 0.0)
        assertEquals(0.0, WhisperRun(listOf(segment("  ", Float.NaN, tokens = 0)), "en").confidence, 0.0)
    }

    @Test
    fun confidenceStaysWithinZeroAndOne() {
        assertEquals(1.0, WhisperRun(listOf(segment("x", 0.5f)), "en").confidence, 0.0)
        assertTrue(WhisperRun(listOf(segment("x", -40f)), "en").confidence >= 0.0)
    }

    /** media §9.9: voice note → timestamps on, −0.6 / 0.5 / 2.2, temperature step 0.2, whole file. */
    @Test
    fun voiceNoteOptionsFollowTheParameterMapping() {
        val options = WhisperDecodeOptions.voiceNote("de", threads = 4)

        assertEquals("de", options.language)
        assertEquals(4, options.threads)
        assertFalse(options.noTimestamps)
        assertEquals(0, options.offsetMs)
        assertEquals(0, options.durationMs)
        assertEquals(-0.6f, options.logprobThold)
        assertEquals(0.5f, options.noSpeechThold)
        assertEquals(2.2f, options.entropyThold)
        assertEquals(0.2f, options.temperatureInc)
    }

    /** `TranscriptionRequest.detectLanguage(clipSeconds: 8)` → 0…8 s without timestamps (`TranscriptionTypes.swift:70-72`). */
    @Test
    fun theLanguageProbeReadsTheFirstEightSecondsWithoutTimestamps() {
        val probe = WhisperDecodeOptions.languageProbe(threads = 2)

        assertEquals(null, probe.language)
        assertTrue(probe.noTimestamps)
        assertEquals(0, probe.offsetMs)
        assertEquals(8_000, probe.durationMs)
    }

    @Test
    fun defaultThreadsAreAtMostFour() {
        val threads = WhisperContext.defaultThreads()

        assertTrue(threads in 1..4)
        assertEquals(minOf(4, Runtime.getRuntime().availableProcessors()), threads)
    }

    @Test
    fun aCancellationBeforeTheRunFiresAsSoonAsTheRunListens() {
        val cancellation = WhisperCancellation()
        val fired = AtomicInteger()
        cancellation.cancel()

        cancellation.setOnCancel { fired.incrementAndGet() }

        assertTrue(cancellation.isCancelled)
        assertEquals(1, fired.get())
    }

    @Test
    fun aCancellationFiresOnceAndNotAfterTheRunDetached() {
        val cancellation = WhisperCancellation()
        val fired = AtomicInteger()
        cancellation.setOnCancel { fired.incrementAndGet() }

        cancellation.cancel()
        cancellation.cancel()
        cancellation.setOnCancel(null)

        assertEquals(1, fired.get())

        val late = WhisperCancellation()
        late.setOnCancel { fired.incrementAndGet() }
        late.setOnCancel(null)
        late.cancel()
        assertEquals("a detached run hears nothing", 1, fired.get())
    }

    @Test
    fun anAbortIsACancellation() {
        val aborted: Throwable = WhisperAbortedException()
        assertTrue(aborted is java.util.concurrent.CancellationException)
    }
}
