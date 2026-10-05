package de.corespace.shroud.core.transcription

import de.corespace.shroud.core.auth.WipeFixture
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The session and the whisper.cpp adapter without a model download
 * (`ios/shroudTests/TranscriptionEngineTests.swift`). `VoiceNoteSeek` is not ported.
 */
class TranscriptionEngineTests {
    @get:Rule val temp = TempDirRule()

    @Test
    fun sessionUsesTheInjectedEngine() = runTest {
        val fake = FakeEngine()
        fake.output = TranscriptionOutput("See you at eight", "en", 0.9)
        val session = TranscriptionSession(fake, FakeSharedPreferences(), StorageSeal())

        session.prepare(TranscriptionModelId.Small)
        session.prepare(TranscriptionModelId.Small)
        assertEquals(TranscriptionModelId.Small, fake.prepared)
        assertEquals(1, fake.prepareCalls)

        val result = session.transcribe(floatArrayOf(0f, 0f), TranscriptionRequest.voiceNote(language = "en"))
        assertEquals("See you at eight", result.text)
        assertEquals("en", fake.lastRequest?.language)
        assertEquals(TranscriptionProfile.voiceNote, fake.lastRequest?.profile)
        assertFalse(result.toString().contains("See you at eight"))
    }

    @Test
    fun concurrentPreparesShareOneDownload() = runTest {
        val engine = SlowEngine()
        val session = TranscriptionSession(engine, FakeSharedPreferences(), StorageSeal())
        listOf(
            async { session.prepare(TranscriptionModelId.Small) },
            async { session.prepare(TranscriptionModelId.Small) },
            async { session.prepare(TranscriptionModelId.Small) },
        ).awaitAll()
        assertEquals(1, engine.prepareCalls)
    }

    @Test
    fun aFailedPrepareCanBeRetried() = runTest {
        val engine = SlowEngine(failingPrepares = 1)
        val session = TranscriptionSession(engine, FakeSharedPreferences(), StorageSeal())
        try {
            session.prepare(TranscriptionModelId.Small)
            error("the first prepare should fail")
        } catch (e: TranscriptionEngineError) {
            assertTrue(e is TranscriptionEngineError.ModelUnavailable)
        }
        session.prepare(TranscriptionModelId.Small)
        assertEquals(2, engine.prepareCalls)
    }

    @Test
    fun transcriptionsRunOneAtATime() = runTest {
        val engine = SlowEngine()
        val session = TranscriptionSession(engine, FakeSharedPreferences(), StorageSeal())
        val results = listOf(
            async { session.transcribe(floatArrayOf(0f), TranscriptionRequest.voiceNote()) },
            async { session.transcribe(floatArrayOf(0f), TranscriptionRequest.voiceNote()) },
            async { session.transcribe(floatArrayOf(0f, 0f), TranscriptionRequest.liveCall()) },
        ).awaitAll()
        assertTrue(results.all { it.text == "ok" })
        assertEquals(1, engine.maxConcurrentTranscriptions)
        assertEquals(1, engine.prepareCalls)
    }

    @Test
    fun aFakeEngineNeverMarksWhisperInstalled() = runTest {
        val prefs = FakeSharedPreferences()
        val whisper = TranscriptionSession(IdEngine(WhisperCppEngine.ENGINE_ID), prefs, StorageSeal())
        val before = whisper.isPrepared
        val fake = TranscriptionSession(SlowEngine(), prefs, StorageSeal())
        fake.prepare(whisper.selectedModel)
        assertTrue(fake.isPrepared)
        assertEquals(before, whisper.isPrepared)
        assertFalse(prefs.contains(TranscriptionSession.readyKey(WhisperCppEngine.ENGINE_ID)))
        assertEquals("base", prefs.getString(TranscriptionSession.readyKey("fake"), null))
    }

    @Test
    fun liveCallProfileIsStricterOnSilence() {
        assertTrue(TranscriptionProfile.liveCall.noSpeechThreshold > TranscriptionProfile.voiceNote.noSpeechThreshold)
    }

