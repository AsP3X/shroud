import CryptoKit
import Foundation
import NaturalLanguage

/// User preference for which language voice messages are transcribed in.
///
/// Human: Whisper's own language ID is strongly English-biased, especially on short notes, so
/// "Automatic" is not a coin flip — it will happily transcribe German as English. An English UI
/// with a German region is a common setup, and that region *is* a useful *challenger* (not a
/// forced language): when detection lands on English we decode again with the hint and keep
/// the better transcript. A detection that is already another language is kept.
/// Conversation history then takes over.
/// Agent: READS/WRITES UserDefaults key `transcription.locale`; no other state.
nonisolated enum TranscriptionLanguage {
    private static let defaultsKey = "transcription.locale"

    /// Test seam — replaces `Locale.preferredLanguages` when non-nil.
    static var preferredLanguageTagsOverride: [String]?
    /// Test seam — replaces `Locale.current` when non-nil.
    static var currentLocaleOverride: Locale?

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

    /// Languages Whisper can transcribe. One multilingual model covers all of them — this is
    /// only the settings picker, not a list of extra downloads.
    static var whisperLocales: [Locale] {
        let preferred = preferredLanguageTags.compactMap { tag -> String? in
            Locale(identifier: tag).language.languageCode?.identifier
        }
        var ordered: [String] = []
        for code in preferred + whisperCodeList where whisperCodes.contains(code) && !ordered.contains(code) {
            ordered.append(code)
        }
        return ordered.map { Locale(identifier: $0) }
    }

    static let whisperCodeList = [
        "en", "de", "es", "fr", "it", "pt", "nl", "pl", "ru", "uk",
        "tr", "ar", "hi", "ja", "ko", "zh", "sv", "da", "nb", "fi",
        "cs", "el", "he", "id", "th", "vi", "ro", "hu", "ca", "hr",
    ]
    static let whisperCodes = Set(whisperCodeList)

    /// ISO 639-1 (`de`, `en`). Whisper sometimes emits a name (`german`); map that too.
    static func normalize(_ code: String) -> String {
        let raw = code.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !raw.isEmpty else { return "" }
        if raw.count == 2, whisperCodes.contains(raw) { return raw }
        if let mapped = languageNames[raw], whisperCodes.contains(mapped) { return mapped }
        let prefix = String(raw.prefix(2))
        return whisperCodes.contains(prefix) ? prefix : raw
    }

    /// Languages we should try, in priority order. Override (if set) is the only entry.
    /// Otherwise: conversation memory, then the languages this device actually lives in —
    /// preferred UI languages *and* the region of an English UI in Germany.
    static func decodeHints(peerID: UUID?) -> [String] {
        if let override = override.flatMap({ $0.language.languageCode?.identifier }) {
            let code = normalize(override)
            return whisperCodes.contains(code) ? [code] : []
        }
        var ordered: [String] = []
        func add(_ raw: String?) {
            guard let raw else { return }
            let code = normalize(raw)
            guard whisperCodes.contains(code), !ordered.contains(code) else { return }
            ordered.append(code)
        }
        add(TranscriptionLanguageMemory.expectedLanguage(peerID: peerID))
        if peerID != nil {
            add(TranscriptionLanguageMemory.expectedLanguage(peerID: nil))
        }
        for tag in preferredLanguageTags {
            add(Locale(identifier: tag).language.languageCode?.identifier)
        }
        for code in regionLanguageHints {
            add(code)
        }
        return ordered
    }

    /// Second pass when detection landed on English or failed. Nil means one pass is enough.
    /// A French or German detection is the language of the note. English is Whisper's biased
    /// default, so a hint may challenge that and nothing else. The hint itself is never English.
    static func challenger(detected: String?, hints: [String]) -> String? {
        let detected = detected.map { normalize($0) } ?? ""
        guard detected.isEmpty || detected == "en" else { return nil }
        for hint in hints {
            let code = normalize(hint)
            if code.isEmpty || code == detected || code == "en" { continue }
            return code
        }
        return nil
    }

    /// Above this, conversation history is trusted enough to skip auto-detect.
    static let trustedPrior = 0.75

    /// English is Whisper's default; forcing it from a poisoned memory would hide German forever.
    static func shouldForceLanguage(_ code: String, prior: Double) -> Bool {
        let code = normalize(code)
        guard whisperCodes.contains(code), code != "en" else { return false }
        return prior >= trustedPrior
    }

    static var preferredLanguageTags: [String] {
        preferredLanguageTagsOverride ?? Locale.preferredLanguages
    }

    static var currentLocale: Locale {
        currentLocaleOverride ?? .current
    }

    /// Spoken language implied by region, so `en-DE` still challenges Whisper's English default.
    static var regionLanguageHints: [String] {
        var tags = preferredLanguageTags
        tags.append(currentLocale.identifier)
        var codes: [String] = []
        for tag in tags {
            let locale = Locale(identifier: tag)
            guard let region = locale.region?.identifier,
                  let language = language(forRegion: region),
                  !codes.contains(language)
            else { continue }
            codes.append(language)
        }
        return codes
    }

    /// Non-English region → likely spoken language. English-speaking regions are omitted on
    /// purpose: Whisper already defaults to English.
    static func language(forRegion region: String) -> String? {
        switch region.uppercased() {
        case "DE", "AT", "LI": "de"
        case "FR", "MC": "fr"
        case "ES", "MX", "AR", "CO", "CL", "PE": "es"
        case "IT": "it"
        case "NL": "nl"
        case "PL": "pl"
        case "PT", "BR": "pt"
        case "RU": "ru"
        case "UA": "uk"
        case "TR": "tr"
        case "JP": "ja"
        case "KR": "ko"
        case "CN", "TW": "zh"
        case "SE": "sv"
        case "DK": "da"
        case "NO": "nb"
        case "FI": "fi"
        case "GR": "el"
        case "IL": "he"
        case "SA", "AE", "EG": "ar"
        case "TH": "th"
        case "VN": "vi"
        case "RO": "ro"
        case "HU": "hu"
        case "CZ": "cs"
        case "HR": "hr"
        default: nil
        }
    }

    private static let languageNames: [String: String] = [
        "german": "de", "english": "en", "spanish": "es", "french": "fr",
        "italian": "it", "portuguese": "pt", "dutch": "nl", "polish": "pl",
        "russian": "ru", "ukrainian": "uk", "turkish": "tr", "arabic": "ar",
        "hindi": "hi", "japanese": "ja", "korean": "ko", "chinese": "zh",
        "swedish": "sv", "danish": "da", "norwegian": "nb", "finnish": "fi",
        "czech": "cs", "greek": "el", "hebrew": "he", "indonesian": "id",
        "thai": "th", "vietnamese": "vi", "romanian": "ro", "hungarian": "hu",
        "catalan": "ca", "croatian": "hr",
    ]
}

