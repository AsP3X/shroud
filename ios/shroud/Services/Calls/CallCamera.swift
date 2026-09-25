import AVFoundation
import Foundation
import WebRTC

/// Feeds the device camera into a call's video source; front camera first.
@MainActor
final class CallCamera {
    private let capturer: RTCCameraVideoCapturer
    private(set) var usesFrontCamera = true
    private(set) var isRunning = false

    init(source: RTCVideoSource) {
        capturer = RTCCameraVideoCapturer(delegate: source)
    }

    /// False on a device without cameras (the simulator).
    static var isAvailable: Bool {
        !RTCCameraVideoCapturer.captureDevices().isEmpty
    }

    func start() {
        guard let device = device(front: usesFrontCamera) else { return }
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
