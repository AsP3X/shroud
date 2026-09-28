#if DEBUG && targetEnvironment(simulator)
import CoreGraphics
import CoreVideo
import Foundation

/// The simulator cannot broadcast its screen. Share there starts this instead: a phone-shaped
/// pattern (a list of rows, a bar that moves) sent through the real `ScreenShareUploader` and
/// socket, so everything but ReplayKit itself runs between two simulators or a simulator and
/// the web. Frames come at a phone's size and on its display's beat, so the chosen resolution and
/// frame rate (`ScreenShareWire.Settings`) are applied by the uploader as on a phone. Debug
/// simulator builds only.
nonisolated final class SimulatedBroadcast: @unchecked Sendable {
    /// An iPhone 17 Pro's screen, in pixels; the pattern is drawn for a 590-wide one and scaled.
    private static let width = 1206
    private static let height = 2622
    private static let drawnWidth: CGFloat = 590
    private static let drawnHeight: CGFloat = 1278

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
            timer.schedule(deadline: .now(), repeating: 1.0 / 60.0)
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

    private lazy var pool: CVPixelBufferPool? = {
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: Self.width,
            kCVPixelBufferHeightKey as String: Self.height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
        ]
        var pool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &pool)
        return pool
    }()

    private func frame() {
        tick += 1
        var buffer: CVPixelBuffer?
        guard let pool else { return }
        CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &buffer)
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
            context.scaleBy(x: CGFloat(Self.width) / Self.drawnWidth, y: CGFloat(Self.height) / Self.drawnHeight)
            draw(in: context)
        }
        CVPixelBufferUnlockBaseAddress(buffer, [])
        uploader.send(buffer, orientation: 1)
    }

    /// A light screen with a header and rows, one of them sliding, so motion shows at the far end.
    private func draw(in context: CGContext) {
        let w = Self.drawnWidth
        let h = Self.drawnHeight
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
        let x = CGFloat(tick % Int(w - 80))
        context.setFillColor(CGColor(red: 0.88, green: 0.21, blue: 0.17, alpha: 1))
        context.fill(CGRect(x: x, y: 60, width: 80, height: 24))
    }
}
#endif
