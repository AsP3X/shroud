import Foundation
import Testing
@testable import shroud

/// The rules that make **short** voice messages resolve to the right language.
///
/// A two-second utterance carries almost no acoustic evidence and far too little text for
/// language identification, so correctness there comes from conversation history rather than
/// from the audio. These tests pin that behaviour down.
@Suite(.serialized)
struct TranscriptionLanguageMemoryTests {
    private func withCleanMemory(_ body: () throws -> Void) rethrows {
        TranscriptionLanguageMemory.reset()
        defer { TranscriptionLanguageMemory.reset() }
        try body()
    }

    // MARK: - Priors

    @Test
    func aFreshInstallHasNoOpinion() {
        withCleanMemory {
            // 0.5 is the neutral value the scoring model collapses to — a first-ever message
            // must be judged on its audio alone, not on a guess.
            #expect(TranscriptionLanguageMemory.prior(for: "de", peerID: nil) == 0.5)
            #expect(TranscriptionLanguageMemory.expectedLanguage(peerID: nil) == nil)
        }
    }

    @Test
    func repeatedObservationsBuildAPreference() {
        withCleanMemory {
            let peer = UUID()
            for _ in 0 ..< 4 {
                TranscriptionLanguageMemory.record(languageCode: "de", peerID: peer, weight: 1)
            }
            #expect(TranscriptionLanguageMemory.prior(for: "de", peerID: peer) > 0.8)
            #expect(TranscriptionLanguageMemory.prior(for: "en", peerID: peer) < 0.2)
            #expect(TranscriptionLanguageMemory.expectedLanguage(peerID: peer) == "de")
        }
    }

    /// One observation must not lock a conversation in — otherwise a single mis-detection
    /// would poison every short message that follows.
    @Test
    func aSingleObservationOnlyNudges() {
        withCleanMemory {
            let peer = UUID()
            TranscriptionLanguageMemory.record(languageCode: "de", peerID: peer, weight: 1)
            let prior = TranscriptionLanguageMemory.prior(for: "de", peerID: peer)
            #expect(prior > 0.5)
            #expect(prior < 0.8, "one message should not be treated as certainty")
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
            #expect(TranscriptionLanguageMemory.expectedLanguage(peerID: german) == "de")
            #expect(TranscriptionLanguageMemory.expectedLanguage(peerID: english) == "en")
        }
    }

    @Test
    func aNewConversationInheritsTheGlobalHabit() {
        withCleanMemory {
            let known = UUID()
            for _ in 0 ..< 5 {
                TranscriptionLanguageMemory.record(languageCode: "de", peerID: known, weight: 1)
            }
            // A chat we have never transcribed still starts from what this user usually speaks.
            #expect(TranscriptionLanguageMemory.prior(for: "de", peerID: UUID()) > 0.5)
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
            #expect(TranscriptionLanguageMemory.expectedLanguage(peerID: peer) == "de")

            for _ in 0 ..< 6 {
                TranscriptionLanguageMemory.record(languageCode: "en", peerID: peer, weight: 1)
            }
            #expect(TranscriptionLanguageMemory.expectedLanguage(peerID: peer) == "en")
        }
    }

    @Test
    func zeroWeightObservationsAreIgnored() {
        withCleanMemory {
            let peer = UUID()
            TranscriptionLanguageMemory.record(languageCode: "de", peerID: peer, weight: 0)
            #expect(TranscriptionLanguageMemory.expectedLanguage(peerID: peer) == nil)
        }
    }

    // MARK: - How much a result teaches

    @Test
    func onlyDecisiveResultsTeachTheMemory() {
        // A shaky result must not reinforce itself into a self-fulfilling prophecy.
        #expect(VoiceTranscript.learningWeight(audioSeconds: 10, score: 0.05) == 0)
        // A short message teaches little even when it scored well.
        let short = VoiceTranscript.learningWeight(audioSeconds: 1.5, score: 0.5)
        let long = VoiceTranscript.learningWeight(audioSeconds: 12, score: 0.5)
        #expect(short < long)
        #expect(long > 0.5)
    }

    // MARK: - Scoring behaviour on short audio

