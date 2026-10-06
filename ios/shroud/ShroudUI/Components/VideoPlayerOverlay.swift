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

    /// The chrome lets go with the clip, as the photo viewer's does.
    private var chromeOpacity: Double {
        max(0, 1 - abs(dragOffset) / 180)
    }

    /// VoiceOver and Switch Control need the controls in reach, so they never hide by themselves.
    private var assistiveTechRunning: Bool {
        UIAccessibility.isVoiceOverRunning || UIAccessibility.isSwitchControlRunning
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

                // In its own container: glass outside one leaves at once, whatever the transition says.
                GlassEffectContainer {
                    if chromeVisible {
                        VStack(spacing: 0) {
                            topChrome(topInset: topInset)
                            Spacer(minLength: 0)
                            bottomChrome(bottomInset: bottomInset)
                        }
                        .transition(.opacity)
                    }
                }
                .opacity(chromeOpacity)
                .allowsHitTesting(abs(dragOffset) < 1)
            }
        }
        .ignoresSafeArea()
        // Dark inside the player only: `preferredColorScheme` would flip the whole window,
        // the chat behind included, for as long as the player is up.
        .environment(\.colorScheme, .dark)
        // Hidden throughout: in light mode the status bar would draw dark on black.
        .statusBarHidden(true)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        // VoiceOver's two-finger scrub closes the player; its two-finger double-tap plays or pauses.
        .accessibilityAction(.escape) { onClose() }
        .accessibilityAction(.magicTap) {
            guard playback.isReady else { return }
            playback.toggle()
            showChrome()
        }
        // VoiceOver turned on mid-clip: bring back the controls it needs.
        .onReceive(NotificationCenter.default.publisher(for: UIAccessibility.voiceOverStatusDidChangeNotification)) { _ in
            if UIAccessibility.isVoiceOverRunning { showChrome() }
        }
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
            // Failure first: the player exists before the clip is known to be playable, and
            // stays after it turns out not to be.
            if playback.failed {
                VStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 30))
                    Text("This video could not be opened.")
                        .font(.system(size: 15, weight: .medium))
                }
                .foregroundStyle(Color.white.opacity(0.85))
            } else {
                if let player = playback.player {
                    PlayerLayerView(player: player)
                        .transition(.opacity)
                }
                if !playback.isReady {
                    ProgressView().tint(.white)
                }
            }

            // Centre play control: always up while paused, otherwise it follows the chrome.
            // In its own container: glass outside one leaves at once, whatever the transition says.
            GlassEffectContainer {
                if playback.isReady, !playback.isPlaying || chromeVisible {
                    Button {
                        playback.toggle()
                        Haptics.impact(.light)
                        showChrome()
                    } label: {
                        Image(systemName: playback.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(Color.white)
                            .offset(x: playback.isPlaying ? 0 : 2)
                            .contentTransition(.symbolEffect(.replace))
                            .frame(width: 74, height: 74)
                            .contentShape(Circle())
                    }
                    // A large glass disc over the frame; interactive glass swells under the finger.
                    .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0, haptic: nil))
                    .glassEffect(.regular.interactive(), in: .circle)
                    .transition(.scale(scale: 0.8).combined(with: .opacity))
                }
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
        HStack(spacing: 10) {
            Button {
                Haptics.impact(.light)
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Color.white)
                    .frame(width: 40, height: 40)
                    .glassEffect(.regular.interactive(), in: .circle)
                    // Keeps the 40 pt circle with a 44 pt target; the paddings below give the
                    // 2 pt back, so the circle and the title stay where they were.
                    .padding(2)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0, haptic: nil))
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
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
        .padding(.top, topInset - 2)
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
            // Both roll once a second while the clip plays (the remaining time downwards); a
            // scrub jumps straight to the time under the finger.
            Text(elapsedLabel)
                .font(.system(size: 12, weight: .medium).monospacedDigit())
                .foregroundStyle(Color.white)
                .rollingDigits(value: elapsedLabel, animated: scrubTime == nil)
                .frame(minWidth: 38, alignment: .leading)

            scrubber

            Text(remainingLabel)
                .font(.system(size: 12, weight: .medium).monospacedDigit())
                .foregroundStyle(Color.white.opacity(0.75))
                .rollingDigits(value: remainingLabel, countsDown: true, animated: scrubTime == nil)
                .frame(minWidth: 42, alignment: .trailing)
        }
        .padding(.horizontal, 18)
        // The scrubber's 44 pt touch band takes 8 pt of each; the bar itself doesn't move.
        .padding(.top, 6)
        .padding(.bottom, bottomInset + 2)
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
        .frame(height: 44)
        // One adjustable element: VoiceOver's swipe up / down seeks a twentieth of the clip.
        .accessibilityElement()
        .accessibilityLabel("Playback position")
        .accessibilityValue("\(elapsedLabel) of \(ChatVideoPlayer.timeLabel(playback.duration))")
        .accessibilityAdjustableAction { direction in
            guard playback.duration > 0 else { return }
            let step = max(1, playback.duration / 20)
            let target = direction == .increment
                ? min(playback.duration, shownTime + step)
                : max(0, shownTime - step)
            playback.seek(to: target, precise: true)
            showChrome()
        }
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

    /// Chrome only auto-hides while something is actually playing — and never with VoiceOver or
    /// Switch Control, which would be left in a modal with nothing to reach.
    private func scheduleChromeHide() {
        hideChromeTask?.cancel()
        guard playback.isPlaying, !assistiveTechRunning else { return }
        hideChromeTask = Task {
            try? await Task.sleep(for: Self.chromeIdleDelay)
            guard !Task.isCancelled, playback.isPlaying, scrubTime == nil, !assistiveTechRunning else { return }
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
