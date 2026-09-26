import AVFoundation
import SwiftUI
import UIKit

/// Telegram-style video send screen — the confirm step between picking a clip and sending it.
///
/// Mirrors `MediaComposeOverlay` (photos) on purpose: same black surface, same top bar with the
/// recipient and Add, same stable caption field with the blue send button. What's different is
/// what sits between them — a looping preview, a filmstrip you can trim, and a mute toggle.
///
/// Important: the caption `TextField` must stay in the hierarchy across focus changes; swapping
/// whole bars in and out is what caused freezes in the photo compose screen.
struct VideoComposeOverlay: View {
    /// Every clip staged for this send. The first is shown; the rest sit in the strip.
    let videos: [PickedVideo]
    let peerUsername: String
    var onCancel: () -> Void
    /// Hands back one ready-to-send plan per staged clip, in order.
    var onSend: (_ plans: [VideoSendPlan]) -> Void
    var onAddMore: (() -> Void)?
    var onRemoveVideo: ((Int) -> Void)?

    @State private var caption = ""
    @State private var selection = 0
    /// Trim window per clip id, so removing one can't hand its handles to another.
    @State private var trims: [UUID: VideoTrim] = [:]
    @State private var muted: Set<UUID> = []
    /// Filmstrip tiles per clip id, generated once each.
    @State private var strips: [UUID: [UIImage]] = [:]
    @State private var player = ChatVideoPlayer()
    @State private var keyboardHeight: CGFloat = 0
    @State private var banner: String?
    @FocusState private var captionFocused: Bool

    private let chrome = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    private let telegramBlue = Color(red: 51 / 255, green: 144 / 255, blue: 236 / 255)

    /// Filmstrip tile count — enough to read the clip, few enough to generate quickly.
    private static let stripTileCount = 14

    private var current: PickedVideo? { videos[safe: selection] }

    private var currentTrim: VideoTrim {
        guard let current else { return VideoTrim(start: 0, end: 1) }
        return trims[current.id] ?? VideoTrim(start: 0, end: current.probe.durationSeconds)
    }

    private var isCurrentMuted: Bool {
        guard let current else { return false }
        return muted.contains(current.id)
    }

    private var isFocused: Bool { captionFocused }

    /// Rough output size: the source scaled by how much of it survives the trim.
    private var estimatedBytes: Int? {
        guard let current, current.probe.fileSizeBytes > 0, current.probe.durationSeconds > 0 else {
            return nil
        }
        let kept = currentTrim.duration / current.probe.durationSeconds
        return max(1, Int(Double(current.probe.fileSizeBytes) * kept))
    }

    private var selectionLabel: String {
        let kept = ChatVideoPlayer.timeLabel(currentTrim.duration)
        guard let estimatedBytes else { return kept }
        return "\(kept)  ·  ≈\(MediaCrypto.byteCountLabel(estimatedBytes))"
    }

    var body: some View {
        GeometryReader { geo in
            let topInset = max(geo.safeAreaInsets.top, Self.keyWindowSafeArea.top, 47)
            let homeInset = max(geo.safeAreaInsets.bottom, Self.keyWindowSafeArea.bottom, 8)

            ZStack {
                Color.black.ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture { dismissCaptionKeyboard() }

                VStack(spacing: 0) {
                    topChrome(topInset: topInset)
                        .opacity(isFocused ? 0.35 : 1)

                    if videos.count > 1 {
                        videoStrip
                            .opacity(isFocused ? 0 : 1)
                            .frame(height: isFocused ? 0 : nil)
                            .clipped()
                            .allowsHitTesting(!isFocused)
                    }

                    preview
                        .frame(maxWidth: .infinity, maxHeight: .infinity)

                    bottomChrome(bottomPadding: keyboardHeight > 0 ? keyboardHeight : max(homeInset, 8))
                }

                if let banner {
                    VStack {
                        Spacer()
                        Text(banner)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Color.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(chrome.opacity(0.95), in: Capsule())
                            .padding(.bottom, 240)
                    }
                    .transition(.opacity)
                    .allowsHitTesting(false)
                }
            }
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .accessibilityAddTraits(.isModal)
        .onAppear { syncTrims() }
        .onChange(of: videos.count) { _, _ in
            syncTrims()
            if selection >= videos.count { selection = max(0, videos.count - 1) }
        }
        // Re-arm the player and the filmstrip whenever the shown clip changes.
        .task(id: current?.id) {
            await loadCurrent()
        }
        .onDisappear { player.teardown() }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
            updateKeyboardHeight(from: note)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { note in
            let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
            withAnimation(.easeOut(duration: duration)) { keyboardHeight = 0 }
        }
    }

    // MARK: - Top

    private func topChrome(topInset: CGFloat) -> some View {
        VStack(spacing: 0) {
            Color.clear.frame(height: topInset)

            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 14, weight: .semibold))
                    Text(peerUsername)
                        .font(.system(size: 16, weight: .semibold))
                        .lineLimit(1)
                }
                .foregroundStyle(Color.white)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Sending to \(peerUsername)")

