import AVFoundation
import Foundation
import Observation

/// Live install/transcribe status for the voice-bubble progress UI.
///
/// Human: The Whisper model is a few hundred megabytes. A spinner that says "Transcribing…"
/// while the phone is actually downloading looks like a hang. This is the single source of
/// that progress so the bubble that kicked off the job can show a determinate bar.
@MainActor
@Observable
final class TranscriptionModelInstall {
    static let shared = TranscriptionModelInstall()

    enum Phase: Equatable {
        case idle
        case downloading
        case transcribing
    }

    private(set) var phase: Phase = .idle
    /// 0…1 while `phase == .downloading`. Ignored when `isDeterminate` is false.
    private(set) var fractionCompleted: Double = 0
    private(set) var isDeterminate = false
    private(set) var languageName: String?
    private(set) var messageID: UUID?
    private var sessionCount = 0

    var isBusy: Bool { phase != .idle }

    func isActive(for id: UUID) -> Bool {
        isBusy && messageID == id
    }

    func begin(messageID: UUID?) {
        sessionCount += 1
        if let messageID { self.messageID = messageID }
        if phase == .idle {
            phase = .transcribing
            fractionCompleted = 0
            isDeterminate = false
            languageName = nil
        }
    }

    func downloading(languageName: String, fraction: Double, determinate: Bool) {
        phase = .downloading
        self.languageName = languageName
        isDeterminate = determinate
        fractionCompleted = min(1, max(0, fraction))
    }

    func transcribing() {
        phase = .transcribing
        fractionCompleted = 1
    }

    func finish() {
        sessionCount = max(0, sessionCount - 1)
        guard sessionCount == 0 else { return }
        phase = .idle
        fractionCompleted = 0
        isDeterminate = false
        languageName = nil
        messageID = nil
    }
}

