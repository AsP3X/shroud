import Foundation

/// Named Whisper weights. Adding a case is how we ship a better model later without
/// touching chat or recording code.
nonisolated enum TranscriptionModelID: String, CaseIterable, Sendable {
    case base
    case small
    case medium

    /// Voice notes default to `small`: a real jump over Apple Speech, still a one-time download.
    static let `default`: TranscriptionModelID = .small

    var displayName: String {
        switch self {
        case .base: "Base"
        case .small: "Small"
        case .medium: "Medium"
        }
    }

    /// Identifier WhisperKit's Hugging Face repo understands (`small` matches `openai_whisper-small`).
    var whisperKitName: String { rawValue }
}

/// Decode knobs that differ between a finished voice note and a live-call chunk.
/// Harmony buried these in the helper; keeping them named is what makes the engine tunable.
nonisolated struct TranscriptionProfile: Sendable, Equatable {
    var compressionRatioThreshold: Float
    var logProbThreshold: Float
    var firstTokenLogProbThreshold: Float
    var noSpeechThreshold: Float
    var windowClipTime: Float

    /// Finished voice notes — Harmony's voice-note preset, slightly stricter than Whisper defaults.
    static let voiceNote = TranscriptionProfile(
        compressionRatioThreshold: 2.2,
        logProbThreshold: -0.6,
        firstTokenLogProbThreshold: -1.2,
        noSpeechThreshold: 0.5,
        windowClipTime: 1.0
    )

    /// Short live-call windows — Harmony's call preset; skip silence harder so chunks don't hallucinate.
    static let liveCall = TranscriptionProfile(
        compressionRatioThreshold: 2.2,
        logProbThreshold: -0.6,
        firstTokenLogProbThreshold: -1.2,
        noSpeechThreshold: 0.75,
        windowClipTime: 1.0
    )
}

nonisolated struct TranscriptionRequest: Sendable {
    /// BCP-47 language code (`de`, `en`). Nil means the engine should detect.
    var language: String?
    /// Contact names and other terms to bias toward. Engines may ignore this.
    var hints: [String]
    /// The device's languages. Detection weighs Whisper's probabilities with them (`SpokenLanguagePick`).
    var candidateLanguages: [String] = []
    /// The languages heard in this chat, by weight. Detection leans on them for an unsure note.
    var languageHistory: [String: Double] = [:]
    var profile: TranscriptionProfile
    /// Optional clip in seconds, e.g. `0...8` for language detection.
    var clipSeconds: ClosedRange<Double>?

    static func voiceNote(
        language: String? = nil,
        hints: [String] = [],
        candidateLanguages: [String] = [],
        languageHistory: [String: Double] = [:]
    ) -> TranscriptionRequest {
        TranscriptionRequest(
            language: language,
            hints: hints,
            candidateLanguages: candidateLanguages,
            languageHistory: languageHistory,
            profile: .voiceNote,
            clipSeconds: nil
        )
    }

    static func liveCall(language: String? = nil) -> TranscriptionRequest {
        TranscriptionRequest(language: language, hints: [], profile: .liveCall, clipSeconds: nil)
    }

    static func detectLanguage(clipSeconds: Double = 8) -> TranscriptionRequest {
        TranscriptionRequest(language: nil, hints: [], profile: .voiceNote, clipSeconds: 0...clipSeconds)
    }
}

/// The spoken language from Whisper's language probabilities.
///
/// The audio decides; what is known about this person only weighs it. The candidates are the
/// device's languages and English: a language outside them needs `outsideCandidateOdds` times the
/// probability. A chat's history makes the language it is spoken in up to `1 + historyOdds` times
/// likelier, at full strength once `historySaturation` notes' worth has been heard. That settles a
/// short note Whisper is unsure about, and a clear note in another language still wins. With no
/// candidates and no history the most likely language wins. Same rule as the web
/// (`pickSpokenLanguage`) and Android.
nonisolated enum SpokenLanguagePick {
    static let outsideCandidateOdds = 5.0
    static let historyOdds = 2.0
    static let historySaturation = 3.0

    /// Whisper's code where it differs from the ISO code the hints use.
    private static let whisperCode = ["nb": "no"]

    static func pick(
        probabilities: [String: Double],
        candidates: [String],
        history: [String: Double] = [:]
    ) -> String? {
        let allowed = Set(candidates.map { whisperCode[$0] ?? $0 })
        var heard: [String: Double] = [:]
        for (code, weight) in history where weight > 0 {
            heard[whisperCode[code] ?? code, default: 0] += weight
        }
        let total = heard.values.reduce(0, +)
        let strength = historyOdds * min(1, total / historySaturation)
        func score(_ code: String, _ probability: Double) -> Double {
            let known = allowed.isEmpty || allowed.contains(code) ? 1 : 1 / outsideCandidateOdds
            let share = total > 0 ? (heard[code] ?? 0) / total : 0
            return probability * known * (1 + strength * share)
        }
        // Ties go to the smaller code, so the pick never depends on dictionary order.
        return probabilities
            .filter { $0.value.isFinite }
            .max { lhs, rhs in
                let left = score(lhs.key, lhs.value)
                let right = score(rhs.key, rhs.value)
                return left == right ? lhs.key > rhs.key : left < right
            }?
            .key
    }
}

