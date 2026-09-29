import AVFoundation
import SwiftUI

/// Voice message bubble — Telegram layout: play/pause disc, scrubbable waveform, a "→A"
/// transcript toggle, elapsed/duration readout, unplayed dot, and the transcript folded inside.
///
/// Human: The bubble owns no player. Everything transport-related goes through
/// `VoicePlaybackCoordinator`, so scrolling a playing note off-screen does not kill it and
/// starting a second note stops the first — both Telegram behaviours. The transcript starts
/// folded; the toggle unfolds it inside the bubble, transcribing on device first when needed.
/// Agent: READS coordinator state + `message.voiceData`; CALLS onAppearLoad to trigger decrypt
/// (again from the disc when an attempt ended without the audio).
/// Scrubbing writes only to the coordinator; the fold lives in `VoiceTranscriptDisclosure`.
struct VoiceMessageBubble: View {
    let message: MessagingController.ChatMessage
    let time: String
    /// Fetches and decrypts the audio, returning once that attempt is over, loaded or not.
    /// Nil (the long-press hero) keeps the spinner up and offers no retry.
    var onAppearLoad: (() async -> Void)? = nil
    var onRequestTranscript: (() async -> String?)? = nil
    /// One of the newest voice notes with nothing newer under it: a short transcript unfolds unasked.
    var inTranscriptTail = false
    /// Lets a fresh note land before it unfolds, and streams text revealed in front of the reader.
    /// Off for the long-press hero, which has to match the list bubble frame for frame.
    var revealsArrival = true
    /// Quote header for a reply; nil for an ordinary note.
    var reply: ReplyQuoteContent? = nil
    /// Jump to the quoted message.
    var onReplyTap: (() -> Void)? = nil
    /// Reaction chips, in a row under the waveform; the time moves to the end of that row.
    var reactions: [ReactionChipContent] = []
    var onReactionTap: ((String) -> Void)? = nil

    @Environment(\.chatRowWidth) private var chatRowWidth
    /// Carries the time and ticks between the footer, the transcript and the reaction row.
    @Namespace private var metaSlot

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
    /// Pinned on appear: whether this bubble is still landing (see `landing`).
    @State private var isLanding: Bool?
    /// Text revealed once the bubble is on screen streams in; text there from the start doesn't.
    @State private var hasAppeared = false
    /// The last fetch of the audio ended without it, so the disc offers a retry, not a spinner.
    @State private var loadFailed = false

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

    /// The audio is on the server and not on this device yet.
    private var needsAudio: Bool {
        message.voiceData == nil && message.mediaObjectId != nil && !message.deleted
    }

