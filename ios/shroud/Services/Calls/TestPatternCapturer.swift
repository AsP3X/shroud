#if DEBUG && targetEnvironment(simulator)
import CoreVideo
import Foundation
import QuartzCore
import WebRTC

/// The simulator has no camera: a moving colour pattern stands in, so a video call between two
/// simulators shows frames end to end. Debug simulator builds only.
///
/// Frames are made on a queue of their own by copying rows out of a pattern drawn once. Drawn
/// pixel by pixel on the main thread (unoptimised, in Debug), they took half of it and stuttered
/// every animation on the call screen while the camera was on.
nonisolated final class TestPatternCapturer: RTCVideoCapturer, @unchecked Sendable {
    private static let width = 640
    private static let height = 480
    /// Six bands of 80 points: the pattern repeats after this many points.
    private static let period = 480
    /// Drawn once per launch, by the first frame.
    private static let pattern = drawPattern()

    private let queue = DispatchQueue(label: "shroud.test-pattern", qos: .userInitiated)
    // Only touched on the queue.
    private var timer: DispatchSourceTimer?
    private var frameIndex = 0

    func start() {
        queue.sync {
            timer?.cancel()
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now(), repeating: 1.0 / 15.0)
            timer.setEventHandler { [weak self] in self?.emitFrame() }
            timer.resume()
            self.timer = timer
        }
    }

    /// No frame is sent once this returns.
    func stop() {
        queue.sync {
            timer?.cancel()
            timer = nil
        }
    }

    deinit {
        // Cancel is thread-safe. Doing it on `queue` would deadlock when the last
        // release happens on that queue (a frame's delegate dropping the capturer).
        timer?.cancel()
    }

    private func emitFrame() {
        var pixelBuffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]]
        CVPixelBufferCreate(
            kCFAllocatorDefault,
            Self.width,
            Self.height,
            kCVPixelFormatType_32BGRA,
            attributes as CFDictionary,
            &pixelBuffer
        )
        guard let pixelBuffer else { return }
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        if let base = CVPixelBufferGetBaseAddress(pixelBuffer) {
            let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
            let patternRow = (Self.width + Self.period) * 4
            let shift = (frameIndex * 4) % Self.period
            Self.pattern.withUnsafeBytes { source in
                for y in 0..<Self.height {
                    memcpy(base + y * bytesPerRow, source.baseAddress! + y * patternRow + shift * 4, Self.width * 4)
                }
            }
        }
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
        frameIndex += 1

        let buffer = RTCCVPixelBuffer(pixelBuffer: pixelBuffer)
        let timestamp = Int64(CACurrentMediaTime() * 1_000_000_000)
        let frame = RTCVideoFrame(buffer: buffer, rotation: ._0, timeStampNs: timestamp)
        delegate?.capturer(self, didCapture: frame)
    }

    /// The pattern one period wider than a frame, so every frame is a window into it.
    private static func drawPattern() -> [UInt8] {
        let columns = width + period
        var pixels = [UInt8](repeating: 255, count: columns * height * 4)
        for y in 0..<height {
            for x in 0..<columns {
                let band = (x / 80) % 6
                let offset = (y * columns + x) * 4
                pixels[offset] = UInt8(band * 40)            // blue
                pixels[offset + 1] = UInt8((y * 255) / height) // green
                pixels[offset + 2] = UInt8(255 - band * 40)  // red
            }
        }
        return pixels
    }
}
#endif