/// How a request is handed to Whisper. A finished voice note must cover the whole file:
/// timestamps let the decoder continue after it stops early, and the tail is not clipped.
/// A short language probe and a live-call chunk keep the old single-pass settings.
nonisolated struct WhisperDecodePlan: Equatable, Sendable {
    var keepTimestamps: Bool
    /// Seconds cut off the end of each window. Zero for a voice note, so the last words stay.
    var tailClipSeconds: Float
    var useVoiceActivityChunking: Bool

    static func make(for request: TranscriptionRequest) -> WhisperDecodePlan {
        let wholeVoiceNote = request.profile == .voiceNote && request.clipSeconds == nil
        if wholeVoiceNote {
            return WhisperDecodePlan(
                keepTimestamps: true,
                tailClipSeconds: 0,
                useVoiceActivityChunking: false
            )
        }
        return WhisperDecodePlan(
            keepTimestamps: false,
            tailClipSeconds: request.profile.windowClipTime,
            useVoiceActivityChunking: request.clipSeconds == nil
        )
    }
}

/// Where to resume after Whisper stops inside a window.
///
/// With no timestamps, an early end (a pause, or the token budget on a dense
/// language) is treated as the end of the whole window and the rest is skipped.
/// A timestamp says how far the words actually reached, so the next pass starts there.
nonisolated enum VoiceNoteSeek {
    /// Whisper's timestamp grid. The engine passes its own constant as well.
    static let secondsPerTimestamp = 0.02
    /// A shorter tail than this stays with the pass that already decoded it.
    static let minimumTailSeconds = 0.2
    /// Voice the rest of the note must still hold for a resume. Whisper's last timestamp lands a
    /// little before the voice fades, and decoding only that fade repeats a word or invents one.
    static let minimumVoiceSeconds = 1.0

    /// `voicedEnd` is where the note's voice ends (`VoiceActivity`). With less than
    /// `minimumVoiceSeconds` left before it, the note is finished: decoding that tail only makes
    /// Whisper describe the silence.
    static func resumeSample(
        tokens: [Int],
        timeTokenBegin: Int,
        sampleRate: Int,
        windowStart: Int,
        segmentSamples: Int,
        engineSeek: Int,
        voicedEnd: Int? = nil,
        secondsPerTimestamp: Double = secondsPerTimestamp
    ) -> Int? {
        guard segmentSamples > 0, sampleRate > 0, secondsPerTimestamp > 0 else { return nil }
        // The stock seeker already continued inside the window.
        guard engineSeek >= windowStart + segmentSamples else { return nil }
        let steps = lastTimestampSteps(in: tokens, timeTokenBegin: timeTokenBegin)
        guard steps > 0 else { return nil }
        let resume = windowStart + Int((Double(steps) * secondsPerTimestamp * Double(sampleRate)).rounded())
        let minimumTail = Int((minimumTailSeconds * Double(sampleRate)).rounded())
        guard resume > windowStart, resume + minimumTail < windowStart + segmentSamples else { return nil }
        if let voicedEnd, resume + Int((minimumVoiceSeconds * Double(sampleRate)).rounded()) > voicedEnd {
            return nil
        }
        return resume
    }

    /// A segment the model closed with a timestamp. Anything after that timestamp
    /// is decoded again from the resume point, so it must not stay in this segment.
    static func isFinishedSegment(tokens: [Int], timeTokenBegin: Int, endToken: Int) -> Bool {
        var body = tokens
        while body.last == endToken { body.removeLast() }
        guard let last = body.last else { return false }
        return last >= timeTokenBegin
    }

    /// Drops words that sit past the last timestamp. Those words are decoded again
    /// when the window resumes, and keeping them would repeat them.
    static func tokensThroughLastTimestamp(_ tokens: [Int], timeTokenBegin: Int, endToken: Int) -> [Int] {
        if isFinishedSegment(tokens: tokens, timeTokenBegin: timeTokenBegin, endToken: endToken) {
            return tokens
        }
        guard let lastTime = tokens.lastIndex(where: { $0 >= timeTokenBegin }),
              tokens[lastTime] != timeTokenBegin
        else { return tokens }
        var kept = Array(tokens[...lastTime])
        if tokens.last == endToken {
            kept.append(endToken)
        }
        return kept
    }

    private static func lastTimestampSteps(in tokens: [Int], timeTokenBegin: Int) -> Int {
        guard let last = tokens.last(where: { $0 >= timeTokenBegin }) else { return 0 }
        return max(0, last - timeTokenBegin)
    }
}

