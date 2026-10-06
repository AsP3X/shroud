import AVFoundation
import Foundation
import WebRTC

/// Feeds the device camera into a call's video source, through `CameraFramer`; front camera first.
///
/// Human: Where the camera frames people by itself (Apple's Center Stage: iPads, newer front
/// cameras), the Center Stage switch turns that on and off, and a change the person makes in
/// Control Center comes back to the switch (docs/calls.md, "Framing and Center Stage"). Whether
/// Apple's is actually framing right now (`isCenterStageActive`) is reported as it changes: while
/// it is not (another camera, a format without it, switched off), `CameraFramer` finds the faces.
///
/// Apple's Center Stage switch is one setting for the whole app, also used by any other camera in
/// it (the in-app camera picker), and the person can change it in Control Center whenever a camera
/// runs. So the call camera holds it only while it runs: on start it takes control
/// (`cooperative`), and Apple's switch and ours meet: a change made in Control Center since a call
/// camera last let go wins (it comes to our switch), otherwise ours is set there. While it runs,
/// each side follows the other. When it stops (video off, the call over) it hands control back in
/// the mode it found, and leaves Apple's switch where the call had it.
@MainActor
final class CallCamera {
    private let capturer: RTCCameraVideoCapturer
    private(set) var usesFrontCamera = true
    private(set) var isRunning = false
    /// The system paused the capture (Shroud left the screen, another app took the camera), or
    /// let it go on again: true while paused.
    var onPaused: ((Bool) -> Void)?
    /// The person turned Apple's Center Stage on or off (in Control Center, or since the last call
    /// camera let go of it): our switch follows.
    var onSystemCenterStage: ((Bool) -> Void)?
    /// Apple's Center Stage is framing the running camera now (true), or not (false); false when
    /// the camera stops or switches, until the next one says.
    var onSystemFraming: ((Bool) -> Void)?
    private var observers: [NSObjectProtocol] = []
    private var centerStageWatch: SystemCenterStageWatch?
    private var activeWatch: CenterStageActiveWatch?
    /// Counts the camera's runs, so a late report from a camera since stopped is ignored.
    private var run = 0
    /// The control mode Apple's Center Stage had before this camera took it; nil while it is not
    /// held.
    private var heldMode: AVCaptureDevice.CenterStageControlMode?
    /// What the Center Stage switch says; Apple's follows it while the camera holds it.
    private var centerStage = true