/// App-facing transcription API. Chat and recording keep calling this; the engine behind it
/// is `TranscriptionSession` so we can swap Whisper models (or the whole backend) without
/// rewriting ConversationView.
///
/// Human: Harmony's helper called WhisperKit from every site and hard-coded decode knobs.
/// This facade is the same shape the rest of Shroud already uses, and the improvable part
/// lives under `Services/Transcription`.
enum VoiceTranscriber {
    enum TranscribeError: Error, LocalizedError {
        case unavailable
        case modelUnavailable
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .unavailable:
                TranscriptionEngineError.unavailable.errorDescription
            case .modelUnavailable:
                TranscriptionEngineError.modelUnavailable.errorDescription
            case let .failed(msg): msg
            }
        }
    }

    /// Transcribes a local audio file on-device.
    static func transcribe(
        fileURL: URL,
        contextualStrings: [String] = [],
        conversationID: UUID? = nil,
        tracking: UUID? = nil
    ) async throws -> String {
        TranscriptionModelInstall.shared.begin(messageID: tracking)
        defer { Task { @MainActor in TranscriptionModelInstall.shared.finish() } }

        do {
            try await TranscriptionSession.shared.prepare { fraction in
                Task { @MainActor in
                    TranscriptionModelInstall.shared.downloading(
                        languageName: "Whisper",
                        fraction: fraction,
                        determinate: fraction > 0
                    )
                }
            }
        } catch let error as TranscriptionEngineError {
            throw map(error)
        } catch {
            throw TranscribeError.modelUnavailable
        }

        TranscriptionModelInstall.shared.transcribing()

        let duration = audioDuration(of: fileURL)
        let hints = TranscriptionLanguage.decodeHints(peerID: conversationID)
        let output: TranscriptionOutput
        do {
            output = try await decodeVoiceNote(
                fileURL: fileURL,
                hints: hints,
                contextualStrings: contextualStrings,
                conversationID: conversationID,
                duration: duration
            )
        } catch let error as TranscriptionEngineError {
            throw map(error)
        } catch {
            throw TranscribeError.failed(error.localizedDescription)
        }

        let winner = VoiceTranscript.Candidate(
            text: output.text,
            language: output.language,
            confidence: output.confidence
        )
        let text = VoiceTranscript.cleaned(winner.text)
        if let code = winner.language, !text.isEmpty {
            let score = VoiceTranscript.score(
                candidate: VoiceTranscript.Candidate(
                    text: text, language: code, confidence: winner.confidence
                ),
                peerID: conversationID,
                audioSeconds: duration
            )
            let weight = VoiceTranscript.learningWeight(audioSeconds: duration, score: score)
            if weight > 0 {
                TranscriptionLanguageMemory.record(languageCode: code, peerID: conversationID, weight: weight)
            }
        }
        return text
    }

    static func transcribe(
        audioData: Data,
        fileExtension: String = "m4a",
        contextualStrings: [String] = [],
        conversationID: UUID? = nil,
        tracking: UUID? = nil
    ) async throws -> String {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-tx-\(UUID().uuidString).\(fileExtension)")
        try audioData.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        return try await transcribe(
            fileURL: url,
            contextualStrings: contextualStrings,
            conversationID: conversationID,
            tracking: tracking
        )
    }

    @discardableResult
    static func prepareModel(locale: Locale? = nil) async -> Bool {
        let alreadyBusy = TranscriptionModelInstall.shared.isBusy
        if !alreadyBusy {
            TranscriptionModelInstall.shared.begin(messageID: nil)
        }
        defer {
            if !alreadyBusy {
                Task { @MainActor in TranscriptionModelInstall.shared.finish() }
            }
        }
        do {
            try await TranscriptionSession.shared.prepare { fraction in
                Task { @MainActor in
                    TranscriptionModelInstall.shared.downloading(
                        languageName: "Whisper",
                        fraction: fraction,
                        determinate: fraction > 0
                    )
                }
            }
            TranscriptionModelInstall.shared.transcribing()
            return true
        } catch {
            return false
        }
    }

    static func availableLocales() async -> [Locale] {
        TranscriptionLanguage.whisperLocales
    }

    /// Whisper has no one-minute ceiling; kept so older call sites still compile.
    static func supportsLongForm(locale: Locale = .current) async -> Bool { true }

    static func modelIsInstalled(locale: Locale = .current) async -> Bool {
        await TranscriptionSession.shared.isPrepared
    }

    static func candidateLocales() async -> [Locale] {
        let hints = TranscriptionLanguage.decodeHints(peerID: nil)
        if hints.isEmpty { return TranscriptionLanguage.whisperLocales }
        return hints.map { Locale(identifier: $0) }
    }

    /// Auto-detect, then a forced second pass when Whisper's English bias disagrees with a hint.
    private static func decodeVoiceNote(
        fileURL: URL,
        hints: [String],
        contextualStrings: [String],
        conversationID: UUID?,
        duration: Double
    ) async throws -> TranscriptionOutput {
        func run(language: String?) async throws -> TranscriptionOutput {
            let request = TranscriptionRequest.voiceNote(language: language, hints: contextualStrings)
            return try await TranscriptionSession.shared.transcribe(fileURL: fileURL, request: request)
        }

        if TranscriptionLanguage.override != nil, let forced = hints.first {
            return try await run(language: forced)
        }

        if let trusted = hints.first,
           TranscriptionLanguage.shouldForceLanguage(
            trusted,
            prior: TranscriptionLanguageMemory.prior(for: trusted, peerID: conversationID)
           )
        {
            let forced = try await run(language: trusted)
            let forcedScore = VoiceTranscript.score(
                candidate: VoiceTranscript.Candidate(
                    text: forced.text, language: trusted, confidence: forced.confidence
                ),
                peerID: conversationID,
                audioSeconds: duration
            )
            if forcedScore >= VoiceTranscript.minimumTrustedScore {
                return TranscriptionOutput(text: forced.text, language: trusted, confidence: forced.confidence)
            }
            let auto = try await run(language: nil)
            let chosen = VoiceTranscript.choose(
                auto: VoiceTranscript.Candidate(
                    text: auto.text, language: auto.language, confidence: auto.confidence
                ),
                challenge: VoiceTranscript.Candidate(
                    text: forced.text, language: trusted, confidence: forced.confidence
                ),
                peerID: conversationID,
                audioSeconds: duration
            )
            return TranscriptionOutput(
                text: chosen.text, language: chosen.language, confidence: chosen.confidence
            )
        }

        let auto = try await run(language: nil)
        guard let challenger = TranscriptionLanguage.challenger(detected: auto.language, hints: hints)
        else { return auto }

        let alt = try await run(language: challenger)
        let chosen = VoiceTranscript.choose(
            auto: VoiceTranscript.Candidate(
                text: auto.text, language: auto.language, confidence: auto.confidence
            ),
            challenge: VoiceTranscript.Candidate(
                text: alt.text, language: challenger, confidence: alt.confidence
            ),
            peerID: conversationID,
            audioSeconds: duration
        )
        return TranscriptionOutput(
            text: chosen.text, language: chosen.language, confidence: chosen.confidence
        )
    }

    private static func map(_ error: TranscriptionEngineError) -> TranscribeError {
        switch error {
        case .unavailable: .unavailable
        case .modelUnavailable: .modelUnavailable
        case let .failed(message): .failed(message)
        }
    }

    private static func audioDuration(of url: URL) -> Double {
        guard let file = try? AVAudioFile(forReading: url), file.processingFormat.sampleRate > 0
        else { return 0 }
        return Double(file.length) / file.processingFormat.sampleRate
    }
}