/// Remembers which language was actually spoken, per conversation and overall.
///
/// Human: This is what makes *short* voice messages work. Two seconds of "Ja, mach ich" carries
/// almost no evidence — acoustically it is a coin flip against "Yeah, mush ish", and text-based
/// language ID needs far more characters than that to be reliable. But language is extremely
/// stable per conversation: whoever you spoke German with yesterday you will speak German with
/// today. So we learn from the messages that *were* long enough to be decisive, and lean on that
/// history exactly when the audio itself cannot decide.
/// Agent: READS/WRITES a sealed file (`shroud/voice/language-stats.sealed`, `LocalHistoryCrypto`
/// context `.languageStats`); stores only language codes and weights — never text, audio, or
/// message ids beyond the peer UUID key. The peer keys reveal who the user exchanges voice notes
/// with, so the file is only open while chats are: locked, reads are empty and writes drop.
/// Migrates and deletes the old plaintext UserDefaults key `transcription.languageStats`.
nonisolated enum TranscriptionLanguageMemory {
    /// Where older builds kept the statistics in the clear.
    static let legacyDefaultsKey = "transcription.languageStats"

    /// `Application Support/shroud/voice/language-stats.sealed`, next to the other sealed stores.
    static var defaultFileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base
            .appendingPathComponent("shroud", isDirectory: true)
            .appendingPathComponent("voice", isDirectory: true)
            .appendingPathComponent("language-stats.sealed")
    }

    /// Present only while unlocked. `stats` is the decrypted copy; the file is rewritten on
    /// every `record`.
    private struct Unlocked {
        let historyKey: SymmetricKey
        let fileURL: URL
        var stats: [String: [String: Double]]
    }

    private static let stateLock = NSLock()
    private nonisolated(unsafe) static var unlocked: Unlocked?
    /// Scope key for observations not tied to a specific conversation.
    private static let globalScope = "*"
    /// Older observations decay so a language switch is picked up within a few messages.
    private static let decay = 0.9
    /// Weight at which a scope is considered to have a real opinion.
    private static let saturation = 3.0

    /// `[scope: [languageCode: weight]]`. Empty while locked; a write while locked is dropped.
    private static var store: [String: [String: Double]] {
        get { stateLock.withLock { unlocked?.stats ?? [:] } }
        set {
            stateLock.withLock {
                guard var state = unlocked else { return }
                state.stats = newValue
                unlocked = state
                write(newValue, historyKey: state.historyKey, to: state.fileURL)
            }
        }
    }

    // MARK: - Lock

    /// Opens the sealed statistics and folds in (then deletes) the plaintext UserDefaults copy
    /// an older build left. Called whenever chats unlock.
    static func unlock(
        historyKey: SymmetricKey,
        fileURL: URL = defaultFileURL,
        defaults: UserDefaults = .standard
    ) {
        stateLock.withLock {
            var stats = (try? Data(contentsOf: fileURL))
                .flatMap { open($0, historyKey: historyKey) } ?? [:]
            if let legacy = defaults.dictionary(forKey: legacyDefaultsKey) as? [String: [String: Double]] {
                // The sealed copy is newer wherever both know a conversation.
                stats.merge(legacy) { sealed, _ in sealed }
                if write(stats, historyKey: historyKey, to: fileURL) {
                    defaults.removeObject(forKey: legacyDefaultsKey)
                }
            } else if defaults.object(forKey: legacyDefaultsKey) != nil {
                // Unreadable leftover: nothing to keep, and it must not stay in the clear.
                defaults.removeObject(forKey: legacyDefaultsKey)
            }
            unlocked = Unlocked(historyKey: historyKey, fileURL: fileURL, stats: stats)
        }
    }

    /// Forgets the decrypted statistics and the key. The sealed file stays.
    static func lock() {
        stateLock.withLock { unlocked = nil }
    }

    // MARK: - Sealing

    static func seal(_ stats: [String: [String: Double]], historyKey: SymmetricKey) throws -> Data {
        let json = try JSONEncoder().encode(stats)
        return try LocalHistoryCrypto.seal(json, masterKey: historyKey, context: .languageStats)
    }

    /// Nil for a file that does not open under `historyKey` (wrong account, tampered).
    static func open(_ blob: Data, historyKey: SymmetricKey) -> [String: [String: Double]]? {
        guard let json = try? LocalHistoryCrypto.open(blob, masterKey: historyKey, context: .languageStats)
        else { return nil }
        return try? JSONDecoder().decode([String: [String: Double]].self, from: json)
    }

    @discardableResult
    private static func write(
        _ stats: [String: [String: Double]],
        historyKey: SymmetricKey,
        to url: URL
    ) -> Bool {
        guard let sealed = try? seal(stats, historyKey: historyKey) else { return false }
        LocalDataProtection.prepareDirectory(url.deletingLastPathComponent())
        do {
            try sealed.write(to: url, options: .atomic)
            LocalDataProtection.lockDown(url: url)
            return true
        } catch {
            return false
        }
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

    /// Test seam — locks the memory and clears the test overrides. Does not touch the file.
    static func reset() {
        lock()
        TranscriptionLanguage.preferredLanguageTagsOverride = nil
        TranscriptionLanguage.currentLocaleOverride = nil
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

    /// Text-based language ID for `languageCode` (ISO 639-1). Neutral 0.5 when the text is
    /// too short to tell, or when the recogniser has no opinion.
    static func languageProbability(of languageCode: String, in text: String) -> Double {
        let code = TranscriptionLanguage.normalize(languageCode)
        guard !code.isEmpty, letterCount(text) >= 8 else { return 0.5 }
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        let hypotheses = recognizer.languageHypotheses(withMaximum: 12)
        guard !hypotheses.isEmpty else { return 0.5 }
        for (language, probability) in hypotheses {
            let raw = language.rawValue.lowercased()
            if raw == code || raw.hasPrefix(code) { return Double(probability) }
        }
        return 0.05
    }

    /// Auto-detect is English-biased; a non-English challenger only has to be close, not better.
    static let englishChallengeMargin = 1.2

    /// One candidate from a decode pass.
    struct Candidate: Equatable {
        var text: String
        var language: String?
        var confidence: Double
    }

    /// Picks between Whisper's auto-detect and a forced-language challenger.
    static func choose(
        auto: Candidate,
        challenge: Candidate?,
        peerID: UUID?,
        audioSeconds: Double
    ) -> Candidate {
        let autoClean = cleaned(auto.text)
        let autoLang = auto.language.map { TranscriptionLanguage.normalize($0) }
        let autoScored = Candidate(text: autoClean, language: autoLang, confidence: auto.confidence)
        let autoScore = score(
            candidate: autoScored,
            peerID: peerID,
            audioSeconds: audioSeconds
        )

        guard var challenge else { return autoScored }
        challenge.text = cleaned(challenge.text)
        challenge.language = challenge.language.map { TranscriptionLanguage.normalize($0) }
        let altScore = score(
            candidate: challenge,
            peerID: peerID,
            audioSeconds: audioSeconds
        )

        var autoEffective = autoScore
        // A missing language is Whisper's English default, not "we don't know".
        let autoLooksEnglish = autoLang == nil || autoLang == "en"
        if autoLooksEnglish, challenge.language != "en" {
            autoEffective = autoScore / englishChallengeMargin
        }
        return altScore > autoEffective ? challenge : autoScored
    }

    static func score(
        candidate: Candidate,
        peerID: UUID?,
        audioSeconds: Double
    ) -> Double {
        let language = candidate.language ?? ""
        return score(
            text: candidate.text,
            modelConfidence: candidate.confidence,
            languageProbability: languageProbability(of: language, in: candidate.text),
            prior: language.isEmpty ? 0.5 : TranscriptionLanguageMemory.prior(for: language, peerID: peerID),
            audioSeconds: audioSeconds
        )
    }
}
