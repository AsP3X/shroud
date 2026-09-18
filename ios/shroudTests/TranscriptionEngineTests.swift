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
