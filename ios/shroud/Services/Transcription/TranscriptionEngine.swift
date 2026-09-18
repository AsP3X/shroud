import Foundation

/// The only contract chat, recording, and (later) live calls talk to.
///
/// Harmony called WhisperKit from a pile of static helpers, so swapping models or adding a
/// second backend meant rewriting every call site. A new engine is a new type that satisfies
/// this protocol; `TranscriptionSession` is the single owner.
/// `nonisolated`: engines are actors of their own, not main-actor types (the app's default).
nonisolated protocol TranscriptionEngine: Sendable {
    var id: String { get }

    func prepare(
        model: TranscriptionModelID,
        progress: (@Sendable (Double) -> Void)?
    ) async throws

    func transcribe(fileURL: URL, request: TranscriptionRequest) async throws -> TranscriptionOutput

    /// 16 kHz mono PCM in [-1, 1]. Used by live-call chunking without writing temp files.
    func transcribe(samples: [Float], sampleRate: Double, request: TranscriptionRequest) async throws -> TranscriptionOutput
}
