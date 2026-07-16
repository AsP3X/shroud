import SwiftUI

/// Receipt state for outbound bubbles (WhatsApp-style ticks).
enum MessageReceiptStatus: Equatable, Sendable, Comparable {
    /// Still encrypting / waiting on the network.
    case sending
    /// Accepted by the server.
    case sent
    /// At least one peer device acknowledged delivery.
    case delivered
    /// Peer opened the chat / marked the message read.
    case read

    var rank: Int {
        switch self {
        case .sending: 0
        case .sent: 1
        case .delivered: 2
        case .read: 3
        }
    }

    static func < (lhs: MessageReceiptStatus, rhs: MessageReceiptStatus) -> Bool {
        lhs.rank < rhs.rank
    }
}

/// Incoming / outgoing text bubble — maps to `Bubble In` / `Bubble Out` in `iOS-App.pen`.
///
/// Short messages hug their text; long messages wrap up to `maxContentWidth`.
struct MessageBubbleView: View {
    let text: String
    let time: String
    let isMine: Bool
    var isDeleted: Bool = false
    var receipt: MessageReceiptStatus = .sent

    /// Max text column width (padding is outside this).
    private let maxContentWidth: CGFloat = 256

    private var bubbleFill: Color {
        isMine ? Theme.accent : Theme.bubbleIncoming
    }

    private var textColor: Color {
        if isDeleted {
            return isMine ? Color.white.opacity(0.85) : Theme.textSecondary
        }
        return isMine ? Color.white : Theme.textPrimary
    }

    private var metaColor: Color {
        isMine ? Color.white.opacity(0.72) : Theme.textSecondary
    }

    private var readMetaColor: Color {
        Color.white.opacity(0.95)
    }

    /// Design: out `[18, 18, 4, 18]`, in `[18, 18, 18, 4]` (TL, TR, BR, BL).
    private var corners: UnevenRoundedRectangle {
        if isMine {
            UnevenRoundedRectangle(
                topLeadingRadius: 18,
                bottomLeadingRadius: 18,
                bottomTrailingRadius: 4,
                topTrailingRadius: 18,
                style: .continuous
            )
        } else {
            UnevenRoundedRectangle(
                topLeadingRadius: 18,
                bottomLeadingRadius: 4,
                bottomTrailingRadius: 18,
                topTrailingRadius: 18,
                style: .continuous
            )
        }
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 0) {
            if isMine { Spacer(minLength: 52) }

            bubbleBody

            if !isMine { Spacer(minLength: 52) }
        }
        .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }

    private var bubbleBody: some View {
        VStack(alignment: .trailing, spacing: 3) {
            Text(isDeleted ? "Message deleted" : text)
                .font(.system(size: 15))
                .italic(isDeleted)
                .foregroundStyle(textColor)
                .multilineTextAlignment(.leading)
                .lineLimit(nil)
                // Ideal width for short text; wrap once content hits the cap.
                .frame(maxWidth: maxContentWidth, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 4) {
                Text(time)
                    .font(.system(size: 11, weight: .regular))
                    .foregroundStyle(metaColor)
                    .monospacedDigit()

                if isMine, !isDeleted {
                    receiptIcon
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 7)
        .background(bubbleFill)
        .clipShape(corners)
        .shadow(
            color: Color.black.opacity(isMine ? 0.06 : 0.05),
            radius: isMine ? 2 : 3,
            y: 1
        )
    }

    @ViewBuilder
    private var receiptIcon: some View {
        switch receipt {
        case .sending:
            ProgressView()
                .controlSize(.mini)
                .tint(metaColor)
                .scaleEffect(0.7)
                .frame(width: 14, height: 12)
        case .sent:
            Image(systemName: "checkmark")
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(metaColor)
        case .delivered:
            doubleCheck(color: metaColor)
        case .read:
            doubleCheck(color: readMetaColor)
        }
    }

    private func doubleCheck(color: Color) -> some View {
        HStack(spacing: -3) {
            Image(systemName: "checkmark")
                .font(.system(size: 10, weight: .bold))
            Image(systemName: "checkmark")
                .font(.system(size: 10, weight: .bold))
        }
        .foregroundStyle(color)
    }

    private var accessibilityLabel: String {
        var parts = [isMine ? "You" : "Them", isDeleted ? "Message deleted" : text, time]
        if isMine {
            switch receipt {
            case .sending: parts.append("Sending")
            case .sent: parts.append("Sent")
            case .delivered: parts.append("Delivered")
            case .read: parts.append("Read")
            }
        }
        return parts.joined(separator: ", ")
    }
}

#Preview {
    VStack(alignment: .leading, spacing: 10) {
        MessageBubbleView(text: "Hi", time: "11:02", isMine: false)
        MessageBubbleView(text: "Ok", time: "11:03", isMine: true, receipt: .sent)
        MessageBubbleView(text: "Yes! 10am", time: "11:05", isMine: true, receipt: .delivered)
        MessageBubbleView(
            text: "Hey! Are we still on for tomorrow? Parking near the trailhead fills up fast on weekends.",
            time: "12:10",
            isMine: true,
            receipt: .read
        )
        MessageBubbleView(text: "Sounds good, see you tomorrow!", time: "12:11", isMine: false)
    }
    .padding()
    .frame(maxWidth: .infinity)
    .background(Theme.backgroundChat)
}
