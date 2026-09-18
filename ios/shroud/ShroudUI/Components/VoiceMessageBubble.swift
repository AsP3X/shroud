import AVFoundation
import SwiftUI

/// Voice message bubble — Telegram layout: play/pause disc, scrubbable waveform, a "→A"
/// transcript toggle, elapsed/duration readout, unplayed dot, and the transcript folded inside.
///
/// Human: The bubble owns no player. Everything transport-related goes through
/// `VoicePlaybackCoordinator`, so scrolling a playing note off-screen does not kill it and
/// starting a second note stops the first — both Telegram behaviours. The transcript starts
/// folded; the toggle unfolds it inside the bubble, transcribing on device first when needed.
/// Agent: READS coordinator state + `message.voiceData`; CALLS onAppearLoad to trigger decrypt.
/// Scrubbing writes only to the coordinator; the fold lives in `VoiceTranscriptDisclosure`.
struct VoiceMessageBubble: View {
    let message: MessagingController.ChatMessage
    let time: String
    var onAppearLoad: () -> Void = {}
    var onRequestTranscript: (() async -> String?)? = nil

    @State private var playback = VoicePlaybackCoordinator.shared
    @State private var install = TranscriptionModelInstall.shared
    @State private var disclosure = VoiceTranscriptDisclosure.shared
    @State private var localTranscript: String?
    @State private var isTranscribing = false
    /// A transcription run on this device came back empty.
    @State private var foundNoSpeech = false
    /// Non-nil while the finger is on the waveform; overrides coordinator progress.
    @State private var scrubProgress: Double?
    /// Duration read back from the audio itself when the payload doesn't carry one.
    @State private var resolvedDurationMs: Int?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isMine: Bool { message.isMine }

    /// The note's text: shared by the sender or a peer, or made here on request.
    private var transcript: String? {
        for candidate in [localTranscript, message.transcript] {
            let text = candidate?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !text.isEmpty { return text }
        }
        return nil
    }

    /// Below this we assume the payload's duration is bogus rather than a real recording —
    /// the recorder itself refuses anything under `VoiceRecorder.minimumDuration`.
    private static let minimumTrustedDurationMs = 300

    /// Payload duration, falling back to what we measured from the decoded file.
    ///
    /// Human: Messages sent before the recorder was fixed carry `d = 1`, because the old code
    /// read `AVAudioRecorder.currentTime` *after* `stop()` (which reports 0) and then clamped it
    /// with `max(_, 1)`. Reading the length back off the decoded audio keeps those bubbles honest
    /// instead of showing "0:00" and sizing every one of them to the minimum width.
    private var durationMs: Int {
        let stated = message.voiceDurationMs ?? 0
        if stated >= Self.minimumTrustedDurationMs { return stated }
        return resolvedDurationMs ?? stated
    }

    /// Audio is still being fetched/decrypted — the disc shows a spinner instead of play.
    private var isLoading: Bool {
        message.voiceData == nil && message.mediaObjectId != nil && !message.deleted
    }

    private var isPlaying: Bool { playback.isPlaying(message.id) }

    private var progress: Double {
        scrubProgress ?? playback.progress(for: message.id)
    }

    /// Incoming notes keep a dot until listened to, like an unread badge.
    private var showsUnplayedDot: Bool {
        !isMine && !playback.hasPlayed(message.id)
    }

    // MARK: - Sizing

    /// Bubble width tracks duration (to a ceiling), so a 2-second note is visibly shorter than a
    /// 40-second one — the single strongest "this is a voice message" cue.
    private var barCount: Int {
        let seconds = Double(durationMs) / 1000
        return min(38, max(18, 16 + Int(seconds * 1.6)))
    }

    /// 3pt bar + 2pt gap, matching `VoiceWaveformView`'s defaults.
    private var waveformWidth: CGFloat {
        CGFloat(barCount) * 5
    }

    /// The bubble hugs its content, but never gets narrower than the footer needs —
    /// without this floor the duration, speed chip, timestamp and ticks collide on short notes.
    private var contentWidth: CGFloat {
        max(waveformWidth, 148)
    }

    /// Waveform plus the transcript toggle beside it; the footer spans both.
    private var columnWidth: CGFloat {
        contentWidth + (showsTranscriptButton ? Self.transcriptButtonGap + VoiceTranscriptButton.size : 0)
    }

