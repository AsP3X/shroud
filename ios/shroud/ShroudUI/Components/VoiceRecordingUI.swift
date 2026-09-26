import SwiftUI

/// Where a hold-to-record gesture currently sits.
///
/// Human: Mirrors Telegram exactly — hold the mic to record, slide left past the cancel
/// threshold to throw it away, slide up past the lock threshold to go hands-free. Releasing
/// while `.recording` sends; once `.locked` the finger is free and explicit buttons take over.
enum VoiceRecordingPhase: Equatable {
    case idle
    /// Finger still down. `cancelProgress`/`lockProgress` are 0…1 toward each threshold.
    case recording(cancelProgress: CGFloat, lockProgress: CGFloat)
    case locked

    var isActive: Bool { self != .idle }

    var isLocked: Bool { self == .locked }
}

/// Drag distances that trigger each outcome.
enum VoiceRecordingThresholds {
    static let cancel: CGFloat = 110
    static let lock: CGFloat = 76
}

// MARK: - Recording bar (finger still down)

/// The bar that replaces the composer while the finger is down: blinking dot, running timer,
/// and the "slide to cancel" hint that tracks the thumb.
///
/// Human: No waveform here on purpose — with the timer and the hint there is only ~100pt left,
/// and Telegram doesn't show one in this state either. The mic button's level-reactive halo
/// carries the "we can hear you" feedback until the recording is locked and the bar has room.
struct VoiceRecordingBar: View {
    let elapsed: TimeInterval
    /// 0…1 toward the cancel threshold; the hint follows the finger and the bar dims.
    let cancelProgress: CGFloat

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(Theme.danger)
                .frame(width: 9, height: 9)
                .modifier(RecordingBlink(active: !reduceMotion))

            Text(VoiceTimeFormat.recording(elapsed))
                .font(.system(size: 15, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(Theme.textPrimary)
                // Fixed width so the centisecond digits don't shuffle the row.
                .frame(width: 66, alignment: .leading)

            Spacer(minLength: 4)

            slideToCancelHint

            Spacer(minLength: 4)
        }
        .frame(height: 36)
        .frame(maxWidth: .infinity)
        // The whole bar recedes as the finger approaches the cancel threshold.
        .opacity(1 - Double(cancelProgress) * 0.45)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "Recording, \(Int(elapsed)) seconds. Release to send, slide left to cancel."
        )
    }

    /// Slides left with the finger and fades out as cancel gets closer.
    private var slideToCancelHint: some View {
        HStack(spacing: 4) {
            Image(systemName: "chevron.left")
                .font(.system(size: 11, weight: .semibold))
            Text("Slide to cancel")
                .font(.system(size: 13))
                .fixedSize()
        }
        .foregroundStyle(Theme.textSecondary)
        .opacity(1 - Double(cancelProgress))
        .offset(x: -cancelProgress * 44)
    }
}

/// Slow blink shared by the recording bar and the locked bar.
private struct RecordingBlink: ViewModifier {
    let active: Bool

    func body(content: Content) -> some View {
        if active {
            content.phaseAnimator([1.0, 0.2]) { view, opacity in
                view.opacity(opacity)
            } animation: { _ in .easeInOut(duration: 0.6) }
        } else {
            content
        }
    }
}

// MARK: - Locked bar (hands-free)