                Spacer(minLength: 8)

                Button {
                    dismissCaptionKeyboard()
                    Haptics.impact(.light)
                    onAddMore?()
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "plus")
                            .font(.system(size: 13, weight: .bold))
                        Text("Add")
                            .font(.system(size: 14, weight: .semibold))
                    }
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 12)
                    .frame(height: 30)
                    .background(chrome, in: Capsule())
                }
                .pressable(scale: 0.9, dimming: 0)
                .accessibilityLabel("Add more videos")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
        }
        .frame(maxWidth: .infinity)
        .background(Color.black)
    }

    /// Posters of everything queued for this send; tap to switch, long-press to remove.
    private var videoStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(videos.enumerated()), id: \.element.id) { index, video in
                    Button {
                        Haptics.impact(.light)
                        dismissCaptionKeyboard()
                        withAnimation(Motion.standard) { selection = index }
                    } label: {
                        ZStack(alignment: .bottomLeading) {
                            Group {
                                if let poster = video.poster {
                                    Image(uiImage: poster)
                                        .resizable()
                                        .scaledToFill()
                                } else {
                                    Color.white.opacity(0.1)
                                }
                            }
                            .frame(width: 54, height: 54)
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

                            Text(ChatVideoPlayer.timeLabel(video.probe.durationSeconds))
                                .font(.system(size: 9, weight: .bold).monospacedDigit())
                                .foregroundStyle(Color.white)
                                .padding(.horizontal, 4)
                                .padding(.vertical, 1.5)
                                .background(Color.black.opacity(0.55), in: Capsule())
                                .padding(4)
                        }
                        .overlay {
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .stroke(index == selection ? telegramBlue : Color.clear, lineWidth: 2.5)
                        }
                        .scaleEffect(index == selection ? 1.05 : 1)
                    }
                    .pressable(scale: 0.9, dimming: 0)
                    .contextMenu {
                        Button(role: .destructive) {
                            onRemoveVideo?(index)
                        } label: {
                            Label("Remove", systemImage: "trash")
                        }
                    }
                    .accessibilityLabel("Video \(index + 1) of \(videos.count)")
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .frame(height: 78)
        .animation(Motion.snappy, value: selection)
    }

    // MARK: - Preview

    private var preview: some View {
        ZStack {
            if let player = player.player {
                PlayerLayerView(player: player)
                    .aspectRatio(current?.probe.aspect ?? 16 / 9, contentMode: .fit)
                    .transition(.opacity)
            } else if let poster = current?.poster {
                Image(uiImage: poster)
                    .resizable()
                    .scaledToFit()
            } else {
                ProgressView().tint(.white)
            }

            if !player.isPlaying, player.isReady {
                Image(systemName: "play.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .offset(x: 2)
                    .frame(width: 64, height: 64)
                    .background(Color.black.opacity(0.32), in: Circle())
                    .background(.ultraThinMaterial.opacity(0.55), in: Circle())
                    .transition(.scale(scale: 0.8).combined(with: .opacity))
            }

            if isCurrentMuted {
                VStack {
                    HStack {
                        Label("MUTED", systemImage: "speaker.slash.fill")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(Color.white)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 5)
                            .background(Color.black.opacity(0.55), in: Capsule())
                        Spacer()
                    }
                    Spacer()
                }
                .padding(12)
                .transition(.opacity)
            }
        }
        .animation(Motion.fade, value: player.isPlaying)
        .animation(Motion.snappy, value: isCurrentMuted)
        .contentShape(Rectangle())
        .onTapGesture {
            dismissCaptionKeyboard()
            player.toggle()
            Haptics.impact(.light)
        }
    }

    // MARK: - Bottom

    private func bottomChrome(bottomPadding: CGFloat) -> some View {
        VStack(spacing: 12) {
            if !isFocused {
                trimSection
                    .transition(.opacity)
            }

            HStack(spacing: 10) {
                captionField
                    .frame(maxWidth: .infinity)
                trailingControl
                    .animation(.easeOut(duration: 0.2), value: isFocused)
            }

            if !isFocused {
                toolRow
                    .transition(.opacity)
            }

            Color.clear.frame(height: bottomPadding)
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .frame(maxWidth: .infinity)
        .background(Color.black)
        .animation(.easeOut(duration: 0.22), value: isFocused)
        .animation(.easeOut(duration: 0.25), value: keyboardHeight)
    }

    private var trimSection: some View {
        VStack(spacing: 6) {
            if let current {
                VideoTrimStrip(
                    frames: strips[current.id] ?? [],
                    duration: current.probe.durationSeconds,
                    trim: Binding(
                        get: { currentTrim },
                        set: { trims[current.id] = $0 }
                    ),
                    playhead: player.currentTime,
                    onSeek: { seconds in
                        player.pause()
                        player.seek(to: seconds)
                    },
                    onScrubEnd: {
                        applyLoopRange()
                        player.play()
                    }
                )
                .padding(.horizontal, 2)

                HStack(spacing: 6) {
                    Text(selectionLabel)
                        .font(.system(size: 12, weight: .medium).monospacedDigit())
                        .foregroundStyle(Color.white.opacity(0.75))
                        .contentTransition(.numericText())
                    Spacer(minLength: 0)
                    if currentTrim.duration < current.probe.durationSeconds - 0.05 {
                        Text("TRIMMED")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(telegramBlue)
                            .transition(.opacity)
                    }
                }
                .animation(Motion.snappy, value: currentTrim)
            }
        }
    }

    /// Stable caption field — never destroyed on focus change.
    private var captionField: some View {
        HStack(spacing: 10) {
            TextField("Add a caption...", text: $caption, axis: .vertical)
                .font(.system(size: 16))
                .foregroundStyle(Color.white)
                .lineLimit(1 ... 4)
                .focused($captionFocused)
                .tint(telegramBlue)

            if !isFocused, videos.count > 1 {
                Text("\(videos.count)")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .contentTransition(.numericText())
                    .frame(width: 22, height: 22)
                    .overlay { Circle().stroke(Color.white.opacity(0.4), lineWidth: 1.5) }
                    .accessibilityLabel("\(videos.count) videos")
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(minHeight: 44)
        .background(chrome, in: Capsule())
    }

    @ViewBuilder
    private var trailingControl: some View {
        if isFocused {
            Button {
                Haptics.impact(.light)
                dismissCaptionKeyboard()
            } label: {
                Image(systemName: "checkmark")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Color.black)
                    .frame(width: 44, height: 44)
                    .background(Color.white, in: Circle())
            }
            .pressable(scale: 0.85, dimming: 0)
            .accessibilityLabel("Done")
            .transition(.scale.combined(with: .opacity))
        } else {
            Button {
                dismissCaptionKeyboard()
                send()
            } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(Color.white)
                    .frame(width: 50, height: 50)
                    .background(telegramBlue, in: Circle())
            }
            .pressable(scale: 0.85, dimming: 0, haptic: .medium)
            .accessibilityLabel(videos.count > 1 ? "Send \(videos.count) videos" : "Send video")
            .transition(.scale.combined(with: .opacity))
        }
    }

    private var toolRow: some View {
        HStack(spacing: 8) {
            toolCircle(systemName: "chevron.left", label: "Back") {
                dismissCaptionKeyboard()
                player.pause()
                onCancel()
            }
            toolCircle(
                systemName: isCurrentMuted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                label: isCurrentMuted ? "Sound off" : "Sound on",
                active: isCurrentMuted,
                enabled: current?.probe.hasAudio ?? false
            ) {
                toggleMute()
            }
            toolCircle(
                systemName: "arrow.counterclockwise",
                label: "Reset trim",
                active: false,
                enabled: currentTrim.duration < (current?.probe.durationSeconds ?? 0) - 0.05
            ) {
                resetTrim()
            }
            Spacer(minLength: 0)
        }
    }

    private func toolCircle(
        systemName: String,
        label: String,
        active: Bool = false,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(active ? telegramBlue : Color.white)
                .frame(width: 44, height: 44)
                .contentShape(Circle())
                .opacity(enabled ? 1 : 0.35)
                .contentTransition(.symbolEffect(.replace))
        }
        // Liquid Glass tool circles over the clip, as the system's own editors draw them.
        .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0))
        .glassEffect(.regular.interactive(), in: .circle)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    // MARK: - Behaviour

    /// Gives every newly staged clip a full-range trim (and drops state for removed ones).
    private func syncTrims() {
        var next: [UUID: VideoTrim] = [:]
        for video in videos {
            next[video.id] = trims[video.id]
                ?? VideoTrim(start: 0, end: video.probe.durationSeconds)
        }
        trims = next
        muted = muted.intersection(Set(videos.map(\.id)))
    }

    private func loadCurrent() async {
        player.teardown()
        guard let current else { return }
        applyLoopRange()
        await player.start(url: current.url)
        applyLoopRange()
        // The preview has to honour this clip's own mute choice, not the last one's.
        player.player?.isMuted = muted.contains(current.id)

        guard strips[current.id] == nil else { return }
        let frames = await VideoMedia.filmstrip(url: current.url, count: Self.stripTileCount)
        guard !Task.isCancelled else { return }
        strips[current.id] = frames
    }

    /// Keeps preview playback inside the handles.
    private func applyLoopRange() {
        let trim = currentTrim
        player.loopRange = trim.start ... max(trim.start + 0.1, trim.end)
    }

    private func toggleMute() {
        guard let current else { return }
        Haptics.impact(.light)
        withAnimation(Motion.snappy) {
            if muted.contains(current.id) {
                muted.remove(current.id)
            } else {
                muted.insert(current.id)
            }
        }
        // Preview follows the choice so "muted" isn't a promise you only hear after sending.
        player.player?.isMuted = muted.contains(current.id)
        flash(muted.contains(current.id) ? "Sound will be removed" : "Sound will be kept")
    }

    private func resetTrim() {
        guard let current else { return }
        Haptics.impact(.light)
        withAnimation(Motion.standard) {
            trims[current.id] = VideoTrim(start: 0, end: current.probe.durationSeconds)
        }
        applyLoopRange()
        player.seek(to: 0, precise: true)
    }

    private func send() {
        Haptics.impact(.medium)
        player.pause()
        let text = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        let plans = videos.enumerated().map { index, video -> VideoSendPlan in
            let trim = trims[video.id] ?? VideoTrim(start: 0, end: video.probe.durationSeconds)
            let kept = trim.duration
            let ratio = video.probe.durationSeconds > 0 ? kept / video.probe.durationSeconds : 1
            return VideoSendPlan(
                sourceURL: video.url,
                // Telegram puts the caption on the first item of an album; so does the photo path.
                caption: index == 0 ? text : "",
                trim: trim,
                removeAudio: muted.contains(video.id),
                posterJPEG: posterJPEG(for: video, at: trim.start),
                width: video.probe.width,
                height: video.probe.height,
                durationMs: max(1, Int(kept * 1000)),
                estimatedBytes: video.probe.fileSizeBytes > 0
                    ? max(1, Int(Double(video.probe.fileSizeBytes) * ratio))
                    : nil
            )
        }
        onSend(plans)
    }

    /// Bubble poster: the filmstrip tile nearest the trim start, else the clip's first frame.
    private func posterJPEG(for video: PickedVideo, at start: Double) -> Data? {
        let frames = strips[video.id] ?? []
        let duration = video.probe.durationSeconds
        if !frames.isEmpty, duration > 0 {
            let index = min(frames.count - 1, max(0, Int((start / duration) * Double(frames.count))))
            if let data = frames[index].jpegData(compressionQuality: 0.7) { return data }
        }
        return video.poster?.jpegData(compressionQuality: 0.7)
    }

    private func dismissCaptionKeyboard() {
        guard captionFocused else { return }
        captionFocused = false
    }

    private func flash(_ text: String) {
        withAnimation(Motion.fade) { banner = text }
        Task {
            try? await Task.sleep(for: .milliseconds(1400))
            withAnimation(Motion.fade) { if banner == text { banner = nil } }
        }
    }

    private func updateKeyboardHeight(from note: Notification) {
        guard let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
        let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        // Prefer the active window scene's screen (UIScreen.main is deprecated in iOS 26).
        let screenHeight = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first(where: { $0.activationState == .foregroundActive })?
            .screen.bounds.height
            ?? frame.maxY
        let overlap = max(0, screenHeight - frame.origin.y)
        guard abs(overlap - keyboardHeight) > 0.5 else { return }
        withAnimation(.easeOut(duration: duration)) { keyboardHeight = overlap }
    }

    private static var keyWindowSafeArea: UIEdgeInsets {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow)
            ?? scenes.flatMap(\.windows).first
        return window?.safeAreaInsets ?? UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0)
    }
}
