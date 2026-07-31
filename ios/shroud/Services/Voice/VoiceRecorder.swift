import AVFoundation
import Foundation
import Observation

/// Records compressed voice messages (M4A/AAC) for E2E media upload, and captures a
/// normalised amplitude envelope so the composer can draw a live waveform and the sent
/// message can carry a real (not decorative) one.
///
/// Human: Metering runs on a 20 Hz timer while recording. Every tick appends one sample to
/// `envelope`; `VoiceWaveform.downsample` averages that envelope down to the fixed-size array we
/// seal into the message payload. `liveLevels` is the tail of the envelope, which is what the
/// recording bar animates.
/// Agent: OWNS AVAudioRecorder + AVAudioSession(.playAndRecord). WRITES a temp .m4a in
/// NSTemporaryDirectory, always removed in `finish`/`cancel`. Deactivates the session on stop so
/// playback is not left routed to the earpiece. Never touches network or key material.
@Observable
@MainActor
final class VoiceRecorder {
    enum RecorderError: Error, LocalizedError {
        case permissionDenied
        case alreadyRecording
        case notRecording
        case encodeFailed

        var errorDescription: String? {
            switch self {
            case .permissionDenied: "Microphone access is required for voice messages."
            case .alreadyRecording: "Already recording."
            case .notRecording: "Not recording."
            case .encodeFailed: "Could not finish the recording."
            }
        }
    }

    /// Finished recording ready to seal and upload.
    struct Recording: Sendable {
        let data: Data
        let durationMs: Int
        /// Fixed-size amplitude envelope, 0…255 per bucket.
        let waveform: [UInt8]
    }

    /// Anything shorter than this is treated as an accidental tap, not a message.
    static let minimumDuration: TimeInterval = 0.6
    /// Bucket count sealed into the payload — enough detail for the widest bubble.
    static let waveformBuckets = 44

    private(set) var isRecording = false
    /// Seconds since `start()`; refreshed at metering rate so the timer can show centiseconds.
    private(set) var elapsed: TimeInterval = 0
    /// Most recent normalised levels (0…1), oldest first — drives the live waveform.
    private(set) var liveLevels: [Float] = []

    /// Full normalised envelope for the take; downsampled on finish.
    private var envelope: [Float] = []
    private var recorder: AVAudioRecorder?
    private var fileURL: URL?
    private var startedAt: Date?
    private var meterTask: Task<Void, Never>?

    /// How many live samples the recording bar shows at once.
    private let liveWindow = 44
    private let meterInterval: Duration = .milliseconds(50)

    // MARK: - Permission

    /// Asks for microphone access without starting a take, so the UI can explain a denial
    /// before the user is mid-gesture.
    static func requestPermission() async -> Bool {
        await AVAudioApplication.requestRecordPermission()
    }

    // MARK: - Lifecycle

    func start() async throws {
        guard !isRecording else { throw RecorderError.alreadyRecording }

        guard await Self.requestPermission() else { throw RecorderError.permissionDenied }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-voice-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder.isMeteringEnabled = true
        guard recorder.record() else { throw RecorderError.encodeFailed }

        self.recorder = recorder
        fileURL = url
        isRecording = true
        startedAt = Date()
        elapsed = 0
        envelope = []
        liveLevels = []
        startMetering()
    }

    /// Stops, keeps the audio, and returns it with its duration + waveform.
    /// Returns `nil` when the take was too short to be a real message.
    func finish() throws -> Recording? {
        guard isRecording, let recorder, let fileURL else {
            throw RecorderError.notRecording
        }
        let duration = recorder.currentTime
        // Read both of these off the recorder/envelope *before* tearing down — `teardown`
        // stops the recorder (which zeroes `currentTime`) and clears live state.
        let captured = VoiceWaveform.downsample(envelope, buckets: Self.waveformBuckets)
        teardown(recorder: recorder)

        defer { removeFile(fileURL) }

        guard duration >= Self.minimumDuration else { return nil }

        let data = try Data(contentsOf: fileURL)
        guard !data.isEmpty else { throw RecorderError.encodeFailed }

        return Recording(
            data: data,
            durationMs: max(Int((duration * 1000).rounded()), 1),
            waveform: captured
        )
    }

    /// Stops and throws the audio away (slide-to-cancel, trash button, view teardown).
    func cancel() {
        guard let recorder else {
            // Still clear any stale UI state if we were mid-teardown.
            resetState()
            return
        }
        teardown(recorder: recorder)
        if let fileURL { removeFile(fileURL) }
    }

    // MARK: - Metering

    private func startMetering() {
        meterTask?.cancel()
        meterTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: self?.meterInterval ?? .milliseconds(50))
                guard let self, self.isRecording else { return }
                self.sampleLevel()
            }
        }
    }

    private func sampleLevel() {
        guard let recorder, let startedAt else { return }
        recorder.updateMeters()
        let level = Self.normalize(
            average: recorder.averagePower(forChannel: 0),
            peak: recorder.peakPower(forChannel: 0)
        )
        envelope.append(level)
        liveLevels.append(level)
        if liveLevels.count > liveWindow {
            liveLevels.removeFirst(liveLevels.count - liveWindow)
        }
        elapsed = Date().timeIntervalSince(startedAt)
    }

    /// Maps AVAudioRecorder's dB scale (-160…0) onto 0…1.
    ///
    /// Human: Speech sits around -40…-5 dB, so a linear dB map would leave every bar pinned near
    /// the floor. We clamp to a -50 dB noise floor, blend average with peak so transients still
    /// show, then apply a mild curve to spread the quiet half of the range.
    nonisolated static func normalize(average: Float, peak: Float) -> Float {
        let floorDB: Float = -50
        func scaled(_ db: Float) -> Float {
            guard db.isFinite else { return 0 }
            return max(0, min(1, (db - floorDB) / -floorDB))
        }
        let blended = scaled(average) * 0.7 + scaled(peak) * 0.3
        return min(1, pow(blended, 0.6))
    }

    // MARK: - Teardown

    private func teardown(recorder: AVAudioRecorder) {
        meterTask?.cancel()
        meterTask = nil
        recorder.stop()
        resetState()
        // Hand the route back so playback does not stay stuck on the earpiece.
        try? AVAudioSession.sharedInstance().setActive(
            false,
            options: [.notifyOthersOnDeactivation]
        )
    }

    /// Human: Deliberately does **not** clear `envelope` — that is owned by `start()`. Clearing
    /// it here is what previously let `finish()` read an already-emptied envelope and ship a flat
    /// waveform for every message.
    private func resetState() {
        isRecording = false
        recorder = nil
        startedAt = nil
        elapsed = 0
        liveLevels = []
    }

    private func removeFile(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
        fileURL = nil
    }
}
