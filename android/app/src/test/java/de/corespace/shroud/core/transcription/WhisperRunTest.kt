package de.corespace.shroud.core.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    /** The probe hears only its 8 s: the samples are cut, since whisper.cpp's duration_ms does not bound the encoder. */
    @Test
    fun aClipCutsTheSamples() {
        val pcm = FloatArray(16_000 * 20) { it.toFloat() }

        assertSame(pcm, WhisperContext.clip(pcm, 0, 0))
        assertEquals(128_000, WhisperContext.clip(pcm, 0, 8_000).size)
        val middle = WhisperContext.clip(pcm, 2_000, 1_000)
        assertEquals(16_000, middle.size)
        assertEquals(32_000f, middle[0])
        assertEquals(16_000 * 15, WhisperContext.clip(pcm, 5_000, 0).size)
        assertEquals("a clip past the end is cut at the end", 16_000 * 2, WhisperContext.clip(pcm, 18_000, 8_000).size)
        assertEquals(0, WhisperContext.clip(pcm, 30_000, 8_000).size)
    }

    @Test
    fun segmentsOfAnOffsetClipMoveBackOntoTheWholeTimeline() {
        val segments = listOf(NativeSegment("a".toByteArray(), 0, 1_000, -0.1f, 1, 2))

        val moved = WhisperContext.placed(segments, offsetMs = 2_000, clipMs = 5_000).single()

        assertEquals(2_000L, moved.t0Ms)
        assertEquals(3_000L, moved.t1Ms)
        assertSame(segments, WhisperContext.placed(segments, offsetMs = 0, clipMs = 5_000))
    }

    /** Without timestamps whisper.cpp closes a segment at its 30 s window end, past an 8 s probe. */
    @Test
    fun segmentTimesStopAtTheEndOfTheClip() {
        val probe = listOf(NativeSegment("a".toByteArray(), 0, 30_000, -0.1f, 1, 2))

        val placed = WhisperContext.placed(probe, offsetMs = 0, clipMs = 8_000).single()

        assertEquals(0L, placed.t0Ms)
        assertEquals(8_000L, placed.t1Ms)
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