    @Test
    fun aVoiceNoteCoversTheWholeRecording() {
        val plan = WhisperDecodePlan.make(TranscriptionRequest.voiceNote())
        assertTrue(plan.keepTimestamps)
        assertEquals(0f, plan.tailClipSeconds)
        assertFalse(plan.useVoiceActivityChunking)
        assertEquals(plan, WhisperDecodePlan.make(TranscriptionRequest.voiceNote(language = "ja")))
    }

    @Test
    fun theOpeningLanguageTokenWins() {
        assertEquals("ja", WhisperLanguageToken.code("<|ja|>"))
        assertEquals("zh", WhisperLanguageToken.code("<|zh|>"))
        assertNull(WhisperLanguageToken.code("<|0.00|>"))
        assertNull(WhisperLanguageToken.code("<|transcribe|>"))
        assertEquals("ko", WhisperLanguageToken.firstCode(listOf("<|startoftranscript|>", "<|ko|>", "<|en|>")))
    }

    @Test
    fun detectionPrefersThePersonsLanguagesUnlessAnotherIsFarLikelier() {
        val germanHeardAsDutch = mapOf("nl" to 0.45, "de" to 0.35, "en" to 0.1, "af" to 0.1)
        assertEquals("de", SpokenLanguagePick.pick(germanHeardAsDutch, listOf("de", "en")))
        assertEquals("nl", SpokenLanguagePick.pick(germanHeardAsDutch, emptyList()))
        val spanish = mapOf("es" to 0.92, "pt" to 0.04, "de" to 0.02, "en" to 0.02)
        assertEquals("es", SpokenLanguagePick.pick(spanish, listOf("de", "en")))
        val justOver = mapOf("fr" to 0.5, "de" to 0.5 / SpokenLanguagePick.OUTSIDE_CANDIDATE_ODDS - 0.001)
        assertEquals("fr", SpokenLanguagePick.pick(justOver, listOf("de")))
        assertEquals("no", SpokenLanguagePick.pick(mapOf("da" to 0.5, "no" to 0.4), listOf("nb", "en")))
        assertEquals("de", SpokenLanguagePick.pick(mapOf("fr" to 0.3, "de" to 0.3), emptyList()))
        assertNull(SpokenLanguagePick.pick(emptyMap(), listOf("de")))
    }

    @Test
    fun chatHistorySettlesAnUnsureNoteButNotAClearOne() {
        val german = mapOf("de" to 40.0)
        // A short German note Whisper leans English on: the German chat settles it.
        val unsure = mapOf("en" to 0.5, "de" to 0.35, "nl" to 0.15)
        assertEquals("en", SpokenLanguagePick.pick(unsure, listOf("en", "de")))
        assertEquals("de", SpokenLanguagePick.pick(unsure, listOf("en", "de"), german))
        // A clear English note in the same chat stays English.
        val english = mapOf("en" to 0.9, "de" to 0.08, "nl" to 0.02)
        assertEquals("en", SpokenLanguagePick.pick(english, listOf("en", "de"), german))
    }

    @Test
    fun aWrongLanguageInTheHistoryCannotOverrideClearAudio() {
        // English heard clearly, but a misdetected note once taught this chat Turkish.
        val english = mapOf("en" to 0.93, "tr" to 0.01, "de" to 0.06)
        assertEquals("en", SpokenLanguagePick.pick(english, listOf("en", "de"), mapOf("tr" to 10.0)))
    }

    @Test
    fun aSingleNoteOfHistoryOnlyNudges() {
        val unsure = mapOf("en" to 0.5, "de" to 0.3)
        assertEquals("en", SpokenLanguagePick.pick(unsure, listOf("en", "de"), mapOf("de" to 0.5)))
        assertEquals("de", SpokenLanguagePick.pick(unsure, listOf("en", "de"), mapOf("de" to 3.0)))
    }

