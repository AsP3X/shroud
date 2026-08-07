import SwiftUI

/// Telegram's media transfer disc: "tap to download", or a live progress ring you can cancel.
///
/// Human: The ring is the only honest feedback a 20 MB clip gets — it fills across compress
/// *and* upload as one arc (see `MediaTransfer.ringFraction`), and falls back to a rotating
/// sweep whenever there is no trustworthy number (decrypting, sealing, unknown length).
/// Agent: Pure presentation. `onTap` means download when idle and cancel when busy.
struct MediaTransferControl: View {
    /// What the disc is doing right now.
    enum Mode: Equatable {
        /// Nothing in flight — arrow plus the payload size.
        case idle(byteCount: Int?)
        /// Bytes (or CPU) are moving.
        case busy(MessagingController.MediaTransfer)
    }

    var mode: Mode
    /// Download when idle, cancel when busy. Nil disables the tap entirely.
    var onTap: (() -> Void)?
    var diameter: CGFloat = 52

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// One full sweep of the indeterminate arc.
    private static let sweepPeriod: TimeInterval = 1.1
    /// How much of the circle the indeterminate arc covers.
    private static let sweepLength: CGFloat = 0.22

    private var lineWidth: CGFloat { max(2, diameter * 0.055) }

    private var transfer: MessagingController.MediaTransfer? {
        if case let .busy(transfer) = mode { return transfer }
        return nil
    }

    var body: some View {
        Button {
            onTap?()
        } label: {
            ZStack {
                Circle()
                    .fill(Color.black.opacity(0.45))
                    .background(.ultraThinMaterial.opacity(0.35), in: Circle())

                if transfer != nil {
                    ring
                }

                glyph
            }
            .frame(width: diameter, height: diameter)
            .scaleEffect(pulse)
            .animation(Motion.snappy, value: transfer?.phase)
        }
        .buttonStyle(.plain)
        .pressable(scale: 0.92, haptic: .light)
        .disabled(onTap == nil)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityValue(accessibilityValue)
    }

    /// A small step up as the ring hands over from bytes to the final CPU work — enough of a
    /// change that "finishing" doesn't read as the same frame frozen.
    private var pulse: CGFloat {
        guard !reduceMotion, transfer?.phase == .finishing else { return 1 }
        return 1.04
    }

    // MARK: - Ring

    private var ring: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.25), lineWidth: lineWidth)

            if let transfer {
                if transfer.isIndeterminate {
                    sweep
                } else {
                    Circle()
                        // A hair of arc even at 0 so the ring reads as "started", not "empty".
                        .trim(from: 0, to: max(0.03, transfer.ringFraction))
                        .stroke(
                            Color.white,
                            style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                        )
                        .rotationEffect(.degrees(-90))
                        .animation(.easeOut(duration: 0.3), value: transfer.ringFraction)
                }
            }
        }
        .padding(lineWidth)
        .transition(.opacity)
    }

    @ViewBuilder
    private var sweep: some View {
        let arc = Circle()
            .trim(from: 0, to: Self.sweepLength)
            .stroke(Color.white, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))

        if reduceMotion {
            arc.rotationEffect(.degrees(-90))
        } else {
            TimelineView(.animation) { context in
                let t = context.date.timeIntervalSinceReferenceDate
                    .truncatingRemainder(dividingBy: Self.sweepPeriod) / Self.sweepPeriod
                arc.rotationEffect(.degrees(-90 + t * 360))
            }
        }
    }

    // MARK: - Centre glyph

    @ViewBuilder
    private var glyph: some View {
        switch mode {
        case let .idle(byteCount):
            VStack(spacing: 1) {
                Image(systemName: "arrow.down")
                    .font(.system(size: diameter * 0.33, weight: .bold))
                    .foregroundStyle(Color.white)
                if let byteCount, byteCount > 0 {
                    Text(MediaCrypto.byteCountLabel(byteCount))
                        .font(.system(size: diameter * 0.19, weight: .semibold).monospacedDigit())
                        .foregroundStyle(Color.white.opacity(0.95))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            .padding(.horizontal, 4)
            .transition(Motion.iconSwap)

        case let .busy(transfer):
            // Downloads are abortable, so the disc offers the cancel cross Telegram uses.
            // An upload is already committed to the wire — it just shows its direction.
            Image(systemName: transfer.isUpload ? "arrow.up" : "xmark")
                .font(.system(size: diameter * 0.28, weight: .bold))
                .foregroundStyle(Color.white)
                .transition(Motion.iconSwap)
        }
    }

    // MARK: - Accessibility

    private var accessibilityLabel: String {
        guard let transfer else { return "Download media" }
        if transfer.isUpload { return "Sending media" }
        return "Cancel download"
    }

    private var accessibilityValue: String {
        guard let transfer else { return "" }
        switch transfer.phase {
        case .preparing: return "Compressing"
        case .transferring: return "\(Int(transfer.ringFraction * 100)) percent"
        case .finishing: return "Finishing"
        }
    }
}

#Preview {
    ZStack {
        Color.gray
        VStack(spacing: 24) {
            MediaTransferControl(mode: .idle(byteCount: 4_812_000), onTap: {})
            MediaTransferControl(
                mode: .busy(.init(phase: .transferring, isUpload: false, fraction: 0.42)),
                onTap: {}
            )
            MediaTransferControl(
                mode: .busy(.init(phase: .preparing, isUpload: true, fraction: nil)),
                onTap: {}
            )
            MediaTransferControl(
                mode: .busy(.init(phase: .finishing, isUpload: true, fraction: 1)),
                onTap: {}
            )
        }
    }
    .ignoresSafeArea()
}
