package de.corespace.shroud.core.transcription

import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import de.corespace.shroud.testing.SealedTestKey
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Transcript cleanup and [VoiceTranscriber] (`ios/shroudTests/VoiceTranscriberTests.swift` for the
 * pure cases). The engine is a fake: these tests do not load whisper.cpp or download a model.
 * Download progress and cancel are already covered by [WhisperModelStoreTest].
 */
class VoiceTranscriberTests {
    @get:Rule val temp = TempDirRule()

    @Test
    fun punctuationOnlyTranscriptsAreRejected() {
        assertEquals("", VoiceTranscript.cleaned(", , , ,"))
        assertEquals("", VoiceTranscript.cleaned(","))
        assertEquals("", VoiceTranscript.cleaned("... — !?"))
        assertEquals("", VoiceTranscript.cleaned("   "))
        assertEquals("", VoiceTranscript.cleaned(""))
    }

    @Test
    fun realSpeechSurvivesTheGate() {
        assertEquals("Hallo, wie geht es dir?", VoiceTranscript.cleaned("Hallo, wie geht es dir?"))
        assertTrue(VoiceTranscript.containsSpeech("Ok"))
        assertFalse(VoiceTranscript.containsSpeech("a"))
    }

    @Test
    fun cleanedCollapsesSegmentJoinWhitespace() {
        assertEquals("Hello there world", VoiceTranscript.cleaned("Hello   there\n\nworld"))
    }

    @Test
    fun prepareModelIsSingleFlightAndReportsDownloadProgress() = runTest {
        val rig = rig()
        rig.engine.prepareDelayMs = 80
        val results = listOf(
            async { rig.voice.prepareModel() },
            async { rig.voice.prepareModel() },
            async { rig.voice.prepareModel() },
        ).awaitAll()
        assertEquals(listOf(true, true, true), results)
        assertEquals(1, rig.engine.prepareCalls)
        val during = rig.duringDownload
        assertEquals(TranscriptionInstallState.Phase.Downloading, during?.phase)
        assertEquals(0.4, during?.fractionCompleted ?: 0.0, 0.0)
        assertEquals(true, during?.isDeterminate)
        assertEquals("Whisper", during?.languageName)
        assertEquals(TranscriptionInstallState.Idle, rig.voice.install.value)
        assertTrue(rig.voice.modelIsInstalled())
    }

    @Test
    fun aFailedPrepareCanBeRetriedAndClearsTheInstall() = runTest {
        val rig = rig()
        rig.engine.failPrepare = TranscriptionEngineError.ModelUnavailable()
        assertFalse(rig.voice.prepareModel())
        assertEquals(TranscriptionInstallState.Idle, rig.voice.install.value)
        rig.engine.failPrepare = null
        assertTrue(rig.voice.prepareModel())
        assertEquals(2, rig.engine.prepareCalls)
    }

    @Test
    fun transcribeShowsTheNoteThenReturnsToIdle() = runTest {
        val rig = rig()
        val tracking = UUID.randomUUID()
        val text = rig.voice.transcribe(byteArrayOf(1, 2), "audio/mp4", tracking = tracking)
        assertEquals("Hallo, wie geht es dir heute Abend", text)
        assertEquals(TranscriptionInstallState.Phase.Downloading, rig.phases.first())
        assertEquals(TranscriptionInstallState.Phase.Transcribing, rig.duringTranscribe?.phase)
        assertEquals(tracking, rig.duringTranscribe?.messageId)
        assertEquals(1.0, rig.duringTranscribe?.fractionCompleted ?: 0.0, 0.0)
        assertEquals(TranscriptionInstallState.Idle, rig.voice.install.value)
    }

    @Test
    fun handOffMovesTheInFlightMessageId() = runTest {
        val rig = rig()
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        var moved: UUID? = null
        rig.engine.onTranscribe = {
            rig.voice.handOff(from, to)
            moved = rig.voice.install.value.messageId
        }
        rig.voice.transcribe(byteArrayOf(1), "audio/mp4", tracking = from)
        assertEquals(to, moved)
        assertEquals(TranscriptionInstallState.Idle, rig.voice.install.value)
    }

    @Test
    fun aFailureTellsTheUserInASentence() = runTest {
        val rig = rig()
        rig.engine.outputFor = { throw TranscriptionEngineError.Unavailable() }
        try {
            rig.voice.transcribe(byteArrayOf(1), "audio/mp4")
            error("expected TranscribeException")
        } catch (e: TranscribeException) {
            assertFalse(e.message.isNullOrBlank())
            assertEquals(TranscriptionEngineError.UNAVAILABLE, e.message)
        }
        assertEquals(TranscriptionInstallState.Idle, rig.voice.install.value)
    }

