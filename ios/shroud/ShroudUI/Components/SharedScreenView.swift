import ReplayKit
import SwiftUI
import UIKit
import WebRTC

/// Their shared screen: all of it, on black, pinched or double-tapped up to read small print and
/// dragged around while zoomed.
///
/// Human: A laptop's screen is wide and a phone is narrow, so the picture is fitted whole first
/// (turn the phone for a larger one). Zooming stays sharp up to the screen's own pixels: the
/// renderer always draws at the video's size, and the zoom only scales that. A tap shows or hides
/// the call's controls, as in Photos.
/// Agent: a UIScrollView zooming the RTCMTLVideoView itself; the video view is sized to the
/// picture's fitted rect (from `didChangeVideoSize`) and centred with content insets.
struct SharedScreenView: UIViewRepresentable {
    let track: RTCVideoTrack
    var onTap: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> SharedScreenContainer {
        let view = SharedScreenContainer()
        view.onTap = onTap
        track.add(view.video)
        context.coordinator.track = track
        return view
    }

    func updateUIView(_ view: SharedScreenContainer, context: Context) {
        view.onTap = onTap
        if context.coordinator.track !== track {
            context.coordinator.track?.remove(view.video)
            track.add(view.video)
            context.coordinator.track = track
        }
    }

    static func dismantleUIView(_ view: SharedScreenContainer, coordinator: Coordinator) {
        coordinator.track?.remove(view.video)
        coordinator.track = nil
    }

    final class Coordinator {
        var track: RTCVideoTrack?
    }
}

final class SharedScreenContainer: UIView, UIScrollViewDelegate, RTCVideoViewDelegate {
    let video = RTCMTLVideoView(frame: .zero)
    var onTap: (() -> Void)?

    private let scroll = UIScrollView()
    /// The picture's size in pixels, turned as it shows; zero until the first frame.
    private var pictureSize = CGSize.zero
    private var laidOutFor = CGSize.zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        scroll.delegate = self
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 4
        scroll.bouncesZoom = true
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        scroll.contentInsetAdjustmentBehavior = .never
        scroll.decelerationRate = .fast
        addSubview(scroll)
        video.videoContentMode = .scaleAspectFit
        video.backgroundColor = .black
        video.delegate = self
        scroll.addSubview(video)

        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        double.numberOfTapsRequired = 2
        let single = UITapGestureRecognizer(target: self, action: #selector(tapped))
        single.require(toFail: double)
        scroll.addGestureRecognizer(double)
        scroll.addGestureRecognizer(single)
        isAccessibilityElement = true
        accessibilityLabel = "Their shared screen"
        accessibilityHint = "Double-tap to show or hide the call controls."
        accessibilityTraits = .image
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        scroll.frame = bounds
        // A new shape (turned, or a new picture): fitted whole again.
        guard laidOutFor != bounds.size else { return }
        laidOutFor = bounds.size
        fit()
    }

    /// The whole picture in view, as large as it fits.
    private func fit() {
        scroll.zoomScale = 1
        let fitted = fittedSize
        video.frame = CGRect(origin: .zero, size: fitted)
        scroll.contentSize = fitted
        centre()
    }

    private var fittedSize: CGSize {
        guard pictureSize.width > 0, pictureSize.height > 0, bounds.width > 0, bounds.height > 0 else {
            return bounds.size
        }
        let scale = min(bounds.width / pictureSize.width, bounds.height / pictureSize.height)
        return CGSize(width: (pictureSize.width * scale).rounded(), height: (pictureSize.height * scale).rounded())
    }

    /// A picture smaller than the screen sits in its middle; a larger one reaches every edge.
    private func centre() {
        let content = video.frame.size
        let x = max(0, (bounds.width - content.width) / 2)
        let y = max(0, (bounds.height - content.height) / 2)
        scroll.contentInset = UIEdgeInsets(top: y, left: x, bottom: y, right: x)
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { video }

    func scrollViewDidZoom(_ scrollView: UIScrollView) { centre() }

    @objc private func tapped() { onTap?() }

    /// Zoomed in round the point tapped, or back to the whole picture.
    @objc private func doubleTapped(_ gesture: UITapGestureRecognizer) {
        if scroll.zoomScale > 1.01 {
            scroll.setZoomScale(1, animated: true)
            return
        }
        let point = gesture.location(in: video)
        let scale: CGFloat = 2.5
        let size = CGSize(width: scroll.bounds.width / scale, height: scroll.bounds.height / scale)
        scroll.zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height), animated: true)
    }

    override func accessibilityActivate() -> Bool {
        onTap?()
        return true
    }

    // MARK: - RTCVideoViewDelegate

    nonisolated func videoView(_ videoView: any RTCVideoRenderer, didChangeVideoSize size: CGSize) {
        Task { @MainActor in
            guard size != self.pictureSize else { return }
            let old = self.pictureSize
            self.pictureSize = size
            // A new resolution of the same picture (the sharer's choice, or the encoder adapting)
            // keeps the zoom; only a new shape (turned, another window) is fitted whole again.
            if old.width > 0, old.height > 0, size.width > 0, size.height > 0,
               abs((size.width / size.height) / (old.width / old.height) - 1) < 0.01 {
                return
            }
            self.fit()
        }
    }
}

/// The system's broadcast picker, kept out of sight: Share opens it for the person to start the
/// broadcast (an app cannot start one itself). Shroud's extension is preselected and the
/// microphone switch is hidden, since the call already carries the microphone.
struct BroadcastPickerHost: UIViewRepresentable {
    let trigger: BroadcastPickerTrigger

    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
        picker.preferredExtension = ScreenShareWire.extensionBundleID
        picker.showsMicrophoneButton = false
        picker.alpha = 0.01
        picker.isAccessibilityElement = false
        picker.accessibilityElementsHidden = true
        trigger.picker = picker
        return picker
    }

    func updateUIView(_ picker: RPSystemBroadcastPickerView, context: Context) {
        trigger.picker = picker
    }
}

/// Opens the hidden picker from a button elsewhere on the call screen.
/// Agent: the picker has no API to open it; its own button is pressed, as every app that offers
/// screen broadcasting does.
final class BroadcastPickerTrigger {
    weak var picker: RPSystemBroadcastPickerView?

    func open() {
        guard let picker else { return }
        let button = picker.subviews.lazy.compactMap { $0 as? UIButton }.first
        button?.sendActions(for: .touchUpInside)
    }
}
