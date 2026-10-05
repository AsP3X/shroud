import AVFoundation
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
    /// Token ids Whisper may not sample: brackets, music notes and the like (`NonSpeechTokens`).
    private var nonSpeechTokens: [Int] = []
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
            nonSpeechTokens = loaded.tokenizer.map(NonSpeechTokens.ids(in:)) ?? []
            preparedModel = model
        } catch {
            throw TranscriptionEngineError.modelUnavailable
        }
    }

    func transcribe(fileURL: URL, request: TranscriptionRequest) async throws -> TranscriptionOutput {
        let pcm: [Float]
        do {
            pcm = try AudioProcessor.loadAudioAsFloatArray(fromPath: fileURL.path)
        } catch {
            throw TranscriptionEngineError.failed(error.localizedDescription)
        }
        return try await transcribe(pcm: pcm, request: request)
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
            guard let resampled = Self.resample(samples, from: sampleRate, to: 16_000) else {
                throw TranscriptionEngineError.failed("Couldn't convert the audio for transcription.")
            }
            pcm = resampled
        }
        return try await transcribe(pcm: pcm, request: request)
    }

    /// 16 kHz mono `pcm`. A whole voice note ends where the voice does (`VoiceActivity`): the quiet
    /// tail after the last word is cut, and the note resumes after a pause only while voice is left.
    /// Fed silence, Whisper writes "[MUSIK]", "Thank you." or a subtitle credit.
    private func transcribe(pcm: [Float], request: TranscriptionRequest) async throws -> TranscriptionOutput {
        var audio = pcm
        var voicedEnd: Int?
        if WhisperDecodePlan.make(for: request).keepTimestamps,
           let end = VoiceActivity.voicedEnd(pcm, sampleRate: Self.sampleRate)
        {
            voicedEnd = end
            audio = VoiceActivity.trimmed(pcm, voicedEnd: end, sampleRate: Self.sampleRate)
        }
        let (request, heard) = await applyingDetection(request) {
            await self.detectSpokenLanguage(samples: audio, request: request)
        }
        let (kit, options) = try prepared(for: request)
        wholeNoteSeeker.voicedEnd = voicedEnd
        defer { wholeNoteSeeker.voicedEnd = nil }
        do {
            let results = try await kit.transcribe(audioArray: audio, decodeOptions: options)
            var output = Self.output(
                from: results,
                tokenizer: kit.tokenizer,
                forced: request.language,
                voicedEndSeconds: voicedEnd.map { Double($0) / Double(Self.sampleRate) }
            )
            output.languageProbability = heard
            return output
        } catch {
            throw TranscriptionEngineError.failed(error.localizedDescription)
        }
    }

    /// Whisper's decoder is prefilled with English before its own detector runs, and each
    /// later window detects again. A fresh check on the opening of the note avoids both, and
    /// lets the device's languages and the chat's history weigh in (`SpokenLanguagePick`).
    /// Returns the request with the detected language and what the audio alone gave it.
    private func applyingDetection(
        _ request: TranscriptionRequest,
        detect: () async -> SpokenLanguage?
    ) async -> (TranscriptionRequest, Double?) {
        if WhisperReportedLanguage.code(request.language) != nil { return (request, nil) }
        guard let detected = await detect(), let code = WhisperReportedLanguage.code(detected.code)
        else { return (request, nil) }
        var copy = request
        copy.language = code
        return (copy, detected.probability)
    }

    private func detectSpokenLanguage(samples: [Float], request: TranscriptionRequest) async -> SpokenLanguage? {
        guard let kit else { return nil }
        return try? await kit.spokenLanguage(
            samples: samples,
            candidates: request.candidateLanguages,
            history: request.languageHistory
        )
    }

    /// Whisper's input rate.
    nonisolated private static let sampleRate = 16_000

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
            // WhisperKit leaves these unsuppressed, unlike OpenAI's decoder: a quiet tail then
            // comes out as "[MUSIK]" or "[BLANK_AUDIO]".
            supressTokens: nonSpeechTokens,
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
        forced: String?,
        voicedEndSeconds: Double? = nil
    ) -> TranscriptionOutput {
        if let voicedEndSeconds {
            // A segment that starts once the voice has ended describes the silence after it.
            for result in results {
                let kept = result.segments.filter {
                    VoiceActivity.isSpoken(segmentStart: Double($0.start), voicedEndSeconds: voicedEndSeconds)
                }
                guard kept.count < result.segments.count else { continue }
                result.segments = kept
                result.text = kept.map(\.text).joined()
            }
        }
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

    /// Band-limited conversion through `AVAudioConverter`, the same path WhisperKit reads files
    /// with. Interpolating samples directly would fold everything above 8 kHz into speech.
    nonisolated private static func resample(_ samples: [Float], from: Double, to: Double) -> [Float]? {
        if samples.isEmpty || from == to { return samples }
        guard let format = AVAudioFormat(standardFormatWithSampleRate: from, channels: 1),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let channel = buffer.floatChannelData?[0]
        else { return nil }
        samples.withUnsafeBufferPointer { channel.update(from: $0.baseAddress!, count: samples.count) }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        guard let converted = AudioProcessor.resampleAudio(fromBuffer: buffer, toSampleRate: to, channelCount: 1)
        else { return nil }
        return AudioProcessor.convertBufferToArray(buffer: converted)
    }
}

