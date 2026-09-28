import CoreImage
import CoreVideo
import Foundation
import ImageIO
import QuartzCore

/// How the broadcast extension hands the phone's screen to the app during a call.
///
/// Human: The system runs a screen broadcast in an extension of its own, with little memory and
/// no network worth using for a call. It sends each frame, scaled down and as a JPEG, over a Unix
/// socket in the app group's container; the app puts the frames on the call. Only the app and
/// its extensions can reach that folder. The app listens only while a call runs, so a broadcast
/// started without one ends at once, and closing the socket is how the app ends a broadcast.
/// Agent: compiled into the app and both extensions (ShroudShared); ShroudScreenShare and the app
/// use it. A frame is `Header.size` bytes of header (little-endian) and then `length` bytes of
/// JPEG. Covered by ScreenShareWireTests. The simulator's socket is in the Mac's /tmp, where any
/// process on the Mac could reach it: debug simulator builds only.
nonisolated enum ScreenShareWire {
    /// The broadcast extension's bundle id, which the system picker offers.
    static let extensionBundleID = "de.corespace.shroud.ScreenShare"

    /// The socket, in the app group's container: about 95 bytes on a phone, under the 104 a Unix
    /// socket path may have. Nil when it would not fit, so sharing is off rather than broken.
    /// The simulator's container path is far longer; its apps share the Mac's /tmp, so the socket
    /// goes there, named for the simulator so two of them never meet.
    static var socketURL: URL? {
        #if targetEnvironment(simulator)
        let device = ProcessInfo.processInfo.environment["SIMULATOR_UDID"] ?? "simulator"
        return URL(fileURLWithPath: "/tmp/shroud-screen-\(device).sock")
        #else
        guard let url = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: NotificationPayload.appGroup)?
            .appendingPathComponent("screen.sock"),
            sockaddr_un.unixAddress(path: url.path) != nil
        else { return nil }
        return url
        #endif
    }

    /// Frames a second at most. A phone screen is mostly still, and each frame costs the
    /// extension a scale and a JPEG within its small memory.
    static let framesPerSecond = 15.0
    /// The longest side of a frame on the wire, in pixels.
    static let maxSide: CGFloat = 1920
    static let jpegQuality: CGFloat = 0.78
    /// Larger than any real frame; a header claiming more is not believed.
    static let maxPayload = 8 * 1024 * 1024

    nonisolated struct Header: Equatable, Sendable {
        static let size = 24
        static let magic: UInt32 = 0x5348_5353 // "SHSS"
        static let version: UInt16 = 1

        var width: UInt32
        var height: UInt32
        /// How the picture is turned (`CGImagePropertyOrientation`), as the broadcast reports it.
        var orientation: UInt32
        var length: UInt32

        func encoded() -> Data {
            var data = Data(capacity: Self.size)
            data.appendLittle(Self.magic)
            data.appendLittle(Self.version)
            data.appendLittle(UInt16(0))
            data.appendLittle(width)
            data.appendLittle(height)
            data.appendLittle(orientation)
            data.appendLittle(length)
            return data
        }

        /// The header at the start of `data`; nil when it is not one (wrong magic or version,
        /// no picture, a length past `maxPayload`). `data` must hold `size` bytes.
        static func decode(_ data: Data) -> Header? {
            guard data.count >= size else { return nil }
            let base = data.startIndex
            guard data.readLittle(UInt32.self, at: base) == magic,
                  data.readLittle(UInt16.self, at: base + 4) == version
            else { return nil }
            let header = Header(
                width: data.readLittle(UInt32.self, at: base + 8),
                height: data.readLittle(UInt32.self, at: base + 12),
                orientation: data.readLittle(UInt32.self, at: base + 16),
                length: data.readLittle(UInt32.self, at: base + 20)
            )
            guard header.width > 0, header.height > 0, header.length > 0,
                  header.length <= UInt32(maxPayload)
            else { return nil }
            return header
        }
    }

    /// Pulls whole frames out of a byte stream that arrives in pieces.
    ///
    /// Agent: a frame of a few hundred KB comes in many small reads (a local socket moves 8 KB at a
    /// time unless told otherwise). Bytes are only appended while a frame is incomplete; the
    /// buffer is moved up once, when frames have been taken out of it.
    nonisolated struct Reader: Sendable {
        private var buffer = Data()
        /// Bytes at the front already handed out as frames.
        private var consumed = 0

        /// False once the stream said something that is not a frame: the connection is dropped.
        private(set) var isBroken = false

        /// Adds bytes and returns every frame now complete, oldest first.
        mutating func append(_ bytes: Data) -> [(header: Header, jpeg: Data)] {
            guard !isBroken else { return [] }
            buffer.append(bytes)
            var frames: [(header: Header, jpeg: Data)] = []
            while buffer.count - consumed >= Header.size {
                let at = buffer.startIndex + consumed
                guard let header = Header.decode(buffer[at..<(at + Header.size)]) else {
                    isBroken = true
                    buffer = Data()
                    consumed = 0
                    return frames
                }
                let total = Header.size + Int(header.length)
                guard buffer.count - consumed >= total else { break }
                frames.append((header, Data(buffer[(at + Header.size)..<(at + total)])))
                consumed += total
            }
            if consumed == buffer.count {
                buffer.removeAll(keepingCapacity: true)
                consumed = 0
            } else if consumed > 0 {
                buffer = Data(buffer[(buffer.startIndex + consumed)...])
                consumed = 0
            }
            return frames
        }
    }

    /// Socket buffers large enough for a frame or two, so one is not cut into 8 KB pieces.
    static let socketBuffer: Int32 = 1 << 20

    /// The size a frame goes out at: at most `maxSide` on its longest side, never enlarged, and
    /// even in both directions (video encoders want that).
    static func wireSize(width: Int, height: Int) -> (width: Int, height: Int) {
        let longest = CGFloat(max(width, height))
        let scale = longest > maxSide ? maxSide / longest : 1
        let even = { (value: CGFloat) in max(2, Int((value * scale).rounded(.down)) & ~1) }
        return (even(CGFloat(width)), even(CGFloat(height)))
    }
}

