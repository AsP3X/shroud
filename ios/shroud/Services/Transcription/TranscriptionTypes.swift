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
