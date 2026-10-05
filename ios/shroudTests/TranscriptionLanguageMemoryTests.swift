import CryptoKit
import Foundation
import Testing
@testable import shroud

/// The language memory that **short** voice messages lean on, and what a note teaches it.
///
/// A two-second utterance carries little acoustic evidence, so conversation history weighs
/// Whisper's detection there (`SpokenLanguagePick`). Only notes whose audio settled the language
/// teach that history. These tests pin that behaviour down.
@Suite(.serialized)
struct TranscriptionLanguageMemoryTests {
    /// Unlocked memory backed by a throwaway sealed file and UserDefaults suite.
    private func withCleanMemory(_ body: () throws -> Void) rethrows {
        let scratch = Scratch()
        defer { scratch.tearDown() }
        TranscriptionLanguageMemory.reset()
        defer { TranscriptionLanguageMemory.reset() }
        TranscriptionLanguageMemory.unlock(
            historyKey: scratch.key,
            fileURL: scratch.fileURL,
            defaults: scratch.defaults
        )
        try body()
    }

    private struct Scratch {
        let key = SymmetricKey(size: .bits256)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("lang-\(UUID().uuidString)", isDirectory: true)
        let suite = "shroud.tests.lang." + UUID().uuidString
        var fileURL: URL { directory.appendingPathComponent("voice/language-stats.sealed") }
        var defaults: UserDefaults { UserDefaults(suiteName: suite)! }

        func tearDown() {
            try? FileManager.default.removeItem(at: directory)
            UserDefaults.standard.removePersistentDomain(forName: suite)
        }
    }

    // MARK: - History

    @Test
    func aFreshInstallHasNoHistory() {
        withCleanMemory {
            #expect(TranscriptionLanguageMemory.history(peerID: nil).isEmpty)
            #expect(TranscriptionLanguageMemory.history(peerID: UUID()).isEmpty)
        }
    }

    @Test
    func repeatedObservationsBuildAHistory() {
        withCleanMemory {
            let peer = UUID()
            for _ in 0 ..< 4 {
                TranscriptionLanguageMemory.record(languageCode: "de", peerID: peer, weight: 1)
            }
            let history = TranscriptionLanguageMemory.history(peerID: peer)
            #expect(Set(history.keys) == ["de"])
            #expect((history["de"] ?? 0) > 3)
        }
    }

    @Test
    func historyIsScopedPerConversation() {
        withCleanMemory {
            let german = UUID()
            let english = UUID()
            for _ in 0 ..< 4 {
                TranscriptionLanguageMemory.record(languageCode: "de", peerID: german, weight: 1)
            }
            for _ in 0 ..< 4 {
                TranscriptionLanguageMemory.record(languageCode: "en", peerID: english, weight: 1)
            }
            #expect(Set(TranscriptionLanguageMemory.history(peerID: german).keys) == ["de"])
            #expect(Set(TranscriptionLanguageMemory.history(peerID: english).keys) == ["en"])
        }
    }

    @Test
    func aNewConversationInheritsTheGlobalHabit() {
        withCleanMemory {
            let known = UUID()
            for _ in 0 ..< 5 {
                TranscriptionLanguageMemory.record(languageCode: "de", peerID: known, weight: 1)
            }
            // A chat we have never transcribed still starts from what this user usually hears.
            #expect(Set(TranscriptionLanguageMemory.history(peerID: UUID()).keys) == ["de"])
        }
    }

    /// Switching language mid-conversation must be picked up within a few messages, not never.
    @Test
    func decayLetsAConversationChangeLanguage() {
        withCleanMemory {
            let peer = UUID()
            for _ in 0 ..< 5 {
                TranscriptionLanguageMemory.record(languageCode: "de", peerID: peer, weight: 1)
            }
            for _ in 0 ..< 6 {
                TranscriptionLanguageMemory.record(languageCode: "en", peerID: peer, weight: 1)
            }
            let history = TranscriptionLanguageMemory.history(peerID: peer)
            #expect((history["en"] ?? 0) > (history["de"] ?? 0))
        }
    }

