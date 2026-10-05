package de.corespace.shroud.core.transcription

import de.corespace.shroud.core.auth.WipeFixture
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeSharedPreferences
import de.corespace.shroud.testing.SealedTestKey
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Per-chat language memory, the device languages, and what a note teaches
 * (`ios/shroudTests/TranscriptionLanguageMemoryTests.swift`). Plaintext defaults are not migrated.
 * Peer keys in the sealed file are lowercase UUIDs.
 */
class TranscriptionLanguageMemoryTests {
    @get:Rule val temp = TempDirRule()

    @Test
    fun aFreshInstallHasNoHistory() {
        val memory = unlocked()
        assertTrue(memory.history(null).isEmpty())
        assertTrue(memory.history(UUID.randomUUID()).isEmpty())
    }

    @Test
    fun repeatedObservationsBuildAHistory() {
        val memory = unlocked()
        val peer = UUID.randomUUID()
        repeat(4) { memory.record("de", peer, 1.0) }
        val history = memory.history(peer)
        assertEquals(setOf("de"), history.keys)
        assertTrue(history.getValue("de") > 3.0)
    }

    @Test
    fun historyIsScopedPerConversation() {
        val memory = unlocked()
        val german = UUID.randomUUID()
        val english = UUID.randomUUID()
        repeat(4) { memory.record("de", german, 1.0) }
        repeat(4) { memory.record("en", english, 1.0) }
        assertEquals(setOf("de"), memory.history(german).keys)
        assertEquals(setOf("en"), memory.history(english).keys)
    }

    @Test
    fun aNewConversationInheritsTheGlobalHabit() {
        val memory = unlocked()
        val known = UUID.randomUUID()
        repeat(5) { memory.record("de", known, 1.0) }
        assertEquals(setOf("de"), memory.history(UUID.randomUUID()).keys)
        assertEquals(setOf("de"), memory.history(null).keys)
    }

    @Test
    fun decayLetsAConversationChangeLanguage() {
        val memory = unlocked()
        val peer = UUID.randomUUID()
        repeat(5) { memory.record("de", peer, 1.0) }
        repeat(6) { memory.record("en", peer, 1.0) }
        val history = memory.history(peer)
        assertTrue(history.getValue("en") > history.getValue("de"))
    }

    @Test
    fun zeroWeightObservationsAreIgnored() {
        val memory = unlocked()
        val peer = UUID.randomUUID()
        memory.record("de", peer, 0.0)
        assertTrue(memory.history(peer).isEmpty())
    }

    @Test
    fun onlyWhatTheAudioSettledTeachesTheMemory() {
        // Whisper unsure: history or the device carried the pick, so nothing is learned.
        assertEquals(0.0, VoiceTranscript.learningWeight(10.0, 0.5), 0.0)
        assertEquals(0.0, VoiceTranscript.learningWeight(10.0, 0.3), 0.0)
        assertEquals(0.0, VoiceTranscript.learningWeight(10.0, Double.NaN), 0.0)
        assertEquals(1.0, VoiceTranscript.learningWeight(10.0, 0.95), 0.0)
        assertTrue(VoiceTranscript.learningWeight(10.0, 0.7) in 0.4..0.6)
        // Short notes teach less than long ones.
        assertTrue(VoiceTranscript.learningWeight(2.0, 0.95) < VoiceTranscript.learningWeight(8.0, 0.95))
    }

    @Test
    fun overrideIsThePinnedLanguage() {
        val (_, language) = language()
        language.preferredLanguageTagsOverride = listOf("en-DE")
        assertNull(language.pinnedLanguage())
        language.override = Locale.forLanguageTag("fr")
        assertEquals("fr", language.pinnedLanguage())
    }

    @Test
    fun automaticTranscriptionIsOffUntilTurnedOn() {
        val prefs = FakeSharedPreferences()
        val language = TranscriptionLanguage(prefs, StorageSeal())
        assertFalse(language.transcribesAutomatically)
        language.transcribesAutomatically = true
        assertTrue(language.transcribesAutomatically)
        assertTrue(prefs.getBoolean(TranscriptionLanguage.AUTOMATIC_KEY, false))
        language.transcribesAutomatically = false
        assertFalse(TranscriptionLanguage(prefs, StorageSeal()).transcribesAutomatically)
    }

    @Test
    fun languageOverrideRoundTripsAndClears() {
        val (_, language) = language()
        language.override = Locale.forLanguageTag("de-DE")
        assertEquals("de", language.override?.language)
        language.override = null
        assertNull(language.override)
    }

    @Test
    fun englishUIInGermanyCountsGermanAsADeviceLanguage() {
        val (_, language) = language()
        language.preferredLanguageTagsOverride = listOf("en-DE")
        language.currentLocaleOverride = Locale.forLanguageTag("en-DE")
        assertEquals(listOf("en", "de"), language.deviceLanguages())
    }

    @Test
    fun conversationMemoryIsNotADeviceLanguage() {
        val (memory, language) = language()
        val peer = UUID.randomUUID()
        repeat(4) { memory.record("tr", peer, 1.0) }
        language.preferredLanguageTagsOverride = listOf("en-DE")
        language.currentLocaleOverride = Locale.forLanguageTag("en-DE")
        assertEquals(listOf("en", "de"), language.deviceLanguages())
    }

    @Test
    fun detectionCandidatesAddEnglish() {
        assertEquals(listOf("de", "en"), TranscriptionLanguage.detectionCandidates(listOf("de")))
        assertEquals(listOf("en", "de"), TranscriptionLanguage.detectionCandidates(listOf("en", "de")))
        assertEquals(emptyList<String>(), TranscriptionLanguage.detectionCandidates(emptyList()))
    }