/// Post-lock controls: discard, keep-recording readout, send.
///
/// Human: Three glass shapes in the composer's container — a plain circle for the bin, a
/// capsule for the readout, an accent-tinted circle for send — so the row reads as the
/// idle composer with the field swapped for the recording.
/// Agent: Pure presentation; the host owns the recorder and the two outcomes.
struct VoiceLockedBar: View {
    let elapsed: TimeInterval
    let levels: [Float]
    let onDiscard: () -> Void
    let onSend: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onDiscard) {
                Image(systemName: "trash")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.danger)
                    .frame(width: ChatComposerView.controlSize, height: ChatComposerView.controlSize)
                    .contentShape(Circle())
            }
            .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0, haptic: .medium))
            .glassEffect(.regular.interactive(), in: .circle)
            .accessibilityLabel("Discard recording")

            HStack(spacing: 10) {
                Circle()
                    .fill(Theme.danger)
                    .frame(width: 9, height: 9)
                    .modifier(RecordingBlink(active: !reduceMotion))

                Text(VoiceTimeFormat.recording(elapsed))
                    .font(.system(size: 15, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(Theme.textPrimary)
                    .frame(width: 62, alignment: .leading)

                VoiceWaveformView(
                    samples: levels,
                    progress: 0,
                    playedColor: Theme.accent,
                    remainingColor: Theme.accent.opacity(0.55)
                )
                .frame(height: 24)
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 14)
            .frame(height: ChatComposerView.controlSize)
            .glassEffect(.regular, in: .capsule)

            Button(action: onSend) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Color.white)
                    .frame(width: ChatComposerView.controlSize, height: ChatComposerView.controlSize)
                    .contentShape(Circle())
            }
            .buttonStyle(PressableButtonStyle(scale: 1, dimming: 0, haptic: .medium))
            .glassEffect(.regular.tint(Theme.accent).interactive(), in: .circle)
            .accessibilityLabel("Send recording")
        }
        .accessibilityElement(children: .contain)
    }
}

// MARK: - Lock affordance

/// The pill that floats above the mic while recording, showing how close the finger is to
/// locking. Fills and lifts as `progress` climbs; snaps shut once locked.
struct VoiceLockIndicator: View {
    /// 0…1 toward the lock threshold.
    let progress: CGFloat

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: progress >= 1 ? "lock.fill" : "lock.open.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(progress >= 1 ? Color.white : Theme.accent)
                .contentTransition(.symbolEffect(.replace))

            Image(systemName: "chevron.up")
                .font(.system(size: 9, weight: .bold))
                .foregroundStyle(Theme.textSecondary)
                .opacity(1 - Double(progress))
        }
        .frame(width: 36, height: 60)
        .background {
            // Fill rises with the finger — a progress bar disguised as a capsule. It sits on
            // the content side of the glass so it stays crisp.
            Capsule(style: .continuous)
                .fill(Theme.accentSoft)
                .frame(height: 60 * progress)
                .opacity(progress >= 1 ? 0 : 1)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .clipShape(Capsule(style: .continuous))
        }
        // Clear glass that turns accent the moment the lock engages.
        .glassEffect(progress >= 1 ? .regular.tint(Theme.accent) : .regular, in: .capsule)
        .offset(y: -progress * 6)
        .animation(Motion.snappy, value: progress >= 1)
        .accessibilityHidden(true)
    }
}

// MARK: - Formatting

enum VoiceTimeFormat {
    /// `0:07,32` — Telegram shows centiseconds while recording so the timer visibly runs.
    static func recording(_ interval: TimeInterval) -> String {
        let total = max(0, interval)
        let minutes = Int(total) / 60
        let seconds = Int(total) % 60
        let centis = Int((total - floor(total)) * 100)
        return String(format: "%d:%02d,%02d", minutes, seconds, centis)
    }

    /// `0:07` — playback and duration readouts.
    static func duration(_ interval: TimeInterval) -> String {
        let total = max(0, interval.rounded())
        return String(format: "%d:%02d", Int(total) / 60, Int(total) % 60)
    }
}

#Preview("Recording") {
    VStack(spacing: 20) {
        VoiceRecordingBar(elapsed: 7.32, cancelProgress: 0)
        VoiceRecordingBar(elapsed: 7.32, cancelProgress: 0.6)
        VoiceLockedBar(
            elapsed: 12.08,
            levels: VoiceWaveform.placeholder(for: UUID(), count: 30),
            onDiscard: {},
            onSend: {}
        )
        HStack(spacing: 30) {
            VoiceLockIndicator(progress: 0)
            VoiceLockIndicator(progress: 0.5)
            VoiceLockIndicator(progress: 1)
        }
    }
    .padding(24)
    .background(Theme.backgroundChat)
}
