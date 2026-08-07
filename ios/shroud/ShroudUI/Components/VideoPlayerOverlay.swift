import AVFoundation
import SwiftUI

/// Full-screen playback for an in-memory encrypted video after local decrypt.
///
/// Telegram's video viewer, not AVKit's: black surface, sender + date in the top bar,
/// chrome that hides itself while the clip plays, a slim scrubber with elapsed/remaining,
/// and drag-down-to-dismiss that tracks the finger.
struct VideoPlayerOverlay: View {
    let data: Data
    /// Who sent it (top bar) — optional so callers that only have bytes still work.
    var title: String = ""
    var subtitle: String = ""
    let onClose: () -> Void

    @State private var playback = ChatVideoPlayer()
    @State private var chromeVisible = true
    @State private var hideChromeTask: Task<Void, Never>?
    @State private var dragOffset: CGFloat = 0
    @State private var scrubTime: Double?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// How far down the finger has to travel before the overlay lets go.
    private static let dismissThreshold: CGFloat = 110
    /// Idle time before the chrome fades out during playback.
    private static let chromeIdleDelay: Duration = .milliseconds(2800)

    private var shownTime: Double { scrubTime ?? playback.currentTime }

    private var elapsedLabel: String { ChatVideoPlayer.timeLabel(shownTime) }

    private var remainingLabel: String {
        guard playback.duration > 0 else { return "-0:00" }
        return "-" + ChatVideoPlayer.timeLabel(max(0, playback.duration - shownTime))
    }

    private var backdropOpacity: Double {
        max(0.35, 1 - abs(dragOffset) / 420)
    }

    private var dragScale: CGFloat {
        guard !reduceMotion else { return 1 }
        return max(0.86, 1 - abs(dragOffset) / 1600)
    }

    var body: some View {
        GeometryReader { geo in
            let topInset = max(geo.safeAreaInsets.top, Self.keyWindowSafeArea.top, 47)
            let bottomInset = max(geo.safeAreaInsets.bottom, Self.keyWindowSafeArea.bottom, 8)

            ZStack {
                Color.black
                    .opacity(backdropOpacity)
                    .ignoresSafeArea()

                stage
                    .offset(y: dragOffset)
                    .scaleEffect(dragScale)

                if chromeVisible {
                    VStack(spacing: 0) {
                        topChrome(topInset: topInset)
                        Spacer(minLength: 0)
                        bottomChrome(bottomInset: bottomInset)
                    }
                    .allowsHitTesting(abs(dragOffset) < 1)
                    .transition(.opacity)
                }
            }
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .statusBarHidden(!chromeVisible)
        .accessibilityAddTraits(.isModal)
        .task {
            await playback.start(data: data)
            scheduleChromeHide()
        }
        .onDisappear {
            hideChromeTask?.cancel()
            playback.teardown()
        }
        .onChange(of: playback.isPlaying) { _, playing in
            playing ? scheduleChromeHide() : showChrome()
        }
    }

    // MARK: - Stage

    @ViewBuilder
    private var stage: some View {
        ZStack {
            if let player = playback.player {
                PlayerLayerView(player: player)
                    .transition(.opacity)
            } else if playback.failed {
                VStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 30))
                    Text("This video could not be opened.")
                        .font(.system(size: 15, weight: .medium))
                }
                .foregroundStyle(Color.white.opacity(0.85))
            } else {
                ProgressView().tint(.white)
            }