    @Test
    fun theEngineReportsWhatTheAudioGaveTheLanguage() = runBlocking {
        val harness = tinyEngine()
        harness.engine.prepare(TranscriptionModelId.Base)
        val runner = harness.runners.single()
        runner.probabilities = mapOf("en" to 0.55, "de" to 0.4, "nl" to 0.05)

        val weighed = harness.engine.transcribe(
            floatArrayOf(0f),
            TranscriptionRequest.voiceNote(candidateLanguages = listOf("en", "de"), languageHistory = mapOf("de" to 5.0)),
        )
        assertEquals("de", weighed.language)
        assertEquals(0.4, weighed.languageProbability ?: error("no probability"), 1e-9)

        val pinned = harness.engine.transcribe(floatArrayOf(0f), TranscriptionRequest.voiceNote(language = "fr"))
        assertNull(pinned.languageProbability)
    }

    @Test
    fun theDetectedLanguageHonoursTheRequestsCandidates() = runBlocking {
        val harness = tinyEngine()
        harness.engine.prepare(TranscriptionModelId.Base)
        val runner = harness.runners.single()
        runner.probabilities = mapOf("nl" to 0.5, "de" to 0.3, "en" to 0.2)

        val preferred = harness.engine.transcribe(
            floatArrayOf(0f),
            TranscriptionRequest.voiceNote(candidateLanguages = listOf("de", "en")),
        )
        assertEquals("de", runner.options.last().language)
        assertEquals("de", preferred.language)

        val open = harness.engine.transcribe(floatArrayOf(0f), TranscriptionRequest.voiceNote())
        assertEquals("nl", runner.options.last().language)
        assertEquals("nl", open.language)
    }

    @Test
    fun theLanguageTheNoteOpenedWithIsTheOneReported() {
        assertEquals("fr", WhisperReportedLanguage.choose(forced = "fr", openingToken = "en", reported = "de"))
        assertEquals("de", WhisperReportedLanguage.choose(forced = null, openingToken = "de", reported = "en"))
        assertEquals("de", WhisperReportedLanguage.choose(forced = null, openingToken = null, reported = "german"))
        assertEquals("en", WhisperReportedLanguage.choose(forced = "en", openingToken = "ja", reported = "ja"))
        assertEquals("de", WhisperReportedLanguage.code("german"))
        assertNull(WhisperReportedLanguage.code("<|ja|>"))
    }

    @Test
    fun aLanguageProbeAndALiveChunkDoNotWalkTheFile() {
        val probe = WhisperDecodePlan.make(TranscriptionRequest.detectLanguage())
        assertFalse(probe.keepTimestamps)
        assertFalse(probe.useVoiceActivityChunking)

        val call = WhisperDecodePlan.make(TranscriptionRequest.liveCall())
        assertFalse(call.keepTimestamps)
        assertTrue(call.useVoiceActivityChunking)
        assertEquals(TranscriptionProfile.liveCall.windowClipTime, call.tailClipSeconds)
    }

    @Test
    fun modelCatalogIsStableForSettings() {
        assertEquals(TranscriptionModelId.Base, TranscriptionModelId.DEFAULT)
        assertEquals(setOf("base", "small"), TranscriptionModelId.entries.map { it.raw }.toSet())
        assertEquals("base", WhisperModelFile.DEFAULT.id)
        assertEquals(setOf("base", "small"), WhisperModelFile.entries.map { it.id }.toSet())
    }

    /** Public weights live in `noBackupFilesDir/whisper` and survive the settings wipe; language stats do not. */
    @Test
    fun modelWeightsAreKeptAndLanguageStatsAreNot() = runTest {
        assertEquals("whisper", WhisperModelStore.DIRECTORY)
        val fixture = WipeFixture(temp.root)
        fixture.seedAccount()
        assertTrue(fixture.exists("no_backup/whisper/ggml-base-q5_1.bin"))
        assertTrue(fixture.exists("no_backup/shroud/voice/language-stats.sealed"))

        fixture.wipe.wipeSettings()

        assertTrue(fixture.exists("no_backup/whisper/ggml-base-q5_1.bin"))
        assertFalse(fixture.exists("no_backup/shroud/voice/language-stats.sealed"))
        val voice = fixture.prefs.open(PrefsFiles.VOICE)
        assertEquals("base", voice.getString("transcription.model", null))
        assertEquals("base", voice.getString("transcription.whispercpp.ready", null))
        assertNull(voice.getString("transcription.locale", null))
    }