    private static let playButtonSize: CGFloat = 38
    private static let playButtonGap: CGFloat = 10
    private static let transcriptButtonGap: CGFloat = 8
    /// Transcript text sits a hair inside the bubble's padding, level with text bubbles' inset.
    private static let transcriptInset: CGFloat = 2

    /// The unfolded transcript wraps at the bubble's own width and never widens it.
    private var transcriptWidth: CGFloat {
        Self.playButtonSize + Self.playButtonGap + columnWidth - Self.transcriptInset * 2
    }

    private var waveformSamples: [Float] {
        let source: [Float] = {
            if let stored = message.voiceWaveform, VoiceWaveform.isUsable(stored) {
                return VoiceWaveform.normalized(stored)
            }
            // No envelope, or a flat one from the build that shipped constant waveforms:
            // a stable stand-in beats a dead line.
            return VoiceWaveform.placeholder(for: message.id, count: barCount)
        }()
        return VoiceWaveform.resample(source, to: barCount)
    }

    // MARK: - Palette

    private var playedColor: Color { isMine ? .white : Theme.accent }

    private var remainingColor: Color {
        isMine ? Color.white.opacity(0.4) : Theme.accent.opacity(0.28)
    }

    private var metaColor: Color {
        isMine ? Color.white.opacity(0.7) : Theme.textSecondary
    }

