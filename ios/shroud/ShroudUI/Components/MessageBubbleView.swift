import SwiftUI
import UIKit

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

    /// Human: The tick is the app's smallest but most-watched state machine
    /// (sending → sent → delivered → read). Each hop pops the new glyph in instead of
    /// hard-cutting, so progress stays legible out of the corner of the eye.
    var body: some View {
        ZStack {
            glyph
                .id(receipt)
                .transition(Motion.iconSwap)
        }
        .animation(Motion.snappy, value: receipt)
    }

    @ViewBuilder
    private var glyph: some View {
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
                .frame(width: MessageBubbleMetrics.tickWidth, height: 11)
        case .sent:
            // Single thin check — Telegram “sent to server”.
            // Padded to the double-check width so a sent → delivered hop swaps the glyph
            // without changing the meta width (which would re-wrap the bubble's last line).
            Image(systemName: "checkmark")
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(metaColor)
                .frame(width: MessageBubbleMetrics.tickWidth, alignment: .leading)
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
        .frame(width: MessageBubbleMetrics.tickWidth, height: 10, alignment: .leading)
    }
}

/// Preference for the visual bubble’s global frame (excludes row spacers).
/// Used so the long-press menu can hero-animate from / back to the real bubble slot.
struct MessageBubbleFrameKey: PreferenceKey {
    static var defaultValue: [UUID: CGRect] = [:]

    static func reduce(value: inout [UUID: CGRect], nextValue: () -> [UUID: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { $1 })
    }
}

/// Width of one chat row (the thread's width minus its horizontal insets).
///
/// Human: Bubbles used to cap at a hard-coded 280pt, so on anything wider than an iPhone 13
/// a long message wrapped into a narrow ragged column with a third of the screen left empty.
/// `ConversationView` measures the thread once and publishes it here instead.
private struct ChatRowWidthKey: EnvironmentKey {
    static let defaultValue: CGFloat = MessageBubbleMetrics.fallbackRowWidth
}

extension EnvironmentValues {
    /// Set by `ConversationView`; read by bubbles to size their max width.
    var chatRowWidth: CGFloat {
        get { self[ChatRowWidthKey.self] }
        set { self[ChatRowWidthKey.self] = newValue }
    }
}

/// Shared geometry for the text bubble.
///
/// Human: The time+ticks overlay and the invisible run that reserves room for it on the last
/// line are two views that must agree on a width to the point. They only stay in sync because
/// both measure from the constants here.
enum MessageBubbleMetrics {
    /// Width of the tick block — every receipt glyph is padded to this so a receipt
    /// upgrade never re-flows the text.
    static let tickWidth: CGFloat = 14
    /// Gap between the time and the ticks inside `metaRow`.
    static let metaSpacing: CGFloat = 3
    static let metaFontSize: CGFloat = 11
    static let bodyFontSize: CGFloat = 16
    /// Empty strip left on the opposite side of the row so direction reads at a glance.
    static let oppositeGutter: CGFloat = 56
    /// Never squeeze narrower than this, even on a very small thread width.
    static let minBubbleWidth: CGFloat = 240
    /// Conservative stand-in (~iPhone SE) until the host has measured its thread.
    static let fallbackRowWidth: CGFloat = 288
    static let textLeadingPad: CGFloat = 11
    static let textTrailingPad: CGFloat = 11
    /// Meta sits a touch closer to the edge than the body text (Telegram does the same).
    static let metaTrailingPad: CGFloat = 10
    /// Clear space between the end of the last line and the time.
    static let metaGap: CGFloat = 8

    private static let metaUIFont = UIFont.monospacedDigitSystemFont(
        ofSize: metaFontSize,
        weight: .regular
    )
    private static let digitWidth = ("0" as NSString)
        .size(withAttributes: [.font: metaUIFont]).width

    /// Rendered width of `metaRow`, measured in the font it actually draws with.
    static func metaWidth(time: String, showsReceipt: Bool) -> CGFloat {
        let timeWidth = (time as NSString).size(withAttributes: [.font: metaUIFont]).width
        return timeWidth.rounded(.up) + (showsReceipt ? metaSpacing + tickWidth : 0)
    }