    @Test
    fun aDecoderFailureDoesNotNameTheAudio() = runTest {
        val rig = rig(decode = { _, _ -> throw IOException("boom /tmp/secret.m4a") })
        try {
            rig.voice.transcribe(byteArrayOf(1), "audio/mp4")
            error("expected TranscribeException")
        } catch (e: TranscribeException) {
            assertEquals(TranscriptionEngineError.FAILED, e.message)
            assertFalse(e.message!!.contains("secret"))
            assertFalse(e.message!!.contains("boom"))
        }
    }

    @Test
    fun anEmptyTranscriptIsSuccess() = runTest {
        val rig = rig()
        rig.engine.outputFor = { TranscriptionOutput(", , ,", "en", 0.99) }
        assertEquals("", rig.voice.transcribe(byteArrayOf(1), "audio/mp4"))
        assertEquals(TranscriptionInstallState.Idle, rig.voice.install.value)
    }

    @Test
    fun languageOverrideWinsOverConversationStats() = runTest {
        val rig = rig()
        val chat = UUID.randomUUID()
        repeat(4) { rig.memory.record("de", chat, 1.0) }
        rig.voice.languageOverride = Locale.forLanguageTag("fr")
        val text = rig.voice.transcribe(
            byteArrayOf(1),
            "audio/mp4",
            hints = listOf("Ada"),
            conversationId = chat,
        )
        assertEquals("Hallo, wie geht es dir heute Abend", text)
        assertEquals(listOf("fr"), rig.engine.languages)
        assertEquals(listOf(listOf("Ada")), rig.engine.hints)
    }

    @Test
    fun conversationStatsWeighDetectionButNeverForceALanguage() = runTest {
        val rig = rig()
        val chat = UUID.randomUUID()
        repeat(4) { rig.memory.record("de", chat, 1.0) }
        rig.voice.transcribe(byteArrayOf(1), "audio/mp4", conversationId = chat)
        // One decode, with Whisper detecting; the chat's German only weighs that detection.
        assertEquals(listOf<String?>(null), rig.engine.languages)
        assertEquals(setOf("de"), rig.engine.histories.single().keys)
    }

    @Test
    fun aNoteTheAudioSettledTeachesTheChat() = runTest {
        val rig = rig()
        val chat = UUID.randomUUID()
        rig.engine.outputFor = { TranscriptionOutput("See you at eight tonight", "en", 0.9, languageProbability = 0.97) }
        rig.voice.transcribe(byteArrayOf(1), "audio/mp4", conversationId = chat)
        assertEquals(setOf("en"), rig.memory.history(chat).keys)
    }

    @Test
    fun aNoteTheHistoryCarriedTeachesNothing() = runTest {
        val rig = rig()
        val chat = UUID.randomUUID()
        rig.engine.outputFor = { TranscriptionOutput("Ja, mach ich", "de", 0.9, languageProbability = 0.35) }
        rig.voice.transcribe(byteArrayOf(1), "audio/mp4", conversationId = chat)
        assertTrue(rig.memory.history(chat).isEmpty())
    }

    @Test
    fun aPinnedLanguageTeachesNothing() = runTest {
        val rig = rig()
        val chat = UUID.randomUUID()
        rig.voice.languageOverride = Locale.forLanguageTag("fr")
        rig.voice.transcribe(byteArrayOf(1), "audio/mp4", conversationId = chat)
        assertTrue(rig.memory.history(chat).isEmpty())
    }

    @Test
    fun detectionIsAskedToPreferTheDevicesLanguagesAndEnglish() = runTest {
        val rig = rig()
        rig.language.preferredLanguageTagsOverride = listOf("de-DE")
        rig.language.currentLocaleOverride = Locale.GERMANY
        rig.voice.transcribe(byteArrayOf(1), "audio/mp4")
        assertNull(rig.engine.languages.first())
        assertEquals(listOf("de", "en"), rig.engine.candidates.first())
    }

    @Test
    fun transcribeWorksWhileLanguageMemoryIsLocked() = runTest {
        val rig = rig(unlock = false)
        val chat = UUID.randomUUID()
        val text = rig.voice.transcribe(byteArrayOf(1), "audio/mp4", conversationId = chat)
        assertEquals("Hallo, wie geht es dir heute Abend", text)
        assertFalse(rig.file.exists())
        assertTrue(rig.memory.history(chat).isEmpty())
    }

    @Test
    fun aMissingLibraryDoesNotDownloadOrInstall() = runTest {
        val rig = rig(nativeLoaded = false)
        assertFalse(rig.voice.isAvailable.value)
        assertFalse(rig.voice.prepareModel())
        assertEquals(0, rig.engine.prepareCalls)
        try {
            rig.voice.transcribe(byteArrayOf(1), "audio/mp4")
            error("expected TranscribeException")
        } catch (e: TranscribeException) {
            assertEquals(TranscriptionEngineError.UNAVAILABLE, e.message)
        }
        assertEquals(TranscriptionInstallState.Idle, rig.voice.install.value)
    }

