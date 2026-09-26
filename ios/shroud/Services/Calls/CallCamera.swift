import AVFoundation
import Foundation
import WebRTC

/// Feeds the device camera into a call's video source; front camera first.
@MainActor
final class CallCamera {
    private let capturer: RTCCameraVideoCapturer
    private(set) var usesFrontCamera = true
    private(set) var isRunning = false
    /// The system paused the capture (Shroud left the screen, another app took the camera), or
    /// let it go on again: true while paused.
    var onPaused: ((Bool) -> Void)?
    private var observers: [NSObjectProtocol] = []

    init(source: RTCVideoSource) {
        capturer = RTCCameraVideoCapturer(delegate: source)
        let session = capturer.captureSession
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.onPaused?(true) }
            },
            center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.onPaused?(false) }
            },
        ]
    }

    /// False on a device without cameras (the simulator).
    static var isAvailable: Bool {
        !RTCCameraVideoCapturer.captureDevices().isEmpty
    }

    /// Whether the camera may be used, asking the person the first time.
    static func requestAccess() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return true
        case .notDetermined:
            return await AVCaptureDevice.requestAccess(for: .video)
        default:
            return false
        }
    }

    func start() {
        guard !isRunning, let device = device(front: usesFrontCamera) else { return }
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        guard let format = Self.bestFormat(formats) else { return }
        let maxRate = format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 30
        capturer.startCapture(with: device, format: format, fps: Int(min(30, maxRate)))
        isRunning = true
    }

    func stop() {
        guard isRunning else { return }
        capturer.stopCapture()
        isRunning = false
    }

    /// Stops for good: the call is over.
    func close() {
        stop()
        for observer in observers {
            NotificationCenter.default.removeObserver(observer)
        }
        observers = []
        onPaused = nil
    }

    func switchCamera() {
        usesFrontCamera.toggle()
        if isRunning {
            capturer.stopCapture()
            isRunning = false
            start()
        }
    }

    private func device(front: Bool) -> AVCaptureDevice? {
        let devices = RTCCameraVideoCapturer.captureDevices()
        return devices.first { $0.position == (front ? .front : .back) } ?? devices.first
    }

    /// The format closest to 1280×720.
    private static func bestFormat(_ formats: [AVCaptureDevice.Format]) -> AVCaptureDevice.Format? {
        formats.min { lhs, rhs in
            distance(lhs) < distance(rhs)
        }
    }

    private static func distance(_ format: AVCaptureDevice.Format) -> Int32 {
        let size = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        return abs(size.width - 1280) + abs(size.height - 720)
    }
}
