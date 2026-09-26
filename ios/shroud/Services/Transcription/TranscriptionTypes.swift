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
    var profile: TranscriptionProfile
    /// Optional clip in seconds, e.g. `0...8` for language detection.
    var clipSeconds: ClosedRange<Double>?

    static func voiceNote(language: String? = nil, hints: [String] = []) -> TranscriptionRequest {
        TranscriptionRequest(language: language, hints: hints, profile: .voiceNote, clipSeconds: nil)
    }

    static func liveCall(language: String? = nil) -> TranscriptionRequest {
        TranscriptionRequest(language: language, hints: [], profile: .liveCall, clipSeconds: nil)
    }

    static func detectLanguage(clipSeconds: Double = 8) -> TranscriptionRequest {
        TranscriptionRequest(language: nil, hints: [], profile: .voiceNote, clipSeconds: 0...clipSeconds)
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
enum VoiceNoteSeek {
    /// Whisper's timestamp grid. The engine passes its own constant as well.
    static let secondsPerTimestamp = 0.02
    /// A shorter tail than this stays with the pass that already decoded it.
    static let minimumTailSeconds = 0.2

    static func resumeSample(
        tokens: [Int],
        timeTokenBegin: Int,
        sampleRate: Int,
        windowStart: Int,
        segmentSamples: Int,
        engineSeek: Int,
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

/// The language token from the first window. Later windows detect again, and a quiet
/// tail must not replace the language the opening of the note already settled.
enum WhisperLanguageToken {
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
enum WhisperReportedLanguage {
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
