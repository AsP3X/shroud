import SwiftUI

/// Bottom composer — maps to `Composer` in `Conversation` (`iOS-App.pen`).
///
/// Three states:
/// - **Idle**: attach + field + mic (or send, once there is a draft).
/// - **Recording**: hold the mic. The field is replaced by a live waveform + timer, and a lock
///   affordance floats above the thumb. Slide left to cancel, up to lock, release to send.
/// - **Locked**: hands-free. Trash / waveform / send.
///
/// Human: The gesture lives here rather than in `ConversationView` so its thresholds sit next to
/// the geometry they act on; the host only receives the three outcomes (start / cancel / send).
/// Agent: READS `recorder` (@Observable) for live elapsed + levels; CALLS onRecordStart /
/// onRecordCancel / onRecordSend. Owns no audio state itself.
struct ChatComposerView: View {
    @Binding var draft: String
    /// Live recording state. Owned by the host so audio outlives composer view updates.
    var recorder: VoiceRecorder
    var onAttach: () -> Void
    var onSend: () -> Void
    /// Returns false when recording could not start (permission denied, already busy) so the
    /// gesture resets instead of showing a recording that is not happening.
    var onRecordStart: () async -> Bool
    var onRecordCancel: () -> Void
    var onRecordSend: () -> Void
    var onDraftChange: (String) -> Void = { _ in }
    /// Quote shown above the field while a reply is being written.
    var reply: ReplyQuoteContent? = nil
    /// Jump to the quoted message.
    var onTapReply: (() -> Void)? = nil
    /// Drop the reply; the draft stays.
    var onCancelReply: () -> Void = {}
    /// Bumped by the host to put the keyboard up — starting a reply focuses the field,
    /// exactly as tapping it would.
    var focusToken: Int = 0
    /// Link preview strip for the link in the draft. Takes the reply bar's place while it is
    /// up (Telegram does the same; the reply is still sent).
    var linkBar: ChatLinkBarState? = nil
    var linkShowsAboveText: Bool = false
    var linkCanToggleImageSize: Bool = false
    var linkUsesLargeImage: Bool = false
    var onToggleLinkAboveText: () -> Void = {}
    var onToggleLinkImageSize: () -> Void = {}
    /// ✕ / "Remove Preview": drop the preview, keep the draft.
    var onRemoveLinkPreview: () -> Void = {}

    @FocusState private var focused: Bool
    @State private var phase: VoiceRecordingPhase = .idle
    /// Guards the drag from firing again while `onRecordStart` is still awaiting.
    @State private var isStarting = false
    /// Set when the finger lifts before start() resolved — we discard whatever arrives.
    @State private var abandonedDuringStart = false

    private var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var cancelProgress: CGFloat {
        if case let .recording(cancel, _) = phase { return cancel }
        return 0
    }

    private var lockProgress: CGFloat {
        if case let .recording(_, lock) = phase { return lock }
        return 0
    }