    /// The heart of it: with two seconds of audio the prior should decide, because nothing else
    /// can. The same comparison on a long message must be driven by the audio instead.
    @Test
    func priorDecidesShortAudioButNotLongAudio() {
        let text = "Ja"

        // Two candidates that the acoustics rate identically.
        let favouredShort = VoiceTranscript.score(
            text: text, modelConfidence: 0.6, languageProbability: 0.5,
            prior: 0.9, audioSeconds: 2
        )
        let unfavouredShort = VoiceTranscript.score(
            text: text, modelConfidence: 0.6, languageProbability: 0.5,
            prior: 0.1, audioSeconds: 2
        )
        #expect(favouredShort > unfavouredShort * 1.5, "history must dominate a 2s utterance")

        let favouredLong = VoiceTranscript.score(
            text: text, modelConfidence: 0.6, languageProbability: 0.5,
            prior: 0.9, audioSeconds: 30
        )
        let unfavouredLong = VoiceTranscript.score(
            text: text, modelConfidence: 0.6, languageProbability: 0.5,
            prior: 0.1, audioSeconds: 30
        )
        #expect(favouredLong == unfavouredLong, "long audio must not be swayed by history")
    }

    /// Strong acoustic evidence has to be able to overrule the prior, or a language switch
    /// could never be detected.
    @Test
    func confidentAudioOverridesAContraryPrior() {
        let againstPrior = VoiceTranscript.score(
            text: "Guten Morgen, ich melde mich später nochmal bei dir",
            modelConfidence: 0.92, languageProbability: 0.95,
            prior: 0.15, audioSeconds: 6
        )
        let withPrior = VoiceTranscript.score(
            text: "Good morning",
            modelConfidence: 0.35, languageProbability: 0.4,
            prior: 0.85, audioSeconds: 6
        )
        #expect(againstPrior > withPrior)
    }

    /// Text-based language ID is worthless on two words and must not swing the result there.
    @Test
    func languageIdentificationIsDiscountedOnVeryShortText() {
        let shortAgreeing = VoiceTranscript.score(
            text: "Ja", modelConfidence: 0.7, languageProbability: 0.95, audioSeconds: 2
        )
        let shortDisagreeing = VoiceTranscript.score(
            text: "Ja", modelConfidence: 0.7, languageProbability: 0.05, audioSeconds: 2
        )
        let ratioShort = shortAgreeing / max(shortDisagreeing, .ulpOfOne)

        let longText = "Guten Morgen, ich wollte dir nur schnell Bescheid geben dass es später wird"
        let longAgreeing = VoiceTranscript.score(
            text: longText, modelConfidence: 0.7, languageProbability: 0.95, audioSeconds: 20
        )
        let longDisagreeing = VoiceTranscript.score(
            text: longText, modelConfidence: 0.7, languageProbability: 0.05, audioSeconds: 20
        )
        let ratioLong = longAgreeing / max(longDisagreeing, .ulpOfOne)

        #expect(ratioLong > ratioShort, "language ID should count for more once there is text")
    }

    @Test
    func belowTheTrustFloorNothingIsLearned() {
        #expect(VoiceTranscript.minimumTrustedScore > 0)
        let noisy = VoiceTranscript.score(
            text: "a b", modelConfidence: 0.1, languageProbability: 0.5, prior: 0.5, audioSeconds: 1
        )
        #expect(noisy < VoiceTranscript.minimumTrustedScore)
        #expect(VoiceTranscript.learningWeight(audioSeconds: 1, score: noisy) == 0)
    }

    // MARK: - Decode hints and challenger