            // Centre play control: always up while paused, otherwise it follows the chrome.
            if playback.isReady, !playback.isPlaying || chromeVisible {
                Button {
                    playback.toggle()
                    Haptics.impact(.light)
                    showChrome()
                } label: {
                    ZStack {
                        Circle()
                            .fill(Color.black.opacity(0.32))
                            .background(.ultraThinMaterial.opacity(0.6), in: Circle())
                            .frame(width: 74, height: 74)
                        Image(systemName: playback.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(Color.white)
                            .offset(x: playback.isPlaying ? 0 : 2)
                            .contentTransition(.symbolEffect(.replace))
                    }
                }
                .buttonStyle(.plain)
                .pressable(scale: 0.9, haptic: nil)
                .transition(.scale(scale: 0.8).combined(with: .opacity))
            }
        }
        .animation(Motion.fade, value: playback.isPlaying)
        .animation(Motion.fade, value: chromeVisible)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .onTapGesture { toggleChrome() }
        // Dismiss lives on the stage, not the whole overlay: a drag that starts on the
        // scrubber must scrub, not throw the player off screen.
        .gesture(dismissDrag)
    }

    // MARK: - Chrome

    private func topChrome(topInset: CGFloat) -> some View {
        HStack(spacing: 12) {
            Button {
                Haptics.impact(.light)
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Color.white)
                    .frame(width: 32, height: 32)
                    .background(Color.white.opacity(0.14), in: Circle())
            }
            .buttonStyle(.plain)
            .pressable(scale: 0.88, haptic: nil)
            .accessibilityLabel("Close video")

            if !title.isEmpty {
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .lineLimit(1)
                    if !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.system(size: 12))
                            .foregroundStyle(Color.white.opacity(0.65))
                            .lineLimit(1)
                    }
                }
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.bottom, 12)
        .padding(.top, topInset)
        .background {
            LinearGradient(
                colors: [Color.black.opacity(0.6), .clear],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
        }
    }

    private func bottomChrome(bottomInset: CGFloat) -> some View {
        HStack(spacing: 12) {
            Text(elapsedLabel)
                .font(.system(size: 12, weight: .medium).monospacedDigit())
                .foregroundStyle(Color.white)
                .frame(minWidth: 38, alignment: .leading)

            scrubber

            Text(remainingLabel)
                .font(.system(size: 12, weight: .medium).monospacedDigit())
                .foregroundStyle(Color.white.opacity(0.75))
                .frame(minWidth: 42, alignment: .trailing)
        }
        .padding(.horizontal, 18)
        .padding(.top, 14)
        .padding(.bottom, bottomInset + 10)
        .background {
            LinearGradient(
                colors: [.clear, Color.black.opacity(0.65)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
        }
    }

    private var scrubber: some View {
        GeometryReader { geo in
            let width = max(1, geo.size.width)
            let fraction = playback.duration > 0 ? min(1, max(0, shownTime / playback.duration)) : 0
            let knob: CGFloat = scrubTime == nil ? 11 : 15

            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.white.opacity(0.28))
                    .frame(height: 3)
                Capsule()
                    .fill(Color.white)
                    .frame(width: width * fraction, height: 3)
                Circle()
                    .fill(Color.white)
                    .frame(width: knob, height: knob)
                    .shadow(color: .black.opacity(0.3), radius: 2, y: 1)
                    .offset(x: width * fraction - knob / 2)
                    .animation(Motion.snappy, value: scrubTime == nil)
            }
            .frame(height: geo.size.height, alignment: .center)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard playback.duration > 0 else { return }
                        if scrubTime == nil {
                            playback.isScrubbing = true
                            Haptics.impact(.light)
                        }
                        let target = Double(min(max(0, value.location.x), width) / width) * playback.duration
                        scrubTime = target
                        playback.seek(to: target)
                        showChrome()
                    }
                    .onEnded { _ in
                        if let scrubTime {
                            playback.seek(to: scrubTime, precise: true)
                        }
                        scrubTime = nil
                        playback.isScrubbing = false
                        if playback.isPlaying { scheduleChromeHide() }
                    }
            )
        }
        .frame(height: 28)
        .accessibilityLabel("Playback position")
        .accessibilityValue("\(elapsedLabel) of \(ChatVideoPlayer.timeLabel(playback.duration))")
    }

    // MARK: - Gestures

    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { value in
                // Sideways swipes belong to nothing here; only vertical drag dismisses.
                guard abs(value.translation.height) > abs(value.translation.width) else { return }
                dragOffset = value.translation.height
            }
            .onEnded { value in
                let flung = value.predictedEndTranslation.height > 320
                if abs(dragOffset) > Self.dismissThreshold || flung {
                    Haptics.impact(.light)
                    onClose()
                } else {
                    withAnimation(Motion.standard) { dragOffset = 0 }
                }
            }
    }

    // MARK: - Chrome timing

    private func toggleChrome() {
        chromeVisible ? hideChrome() : showChrome()
    }

    private func showChrome() {
        withAnimation(.easeInOut(duration: 0.18)) { chromeVisible = true }
        scheduleChromeHide()
    }

    private func hideChrome() {
        hideChromeTask?.cancel()
        withAnimation(.easeInOut(duration: 0.18)) { chromeVisible = false }
    }

    /// Chrome only auto-hides while something is actually playing.
    private func scheduleChromeHide() {
        hideChromeTask?.cancel()
        guard playback.isPlaying else { return }
        hideChromeTask = Task {
            try? await Task.sleep(for: Self.chromeIdleDelay)
            guard !Task.isCancelled, playback.isPlaying, scrubTime == nil else { return }
            withAnimation(.easeInOut(duration: 0.18)) { chromeVisible = false }
        }
    }

    /// Window insets — reliable when this overlay sits inside a view that already ate the safe area.
    private static var keyWindowSafeArea: UIEdgeInsets {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow)
            ?? scenes.flatMap(\.windows).first
        return window?.safeAreaInsets ?? UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0)
    }
}

/// `AVPlayerLayer` without AVKit's control bar — the transport here is ours.
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    var gravity: AVLayerVideoGravity = .resizeAspect

    func makeUIView(context: Context) -> PlayerHostView {
        let view = PlayerHostView()
        view.backgroundColor = .clear
        view.playerLayer.player = player
        view.playerLayer.videoGravity = gravity
        return view
    }

    func updateUIView(_ uiView: PlayerHostView, context: Context) {
        if uiView.playerLayer.player !== player {
            uiView.playerLayer.player = player
        }
        uiView.playerLayer.videoGravity = gravity
    }

    final class PlayerHostView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer {
            // Guaranteed by `layerClass`.
            layer as! AVPlayerLayer
        }
    }
}