    @Test
    fun whisperCppDetectsThenForcesAndAPinnedLanguageSkipsDetection() = runBlocking {
        val harness = tinyEngine()
        harness.engine.prepare(TranscriptionModelId.Base)
        val runner = harness.runners.single()

        val detected = harness.engine.transcribe(floatArrayOf(0f), TranscriptionRequest.voiceNote())
        assertEquals(listOf(1), runner.detectedSamples)
        assertEquals("de", runner.options.single().language)
        assertFalse(runner.options.single().noTimestamps)
        assertEquals(0, runner.options.single().durationMs)
        assertEquals(0, runner.options.single().offsetMs)
        assertEquals(0.5f, runner.options.single().noSpeechThold)
        assertEquals("de", detected.language)

        runner.options.clear()
        runner.detectedSamples.clear()
        val pinned = harness.engine.transcribe(
            floatArrayOf(0f),
            TranscriptionRequest.voiceNote(language = "fr"),
        )
        assertTrue(runner.detectedSamples.isEmpty())
        assertEquals("fr", runner.options.single().language)
        assertEquals("fr", pinned.language)
    }

    @Test
    fun aLanguageProbeClipsEightSecondsAndDropsTimestamps() {
        val options = WhisperCppEngine.decodeOptions(TranscriptionRequest.detectLanguage(), null)
        assertTrue(options.noTimestamps)
        assertEquals(8_000, options.durationMs)
        assertEquals(0, options.offsetMs)

        val live = WhisperCppEngine.decodeOptions(TranscriptionRequest.liveCall(), "en")
        assertTrue(live.noTimestamps)
        assertEquals(0, live.durationMs)
        assertEquals(0.75f, live.noSpeechThold)
    }

    @Test
    fun releasingTheContextReloadsFromTheInstalledFileWithoutADownload() = runBlocking {
        val harness = tinyEngine()
        harness.engine.prepare(TranscriptionModelId.Base)
        assertEquals(1, harness.runners.size)
        harness.engine.release()
        assertTrue(harness.runners.single().closed)
        harness.engine.transcribe(floatArrayOf(0f), TranscriptionRequest.voiceNote(language = "en"))
        assertEquals(2, harness.runners.size)
        assertTrue(harness.installed.isFile)
        assertEquals(4L, harness.installed.length())
    }

    @Test
    fun aMissingLibraryIsUnavailableAndAMissingModelIsNot() = runBlocking {
        val missingLibrary = tinyEngine(load = { throw WhisperException("the library is not available") })
        try {
            missingLibrary.engine.prepare(TranscriptionModelId.Base)
            error("expected unavailable")
        } catch (e: TranscriptionEngineError.Unavailable) {
            assertEquals(TranscriptionEngineError.UNAVAILABLE, e.message)
        }

        val fullDisk = tinyEngine(install = false, space = 0L)
        try {
            fullDisk.engine.prepare(TranscriptionModelId.Base)
            error("expected the model to be unavailable")
        } catch (e: TranscriptionEngineError.ModelUnavailable) {
            assertEquals(TranscriptionEngineError.MODEL_UNAVAILABLE, e.message)
        }
    }

    @Test
    fun anAbortWhileTheCallIsActiveFailsTheNote() = runBlocking {
        val harness = tinyEngine()
        harness.engine.prepare(TranscriptionModelId.Base)
        harness.runners.single().failure = WhisperAbortedException()
        try {
            harness.engine.transcribe(floatArrayOf(0f), TranscriptionRequest.voiceNote(language = "en"))
            error("expected a failure")
        } catch (e: TranscriptionEngineError.Failed) {
            assertEquals(TranscriptionEngineError.FAILED, e.message)
        }
    }

