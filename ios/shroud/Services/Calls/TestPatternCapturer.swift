#if DEBUG && targetEnvironment(simulator)
import CoreVideo
import Foundation
import QuartzCore
import WebRTC

/// The simulator has no camera: a moving colour pattern stands in, so a video call between two
/// simulators shows frames end to end. Debug simulator builds only.
@MainActor
final class TestPatternCapturer: RTCVideoCapturer {
    private var timer: Timer?
    private var frameIndex = 0
    private let width = 640
    private let height = 480

    func start() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 15.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.emitFrame() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func emitFrame() {
        var pixelBuffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]]
        CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32BGRA,
            attributes as CFDictionary,
            &pixelBuffer
        )
        guard let pixelBuffer else { return }
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        if let base = CVPixelBufferGetBaseAddress(pixelBuffer) {
            let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
            let pixels = base.assumingMemoryBound(to: UInt8.self)
            let shift = frameIndex * 4
            for y in 0..<height {
                let row = pixels + y * bytesPerRow
                for x in 0..<width {
                    let band = ((x + shift) / 80) % 6
                    let offset = x * 4
                    row[offset] = UInt8(band * 40)            // blue
                    row[offset + 1] = UInt8((y * 255) / height) // green
                    row[offset + 2] = UInt8(255 - band * 40)  // red
                    row[offset + 3] = 255
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
}
#endif
