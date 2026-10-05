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

    /// A finished note must be walked to the end. Timestamps continue after an early stop,
    /// the last second is not clipped, and silence detection must not drop a quiet stretch.
    @Test
    func aVoiceNoteCoversTheWholeRecording() {
        let plan = WhisperDecodePlan.make(for: .voiceNote())
        #expect(plan.keepTimestamps)
        #expect(plan.tailClipSeconds == 0)
        #expect(!plan.useVoiceActivityChunking)

        let forced = WhisperDecodePlan.make(for: .voiceNote(language: "ja"))
        #expect(forced == plan)
    }

    /// Whisper stopped at 8s of a 30s window and the stock seeker jumped to the end.
    /// The note resumes at 8s, and words past that timestamp are not kept to be repeated.
    @Test
    func aVoiceNoteResumesAfterAnEarlyStop() {
        let time = 5_000
        let end = 50
        let eightSeconds = time + 400
        let tokens = [time, 11, 12, eightSeconds, 13, 14, end]
        let window = 30 * 16_000
        let resume = VoiceNoteSeek.resumeSample(
            tokens: tokens,
            timeTokenBegin: time,
            sampleRate: 16_000,
            windowStart: 0,
            segmentSamples: window,
            engineSeek: window
        )
        #expect(resume == 8 * 16_000)
        #expect(VoiceNoteSeek.tokensThroughLastTimestamp(tokens, timeTokenBegin: time, endToken: end) == [time, 11, 12, eightSeconds, end])
    }

    /// The model closed the window. Starting another pass would only re-read silence.
    @Test
    func aFinishedWindowIsNotDecodedAgain() {
        let time = 5_000
        let window = 30 * 16_000
        let atTheEnd = time + Int((29.9 / VoiceNoteSeek.secondsPerTimestamp).rounded())
        let resume = VoiceNoteSeek.resumeSample(
            tokens: [time, 11, atTheEnd, 50],
            timeTokenBegin: time,
            sampleRate: 16_000,
            windowStart: 0,
            segmentSamples: window,
            engineSeek: window
        )
        #expect(resume == nil)

        let alreadyContinued = VoiceNoteSeek.resumeSample(
            tokens: [time, 11, time + 400, 50],
            timeTokenBegin: time,
            sampleRate: 16_000,
            windowStart: 0,
            segmentSamples: window,
            engineSeek: 8 * 16_000
        )
        #expect(alreadyContinued == nil)
    }

    /// Words after a real pause are still decoded; a fade after the last word is not.
    @Test
    func aResumeNeedsVoiceLeftAfterTheTimestamp() {
        let time = 5_000
        let window = 30 * 16_000
        let tokens = [time, 11, time + 400, 50] // stops at 8 s
        func resume(voicedEnd: Int?) -> Int? {
            VoiceNoteSeek.resumeSample(
                tokens: tokens,
                timeTokenBegin: time,
                sampleRate: 16_000,
                windowStart: 0,
                segmentSamples: window,
                engineSeek: window,
                voicedEnd: voicedEnd
            )
        }
        #expect(resume(voicedEnd: 12 * 16_000) == 8 * 16_000, "more speech after a pause")
        #expect(resume(voicedEnd: 8 * 16_000 + 8_000) == nil, "only the fade of the last word is left")
        #expect(resume(voicedEnd: nil) == 8 * 16_000)
    }

    /// Speech, then room tone: the voice ends where the speech does, not where the recording stops.
    @Test
    func theVoiceEndsWhereTheSpeechDoes() throws {
        let rate = 16_000
        var samples: [Float] = []
        var noise: UInt32 = 1
        func roomTone() -> Float {
            noise = noise &* 1_664_525 &+ 1_013_904_223
            return (Float(noise >> 8) / Float(1 << 24) - 0.5) * 0.004
        }
        for _ in 0 ..< rate / 2 { samples.append(roomTone()) }
        for index in 0 ..< 2 * rate { samples.append(0.3 * sin(Float(index) * 0.12) + roomTone()) }
        for _ in 0 ..< 2 * rate { samples.append(roomTone()) }
        let end = try #require(VoiceActivity.voicedEnd(samples, sampleRate: rate))
        #expect(abs(end - rate * 5 / 2) <= rate / 50, "ends at 2.5 s, not 4.5 s")

        let trimmed = VoiceActivity.trimmed(samples, voicedEnd: end, sampleRate: rate)
        #expect(trimmed.count == end + Int(Double(rate) * VoiceActivity.hangoverSeconds))
        #expect(abs(trimmed.last ?? 1) < 0.001, "the kept room tone fades to silence")
        #expect(Array(trimmed.prefix(end)) == Array(samples.prefix(end)), "the speech is untouched")
    }

    @Test
    func aQuietNoteIsLeftAlone() {
        #expect(VoiceActivity.voicedEnd([Float](repeating: 0.0005, count: 32_000), sampleRate: 16_000) == nil)
        #expect(VoiceActivity.voicedEnd([], sampleRate: 16_000) == nil)
    }

    /// The made-up "Copyright WDR 2020" started at 9.46 s on a note whose voice ended at 9.48 s.
    @Test
    func aSegmentStartingAfterTheVoiceIsNotSpeech() {
        #expect(!VoiceActivity.isSpoken(segmentStart: 9.46, voicedEndSeconds: 9.48))
        #expect(VoiceActivity.isSpoken(segmentStart: 6.58, voicedEndSeconds: 9.48))
        #expect(VoiceActivity.isSpoken(segmentStart: 0, voicedEndSeconds: 1.2))
    }

    /// OpenAI's `non_speech_tokens`: single-token symbols and music notes, never words.
    @Test
    func nonSpeechTokensCoverBracketsAndMusicButNotWords() {
        let begin = 50_000
        let vocabulary: [String: [Int]] = [
            "[": [1], " [": [2], "(": [3], " (": [4], "♪": [5, 6], " ♪": [7, 8],
            " -": [9], " '": [10], "<<": [11, 12], " <<": [13], "Musik": [14], " Musik": [15],
        ]
        let ids = NonSpeechTokens.ids(specialTokenBegin: begin) { text in
            // Special tokens around the text, as swift-transformers adds them.
            [begin + 8] + (vocabulary[text] ?? [20_000, 20_001]) + [begin + 7]
        }
        #expect(Set(ids).isSuperset(of: [1, 2, 3, 4, 5, 7, 9, 10, 13]))
        #expect(!ids.contains(11), "a symbol split into two tokens is not suppressed")
        #expect(!ids.contains(14) && !ids.contains(15), "words stay")
        #expect(!ids.contains(where: { $0 >= begin }), "special tokens are never suppressed")
    }

    /// A quiet tail must not replace the language the opening of the note already settled.
    @Test
    func theOpeningLanguageTokenWins() {
        #expect(WhisperLanguageToken.code(from: "<|ja|>") == "ja")
        #expect(WhisperLanguageToken.code(from: "<|zh|>") == "zh")
        #expect(WhisperLanguageToken.code(from: "<|0.00|>") == nil)
        #expect(WhisperLanguageToken.code(from: "<|transcribe|>") == nil)
        #expect(WhisperLanguageToken.firstCode(in: ["<|startoftranscript|>", "<|ko|>", "<|en|>"]) == "ko")
    }

    /// The language asked for, then the opening of the note, then the engine's last window.
    @Test
    func theLanguageTheNoteOpenedWithIsTheOneReported() {
        #expect(WhisperReportedLanguage.choose(forced: "fr", openingToken: "en", reported: "de") == "fr")
        #expect(WhisperReportedLanguage.choose(forced: nil, openingToken: "de", reported: "en") == "de")
        #expect(WhisperReportedLanguage.choose(forced: nil, openingToken: nil, reported: "german") == "de")
        #expect(WhisperReportedLanguage.choose(forced: "en", openingToken: "ja", reported: "ja") == "en")
        #expect(WhisperReportedLanguage.code("german") == "de")
        #expect(WhisperReportedLanguage.code("<|ja|>") == nil)
    }

    /// A language probe is only the opening of the note, and a live-call chunk stays single-pass.
    @Test
    func aLanguageProbeAndALiveChunkDoNotWalkTheFile() {
        let probe = WhisperDecodePlan.make(for: .detectLanguage())
        #expect(!probe.keepTimestamps)
        #expect(!probe.useVoiceActivityChunking)

        let call = WhisperDecodePlan.make(for: .liveCall())
        #expect(!call.keepTimestamps)
        #expect(call.useVoiceActivityChunking)
        #expect(call.tailClipSeconds == TranscriptionProfile.liveCall.windowClipTime)
    }

    @Test
    func modelCatalogIsStableForSettings() {
        #expect(TranscriptionModelID.default == .small)
        #expect(TranscriptionModelID.small.whisperKitName == "small")
        #expect(Set(TranscriptionModelID.allCases.map(\.rawValue)) == ["base", "small", "medium"])
    }

    /// Whisper confuses close languages on short notes; the person's own languages win unless
    /// another one is far likelier. Same vectors as the web and Android tests.
    @Test
    func detectionPrefersThePersonsLanguagesUnlessAnotherIsFarLikelier() {
        let germanHeardAsDutch = ["nl": 0.45, "de": 0.35, "en": 0.1, "af": 0.1]
        #expect(SpokenLanguagePick.pick(probabilities: germanHeardAsDutch, candidates: ["de", "en"]) == "de")
        #expect(SpokenLanguagePick.pick(probabilities: germanHeardAsDutch, candidates: []) == "nl")
        let spanish = ["es": 0.92, "pt": 0.04, "de": 0.02, "en": 0.02]
        #expect(SpokenLanguagePick.pick(probabilities: spanish, candidates: ["de", "en"]) == "es")
        let justOver = ["fr": 0.5, "de": 0.5 / SpokenLanguagePick.outsideCandidateOdds - 0.001]
        #expect(SpokenLanguagePick.pick(probabilities: justOver, candidates: ["de"]) == "fr")
        #expect(SpokenLanguagePick.pick(probabilities: ["da": 0.5, "no": 0.4], candidates: ["nb", "en"]) == "no")
        #expect(SpokenLanguagePick.pick(probabilities: ["fr": 0.3, "de": 0.3], candidates: []) == "de")
        #expect(SpokenLanguagePick.pick(probabilities: [:], candidates: ["de"]) == nil)
    }

    /// A chat's history settles a note Whisper is unsure about, and never a clear one.
    /// Same vectors as the web and Android tests.
    @Test
    func chatHistorySettlesAnUnsureNoteButNotAClearOne() {
        let german = ["de": 40.0]
        let unsure = ["en": 0.5, "de": 0.35, "nl": 0.15]
        #expect(SpokenLanguagePick.pick(probabilities: unsure, candidates: ["en", "de"]) == "en")
        #expect(SpokenLanguagePick.pick(probabilities: unsure, candidates: ["en", "de"], history: german) == "de")
        let english = ["en": 0.9, "de": 0.08, "nl": 0.02]
        #expect(SpokenLanguagePick.pick(probabilities: english, candidates: ["en", "de"], history: german) == "en")
    }

    /// The regression: English heard clearly, but one misdetected note taught the chat Turkish.
    @Test
    func aWrongLanguageInTheHistoryCannotOverrideClearAudio() {
        let english = ["en": 0.93, "tr": 0.01, "de": 0.06]
        #expect(SpokenLanguagePick.pick(probabilities: english, candidates: ["en", "de"], history: ["tr": 10]) == "en")
    }

    @Test
    func aSingleNoteOfHistoryOnlyNudges() {
        let unsure = ["en": 0.5, "de": 0.3]
        #expect(SpokenLanguagePick.pick(probabilities: unsure, candidates: ["en", "de"], history: ["de": 0.5]) == "en")
        #expect(SpokenLanguagePick.pick(probabilities: unsure, candidates: ["en", "de"], history: ["de": 3]) == "de")
    }

    @Test
    func languageScoresBecomeProbabilities() {
        let probabilities = SpokenLanguageSampler.probabilities(["de": 2, "en": 1, "fr": -.infinity])
        #expect(probabilities.count == 2)
        #expect(abs((probabilities["de"] ?? 0) - 1 / (1 + exp(-1.0))) < 1e-9)
        #expect(SpokenLanguageSampler.probabilities(["de": -.infinity]).isEmpty)
    }

    @Test
    func detectionCandidatesAreTheDeviceLanguagesAndEnglish() {
        #expect(TranscriptionLanguage.detectionCandidates(["de"]) == ["de", "en"])
        #expect(TranscriptionLanguage.detectionCandidates(["en", "de"]) == ["en", "de"])
        #expect(TranscriptionLanguage.detectionCandidates([]).isEmpty)
        let request = TranscriptionRequest.voiceNote(candidateLanguages: ["de", "en"])
        #expect(request.candidateLanguages == ["de", "en"])
        #expect(TranscriptionRequest.liveCall().candidateLanguages.isEmpty)
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
