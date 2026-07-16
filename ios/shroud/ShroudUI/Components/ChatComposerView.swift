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
            } else {
                composerRow
            }
        }
        .background {
            // Extends under the home indicator; content stays in the safe area.
            Theme.background
                .ignoresSafeArea(edges: .bottom)
        }
    }

    private var composerRow: some View {
        HStack(spacing: 10) {
            Button(action: onAttach) {
                Image(systemName: "plus")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
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
                    .foregroundStyle(Theme.textSecondary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(minHeight: 36)
            .background(Theme.backgroundGrouped)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

            if canSend {
                Button(action: onSend) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Color.white)
                        .frame(width: 34, height: 34)
                        .background(Theme.accent)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Send")
                .transition(.scale.combined(with: .opacity))
            } else {
                Button(action: onMicTap) {
                    Image(systemName: "mic.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Voice message")
                .transition(.scale.combined(with: .opacity))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .animation(.spring(response: 0.28, dampingFraction: 0.82), value: canSend)
    }

    private var recordingPanel: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                Circle()
                    .fill(Theme.danger)
                    .frame(width: 8, height: 8)
                ChatWaveformBar(accent: true)
                    .frame(height: 28)
                Text(timerLabel)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.danger)
                    .monospacedDigit()
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
                .buttonStyle(.plain)

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
                .buttonStyle(.plain)
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
struct ChatWaveformBar: View {
    var accent: Bool = true
    private let heights: [CGFloat] = [
        5, 15, 18, 19, 25, 23, 22, 23, 14, 14, 11, 11, 20, 22, 22, 26, 21, 20, 18, 9,
        12, 16, 16, 24, 23, 22, 24, 18, 16, 13, 8, 18, 21, 20, 26, 23, 21, 21, 13, 11,
    ]

    var body: some View {
        HStack(alignment: .center, spacing: 2) {
            ForEach(Array(heights.enumerated()), id: \.offset) { _, h in
                RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                    .fill(accent ? Theme.accent : Theme.textSecondary.opacity(0.45))
                    .frame(width: 3, height: h * 0.7)
            }
        }
        .frame(maxWidth: .infinity)
        .clipped()
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