    /// Filler appended to the body so the last line keeps clear of the meta overlay.
    ///
    /// Human: It has to be built from *glyphs*, not spaces — the text engine drops trailing
    /// whitespace when it breaks a line, which silently collapses a space-based reservation
    /// and drops the timestamp on top of the words. Clear-coloured zeroes in the meta's own
    /// monospaced-digit font give an exact, un-trimmable width.
    static func metaReservation(time: String, showsReceipt: Bool) -> String {
        let needed = metaWidth(time: time, showsReceipt: showsReceipt)
            + (textTrailingPad - metaTrailingPad)
            + metaGap
        let count = max(1, Int((needed / max(digitWidth, 1)).rounded(.up)))
        // Leading plain space is a legal break point, so a reservation that no longer fits
        // moves to its own line instead of dragging the last word down with it.
        return " " + String(repeating: "0", count: count)
    }

    /// Display normalisation for pasted content.
    ///
    /// Human: People paste assistant answers and flattened markdown tables in here. Raw tabs
    /// jump to the text engine's default tab stops, which in a bubble scatters cells across
    /// lines and leaves craters after list bullets. Collapsing horizontal whitespace turns
    /// that back into ordinary prose. Presentation only — `message.text` is what gets copied,
    /// forwarded and re-encrypted.
    static func normalizedForDisplay(_ raw: String) -> String {
        var out = ""
        out.reserveCapacity(raw.count)
        var pendingNewlines = 0
        var pendingSpace = false
        var wroteAny = false

        for character in raw {
            if character == "\r" { continue }
            if character.isNewline {
                pendingNewlines += 1
                pendingSpace = false
                continue
            }
            if character == "\t" || character == " " {
                pendingSpace = true
                continue
            }
            if wroteAny {
                if pendingNewlines > 0 {
                    // Keep one blank line as a paragraph break; drop the rest.
                    out.append(String(repeating: "\n", count: min(pendingNewlines, 2)))
                } else if pendingSpace {
                    out.append(" ")
                }
            }
            pendingNewlines = 0
            pendingSpace = false
            out.append(character)
            wroteAny = true
        }
        return out
    }
}

/// Message bubble styled close to Telegram iOS:
/// - Content-hugging width for short text
/// - Wraps long text at a max width that follows the thread's own width
/// - Time (+ ticks) sit on the **last line** of the message (not a separate row)
/// - Soft tail corner toward the speaker, flat fill, light meta type
struct MessageBubbleView: View {
    let text: String
    let time: String
    let isMine: Bool
    var isDeleted: Bool = false
    var receipt: MessageReceiptStatus = .sent
    /// When false, renders only the bubble (no leading/trailing row spacers) for menu hero.
    var isRowEmbedded: Bool = true
    /// When set, reports this bubble’s global frame via `MessageBubbleFrameKey`.
    var frameReportID: UUID? = nil

    @Environment(\.chatRowWidth) private var chatRowWidth

    /// Max width of the full bubble (including padding) — the row minus the opposite gutter.
    private var maxBubbleWidth: CGFloat {
        // A host that hasn't measured yet reports 0; fall back rather than collapse the bubble.
        let row = chatRowWidth > 0 ? chatRowWidth : MessageBubbleMetrics.fallbackRowWidth
        return min(row, max(MessageBubbleMetrics.minBubbleWidth, row - MessageBubbleMetrics.oppositeGutter))
    }

    private var displayText: String {
        isDeleted ? "Message deleted" : MessageBubbleMetrics.normalizedForDisplay(text)
    }

    /// Explicit line breaks force the wrapping layout: the compact row is `lineLimit(1)`
    /// and would truncate everything after the first line away.
    private var isMultiline: Bool {
        displayText.contains { $0.isNewline }
    }

