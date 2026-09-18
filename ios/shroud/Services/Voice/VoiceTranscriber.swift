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
        case permissionDenied
        case unavailable
        case modelUnavailable
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .permissionDenied:
                "Speech recognition permission is required."
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
        await TranscriptionModelInstall.shared.begin(messageID: tracking)
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

        await TranscriptionModelInstall.shared.transcribing()

        let languageHint = TranscriptionLanguage.override.flatMap {
            $0.language.languageCode?.identifier
        }
        let request = TranscriptionRequest.voiceNote(language: languageHint, hints: contextualStrings)
        let output: TranscriptionOutput
        do {
            output = try await TranscriptionSession.shared.transcribe(fileURL: fileURL, request: request)
        } catch let error as TranscriptionEngineError {
            throw map(error)
        } catch {
            throw TranscribeError.failed(error.localizedDescription)
        }

        let text = VoiceTranscript.cleaned(output.text)
        if let code = output.language ?? languageHint, !text.isEmpty {
            let duration = audioDuration(of: fileURL)
            let score = VoiceTranscript.score(
                text: text,
                modelConfidence: output.confidence,
                languageProbability: output.language == nil ? 0.5 : 0.9,
                prior: TranscriptionLanguageMemory.prior(for: code, peerID: conversationID),
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
        let alreadyBusy = await MainActor.run { TranscriptionModelInstall.shared.isBusy }
        if !alreadyBusy {
            await TranscriptionModelInstall.shared.begin(messageID: nil)
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
            await TranscriptionModelInstall.shared.transcribing()
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
        if let override = TranscriptionLanguage.override { return [override] }
        return TranscriptionLanguage.whisperLocales
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
