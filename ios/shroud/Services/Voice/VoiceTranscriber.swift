import Foundation
import Speech

/// On-device speech-to-text for voice messages (Tier 1 — never leaves the device).
/// Human: Uses Apple Speech with `requiresOnDeviceRecognition` when available.
/// Agent: Transcripts stay local unless sealed into the media payload by the caller.
enum VoiceTranscriber {
    enum TranscribeError: Error, LocalizedError {
        case permissionDenied
        case unavailable
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .permissionDenied: "Speech recognition permission is required."
            case .unavailable: "On-device transcription is not available."
            case let .failed(msg): msg
            }
        }
    }

    /// Requests speech authorization if needed.
    static func requestAuthorization() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status)
            }
        }
    }

    /// Transcribes a local audio file. Prefers on-device recognition.
    static func transcribe(fileURL: URL, locale: Locale = .current) async throws -> String {
        let status = await requestAuthorization()
        guard status == .authorized else { throw TranscribeError.permissionDenied }

        guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable else {
            throw TranscribeError.unavailable
        }

        let request = SFSpeechURLRecognitionRequest(url: fileURL)
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }
        request.shouldReportPartialResults = false

        return try await withCheckedThrowingContinuation { cont in
            var finished = false
            recognizer.recognitionTask(with: request) { result, error in
                if finished { return }
                if let error {
                    finished = true
                    cont.resume(throwing: TranscribeError.failed(error.localizedDescription))
                    return
                }
                guard let result, result.isFinal else { return }
                finished = true
                let text = result.bestTranscription.formattedString.trimmingCharacters(in: .whitespacesAndNewlines)
                cont.resume(returning: text)
            }
        }
    }

    /// Convenience: write audio bytes to a temp file, transcribe, delete.
    static func transcribe(audioData: Data, fileExtension: String = "m4a") async throws -> String {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-tx-\(UUID().uuidString).\(fileExtension)")
        try audioData.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        return try await transcribe(fileURL: url)
    }
}
