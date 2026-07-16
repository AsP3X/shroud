import SwiftUI

/// Incoming / outgoing text bubble — maps to `Bubble In` / `Bubble Out` in `iOS-App.pen`.
struct MessageBubbleView: View {
    let text: String
    let time: String
    let isMine: Bool
    var isDeleted: Bool = false
    /// Outgoing only: show read-receipt checks when true.
    var showReadReceipt: Bool = true

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
        isMine ? Color.white.opacity(0.7) : Theme.textSecondary
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
            if isMine { Spacer(minLength: 56) }

            VStack(alignment: .trailing, spacing: 2) {
                Text(isDeleted ? "Message deleted" : text)
                    .font(.system(size: 15))
                    .italic(isDeleted)
                    .foregroundStyle(textColor)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 3) {
                    Text(time)
                        .font(.system(size: 11))
                        .foregroundStyle(metaColor)
                    if isMine, showReadReceipt, !isDeleted {
                        Image(systemName: "checkmark.circle")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(metaColor)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(bubbleFill)
            .clipShape(corners)
            .shadow(color: Color.black.opacity(isMine ? 0 : 0.04), radius: 4, y: 1)
            .frame(maxWidth: 280, alignment: isMine ? .trailing : .leading)

            if !isMine { Spacer(minLength: 56) }
        }
        .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
    }
}

#Preview {
    VStack(spacing: 10) {
        MessageBubbleView(text: "Hey! Are we still on for tomorrow?", time: "11:02", isMine: false)
        MessageBubbleView(text: "Yes! 10am at the trailhead", time: "11:05", isMine: true)
        MessageBubbleView(text: "Sounds good!", time: "12:10", isMine: false)
    }
    .padding()
    .background(Theme.backgroundChat)
}
