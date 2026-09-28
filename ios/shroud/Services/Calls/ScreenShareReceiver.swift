import CoreVideo
import Foundation
import ImageIO
import QuartzCore
import WebRTC

/// The app's end of a screen broadcast (`ScreenShareWire`): listens on the app group's socket
/// while a call runs, takes one broadcast at a time, and puts its frames on the call.
///
/// Human: A still screen sends no frames, so the last one goes out again every half second:
/// a picture that was lost on the way comes back without waiting for the screen to change.
/// Closing the connection is how a Stop in Shroud, or the end of the call, ends the broadcast.
/// Agent: sockets, decoding and the repeat timer run on one serial queue; `onEvent` hops to the
/// main queue, in order (a first frame never overtakes its connection). A socket is closed in its
/// dispatch source's cancel handler, never while the source may still read it. `feed` is where
/// frames go, set by the controller once the screen is on its section; frames before that are
/// decoded and kept as the one to repeat.
nonisolated final class ScreenShareReceiver: @unchecked Sendable {
    enum Event: Equatable, Sendable {
        /// A broadcast connected.
        case connected
        /// Its first frame is here.
        case firstFrame
        /// It ended, or the connection broke.
        case disconnected
    }

    private let queue = DispatchQueue(label: "shroud.screen-share.receive", qos: .userInitiated)
    private let path: String
    private let onEvent: @MainActor @Sendable (Event) -> Void
    // Only touched on `queue`.
    private var listener: Int32 = -1
    private var client: Int32 = -1
    private var acceptSource: DispatchSourceRead?
    private var readSource: DispatchSourceRead?
    private var repeatTimer: DispatchSourceTimer?
    private var reader = ScreenShareWire.Reader()
    private var last: (buffer: CVPixelBuffer, rotation: RTCVideoRotation)?
    private var lastAt: CFTimeInterval = 0
    private var sawFrame = false
    private var pool: CVPixelBufferPool?
    private var poolSize = (width: 0, height: 0)
    private var target: ScreenFrameFeed?
    /// One read buffer for the connection's life: a frame arrives in many reads.
    private var chunk = [UInt8](repeating: 0, count: 256 * 1024)

    /// Where frames go; nil drops them (kept as the one to repeat).
    var feed: ScreenFrameFeed? {
        get { queue.sync { target } }
        set { queue.async { self.target = newValue } }
    }

    /// Nil without an app group container (a build without the entitlement).
    init?(onEvent: @escaping @MainActor @Sendable (Event) -> Void) {
        guard let url = ScreenShareWire.socketURL else { return nil }
        path = url.path
        self.onEvent = onEvent
    }

    /// A receiver let go without `stop()` must not leave sources waking for nobody.
    deinit {
        acceptSource?.cancel()
        readSource?.cancel()
        repeatTimer?.cancel()
    }

    /// Starts listening. A socket left behind by an earlier run is replaced.
    func start() {
        queue.async { [self] in
            guard listener < 0 else { return }
            unlink(path)
            let fd = socket(AF_UNIX, SOCK_STREAM, 0)
            guard fd >= 0 else { return }
            guard var address = sockaddr_un.unixAddress(path: path) else {
                close(fd)
                return
            }
            let bound = withUnsafePointer(to: &address) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
                }
            }
            guard bound == 0, listen(fd, 1) == 0 else {
                close(fd)
                return
            }
            _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
            listener = fd
            let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
            source.setEventHandler { [weak self] in self?.acceptOne() }
            source.setCancelHandler { close(fd) }
            source.resume()
            acceptSource = source
        }
    }

    /// Ends the broadcast that is connected, if any, and keeps listening for the next.
    func dropBroadcast() {
        queue.async { [self] in closeClient(notify: true) }
    }

    /// Stops listening for good (the call ended); a connected broadcast ends with it.
    func stop() {
        queue.sync {
            closeClient(notify: false)
            // The source's cancel handler closes the listening socket.
            acceptSource?.cancel()
            acceptSource = nil
            if listener >= 0 {
                listener = -1
                unlink(path)
            }
            target = nil
        }
    }

    // MARK: - On the queue

    private func acceptOne() {
        let fd = accept(listener, nil, nil)
        guard fd >= 0 else {
            // Nothing waiting (a wakeup another took) is fine. Anything else (no descriptors
            // left) would wake this source again at once, for good: stop listening instead.
            if errno != EAGAIN, errno != EWOULDBLOCK, errno != EINTR, errno != ECONNABORTED {
                acceptSource?.cancel()
                acceptSource = nil
                listener = -1
                unlink(path)
            }
            return
        }
        // One broadcast at a time; a second is turned away (its extension ends it).
        guard client < 0 else {
            close(fd)
            return
        }
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        var room = ScreenShareWire.socketBuffer
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &room, socklen_t(MemoryLayout<Int32>.size))
        _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
        client = fd
        reader = ScreenShareWire.Reader()
        sawFrame = false
        last = nil
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in self?.readAvailable() }
        source.setCancelHandler { close(fd) }
        source.resume()
        readSource = source
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 0.5, repeating: 0.5)
        timer.setEventHandler { [weak self] in self?.repeatStill() }
        timer.resume()
        repeatTimer = timer
        emit(.connected)
    }

    private func readAvailable() {
        let fd = client
        let count = chunk.withUnsafeMutableBytes { recv(fd, $0.baseAddress, $0.count, 0) }
        if count == 0 || (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
            closeClient(notify: true)
            return
        }
        guard count > 0 else { return }
        let frames = reader.append(Data(chunk[0..<count]))
        if reader.isBroken {
            closeClient(notify: true)
            return
        }
        // Only the newest frame of a burst is worth decoding.
        guard let newest = frames.last, let buffer = decode(newest.jpeg) else { return }
        let rotation = Self.rotation(newest.header.orientation)
        last = (buffer, rotation)
        push(buffer, rotation)
        if !sawFrame {
            sawFrame = true
            emit(.firstFrame)
        }
    }

    /// The screen has not changed for a while: its last frame again.
    private func repeatStill() {
        guard let last, CACurrentMediaTime() - lastAt >= 0.45 else { return }
        push(last.buffer, last.rotation)
    }

    private func push(_ buffer: CVPixelBuffer, _ rotation: RTCVideoRotation) {
        lastAt = CACurrentMediaTime()
        guard let target else { return }
        let frame = RTCVideoFrame(
            buffer: RTCCVPixelBuffer(pixelBuffer: buffer),
            rotation: rotation,
            timeStampNs: Int64(lastAt * 1_000_000_000)
        )
        target.push(frame)
    }

    private func closeClient(notify: Bool) {
        guard client >= 0 else { return }
        // The source's cancel handler closes the socket, once it no longer reads it.
        readSource?.cancel()
        readSource = nil
        repeatTimer?.cancel()
        repeatTimer = nil
        client = -1
        last = nil
        if notify { emit(.disconnected) }
    }

    /// A JPEG into a pixel buffer the encoder can take (BGRA, IOSurface-backed, from a pool).
    private func decode(_ jpeg: Data) -> CVPixelBuffer? {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
        else { return nil }
        let width = image.width
        let height = image.height
        if pool == nil || poolSize != (width, height) {
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
                kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
            ]
            var created: CVPixelBufferPool?
            CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &created)
            pool = created
            poolSize = (width, height)
        }
        guard let pool else { return nil }
        var buffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &buffer)
        guard let buffer else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let context = CGContext(
            data: CVPixelBufferGetBaseAddress(buffer),
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
            // The JPEG's own colour space: drawn as it is, with no conversion per frame.
            space: image.colorSpace.flatMap { $0.model == .rgb ? $0 : nil } ?? CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return nil }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return buffer
    }

    /// The broadcast's orientation as the turn WebRTC sends along with the frame, so the other
    /// side shows a phone held sideways the right way up.
    static func rotation(_ orientation: UInt32) -> RTCVideoRotation {
        switch CGImagePropertyOrientation(rawValue: orientation) {
        case .down, .downMirrored: ._180
        case .left, .leftMirrored: ._90
        case .right, .rightMirrored: ._270
        default: ._0
        }
    }

    /// On the main queue, which keeps the order events were sent in (tasks would not).
    private func emit(_ event: Event) {
        let onEvent = onEvent
        DispatchQueue.main.async {
            MainActor.assumeIsolated { onEvent(event) }
        }
    }
}
