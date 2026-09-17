import AVFoundation
import CoreMedia
import Foundation
import NaturalLanguage
import Observation
import Speech

/// Live install/transcribe status for the voice-bubble progress UI.
///
/// Human: Model assets are hundreds of megabytes. A spinner that says "Transcribing…" while the
/// phone is actually downloading looks like a hang. This is the single source of that progress
/// so the bubble that kicked off the job can show a determinate bar.
@MainActor
@Observable
final class TranscriptionModelInstall {
    static let shared = TranscriptionModelInstall()

    enum Phase: Equatable {
        case idle
        case downloading
        case transcribing
    }

    private(set) var phase: Phase = .idle
    /// 0…1 while `phase == .downloading`. Ignored when `isDeterminate` is false.
    private(set) var fractionCompleted: Double = 0
    private(set) var isDeterminate = false
    private(set) var languageName: String?
    private(set) var messageID: UUID?
    private var sessionCount = 0

    var isBusy: Bool { phase != .idle }

    func isActive(for id: UUID) -> Bool {
        isBusy && messageID == id
    }

    func begin(messageID: UUID?) {
        sessionCount += 1
        if let messageID { self.messageID = messageID }
        if phase == .idle {
            phase = .transcribing
            fractionCompleted = 0
            isDeterminate = false
            languageName = nil
        }
    }

    func downloading(languageName: String, fraction: Double, determinate: Bool) {
        phase = .downloading
        self.languageName = languageName
        isDeterminate = determinate
        fractionCompleted = min(1, max(0, fraction))
    }

    func transcribing() {
        phase = .transcribing
        fractionCompleted = 1
    }

    func finish() {
        sessionCount = max(0, sessionCount - 1)
        guard sessionCount == 0 else { return }
        phase = .idle
        fractionCompleted = 0
        isDeterminate = false
        languageName = nil
        messageID = nil
    }
}

