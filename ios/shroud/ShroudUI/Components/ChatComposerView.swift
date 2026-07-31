import SwiftUI

/// Bottom composer — maps to `Composer` in `Conversation` (`iOS-App.pen`).
/// Empty: attach + field + mic. Non-empty: attach + field + send.
struct ChatComposerView: View {
    @Binding var draft: String
    var isRecording: Bool = false
    var recordingSeconds: Int = 0
    var onAttach: () -> Void
    var onSend: () -> Void
    var onMicTap: () -> Void
    var onDiscardRecording: () -> Void = {}
    var onSendRecording: () -> Void = {}
    var onDraftChange: (String) -> Void = { _ in }

    @FocusState private var focused: Bool

    private var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(spacing: 0) {
            if isRecording {
                recordingPanel
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
        // Recording takes over the whole bar — slide the panels past each other.
        .animation(Motion.standard, value: isRecording)
    }

    private var composerRow: some View {
        HStack(spacing: 10) {
            Button(action: onAttach) {
                Image(systemName: "plus")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.82)
            .accessibilityLabel("Attach")

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

            // ZStack instead of if/else so the two controls cross-fade on the same
            // spot — swapping siblings in the HStack used to nudge the field's width.
            ZStack {
                if canSend {
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
                    Button(action: onMicTap) {
                        Image(systemName: "mic.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Theme.accent)
                            .frame(width: 34, height: 34)
                            .contentShape(Rectangle())
                    }
                    .pressable(scale: 0.82)
                    .accessibilityLabel("Voice message")
                    .transition(Motion.iconSwap)
                }
            }
            .frame(width: 34, height: 34)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        // Bouncy: the send button appearing is the "you can send now" moment.
        .animation(Motion.bouncy, value: canSend)
    }

    private var recordingPanel: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                // Slow blink is the universal "we are recording" cue.
                Circle()
                    .fill(Theme.danger)
                    .frame(width: 8, height: 8)
                    .phaseAnimator([1.0, 0.25]) { view, opacity in
                        view.opacity(opacity)
                    } animation: { _ in .easeInOut(duration: 0.6) }
                ChatWaveformBar(accent: true, animated: true)
                    .frame(height: 28)
                Text(timerLabel)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.danger)
                    .monospacedDigit()
                    // Seconds roll upward instead of flickering.
                    .contentTransition(.numericText(countsDown: false))
                    .animation(Motion.snappy, value: recordingSeconds)
            }
            .padding(.horizontal, 14)
            .frame(height: 44)
            .background(Theme.backgroundGrouped)
            .clipShape(Capsule())

            HStack(spacing: 10) {
                Button(action: onDiscardRecording) {
                    Image(systemName: "trash")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.danger)
                        .frame(width: 40, height: 40)
                        .background(Color(red: 0.988, green: 0.906, blue: 0.929))
                        .clipShape(Circle())
                }
                .pressable(scale: 0.88)
                .accessibilityLabel("Discard recording")

                HStack(spacing: 5) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 11))
                    Text("Recording locked — release to review")
                        .font(.system(size: 12, weight: .medium))
                }
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity)

                Button(action: onSendRecording) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(Color.white)
                        .frame(width: 44, height: 44)
                        .background(Theme.accent)
                        .clipShape(Circle())
                }
                .pressable(scale: 0.88, dimming: 0)
                .accessibilityLabel("Send recording")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var timerLabel: String {
        let m = recordingSeconds / 60
        let s = recordingSeconds % 60
        return String(format: "%d:%02d", m, s)
    }
}

/// Decorative waveform used in recording UI and voice bubbles.
///
/// Human: When `animated`, the bars breathe on a travelling sine so an in-progress recording
/// looks live. The motion is driven by `TimelineView`, so it costs nothing while off-screen
/// and never needs a timer to be torn down.
/// Agent: Pure view; no audio metering is read (levels are decorative, not sampled).
struct ChatWaveformBar: View {
    var accent: Bool = true
    /// Animates the bar heights — set while a recording is in flight.
    var animated: Bool = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let heights: [CGFloat] = [
        5, 15, 18, 19, 25, 23, 22, 23, 14, 14, 11, 11, 20, 22, 22, 26, 21, 20, 18, 9,
        12, 16, 16, 24, 23, 22, 24, 18, 16, 13, 8, 18, 21, 20, 26, 23, 21, 21, 13, 11,
    ]

    var body: some View {
        if animated, !reduceMotion {
            TimelineView(.animation(minimumInterval: 1 / 30)) { context in
                bars(phase: context.date.timeIntervalSinceReferenceDate * 3.2)
            }
        } else {
            bars(phase: nil)
        }
    }

    /// `phase` nil renders the static design heights; otherwise each bar rides a shifted sine.
    private func bars(phase: Double?) -> some View {
        HStack(alignment: .center, spacing: 2) {
            ForEach(Array(heights.enumerated()), id: \.offset) { index, h in
                RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                    .fill(accent ? Theme.accent : Theme.textSecondary.opacity(0.45))
                    .frame(width: 3, height: barHeight(base: h, index: index, phase: phase))
            }
        }
        .frame(maxWidth: .infinity)
        .clipped()
    }

    private func barHeight(base: CGFloat, index: Int, phase: Double?) -> CGFloat {
        let rest = base * 0.7
        guard let phase else { return rest }
        // Offsetting by index makes the pulse travel left→right instead of blinking in unison.
        let wave = sin(phase - Double(index) * 0.45)
        return max(3, rest * (0.55 + 0.45 * CGFloat(wave + 1) / 2 + 0.25))
    }
}

#Preview {
    VStack {
        Spacer()
        ChatComposerView(
            draft: .constant(""),
            onAttach: {},
            onSend: {},
            onMicTap: {}
        )
        ChatComposerView(
            draft: .constant("Hello"),
            onAttach: {},
            onSend: {},
            onMicTap: {}
        )
        ChatComposerView(
            draft: .constant(""),
            isRecording: true,
            recordingSeconds: 12,
            onAttach: {},
            onSend: {},
            onMicTap: {}
        )
    }
    .background(Theme.backgroundChat)
}
