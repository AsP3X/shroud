import SwiftUI

/// Checklist item bubble for Notes to me.
struct TodoMessageBubble: View {
    let text: String
    let time: String
    let isDone: Bool
    var onToggle: () -> Void

    var body: some View {
        HStack {
            Spacer(minLength: MessageBubbleMetrics.oppositeGutter)
            HStack(alignment: .top, spacing: 10) {
                Button(action: onToggle) {
                    Image(systemName: isDone ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(isDone ? Theme.accent : Theme.textSecondary)
                        .frame(width: 28, height: 28)
                        // 44 pt of target around the 28 pt circle; the negative padding below
                        // keeps the glyph and the bubble where they were.
                        .padding(8)
                        .contentShape(Rectangle())
                }
                // The host's onToggle plays the haptic.
                .pressable(scale: 0.88, haptic: nil)
                .padding(-8)
                .accessibilityLabel(isDone ? "Mark incomplete" : "Mark complete")

                VStack(alignment: .leading, spacing: 4) {
                    Text(text)
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textPrimary)
                        .strikethrough(isDone, color: Theme.textSecondary)
                        .opacity(isDone ? 0.65 : 1)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)

                    Text(time)
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Theme.bubbleIncoming.opacity(0.92))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Todo, \(text), \(isDone ? "done" : "open")")
    }
}