    @Test
    fun normalizeMapsWhisperLanguageNames() {
        assertEquals("de", TranscriptionLanguage.normalize("german"))
        assertEquals("de", TranscriptionLanguage.normalize("DE"))
        assertEquals("en", TranscriptionLanguage.normalize("en"))
        @Suppress("DEPRECATION") // the legacy JDK codes the mapper exists for
        val hebrew = Locale("iw")
        @Suppress("DEPRECATION")
        val indonesian = Locale("in")
        assertEquals("he", TranscriptionLanguage.languageCode(hebrew))
        assertEquals("id", TranscriptionLanguage.languageCode(indonesian))
    }

    @Test
    fun lockedMemoryReadsEmptyAndDropsWrites() {
        val file = temp.file("shroud/voice/language-stats.sealed")
        val state = SealedLocalState()
        val memory = TranscriptionLanguageMemory(file, state, StorageSeal())
        val peer = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        state.unlock(SealedTestKey.bytes())
        repeat(4) { memory.record("de", peer, 1.0) }
        state.lock()

        assertTrue(memory.history(peer).isEmpty())
        val before = file.readBytes()
        memory.record("fr", peer, 5.0)
        assertArrayEquals(before, file.readBytes())

        state.unlock(SealedTestKey.bytes())
        assertEquals(setOf("de"), memory.history(peer).keys)
    }

    @Test
    fun theSealedFileRoundTripsAndNeverNamesThePeer() {
        val file = temp.file("shroud/voice/language-stats.sealed")
        val state = SealedLocalState()
        val memory = TranscriptionLanguageMemory(file, state, StorageSeal())
        val peer = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        state.unlock(SealedTestKey.bytes())
        memory.record("de", peer, 1.0)

        val blob = file.readBytes()
        assertTrue(LocalHistoryCrypto.isSealedBlob(blob))
        val raw = String(blob, Charsets.ISO_8859_1)
        assertFalse(raw.contains(peer.toString()))
        assertFalse(raw.contains(peer.toString().uppercase()))
        assertNull(TranscriptionLanguageMemory.open(blob, ByteArray(32) { 0x11 }))
        val opened = TranscriptionLanguageMemory.open(blob, SealedTestKey.bytes())
        assertEquals(1.0, opened?.get(peer.toString())?.get("de") ?: error("sealed stats did not open"), 0.0)
        assertEquals(1.0, opened["*"]?.get("de") ?: error("global stats missing"), 0.0)

        val again = TranscriptionLanguageMemory.seal(
            mapOf(peer.toString() to mapOf("de" to 1.0)),
            SealedTestKey.bytes(),
        )
        assertEquals(1.0, TranscriptionLanguageMemory.open(again, SealedTestKey.bytes())?.get(peer.toString())?.get("de") ?: 0.0, 0.0)
    }

    @Test
    fun aWrongKeyReadsNothingAndDoesNotReplaceTheFile() {
        val file = temp.file("shroud/voice/language-stats.sealed")
        val state = SealedLocalState()
        val memory = TranscriptionLanguageMemory(file, state, StorageSeal())
        val peer = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        state.unlock(SealedTestKey.bytes())
        memory.record("de", peer, 1.0)
        state.lock()
        val snapshot = file.readBytes()

        state.unlock(ByteArray(32) { 0x11 })
        assertTrue(memory.history(peer).isEmpty())
        assertArrayEquals(snapshot, file.readBytes())
        assertEquals("de", TranscriptionLanguageMemory.open(snapshot, SealedTestKey.bytes())?.get(peer.toString())?.keys?.single())
    }

    @Test
    fun wipeDeletesTheSealedStatsAndTheLocaleButKeepsTheModel() = runTest {
        val fixture = WipeFixture(temp.root)
        fixture.seedAccount()
        val file = File(fixture.locations.voiceDir, TranscriptionLanguageMemory.FILE_NAME)
        val state = SealedLocalState()
        val memory = TranscriptionLanguageMemory(file, state, StorageSeal())
        state.unlock(SealedTestKey.bytes())
        memory.record("de", UUID.randomUUID(), 1.0)
        assertTrue(LocalHistoryCrypto.isSealedBlob(file.readBytes()))

        fixture.wipe.wipeSettings()

        assertFalse(file.exists())
        assertFalse(fixture.exists("no_backup/shroud/voice/language-stats.sealed"))
        val voice = fixture.prefs.open(PrefsFiles.VOICE)
        assertNull(voice.getString(TranscriptionLanguage.LOCALE_KEY, null))
        assertEquals("base", voice.getString("transcription.model", null))
        assertEquals("base", voice.getString("transcription.whispercpp.ready", null))
        assertTrue(fixture.exists("no_backup/whisper/ggml-base-q5_1.bin"))
    }

    private fun unlocked(): TranscriptionLanguageMemory = language().first

    private fun language(): Pair<TranscriptionLanguageMemory, TranscriptionLanguage> {
        val state = SealedLocalState()
        val memory = TranscriptionLanguageMemory(
            temp.file("lang-${System.nanoTime()}/language-stats.sealed"),
            state,
            StorageSeal(),
        )
        state.unlock(SealedTestKey.bytes())
        val language = TranscriptionLanguage(FakeSharedPreferences(), StorageSeal())
        language.preferredLanguageTagsOverride = listOf("en-US")
        language.currentLocaleOverride = Locale.US
        return memory to language
    }
}
