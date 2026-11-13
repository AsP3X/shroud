import AVFoundation
import Foundation
import Testing
@testable import shroud

/// Covers the transcript quality gate and language preference, plus an end-to-end run of the
/// pipeline when a speech model happens to be installed.
///
/// The engine-dependent tests deliberately **skip** rather than fail when no model is present —
/// asserting otherwise would make CI depend on a multi-hundred-megabyte download. The pure
/// helpers below run everywhere and are where the real regressions are caught.
/// Serialized: several tests mutate the shared `TranscriptionLanguage.override` default, and
/// Swift Testing runs cases in parallel by default — without this they clobber each other.
@Suite(.serialized)
struct VoiceTranscriberTests {
    private let englishUS = Locale(identifier: "en-US")

    // MARK: - Transcript quality gate

    /// The regression this suite exists for: a model fed audio it cannot parse (wrong sample
    /// rate, wrong language, noise) emits punctuation and nothing else. Showing the user
    /// ", , , ," is worse than showing nothing.
    @Test
    func punctuationOnlyTranscriptsAreRejected() {
        #expect(VoiceTranscript.cleaned(", , , ,") == "")
        #expect(VoiceTranscript.cleaned(",") == "")
        #expect(VoiceTranscript.cleaned("... — !?") == "")
        #expect(VoiceTranscript.cleaned("   ") == "")
        #expect(VoiceTranscript.cleaned("") == "")
    }

    @Test
    func realSpeechSurvivesTheGate() {
        #expect(VoiceTranscript.cleaned("Hallo, wie geht es dir?") == "Hallo, wie geht es dir?")
        #expect(VoiceTranscript.containsSpeech("Ok"))
        // A single letter is noise, not a message.
        #expect(!VoiceTranscript.containsSpeech("a"))
    }

    @Test
    func cleanedCollapsesSegmentJoinWhitespace() {
        // Concatenated result segments can leave doubled spaces and stray newlines behind.
        #expect(VoiceTranscript.cleaned("Hello   there\n\nworld") == "Hello there world")
    }

    // MARK: - Language preference

    @Test
    func displayNameIsHumanReadable() {
        let name = TranscriptionLanguage.displayName(for: Locale(identifier: "de-DE"))
        #expect(!name.isEmpty)
        #expect(name != "de-DE")
    }

    // MARK: - Engine (skipped without an installed model)

    /// Writes `seconds` of silent 16 kHz mono PCM to a temp .wav and returns its URL.
    private func makeSilentFile(seconds: Double, sampleRate: Double = 16_000) throws -> URL {
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: 1,
            interleaved: false
        ) else {
            throw TestSetupError.formatUnavailable
        }

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-test-\(UUID().uuidString).wav")
        let file = try AVAudioFile(forWriting: url, settings: format.settings)

