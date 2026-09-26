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
    /// Resumes a voice note after Whisper stops early. Probes and live chunks keep the stock seeker.
    private let wholeNoteSeeker = WholeVoiceNoteSeeker()
    private let windowSeeker = SegmentSeeker()

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
        let request = await applyingDetection(request) {
            await self.detectSpokenLanguage(fileURL: fileURL)
        }
        let (kit, options) = try prepared(for: request)
        do {
            let results = try await kit.transcribe(audioPath: fileURL.path, decodeOptions: options)
            return Self.output(from: results, tokenizer: kit.tokenizer, forced: request.language)
        } catch {
            throw TranscriptionEngineError.failed(error.localizedDescription)
        }
    }

    func transcribe(
        samples: [Float],
        sampleRate: Double,
        request: TranscriptionRequest
    ) async throws -> TranscriptionOutput {
        let pcm: [Float]
        if abs(sampleRate - 16_000) < 1 {
            pcm = samples
        } else {
            pcm = Self.resample(samples, from: sampleRate, to: 16_000)
        }
        let request = await applyingDetection(request) {
            await self.detectSpokenLanguage(samples: pcm)
        }
        let (kit, options) = try prepared(for: request)
        do {
            let results = try await kit.transcribe(audioArray: pcm, decodeOptions: options)
            return Self.output(from: results, tokenizer: kit.tokenizer, forced: request.language)
        } catch {
            throw TranscriptionEngineError.failed(error.localizedDescription)
        }
    }

    /// Whisper's decoder is prefilled with English before its own detector runs, and each
    /// later window detects again. A fresh check on the opening of the note avoids both.
    private func applyingDetection(
        _ request: TranscriptionRequest,
        detect: () async -> String?
    ) async -> TranscriptionRequest {
        if WhisperReportedLanguage.code(request.language) != nil { return request }
        guard let detected = await detect() else { return request }
        var copy = request
        copy.language = detected
        return copy
    }

    private func detectSpokenLanguage(fileURL: URL) async -> String? {
        guard let kit else { return nil }
        guard let found = try? await kit.detectLanguage(audioPath: fileURL.path) else { return nil }
        return WhisperReportedLanguage.code(found.language)
    }

    private func detectSpokenLanguage(samples: [Float]) async -> String? {
        guard let kit, !samples.isEmpty else { return nil }
        guard let found = try? await kit.detectLangauge(audioArray: samples) else { return nil }
        return WhisperReportedLanguage.code(found.language)
    }

    private func prepared(for request: TranscriptionRequest) throws -> (WhisperKit, DecodingOptions) {
        let kit = try readyKit()
        let plan = WhisperDecodePlan.make(for: request)
        kit.segmentSeeker = plan.keepTimestamps ? wholeNoteSeeker : windowSeeker
        return (kit, decodeOptions(request))
    }

    private func readyKit() throws -> WhisperKit {
        guard let kit else { throw TranscriptionEngineError.unavailable }
        return kit
    }

    private func decodeOptions(_ request: TranscriptionRequest) -> DecodingOptions {
        let profile = request.profile
        let plan = WhisperDecodePlan.make(for: request)
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
            // Timestamps are what let a note continue after Whisper stops at a pause.
            // Without them the rest of that window is skipped.
            withoutTimestamps: !plan.keepTimestamps,
            clipTimestamps: clip,
            windowClipTime: plan.tailClipSeconds,
            suppressBlank: true,
            compressionRatioThreshold: profile.compressionRatioThreshold,
            logProbThreshold: profile.logProbThreshold,
            firstTokenLogProbThreshold: profile.firstTokenLogProbThreshold,
            noSpeechThreshold: profile.noSpeechThreshold,
            chunkingStrategy: plan.useVoiceActivityChunking ? .vad : nil
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

    nonisolated private static func output(
        from results: [TranscriptionResult],
        tokenizer: WhisperTokenizer?,
        forced: String?
    ) -> TranscriptionOutput {
        let merged = TranscriptionUtilities.mergeTranscriptionResults(results).text
        let text = merged.trimmingCharacters(in: .whitespacesAndNewlines)
        let language = WhisperReportedLanguage.choose(
            forced: forced,
            openingToken: firstLanguage(in: results, tokenizer: tokenizer),
            reported: results.first?.language
        )
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

    /// Language is decided on the first window. A later window (the tail, after a pause)
    /// detects again, and that second guess must not replace the one the note opened with.
    nonisolated private static func firstLanguage(
        in results: [TranscriptionResult],
        tokenizer: WhisperTokenizer?
    ) -> String? {
        guard let tokenizer, let segment = results.first?.segments.first else { return nil }
        var texts: [String] = []
        for token in segment.tokens where tokenizer.allLanguageTokens.contains(token) {
            if let text = tokenizer.convertIdToToken(token) {
                texts.append(text)
            }
        }
        return WhisperLanguageToken.firstCode(in: texts)
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

/// Stock WhisperKit jumps to the end of the window when a decode stops early.
/// A voice note resumes at the last timestamp instead, so the words after a pause
/// (or after the token budget) are still transcribed.
final class WholeVoiceNoteSeeker: SegmentSeeking {
    private let inner = SegmentSeeker()

    func findSeekPointAndSegments(
        decodingResult: DecodingResult,
        options: DecodingOptions,
        allSegmentsCount: Int,
        currentSeek seek: Int,
        segmentSize: Int,
        sampleRate: Int,
        timeToken: Int,
        specialToken: Int,
        tokenizer: WhisperTokenizer
    ) -> (Int, [TranscriptionSegment]?) {
        let (engineSeek, found) = inner.findSeekPointAndSegments(
            decodingResult: decodingResult,
            options: options,
            allSegmentsCount: allSegmentsCount,
            currentSeek: seek,
            segmentSize: segmentSize,
            sampleRate: sampleRate,
            timeToken: timeToken,
            specialToken: specialToken,
            tokenizer: tokenizer
        )
        guard var segments = found else { return (engineSeek, nil) }
        guard let resume = VoiceNoteSeek.resumeSample(
            tokens: decodingResult.tokens,
            timeTokenBegin: timeToken,
            sampleRate: sampleRate,
            windowStart: seek,
            segmentSamples: segmentSize,
            engineSeek: engineSeek,
            secondsPerTimestamp: Double(WhisperKit.secondsPerTimeToken)
        ) else {
            return (engineSeek, segments)
        }
        for index in segments.indices {
            segments[index].tokens = VoiceNoteSeek.tokensThroughLastTimestamp(
                segments[index].tokens,
                timeTokenBegin: timeToken,
                endToken: specialToken
            )
        }
        return (resume, segments)
    }

    func addWordTimestamps(
        segments: [TranscriptionSegment],
        alignmentWeights: MLMultiArray,
        tokenizer: WhisperTokenizer,
        seek: Int,
        segmentSize: Int,
        prependPunctuations: String,
        appendPunctuations: String,
        lastSpeechTimestamp: Float,
        options: DecodingOptions,
        timings: TranscriptionTimings
    ) throws -> [TranscriptionSegment]? {
        try inner.addWordTimestamps(
            segments: segments,
            alignmentWeights: alignmentWeights,
            tokenizer: tokenizer,
            seek: seek,
            segmentSize: segmentSize,
            prependPunctuations: prependPunctuations,
            appendPunctuations: appendPunctuations,
            lastSpeechTimestamp: lastSpeechTimestamp,
            options: options,
            timings: timings
        )
    }
}