    var body: some View {
        VStack(spacing: 0) {
            if let linkBar, !phase.isActive {
                ChatLinkBar(
                    state: linkBar,
                    showsAboveText: linkShowsAboveText,
                    canToggleImageSize: linkCanToggleImageSize,
                    usesLargeImage: linkUsesLargeImage,
                    onToggleAboveText: onToggleLinkAboveText,
                    onToggleImageSize: onToggleLinkImageSize,
                    onRemove: onRemoveLinkPreview
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else if let reply {
                // Stays up while recording: a voice note can answer a message too.
                ChatReplyBar(content: reply, onTapPreview: onTapReply, onCancel: onCancelReply)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if phase.isLocked {
                VoiceLockedBar(
                    elapsed: recorder.elapsed,
                    levels: recorder.liveLevels,
                    onDiscard: { finishRecording(send: false) },
                    onSend: { finishRecording(send: true) }
                )
                .padding(.vertical, 8)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                composerRow
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .background {
            // Extends under the home indicator; content stays in the safe area.
            Theme.background
                .ignoresSafeArea(edges: .bottom)
        }
        .animation(Motion.standard, value: phase.isLocked)
        // The bar pushes the thread up as it appears; spring it so nothing snaps.
        .animation(Motion.snappy, value: reply)
        .animation(Motion.snappy, value: linkBar)
        .onChange(of: focusToken) { _, _ in
            focused = true
        }
    }

    // MARK: - Idle / recording row

    private var composerRow: some View {
        HStack(spacing: 10) {
            if phase.isActive {
                VoiceRecordingBar(
                    elapsed: recorder.elapsed,
                    cancelProgress: cancelProgress
                )
                .transition(.opacity)
            } else {
                attachButton
                textField
                    .transition(.opacity)
            }

            trailingControl
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .animation(Motion.snappy, value: phase.isActive)
        // Bouncy: the send button appearing is the "you can send now" moment.
        .animation(Motion.bouncy, value: canSend)
    }

    private var attachButton: some View {
        Button(action: onAttach) {
            Image(systemName: "plus")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 34, height: 34)
                .contentShape(Rectangle())
        }
        .pressable(scale: 0.82)
        .accessibilityLabel("Attach")
    }

    private var textField: some View {
        HStack(spacing: 8) {
            TextField("Message", text: $draft, axis: .vertical)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1 ... 5)
                .focused($focused)
                .onChange(of: draft) { _, value in
                    onDraftChange(value)
                }

            Image(systemName: "face.smiling")
                .font(.system(size: 18, weight: .regular))
                .foregroundStyle(focused ? Theme.accent : Theme.textSecondary)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(minHeight: 36)
        .background(Theme.backgroundGrouped)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            // Focus ring fades in rather than snapping — signals the field is live.
            // Decorative only: it must never swallow taps meant for the text field.
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(Theme.accent.opacity(focused ? 0.35 : 0), lineWidth: 1)
                .allowsHitTesting(false)
        }
        // Field grows as the draft wraps; spring the whole row so nothing jumps.
        .animation(Motion.snappy, value: focused)
        .animation(Motion.snappy, value: draft)
    }

    /// Send button once there is a draft, otherwise the hold-to-record mic.
    private var trailingControl: some View {
        ZStack {
            if canSend, !phase.isActive {
                Button(action: onSend) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Color.white)
                        .frame(width: 34, height: 34)
                        .background(Theme.accent)
                        .clipShape(Circle())
                }
                .pressable(scale: 0.86, dimming: 0, haptic: nil)
                .accessibilityLabel("Send")
                .transition(Motion.iconSwap.combined(with: .offset(y: 6)))
            } else {
                micButton
                    .transition(Motion.iconSwap)
            }
        }
        // 44pt so the hit target survives the first points of drag travel; the glyphs
        // inside stay 34pt to match the design system's composer spec.
        .frame(width: 44, height: 44)
        // Lock affordance floats above the thumb while the finger is down.
        .overlay(alignment: .bottom) {
            if phase.isActive, !phase.isLocked {
                VoiceLockIndicator(progress: lockProgress)
                    .offset(y: -58)
                    .transition(.scale(scale: 0.6, anchor: .bottom).combined(with: .opacity))
            }
        }
        .animation(Motion.snappy, value: phase.isActive)
    }

    /// Grows and glows while recording; the halo tracks the current input level.
    private var micButton: some View {
        let level = CGFloat(recorder.liveLevels.last ?? 0)
        return Image(systemName: "mic.fill")
            .font(.system(size: 18, weight: .semibold))
            .foregroundStyle(phase.isActive ? Color.white : Theme.accent)
            .frame(width: 34, height: 34)
            .background {
                Circle()
                    .fill(Theme.accent)
                    .opacity(phase.isActive ? 1 : 0)
                    .scaleEffect(phase.isActive ? 1 : 0.4)
            }
            .background {
                // Level-reactive halo — visible proof the mic is hearing something.
                Circle()
                    .fill(Theme.accent.opacity(0.18))
                    .scaleEffect(phase.isActive ? 1.6 + level * 1.1 : 0.5)
                    .opacity(phase.isActive ? 1 : 0)
                    .animation(.easeOut(duration: 0.12), value: level)
            }
            .scaleEffect(phase.isActive ? 1.25 : 1)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .gesture(recordGesture)
            .animation(Motion.snappy, value: phase.isActive)
            .accessibilityLabel("Hold to record a voice message")
            .accessibilityAddTraits(.startsMediaSession)
    }

    // MARK: - Gesture

    private var recordGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                switch phase {
                case .idle:
                    beginRecording()
                case .recording:
                    updateDrag(translation: value.translation)
                case .locked:
                    // The finger is irrelevant once locked; explicit buttons take over.
                    break
                }
            }
            .onEnded { _ in
                switch phase {
                case .idle:
                    if isStarting { abandonedDuringStart = true }
                case .recording:
                    finishRecording(send: cancelProgress < 1)
                case .locked:
                    break
                }
            }
    }

    private func beginRecording() {
        guard !isStarting else { return }
        isStarting = true
        abandonedDuringStart = false

        Task {
            let started = await onRecordStart()
            isStarting = false
            guard started else {
                phase = .idle
                return
            }
            guard !abandonedDuringStart else {
                // Released during the permission / session round-trip: a tap, not a message.
                abandonedDuringStart = false
                onRecordCancel()
                phase = .idle
                return
            }
            withAnimation(Motion.snappy) {
                phase = .recording(cancelProgress: 0, lockProgress: 0)
            }
        }
    }

    private func updateDrag(translation: CGSize) {
        let cancel = min(1, max(0, -translation.width / VoiceRecordingThresholds.cancel))
        let lock = min(1, max(0, -translation.height / VoiceRecordingThresholds.lock))

        if lock >= 1 {
            Haptics.notification(.success)
            withAnimation(Motion.standard) { phase = .locked }
            return
        }
        if cancel >= 1 {
            // Telegram cancels the moment you cross, without waiting for the release.
            finishRecording(send: false)
            return
        }
        phase = .recording(cancelProgress: cancel, lockProgress: lock)
    }

    private func finishRecording(send: Bool) {
        guard phase.isActive else { return }
        withAnimation(Motion.standard) { phase = .idle }
        if send {
            onRecordSend()
        } else {
            Haptics.impact(.rigid)
            onRecordCancel()
        }
    }
}

#Preview {
    VStack {
        Spacer()
        ChatComposerView(
            draft: .constant(""),
            recorder: VoiceRecorder(),
            onAttach: {},
            onSend: {},
            onRecordStart: { true },
            onRecordCancel: {},
            onRecordSend: {}
        )
        ChatComposerView(
            draft: .constant("Hello"),
            recorder: VoiceRecorder(),
            onAttach: {},
            onSend: {},
            onRecordStart: { true },
            onRecordCancel: {},
            onRecordSend: {}
        )
    }
    .background(Theme.backgroundChat)
}