/// Where the voice in a note ends, from its loudness: 20 ms frames against the note's own noise
/// floor and speech level. A voice note usually ends with a second or two of room tone before the
/// button is released; Whisper fed that silence writes "[MUSIK]", "Thank you." or a subtitle credit.
nonisolated enum VoiceActivity {
    static let frameSeconds = 0.02
    /// Quiet kept after the last voiced frame, so a soft last syllable is not clipped.
    static let hangoverSeconds = 0.3
    /// Below this level (about −50 dBFS) a note is all quiet, and nothing is cut.
    static let silentLevel: Float = 0.003

    /// The sample just past the last voiced frame, or nil when no frame stands out as voice.
    static func voicedEnd(_ samples: [Float], sampleRate: Int) -> Int? {
        let frame = max(1, Int(Double(sampleRate) * frameSeconds))
        guard samples.count >= frame else { return nil }
        var levels: [Float] = []
        levels.reserveCapacity(samples.count / frame)
        var start = 0
        while start + frame <= samples.count {
            var sum: Float = 0
            for index in start ..< start + frame {
                sum += samples[index] * samples[index]
            }
            levels.append((sum / Float(frame)).squareRoot())
            start += frame
        }
        let sorted = levels.sorted()
        let floor = sorted[sorted.count / 10]
        // The 99th percentile, so a single click does not set the speech level.
        let speech = sorted[min(sorted.count - 1, sorted.count * 99 / 100)]
        guard speech >= silentLevel else { return nil }
        // Voice is well above the room (12 dB) and not far below the note's speech (30 dB).
        let threshold = max(floor * 4, speech * 0.03, silentLevel / 2)
        guard let last = levels.lastIndex(where: { $0 > threshold }) else { return nil }
        return min(samples.count, (last + 1) * frame)
    }

    /// How close to the voice's end a segment may start and still hold a word.
    static let segmentStartToleranceSeconds = 0.15

    /// False for a segment Whisper starts once the voice has ended: it describes the silence
    /// ("Copyright WDR 2020", "[MUSIK]"), since no word starts after the last voiced frame.
    static func isSpoken(segmentStart: Double, voicedEndSeconds: Double) -> Bool {
        segmentStart < voicedEndSeconds - segmentStartToleranceSeconds
    }

    /// The note through `voicedEnd`, then its hangover faded to silence. Room noise after the last
    /// word is what Whisper describes; the silence Whisper pads a window with is not.
    static func trimmed(_ samples: [Float], voicedEnd: Int, sampleRate: Int) -> [Float] {
        let end = min(samples.count, voicedEnd + Int(Double(sampleRate) * hangoverSeconds))
        var kept = Array(samples.prefix(end))
        let fade = end - voicedEnd
        if fade > 0 {
            for offset in 0 ..< fade {
                kept[voicedEnd + offset] *= Float(fade - offset) / Float(fade + 1)
            }
        }
        return kept
    }
}

/// The language token from the first window. Later windows detect again, and a quiet
/// tail must not replace the language the opening of the note already settled.
nonisolated enum WhisperLanguageToken {
    static func code(from tokenText: String) -> String? {
        let trimmed = tokenText.trimmingCharacters(in: .whitespacesAndNewlines)
        let inner: Substring
        if trimmed.hasPrefix("<|"), trimmed.hasSuffix("|>"), trimmed.count > 4 {
            inner = trimmed.dropFirst(2).dropLast(2)
        } else {
            inner = Substring(trimmed)
        }
        guard inner.count == 2, inner.allSatisfy(\.isLetter) else { return nil }
        return inner.lowercased()
    }

    static func firstCode(in tokenTexts: [String]) -> String? {
        for text in tokenTexts {
            if let code = code(from: text) { return code }
        }
        return nil
    }
}

/// Which language a finished note reports.
///
/// A language the caller asked for wins. Otherwise the token from the opening
/// of the note wins over the engine's file-level label, because a later window
/// detects again and that label is the last window.
nonisolated enum WhisperReportedLanguage {
    /// A two-letter code Whisper can be asked for, or nil.
    static func code(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let normalized = TranscriptionLanguage.normalize(raw)
        guard normalized.count == 2, normalized.allSatisfy(\.isLetter) else { return nil }
        return normalized
    }

    static func choose(forced: String?, openingToken: String?, reported: String?) -> String? {
        if let forced = code(forced) { return forced }
        if let opening = code(openingToken) { return opening }
        return code(reported)
    }
}

nonisolated struct TranscriptionOutput: Sendable, Equatable {
    var text: String
    var language: String?
    var confidence: Double
    /// What the audio alone gave `language` when the engine detected it; nil when it was asked for one.
    var languageProbability: Double? = nil
}

nonisolated enum TranscriptionEngineError: Error, LocalizedError, Equatable {
    case unavailable
    case modelUnavailable
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .unavailable:
            "On-device transcription is not available on this device."
        case .modelUnavailable:
            "Couldn't download the transcription model. Check your connection and try again."
        case let .failed(message):
            message
        }
    }
}