/// Tokens Whisper writes for sounds instead of speech — "[MUSIK]", "(Applaus)", "♪" — suppressed the
/// way OpenAI's decoder does by default (`non_speech_tokens` in `whisper/tokenizer.py`). WhisperKit
/// 0.18 leaves this as a to-do, so a quiet tail after the last word comes out as a bracketed tag.
nonisolated enum NonSpeechTokens {
    static let symbols: [String] = "\"#()*+/:;<=>@[\\]^_`{|}~「」『』".map(String.init)
        + "<< >> <<< >>> -- --- -( -[ (' (\" (( )) ((( ))) [[ ]] {{ }} ♪♪ ♪♪♪".split(separator: " ").map(String.init)
    static let miscellaneous: Set<String> = ["♩", "♪", "♫", "♬", "♭", "♮", "♯"]

    static func ids(in tokenizer: WhisperTokenizer) -> [Int] {
        ids(specialTokenBegin: tokenizer.specialTokens.specialTokenBegin, encode: tokenizer.encode(text:))
    }

    /// A symbol counts when it is a single token on its own or after a space; a music symbol always
    /// counts by its first token. A hyphen or apostrophe after a space is suppressed too, so neither
    /// can start a word. `encode`'s special tokens (start of transcript, end of text) are ignored.
    static func ids(specialTokenBegin: Int, encode: (String) -> [Int]) -> [Int] {
        func text(_ string: String) -> [Int] { encode(string).filter { $0 < specialTokenBegin } }
        var result = Set<Int>()
        for start in [" -", " '"] {
            if let first = text(start).first { result.insert(first) }
        }
        for symbol in symbols + miscellaneous.sorted() {
            for tokens in [text(symbol), text(" " + symbol)] {
                guard let first = tokens.first else { continue }
                if tokens.count == 1 || miscellaneous.contains(symbol) { result.insert(first) }
            }
        }
        return result.sorted()
    }
}

/// The language picked for a note, and the probability the audio alone gave it.
nonisolated struct SpokenLanguage: Sendable, Equatable {
    var code: String
    var probability: Double
}

