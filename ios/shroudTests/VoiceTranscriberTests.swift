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

    @Test
    func transcribingDataCleansUpItsTempFile() async throws {
        guard await VoiceTranscriber.modelIsInstalled(locale: englishUS) else { return }

        let url = try makeSilentFile(seconds: 1)
        defer { try? FileManager.default.removeItem(at: url) }
        let data = try Data(contentsOf: url)

        let before = temporaryTranscriptionFileCount()
        _ = try await VoiceTranscriber.transcribe(audioData: data, fileExtension: "wav")
        #expect(temporaryTranscriptionFileCount() == before, "temp audio must not be left behind")
    }

    private func temporaryTranscriptionFileCount() -> Int {
        let dir = FileManager.default.temporaryDirectory
        let contents = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return contents.filter { $0.hasPrefix("shroud-tx-") }.count
    }

    @Test
    func whisperAlwaysOffersLanguages() async {
        let locales = await VoiceTranscriber.availableLocales()
        #expect(!locales.isEmpty)
        #expect(await VoiceTranscriber.supportsLongForm())
    }
}