        let frames = AVAudioFrameCount(format.sampleRate * seconds)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else {
            throw TestSetupError.bufferUnavailable
        }
        buffer.frameLength = frames
        try file.write(from: buffer)
        return url
    }

    private enum TestSetupError: Error {
        case formatUnavailable
        case bufferUnavailable
    }

    @Test
    func engineCompletesOnAFileWithoutThrowing() async throws {
        guard await VoiceTranscriber.modelIsInstalled(locale: englishUS) else { return }
        let original = TranscriptionLanguage.override
        defer { TranscriptionLanguage.override = original }
        TranscriptionLanguage.override = englishUS

        let url = try makeSilentFile(seconds: 1.5)
        defer { try? FileManager.default.removeItem(at: url) }

        // Silence should finish, not hang. Whisper may emit a short hallucination; the
        // quality gate still returns a string (possibly empty).
        let text = try await VoiceTranscriber.transcribe(fileURL: url)
        #expect(VoiceTranscript.cleaned(text) == text)
    }

    /// The recording format (44.1 kHz) differs from the model's — resampling must happen, and
    /// feeding a mismatched rate is what produced punctuation-only transcripts.
    @Test
    func engineAcceptsAudioAtTheRecordersSampleRate() async throws {
        guard await VoiceTranscriber.modelIsInstalled(locale: englishUS) else { return }
        let original = TranscriptionLanguage.override
        defer { TranscriptionLanguage.override = original }
        TranscriptionLanguage.override = englishUS

        let url = try makeSilentFile(seconds: 2, sampleRate: 44_100)
        defer { try? FileManager.default.removeItem(at: url) }

        _ = try await VoiceTranscriber.transcribe(fileURL: url)
    }

    /// Guards the long-form path against the one-minute ceiling of the legacy dictation API.
    @Test
    func engineHandlesAudioLongerThanTheDictationLimit() async throws {
        guard await VoiceTranscriber.modelIsInstalled(locale: englishUS) else { return }
        let original = TranscriptionLanguage.override
        defer { TranscriptionLanguage.override = original }
        TranscriptionLanguage.override = englishUS

        let url = try makeSilentFile(seconds: 75)
        defer { try? FileManager.default.removeItem(at: url) }

        _ = try await VoiceTranscriber.transcribe(fileURL: url)
    }

    // MARK: - No plaintext audio on disk

    /// The regression this section exists for: `transcribe(audioData:)` wrote the decrypted note to
    /// `tmp/shroud-tx-<uuid>.m4a` and deleted it afterwards. The engine looks at `tmp` while it is
    /// decoding, which is when that file existed.
    @Test
    func transcribingANoteWritesNothingUnderTmp() async throws {
        let note = try makeVoiceNote(seconds: 1.5)
        let engine = TempDirectoryWatchingEngine(watching: note.data)
        let session = TranscriptionSession(engine: engine)
        let before = Self.transcriptionTempFiles()

        let text = try await VoiceTranscriber.transcribe(audioData: note.data, session: session)

        #expect(text == "See you at eight")
        #expect(engine.transcriptions == 1)
        #expect(engine.copiesOnDisk.isEmpty, "the note's plaintext was on disk: \(engine.copiesOnDisk)")
        #expect(engine.transcriptionTempFiles == before, "a transcription temp file appeared under tmp")
        // The engine got the whole note, at the recorder's rate, for its own resampler.
        #expect(engine.sampleRate == 44_100)
        #expect(engine.samples == note.samples)
    }

    /// Whisper must hear what `AVAudioFile` reads from the same bytes, or transcripts change.
    @Test
    func inMemoryDecodeMatchesAVAudioFile() throws {
        let note = try makeVoiceNote(seconds: 2)
        let pcm = try InMemoryAudioDecoder.decode(note.data)
        #expect(pcm.sampleRate == 44_100)
        #expect(pcm.samples == note.samples)
        #expect(abs(pcm.seconds - 2) < 0.001)

        let wavURL = try makeSilentFile(seconds: 1)
        defer { try? FileManager.default.removeItem(at: wavURL) }
        let wav = try InMemoryAudioDecoder.decode(Data(contentsOf: wavURL))
        #expect(wav.sampleRate == 16_000)
        #expect(wav.samples.count == 16_000)
    }

    /// `message.voiceData` can be a slice whose first index is not 0.
    @Test
    func inMemoryDecodeReadsADataSlice() throws {
        let note = try makeVoiceNote(seconds: 1)
        let slice = (Data([1, 2, 3]) + note.data).dropFirst(3)
        #expect(slice.startIndex == 3)
        #expect(try InMemoryAudioDecoder.decode(slice).samples == note.samples)
    }

    @Test
    func bytesThatAreNotAudioFailTheTranscription() async throws {
        #expect(throws: InMemoryAudioDecoder.DecodeError.self) {
            try InMemoryAudioDecoder.decode(Data("not audio".utf8))
        }
        #expect(throws: InMemoryAudioDecoder.DecodeError.self) {
            try InMemoryAudioDecoder.decode(Data())
        }
        let session = TranscriptionSession(engine: TempDirectoryWatchingEngine(watching: Data()))
        await #expect(throws: VoiceTranscriber.TranscribeError.self) {
            try await VoiceTranscriber.transcribe(audioData: Data("not audio".utf8), session: session)
        }
    }

    /// A 440 Hz tone in the recorder's format (AAC, 44.1 kHz, mono), and the samples `AVAudioFile`
    /// reads back from it. The file exists only while this runs, before any test looks at `tmp`.
    private func makeVoiceNote(seconds: Double) throws -> (data: Data, samples: [Float]) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-test-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: url) }
        try writeTone(to: url, seconds: seconds)

        let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        guard let buffer = AVAudioPCMBuffer(
            pcmFormat: file.processingFormat,
            frameCapacity: AVAudioFrameCount(file.length)
        ) else {
            throw TestSetupError.bufferUnavailable
        }
        try file.read(into: buffer)
        let samples = Array(UnsafeBufferPointer(start: buffer.floatChannelData?[0], count: Int(buffer.frameLength)))
        return (try Data(contentsOf: url), samples)
    }

    /// Separate so the writer is released, and the file finished, before it is read.
    private func writeTone(to url: URL, seconds: Double) throws {
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
        ]
        guard let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1) else {
            throw TestSetupError.formatUnavailable
        }
        let frames = AVAudioFrameCount(format.sampleRate * seconds)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames),
              let channel = buffer.floatChannelData?[0]
        else {
            throw TestSetupError.bufferUnavailable
        }
        buffer.frameLength = frames
        for frame in 0 ..< Int(frames) {
            channel[frame] = 0.3 * sin(Float(frame) * 2 * .pi * 440 / Float(format.sampleRate))
        }
        let file = try AVAudioFile(forWriting: url, settings: settings)
        try file.write(from: buffer)
    }

    /// Names under `tmp` that the old code used for its plaintext copy.
    fileprivate static func transcriptionTempFiles() -> Set<String> {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: FileManager.default.temporaryDirectory.path)) ?? []
        return Set(names.filter { $0.hasPrefix("shroud-tx-") })
    }

    @Test
    func whisperAlwaysOffersLanguages() async {
        let locales = await VoiceTranscriber.availableLocales()
        #expect(!locales.isEmpty)
        #expect(await VoiceTranscriber.supportsLongForm())
    }
}