    var body: some View {
        HStack {
            if isMine { Spacer(minLength: 48) }

            bubble

            if !isMine { Spacer(minLength: 48) }
        }
        .animation(Motion.respecting(reduceMotion, Motion.standard), value: transcript)
        .animation(Motion.respecting(reduceMotion, Motion.standard), value: isWorkingOnTranscript)
        .onAppear(perform: onAppearLoad)
        .task(id: message.voiceData) { await resolveDurationIfNeeded() }
        .onDisappear {
            // Only tear down if this bubble owns the player *and* is paused; a playing note
            // keeps going while the user scrolls, as it does in Telegram.
            if playback.isActive(message.id), !playback.isPlaying {
                playback.stopIfActive(message.id)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: isPlaying ? "Pause" : "Play") { togglePlayback() }
        .accessibilityActions {
            if showsTranscriptButton, transcriptButtonEnabled {
                Button(transcriptActionName) { toggleTranscript() }
            }
        }
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Self.playButtonGap) {
                playButton

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: Self.transcriptButtonGap) {
                        waveform
                            .frame(width: contentWidth)
                        if showsTranscriptButton {
                            transcriptButton
                        }
                    }
                    footer
                }
                .frame(width: columnWidth)
            }

            if isTranscriptOpen {
                transcriptDrawer
                    // Laid out at its final size, so the growing bubble uncovers it top-down
                    // (the clip below) while it fades in, rather than the text sliding around.
                    .transition(.asymmetric(
                        insertion: .opacity.combined(with: .offset(y: -6)),
                        removal: .opacity
                    ))
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(isMine ? Theme.accent : Theme.bubbleIncoming)
        .clipShape(
            UnevenRoundedRectangle(
                topLeadingRadius: 17.5,
                bottomLeadingRadius: isMine ? 17.5 : 5,
                bottomTrailingRadius: isMine ? 5 : 17.5,
                topTrailingRadius: 17.5,
                style: .continuous
            )
        )
        .shadow(color: Color.black.opacity(isMine ? 0 : 0.04), radius: 3, y: 1)
    }

    // MARK: - Play control

    private var playButton: some View {
        Button(action: togglePlayback) {
            ZStack {
                Circle()
                    .fill(isMine ? Color.white.opacity(0.22) : Theme.accent)
                    .frame(width: Self.playButtonSize, height: Self.playButtonSize)

                if isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .tint(Color.white)
                } else {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Color.white)
                        // Morphs through the shared shape instead of cutting.
                        .contentTransition(.symbolEffect(.replace.offUp))
                        // `play.fill` is visually left-heavy; nudge it onto the optical centre.
                        .offset(x: isPlaying ? 0 : 1)
                }
            }
        }
        .pressable(scale: 0.88, dimming: 0)
        .disabled(isLoading || message.voiceData == nil)
        .animation(Motion.snappy, value: isPlaying)
        .animation(Motion.snappy, value: isLoading)
        .accessibilityHidden(true)
    }

    // MARK: - Waveform (scrubbable)

    private var waveform: some View {
        GeometryReader { geo in
            VoiceWaveformView(
                samples: waveformSamples,
                progress: progress,
                playedColor: playedColor,
                remainingColor: remainingColor
            )
            .frame(height: 26)
            // Full-height target: the bars are 3pt wide, the gesture area must not be.
            .contentShape(Rectangle())
            .gesture(scrubGesture(width: geo.size.width))
        }
        .frame(height: 26)
    }

    /// Drag anywhere on the waveform to seek; the playhead follows the finger live.
    private func scrubGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard message.voiceData != nil, width > 0 else { return }
                scrubProgress = min(1, max(0, value.location.x / width))
            }
            .onEnded { value in
                defer { scrubProgress = nil }
                guard let data = message.voiceData, width > 0 else { return }
                let fraction = min(1, max(0, value.location.x / width))
                Haptics.impact(.light)
                playback.seek(id: message.id, data: data, to: fraction)
            }
    }

    // MARK: - Footer (time, speed, receipt)

    private var footer: some View {
        HStack(spacing: 6) {
            Text(
                VoiceTimeFormat.duration(
                    playback.displayTime(for: message.id, fallbackMs: durationMs)
                )
            )
            .font(.system(size: 11, weight: .medium))
            .monospacedDigit()
            .foregroundStyle(metaColor)
            .contentTransition(.numericText())

            if showsUnplayedDot {
                Circle()
                    .fill(Theme.accent)
                    .frame(width: 5, height: 5)
                    .transition(Motion.iconSwap)
            }

            if playback.isActive(message.id) {
                speedChip
                    .transition(Motion.iconSwap)
            }

            Spacer(minLength: 4)

            Text(time)
                .font(.system(size: 11))
                .monospacedDigit()
                .foregroundStyle(metaColor)

            if isMine {
                MessageReceiptIcon(
                    receipt: message.receipt,
                    metaColor: Color.white.opacity(0.7),
                    readColor: Color.white,
                    failedColor: Color.white
                )
            }
        }
        .animation(Motion.snappy, value: playback.isActive(message.id))
        .animation(Motion.snappy, value: showsUnplayedDot)
    }

    /// Telegram's 1× / 1.5× / 2× chip, shown only while this note is loaded.
    private var speedChip: some View {
        Button {
            Haptics.impact(.light)
            playback.cycleRate()
        } label: {
            Text(rateLabel)
                .font(.system(size: 10, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(isMine ? Color.white : Theme.accent)
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(isMine ? Color.white.opacity(0.22) : Theme.accentSoft)
                .clipShape(Capsule())
                .contentTransition(.numericText())
        }
        .pressable(scale: 0.85, dimming: 0, haptic: nil)
        .accessibilityLabel("Playback speed \(rateLabel)")
    }

    private var rateLabel: String {
        let rate = playback.rate
        return rate == rate.rounded() ? "\(Int(rate))×" : String(format: "%.1f×", rate)
    }

    // MARK: - Transcript

    /// Every live note offers its transcript — made on device if nobody has shared one yet —
    /// so the toggle is always there and the bubble never changes width as audio loads.
    private var showsTranscriptButton: Bool {
        !message.deleted
    }

    /// Dimmed until there is text to show, or audio this bubble can actually transcribe.
    /// The long-press hero has no transcriber — leave the control inert there unless
    /// a transcript already exists to unfold.
    private var transcriptButtonEnabled: Bool {
        if drawerContent != nil { return true }
        return onRequestTranscript != nil && message.voiceData != nil
    }

    private var isWorkingOnTranscript: Bool {
        isTranscribing || install.isActive(for: message.id)
    }

    private enum DrawerContent: Equatable {
        case text(String)
        case working
        case noSpeech
    }

    private var drawerContent: DrawerContent? {
        if let transcript { return .text(transcript) }
        if isWorkingOnTranscript { return .working }
        if foundNoSpeech { return .noSpeech }
        return nil
    }

    /// Folded by default; the toggle opens it, and it only shows once there is something in it.
    private var isTranscriptOpen: Bool {
        disclosure.isOpen(message.id) && drawerContent != nil
    }

    private var transcriptButton: some View {
        VoiceTranscriptButton(
            isOpen: isTranscriptOpen,
            isWorking: isWorkingOnTranscript,
            ink: isMine ? Color.white : Theme.accent,
            fill: isMine ? Color.white.opacity(0.2) : Theme.accent.opacity(0.12),
            isEnabled: transcriptButtonEnabled,
            action: toggleTranscript
        )
    }

    private var transcriptActionName: String {
        if isTranscriptOpen { return "Hide transcript" }
        return transcript == nil && !isWorkingOnTranscript ? "Transcribe" : "Show transcript"
    }

    private var transcriptDrawer: some View {
        ZStack(alignment: .topLeading) {
            switch drawerContent {
            case let .text(text):
                Text(text)
                    .font(.system(size: 15))
                    .foregroundStyle(isMine ? Color.white : Theme.textPrimary)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            case .working:
                transcriptProgress
                    .transition(.opacity)
            case .noSpeech:
                Text("No speech detected")
                    .font(.system(size: 13).italic())
                    .foregroundStyle(metaColor)
                    .transition(.opacity)
            case nil:
                EmptyView()
            }
        }
        .frame(width: transcriptWidth, alignment: .leading)
        .padding(.horizontal, Self.transcriptInset)
        .padding(.top, 8)
        .padding(.bottom, 1)
    }

    private var transcriptProgress: some View {
        let downloading = install.isActive(for: message.id) && install.phase == .downloading
        return VStack(alignment: .leading, spacing: 7) {
            Text(transcriptProgressLabel(downloading: downloading))
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(metaColor)
                .contentTransition(.numericText())
                .shimmering()
            if downloading, install.isDeterminate {
                ProgressView(value: max(install.fractionCompleted, 0.02))
                    .progressViewStyle(.linear)
                    .tint(isMine ? Color.white : Theme.accent)
            }
        }
        .animation(Motion.snappy, value: install.phase)
        .animation(Motion.snappy, value: install.fractionCompleted)
        .accessibilityLabel(transcriptProgressLabel(downloading: downloading))
    }

    private func transcriptProgressLabel(downloading: Bool) -> String {
        if downloading {
            let percent = Int((install.fractionCompleted * 100).rounded())
            if let name = install.languageName {
                return install.isDeterminate && percent > 0
                    ? "Downloading \(name)… \(percent)%"
                    : "Downloading \(name)…"
            }
            return install.isDeterminate && percent > 0
                ? "Downloading model… \(percent)%"
                : "Downloading model…"
        }
        return "Transcribing…"
    }

    /// Folds or unfolds the transcript, transcribing on device first when there is none yet.
    private func toggleTranscript() {
        let animation = Motion.respecting(reduceMotion, Motion.standard)
        if isTranscriptOpen {
            withAnimation(animation) { disclosure.setOpen(false, for: message.id) }
            return
        }
        let needsTranscript = drawerContent == nil
        // The long-press hero has no transcriber; with nothing to show there is nothing to open.
        if needsTranscript, onRequestTranscript == nil { return }
        withAnimation(animation) {
            disclosure.setOpen(true, for: message.id)
            if needsTranscript { isTranscribing = true }
        }
        guard needsTranscript, let onRequestTranscript else { return }
        Task {
            let result = await onRequestTranscript()
            withAnimation(animation) {
                isTranscribing = false
                if let result {
                    localTranscript = result
                    foundNoSpeech = result.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                } else {
                    // Failed, and the host already said why: fold back to a plain "→A".
                    disclosure.setOpen(false, for: message.id)
                }
            }
        }
    }

    // MARK: - Actions

    /// Reads the true length off the decoded file when the payload's duration is missing.
    /// Runs off the main actor — instantiating an AVAudioPlayer parses the container.
    private func resolveDurationIfNeeded() async {
        guard (message.voiceDurationMs ?? 0) < Self.minimumTrustedDurationMs,
              resolvedDurationMs == nil,
              let data = message.voiceData
        else { return }

        let measured = await Task.detached(priority: .utility) { () -> Int? in
            guard let probe = try? AVAudioPlayer(data: data), probe.duration > 0 else { return nil }
            return Int((probe.duration * 1000).rounded())
        }.value

        resolvedDurationMs = measured
    }

    private func togglePlayback() {
        guard let data = message.voiceData else {
            // Not decrypted yet — nudge the load and let the spinner explain the wait.
            onAppearLoad()
            return
        }
        Haptics.impact(.light)
        playback.toggle(id: message.id, data: data)
    }

    private var accessibilityLabel: String {
        var parts = [isMine ? "You" : "Them", "voice message"]
        parts.append(VoiceTimeFormat.duration(Double(durationMs) / 1000))
        if showsUnplayedDot { parts.append("unplayed") }
        if let transcript, !transcript.isEmpty { parts.append(transcript) }
        parts.append(time)
        return parts.joined(separator: ", ")
    }
}
