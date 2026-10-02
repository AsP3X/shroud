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
 * Per-chat language memory and the scoring that short notes lean on
 * (`ios/shroudTests/TranscriptionLanguageMemoryTests.swift`). Plaintext defaults are not migrated.
 * Peer keys in the sealed file are lowercase UUIDs.
 */
class TranscriptionLanguageMemoryTests {
    @get:Rule val temp = TempDirRule()

    @Test
    fun aFreshInstallHasNoOpinion() {
        val memory = unlocked()
        assertEquals(0.5, memory.prior("de", null), 0.0)
        assertNull(memory.expectedLanguage(null))
    }

    @Test
    fun repeatedObservationsBuildAPreference() {
        val memory = unlocked()
        val peer = UUID.randomUUID()
        repeat(4) { memory.record("de", peer, 1.0) }
        assertTrue(memory.prior("de", peer) > 0.8)
        assertTrue(memory.prior("en", peer) < 0.2)
        assertEquals("de", memory.expectedLanguage(peer))
    }

    @Test
    fun aSingleObservationOnlyNudges() {
        val memory = unlocked()
        val peer = UUID.randomUUID()
        memory.record("de", peer, 1.0)
        val prior = memory.prior("de", peer)
        assertTrue(prior > 0.5)
        assertTrue(prior < 0.8)
    }

    @Test
    fun historyIsScopedPerConversation() {
        val memory = unlocked()
        val german = UUID.randomUUID()
        val english = UUID.randomUUID()
        repeat(4) { memory.record("de", german, 1.0) }
        repeat(4) { memory.record("en", english, 1.0) }
        assertEquals("de", memory.expectedLanguage(german))
        assertEquals("en", memory.expectedLanguage(english))
        assertEquals("de", memory.expectedLanguage(german))
    }

    @Test
    fun aNewConversationInheritsTheGlobalHabit() {
        val memory = unlocked()
        val known = UUID.randomUUID()
        repeat(5) { memory.record("de", known, 1.0) }
        assertTrue(memory.prior("de", UUID.randomUUID()) > 0.5)
    }

    @Test
    fun decayLetsAConversationChangeLanguage() {
        val memory = unlocked()
        val peer = UUID.randomUUID()
        repeat(5) { memory.record("de", peer, 1.0) }
        assertEquals("de", memory.expectedLanguage(peer))
        repeat(6) { memory.record("en", peer, 1.0) }
        assertEquals("en", memory.expectedLanguage(peer))
    }

    @Test
    fun zeroWeightObservationsAreIgnored() {
        val memory = unlocked()
        val peer = UUID.randomUUID()
        memory.record("de", peer, 0.0)
        assertNull(memory.expectedLanguage(peer))
    }

    @Test
    fun onlyDecisiveResultsTeachTheMemory() {
        assertEquals(0.0, VoiceTranscript.learningWeight(10.0, 0.05), 0.0)
        val short = VoiceTranscript.learningWeight(1.5, 0.5)
        val long = VoiceTranscript.learningWeight(12.0, 0.5)
        assertTrue(short < long)
        assertTrue(long > 0.5)
    }

    @Test
    fun priorDecidesShortAudioButNotLongAudio() {
        val text = "Ja"
        val favouredShort = VoiceTranscript.score(text, 0.6, languageProbability = 0.5, prior = 0.9, audioSeconds = 2.0)
        val unfavouredShort = VoiceTranscript.score(text, 0.6, languageProbability = 0.5, prior = 0.1, audioSeconds = 2.0)
        assertTrue(favouredShort > unfavouredShort * 1.5)

        val favouredLong = VoiceTranscript.score(text, 0.6, languageProbability = 0.5, prior = 0.9, audioSeconds = 30.0)
        val unfavouredLong = VoiceTranscript.score(text, 0.6, languageProbability = 0.5, prior = 0.1, audioSeconds = 30.0)
        assertEquals(favouredLong, unfavouredLong, 0.0)
    }

    @Test
    fun confidentAudioOverridesAContraryPrior() {
        val againstPrior = VoiceTranscript.score(
            "Guten Morgen, ich melde mich später nochmal bei dir",
            0.92,
            languageProbability = 0.95,
            prior = 0.15,
            audioSeconds = 6.0,
        )
        val withPrior = VoiceTranscript.score(
            "Good morning",
            0.35,
            languageProbability = 0.4,
            prior = 0.85,
            audioSeconds = 6.0,
        )
        assertTrue(againstPrior > withPrior)
    }

    @Test
    fun languageIdentificationIsDiscountedOnVeryShortText() {
        val shortAgreeing = VoiceTranscript.score("Ja", 0.7, languageProbability = 0.95, audioSeconds = 2.0)
        val shortDisagreeing = VoiceTranscript.score("Ja", 0.7, languageProbability = 0.05, audioSeconds = 2.0)
        val ratioShort = shortAgreeing / shortDisagreeing.coerceAtLeast(Double.MIN_VALUE)
        val longText = "Guten Morgen, ich wollte dir nur schnell Bescheid geben dass es später wird"
        val longAgreeing = VoiceTranscript.score(longText, 0.7, languageProbability = 0.95, audioSeconds = 20.0)
        val longDisagreeing = VoiceTranscript.score(longText, 0.7, languageProbability = 0.05, audioSeconds = 20.0)
        val ratioLong = longAgreeing / longDisagreeing.coerceAtLeast(Double.MIN_VALUE)
        assertTrue(ratioLong > ratioShort)
    }

