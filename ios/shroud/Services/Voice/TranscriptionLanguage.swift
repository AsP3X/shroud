import CryptoKit
import Foundation

/// User preference for which language voice messages are transcribed in.
///
/// Human: "Automatic" means Whisper hears the language. The device's languages — UI languages and
/// the region's, so an English UI in Germany counts German — are the candidates its probabilities
/// are weighed with (`SpokenLanguagePick`), together with the chat's history. They never replace
/// what the audio says: decoding a note in a language Whisper did not hear makes it translate.
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

    /// The pinned language as a Whisper code, or nil for automatic (also for a pin Whisper can't use).
    static var pinnedLanguage: String? {
        guard let code = override.flatMap({ $0.language.languageCode?.identifier }) else { return nil }
        let normalized = normalize(code)
        return whisperCodes.contains(normalized) ? normalized : nil
    }

    /// The languages this device lives in: preferred UI languages first, then the region's
    /// (`en-DE` gives `en`, `de`).
    static var deviceLanguages: [String] {
        var ordered: [String] = []
        func add(_ raw: String?) {
            guard let raw else { return }
            let code = normalize(raw)
            guard whisperCodes.contains(code), !ordered.contains(code) else { return }
            ordered.append(code)
        }
        for tag in preferredLanguageTags {
            add(Locale(identifier: tag).language.languageCode?.identifier)
        }
        for code in regionLanguageHints {
            add(code)
        }
        return ordered
    }

    /// Languages detection weighs fully (`SpokenLanguagePick`): the device's, and English, which
    /// Whisper is best at and many people mix in. None, no preference.
    static func detectionCandidates(_ languages: [String]) -> [String] {
        languages.isEmpty || languages.contains("en") ? languages : languages + ["en"]
    }

    static var preferredLanguageTags: [String] {
        preferredLanguageTagsOverride ?? Locale.preferredLanguages
    }

    static var currentLocale: Locale {
        currentLocaleOverride ?? .current
    }

    /// Spoken language implied by region, so `en-DE` also counts German.
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
    /// purpose: English is always a candidate.
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
/// little evidence, and Whisper may lean English on it. But language is stable per conversation:
/// whoever you spoke German with yesterday you will speak German with today. So we learn from the
/// notes whose audio settled the language, and lean on that history when the audio is unsure.
/// Only the audio teaches it (`VoiceTranscript.learningWeight`): a pick the history carried
/// teaches nothing, so one wrong entry can't feed itself.
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

    /// The languages heard in `peerID`'s chat, by weight: its own record, or the overall one while
    /// the chat has none yet. Empty while locked. `SpokenLanguagePick` weighs Whisper's
    /// probabilities with it; it never replaces what the audio says.
    static func history(peerID: UUID?) -> [String: Double] {
        let all = store
        if let peer = peerID.flatMap({ all[$0.uuidString] }), peer.values.reduce(0, +) > 0 {
            return peer
        }
        return all[globalScope] ?? [:]
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

/// Pure helpers for judging whether a transcript is real speech, and how much a note teaches the
/// language memory.
///
/// Human: Extracted so the decision rules are testable without audio hardware.
nonisolated enum VoiceTranscript {
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

    /// Below this probability the audio did not settle the language, so the note teaches nothing.
    static let decisiveProbability = 0.5
    /// At this probability the audio settled it fully.
    static let certainProbability = 0.9

    /// How much a finished note teaches the memory. Only what the audio itself settled counts:
    /// `languageProbability` is Whisper's probability for the language the note was decoded in,
    /// before any weighting. A note the history carried teaches nothing, so a wrong entry can't
    /// feed itself. Longer notes count more, up to 8 s.
    static func learningWeight(audioSeconds: Double, languageProbability: Double) -> Double {
        guard languageProbability.isFinite, audioSeconds > 0 else { return 0 }
        let settled = (languageProbability - decisiveProbability) / (certainProbability - decisiveProbability)
        return min(1, audioSeconds / 8) * min(1, max(0, settled))
    }
}
