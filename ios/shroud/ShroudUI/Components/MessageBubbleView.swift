import SwiftUI

/// Receipt state for outbound bubbles (Telegram-style ticks).
enum MessageReceiptStatus: Equatable, Sendable, Comparable {
    case failed
    case sending
    case sent
    case delivered
    case read

    var rank: Int {
        switch self {
        case .failed: -1
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

// Note: `failed` is used for outbound media that stayed local after a send error.

/// Shared Telegram-style receipt ticks for text and image bubbles.
struct MessageReceiptIcon: View {
    let receipt: MessageReceiptStatus
    /// Muted color for sent / delivered (and sending spinner).
    var metaColor: Color
    /// Brighter color for read double-checks.
    var readColor: Color
    /// Failed glyph color (text bubbles use danger; image chips may use white).
    var failedColor: Color = Theme.danger

    var body: some View {
        switch receipt {
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(failedColor)
        case .sending:
            ProgressView()
                .controlSize(.mini)
                .tint(metaColor)
                .scaleEffect(0.65)
                .frame(width: 12, height: 11)
        case .sent:
            // Single thin check — Telegram “sent to server”
            Image(systemName: "checkmark")
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(metaColor)
        case .delivered:
            telegramDoubleCheck(color: metaColor)
        case .read:
            telegramDoubleCheck(color: readColor)
        }
    }

    /// Overlapped double check, closer to Telegram’s glyph than two spaced SF symbols.
    private func telegramDoubleCheck(color: Color) -> some View {
        ZStack(alignment: .leading) {
            Image(systemName: "checkmark")
                .font(.system(size: 9, weight: .semibold))
            Image(systemName: "checkmark")
                .font(.system(size: 9, weight: .semibold))
                .offset(x: 4)
        }
        .foregroundStyle(color)
        .frame(width: 14, height: 10, alignment: .leading)
    }
}

/// Message bubble styled close to Telegram iOS:
/// - Content-hugging width for short text
/// - Wraps long text at a max width
/// - Time (+ ticks) sit on the **last line** of the message (not a separate row)
/// - Soft tail corner toward the speaker, flat fill, light meta type
struct MessageBubbleView: View {
    let text: String
    let time: String
    let isMine: Bool
    var isDeleted: Bool = false
    var receipt: MessageReceiptStatus = .sent

    /// Max width of the full bubble (including padding), ~Telegram on a 390pt phone.
    private let maxBubbleWidth: CGFloat = 280

    private var displayText: String {
        isDeleted ? "Message deleted" : text
    }

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
        // Telegram: muted meta on both bubble types.
        isMine ? Color.white.opacity(0.65) : Theme.textSecondary.opacity(0.95)
    }

    private var readTickColor: Color {
        // Read ticks slightly brighter than delivered (Telegram “blue checks” on colored bubbles).
        Color.white.opacity(0.95)
    }

    /// Telegram-like radii: large body, small “tail” corner.
    private var corners: UnevenRoundedRectangle {
        if isMine {
            UnevenRoundedRectangle(
                topLeadingRadius: 17.5,
                bottomLeadingRadius: 17.5,
                bottomTrailingRadius: 5,
                topTrailingRadius: 17.5,
                style: .continuous
            )
        } else {
            UnevenRoundedRectangle(
                topLeadingRadius: 17.5,
                bottomLeadingRadius: 5,
                bottomTrailingRadius: 17.5,
                topTrailingRadius: 17.5,
                style: .continuous
            )
        }
    }

    /// Invisible trailing reservation so the last text line leaves room for time + ticks.
    private var metaSpacerText: Text {
        // Match meta font metrics so reservation width ≈ real meta.
        let ticks = (isMine && !isDeleted) ? " ✓✓" : ""
        return Text(" \(time)\(ticks)")
            .font(.system(size: 11))
            .foregroundStyle(Color.clear)
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 0) {
            if isMine { Spacer(minLength: 56) }

            // Compact single-line (text + meta side by side) when it fits;
            // otherwise multi-line body with meta on the last line (Telegram style).
            ViewThatFits(in: .horizontal) {
                compactBubble
                wrappingBubble
            }

            if !isMine { Spacer(minLength: 56) }
        }
        .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }

    // MARK: - Compact (short messages)

    /// `Hi          12:30 ✓✓` on one row — classic Telegram short bubble.
    private var compactBubble: some View {
        HStack(alignment: .firstTextBaseline, spacing: 7) {
            Text(displayText)
                .font(messageFont)
                .italic(isDeleted)
                .foregroundStyle(textColor)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: true)

            metaRow
                .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 1 }
        }
        .padding(.leading, 11)
        .padding(.trailing, 9)
        .padding(.vertical, 6)
        .background(bubbleFill)
        .clipShape(corners)
        .fixedSize(horizontal: true, vertical: false)
    }

    // MARK: - Wrapping (long messages)

    /// Multi-line text; time/ticks sit on the last line (Telegram layout).
    private var wrappingBubble: some View {
        ZStack(alignment: .bottomTrailing) {
            // Text + clear spacer on the last line reserves space for meta.
            (Text(displayText).font(messageFont).italic(isDeleted).foregroundStyle(textColor)
                + metaSpacerText)
                .multilineTextAlignment(.leading)
                .lineSpacing(1)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 11)
                .padding(.top, 7)
                .padding(.bottom, 6)

            metaRow
                .padding(.trailing, 9)
                .padding(.bottom, 5)
        }
        .frame(maxWidth: maxBubbleWidth, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        .background(bubbleFill)
        .clipShape(corners)
        // Keep the bubble from stretching to the full row when text is only medium-length:
        // measure ideal width via background preference is heavy; ViewThatFits already
        // preferred compact. For wrapping, allow up to maxBubbleWidth.
        .frame(maxWidth: maxBubbleWidth, alignment: isMine ? .trailing : .leading)
    }

    // MARK: - Meta (time + ticks)

    private var metaRow: some View {
        HStack(spacing: 3) {
            Text(time)
                .font(.system(size: 11, weight: .regular))
                .foregroundStyle(metaColor)
                .monospacedDigit()
                .fixedSize()

            if isMine, !isDeleted {
                receiptIcon
            }
        }
    }

    private var messageFont: Font {
        // Telegram iOS message body is ~17pt SF; 16 keeps density close without feeling large.
        .system(size: 16)
    }

    private var receiptIcon: some View {
        MessageReceiptIcon(
            receipt: receipt,
            metaColor: metaColor,
            readColor: readTickColor
        )
    }

    private var accessibilityLabel: String {
        var parts = [isMine ? "You" : "Them", displayText, time]
        if isMine {
            switch receipt {
            case .failed: parts.append("Failed")
            case .sending: parts.append("Sending")
            case .sent: parts.append("Sent")
            case .delivered: parts.append("Delivered")
            case .read: parts.append("Read")
            }
        }
        return parts.joined(separator: ", ")
    }
}

#Preview("Telegram-style bubbles") {
    ScrollView {
        VStack(spacing: 8) {
            MessageBubbleView(text: "a", time: "11:00", isMine: false)
            MessageBubbleView(text: "👍", time: "11:01", isMine: true, receipt: .sent)
            MessageBubbleView(text: "Hi", time: "11:02", isMine: false)
            MessageBubbleView(text: "Ok", time: "11:03", isMine: true, receipt: .delivered)
            MessageBubbleView(text: "See you at 10", time: "11:05", isMine: true, receipt: .read)
            MessageBubbleView(
                text: "Hey! Are we still on for tomorrow? Parking near the trailhead fills up fast on weekends so let's leave early.",
                time: "12:10",
                isMine: true,
                receipt: .read
            )
            MessageBubbleView(
                text: "Sounds good, see you tomorrow! I'll bring snacks.",
                time: "12:11",
                isMine: false
            )
            MessageBubbleView(
                text: String(repeating: "longword ", count: 12),
                time: "12:15",
                isMine: false
            )
        }
        .padding(16)
        .frame(maxWidth: .infinity)
    }
    .background(Theme.backgroundChat)
}
