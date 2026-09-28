#if DEBUG && targetEnvironment(simulator)
import CoreGraphics
import CoreVideo
import Foundation

/// The simulator cannot broadcast its screen. Share there starts this instead: a phone-shaped
/// pattern (a list of rows, a bar that moves) sent through the real `ScreenShareUploader` and
/// socket, so everything but ReplayKit itself runs between two simulators or a simulator and
/// the web. Debug simulator builds only.
nonisolated final class SimulatedBroadcast: @unchecked Sendable {
    private static let width = 590
    private static let height = 1278

    private let uploader = ScreenShareUploader()
    private let queue = DispatchQueue(label: "shroud.simulated-broadcast", qos: .userInitiated)
    // Only touched on the queue.
    private var timer: DispatchSourceTimer?
    private var tick = 0

    func start() {
        queue.async { [self] in
            do {
                try uploader.connect { [weak self] in self?.stop() }
            } catch {
                return
            }
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now(), repeating: 1.0 / 10.0)
            timer.setEventHandler { [weak self] in self?.frame() }
            timer.resume()
            self.timer = timer
        }
    }

    func stop() {
        queue.async { [self] in
            timer?.cancel()
            timer = nil
            uploader.close()
        }
    }

    private func frame() {
        var buffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]]
        CVPixelBufferCreate(kCFAllocatorDefault, Self.width, Self.height, kCVPixelFormatType_32BGRA, attributes as CFDictionary, &buffer)
        guard let buffer else { return }
        CVPixelBufferLockBaseAddress(buffer, [])
        if let context = CGContext(
            data: CVPixelBufferGetBaseAddress(buffer),
            width: Self.width,
            height: Self.height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) {
            draw(in: context)
        }
        CVPixelBufferUnlockBaseAddress(buffer, [])
        tick += 1
        uploader.send(buffer, orientation: 1)
    }

    /// A light screen with a header and rows, one of them sliding, so motion shows at the far end.
    private func draw(in context: CGContext) {
        let w = CGFloat(Self.width)
        let h = CGFloat(Self.height)
        context.setFillColor(CGColor(red: 0.96, green: 0.96, blue: 0.98, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: w, height: h))
        context.setFillColor(CGColor(red: 0.26, green: 0.36, blue: 0.95, alpha: 1))
        context.fill(CGRect(x: 0, y: h - 150, width: w, height: 150))
        for row in 0..<14 {
            let y = h - 220 - CGFloat(row) * 76
            context.setFillColor(CGColor(gray: 0.82, alpha: 1))
            context.fillEllipse(in: CGRect(x: 28, y: y, width: 52, height: 52))
            context.setFillColor(CGColor(gray: 0.3, alpha: 1))
            context.fill(CGRect(x: 100, y: y + 30, width: w * 0.5, height: 12))
            context.setFillColor(CGColor(gray: 0.6, alpha: 1))
            context.fill(CGRect(x: 100, y: y + 8, width: w * 0.35, height: 10))
        }
        let x = CGFloat((tick * 6) % Int(w - 80))
        context.setFillColor(CGColor(red: 0.88, green: 0.21, blue: 0.17, alpha: 1))
        context.fill(CGRect(x: x, y: 60, width: 80, height: 24))
    }
}
#endif