/// On-device speech-to-text for voice messages (Tier 1 — audio never leaves the device).
///
/// Two `SpeechAnalyzer` modules, best first:
///
/// 1. **`SpeechTranscriber`** (iOS 26, A14+/16-core Neural Engine). Long-form model used by
///    Notes and Voice Memos. Unavailable on Simulator (no ANE) and on A13 devices.
/// 2. **`DictationTranscriber`**. Same on-device models as `SFSpeechRecognizer` with
///    `requiresOnDeviceRecognition`, but Apple's documented fallback when `SpeechTranscriber`
///    cannot run. Does **not** require Keyboard Dictation to be enabled in Settings.
///
/// `SFSpeechRecognizer` is not used. `SFSpeechURLRecognitionRequest` on AAC/M4A is what produced
/// `kAFAssistantErrorDomain` 1101 / `SFSpeechRecognitionTask speechRecordingDidFail` here.
///
/// Human: Two things decide whether the output is usable — the audio must be in the format the
/// model expects (see `feed`), and it must be transcribed in the language actually being spoken
/// (see `resolveLocale`).
/// Agent: READS a local audio file; WRITES only the UserDefaults key owned by
/// `TranscriptionLanguage`. The only network traffic is Apple's model asset download, which
/// carries no user audio. Never add a server-side path (`security-crypto.mdc`).
enum VoiceTranscriber {
    enum TranscribeError: Error, LocalizedError {
        case permissionDenied
        case unavailable
        case modelUnavailable
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .permissionDenied: "Speech recognition permission is required."
            case .unavailable:
                #if targetEnvironment(simulator)
                "On-device transcription isn't available in the Simulator. Run Shroud on an iPhone."
                #else
                "On-device transcription is not available on this device."
                #endif
            case .modelUnavailable:
                "Couldn't download the transcription model. Check your connection and try again."
            case let .failed(msg): msg
            }
        }
    }

    /// One candidate transcription, used both as the final result and when comparing languages.
    private struct Attempt {
        let locale: Locale
        let text: String
        let confidence: Double
        let score: Double

        var languageCode: String {
            locale.language.languageCode?.identifier ?? locale.identifier
        }
    }

    /// Seconds of audio used to decide which language is being spoken.
    private static let detectionWindow: Double = 20
    /// Upper bound on how many languages we are willing to probe.
    private static let maxDetectionCandidates = 4

    private enum Engine {
        case longForm
        case dictation
    }

    // MARK: - Entry points

    /// Transcribes a local audio file on-device.
    ///
    /// - Parameter contextualStrings: Names or terms to bias recognition toward (contact
    ///   usernames, for example). Materially improves proper nouns.
    /// - Parameter conversationID: Peer this recording belongs to. Scopes the learned language
    ///   history, which is what makes short messages resolve correctly.
    static func transcribe(
        fileURL: URL,
        contextualStrings: [String] = [],
        conversationID: UUID? = nil,
        tracking: UUID? = nil
    ) async throws -> String {
        // `SFSpeechRecognizer.requestAuthorization` must run on the main thread. Callers are
        // SwiftUI (main actor); do this before hopping off for converter/analyzer work.
        let status = await requestAuthorization()
        guard status == .authorized else { throw TranscribeError.permissionDenied }

        #if targetEnvironment(simulator)
        // No Neural Engine, and DictationTranscriber assets do not install here.
        // Pretending to download produced "Couldn't download the transcription model".
        if !SpeechTranscriber.isAvailable {
            throw TranscribeError.unavailable
        }
        #endif

        await TranscriptionModelInstall.shared.begin(messageID: tracking)
        defer {
            Task { @MainActor in TranscriptionModelInstall.shared.finish() }
        }

        // AssetInventory / model download must not run inside `Task.detached` — those APIs
        // fail closed off the main thread and we were mapping that to "Couldn't download".
        let candidates = await candidateLocales()
        guard !candidates.isEmpty else { throw TranscribeError.unavailable }
        let prepared = try await prepareEngine(for: candidates[0])
        await TranscriptionModelInstall.shared.transcribing()

        return try await Task.detached(priority: .userInitiated) {
            try await Self.transcribeOffMain(
                fileURL: fileURL,
                contextualStrings: contextualStrings,
                conversationID: conversationID,
                engine: prepared.engine,
                fallbackLocale: prepared.locale,
                candidates: candidates
            )
        }.value
    }

    private static func transcribeOffMain(
        fileURL: URL,
        contextualStrings: [String],
        conversationID: UUID?,
        engine: Engine,
        fallbackLocale: Locale,
        candidates: [Locale]
    ) async throws -> String {
        let duration = audioDuration(of: fileURL)

        let locale: Locale
        if candidates.count > 1, await localeIsInstalled(fallbackLocale, engine: engine) {
            locale = (try? await resolveLocale(
                from: candidates,
                fileURL: fileURL,
                contextualStrings: contextualStrings,
                conversationID: conversationID,
                audioSeconds: duration
            )) ?? fallbackLocale
        } else {
            locale = fallbackLocale
        }

        let attempt = try await analyze(
            fileURL: fileURL,
            locale: locale,
            engine: engine,
            contextualStrings: contextualStrings,
            limitSeconds: nil,
            conversationID: conversationID,
            audioSeconds: duration,
            candidates: candidates
        )
        let text = VoiceTranscript.cleaned(attempt.text)
        let weight = VoiceTranscript.learningWeight(
            audioSeconds: duration,
            score: attempt.score
        )
        if !text.isEmpty, weight > 0 {
            TranscriptionLanguageMemory.record(
                languageCode: attempt.languageCode,
                peerID: conversationID,
                weight: weight
            )
        }
        return text
    }

    /// Duration in seconds, or 0 when the file cannot be read.
    private static func audioDuration(of url: URL) -> Double {
        guard let file = try? AVAudioFile(forReading: url), file.processingFormat.sampleRate > 0
        else { return 0 }
        return Double(file.length) / file.processingFormat.sampleRate
    }

    /// Convenience: write audio bytes to a temp file, transcribe, delete.
    static func transcribe(
        audioData: Data,
        fileExtension: String = "m4a",
        contextualStrings: [String] = [],
        conversationID: UUID? = nil,
        tracking: UUID? = nil
    ) async throws -> String {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-tx-\(UUID().uuidString).\(fileExtension)")
        try audioData.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        return try await transcribe(
            fileURL: url,
            contextualStrings: contextualStrings,
            conversationID: conversationID,
            tracking: tracking
        )
    }

    /// Downloads and reserves the model for `locale` if it isn't installed yet.
    /// Safe to call repeatedly; a no-op once installed.
    @discardableResult
    static func prepareModel(locale: Locale? = nil) async -> Bool {
        let target = locale ?? TranscriptionLanguage.override ?? .current
        let alreadyBusy = await MainActor.run { TranscriptionModelInstall.shared.isBusy }
        if !alreadyBusy {
            await TranscriptionModelInstall.shared.begin(messageID: nil)
        }
        defer {
            if !alreadyBusy {
                Task { @MainActor in TranscriptionModelInstall.shared.finish() }
            }
        }
        do {
            _ = try await prepareEngine(for: target)
            return true
        } catch {
            return false
        }
    }

    /// Locales the active engine can serve on this device, for a settings picker.
    static func availableLocales() async -> [Locale] {
        guard let engine = await activeEngine() else { return [] }
        let locales: [Locale]
        switch engine {
        case .longForm:
            locales = await SpeechTranscriber.supportedLocales
        case .dictation:
            locales = await DictationTranscriber.supportedLocales
        }
        return locales.sorted {
            TranscriptionLanguage.displayName(for: $0) < TranscriptionLanguage.displayName(for: $1)
        }
    }

    /// Whether the long-form engine can serve `locale` at all.
    static func supportsLongForm(locale: Locale = .current) async -> Bool {
        await analyzerLocale(equivalentTo: locale) != nil
    }

    /// Whether the active engine's model for `locale` is already on disk (no download needed).
    static func modelIsInstalled(locale: Locale = .current) async -> Bool {
        #if targetEnvironment(simulator)
        if !SpeechTranscriber.isAvailable { return false }
        #endif
        guard let engine = await activeEngine() else { return false }
        switch engine {
        case .longForm:
            guard let supported = await analyzerLocale(equivalentTo: locale) else { return false }
            return await localeIsInstalled(supported, engine: .longForm)
        case .dictation:
            guard let supported = await dictationLocale(equivalentTo: locale) else { return false }
            return await localeIsInstalled(supported, engine: .dictation)
        }
    }

    private static func localeIsInstalled(_ locale: Locale, engine: Engine) async -> Bool {
        let module: any SpeechModule = switch engine {
        case .longForm: makeTranscriber(locale: locale)
        case .dictation: makeDictationTranscriber(locale: locale)
        }
        guard await AssetInventory.status(forModules: [module]) == .installed else {
            return false
        }
        return await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module]) != nil
    }

    // MARK: - Engine selection

    /// Long-form when the hardware can run it *and* it actually lists locales. `isAvailable`
    /// alone is not enough: on Simulator it is false (no ANE) and `supportedLocales` is empty,
    /// which previously dumped us into `SFSpeechRecognizer` and 1101.
    private static func activeEngine() async -> Engine? {
        if SpeechTranscriber.isAvailable {
            let locales = await SpeechTranscriber.supportedLocales
            if !locales.isEmpty { return .longForm }
        }
        let locales = await DictationTranscriber.supportedLocales
        return locales.isEmpty ? nil : .dictation
    }

    // MARK: - Language selection

    /// Languages worth trying, most likely first.
    ///
    /// Human: An explicit override short-circuits everything. Otherwise we combine the user's
    /// preferred languages with the models already installed on the device — the installed set
    /// is what makes detection useful, because someone who dictates in German has the German
    /// model even when their phone's UI language is English.
    static func candidateLocales() async -> [Locale] {
        guard let engine = await activeEngine() else { return [] }

        if let override = TranscriptionLanguage.override,
           let resolved = await locale(equivalentTo: override, engine: engine)
        {
            return [resolved]
        }

        var seen = Set<String>()
        var ordered: [Locale] = []

        func add(_ locale: Locale) {
            let key = locale.identifier(.bcp47)
            guard seen.insert(key).inserted else { return }
            ordered.append(locale)
        }

        for identifier in Locale.preferredLanguages {
            if let resolved = await locale(equivalentTo: Locale(identifier: identifier), engine: engine) {
                add(resolved)
            }
        }
        if let current = await locale(equivalentTo: .current, engine: engine) {
            add(current)
        }
        switch engine {
        case .longForm:
            for installed in await SpeechTranscriber.installedLocales {
                add(installed)
            }
        case .dictation:
            for installed in await DictationTranscriber.installedLocales {
                add(installed)
            }
        }

        return Array(ordered.prefix(maxDetectionCandidates))
    }

    /// Picks the language to transcribe in.
    ///
    /// Human: Order of preference is (1) whatever the user pinned in Settings, (2) the candidate
    /// that best explains this audio, (3) what this conversation has sounded like so far. Step 3
    /// is not a nicety — for a two-second message the acoustics genuinely cannot decide, and
    /// history is a far better guess than the device locale.
    private static func resolveLocale(
        from candidates: [Locale],
        fileURL: URL,
        contextualStrings: [String],
        conversationID: UUID?,
        audioSeconds: Double
    ) async throws -> Locale {
        guard let first = candidates.first else { throw TranscribeError.unavailable }
        guard candidates.count > 1 else { return first }

        var attempts: [Attempt] = []
        for candidate in candidates {
            // Only probe models already on disk — downloading several hundred megabytes per
            // candidate just to guess a language would be absurd.
            guard await modelIsInstalled(locale: candidate) else { continue }
            guard let attempt = try? await analyze(
                fileURL: fileURL,
                locale: candidate,
                engine: await activeEngine() ?? .dictation,
                contextualStrings: contextualStrings,
                limitSeconds: detectionWindow,
                conversationID: conversationID,
                audioSeconds: audioSeconds,
                candidates: candidates
            ) else { continue }
            attempts.append(attempt)
        }

        let expected = TranscriptionLanguageMemory.expectedLanguage(peerID: conversationID)

        if let best = attempts.max(by: { $0.score < $1.score }), best.score >= VoiceTranscript.minimumTrustedScore {
            return best.locale
        }

        // Nothing scored well enough to trust — silence, noise, or an utterance too short to
        // tell apart. Prefer what this conversation usually sounds like over the device locale.
        if let expected,
           let remembered = candidates.first(where: { $0.language.languageCode?.identifier == expected })
        {
            return remembered
        }
        return first
    }

    /// Probability that `text` is in `locale`'s language, constrained to the candidate set and
    /// biased by learned history.
    ///
    /// Human: Constraining the recogniser to the languages actually in play is what makes this
    /// usable on short text — unconstrained, a three-word German phrase is regularly classified
    /// as Dutch or Danish. 0.5 is returned when there is nothing to go on, which the scoring
    /// model reads as "no opinion".
    private static func languageProbability(
        text: String,
        locale: Locale,
        candidates: [Locale],
        conversationID: UUID?
    ) -> Double {
        guard VoiceTranscript.containsSpeech(text) else { return 0.5 }
        guard let code = locale.language.languageCode?.identifier else { return 0.5 }

        let recognizer = NLLanguageRecognizer()
        let constraints = candidates.compactMap { $0.language.languageCode?.identifier }
        if constraints.count > 1 {
            recognizer.languageConstraints = constraints.map { NLLanguage($0) }
            recognizer.languageHints = Dictionary(
                uniqueKeysWithValues: Set(constraints).map { candidate in
                    (
                        NLLanguage(candidate),
                        TranscriptionLanguageMemory.prior(for: candidate, peerID: conversationID)
                    )
                }
            )
        }
        recognizer.processString(text)

        let hypotheses = recognizer.languageHypotheses(withMaximum: max(constraints.count, 2))
        guard !hypotheses.isEmpty else { return 0.5 }
        return hypotheses[NLLanguage(code)] ?? 0
    }

    // MARK: - Locale resolution

    private static func locale(equivalentTo locale: Locale, engine: Engine) async -> Locale? {
        switch engine {
        case .longForm: await analyzerLocale(equivalentTo: locale)
        case .dictation: await dictationLocale(equivalentTo: locale)
        }
    }

    /// Resolves `locale` to one the long-form engine can actually serve, or nil.
    ///
    /// Human: Both checks are load-bearing. `supportedLocale(equivalentTo:)` happily answers
    /// with a locale even when the framework supports *nothing* — on Simulator it returns
    /// `en_US` while `isAvailable` is false and `supportedLocales` is empty.
    private static func analyzerLocale(equivalentTo locale: Locale) async -> Locale? {
        guard SpeechTranscriber.isAvailable else { return nil }
        guard let candidate = await SpeechTranscriber.supportedLocale(equivalentTo: locale) else {
            return nil
        }
        let supported = await SpeechTranscriber.supportedLocales
        // Use the array's own Locale object. A reconstructed equivalent can fail
        // AssetInventory reservation ("unallocated locales").
        return supported.first { $0.identifier(.bcp47) == candidate.identifier(.bcp47) }
    }

    private static func dictationLocale(equivalentTo locale: Locale) async -> Locale? {
        guard let candidate = await DictationTranscriber.supportedLocale(equivalentTo: locale) else {
            return nil
        }
        let supported = await DictationTranscriber.supportedLocales
        return supported.first { $0.identifier(.bcp47) == candidate.identifier(.bcp47) }
    }

    private static func makeTranscriber(locale: Locale) -> SpeechTranscriber {
        // Apple's `.transcription` preset is the configuration AssetInventory actually
        // downloads. Extra options (confidence, custom reporting) made status `.unsupported`
        // so `assetInstallationRequest` threw and the bubble showed "Couldn't download".
        SpeechTranscriber(locale: locale, preset: .transcription)
    }

    private static func makeDictationTranscriber(locale: Locale) -> DictationTranscriber {
        DictationTranscriber(locale: locale, preset: .longDictation)
    }

    // MARK: - Analysis

    private static func analyze(
        fileURL: URL,
        locale: Locale,
        engine: Engine,
        contextualStrings: [String],
        limitSeconds: Double?,
        conversationID: UUID?,
        audioSeconds: Double,
        candidates: [Locale]
    ) async throws -> Attempt {
        let context = AnalysisContext()
        if !contextualStrings.isEmpty {
            context.contextualStrings = [.general: contextualStrings]
        }

        let transcript: AttributedString
        switch engine {
        case .longForm:
            let transcriber = makeTranscriber(locale: locale)
            try await installModelIfNeeded(for: transcriber, locale: locale, engine: .longForm)
            let collector = Task {
                var combined = AttributedString()
                for try await result in transcriber.results {
                    combined.append(result.text)
                }
                return combined
            }
            do {
                try await feed(
                    fileURL: fileURL,
                    module: transcriber,
                    locale: locale,
                    engine: .longForm,
                    context: context,
                    limitSeconds: limitSeconds
                )
            } catch {
                collector.cancel()
                throw (error as? TranscribeError) ?? TranscribeError.failed(error.localizedDescription)
            }
            transcript = try await collector.value
        case .dictation:
            let transcriber = makeDictationTranscriber(locale: locale)
            try await installModelIfNeeded(for: transcriber, locale: locale, engine: .dictation)
            let collector = Task {
                var combined = AttributedString()
                for try await result in transcriber.results {
                    combined.append(result.text)
                }
                return combined
            }
            do {
                try await feed(
                    fileURL: fileURL,
                    module: transcriber,
                    locale: locale,
                    engine: .dictation,
                    context: context,
                    limitSeconds: limitSeconds
                )
            } catch {
                collector.cancel()
                throw (error as? TranscribeError) ?? TranscribeError.failed(error.localizedDescription)
            }
            transcript = try await collector.value
        }

        let text = String(transcript.characters)
        let confidence = meanConfidence(of: transcript)
        return Attempt(
            locale: locale,
            text: text,
            confidence: confidence,
            score: VoiceTranscript.score(
                text: text,
                modelConfidence: confidence,
                languageProbability: languageProbability(
                    text: text,
                    locale: locale,
                    candidates: candidates,
                    conversationID: conversationID
                ),
                prior: TranscriptionLanguageMemory.prior(
                    for: locale.language.languageCode?.identifier ?? locale.identifier,
                    peerID: conversationID
                ),
                audioSeconds: audioSeconds
            )
        )
    }

    /// Pushes `fileURL` through `module`. Uses Apple's file API when the file is already in the
    /// analyzer format; otherwise converts in memory and streams PCM. Never hands AAC/M4A to
    /// `SFSpeechURLRecognitionRequest` (that path is what FigExport -12785 / 1101 was).
    private static func feed(
        fileURL: URL,
        module: any SpeechModule,
        locale: Locale,
        engine: Engine,
        context: AnalysisContext,
        limitSeconds: Double?
    ) async throws {
        let file = try AVAudioFile(forReading: fileURL)
        // Do **not** pass `considering: file.processingFormat`. Voice notes are 44.1 kHz AAC;
        // the model assets are typically 16 kHz. `considering` the file made this return nil
        // even with the model installed, which we then mapped to "Couldn't download".
        var analyzerFormat = await SpeechAnalyzer.bestAvailableAudioFormat(
            compatibleWith: [module]
        )
        if analyzerFormat == nil {
            try await installModelIfNeeded(for: module, locale: locale, engine: engine)
            analyzerFormat = await SpeechAnalyzer.bestAvailableAudioFormat(
                compatibleWith: [module]
            )
        }
        guard let analyzerFormat else {
            throw TranscribeError.unavailable
        }

        let analyzer = SpeechAnalyzer(modules: [module])
        try await analyzer.setContext(context)
        try await analyzer.prepareToAnalyze(in: analyzerFormat, withProgressReadyHandler: nil)

        let lastSample: CMTime?
        if limitSeconds == nil, formatsMatch(file.processingFormat, analyzerFormat) {
            lastSample = try await analyzer.analyzeSequence(from: file)
        } else {
            let buffers = try resampledBuffers(
                from: file,
                to: analyzerFormat,
                limitSeconds: limitSeconds
            )
            guard !buffers.isEmpty else {
                throw TranscribeError.failed("The recording could not be read.")
            }
            lastSample = try await analyzeConvertedBuffers(
                buffers,
                format: analyzerFormat,
                analyzer: analyzer
            )
        }

        if let lastSample {
            try await analyzer.finalizeAndFinish(through: lastSample)
        } else {
            try await analyzer.finalizeAndFinishThroughEndOfInput()
        }
    }

    /// Starts `analyzeSequence` *before* finishing the stream — Apple's documented order.
    /// Timestamps are monotonic so the analyzer does not see the input as discontiguous.
    private static func analyzeConvertedBuffers(
        _ buffers: [AVAudioPCMBuffer],
        format: AVAudioFormat,
        analyzer: SpeechAnalyzer
    ) async throws -> CMTime? {
        let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream()
        let analysis = Task {
            try await analyzer.analyzeSequence(stream)
        }

        var start = CMTime.zero
        let timescale = CMTimeScale(max(Int32(format.sampleRate.rounded()), 1))
        for buffer in buffers {
            continuation.yield(AnalyzerInput(buffer: buffer, bufferStartTime: start))
            let seconds = Double(buffer.frameLength) / format.sampleRate
            start = start + CMTime(seconds: seconds, preferredTimescale: timescale)
        }
        continuation.finish()

        return try await analysis.value
    }

    private static func formatsMatch(_ a: AVAudioFormat, _ b: AVAudioFormat) -> Bool {
        a.sampleRate == b.sampleRate
            && a.channelCount == b.channelCount
            && a.commonFormat == b.commonFormat
            && a.isInterleaved == b.isInterleaved
    }

    /// Converts `file` into the analyzer format. Always flushes the converter — skipping that
    /// drop the last AAC packet and the model sees truncated PCM (punctuation-only transcripts).
    private static func resampledBuffers(
        from file: AVAudioFile,
        to targetFormat: AVAudioFormat,
        limitSeconds: Double?
    ) throws -> [AVAudioPCMBuffer] {
        let sourceFormat = file.processingFormat
        guard let converter = AVAudioConverter(from: sourceFormat, to: targetFormat) else {
            throw TranscribeError.failed("Could not convert the recording for transcription.")
        }
        converter.sampleRateConverterQuality = AVAudioQuality.max.rawValue

        let frameLimit: AVAudioFramePosition = limitSeconds
            .map { AVAudioFramePosition($0 * sourceFormat.sampleRate) }
            .map { min($0, file.length) } ?? file.length

        let chunkFrames: AVAudioFrameCount = 16_384
        let ratio = targetFormat.sampleRate / sourceFormat.sampleRate
        var buffers: [AVAudioPCMBuffer] = []
        var framesRead: AVAudioFramePosition = 0

        while framesRead < frameLimit {
            let remaining = frameLimit - framesRead
            let toRead = AVAudioFrameCount(min(AVAudioFramePosition(chunkFrames), remaining))
            guard let input = AVAudioPCMBuffer(pcmFormat: sourceFormat, frameCapacity: toRead) else {
                break
            }
            try file.read(into: input, frameCount: toRead)
            guard input.frameLength > 0 else { break }
            framesRead += AVAudioFramePosition(input.frameLength)

            try convertChunk(input, with: converter, into: &buffers, ratio: ratio, targetFormat: targetFormat)
        }

        try flushConverter(converter, into: &buffers, targetFormat: targetFormat)
        return buffers
    }

    private static func convertChunk(
        _ input: AVAudioPCMBuffer,
        with converter: AVAudioConverter,
        into buffers: inout [AVAudioPCMBuffer],
        ratio: Double,
        targetFormat: AVAudioFormat
    ) throws {
        let capacity = AVAudioFrameCount(Double(input.frameLength) * ratio) + 4096
        guard let output = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else {
            return
        }
        var conversionError: NSError?
        var consumed = false
        let status = converter.convert(to: output, error: &conversionError) { _, inputStatus in
            if consumed {
                inputStatus.pointee = .noDataNow
                return nil
            }
            consumed = true
            inputStatus.pointee = .haveData
            return input
        }
        if let conversionError {
            throw TranscribeError.failed(conversionError.localizedDescription)
        }
        guard status != .error else {
            throw TranscribeError.failed("Audio conversion failed.")
        }
        if output.frameLength > 0 {
            buffers.append(output)
        }
    }

    private static func flushConverter(
        _ converter: AVAudioConverter,
        into buffers: inout [AVAudioPCMBuffer],
        targetFormat: AVAudioFormat
    ) throws {
        var more = true
        while more {
            guard let output = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: 8192) else {
                return
            }
            var conversionError: NSError?
            let status = converter.convert(to: output, error: &conversionError) { _, inputStatus in
                inputStatus.pointee = .endOfStream
                return nil
            }
            if let conversionError {
                throw TranscribeError.failed(conversionError.localizedDescription)
            }
            if output.frameLength > 0 {
                buffers.append(output)
            }
            more = status == .haveData
        }
    }

    /// Length-weighted mean of the model's per-run confidence.
    private static func meanConfidence(of transcript: AttributedString) -> Double {
        var weighted = 0.0
        var weight = 0.0
        for run in transcript.runs {
            guard let confidence = run.transcriptionConfidence else { continue }
            let length = Double(transcript[run.range].characters.count)
            weighted += confidence * length
            weight += length
        }
        guard weight > 0 else { return 0 }
        return weighted / weight
    }

    /// Picks an engine whose model is on disk, downloading if needed. Long-form first; if that
    /// download fails, DictationTranscriber (system dictation assets) is the documented fallback.
    private static func prepareEngine(for locale: Locale) async throws -> (engine: Engine, locale: Locale) {
        if let longLocale = await analyzerLocale(equivalentTo: locale) {
            let module = makeTranscriber(locale: longLocale)
            do {
                try await installModelIfNeeded(for: module, locale: longLocale, engine: .longForm)
                if await localeIsInstalled(longLocale, engine: .longForm) {
                    return (.longForm, longLocale)
                }
            } catch {
                // Fall through to dictation rather than failing the Transcribe tap.
            }
        }

        if let dictationLocale = await dictationLocale(equivalentTo: locale) {
            let module = makeDictationTranscriber(locale: dictationLocale)
            try await installModelIfNeeded(for: module, locale: dictationLocale, engine: .dictation)
            return (.dictation, dictationLocale)
        }

        throw TranscribeError.modelUnavailable
    }

    private static func installForLocale(_ locale: Locale) async throws {
        _ = try await prepareEngine(for: locale)
    }

    private static func installModelIfNeeded(
        for module: any SpeechModule,
        locale: Locale,
        engine: Engine
    ) async throws {
        if await localeIsInstalled(locale, engine: engine) {
            try await ensureReserved(locale)
            return
        }

        try await ensureReserved(locale)
        try await downloadAssets(for: module, locale: locale, engine: engine)

        if await localeIsInstalled(locale, engine: engine) {
            return
        }
        if engine == .dictation,
           await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module]) != nil
        {
            return
        }
        throw TranscribeError.modelUnavailable
    }

    private static func downloadAssets(
        for module: any SpeechModule,
        locale: Locale,
        engine: Engine
    ) async throws {
        var lastError: Error?
        for attempt in 0..<3 {
            do {
                try await requestAndDownload(module, locale: locale, engine: engine)
                if await localeIsInstalled(locale, engine: engine) { return }
                if engine == .dictation,
                   await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module]) != nil
                {
                    return
                }
            } catch {
                lastError = error
                try await ensureReserved(locale, forceSlot: true)
            }
            if attempt < 2 {
                try? await Task.sleep(for: .seconds(1))
            }
        }
        if engine == .dictation,
           await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module]) != nil
        {
            return
        }
        if let lastError {
            throw TranscribeError.failed(lastError.localizedDescription)
        }
        throw TranscribeError.modelUnavailable
    }

    private static func requestAndDownload(
        _ module: any SpeechModule,
        locale: Locale,
        engine: Engine
    ) async throws {
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
            try await performDownload(request, locale: locale)
            return
        }
        if await localeIsInstalled(locale, engine: engine) { return }
        await waitUntilInstalled(module, locale: locale, engine: engine)
    }

    private static func performDownload(
        _ request: AssetInstallationRequest,
        locale: Locale
    ) async throws {
        let progress = request.progress
        let languageName = TranscriptionLanguage.displayName(for: locale)
        await MainActor.run {
            TranscriptionModelInstall.shared.downloading(
                languageName: languageName,
                fraction: progress.fractionCompleted,
                determinate: progress.totalUnitCount > 0
            )
        }
        let observation = progress.observe(\.fractionCompleted, options: [.new]) { prog, _ in
            let fraction = prog.fractionCompleted
            let determinate = prog.totalUnitCount > 0
            Task { @MainActor in
                TranscriptionModelInstall.shared.downloading(
                    languageName: languageName,
                    fraction: fraction,
                    determinate: determinate
                )
            }
        }
        defer { observation.invalidate() }
        try await request.downloadAndInstall()
    }

    private static func waitUntilInstalled(
        _ module: any SpeechModule,
        locale: Locale,
        engine: Engine
    ) async {
        let languageName = TranscriptionLanguage.displayName(for: locale)
        await MainActor.run {
            TranscriptionModelInstall.shared.downloading(
                languageName: languageName,
                fraction: 0,
                determinate: false
            )
        }
        for _ in 0..<40 {
            if await localeIsInstalled(locale, engine: engine) { return }
            if await AssetInventory.status(forModules: [module]) == .installed { return }
            try? await Task.sleep(for: .milliseconds(500))
        }
    }

    /// The app is allowed a small number of locale reservations. `assetInstallationRequest`
    /// auto-reserves, but throws when the pool is full — which we used to map to "not installed".
    private static func ensureReserved(_ locale: Locale, forceSlot: Bool = false) async throws {
        let reserved = await AssetInventory.reservedLocales
        if reserved.contains(where: { $0.identifier(.bcp47) == locale.identifier(.bcp47) }) {
            return
        }

        let maxCount = await AssetInventory.maximumReservedLocales
        if reserved.count >= maxCount || forceSlot {
            if let victim = reserved.first(where: { $0.identifier(.bcp47) != locale.identifier(.bcp47) }) {
                _ = await AssetInventory.release(reservedLocale: victim)
            }
        }

        do {
            _ = try await AssetInventory.reserve(locale: locale)
        } catch {
            if !forceSlot {
                try await ensureReserved(locale, forceSlot: true)
            }
        }
    }

    // MARK: - Authorization

    /// Requests speech authorization if needed. Required for both `SpeechTranscriber` and
    /// `DictationTranscriber` — they share the Speech Recognition privacy permission.
    static func requestAuthorization() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status)
            }
        }
    }
}