    private var showsReceipt: Bool {
        isMine && !isDeleted
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

    /// The message body, styled for inline composition with `metaSpacerText`.
    private var bodyText: Text {
        Text(displayText)
            .font(messageFont)
            .italic(isDeleted)
            .foregroundStyle(textColor)
    }

    /// Invisible trailing reservation so the last text line leaves room for time + ticks.
    private var metaSpacerText: Text {
        Text(verbatim: MessageBubbleMetrics.metaReservation(time: time, showsReceipt: showsReceipt))
            .font(metaFont)
            .foregroundStyle(Color.clear)
    }

    var body: some View {
        Group {
            if isRowEmbedded {
                HStack(alignment: .bottom, spacing: 0) {
                    if isMine { Spacer(minLength: MessageBubbleMetrics.oppositeGutter) }
                    bubbleCore
                    if !isMine { Spacer(minLength: MessageBubbleMetrics.oppositeGutter) }
                }
                .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
            } else {
                bubbleCore
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }

    /// Compact single-line when it fits; otherwise multi-line body with meta on the last line.
    private var bubbleCore: some View {
        Group {
            if isMultiline {
                wrappingBubble
            } else {
                ViewThatFits(in: .horizontal) {
                    compactBubble
                    wrappingBubble
                }
            }
        }
        // Clamps the width proposed to `ViewThatFits`, so the compact row is only chosen when
        // it fits inside the bubble's real budget rather than the whole row.
        .frame(maxWidth: maxBubbleWidth, alignment: isMine ? .trailing : .leading)
    }

    /// Reports the drawn bubble (not the row slot) so the long-press menu hero lines up.
    @ViewBuilder
    private var frameReporter: some View {
        if let frameReportID {
            GeometryReader { geo in
                Color.clear.preference(
                    key: MessageBubbleFrameKey.self,
                    value: [frameReportID: geo.frame(in: .global)]
                )
            }
        }
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
        .padding(.leading, MessageBubbleMetrics.textLeadingPad)
        .padding(.trailing, MessageBubbleMetrics.metaTrailingPad)
        .padding(.vertical, 6)
        .background(bubbleFill)
        .clipShape(corners)
        .background { frameReporter }
        .fixedSize(horizontal: true, vertical: false)
    }

    // MARK: - Wrapping (long messages)

    /// Multi-line text; time/ticks sit on the last line (Telegram layout).
    ///
    /// Human: The bubble hugs its longest line rather than snapping to the cap, so a two-line
    /// reply stays a two-line bubble. The cap comes from the `bubbleCore` frame above, which
    /// is what the wrapping is measured against.
    private var wrappingBubble: some View {
        ZStack(alignment: .bottomTrailing) {
            // Text + clear spacer on the last line reserves space for meta.
            Text("\(bodyText)\(metaSpacerText)")
                .multilineTextAlignment(.leading)
                // Wide paragraphs of pasted text read as a wall without a little extra leading.
                .lineSpacing(2.5)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.leading, MessageBubbleMetrics.textLeadingPad)
                .padding(.trailing, MessageBubbleMetrics.textTrailingPad)
                .padding(.top, 7)
                .padding(.bottom, 6)

            metaRow
                .padding(.trailing, MessageBubbleMetrics.metaTrailingPad)
                .padding(.bottom, 5)
        }
        .fixedSize(horizontal: false, vertical: true)
        .background(bubbleFill)
        .clipShape(corners)
        .background { frameReporter }
    }

    // MARK: - Meta (time + ticks)

    private var metaRow: some View {
        HStack(spacing: MessageBubbleMetrics.metaSpacing) {
            Text(time)
                .font(metaFont)
                .foregroundStyle(metaColor)
                .fixedSize()

            if showsReceipt {
                receiptIcon
            }
        }
    }

    private var messageFont: Font {
        // Telegram iOS message body is ~17pt SF; 16 keeps density close without feeling large.
        .system(size: MessageBubbleMetrics.bodyFontSize)
    }

    /// Must stay in step with `MessageBubbleMetrics`' measuring font, or the last-line
    /// reservation drifts away from the meta it is reserving for.
    private var metaFont: Font {
        .system(size: MessageBubbleMetrics.metaFontSize, weight: .regular).monospacedDigit()
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
    GeometryReader { geo in
        ScrollView {
            VStack(spacing: 8) {
                MessageBubbleView(text: "a", time: "11:00", isMine: false)
                MessageBubbleView(text: "👍", time: "11:01", isMine: true, receipt: .sent)
                MessageBubbleView(text: "Ok", time: "11:03", isMine: true, receipt: .delivered)
                MessageBubbleView(
                    text: "Two lines\nand the time must clear the second one",
                    time: "11:06",
                    isMine: false
                )
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
                // Pasted table: tabs would otherwise scatter the cells across lines.
                MessageBubbleView(
                    text: "Key takeaways:\nMetric\tValue\nDurchschnittsgehalt\t41.000 € brutto / Jahr\nMonatsgehalt\t≈ 3.417 €\n\t•\tPersonalverantwortung bringt im Schnitt +19 %.",
                    time: "15:58",
                    isMine: false
                )
                MessageBubbleView(
                    text: String(repeating: "longword ", count: 12),
                    time: "12:15",
                    isMine: true,
                    receipt: .sending
                )
            }
            .padding(16)
            .frame(maxWidth: .infinity)
        }
        .environment(\.chatRowWidth, geo.size.width - 32)
        .background(Theme.backgroundChat)
    }
}
