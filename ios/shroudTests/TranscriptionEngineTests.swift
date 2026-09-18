import Foundation
import Testing
@testable import shroud

/// The engine is a protocol so we can prove the session and quality gate without downloading Whisper.
struct TranscriptionEngineTests {
    @Test
    func sessionUsesTheInjectedEngine() async throws {
        let fake = FakeTranscriptionEngine()
        fake.output = TranscriptionOutput(text: "See you at eight", language: "en", confidence: 0.9)
        let session = TranscriptionSession(engine: fake)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("tx-test.wav")
        FileManager.default.createFile(atPath: url.path, contents: Data(), attributes: nil)
        defer { try? FileManager.default.removeItem(at: url) }

        try await session.prepare(model: .small)
        #expect(fake.prepared == .small)

        let result = try await session.transcribe(fileURL: url, request: .voiceNote(language: "en"))
        #expect(result.text == "See you at eight")
        #expect(fake.lastRequest?.language == "en")
        #expect(fake.lastRequest?.profile == .voiceNote)
    }

    /// The prefetch at record start and the transcription at send each used to start their own
    /// download of a multi-hundred-megabyte model.
    @Test
    func concurrentPreparesShareOneDownload() async throws {
        let engine = SlowEngine()
        let session = TranscriptionSession(engine: engine)
        async let first: Void = session.prepare(model: .small)
        async let second: Void = session.prepare(model: .small)
        async let third: Void = session.prepare(model: .small)
        _ = try await (first, second, third)
        #expect(await engine.prepareCalls == 1)
    }

    @Test
    func aFailedPrepareCanBeRetried() async throws {
        let engine = SlowEngine(failingPrepares: 1)
        let session = TranscriptionSession(engine: engine)
        await #expect(throws: TranscriptionEngineError.self) {
            try await session.prepare(model: .small)
        }
        try await session.prepare(model: .small)
        #expect(await engine.prepareCalls == 2)
    }

    /// One Whisper instance must never decode two notes at once.
    @Test
    func transcriptionsRunOneAtATime() async throws {
        let engine = SlowEngine()
        let session = TranscriptionSession(engine: engine)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("tx-queue.wav")
        async let a = session.transcribe(fileURL: url, request: .voiceNote())
        async let b = session.transcribe(fileURL: url, request: .voiceNote())
        async let c = session.transcribe(samples: [0, 0], sampleRate: 16_000, request: .liveCall())
        let results = try await [a, b, c]
        #expect(results.allSatisfy { $0.text == "ok" })
        #expect(await engine.maxConcurrentTranscriptions == 1)
        #expect(await engine.prepareCalls == 1)
    }

    /// A fake engine must not flag the real Whisper model as installed; that flag decides
    /// whether a note waits for transcription before it is sent.
    @Test
    func aFakeEngineNeverMarksWhisperInstalled() async throws {
        let whisper = TranscriptionSession()
        let before = await whisper.isPrepared
        let fake = TranscriptionSession(engine: SlowEngine())
        try await fake.prepare(model: await whisper.selectedModel)
        #expect(await fake.isPrepared)
        #expect(await whisper.isPrepared == before)
    }

    /// The model is hundreds of megabytes: it must live outside iCloud backups, and a download
    /// made by an earlier build (in Documents) must be moved rather than fetched again.
    @Test
    func modelStorageIsExcludedFromBackupAndAdoptsTheOldDownload() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("tx-storage-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let support = root.appendingPathComponent("Application Support", isDirectory: true)
        let documents = root.appendingPathComponent("Documents", isDirectory: true)
        let legacyModel = documents.appendingPathComponent("huggingface/models/whisper", isDirectory: true)
        try FileManager.default.createDirectory(at: legacyModel, withIntermediateDirectories: true)
        FileManager.default.createFile(
            atPath: legacyModel.appendingPathComponent("weights.bin").path,
            contents: Data([1, 2, 3])
        )

        let base = WhisperKitEngine.modelStorage(support: support, documents: documents)

        #expect(base.path.hasPrefix(support.path))
        #expect(FileManager.default.fileExists(atPath: base.appendingPathComponent("models/whisper/weights.bin").path))
        #expect(!FileManager.default.fileExists(atPath: documents.appendingPathComponent("huggingface").path))
        let values = try base.resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
    }

    @Test
    func liveCallProfileIsStricterOnSilence() {
        #expect(TranscriptionProfile.liveCall.noSpeechThreshold > TranscriptionProfile.voiceNote.noSpeechThreshold)
    }

    @Test
    func modelCatalogIsStableForSettings() {
        #expect(TranscriptionModelID.default == .small)
        #expect(TranscriptionModelID.small.whisperKitName == "small")
        #expect(Set(TranscriptionModelID.allCases.map(\.rawValue)) == ["base", "small", "medium"])
    }
}

final class FakeTranscriptionEngine: TranscriptionEngine, @unchecked Sendable {
    let id = "fake"
    var prepared: TranscriptionModelID?
    var output = TranscriptionOutput(text: "", language: nil, confidence: 0)
    var lastRequest: TranscriptionRequest?

    func prepare(model: TranscriptionModelID, progress: (@Sendable (Double) -> Void)?) async throws {
        progress?(1)
        prepared = model
    }

    func transcribe(fileURL: URL, request: TranscriptionRequest) async throws -> TranscriptionOutput {
        lastRequest = request
        return output
    }

    func transcribe(
        samples: [Float],
        sampleRate: Double,
        request: TranscriptionRequest
    ) async throws -> TranscriptionOutput {
        lastRequest = request
        return output
    }
}

/// Slow on purpose, so overlapping calls actually overlap.
actor SlowEngine: TranscriptionEngine {
    let id = "slow"
    private(set) var prepareCalls = 0
    private(set) var maxConcurrentTranscriptions = 0
    private var running = 0
    private var failingPrepares: Int

    init(failingPrepares: Int = 0) {
        self.failingPrepares = failingPrepares
    }

    func prepare(model: TranscriptionModelID, progress: (@Sendable (Double) -> Void)?) async throws {
        prepareCalls += 1
        try await Task.sleep(for: .milliseconds(80))
        if failingPrepares > 0 {
            failingPrepares -= 1
            throw TranscriptionEngineError.modelUnavailable
        }
    }

    func transcribe(fileURL: URL, request: TranscriptionRequest) async throws -> TranscriptionOutput {
        try await decode()
    }

    func transcribe(
        samples: [Float],
        sampleRate: Double,
        request: TranscriptionRequest
    ) async throws -> TranscriptionOutput {
        try await decode()
    }

    private func decode() async throws -> TranscriptionOutput {
        running += 1
        maxConcurrentTranscriptions = max(maxConcurrentTranscriptions, running)
        try await Task.sleep(for: .milliseconds(60))
        running -= 1
        return TranscriptionOutput(text: "ok", language: "en", confidence: 1)
    }
}
