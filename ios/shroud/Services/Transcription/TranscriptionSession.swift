import Foundation

/// App-wide owner of the active engine and the currently loaded model.
///
/// Swap the engine in tests (or later, in Settings) without ConversationView knowing.
///
/// Human: Actor methods are re-entrant across `await`, so without the bookkeeping below two
/// callers (the prefetch at record start and the transcription at send) would each start their
/// own multi-hundred-megabyte download, and two notes could decode at once on one Whisper
/// instance. `prepare` is single-flight per model; transcriptions run one at a time, in order.
actor TranscriptionSession {
    static let shared = TranscriptionSession()

    private let modelDefaultsKey = "transcription.model"
    private var engine: any TranscriptionEngine
    private var loaded: TranscriptionModelID?
    /// The download/load in flight; later callers for the same model wait on it.
    private var preparing: (model: TranscriptionModelID, task: Task<Void, Error>)?
    /// Completes when the most recently queued transcription has finished.
    private var queueTail: Task<Void, Never>?

    init(engine: any TranscriptionEngine = WhisperKitEngine()) {
        self.engine = engine
    }

    /// Replace the backend. The next `prepare` loads its model. Used by tests and, later, Settings.
    func use(_ engine: any TranscriptionEngine) {
        preparing?.task.cancel()
        preparing = nil
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

    /// "This model was downloaded before" marker, per engine, so a test double can never mark
    /// the real Whisper model as installed (that made guarded tests download it mid-run).
    private var readyKey: String { "transcription.\(engine.id).ready" }

    var isPrepared: Bool {
        loaded != nil || UserDefaults.standard.string(forKey: readyKey) == selectedModel.rawValue
    }

    func prepare(
        model: TranscriptionModelID? = nil,
        progress: (@Sendable (Double) -> Void)? = nil
    ) async throws {
        let target = model ?? selectedModel
        if loaded == target { return }
        if let preparing, preparing.model == target {
            try await preparing.task.value
            return
        }
        let engine = self.engine
        let task = Task { try await engine.prepare(model: target, progress: progress) }
        preparing = (target, task)
        do {
            try await task.value
        } catch {
            if preparing?.model == target { preparing = nil }
            throw error
        }
        if preparing?.model == target { preparing = nil }
        // `use(_:)` may have swapped the backend while we were downloading.
        guard self.engine.id == engine.id else { return }
        loaded = target
        UserDefaults.standard.set(target.rawValue, forKey: readyKey)
    }

    func transcribe(fileURL: URL, request: TranscriptionRequest) async throws -> TranscriptionOutput {
        try await prepare()
        let engine = self.engine
        return try await oneAtATime {
            try await engine.transcribe(fileURL: fileURL, request: request)
        }
    }

    func transcribe(
        samples: [Float],
        sampleRate: Double,
        request: TranscriptionRequest
    ) async throws -> TranscriptionOutput {
        try await prepare()
        let engine = self.engine
        return try await oneAtATime {
            try await engine.transcribe(samples: samples, sampleRate: sampleRate, request: request)
        }
    }

    func detectLanguage(fileURL: URL) async -> String? {
        try? await transcribe(fileURL: fileURL, request: .detectLanguage()).language
    }

    /// Runs `work` after every transcription queued before it. Whisper keeps the whole model in
    /// memory and one instance must not decode two notes at once.
    private func oneAtATime<T: Sendable>(
        _ work: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        let previous = queueTail
        let job = Task {
            await previous?.value
            try Task.checkCancellation()
            return try await work()
        }
        queueTail = Task { _ = try? await job.value }
        return try await withTaskCancellationHandler {
            try await job.value
        } onCancel: {
            job.cancel()
        }
    }
}