    @Test
    fun theLanguageOverrideIsPersistedAndThePickerListsWhisperLanguages() = runTest {
        val rig = rig()
        assertNull(rig.voice.languageOverride)
        rig.voice.languageOverride = Locale.forLanguageTag("de-DE")
        assertEquals("de", rig.voice.languageOverride?.language)
        assertEquals("de-DE", rig.prefs.getString(TranscriptionLanguage.LOCALE_KEY, null))
        rig.voice.languageOverride = null
        assertNull(rig.voice.languageOverride)
        assertFalse(rig.prefs.contains(TranscriptionLanguage.LOCALE_KEY))
        assertEquals(TranscriptionLanguage.WHISPER_CODES.size, rig.voice.availableLocales().size)
        assertTrue(rig.voice.availableLocales().any { it.language == "de" })
        assertEquals(true, rig.voice.isAvailable.value)
    }

    @Test
    fun cancellationIsNotATranscribeException() = runTest {
        val rig = rig()
        rig.engine.outputFor = { throw CancellationException("stop") }
        try {
            rig.voice.transcribe(byteArrayOf(1), "audio/mp4")
            error("expected cancellation")
        } catch (_: TranscribeException) {
            error("cancellation was reported as a transcription failure")
        } catch (_: CancellationException) {
            // The caller cancelled; the note is not a failed transcription.
        }
        assertEquals(TranscriptionInstallState.Idle, rig.voice.install.value)
    }

    private fun rig(
        nativeLoaded: Boolean = true,
        unlock: Boolean = true,
        decode: Pcm16kSource = Pcm16kSource { _, _ -> FloatArray(16_000 * 8) },
    ): Rig {
        val file = temp.file("voice-${System.nanoTime()}/language-stats.sealed")
        val state = SealedLocalState()
        val memory = TranscriptionLanguageMemory(file, state, StorageSeal())
        if (unlock) state.unlock(SealedTestKey.bytes())
        val prefs = FakeSharedPreferences()
        val language = TranscriptionLanguage(prefs, StorageSeal())
        language.preferredLanguageTagsOverride = listOf("en-US")
        language.currentLocaleOverride = Locale.US
        val engine = ScriptEngine()
        val session = TranscriptionSession(engine, prefs, StorageSeal())
        val voice = VoiceTranscriber(session, language, memory, nativeLoaded, decode)
        engine.install = { voice.install.value }
        return Rig(engine, voice, memory, language, prefs, file, state)
    }

    private class Rig(
        val engine: ScriptEngine,
        val voice: VoiceTranscriber,
        val memory: TranscriptionLanguageMemory,
        val language: TranscriptionLanguage,
        val prefs: FakeSharedPreferences,
        val file: File,
        val state: SealedLocalState,
    ) {
        val duringDownload: TranscriptionInstallState? get() = engine.duringDownload
        val duringTranscribe: TranscriptionInstallState? get() = engine.duringTranscribe
        val phases: List<TranscriptionInstallState.Phase> get() = engine.phases
    }

    private class ScriptEngine : TranscriptionEngine {
        override val id = "fake"
        var prepareCalls = 0
        var prepareDelayMs = 0L
        var failPrepare: Exception? = null
        val languages = mutableListOf<String?>()
        val hints = mutableListOf<List<String>>()
        val candidates = mutableListOf<List<String>>()
        val histories = mutableListOf<Map<String, Double>>()
        val phases = mutableListOf<TranscriptionInstallState.Phase>()
        var duringDownload: TranscriptionInstallState? = null
        var duringTranscribe: TranscriptionInstallState? = null
        var onTranscribe: (() -> Unit)? = null
        var install: () -> TranscriptionInstallState = { TranscriptionInstallState.Idle }
        var outputFor: (TranscriptionRequest) -> TranscriptionOutput = { request ->
            TranscriptionOutput("Hallo, wie geht es dir heute Abend", request.language ?: "en", 0.9)
        }

        override suspend fun prepare(model: TranscriptionModelId, progress: ((Double) -> Unit)?) {
            prepareCalls += 1
            progress?.invoke(0.4)
            val state = install()
            if (duringDownload == null && state.phase == TranscriptionInstallState.Phase.Downloading) {
                duringDownload = state
            }
            phases += state.phase
            if (prepareDelayMs > 0) delay(prepareDelayMs)
            failPrepare?.let { throw it }
        }

        override suspend fun transcribe(pcm16k: FloatArray, request: TranscriptionRequest): TranscriptionOutput {
            languages += request.language
            hints += request.hints
            candidates += request.candidateLanguages
            histories += request.languageHistory
            duringTranscribe = install()
            onTranscribe?.invoke()
            return outputFor(request)
        }
    }
}
