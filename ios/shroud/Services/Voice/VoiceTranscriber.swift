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

    /// The server re-keyed the note being transcribed; its progress follows the bubble.
    func handOff(from oldID: UUID, to newID: UUID) {
        if messageID == oldID { messageID = newID }
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
        try await transcribe(
            contextualStrings: contextualStrings,
            conversationID: conversationID,
            tracking: tracking,
            session: .shared
        ) { request in
            let output = try await TranscriptionSession.shared.transcribe(fileURL: fileURL, request: request)
            return (output, audioDuration(of: fileURL))
        }
    }

    /// Transcribes a voice note held in memory: a received note or the user's own take.
    ///
    /// Human: The bytes are plaintext audio, so they are decoded in memory and never written to a
    /// file; everything on disk must be sealed with the history key (`docs/architecture.md`,
    /// security invariants 1 and 4). This used to write `tmp/shroud-tx-<uuid>.m4a` for Whisper.
    /// Agent: READS audioData; CALLS InMemoryAudioDecoder.decode off the main actor, then
    /// session.transcribe(samples:sampleRate:); WRITES no file. `session` is a test seam.
    static func transcribe(
        audioData: Data,
        contextualStrings: [String] = [],
        conversationID: UUID? = nil,
        tracking: UUID? = nil,
        session: TranscriptionSession = .shared
    ) async throws -> String {
        try await transcribe(
            contextualStrings: contextualStrings,
            conversationID: conversationID,
            tracking: tracking,
            session: session
        ) { request in
            // Human: Decoding a long note takes a moment; keep it off the main actor.
            let pcm = try await Task.detached(priority: .userInitiated) {
                try InMemoryAudioDecoder.decode(audioData)
            }.value
            let output = try await session.transcribe(
                samples: pcm.samples,
                sampleRate: pcm.sampleRate,
                request: request
            )
            return (output, pcm.seconds)
        }
    }

    /// Shared by both entry points: progress for the bubble, the model, the decode, the cleanup.
    ///
    /// Agent: CALLS session.prepare (may download Whisper), then `decode` with the voice-note
    /// request; WRITES TranscriptionLanguageMemory when the note settled its language.
    private static func transcribe(
        contextualStrings: [String],
        conversationID: UUID?,
        tracking: UUID?,
        session: TranscriptionSession,
        decode: (TranscriptionRequest) async throws -> (output: TranscriptionOutput, seconds: Double)
    ) async throws -> String {
        TranscriptionModelInstall.shared.begin(messageID: tracking)
        defer { Task { @MainActor in TranscriptionModelInstall.shared.finish() } }

        do {
            try await session.prepare { fraction in
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

        let decoded: (output: TranscriptionOutput, seconds: Double)
        do {
            decoded = try await decode(voiceNoteRequest(
                contextualStrings: contextualStrings,
                conversationID: conversationID
            ))
        } catch let error as TranscriptionEngineError {
            throw map(error)
        } catch {
            throw TranscribeError.failed(error.localizedDescription)
        }

        let output = decoded.output
        let text = VoiceTranscript.cleaned(output.text)
        if let code = output.language, let heard = output.languageProbability, !text.isEmpty {
            let weight = VoiceTranscript.learningWeight(audioSeconds: decoded.seconds, languageProbability: heard)
            if weight > 0 {
                TranscriptionLanguageMemory.record(languageCode: code, peerID: conversationID, weight: weight)
            }
        }
        return text
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

    /// The pin, else one decode in the language Whisper detects, weighed with the device's
    /// languages and this chat's history (`SpokenLanguagePick`). A note is never decoded again in
    /// a language Whisper did not hear: forced, it translates.
    private static func voiceNoteRequest(
        contextualStrings: [String],
        conversationID: UUID?
    ) -> TranscriptionRequest {
        TranscriptionRequest.voiceNote(
            language: TranscriptionLanguage.pinnedLanguage,
            hints: contextualStrings,
            candidateLanguages: TranscriptionLanguage.detectionCandidates(TranscriptionLanguage.deviceLanguages),
            languageHistory: TranscriptionLanguageMemory.history(peerID: conversationID)
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
