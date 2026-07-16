import SwiftUI

/// Three-dot typing bubble — maps to `Typing Bubble` in `Conversation — Typing`.
struct TypingIndicatorBubble: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 0.32, paused: false)) { context in
            let phase = Int(context.date.timeIntervalSinceReferenceDate / 0.32) % 3
            HStack {
                HStack(spacing: 5) {
                    ForEach(0 ..< 3, id: \.self) { index in
                        Circle()
                            .fill(dotColor(for: index, phase: phase))
                            .frame(width: 7, height: 7)
                            .offset(y: phase == index ? -3 : 0)
                            .animation(.easeInOut(duration: 0.28), value: phase)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 14)
                .background(Theme.bubbleIncoming)
                .clipShape(
                    UnevenRoundedRectangle(
                        topLeadingRadius: 18,
                        bottomLeadingRadius: 4,
                        bottomTrailingRadius: 18,
                        topTrailingRadius: 18,
                        style: .continuous
                    )
                )
                .shadow(color: Color.black.opacity(0.04), radius: 4, y: 1)

                Spacer(minLength: 56)
            }
        }
    }

    private func dotColor(for index: Int, phase: Int) -> Color {
        // Design: #8E8E93, #B4B4BA, #D3D3D8 — active slightly darker.
        let base: [Color] = [
            Color(red: 0.557, green: 0.557, blue: 0.576),
            Color(red: 0.706, green: 0.706, blue: 0.729),
            Color(red: 0.827, green: 0.827, blue: 0.847),
        ]
        return phase == index ? Theme.textSecondary : base[index]
    }
}

#Preview {
    TypingIndicatorBubble()
        .padding()
        .background(Theme.backgroundChat)
}
