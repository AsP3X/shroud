import SwiftUI

/// Bottom composer — maps to `Composer` in `Conversation` (`iOS-App.pen`).
///
/// Three states:
/// - **Idle**: attach + field + mic (or send, once there is a draft).
/// - **Recording**: hold the mic. The field is replaced by a live timer, and a lock
///   affordance floats above the thumb. Slide left to cancel, up to lock, release to send.
/// - **Locked**: hands-free. Trash / waveform / send.
///
/// Human: Liquid Glass throughout — the attach circle, the field capsule and the mic / send
/// circle each carry their own glass and float over the thread, which the host fades under
/// them with `glassBottomBar`. A reply or link strip rides above the row as one more glass
/// panel. The gesture lives here rather than in `ConversationView` so its thresholds sit next
/// to the geometry they act on; the host only receives the three outcomes (start / cancel / send).
/// Agent: READS `recorder` (@Observable) for live elapsed + levels; CALLS onRecordStart /
/// onRecordCancel / onRecordSend. Owns no audio state itself. Every glass shape sits in one
/// `GlassEffectContainer`, so the mic ⇄ send swap morphs instead of cross-fading.
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

    /// Composer control diameter — lighter than the 44 pt bar controls above the thread.
    static let controlSize: CGFloat = 40
    /// Corner radius of the field: a capsule at one line, a rounded rect as it grows.
    static let fieldRadius: CGFloat = 20

    @FocusState private var focused: Bool
    @State private var phase: VoiceRecordingPhase = .idle
    /// Guards the drag from firing again while `onRecordStart` is still awaiting.
    @State private var isStarting = false
    /// Set when the finger lifts before start() resolved — we discard whatever arrives.
    @State private var abandonedDuringStart = false
    /// True while a finger is on the mic. Resets on release *and* on cancellation, which
    /// `onEnded` never sees (a system alert such as the first mic-permission prompt).
    @GestureState private var micHeld = false
    /// One start attempt per touch: a failed start, or a slide-to-cancel, must not restart
    /// while the finger is still down.
    @State private var attemptedThisTouch = false
    @Namespace private var glassNamespace

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
        GlassEffectContainer(spacing: 8) {
            VStack(spacing: 8) {
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
                    .modifier(ComposerStripGlass())
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                } else if let reply {
                    // Stays up while recording: a voice note can answer a message too.
                    ChatReplyBar(content: reply, onTapPreview: onTapReply, onCancel: onCancelReply)
                        .modifier(ComposerStripGlass())
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                if phase.isLocked {
                    VoiceLockedBar(
                        elapsed: recorder.elapsed,
                        levels: recorder.liveLevels,
                        onDiscard: { finishRecording(send: false) },
                        onSend: { finishRecording(send: true) }
                    )
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                } else {
                    composerRow
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            // Lock affordance floats above the thumb while the finger is down — and above the
            // reply strip, which stays up while recording. On the stack rather than the mic so
            // it clears whatever sits on top; still inside the container so the glass dematerialises.
            .overlay(alignment: .topTrailing) {
                ZStack {
                    if phase.isActive, !phase.isLocked {
                        VoiceLockIndicator(progress: lockProgress)
                            .padding(.trailing, 4) // centred over the 44 pt trailing slot
                            .offset(y: -74) // bottom 14 pt above the top-most row or strip
                            .transition(.scale(scale: 0.6, anchor: .bottom).combined(with: .opacity))
                    }
                }
                .animation(Motion.snappy, value: phase.isActive)
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 4)
        .padding(.bottom, 8)
        .animation(Motion.standard, value: phase.isLocked)
        // The strip pushes the thread up as it appears; spring it so nothing snaps.
        .animation(Motion.snappy, value: reply)
        .animation(Motion.snappy, value: linkBar)
        .onChange(of: focusToken) { _, _ in
            focused = true
        }
        // The host can stop the take itself (a call taking the mic); drop back to idle with it.
        .onChange(of: recorder.isRecording) { _, recording in
            guard !recording, phase.isActive else { return }
            withAnimation(Motion.standard) { phase = .idle }
        }
        // The finger left the mic — released, or the touch was cancelled.
        .onChange(of: micHeld) { _, held in
            guard !held else { return }
            attemptedThisTouch = false
            if isStarting {
                abandonedDuringStart = true
            } else if case .recording = phase {
                finishRecording(send: cancelProgress < 1)
            }
        }
    }

    // MARK: - Idle / recording row

    private var composerRow: some View {
        HStack(spacing: 8) {
            if phase.isActive {
                VoiceRecordingBar(
                    elapsed: recorder.elapsed,
                    cancelProgress: cancelProgress
                )
                .padding(.horizontal, 14)
                .frame(minHeight: Self.controlSize)
                .glassEffect(.regular, in: .capsule)
                .glassEffectID("field", in: glassNamespace)
                .transition(.opacity)
            } else {
                attachButton
                textField
                    .transition(.opacity)
            }

            trailingControl
        }
        .animation(Motion.snappy, value: phase.isActive)
        // Bouncy: the send button appearing is the "you can send now" moment.
        .animation(Motion.bouncy, value: canSend)
    }

    private var attachButton: some View {
        Button(action: onAttach) {
            Image(systemName: "plus")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: Self.controlSize, height: Self.controlSize)
                // 44 pt hit target around the 40 pt glass.
                .contentShape(Circle().inset(by: -2))
        }
        // Interactive glass swells under the finger; the style only adds the haptic tick.
        .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0))
        .glassEffect(.regular.interactive(), in: .circle)
        .glassEffectID("attach", in: glassNamespace)
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
                .allowsHitTesting(false)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(minHeight: Self.controlSize)
        // Taps on the capsule's padding or the smiley focus the field, like the system composer.
        // Behind the text, so taps on the text itself still place the cursor.
        .background {
            Color.clear
                .contentShape(.rect(cornerRadius: Self.fieldRadius))
                .onTapGesture { focused = true }
        }
        // A whisper of accent in the glass while the field is live, instead of a stroke.
        .glassEffect(
            focused ? .regular.tint(Theme.accent.opacity(0.12)) : .regular,
            in: .rect(cornerRadius: Self.fieldRadius)
        )
        .glassEffectID("field", in: glassNamespace)
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
                        .frame(width: Self.controlSize, height: Self.controlSize)
                        // Fills the 44 pt slot, like the mic it replaces.
                        .contentShape(Circle().inset(by: -2))
                }
                .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0, haptic: nil))
                .glassEffect(.regular.tint(Theme.accent).interactive(), in: .circle)
                .glassEffectID("trailing", in: glassNamespace)
                .accessibilityLabel("Send")
                .transition(Motion.iconSwap.combined(with: .offset(y: 6)))
            } else {
                micButton
                    .transition(Motion.iconSwap)
            }
        }
        // 44pt so the hit target survives the first points of drag travel; the glass
        // inside stays `controlSize` to match the other composer controls.
        .frame(width: 44, height: 44)
        .animation(Motion.snappy, value: phase.isActive)
    }

    /// Grows and fills with accent while recording; the halo tracks the current input level.
    private var micButton: some View {
        let level = CGFloat(recorder.liveLevels.last ?? 0)
        return Image(systemName: "mic.fill")
            .font(.system(size: 18, weight: .semibold))
            .foregroundStyle(phase.isActive ? Color.white : Theme.accent)
            .frame(width: Self.controlSize, height: Self.controlSize)
            .contentShape(Circle())
            // Clear glass at rest, accent-tinted glass while the mic is live.
            .glassEffect(
                phase.isActive ? .regular.tint(Theme.accent).interactive() : .regular.interactive(),
                in: .circle
            )
            .glassEffectID("trailing", in: glassNamespace)
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
            .accessibilityLabel("Record voice message")
            .accessibilityHint("Starts a hands-free recording. Send or discard it when you're done.")
            .accessibilityAddTraits([.isButton, .startsMediaSession])
            .accessibilityAction { startLockedRecording() }
    }

    // MARK: - Gesture

    /// Human: Global space, because the composer itself moves under a still finger — the take
    /// swaps the focused field out, the keyboard drops, and the bar slides down ~300 pt, which
    /// in local space reads as a slide up to lock.
    /// Agent: Release is handled by `.onChange(of: micHeld)` in `body`, not `onEnded`, so a
    /// cancelled touch lands there too.
    private var recordGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .global)
            .updating($micHeld) { _, held, _ in held = true }
            .onChanged { value in
                switch phase {
                case .idle:
                    guard !attemptedThisTouch else { return }
                    attemptedThisTouch = true
                    beginRecording()
                case .recording:
                    updateDrag(translation: value.translation)
                case .locked:
                    // The finger is irrelevant once locked; explicit buttons take over.
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

    /// Assistive tech can't hold and slide, so its activation goes straight to the hands-free
    /// state; the locked bar's labelled Send and Discard finish the take.
    private func startLockedRecording() {
        guard phase == .idle, !isStarting else { return }
        isStarting = true
        abandonedDuringStart = false

        Task {
            let started = await onRecordStart()
            isStarting = false
            guard started else { return }
            withAnimation(Motion.standard) { phase = .locked }
        }
    }

    private func updateDrag(translation: CGSize) {
        let cancel = min(1, max(0, -translation.width / VoiceRecordingThresholds.cancel))
        let lock = min(1, max(0, -translation.height / VoiceRecordingThresholds.lock))

        if lock >= 1 {
            Haptics.notification(.success)
            // The mic leaves the tree with this touch still down; the next touch starts fresh.
            attemptedThisTouch = false
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

/// The reply / link strip as one glass panel above the field.
private struct ComposerStripGlass: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.vertical, 2)
            .glassEffect(.regular, in: .rect(cornerRadius: ChatComposerView.fieldRadius))
    }
}

#Preview {
    ZStack {
        Theme.backgroundChat.ignoresSafeArea()
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
                onRecordSend: {},
                reply: ReplyQuoteContent(
                    author: "Jane Cooper",
                    text: "Are we still on for tomorrow?",
                    isStandIn: false,
                    thumbnail: nil,
                    symbolName: nil
                )
            )
        }
    }
}