    @Test
    func zeroWeightObservationsAreIgnored() {
        withCleanMemory {
            let peer = UUID()
            TranscriptionLanguageMemory.record(languageCode: "de", peerID: peer, weight: 0)
            #expect(TranscriptionLanguageMemory.history(peerID: peer).isEmpty)
        }
    }

    // MARK: - How much a note teaches

    /// The regression: a pick the history carried must not teach the history, or one wrong
    /// entry (English heard as Turkish) turns every later note into that language.
    @Test
    func onlyWhatTheAudioSettledTeachesTheMemory() {
        #expect(VoiceTranscript.learningWeight(audioSeconds: 10, languageProbability: 0.5) == 0)
        #expect(VoiceTranscript.learningWeight(audioSeconds: 10, languageProbability: 0.3) == 0)
        #expect(VoiceTranscript.learningWeight(audioSeconds: 10, languageProbability: .nan) == 0)
        #expect(VoiceTranscript.learningWeight(audioSeconds: 10, languageProbability: 0.95) == 1)
        let middling = VoiceTranscript.learningWeight(audioSeconds: 10, languageProbability: 0.7)
        #expect(middling > 0.4 && middling < 0.6)
        // Short notes teach less than long ones.
        #expect(
            VoiceTranscript.learningWeight(audioSeconds: 2, languageProbability: 0.95)
                < VoiceTranscript.learningWeight(audioSeconds: 8, languageProbability: 0.95)
        )
    }

    // MARK: - Pin and device languages

    @Test
    func overrideIsThePinnedLanguage() {
        withCleanMemory {
            let original = TranscriptionLanguage.override
            defer { TranscriptionLanguage.override = original }
            TranscriptionLanguage.override = nil
            #expect(TranscriptionLanguage.pinnedLanguage == nil)
            TranscriptionLanguage.override = Locale(identifier: "fr")
            #expect(TranscriptionLanguage.pinnedLanguage == "fr")
        }
    }

    @Test
    func languageOverrideRoundTripsAndClears() {
        withCleanMemory {
            let original = TranscriptionLanguage.override
            defer { TranscriptionLanguage.override = original }
            TranscriptionLanguage.override = Locale(identifier: "de-DE")
            // Compare the language code, not the raw identifier — Foundation canonicalises
            // "de-DE" to "de_DE" on the round trip.
            #expect(TranscriptionLanguage.override?.language.languageCode?.identifier == "de")
            TranscriptionLanguage.override = nil
            #expect(TranscriptionLanguage.override == nil)
        }
    }

    @Test
    func englishUIInGermanyCountsGermanAsADeviceLanguage() {
        withCleanMemory {
            TranscriptionLanguage.preferredLanguageTagsOverride = ["en-DE"]
            TranscriptionLanguage.currentLocaleOverride = Locale(identifier: "en_DE")
            #expect(TranscriptionLanguage.deviceLanguages == ["en", "de"])
        }
    }

    @Test
    func conversationMemoryIsNotADeviceLanguage() {
        withCleanMemory {
            let peer = UUID()
            for _ in 0 ..< 4 {
                TranscriptionLanguageMemory.record(languageCode: "tr", peerID: peer, weight: 1)
            }
            TranscriptionLanguage.preferredLanguageTagsOverride = ["en-DE"]
            TranscriptionLanguage.currentLocaleOverride = Locale(identifier: "en_DE")
            #expect(TranscriptionLanguage.deviceLanguages == ["en", "de"])
        }
    }

    @Test
    func detectionCandidatesAddEnglish() {
        #expect(TranscriptionLanguage.detectionCandidates(["de"]) == ["de", "en"])
        #expect(TranscriptionLanguage.detectionCandidates(["en", "de"]) == ["en", "de"])
        #expect(TranscriptionLanguage.detectionCandidates([]) == [])
    }

    @Test
    func normalizeMapsWhisperLanguageNames() {
        #expect(TranscriptionLanguage.normalize("german") == "de")
        #expect(TranscriptionLanguage.normalize("DE") == "de")
        #expect(TranscriptionLanguage.normalize("en") == "en")
    }

    // MARK: - Sealed at rest

    @Test
    func lockedMemoryReadsEmptyAndDropsWrites() {
        let scratch = Scratch()
        defer { scratch.tearDown() }
        TranscriptionLanguageMemory.reset()
        defer { TranscriptionLanguageMemory.reset() }
        let peer = UUID()
        TranscriptionLanguageMemory.unlock(historyKey: scratch.key, fileURL: scratch.fileURL, defaults: scratch.defaults)
        for _ in 0 ..< 4 {
            TranscriptionLanguageMemory.record(languageCode: "de", peerID: peer, weight: 1)
        }
        TranscriptionLanguageMemory.lock()

        #expect(TranscriptionLanguageMemory.history(peerID: peer).isEmpty)
        let before = try? Data(contentsOf: scratch.fileURL)
        TranscriptionLanguageMemory.record(languageCode: "fr", peerID: peer, weight: 5)
        #expect((try? Data(contentsOf: scratch.fileURL)) == before)
        #expect(scratch.defaults.object(forKey: TranscriptionLanguageMemory.legacyDefaultsKey) == nil)

        // Unlocking again brings the sealed history back, without the dropped write.
        TranscriptionLanguageMemory.unlock(historyKey: scratch.key, fileURL: scratch.fileURL, defaults: scratch.defaults)
        #expect(Set(TranscriptionLanguageMemory.history(peerID: peer).keys) == ["de"])
    }

    @Test
    func theFileNeverNamesThePeer() throws {
        let scratch = Scratch()
        defer { scratch.tearDown() }
        TranscriptionLanguageMemory.reset()
        defer { TranscriptionLanguageMemory.reset() }
        let peer = UUID()
        TranscriptionLanguageMemory.unlock(historyKey: scratch.key, fileURL: scratch.fileURL, defaults: scratch.defaults)
        TranscriptionLanguageMemory.record(languageCode: "de", peerID: peer, weight: 1)

        let blob = try Data(contentsOf: scratch.fileURL)
        #expect(LocalHistoryCrypto.isSealedBlob(blob))
        #expect(blob.range(of: Data(peer.uuidString.utf8)) == nil)
        #expect(TranscriptionLanguageMemory.open(blob, historyKey: SymmetricKey(size: .bits256)) == nil)
        let opened = try #require(TranscriptionLanguageMemory.open(blob, historyKey: scratch.key))
        #expect(opened[peer.uuidString]?["de"] == 1)
    }

    @Test
    func plaintextDefaultsAreMigratedThenDeleted() throws {
        let scratch = Scratch()
        defer { scratch.tearDown() }
        TranscriptionLanguageMemory.reset()
        defer { TranscriptionLanguageMemory.reset() }
        let peer = UUID()
        scratch.defaults.set(
            [peer.uuidString: ["de": 4.0], "*": ["de": 4.0]],
            forKey: TranscriptionLanguageMemory.legacyDefaultsKey
        )

        TranscriptionLanguageMemory.unlock(historyKey: scratch.key, fileURL: scratch.fileURL, defaults: scratch.defaults)

        #expect(scratch.defaults.object(forKey: TranscriptionLanguageMemory.legacyDefaultsKey) == nil)
        #expect(Set(TranscriptionLanguageMemory.history(peerID: peer).keys) == ["de"])
        let blob = try Data(contentsOf: scratch.fileURL)
        #expect(TranscriptionLanguageMemory.open(blob, historyKey: scratch.key)?[peer.uuidString]?["de"] == 4)
    }
}