    private fun tinyEngine(
        load: ((File) -> WhisperRunner)? = null,
        install: Boolean = true,
        space: Long = Long.MAX_VALUE,
    ): TinyHarness {
        val directory = temp.dir("whisper-${System.nanoTime()}")
        val model = TinyModel
        val installed = File(directory, model.fileName)
        if (install) installed.writeBytes(byteArrayOf(1, 2, 3, 4))
        val runners = mutableListOf<ScriptedRunner>()
        val store = WhisperModelStore(
            directory = directory,
            http = OkHttpClient.Builder().addInterceptor { error("the test must not download a model") }.build(),
            usableSpace = { space },
            catalog = listOf(model),
        )
        val engine = WhisperCppEngine(
            models = store,
            specFor = { model },
            load = load ?: { ScriptedRunner().also { runners += it } },
            dispatcher = Dispatchers.Unconfined,
        )
        return TinyHarness(engine, runners, installed)
    }

    private class TinyHarness(
        val engine: WhisperCppEngine,
        val runners: List<ScriptedRunner>,
        val installed: File,
    )

    private object TinyModel : WhisperModelSpec {
        override val fileName = "tiny.bin"
        override val sizeBytes = 4L
        override val sha256 = "00"
    }

    private class ScriptedRunner : WhisperRunner {
        val detectedSamples = mutableListOf<Int>()
        val options = mutableListOf<WhisperDecodeOptions>()
        var closed = false
        var failure: Exception? = null
        var probabilities = mapOf("de" to 0.9, "en" to 0.06, "nl" to 0.04)
        var reported = "de"

        override fun languageProbabilities(pcm16k: FloatArray, threads: Int): Map<String, Double>? {
            detectedSamples += pcm16k.size
            return probabilities
        }

        override fun transcribe(pcm16k: FloatArray, options: WhisperDecodeOptions, cancellation: WhisperCancellation?): WhisperRun {
            this.options += options
            failure?.let { throw it }
            return WhisperRun(emptyList(), reported)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeEngine : TranscriptionEngine {
        override val id = "fake"
        var prepareCalls = 0
        var prepared: TranscriptionModelId? = null
        var output = TranscriptionOutput("", null, 0.0)
        var lastRequest: TranscriptionRequest? = null

        override suspend fun prepare(model: TranscriptionModelId, progress: ((Double) -> Unit)?) {
            prepareCalls += 1
            progress?.invoke(1.0)
            prepared = model
        }

        override suspend fun transcribe(pcm16k: FloatArray, request: TranscriptionRequest): TranscriptionOutput {
            lastRequest = request
            return output
        }
    }

    private class IdEngine(override val id: String) : TranscriptionEngine {
        override suspend fun prepare(model: TranscriptionModelId, progress: ((Double) -> Unit)?) = Unit

        override suspend fun transcribe(pcm16k: FloatArray, request: TranscriptionRequest) =
            TranscriptionOutput("", null, 0.0)
    }

    private class SlowEngine(private var failingPrepares: Int = 0) : TranscriptionEngine {
        override val id = "fake"
        var prepareCalls = 0
        var maxConcurrentTranscriptions = 0
        private var running = 0

        override suspend fun prepare(model: TranscriptionModelId, progress: ((Double) -> Unit)?) {
            prepareCalls += 1
            delay(80)
            if (failingPrepares > 0) {
                failingPrepares -= 1
                throw TranscriptionEngineError.ModelUnavailable()
            }
            progress?.invoke(1.0)
        }

        override suspend fun transcribe(pcm16k: FloatArray, request: TranscriptionRequest): TranscriptionOutput {
            running += 1
            maxConcurrentTranscriptions = maxOf(maxConcurrentTranscriptions, running)
            try {
                delay(60)
            } finally {
                running -= 1
            }
            return TranscriptionOutput("ok", "en", 1.0)
        }
    }
}
