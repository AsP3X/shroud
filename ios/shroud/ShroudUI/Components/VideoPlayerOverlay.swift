import AVKit
import SwiftUI

/// Full-screen playback for an in-memory encrypted video after local decrypt.
struct VideoPlayerOverlay: View {
    let data: Data
    let onClose: () -> Void

    @State private var player: AVPlayer?
    @State private var tempURL: URL?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let player {
                VideoPlayer(player: player)
                    .ignoresSafeArea()
            } else {
                ProgressView()
                    .tint(.white)
            }

            VStack {
                HStack {
                    Spacer()
                    Button {
                        onClose()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 32))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .white.opacity(0.25))
                            .padding(16)
                    }
                    .pressable(scale: 0.9)
                }
                Spacer()
            }
        }
        .task {
            await preparePlayer()
        }
        .onDisappear {
            player?.pause()
            player = nil
            if let tempURL {
                try? FileManager.default.removeItem(at: tempURL)
            }
        }
    }

    private func preparePlayer() async {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-play-\(UUID().uuidString).mp4")
        do {
            try data.write(to: url, options: .atomic)
            tempURL = url
            let item = AVPlayerItem(url: url)
            let av = AVPlayer(playerItem: item)
            player = av
            av.play()
        } catch {
            // Leave spinner; user can dismiss.
        }
    }
}
