import Foundation

/// User preference for which language voice messages are transcribed in.
///
/// Human: Device locale is a poor proxy for the language someone *speaks* — an English UI with
/// a German region is a common setup, and transcribing German speech with an English model
/// produces garbage. `automatic` probes plausible languages and scores them; the explicit
/// override exists because auto-detection can only choose between installed models.
/// Agent: READS/WRITES UserDefaults key `transcription.locale`; no other state.
enum TranscriptionLanguage {
    private static let defaultsKey = "transcription.locale"

    /// Explicitly chosen language, or nil for automatic detection.
    static var override: Locale? {
        get {
            guard let id = UserDefaults.standard.string(forKey: defaultsKey), !id.isEmpty else {
                return nil
            }
            return Locale(identifier: id)
        }
        set {
            let defaults = UserDefaults.standard
            if let newValue {
                defaults.set(newValue.identifier, forKey: defaultsKey)
            } else {
                defaults.removeObject(forKey: defaultsKey)
            }
        }
    }

    /// Human-readable name for a locale in the user's own language, e.g. "German (Germany)".
    static func displayName(for locale: Locale) -> String {
        Locale.current.localizedString(forIdentifier: locale.identifier) ?? locale.identifier
    }
}

/// Remembers which language was actually spoken, per conversation and overall.
///
/// Human: This is what makes *short* voice messages work. Two seconds of "Ja, mach ich" carries
/// almost no evidence — acoustically it is a coin flip against "Yeah, mush ish", and text-based
/// language ID needs far more characters than that to be reliable. But language is extremely
/// stable per conversation: whoever you spoke German with yesterday you will speak German with
/// today. So we learn from the messages that *were* long enough to be decisive, and lean on that
/// history exactly when the audio itself cannot decide.
/// Agent: READS/WRITES UserDefaults key `transcription.languageStats`; stores only BCP-47
/// language codes and weights — never text, audio, or message ids beyond the peer UUID key.
enum TranscriptionLanguageMemory {
    private static let defaultsKey = "transcription.languageStats"
    /// Scope key for observations not tied to a specific conversation.
    private static let globalScope = "*"
    /// Older observations decay so a language switch is picked up within a few messages.
    private static let decay = 0.9
    /// Weight at which a scope is considered to have a real opinion.
    private static let saturation = 3.0

    /// `[scope: [languageCode: weight]]`
    private static var store: [String: [String: Double]] {
        get { UserDefaults.standard.dictionary(forKey: defaultsKey) as? [String: [String: Double]] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: defaultsKey) }
    }

    /// Learned likelihood of `languageCode`, 0…1. **0.5 means "no opinion"** — the neutral value
    /// the scoring model expects, so a first-ever message is judged on its audio alone.
    static func prior(for languageCode: String, peerID: UUID?) -> Double {
        let all = store
        let peer = peerID.map { all[$0.uuidString] ?? [:] } ?? [:]
        let global = all[globalScope] ?? [:]

        let peerTotal = peer.values.reduce(0, +)
        let globalTotal = global.values.reduce(0, +)
        guard peerTotal + globalTotal > 0 else { return 0.5 }

        func share(_ counts: [String: Double], _ total: Double) -> Double {
            guard total > 0 else { return 0.5 }
            return (counts[languageCode] ?? 0) / total
        }

        // This conversation dominates; the global history only breaks ties for a new chat.
        let combined: Double
        if peerTotal > 0 {
            combined = 0.75 * share(peer, peerTotal) + 0.25 * share(global, globalTotal)
        } else {
            combined = share(global, globalTotal)
        }

        // Shrink toward neutral until we have seen a few messages, so one observation cannot
        // lock a conversation into the wrong language. Every observation is written to both the
        // peer and global scopes, so summing the two would double-count the same evidence —
        // measure whichever scope is actually driving the answer.
        let evidenceTotal = peerTotal > 0 ? peerTotal : globalTotal
        let evidence = min(1, evidenceTotal / saturation)
        return 0.5 + (combined - 0.5) * evidence
    }

    /// The language this scope most expects, if it has a real opinion.
    static func expectedLanguage(peerID: UUID?) -> String? {
        let all = store
        let counts = peerID.flatMap { all[$0.uuidString] } ?? all[globalScope] ?? [:]
        let total = counts.values.reduce(0, +)
        guard total >= 1 else { return nil }
        return counts.max { $0.value < $1.value }?.key
    }

    /// Records a decisive observation. `weight` should reflect how trustworthy it was —
    /// long, confidently-transcribed audio counts for more than a two-second fragment.
    static func record(languageCode: String, peerID: UUID?, weight: Double) {
        guard weight > 0 else { return }
        var all = store
        for scope in [peerID?.uuidString, globalScope].compactMap({ $0 }) {
            var counts = all[scope] ?? [:]
            // Decay everything first so a language change overtakes history in a few messages.
            for (key, value) in counts {
                counts[key] = value * decay
            }
            counts[languageCode] = (counts[languageCode] ?? 0) + weight
            // Drop noise so the dictionary cannot grow without bound.
            all[scope] = counts.filter { $0.value >= 0.05 }
        }
        store = all
    }

    /// Test seam — clears learned history.
    static func reset() {
        UserDefaults.standard.removeObject(forKey: defaultsKey)
    }
}

