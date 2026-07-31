import AVFoundation
import Foundation

/// Records compressed voice messages (M4A/AAC) for E2E media upload.
@MainActor
final class VoiceRecorder: NSObject {
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

    private var recorder: AVAudioRecorder?
    private var fileURL: URL?
    private(set) var isRecording = false
    private(set) var startedAt: Date?

    var elapsedSeconds: Int {
        guard let startedAt else { return 0 }
        return max(0, Int(Date().timeIntervalSince(startedAt)))
    }

    func start() async throws {
        guard !isRecording else { throw RecorderError.alreadyRecording }

        let session = AVAudioSession.sharedInstance()
        let granted: Bool
        if #available(iOS 17.0, *) {
            granted = await AVAudioApplication.requestRecordPermission()
        } else {
            granted = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
                session.requestRecordPermission { cont.resume(returning: $0) }
            }
        }
        guard granted else { throw RecorderError.permissionDenied }

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
        self.fileURL = url
        self.isRecording = true
        self.startedAt = Date()
    }

    /// Stops recording and returns file bytes + duration. Pass `discard: true` to delete without reading.
    func stop(discard: Bool) throws -> (data: Data, durationMs: Int)? {
        guard isRecording, let recorder, let fileURL else {
            throw RecorderError.notRecording
        }
        recorder.stop()
        isRecording = false
        let durationMs = Int((recorder.currentTime * 1000).rounded())
        self.recorder = nil
        startedAt = nil

        defer {
            try? FileManager.default.removeItem(at: fileURL)
            self.fileURL = nil
        }

        if discard { return nil }
        let data = try Data(contentsOf: fileURL)
        guard !data.isEmpty else { throw RecorderError.encodeFailed }
        return (data, max(durationMs, 1))
    }
}
