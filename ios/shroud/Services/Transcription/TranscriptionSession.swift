import Foundation

/// App-wide owner of the active engine and the currently loaded model.
///
/// Swap the engine in tests (or later, in Settings) without ConversationView knowing.
actor TranscriptionSession {
    static let shared = TranscriptionSession()

    private let modelDefaultsKey = "transcription.model"
    private let readyKey = "transcription.whisper.ready"
    private var engine: any TranscriptionEngine
    private var loaded: TranscriptionModelID?

    init(engine: any TranscriptionEngine = WhisperKitEngine()) {
        self.engine = engine
    }

    /// Replace the backend. The next `prepare` loads its model. Used by tests and, later, Settings.
    func use(_ engine: any TranscriptionEngine) {
        self.engine = engine
        loaded = nil
    }

    var selectedModel: TranscriptionModelID {
        if let raw = UserDefaults.standard.string(forKey: modelDefaultsKey),
           let model = TranscriptionModelID(rawValue: raw)
        {
            return model
        }
        return .default
    }

    func selectModel(_ model: TranscriptionModelID) {
        UserDefaults.standard.set(model.rawValue, forKey: modelDefaultsKey)
    }

    var isPrepared: Bool {
        loaded != nil || UserDefaults.standard.string(forKey: readyKey) == selectedModel.rawValue
    }

    func prepare(
        model: TranscriptionModelID? = nil,
        progress: (@Sendable (Double) -> Void)? = nil
    ) async throws {
        let target = model ?? selectedModel
        if loaded == target { return }
        try await engine.prepare(model: target, progress: progress)
        loaded = target
        UserDefaults.standard.set(target.rawValue, forKey: readyKey)
    }

    func transcribe(fileURL: URL, request: TranscriptionRequest) async throws -> TranscriptionOutput {
        try await prepare()
        return try await engine.transcribe(fileURL: fileURL, request: request)
    }

    func transcribe(
        samples: [Float],
        sampleRate: Double,
        request: TranscriptionRequest
    ) async throws -> TranscriptionOutput {
        try await prepare()
        return try await engine.transcribe(samples: samples, sampleRate: sampleRate, request: request)
    }

    func detectLanguage(fileURL: URL) async -> String? {
        try? await transcribe(fileURL: fileURL, request: .detectLanguage()).language
    }
}
