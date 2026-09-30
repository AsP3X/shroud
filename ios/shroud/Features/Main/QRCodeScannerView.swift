import AVFoundation
import SwiftUI

/// Full-screen camera scanner for contact invite QR codes.
struct QRCodeScannerView: UIViewControllerRepresentable {
    var onCode: (String) -> Void
    var onCancel: () -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onCode = onCode
        controller.onCancel = onCancel
        return controller
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {}
}

/// Human: A live camera with a glass Cancel and a hint capsule that stay legible over a
/// bright scene (a white page, a phone screen — where QR codes are). Without camera access,
/// or without a camera, it says so and points to Settings or the paste field instead of
/// showing a silent black screen.
/// Agent: Start and stop run on `sessionQueue`, never on main; configuration runs on main
/// before the first start is queued. CALLS onCode once, then stops the session.
final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    var onCancel: (() -> Void)?

    private let session = AVCaptureSession()
    /// `startRunning` / `stopRunning` block until the camera is up or down. A serial queue
    /// keeps that off the main thread, and a stop queued after a start always runs after it.
    private let sessionQueue = DispatchQueue(label: "shroud.qr.session")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var isConfigured = false
    private var didEmit = false

    private let hint = UILabel()
    private let hintBackdrop = UIView()
    private var settingsButton: UIButton?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureChrome()
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            if !configureSession() {
                showCameraUnavailable(denied: false)
            }
        case .notDetermined:
            Task { [weak self] in
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                guard let self else { return }
                if granted, configureSession() {
                    // Still on screen: the system prompt covered it, so viewWillAppear has
                    // already run without a session to start.
                    if view.window != nil { startSession() }
                } else {
                    showCameraUnavailable(denied: !granted)
                }
            }
        default:
            showCameraUnavailable(denied: true)
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if isConfigured { startSession() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private func startSession() {
        sessionQueue.async { [session] in
            if !session.isRunning { session.startRunning() }
        }
    }

    private func stopSession() {
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    /// False when there is no camera (the simulator) or it can't be opened (access denied
    /// or restricted); the caller then shows why.
    private func configureSession() -> Bool {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else {
            return false
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return false }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        // Under the chrome, which may already be in place (access granted after the prompt).
        view.layer.insertSublayer(preview, at: 0)
        previewLayer = preview
        isConfigured = true
        return true
    }

    private func configureChrome() {
        // The same 44 pt glass xmark as the other full-screen dark overlays.
        var config = UIButton.Configuration.glass()
        config.image = UIImage(
            systemName: "xmark",
            withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .bold)
        )
        config.baseForegroundColor = .white
        config.cornerStyle = .capsule
        let cancel = UIButton(configuration: config, primaryAction: UIAction { [weak self] _ in
            self?.onCancel?()
        })
        cancel.accessibilityLabel = "Cancel"
        cancel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(cancel)

        // A dark capsule behind the hint keeps it readable over a white page.
        hintBackdrop.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        hintBackdrop.layer.cornerRadius = 16
        hintBackdrop.layer.cornerCurve = .continuous
        hintBackdrop.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hintBackdrop)

        hint.text = "Point at their Shroud QR code"
        hint.textColor = .white
        hint.font = .systemFont(ofSize: 15, weight: .medium)
        hint.textAlignment = .center
        hint.numberOfLines = 0
        hint.translatesAutoresizingMaskIntoConstraints = false
        hintBackdrop.addSubview(hint)

        NSLayoutConstraint.activate([
            cancel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            cancel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            cancel.widthAnchor.constraint(equalToConstant: 44),
            cancel.heightAnchor.constraint(equalToConstant: 44),

            hint.topAnchor.constraint(equalTo: hintBackdrop.topAnchor, constant: 8),
            hint.bottomAnchor.constraint(equalTo: hintBackdrop.bottomAnchor, constant: -8),
            hint.leadingAnchor.constraint(equalTo: hintBackdrop.leadingAnchor, constant: 14),
            hint.trailingAnchor.constraint(equalTo: hintBackdrop.trailingAnchor, constant: -14),

            hintBackdrop.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            hintBackdrop.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),
            hintBackdrop.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
        ])
    }

    /// Replaces the aiming hint with what went wrong; with access denied it also offers
    /// Settings. Cancel stays, and the Add Contact sheet behind it takes a pasted link.
    private func showCameraUnavailable(denied: Bool) {
        hint.text = denied
            ? "Camera access is off. Allow it in Settings, or paste their link instead."
            : "No camera available. Paste their link instead."

        if denied, settingsButton == nil {
            var config = UIButton.Configuration.glass()
            config.title = "Open Settings"
            config.baseForegroundColor = .white
            config.cornerStyle = .capsule
            config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
                var outgoing = incoming
                outgoing.font = UIFont.systemFont(ofSize: 16, weight: .semibold)
                return outgoing
            }
            let button = UIButton(configuration: config, primaryAction: UIAction { _ in
                guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                UIApplication.shared.open(url)
            })
            button.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(button)
            NSLayoutConstraint.activate([
                button.centerXAnchor.constraint(equalTo: view.centerXAnchor),
                button.bottomAnchor.constraint(equalTo: hintBackdrop.topAnchor, constant: -16),
                button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            ])
            settingsButton = button
        }
        UIAccessibility.post(notification: .layoutChanged, argument: hint)
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didEmit,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              object.type == .qr,
              let value = object.stringValue,
              !value.isEmpty
        else { return }
        didEmit = true
        // Off main: the cover's dismissal starts right away instead of waiting on the camera.
        stopSession()
        onCode?(value)
    }
}