    /// - Parameter delegate: where the frames go: the `CameraFramer` in front of the video source.
    ///   WebRTC holds it weakly; the caller keeps it.
    init(delegate: RTCVideoCapturerDelegate) {
        capturer = RTCCameraVideoCapturer(delegate: delegate)
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

    /// Some camera here has Apple's Center Stage: only then is Apple's switch taken and set.
    private static let phoneHasCenterStage = RTCCameraVideoCapturer.captureDevices().contains { device in
        device.formats.contains(where: \.isCenterStageSupported)
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

    /// The size the camera captures at (sensor orientation, so landscape), once it has started;
    /// the last one while it is stopped.
    private(set) var captureSize: CMVideoDimensions?

    func start() {
        guard !isRunning, let device = device(front: usesFrontCamera) else { return }
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        guard let format = Self.bestFormat(formats) else { return }
        var maxRate = Self.maxFrameRate(format)
        if format.isCenterStageSupported, let range = format.videoFrameRateRangeForCenterStage {
            // Center Stage limits the frame rate; asking for more than it allows would throw.
            maxRate = min(maxRate, range.maxFrameRate)
        }
        holdSystemCenterStage()
        capturer.startCapture(with: device, format: format, fps: Int(min(30, maxRate)))
        captureSize = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        isRunning = true
        run += 1
        let thisRun = run
        activeWatch = CenterStageActiveWatch(device: device) { [weak self] active in
            guard let self, run == thisRun, isRunning else { return }
            onSystemFraming?(active)
        }
    }

    /// The Center Stage switch. While the camera holds Apple's Center Stage, that follows it;
    /// otherwise `CameraFramer` does the framing and this only remembers it.
    func setCenterStage(_ on: Bool) {
        centerStage = on
        if heldMode != nil { applySystemCenterStage() }
    }

    /// Takes Apple's Center Stage for the call (`cooperative`: shared with Control Center), on a
    /// phone that has it. The switches meet first: Apple's, when it changed since a call camera
    /// last let go of it (the person turned it in Control Center meanwhile), else ours.
    private func holdSystemCenterStage() {
        guard Self.phoneHasCenterStage, heldMode == nil else { return }
        heldMode = AVCaptureDevice.centerStageControlMode
        let agreed = CallCenterStage.agreed(
            ours: centerStage,
            system: AVCaptureDevice.isCenterStageEnabled,
            systemLeft: CallCenterStage.systemLeft
        )
        if agreed != centerStage {
            centerStage = agreed
            onSystemCenterStage?(agreed)
        }
        applySystemCenterStage()
    }

    /// Apple's Center Stage set to the switch, and a change made in Control Center reported back.
    /// The mode has to be set first, or setting it throws.
    private func applySystemCenterStage() {
        if AVCaptureDevice.centerStageControlMode != .cooperative {
            AVCaptureDevice.centerStageControlMode = .cooperative
        }
        if AVCaptureDevice.isCenterStageEnabled != centerStage {
            AVCaptureDevice.isCenterStageEnabled = centerStage
        }
        if centerStageWatch == nil {
            centerStageWatch = SystemCenterStageWatch { [weak self] on in
                guard let self, heldMode != nil, on != centerStage else { return }
                centerStage = on
                onSystemCenterStage?(on)
            }
        }
    }

    /// Hands Apple's Center Stage back in the mode it was found in, its switch where the call left
    /// it; remembers that value, so a change made before the next call is seen as the person's.
    private func releaseSystemCenterStage() {
        guard let mode = heldMode else { return }
        heldMode = nil
        // First, so the mode change below is not taken for the person's.
        centerStageWatch = nil
        if AVCaptureDevice.centerStageControlMode != mode {
            AVCaptureDevice.centerStageControlMode = mode
        }
        CallCenterStage.systemLeft = AVCaptureDevice.isCenterStageEnabled
    }

    func stop() {
        if isRunning { halt() }
        releaseSystemCenterStage()
    }

    /// The capture stops; Apple's Center Stage stays held (a camera switch starts the other one).
    private func halt() {
        capturer.stopCapture()
        isRunning = false
        activeWatch = nil
        onSystemFraming?(false)
    }

    /// Stops for good: the call is over.
    func close() {
        stop()
        for observer in observers {
            NotificationCenter.default.removeObserver(observer)
        }
        observers = []
        onPaused = nil
        onSystemCenterStage = nil
        onSystemFraming = nil
    }

    func switchCamera() {
        usesFrontCamera.toggle()
        if isRunning {
            halt()
            start()
        }
    }

    private func device(front: Bool) -> AVCaptureDevice? {
        let devices = RTCCameraVideoCapturer.captureDevices()
        return devices.first { $0.position == (front ? .front : .back) } ?? devices.first
    }

    /// The format closest to 1920×1080 among those that reach 30 fps (any, when none does), one
    /// with Apple's Center Stage first among those of the same size. `CameraFramer` cuts it to
    /// the shape the other side shows, and the encoder sends a rung of the ladder below that, as
    /// the link allows (`CallVideoQuality`).
    private static func bestFormat(_ formats: [AVCaptureDevice.Format]) -> AVCaptureDevice.Format? {
        let smooth = formats.filter { maxFrameRate($0) >= 30 }
        return (smooth.isEmpty ? formats : smooth).min { lhs, rhs in
            let left = distance(lhs)
            let right = distance(rhs)
            if left != right { return left < right }
            return lhs.isCenterStageSupported && !rhs.isCenterStageSupported
        }
    }

    private static func maxFrameRate(_ format: AVCaptureDevice.Format) -> Float64 {
        format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 30
    }

    private static func distance(_ format: AVCaptureDevice.Format) -> Int32 {
        let size = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        return abs(size.width - 1920) + abs(size.height - 1080)
    }
}

/// Reports changes to Apple's Center Stage switch (`AVCaptureDevice.isCenterStageEnabled`, a class
/// property the person also sets in Control Center), on the main actor.
///
/// Agent: key-value observing on the class object, as the property's documentation asks; there
/// is no notification for it.
nonisolated private final class SystemCenterStageWatch: NSObject, @unchecked Sendable {
    private static let keyPath = "centerStageEnabled"
    private let onChange: @MainActor @Sendable (Bool) -> Void

    init(onChange: @escaping @MainActor @Sendable (Bool) -> Void) {
        self.onChange = onChange
        super.init()
        (AVCaptureDevice.self as AnyObject).addObserver(self, forKeyPath: Self.keyPath, options: [.new], context: nil)
    }

    deinit {
        (AVCaptureDevice.self as AnyObject).removeObserver(self, forKeyPath: Self.keyPath)
    }

    override func observeValue(
        forKeyPath keyPath: String?,
        of object: Any?,
        change: [NSKeyValueChangeKey: Any]?,
        context: UnsafeMutableRawPointer?
    ) {
        let onChange = onChange
        Task { @MainActor in onChange(AVCaptureDevice.isCenterStageEnabled) }
    }
}

/// Reports whether Apple's Center Stage is framing one camera right now
/// (`AVCaptureDevice.isCenterStageActive`), at once and on every change, on the main actor.
///
/// Agent: key-value observing, as the property's documentation asks; the observation ends with
/// this object.
nonisolated private final class CenterStageActiveWatch: @unchecked Sendable {
    private var observation: NSKeyValueObservation?

    init(device: AVCaptureDevice, onChange: @escaping @MainActor @Sendable (Bool) -> Void) {
        observation = device.observe(\.isCenterStageActive, options: [.initial, .new]) { @Sendable device, _ in
            let active = device.isCenterStageActive
            Task { @MainActor in onChange(active) }
        }
    }

    deinit {
        observation?.invalidate()
    }
}
