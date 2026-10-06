import AVFoundation
import SwiftUI
import UIKit

/// Telegram-style video send screen — the confirm step between picking a clip and sending it.
///
/// Mirrors `MediaComposeOverlay` (photos) on purpose: same black surface, same top bar with the
/// recipient and Add, same stable caption field with the blue send button. What's different is
/// what sits between them — a looping preview, a filmstrip you can trim, mute, and a quality choice.
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
    /// The clip on screen, by identity — an index would slide onto a neighbour when a clip to its
    /// left leaves the strip. Nil until the user picks one, which means the first.
    @State private var selectedID: UUID?
    /// Trim window per clip id, so removing one can't hand its handles to another.
    @State private var trims: [UUID: VideoTrim] = [:]
    @State private var muted: Set<UUID> = []
    /// While a trim handle is held: the kept length follows the finger instead of rolling.
    @State private var isTrimming = false
    /// One quality for every clip in this send. High is at most 720p.
    @State private var quality: VideoUploadQuality = .high
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

    /// Index into `videos` currently on screen.
    private var selection: Int {
        videos.firstIndex { $0.id == selectedID } ?? 0
    }

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

    private func plan(for video: PickedVideo, quality: VideoUploadQuality) -> Result<VideoOutgoingPlan, VideoPlanError> {
        let trim = trims[video.id] ?? VideoTrim(start: 0, end: video.probe.durationSeconds)
        do {
            return .success(
                try VideoMedia.previewPlan(
                    probe: video.probe,
                    fileExtension: video.url.pathExtension,
                    trim: trim,
                    removeAudio: muted.contains(video.id),
                    quality: quality
                )
            )
        } catch let error as VideoPlanError {
            return .failure(error)
        } catch {
            return .failure(VideoPlanError(message: "This video is too long to send.", maxSeconds: 1))
        }
    }

    private var currentPlan: VideoOutgoingPlan? {
        guard let current else { return nil }
        if case .success(let plan) = plan(for: current, quality: quality) { return plan }
        return nil
    }

    /// Why Send is held back. Nil when every staged clip fits the chosen quality.
    private var sendBlocked: String? {
        for video in videos {
            if case .failure(let error) = plan(for: video, quality: quality) {
                if video.id == current?.id { return error.message }
                return "One video won’t fit at this quality. \(error.message)"
            }
        }
        return nil
    }

    private var selectionLabel: String {
        let kept = ChatVideoPlayer.timeLabel(currentTrim.duration)
        guard let current else { return kept }
        if case .failure = plan(for: current, quality: quality) { return "\(kept)  ·  Too long" }
        guard let currentPlan else { return kept }
        return "\(kept)  ·  \(currentPlan.resolutionLabel)  ·  ≈\(MediaCrypto.byteCountLabel(currentPlan.estimatedBytes))"
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
                // Focus changes arrive without a transaction; animate the whole column so the strip,
                // top bar and preview move with the bottom chrome instead of snapping.
                .animation(Motion.scrim, value: isFocused)

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
        // `selection` needs no clamp: it follows `selectedID`, and falls back to the first clip.
        .onChange(of: videos.count) { _, _ in syncTrims() }
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
                    // A 44 pt target without making the capsule itself taller.
                    .contentShape(Capsule().inset(by: -7))
                }
                .pressable(scale: 0.9, dimming: 0)
                // The host passes nil once the send is full, so Add reads as unavailable.
                .disabled(onAddMore == nil)
                .opacity(onAddMore == nil ? 0.4 : 1)
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
                        dismissCaptionKeyboard()
                        withAnimation(Motion.standard) { selectedID = video.id }
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
                            removeVideo(at: index)
                        } label: {
                            Label("Remove", systemImage: "trash")
                        }
                    }
                    .accessibilityLabel("Video \(index + 1) of \(videos.count)")
                    // The label hides the duration pill, so VoiceOver gets it spelled out.
                    .accessibilityValue(
                        Duration.seconds(video.probe.durationSeconds.rounded(.down))
                            .formatted(.units(allowed: [.minutes, .seconds], width: .wide))
                    )
                    .accessibilityAddTraits(index == selection ? [.isSelected] : [])
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .frame(height: 78)
        .animation(Motion.snappy, value: selection)
    }

    /// Keeps the clip on screen when another one leaves; removing the one on screen moves to its
    /// right-hand neighbour (or the left one at the end).
    private func removeVideo(at index: Int) {
        guard let onRemoveVideo else { return }
        if index == selection {
            selectedID = (videos[safe: index + 1] ?? videos[safe: index - 1])?.id
        }
        onRemoveVideo(index)
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
            // While typing, a tap on the clip only drops the keyboard, as on the photo screen.
            if captionFocused {
                dismissCaptionKeyboard()
                return
            }
            player.toggle()
            Haptics.impact(.light)
        }
        // One element for VoiceOver: the play glyph is gone while the clip plays, which left
        // nothing to focus to pause it.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Video preview")
        .accessibilityValue((player.isPlaying ? "Playing" : "Paused") + (isCurrentMuted ? ", sound off" : ""))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction {
            dismissCaptionKeyboard()
            player.toggle()
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
        // Same as the column's; kept here so it outranks the keyboard-height animation below.
        .animation(Motion.scrim, value: isFocused)
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
                        isTrimming = true
                        player.pause()
                        player.seek(to: seconds)
                    },
                    onScrubEnd: {
                        isTrimming = false
                        applyLoopRange()
                        player.play()
                    }
                )
                .padding(.horizontal, 2)

                let isTrimmed = currentTrim.duration < current.probe.durationSeconds - 0.05
                HStack(spacing: 6) {
                    Text(selectionLabel)
                        .font(.system(size: 12, weight: .medium).monospacedDigit())
                        .foregroundStyle(currentPlan == nil ? Color(red: 1, green: 0.62, blue: 0.55) : Color.white.opacity(0.75))
                        .lineLimit(2)
                        // Rolls when a quality or a released trim changes it; while a handle is
                        // held the numbers follow the finger in place.
                        .rollingDigits(value: selectionLabel, animated: !isTrimming)
                    Spacer(minLength: 0)
                    qualityMenu
                    if isTrimmed {
                        Text("TRIMMED")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(telegramBlue)
                            .transition(.opacity)
                    }
                }
                // Keyed on the badge, not the trim: a handle drag writes the trim every tick.
                .animation(Motion.snappy, value: isTrimmed)
                .animation(Motion.snappy, value: quality)

                if let sendBlocked {
                    Text(sendBlocked)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Color(red: 1, green: 0.62, blue: 0.55))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
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
            .disabled(sendBlocked != nil)
            .opacity(sendBlocked == nil ? 1 : 0.4)
            .accessibilityLabel(videos.count > 1 ? "Send \(videos.count) videos" : "Send video")
            .transition(.scale.combined(with: .opacity))
        }
    }

    private var qualityMenu: some View {
        Menu {
            ForEach(VideoUploadQuality.allCases) { item in
                qualityChoice(item)
            }
        } label: {
            HStack(spacing: 4) {
                Text(quality.label)
                    .font(.system(size: 12, weight: .semibold))
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 9, weight: .bold))
            }
            .foregroundStyle(Color.white)
            .padding(.horizontal, 10)
            .frame(height: 26)
            .background(chrome, in: Capsule())
        }
        .tint(telegramBlue)
        .accessibilityLabel("Video quality, \(quality.label)")
        .onChange(of: quality) { _, new in
            Haptics.impact(.light)
            if let current, case .success(let plan) = plan(for: current, quality: new) {
                flash("\(new.label) · \(Self.hint(for: new, plan: plan))")
            } else {
                flash(new.label)
            }
        }
    }

    private func qualityChoice(_ item: VideoUploadQuality) -> some View {
        let offer: VideoOutgoingPlan? = {
            guard let current else { return nil }
            if case .success(let plan) = plan(for: current, quality: item) { return plan }
            return nil
        }()
        let title: String = {
            guard let offer else { return "\(item.label) · Too long" }
            let size = MediaCrypto.byteCountLabel(offer.estimatedBytes)
            return "\(item.label) · \(Self.hint(for: item, plan: offer))  ≈\(size)"
        }()
        return Button {
            quality = item
        } label: {
            if item == quality {
                Label(title, systemImage: "checkmark")
            } else {
                Text(title)
            }
        }
        .disabled(offer == nil)
    }

    private static func hint(for quality: VideoUploadQuality, plan: VideoOutgoingPlan) -> String {
        if quality == .original, plan.resolutionLabel == "Original" { return quality.hint }
        return plan.resolutionLabel
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
        withAnimation(Motion.standard) {
            trims[current.id] = VideoTrim(start: 0, end: current.probe.durationSeconds)
        }
        applyLoopRange()
        player.seek(to: 0, precise: true)
    }

    private func send() {
        if let sendBlocked {
            flash(sendBlocked)
            return
        }
        // The Send button's press-down `.medium` is the haptic; no second one here.
        player.pause()
        let text = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        let plans = videos.enumerated().map { index, video -> VideoSendPlan in
            let trim = trims[video.id] ?? VideoTrim(start: 0, end: video.probe.durationSeconds)
            let kept = trim.duration
            let preview = try? VideoMedia.previewPlan(
                probe: video.probe,
                fileExtension: video.url.pathExtension,
                trim: trim,
                removeAudio: muted.contains(video.id),
                quality: quality
            )
            return VideoSendPlan(
                sourceURL: video.url,
                // Telegram puts the caption on the first item of an album; so does the photo path.
                caption: index == 0 ? text : "",
                trim: trim,
                removeAudio: muted.contains(video.id),
                quality: quality,
                posterJPEG: posterJPEG(for: video, at: trim.start),
                width: preview?.width ?? video.probe.width,
                height: preview?.height ?? video.probe.height,
                durationMs: max(1, Int(kept * 1000)),
                estimatedBytes: preview?.estimatedBytes
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
