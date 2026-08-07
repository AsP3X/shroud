import SwiftUI

/// Checklist item bubble for Notes to me.
struct TodoMessageBubble: View {
    let text: String
    let time: String
    let isDone: Bool
    var onToggle: () -> Void

    var body: some View {
        HStack {
            Spacer(minLength: 48)
            HStack(alignment: .top, spacing: 10) {
                Button(action: onToggle) {
                    Image(systemName: isDone ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(isDone ? Theme.accent : Theme.textSecondary)
                        .frame(width: 28, height: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .pressable(scale: 0.88)
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
        .padding(.horizontal, 10)
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Todo, \(text), \(isDone ? "done" : "open")")
    }
}