/// The extension's side: connects to the app, and sends frames as fast as it takes them.
///
/// Agent: the socket and the encoding live on one serial queue; the gate that drops frames is a
/// lock, so the broadcast's own thread never waits for an encode. A frame that arrives while the
/// previous one is still being written is dropped: a stalled app never piles frames up in the
/// extension's memory.
/// `onEnded` fires once, on that queue, when the app closes the socket (Stop, the call ended).
nonisolated final class ScreenShareUploader: @unchecked Sendable {
    enum ConnectError: Error {
        case noContainer
        case noCall
    }

    // Each frame's CoreImage temporaries go when it is done: the extension has little memory.
    private let queue = DispatchQueue(label: "shroud.screen-share.upload", qos: .userInitiated, autoreleaseFrequency: .workItem)
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    private var socket: Int32 = -1
    private var hangupWatch: DispatchSourceRead?
    private let gate = NSLock()
    // Under `gate`.
    private var busy = false
    private var lastSent: CFTimeInterval = 0
    private var ended = false
    // Only touched on `queue`.
    private var onEnded: (@Sendable () -> Void)?

    init() {}

    /// Connects to the app's socket. Fails when no call is listening. `onEnded` fires once, on the
    /// upload queue, when the app closes its end; it is in place before anything is watched, so
    /// a close that comes at once is not missed.
    func connect(onEnded: @escaping @Sendable () -> Void) throws {
        guard let url = ScreenShareWire.socketURL else { throw ConnectError.noContainer }
        let fd = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw ConnectError.noCall }
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        // An app that stops reading ends the broadcast after a while, rather than leaving the
        // upload queue (and with it `close()`) stuck in a write for good.
        var patience = timeval(tv_sec: 3, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &patience, socklen_t(MemoryLayout<timeval>.size))
        var room = ScreenShareWire.socketBuffer
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &room, socklen_t(MemoryLayout<Int32>.size))
        guard var address = sockaddr_un.unixAddress(path: url.path) else {
            Darwin.close(fd)
            throw ConnectError.noContainer
        }
        let connected = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard connected == 0 else {
            Darwin.close(fd)
            throw ConnectError.noCall
        }
        queue.sync {
            socket = fd
            self.onEnded = onEnded
            // The app says nothing on this socket: readable means it closed its end.
            let watch = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
            watch.setEventHandler { [weak self] in
                guard let self else { return }
                var byte: UInt8 = 0
                let got = recv(fd, &byte, 1, MSG_PEEK | MSG_DONTWAIT)
                // A wakeup with nothing to read is not a hang-up; only an end or an error is.
                if got == 0 || (got < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) { self.end() }
            }
            // Closed here, once the watch no longer reads it.
            watch.setCancelHandler { Darwin.close(fd) }
            watch.resume()
            hangupWatch = watch
        }
    }

    /// Sends one frame, scaled and compressed, unless the last is still on its way or it comes
    /// sooner than the frame rate allows. `orientation` is `CGImagePropertyOrientation`'s raw value.
    func send(_ pixels: CVPixelBuffer, orientation: UInt32) {
        let now = CACurrentMediaTime()
        let due = gate.withLock { () -> Bool in
            // A little slack: frames arrive on the display's beat (every 16.7 or 33.3 ms), and a
            // strict gate would take every third of them, not every other.
            guard !busy, !ended, now - lastSent >= 0.9 / ScreenShareWire.framesPerSecond else { return false }
            busy = true
            lastSent = now
            return true
        }
        guard due else { return }
        // Read on the queue only; the broadcast lets go of it once this frame is done.
        nonisolated(unsafe) let frame = pixels
        queue.async { [self] in
            defer { gate.withLock { busy = false } }
            guard let jpeg = encode(frame) else { return }
            let header = ScreenShareWire.Header(
                width: UInt32(jpeg.width),
                height: UInt32(jpeg.height),
                orientation: orientation,
                length: UInt32(jpeg.data.count)
            )
            if !write(header.encoded() + jpeg.data) { end() }
        }
    }

    /// The broadcast is over: the socket closes, and the app stops sharing. Safe from any thread,
    /// `onEnded` included (asynchronous, so it never waits on its own queue).
    func close() {
        queue.async { [self] in shut() }
    }

    private func encode(_ pixels: CVPixelBuffer) -> (data: Data, width: Int, height: Int)? {
        let width = CVPixelBufferGetWidth(pixels)
        let height = CVPixelBufferGetHeight(pixels)
        let size = ScreenShareWire.wireSize(width: width, height: height)
        var image = CIImage(cvPixelBuffer: pixels)
        if size.width != width || size.height != height {
            // Lanczos keeps small text legible where plain resampling would blur or alias it;
            // the crop keeps the frame exactly the even size the header gives.
            let scale = CGFloat(size.height) / CGFloat(height)
            let aspect = (CGFloat(size.width) / CGFloat(width)) / scale
            image = image
                .applyingFilter("CILanczosScaleTransform", parameters: [kCIInputScaleKey: scale, kCIInputAspectRatioKey: aspect])
                .cropped(to: CGRect(x: 0, y: 0, width: size.width, height: size.height))
        }
        let options = [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: ScreenShareWire.jpegQuality]
        guard let data = context.jpegRepresentation(of: image, colorSpace: colorSpace, options: options) else { return nil }
        return (data, size.width, size.height)
    }

    /// Writes all of `data`; false once the app has gone.
    private func write(_ data: Data) -> Bool {
        guard socket >= 0 else { return false }
        return data.withUnsafeBytes { raw -> Bool in
            var offset = 0
            while offset < raw.count {
                let wrote = Darwin.send(socket, raw.baseAddress! + offset, raw.count - offset, 0)
                if wrote < 0, errno == EINTR { continue }
                guard wrote > 0 else { return false }
                offset += wrote
            }
            return true
        }
    }

    private func end() {
        guard !gate.withLock({ ended }) else { return }
        shut()
        let onEnded = onEnded
        self.onEnded = nil
        onEnded?()
    }

    private func shut() {
        gate.withLock { ended = true }
        guard socket >= 0 else { return }
        socket = -1
        // Its cancel handler closes the socket.
        hangupWatch?.cancel()
        hangupWatch = nil
    }
}

extension sockaddr_un {
    /// A Unix socket address for `path`; nil when the path is too long for one (never cut short:
    /// a shortened path names another file).
    nonisolated static func unixAddress(path: String) -> sockaddr_un? {
        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        let bytes = Array(path.utf8)
        guard bytes.count < MemoryLayout.size(ofValue: address.sun_path) else { return nil }
        withUnsafeMutableBytes(of: &address.sun_path) { buffer in
            for (index, byte) in bytes.enumerated() { buffer[index] = byte }
            buffer[bytes.count] = 0
        }
        address.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        return address
    }
}

private extension Data {
    nonisolated mutating func appendLittle<T: FixedWidthInteger>(_ value: T) {
        var little = value.littleEndian
        Swift.withUnsafeBytes(of: &little) { append(contentsOf: $0) }
    }

    nonisolated func readLittle<T: FixedWidthInteger>(_: T.Type, at offset: Int) -> T {
        var value: T = 0
        Swift.withUnsafeMutableBytes(of: &value) { target in
            for index in 0..<MemoryLayout<T>.size { target[index] = self[offset + index] }
        }
        return T(littleEndian: value)
    }
}