    @Test
    func overrideIsTheOnlyDecodeHint() {
        withCleanMemory {
            let original = TranscriptionLanguage.override
            defer { TranscriptionLanguage.override = original }
            TranscriptionLanguage.preferredLanguageTagsOverride = ["en-DE"]
            TranscriptionLanguage.override = Locale(identifier: "fr")
            #expect(TranscriptionLanguage.decodeHints(peerID: UUID()) == ["fr"])
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
    func overrideShortCircuitsCandidateDetection() async {
        TranscriptionLanguageMemory.reset()
        let original = TranscriptionLanguage.override
        defer {
            TranscriptionLanguage.override = original
            TranscriptionLanguageMemory.reset()
        }
        TranscriptionLanguage.override = Locale(identifier: "en-US")
        let candidates = await VoiceTranscriber.candidateLocales()
        #expect(candidates.count == 1)
        #expect(candidates.first?.language.languageCode?.identifier == "en")
    }

    @Test
    func englishUIInGermanyStillHintsGerman() {
        withCleanMemory {
            let original = TranscriptionLanguage.override
            defer { TranscriptionLanguage.override = original }
            TranscriptionLanguage.override = nil
            TranscriptionLanguage.preferredLanguageTagsOverride = ["en-DE"]
            TranscriptionLanguage.currentLocaleOverride = Locale(identifier: "en_DE")
            let hints = TranscriptionLanguage.decodeHints(peerID: nil)
            #expect(hints.contains("de"), "en-DE must still challenge Whisper's English default")
            #expect(TranscriptionLanguage.challenger(detected: "en", hints: hints) == "de")
        }
    }

    @Test
    func conversationMemoryOutranksTheDeviceRegion() {
        withCleanMemory {
            let peer = UUID()
            for _ in 0 ..< 4 {
                TranscriptionLanguageMemory.record(languageCode: "fr", peerID: peer, weight: 1)
            }
            TranscriptionLanguage.preferredLanguageTagsOverride = ["en-DE"]
            TranscriptionLanguage.currentLocaleOverride = Locale(identifier: "en_DE")
            let hints = TranscriptionLanguage.decodeHints(peerID: peer)
            #expect(hints.first == "fr")
            #expect(TranscriptionLanguage.challenger(detected: "en", hints: hints) == "fr")
        }
    }

    @Test
    func challengerIsNilWhenDetectionAlreadyMatches() {
        #expect(TranscriptionLanguage.challenger(detected: "de", hints: ["de", "en"]) == nil)
        #expect(TranscriptionLanguage.challenger(detected: "german", hints: ["de"]) == nil)
        #expect(TranscriptionLanguage.challenger(detected: nil, hints: ["en"]) == nil)
        #expect(TranscriptionLanguage.challenger(detected: nil, hints: ["en", "de"]) == "de")
    }

    @Test
    func englishMemoryDoesNotSkipAGermanChallenger() {
        withCleanMemory {
            let peer = UUID()
            for _ in 0 ..< 5 {
                TranscriptionLanguageMemory.record(languageCode: "en", peerID: peer, weight: 1)
            }
            #expect(TranscriptionLanguageMemory.prior(for: "en", peerID: peer) >= TranscriptionLanguage.trustedPrior)
            #expect(!TranscriptionLanguage.shouldForceLanguage("en", prior: TranscriptionLanguageMemory.prior(for: "en", peerID: peer)))
            TranscriptionLanguage.preferredLanguageTagsOverride = ["en-DE"]
            let hints = TranscriptionLanguage.decodeHints(peerID: peer)
            #expect(TranscriptionLanguage.challenger(detected: "en", hints: hints) == "de")
        }
    }

    @Test
    func germanMemoryDoesSkipAutoDetect() {
        #expect(TranscriptionLanguage.shouldForceLanguage("de", prior: 0.8))
        #expect(!TranscriptionLanguage.shouldForceLanguage("de", prior: 0.5))
        #expect(!TranscriptionLanguage.shouldForceLanguage("en", prior: 0.99))
    }

    @Test
    func normalizeMapsWhisperLanguageNames() {
        #expect(TranscriptionLanguage.normalize("german") == "de")
        #expect(TranscriptionLanguage.normalize("DE") == "de")
        #expect(TranscriptionLanguage.normalize("en") == "en")
    }

    @Test
    func languageProbabilityPrefersMatchingText() {
        let german = "Guten Morgen, ich wollte dir nur schnell Bescheid geben dass es später wird"
        let english = "Good morning, I just wanted to let you know that it is going to be later"
        let deOnGerman = VoiceTranscript.languageProbability(of: "de", in: german)
        let enOnGerman = VoiceTranscript.languageProbability(of: "en", in: german)
        let enOnEnglish = VoiceTranscript.languageProbability(of: "en", in: english)
        #expect(deOnGerman > enOnGerman)
        #expect(enOnEnglish > VoiceTranscript.languageProbability(of: "de", in: english))
    }

    /// The regression: Whisper auto-detects English, but the German decode is the real speech.
    @Test
    func choosePrefersAGermanChallengerOverEnglishAutoDetect() {
        withCleanMemory {
            let auto = VoiceTranscript.Candidate(
                text: "House goes to the deer tonight",
                language: "en",
                confidence: 0.72
            )
            let challenge = VoiceTranscript.Candidate(
                text: "Haus, ich gehe später noch zu dir",
                language: "de",
                confidence: 0.68
            )
            let chosen = VoiceTranscript.choose(
                auto: auto, challenge: challenge, peerID: nil, audioSeconds: 6
            )
            #expect(chosen.language == "de")
            #expect(chosen.text.contains("Haus"))
        }
    }

    @Test
    func chooseKeepsEnglishWhenTheChallengerIsEmpty() {
        withCleanMemory {
            let auto = VoiceTranscript.Candidate(
                text: "I'll be there in five minutes",
                language: "en",
                confidence: 0.9
            )
            let challenge = VoiceTranscript.Candidate(
                text: ", , ,",
                language: "de",
                confidence: 0.4
            )
            let chosen = VoiceTranscript.choose(
                auto: auto, challenge: challenge, peerID: nil, audioSeconds: 8
            )
            #expect(chosen.language == "en")
        }
    }
}