/// Answers like a model, and while it "decodes" looks through `tmp` for a file holding the note.
///
/// Human: Other suites write their own files under `tmp` in parallel, so this looks for the note's
/// bytes (and the old `shroud-tx-` names) rather than for any new entry.
final class TempDirectoryWatchingEngine: TranscriptionEngine, @unchecked Sendable {
    let id = "tmp-watch"
    private let note: Data
    private(set) var transcriptions = 0
    private(set) var copiesOnDisk: [String] = []
    private(set) var transcriptionTempFiles: Set<String> = []
    private(set) var samples: [Float] = []
    private(set) var sampleRate: Double = 0

    init(watching note: Data) {
        self.note = note
    }

    func prepare(model: TranscriptionModelID, progress: (@Sendable (Double) -> Void)?) async throws {}

    func transcribe(fileURL: URL, request: TranscriptionRequest) async throws -> TranscriptionOutput {
        lookAtTmp()
        return Self.output
    }

    func transcribe(
        samples: [Float],
        sampleRate: Double,
        request: TranscriptionRequest
    ) async throws -> TranscriptionOutput {
        self.samples = samples
        self.sampleRate = sampleRate
        lookAtTmp()
        return Self.output
    }

    /// No language, so the test teaches the chat's language history nothing.
    private static let output = TranscriptionOutput(text: "See you at eight", language: nil, confidence: 0.9)

    private func lookAtTmp() {
        transcriptions += 1
        transcriptionTempFiles = VoiceTranscriberTests.transcriptionTempFiles()
        let tmp = FileManager.default.temporaryDirectory
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        guard let files = FileManager.default.enumerator(at: tmp, includingPropertiesForKeys: keys) else { return }
        for case let url as URL in files {
            guard let values = try? url.resourceValues(forKeys: Set(keys)),
                  values.isRegularFile == true, values.fileSize == note.count,
                  (try? Data(contentsOf: url)) == note
            else { continue }
            copiesOnDisk.append(url.lastPathComponent)
        }
    }
}