extension WhisperKit {
    /// `detectLangauge(audioArray:)` with `SpokenLanguageSampler` in place of the greedy sampler,
    /// which reports only the winning token. Nil for an English-only model.
    nonisolated func spokenLanguage(
        samples: [Float],
        candidates: [String],
        history: [String: Double]
    ) async throws -> SpokenLanguage? {
        guard textDecoder.isModelMultilingual, let tokenizer, !samples.isEmpty else { return nil }
        guard let window = audioProcessor.padOrTrim(
            fromArray: samples,
            startAt: 0,
            toLength: featureExtractor.windowSamples ?? Constants.defaultWindowSamples
        ),
            let mel = try await featureExtractor.logMelSpectrogram(fromAudio: window),
            let encoded = try await audioEncoder.encodeFeatures(mel)
        else { return nil }
        let inputs = try textDecoder.prepareDecoderInputs(
            withPrompt: [tokenizer.specialTokens.startOfTranscriptToken]
        )
        let sampler = SpokenLanguageSampler(
            languages: Dictionary(uniqueKeysWithValues: tokenizer.allLanguageTokens.compactMap { token in
                tokenizer.convertIdToToken(token)
                    .flatMap(WhisperLanguageToken.code(from:))
                    .map { (token, $0) }
            }),
            candidates: candidates,
            history: history
        )
        let result = try await textDecoder.detectLanguage(
            from: encoded,
            using: inputs,
            sampler: sampler,
            options: DecodingOptions(),
            temperature: 0
        )
        // Without a sampled language token WhisperKit reports English; that is not a detection.
        // The sampler stores the log of the audio-only probability under the picked language.
        guard let logProbability = result.languageProbs[result.language] else { return nil }
        return SpokenLanguage(code: result.language, probability: Double(exp(logProbability)))
    }
}

/// Samples the language token after Whisper's single detection step. The logits arrive with
/// every non-language token masked; this turns the language scores into probabilities and lets
/// `SpokenLanguagePick` choose. The log probability it records is the audio's own, before weighing.
nonisolated struct SpokenLanguageSampler: TokenSampling {
    /// Token id → two-letter code.
    let languages: [Int: String]
    let candidates: [String]
    let history: [String: Double]

    func update(tokens: [Int], logits: MLMultiArray, logProbs: [Float]) -> SamplingResult {
        let probabilities = Self.probabilities(scores(in: logits))
        guard let code = SpokenLanguagePick.pick(
            probabilities: probabilities,
            candidates: candidates,
            history: history
        ),
              let token = languages.first(where: { $0.value == code })?.key,
              let probability = probabilities[code]
        else { return SamplingResult(tokens: tokens, logProbs: logProbs, completed: true) }
        return SamplingResult(
            tokens: tokens + [token],
            logProbs: logProbs + [Float(log(probability))],
            completed: true
        )
    }

    func finalize(tokens: [Int], logProbs: [Float]) -> SamplingResult {
        SamplingResult(tokens: tokens, logProbs: logProbs, completed: true)
    }

    /// Each language's logit, keyed by its code.
    private func scores(in logits: MLMultiArray) -> [String: Double] {
        var scores: [String: Double] = [:]
        for (token, code) in languages where token < logits.count {
            scores[code] = logits[token].doubleValue
        }
        return scores
    }

    /// Softmax over the finite scores.
    static func probabilities(_ scores: [String: Double]) -> [String: Double] {
        let finite = scores.filter { $0.value.isFinite }
        guard let top = finite.values.max() else { return [:] }
        let weights = finite.mapValues { exp($0 - top) }
        let total = weights.values.reduce(0, +)
        return weights.mapValues { $0 / total }
    }
}

/// Stock WhisperKit jumps to the end of the window when a decode stops early.
/// A voice note resumes at the last timestamp instead, so the words after a pause
/// (or after the token budget) are still transcribed.
nonisolated final class WholeVoiceNoteSeeker: SegmentSeeking {
    private let inner = SegmentSeeker()
    /// Sample where the note's voice ends (`VoiceActivity`); no resume past it. Set per note by the engine.
    nonisolated(unsafe) var voicedEnd: Int?

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
            voicedEnd: voicedEnd,
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
