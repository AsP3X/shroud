import CoreML
import Foundation
import WhisperKit

/// WhisperKit adapter. This is the only file that may import WhisperKit — every other
/// transcription type talks the protocol, so a future engine (Parakeet, a bigger Whisper,
/// a local server) does not leak into chat.
actor WhisperKitEngine: TranscriptionEngine {
    let id = "whisperkit"
    private var preparedModel: TranscriptionModelID?
    private var kit: WhisperKit?

    func prepare(
        model: TranscriptionModelID,
        progress: (@Sendable (Double) -> Void)?
    ) async throws {
        if preparedModel == model, kit != nil { return }

        do {
            let storage = Self.modelStorage()
            let folder = try await WhisperKit.download(
                variant: model.whisperKitName,
                downloadBase: storage,
                progressCallback: { p in
                    progress?(p.fractionCompleted)
                }
            )
            let config = Self.makeConfig(modelFolder: folder.path, downloadBase: storage)
            let loaded = try await WhisperKit(config)
            // Swap only once the new model is ready, so a switch never strands a running note.
            kit = loaded
            preparedModel = model
        } catch {
            throw TranscriptionEngineError.modelUnavailable
        }
    }

    func transcribe(fileURL: URL, request: TranscriptionRequest) async throws -> TranscriptionOutput {
        let kit = try readyKit()
        let options = decodeOptions(request)
        do {
            let results = try await kit.transcribe(audioPath: fileURL.path, decodeOptions: options)
            return Self.output(from: results)
        } catch {
            throw TranscriptionEngineError.failed(error.localizedDescription)
        }
    }

    func transcribe(
        samples: [Float],
        sampleRate: Double,
        request: TranscriptionRequest
    ) async throws -> TranscriptionOutput {
        let kit = try readyKit()
        let options = decodeOptions(request)
        let pcm: [Float]
        if abs(sampleRate - 16_000) < 1 {
            pcm = samples
        } else {
            pcm = Self.resample(samples, from: sampleRate, to: 16_000)
        }
        do {
            let results = try await kit.transcribe(audioArray: pcm, decodeOptions: options)
            return Self.output(from: results)
        } catch {
            throw TranscriptionEngineError.failed(error.localizedDescription)
        }
    }

    private func readyKit() throws -> WhisperKit {
        guard let kit else { throw TranscriptionEngineError.unavailable }
        return kit
    }

    private func decodeOptions(_ request: TranscriptionRequest) -> DecodingOptions {
        let profile = request.profile
        let hinted = request.language.map { !$0.isEmpty } ?? false
        var clip: [Float] = []
        if let range = request.clipSeconds {
            clip = [Float(range.lowerBound), Float(range.upperBound)]
        }
        return DecodingOptions(
            verbose: false,
            task: .transcribe,
            language: hinted ? request.language : nil,
            temperature: 0,
            usePrefillPrompt: true,
            usePrefillCache: true,
            detectLanguage: !hinted,
            skipSpecialTokens: true,
            withoutTimestamps: true,
            clipTimestamps: clip,
            windowClipTime: profile.windowClipTime,
            suppressBlank: true,
            compressionRatioThreshold: profile.compressionRatioThreshold,
            logProbThreshold: profile.logProbThreshold,
            firstTokenLogProbThreshold: profile.firstTokenLogProbThreshold,
            noSpeechThreshold: profile.noSpeechThreshold,
            chunkingStrategy: request.clipSeconds == nil ? .vad : nil
        )
    }

    /// Where the model (and its tokenizer) live: Application Support, excluded from backup.
    ///
    /// Human: WhisperKit's default is `Documents/huggingface`, which iOS backs up to iCloud —
    /// several hundred megabytes that can always be downloaded again, and Apple's storage
    /// guidelines reject that. Earlier builds used the default, so an existing download is moved
    /// here instead of fetched again.
    nonisolated static func modelStorage() -> URL? {
        let files = FileManager.default
        guard let support = files.urls(for: .applicationSupportDirectory, in: .userDomainMask).first,
              let documents = files.urls(for: .documentDirectory, in: .userDomainMask).first
        else { return nil }
        return modelStorage(support: support, documents: documents)
    }

    /// `modelStorage()` with the directories injected, so tests never touch a real download.
    nonisolated static func modelStorage(support: URL, documents: URL) -> URL {
        let files = FileManager.default
        var base = support.appendingPathComponent("huggingface", isDirectory: true)
        let legacy = documents.appendingPathComponent("huggingface", isDirectory: true)
        if !files.fileExists(atPath: base.path), files.fileExists(atPath: legacy.path) {
            try? files.createDirectory(at: support, withIntermediateDirectories: true)
            try? files.moveItem(at: legacy, to: base)
        }
        try? files.createDirectory(at: base, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? base.setResourceValues(values)
        return base
    }

    nonisolated private static func makeConfig(modelFolder: String, downloadBase: URL?) -> WhisperKitConfig {
        let config = WhisperKitConfig(
            // The tokenizer is fetched here too (WhisperKit falls back to Documents otherwise).
            downloadBase: downloadBase,
            modelFolder: modelFolder,
            verbose: false,
            logLevel: .error,
            prewarm: true,
            load: true,
            download: false
        )
        var compute = ModelComputeOptions()
        #if !targetEnvironment(simulator)
        compute.melCompute = .cpuAndGPU
        compute.audioEncoderCompute = .cpuAndGPU
        compute.textDecoderCompute = .cpuAndGPU
        compute.prefillCompute = .cpuAndGPU
        #endif
        config.computeOptions = compute
        return config
    }

    nonisolated private static func output(from results: [TranscriptionResult]) -> TranscriptionOutput {
        let merged = TranscriptionUtilities.mergeTranscriptionResults(results).text
        let text = merged.trimmingCharacters(in: .whitespacesAndNewlines)
        let language = results.first.map { String($0.language.prefix(2)).lowercased() }
        let segments = results.flatMap(\.segments)
        let confidence: Double
        if segments.isEmpty {
            confidence = text.isEmpty ? 0 : 0.7
        } else {
            let mean = segments.map(\.avgLogprob).reduce(0, +) / Float(segments.count)
            confidence = Double(min(1, max(0, exp(mean))))
        }
        return TranscriptionOutput(text: text, language: language, confidence: confidence)
    }

    nonisolated private static func resample(_ samples: [Float], from: Double, to: Double) -> [Float] {
        if samples.isEmpty || from == to { return samples }
        let ratio = from / to
        let count = max(1, Int(Double(samples.count) / ratio))
        var out = [Float](repeating: 0, count: count)
        for i in 0..<count {
            let x = Double(i) * ratio
            let i0 = min(Int(x), samples.count - 1)
            let i1 = min(i0 + 1, samples.count - 1)
            let t = Float(x - Double(i0))
            out[i] = samples[i0] * (1 - t) + samples[i1] * t
        }
        return out
    }
}
