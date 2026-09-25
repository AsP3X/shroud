import SwiftUI
import WebRTC

/// One WebRTC video track, cropped to fill. A front-camera self view is mirrored.
struct CallVideoView: UIViewRepresentable {
    let track: RTCVideoTrack?
    var mirror = false

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> RTCMTLVideoView {
        let view = RTCMTLVideoView(frame: .zero)
        view.videoContentMode = .scaleAspectFill
        view.clipsToBounds = true
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ view: RTCMTLVideoView, context: Context) {
        if context.coordinator.track !== track {
            context.coordinator.track?.remove(view)
            track?.add(view)
            context.coordinator.track = track
        }
        view.transform = mirror ? CGAffineTransform(scaleX: -1, y: 1) : .identity
    }

    static func dismantleUIView(_ view: RTCMTLVideoView, coordinator: Coordinator) {
        coordinator.track?.remove(view)
        coordinator.track = nil
    }

    final class Coordinator {
        var track: RTCVideoTrack?
    }
}