    /// Audio is still being fetched/decrypted — the disc shows a spinner instead of play.
    private var isLoading: Bool {
        needsAudio && !loadFailed
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

    /// Notes this short draw the narrowest waveform; notes this long (and longer) the widest.
    /// Telegram ramps its voice bubbles by duration the same way.
    private static let shortNoteSeconds = 2.0
    private static let longNoteSeconds = 14.0
    /// Narrowest waveform. Below this the duration, speed chip and unplayed dot crowd the
    /// footer, and a short transcript folds into a column too thin to read.
    private static let minWaveformWidth: CGFloat = 160
    /// 3pt bar + 2pt gap, matching `VoiceWaveformView`'s defaults.
    private static let barPitch: CGFloat = 5

    /// Widest the bubble may draw — the same edge a long text message stops at.
    private var maxBubbleWidth: CGFloat {
        // A host that hasn't measured yet reports 0; fall back rather than collapse the bubble.
        let row = chatRowWidth > 0 ? chatRowWidth : MessageBubbleMetrics.fallbackRowWidth
        return min(row, max(MessageBubbleMetrics.minBubbleWidth, row - MessageBubbleMetrics.oppositeGutter))
    }

    /// Everything in the waveform row besides the waveform: the bubble's padding, the play
    /// disc and the transcript toggle.
    private static var waveformChrome: CGFloat {
        horizontalPadding * 2 + playButtonSize + playButtonGap + transcriptButtonGap + VoiceTranscriptButton.size
    }

    /// Bubble width tracks duration, so a 2-second note is visibly shorter than a 40-second
    /// one — the single strongest "this is a voice message" cue. A long note runs to the width
    /// of a long text bubble, which is also where its transcript wraps: wide enough to read
    /// comfortably, never the whole thread. Whole bars, so the waveform ends on one.
    private var waveformWidth: CGFloat {
        let seconds = Double(durationMs) / 1000
        let ramp = (seconds - Self.shortNoteSeconds) / (Self.longNoteSeconds - Self.shortNoteSeconds)
        let ceiling = max(Self.minWaveformWidth, maxBubbleWidth - Self.waveformChrome)
        let width = Self.minWaveformWidth + (ceiling - Self.minWaveformWidth) * min(1, max(0, ramp))
        return (width / Self.barPitch).rounded(.down) * Self.barPitch
    }

    private var barCount: Int {
        Int(waveformWidth / Self.barPitch)
    }

    private var contentWidth: CGFloat {
        waveformWidth
    }

    /// Waveform plus the transcript toggle beside it; the footer spans both.
    private var columnWidth: CGFloat {
        contentWidth + (showsTranscriptButton ? Self.transcriptButtonGap + VoiceTranscriptButton.size : 0)
    }

    private static let playButtonSize: CGFloat = 38
    private static let playButtonGap: CGFloat = 10
    private static let transcriptButtonGap: CGFloat = 8
    private static let horizontalPadding: CGFloat = 10
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
        // Covers the automatic fold too: a new message below, or a fresh note done landing.
        .animation(Motion.respecting(reduceMotion, Motion.standard), value: isTranscriptOpen)
        .onAppear {
            loadAudio()
            hasAppeared = true
        }
        .task {
            // A note that arrives while the thread is open lands folded, then unfolds.
            // `defer` so scrolling away mid-wait still finishes landing — otherwise the
            // note would stay folded until the view is recreated.
            guard isLanding == nil else { return }
            let shouldLand = landing
            isLanding = shouldLand
            guard shouldLand else { return }
            defer { isLanding = false }
            if reduceMotion { return }
            try? await Task.sleep(for: .milliseconds(VoiceTranscriptDisclosure.landingDelayMs))
        }
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
        .reactionAccessibilityActions(message.deleted ? [] : reactions, onTap: onReactionTap)
        .accessibilityAddTraits(.isButton)
        // The play disc is hidden, so without a default action a double tap would land on
        // whatever child SwiftUI merged in (the quote) or seek the waveform.
        .accessibilityAction { togglePlayback() }
        .accessibilityAction(named: playActionName) { togglePlayback() }
        .accessibilityActions {
            if showsTranscriptButton, transcriptButtonEnabled {
                Button(transcriptActionName) { toggleTranscript() }
            }
            // The default action above replaces the quote's own tap; the speed chip is hidden.
            if reply != nil, let onReplyTap {
                Button("Show replied message", action: onReplyTap)
            }
            if playback.isActive(message.id) {
                Button("Playback speed \(rateLabel)") { playback.cycleRate() }
            }
        }
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let reply {
                ReplyQuoteView(
                    content: reply,
                    style: isMine ? .outgoing : .incoming,
                    onTap: onReplyTap
                )
                // Matches the waveform row's width so the header never widens the bubble.
                .frame(width: Self.playButtonSize + Self.playButtonGap + columnWidth)
                .padding(.bottom, 5)
            }

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

            if showsReactions {
                // Chips flow left to right; the time and ticks close the last row (Telegram).
                ReactionFooter(
                    chips: reactions,
                    onOutgoingBubble: isMine,
                    onTap: onReactionTap,
                    chipsAccessible: false
                ) {
                    metaRow
                }
                .frame(width: Self.playButtonSize + Self.playButtonGap + columnWidth, alignment: .leading)
                .padding(.top, 7)
            }
        }
        // The unfolded transcript's time and ticks. Outside the drawer, so they slide down from
        // the footer on the bubble's growing bottom edge instead of fading in with the text.
        .overlay(alignment: .bottomTrailing) {
            if metaInTranscript, drawerContent != nil {
                metaRow
                    .padding(.trailing, Self.transcriptInset)
                    .transition(.identity)
            }
        }
        .padding(.horizontal, Self.horizontalPadding)
        .padding(.vertical, 8)
        .background(isMine ? Theme.bubbleOutgoing : Theme.bubbleIncoming)
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
                } else if needsAudio {
                    // The last fetch came back without the audio; a tap tries again.
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Color.white)
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
        // Live in the retry state: the tap goes through `togglePlayback`'s reload branch.
        .disabled(isLoading || (message.voiceData == nil && !needsAudio))
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
            // Only the loaded note scrubs (Telegram). A zero-distance drag on every note would
            // take swipe-to-reply and the thread's scroll from most of each bubble; an inactive
            // note leaves those drags alone and starts from its play disc.
            .gesture(scrubGesture(width: geo.size.width), isEnabled: playback.isActive(message.id))
        }
        .frame(height: 26)
    }

    /// Drag anywhere on the loaded note's waveform to seek; the playhead follows the finger live.
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

            if metaInFooter {
                metaRow
            }
        }
        .animation(Motion.snappy, value: playback.isActive(message.id))
        .animation(Motion.snappy, value: showsUnplayedDot)
    }

    // MARK: - Meta (time + ticks)

    /// The time and ticks are always the last thing in the bubble, as in Telegram: in the
    /// waveform footer of a bare note, at the end of the transcript's last line once it is
    /// unfolded, and closing the reaction row of a reacted note. Nothing may sit under them.
    private var showsReactions: Bool {
        !reactions.isEmpty && !message.deleted
    }

    private var metaInFooter: Bool {
        !isTranscriptOpen && !showsReactions
    }

    private var metaInTranscript: Bool {
        isTranscriptOpen && !showsReactions
    }

    private var metaRow: some View {
        HStack(spacing: MessageBubbleMetrics.metaSpacing) {
            Text(time)
                .font(Self.metaFont)
                .foregroundStyle(metaColor)
                .fixedSize()

            if isMine {
                MessageReceiptIcon(
                    receipt: message.receipt,
                    metaColor: Color.white.opacity(0.7),
                    readColor: Color.white,
                    failedColor: Color.white
                )
            }
        }
        // One view moving between three places, so a fold or unfold slides it rather than
        // cutting it from one row into another.
        .matchedGeometryEffect(id: "meta", in: metaSlot)
    }

    /// Same font `MessageBubbleMetrics.metaReservation` measures with, so the room kept at
    /// the end of a transcript's last line is exactly the meta's width.
    private static let metaFont = Font.system(size: MessageBubbleMetrics.metaFontSize).monospacedDigit()

    /// Telegram's 1× / 1.5× / 2× chip, shown only while this note is loaded.
    private var speedChip: some View {
        Button {
            Haptics.impact(.light)
            playback.cycleRate()
        } label: {
            Text(rateLabel)
                .font(.system(size: 10, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(isMine ? Color.white : Theme.accentText)
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(isMine ? Color.white.opacity(0.22) : Theme.accentSoft)
                .clipShape(Capsule())
                // A bigger target than the 16 pt chip, without moving the footer; it wins over
                // the waveform above where the two overlap.
                .contentShape(Rectangle().inset(by: -8))
                .contentTransition(.numericText())
        }
        .pressable(scale: 0.85, dimming: 0, haptic: nil)
        // Reached through the bubble's "Playback speed" action instead, like the transcript
        // toggle; merged in, it would only compete with the bubble's default action.
        .accessibilityHidden(true)
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

    /// The reader's own choice wins; otherwise the newest notes unfold by themselves. It only
    /// shows once there is something in it.
    private var isTranscriptOpen: Bool {
        guard drawerContent != nil else { return false }
        return disclosure.choice(for: message.id) ?? opensUnasked
    }

    /// At the bottom of the thread a short transcript unfolds once the bubble has landed.
    private var opensUnasked: Bool {
        inTranscriptTail && !landing && VoiceTranscriptDisclosure.opensUnasked(
            transcript: transcript,
            durationMs: durationMs,
            isWorking: isWorkingOnTranscript
        )
    }

    /// Fresh off the network or the recorder — not history, and not the server's copy of a bubble
    /// that was already on screen.
    private var landing: Bool {
        isLanding ?? (
            revealsArrival
                && !disclosure.wasHandedOff(message.id)
                && Date().timeIntervalSince(message.createdAt) < VoiceTranscriptDisclosure.arrivalWindow
        )
    }

    private var transcriptButton: some View {
        VoiceTranscriptButton(
            isOpen: isTranscriptOpen,
            isWorking: isWorkingOnTranscript,
            ink: isMine ? Color.white : Theme.accentText,
            fill: isMine ? Color.white.opacity(0.2) : Theme.accent.opacity(0.12),
            isEnabled: transcriptButtonEnabled,
            action: toggleTranscript
        )
    }

    private var transcriptActionName: String {
        if isTranscriptOpen { return "Hide transcript" }
        return transcript == nil && !isWorkingOnTranscript ? "Transcribe" : "Show transcript"
    }

    /// With no reaction row under it, the time and ticks (the bubble's overlay) sit at the end
    /// of the drawer's last line like a text bubble's, and the content keeps clear of them.
    private var transcriptDrawer: some View {
        ZStack(alignment: .topLeading) {
            switch drawerContent {
            case let .text(text):
                VoiceTranscriptText(
                    text: text,
                    color: isMine ? Color.white : Theme.textPrimary,
                    streams: hasAppeared && revealsArrival && !reduceMotion,
                    trailingReservation: metaInTranscript ? metaReservation : nil
                )
                .transition(.opacity)
            case .working:
                transcriptProgress
                    .padding(.trailing, metaClearance)
                    .transition(.opacity)
            case .noSpeech:
                Text("No speech detected")
                    .font(.system(size: 13).italic())
                    .foregroundStyle(metaColor)
                    .padding(.trailing, metaClearance)
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

    /// Invisible tail on the transcript that keeps its last line clear of the time and ticks.
    private var metaReservation: String {
        MessageBubbleMetrics.metaReservation(time: time, showsReceipt: isMine)
    }

    /// Room kept beside the progress or "no speech" line for the time and ticks.
    private var metaClearance: CGFloat {
        guard metaInTranscript else { return 0 }
        return MessageBubbleMetrics.metaWidth(time: time, showsReceipt: isMine) + MessageBubbleMetrics.metaGap
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
            // Not decrypted yet, or the last fetch failed: load again and let the spinner
            // explain the wait.
            loadAudio()
            return
        }
        Haptics.impact(.light)
        playback.toggle(id: message.id, data: data)
    }

    /// Asks the host for the audio. The host returns once that attempt is over; if the audio
    /// still isn't here then, the disc turns into a retry instead of spinning forever.
    private func loadAudio() {
        guard let onAppearLoad else { return }
        loadFailed = false
        Task {
            await onAppearLoad()
            // `message` is a stale copy after the await, so don't check it here: a successful
            // load set `voiceData` in the same main-actor turn, `needsAudio` is false, and the
            // flag goes unread.
            loadFailed = true
        }
    }

    private var playActionName: String {
        if needsAudio, loadFailed { return "Retry download" }
        return isPlaying ? "Pause" : "Play"
    }

    private var accessibilityLabel: String {
        var parts = [isMine ? "You" : "Them"]
        // The quote's own label is replaced by this one, so it is spoken here.
        if let reply {
            parts.append("Reply to \(reply.author): \(reply.text)")
        }
        parts.append("voice message")
        parts.append(VoiceTimeFormat.duration(Double(durationMs) / 1000))
        if showsUnplayedDot { parts.append("unplayed") }
        if needsAudio, loadFailed { parts.append("Download failed") }
        if let transcript, !transcript.isEmpty { parts.append(transcript) }
        if !message.deleted, let reactionsSummary = reactions.spokenSummary {
            parts.append(reactionsSummary)
        }
        parts.append(time)
        if isMine { parts.append(message.receipt.spokenLabel) }
        return parts.joined(separator: ", ")
    }
}
