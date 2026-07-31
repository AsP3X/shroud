import AVFoundation
import Foundation
import NaturalLanguage
import Speech

/// On-device speech-to-text for voice messages (Tier 1 — audio never leaves the device).
///
/// Two engines, best first:
///
/// 1. **`SpeechAnalyzer` + `SpeechTranscriber`** (iOS 26). Apple's long-form stack — the one
///    Notes and Voice Memos use. No practical duration limit, punctuation and capitalisation
///    from the model, markedly better accuracy than dictation. Needs a per-locale model asset.
/// 2. **`SFSpeechRecognizer`** (legacy), only for locales the new stack does not serve. Apple
///    documents a one-minute audio ceiling on it (`SFSpeechRecognizer.h`), so it is a fallback,
///    not a peer.
///
/// Human: Two things decide whether the output is usable, and both used to be wrong here —
/// the audio must be resampled into the format the model expects (see `analyze`), and it must
/// be transcribed in the language actually being spoken (see `resolveLocale`).
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
            case .unavailable: "On-device transcription is not available."
            case .modelUnavailable: "The transcription model for this language is not installed."
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
        conversationID: UUID? = nil
    ) async throws -> String {
        let candidates = await candidateLocales()

        guard !candidates.isEmpty else {
            // No long-form model available at all — try the legacy engine in the device locale.
            return VoiceTranscript.cleaned(
                try await recognizeWithDictation(
                    fileURL: fileURL,
                    locale: .current,
                    contextualStrings: contextualStrings
                )
            )
        }

        let duration = audioDuration(of: fileURL)
        let locale = try await resolveLocale(
            from: candidates,
            fileURL: fileURL,
            contextualStrings: contextualStrings,
            conversationID: conversationID,
            audioSeconds: duration
        )

        do {
            let attempt = try await analyze(
                fileURL: fileURL,
                locale: locale,
                contextualStrings: contextualStrings,
                limitSeconds: nil,
                conversationID: conversationID,
                audioSeconds: duration,
                candidates: candidates
            )
            let text = VoiceTranscript.cleaned(attempt.text)
            // Teach the memory only from results that were actually decisive.
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
        } catch TranscribeError.modelUnavailable {
            return VoiceTranscript.cleaned(
                try await recognizeWithDictation(
                    fileURL: fileURL,
                    locale: locale,
                    contextualStrings: contextualStrings
                )
            )
        }
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
        conversationID: UUID? = nil
    ) async throws -> String {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-tx-\(UUID().uuidString).\(fileExtension)")
        try audioData.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        return try await transcribe(
            fileURL: url,
            contextualStrings: contextualStrings,
            conversationID: conversationID
        )
    }

    /// Downloads and reserves the long-form model for `locale` if it isn't installed yet.
    /// Safe to call repeatedly; a no-op once installed.
    @discardableResult
    static func prepareModel(locale: Locale? = nil) async -> Bool {
        let target = locale ?? TranscriptionLanguage.override ?? .current
        guard let supported = await analyzerLocale(equivalentTo: target) else { return false }
        do {
            try await installModelIfNeeded(
                for: makeTranscriber(locale: supported),
                locale: supported
            )
            return true
        } catch {
            return false
        }
    }

    /// Locales the long-form engine can serve on this device, for a settings picker.
    static func availableLocales() async -> [Locale] {
        guard SpeechTranscriber.isAvailable else { return [] }
        return await SpeechTranscriber.supportedLocales
            .sorted { TranscriptionLanguage.displayName(for: $0) < TranscriptionLanguage.displayName(for: $1) }
    }

    /// Whether the long-form engine can serve `locale` at all.
    static func supportsLongForm(locale: Locale = .current) async -> Bool {
        await analyzerLocale(equivalentTo: locale) != nil
    }

    /// Whether the long-form model for `locale` is already on disk (no download needed).
    static func modelIsInstalled(locale: Locale = .current) async -> Bool {
        guard let supported = await analyzerLocale(equivalentTo: locale) else { return false }
        return await AssetInventory.status(
            forModules: [makeTranscriber(locale: supported)]
        ) == .installed
    }

    // MARK: - Language selection

    /// Languages worth trying, most likely first.
    ///
    /// Human: An explicit override short-circuits everything. Otherwise we combine the user's
    /// preferred languages with the models already installed on the device — the installed set
    /// is what makes detection useful, because someone who dictates in German has the German
    /// model even when their phone's UI language is English.
    static func candidateLocales() async -> [Locale] {
        if let override = TranscriptionLanguage.override,
           let resolved = await analyzerLocale(equivalentTo: override)
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
            if let resolved = await analyzerLocale(equivalentTo: Locale(identifier: identifier)) {
                add(resolved)
            }
        }
        if let current = await analyzerLocale(equivalentTo: .current) {
            add(current)
        }
        // Installed models cost nothing to probe and cover languages the user actually dictates in.
        if SpeechTranscriber.isAvailable {
            for installed in await SpeechTranscriber.installedLocales {
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

    // MARK: - Long-form engine

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
        let match = supported.contains { $0.identifier(.bcp47) == candidate.identifier(.bcp47) }
        return match ? candidate : nil
    }

    private static func makeTranscriber(locale: Locale) -> SpeechTranscriber {
        SpeechTranscriber(
            locale: locale,
            // `etiquetteReplacements` masks profanity — deliberately off, a messenger transcript
            // should say what was actually said.
            transcriptionOptions: [],
            // Final results only; we transcribe a finished file, so volatile reporting is churn.
            reportingOptions: [],
            // Confidence is what lets us compare candidate languages against each other.
            attributeOptions: [.transcriptionConfidence]
        )
    }

    private static func analyze(
        fileURL: URL,
        locale: Locale,
        contextualStrings: [String],
        limitSeconds: Double?,
        conversationID: UUID?,
        audioSeconds: Double,
        candidates: [Locale]
    ) async throws -> Attempt {
        let transcriber = makeTranscriber(locale: locale)
        try await installModelIfNeeded(for: transcriber, locale: locale)

        let file = try AVAudioFile(forReading: fileURL)

        // Human: This is the step whose absence produced transcripts of pure punctuation. Voice
        // messages are recorded at 44.1 kHz; the model wants its own (typically 16 kHz) format.
        // Handing it the file's buffers unconverted makes speech unintelligible to the model,
        // which then emits only pause punctuation.
        guard let analyzerFormat = await SpeechAnalyzer.bestAvailableAudioFormat(
            compatibleWith: [transcriber],
            considering: file.processingFormat
        ) else {
            throw TranscribeError.modelUnavailable
        }

        let analyzer = SpeechAnalyzer(modules: [transcriber])

        if !contextualStrings.isEmpty {
            let context = AnalysisContext()
            context.contextualStrings = [.general: contextualStrings]
            try? await analyzer.setContext(context)
        }

        // Results stream while the input is consumed, so collect concurrently.
        let collector = Task {
            var transcript = AttributedString()
            for try await result in transcriber.results {
                transcript.append(result.text)
            }
            return transcript
        }

        do {
            let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream()
            // A voice message is small enough to convert up front; the stream buffers it.
            for buffer in try resampledBuffers(
                from: file,
                to: analyzerFormat,
                limitSeconds: limitSeconds
            ) {
                continuation.yield(AnalyzerInput(buffer: buffer))
            }
            continuation.finish()

            let lastSample = try await analyzer.analyzeSequence(stream)
            if let lastSample {
                try await analyzer.finalizeAndFinish(through: lastSample)
            } else {
                await analyzer.cancelAndFinishNow()
            }
        } catch {
            collector.cancel()
            await analyzer.cancelAndFinishNow()
            throw TranscribeError.failed(error.localizedDescription)
        }

        let transcript = try await collector.value
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

    /// Reads `file` and converts it into `targetFormat`, optionally stopping after `limitSeconds`.
    private static func resampledBuffers(
        from file: AVAudioFile,
        to targetFormat: AVAudioFormat,
        limitSeconds: Double?
    ) throws -> [AVAudioPCMBuffer] {
        let sourceFormat = file.processingFormat
        guard let converter = AVAudioConverter(from: sourceFormat, to: targetFormat) else {
            throw TranscribeError.failed("Could not convert the recording for transcription.")
        }
        // Resampling quality directly affects recognition accuracy; this is not a hot path.
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

            let capacity = AVAudioFrameCount(Double(input.frameLength) * ratio) + 4096
            guard let output = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else {
                break
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

        return buffers
    }

    /// Ensures the locale's model is on disk, reserving it so the system doesn't evict it.
    private static func installModelIfNeeded(
        for transcriber: SpeechTranscriber,
        locale: Locale
    ) async throws {
        switch await AssetInventory.status(forModules: [transcriber]) {
        case .installed:
            return
        case .unsupported:
            throw TranscribeError.modelUnavailable
        case .supported, .downloading:
            do {
                if let request = try await AssetInventory.assetInstallationRequest(
                    supporting: [transcriber]
                ) {
                    try await request.downloadAndInstall()
                }
            } catch {
                throw TranscribeError.modelUnavailable
            }
        @unknown default:
            throw TranscribeError.modelUnavailable
        }

        // Reserving keeps the model resident for later messages. Failure is not fatal —
        // the reservation pool is small and shared across apps.
        _ = try? await AssetInventory.reserve(locale: locale)
    }

    // MARK: - Dictation fallback (legacy locales)

    /// Requests speech authorization if needed.
    static func requestAuthorization() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status)
            }
        }
    }

    private static func recognizeWithDictation(
        fileURL: URL,
        locale: Locale,
        contextualStrings: [String]
    ) async throws -> String {
        let status = await requestAuthorization()
        guard status == .authorized else { throw TranscribeError.permissionDenied }

        guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable else {
            throw TranscribeError.unavailable
        }

        // Human: Refusing here is deliberate. `SFSpeechRecognizer` defaults to Apple's *server*
        // recognition, so leaving `requiresOnDeviceRecognition` false on a device or locale
        // without an on-device model would upload decrypted voice audio. `security-crypto.mdc`
        // forbids that outright, so no transcript is the correct outcome.
        guard recognizer.supportsOnDeviceRecognition else {
            throw TranscribeError.unavailable
        }

        let request = SFSpeechURLRecognitionRequest(url: fileURL)
        request.requiresOnDeviceRecognition = true
        request.addsPunctuation = true
        request.taskHint = .dictation
        if !contextualStrings.isEmpty {
            request.contextualStrings = contextualStrings
        }
        // Human: Partials are not shown anywhere — they are kept so a recognizer that gives up
        // part-way through still yields the text it managed, instead of losing everything.
        request.shouldReportPartialResults = true

        return try await withCheckedThrowingContinuation { cont in
            let box = ResultBox()
            recognizer.recognitionTask(with: request) { result, error in
                if let result {
                    let text = result.bestTranscription.formattedString
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    box.latest = text
                    if result.isFinal {
                        box.finish(cont) { $0.resume(returning: text) }
                        return
                    }
                }
                guard let error else { return }
                if let partial = box.latest, !partial.isEmpty {
                    box.finish(cont) { $0.resume(returning: partial) }
                } else {
                    box.finish(cont) {
                        $0.resume(throwing: TranscribeError.failed(error.localizedDescription))
                    }
                }
            }
        }
    }

    /// Guards single-resumption of the recognition continuation across many callback firings.
    private final class ResultBox: @unchecked Sendable {
        private let lock = NSLock()
        private var resumed = false
        var latest: String?

        func finish(
            _ continuation: CheckedContinuation<String, Error>,
            _ body: (CheckedContinuation<String, Error>) -> Void
        ) {
            lock.lock()
            let alreadyResumed = resumed
            resumed = true
            lock.unlock()
            guard !alreadyResumed else { return }
            body(continuation)
        }
    }
}