    @Test
    fun belowTheTrustFloorNothingIsLearned() {
        assertTrue(VoiceTranscript.MINIMUM_TRUSTED_SCORE > 0)
        val noisy = VoiceTranscript.score("a b", 0.1, languageProbability = 0.5, prior = 0.5, audioSeconds = 1.0)
        assertTrue(noisy < VoiceTranscript.MINIMUM_TRUSTED_SCORE)
        assertEquals(0.0, VoiceTranscript.learningWeight(1.0, noisy), 0.0)
    }

    @Test
    fun overrideIsTheOnlyDecodeHint() {
        val (memory, language) = language()
        val peer = UUID.randomUUID()
        repeat(4) { memory.record("de", peer, 1.0) }
        language.preferredLanguageTagsOverride = listOf("en-DE")
        language.override = Locale.forLanguageTag("fr")
        assertEquals(listOf("fr"), language.decodeHints(peer))
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
    fun englishUIInGermanyStillHintsGerman() {
        val (_, language) = language()
        language.override = null
        language.preferredLanguageTagsOverride = listOf("en-DE")
        language.currentLocaleOverride = Locale.forLanguageTag("en-DE")
        val hints = language.decodeHints(null)
        assertTrue(hints.contains("de"))
        assertEquals("de", TranscriptionLanguage.challenger("en", hints))
    }

    @Test
    fun conversationMemoryOutranksTheDeviceRegion() {
        val (memory, language) = language()
        val peer = UUID.randomUUID()
        repeat(4) { memory.record("fr", peer, 1.0) }
        language.preferredLanguageTagsOverride = listOf("en-DE")
        language.currentLocaleOverride = Locale.forLanguageTag("en-DE")
        val hints = language.decodeHints(peer)
        assertEquals("fr", hints.first())
        assertEquals("fr", TranscriptionLanguage.challenger("en", hints))
    }

    @Test
    fun challengerIsNilWhenDetectionAlreadyMatches() {
        assertNull(TranscriptionLanguage.challenger("de", listOf("de", "en")))
        assertNull(TranscriptionLanguage.challenger("german", listOf("de")))
        assertNull(TranscriptionLanguage.challenger(null, listOf("en")))
        assertEquals("de", TranscriptionLanguage.challenger(null, listOf("en", "de")))
        assertNull(TranscriptionLanguage.challenger("fr", listOf("en", "de")))
        assertNull(TranscriptionLanguage.challenger("ja", listOf("de", "en")))
    }

    @Test
    fun englishMemoryDoesNotSkipAGermanChallenger() {
        val (memory, language) = language()
        val peer = UUID.randomUUID()
        repeat(5) { memory.record("en", peer, 1.0) }
        assertTrue(memory.prior("en", peer) >= TranscriptionLanguage.TRUSTED_PRIOR)
        assertFalse(TranscriptionLanguage.shouldForceLanguage("en", memory.prior("en", peer)))
        language.preferredLanguageTagsOverride = listOf("en-DE")
        language.currentLocaleOverride = Locale.forLanguageTag("en-DE")
        val hints = language.decodeHints(peer)
        assertEquals("de", TranscriptionLanguage.challenger("en", hints))
    }

    @Test
    fun germanMemoryDoesSkipAutoDetect() {
        assertTrue(TranscriptionLanguage.shouldForceLanguage("de", 0.8))
        assertFalse(TranscriptionLanguage.shouldForceLanguage("de", 0.5))
        assertFalse(TranscriptionLanguage.shouldForceLanguage("en", 0.99))
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
    fun languageProbabilityPrefersMatchingText() {
        val german = "Guten Morgen, ich wollte dir nur schnell Bescheid geben dass es später wird"
        val english = "Good morning, I just wanted to let you know that it is going to be later"
        assertTrue(VoiceTranscript.languageProbability("de", german) > VoiceTranscript.languageProbability("en", german))
        assertTrue(VoiceTranscript.languageProbability("en", english) > VoiceTranscript.languageProbability("de", english))
    }

    @Test
    fun choosePrefersAGermanChallengerOverEnglishAutoDetect() {
        val memory = unlocked()
        val chosen = VoiceTranscript.choose(
            VoiceTranscript.Candidate("House goes to the deer tonight", "en", 0.72),
            VoiceTranscript.Candidate("Haus, ich gehe später noch zu dir", "de", 0.68),
            memory,
            null,
            6.0,
        )
        assertEquals("de", chosen.language)
        assertTrue(chosen.text.contains("Haus"))
        assertFalse(chosen.toString().contains("Haus"))
    }

    @Test
    fun chooseKeepsEnglishWhenTheChallengerIsEmpty() {
        val memory = unlocked()
        val chosen = VoiceTranscript.choose(
            VoiceTranscript.Candidate("I'll be there in five minutes", "en", 0.9),
            VoiceTranscript.Candidate(", , ,", "de", 0.4),
            memory,
            null,
            8.0,
        )
        assertEquals("en", chosen.language)
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

        assertNull(memory.expectedLanguage(peer))
        assertEquals(0.5, memory.prior("de", peer), 0.0)
        val before = file.readBytes()
        memory.record("fr", peer, 5.0)
        assertArrayEquals(before, file.readBytes())

        state.unlock(SealedTestKey.bytes())
        assertEquals("de", memory.expectedLanguage(peer))
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
        assertNull(memory.expectedLanguage(peer))
        assertEquals(0.5, memory.prior("de", peer), 0.0)
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
        val language = TranscriptionLanguage(FakeSharedPreferences(), StorageSeal(), memory)
        language.preferredLanguageTagsOverride = listOf("en-US")
        language.currentLocaleOverride = Locale.US
        return memory to language
    }
}