/// Pure helpers for judging whether a transcript is real speech, and how much to trust it.
///
/// Human: Extracted so the decision rules are testable without audio hardware.
enum VoiceTranscript {
    /// True when the text contains enough letters to be worth showing.
    static func containsSpeech(_ text: String) -> Bool {
        letterCount(text) >= 2
    }

    static func letterCount(_ text: String) -> Int {
        text.unicodeScalars.filter(CharacterSet.letters.contains).count
    }

    /// Trims and drops transcripts that carry no actual words.
    ///
    /// A model fed audio it cannot parse — wrong sample rate, wrong language, pure noise —
    /// emits punctuation and nothing else. Showing the user ", , , ," is worse than nothing.
    static func cleaned(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard containsSpeech(trimmed) else { return "" }
        return trimmed
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    /// Ranks a candidate transcription. Higher is better; 0 means "not speech".
    ///
    /// Three modifiers, each centred on a neutral 0.5 so that a no-information case collapses to
    /// plain `modelConfidence × substance`:
    ///
    /// - `substance` — a one-word fluke must not outrank a real sentence.
    /// - `languageProbability` — text-based language ID, faded toward "no opinion" when the
    ///   transcript is too short for it to mean anything (it needs ~40 characters).
    /// - `prior` — learned conversation history, weighted *up* as the audio gets shorter. This is
    ///   the term that carries short messages, where the acoustics cannot decide alone.
    static func score(
        text: String,
        modelConfidence: Double,
        languageProbability: Double = 0.5,
        prior: Double = 0.5,
        audioSeconds: Double = .greatestFiniteMagnitude
    ) -> Double {
        guard containsSpeech(text) else { return 0 }

        let letters = Double(letterCount(text))
        let substance = min(1, letters / 12)

        let textTrust = min(1, letters / 40)
        let languageTerm = 0.5 + (languageProbability - 0.5) * textTrust

        // Full weight below ~2s of audio, fading out by ~8s where the audio speaks for itself.
        let priorTrust = 1 - min(1, max(0, audioSeconds - 2) / 6)
        let priorTerm = 0.5 + (prior - 0.5) * priorTrust

        // Each modifier lands in [0.5, 1.5]; neutral inputs give exactly 1.
        return modelConfidence * substance * (0.5 + languageTerm) * (0.5 + priorTerm)
    }

    /// Below this the detection is not trustworthy and the caller should fall back to history.
    static let minimumTrustedScore = 0.12

    /// How much a finished transcription should teach the language memory.
    /// Short or shaky results teach little; a long confident one teaches a lot.
    static func learningWeight(audioSeconds: Double, score: Double) -> Double {
        guard score >= minimumTrustedScore else { return 0 }
        let duration = min(1, audioSeconds / 8)
        let strength = min(1, score / 0.4)
        return duration * strength
    }
}
