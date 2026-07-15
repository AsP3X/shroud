import SwiftUI

/// Encryption phrase card with paired rows (1–2, 3–4, …) and staggered spring reveal.
struct EncryptionPhraseCard: View {
    let words: [String]
    let revealedCount: Int

    private let rowCount = 6

    var body: some View {
        VStack(spacing: 0) {
            ForEach(0 ..< rowCount, id: \.self) { row in
                HStack(spacing: 8) {
                    phraseCell(number: row * 2 + 1, word: word(at: row * 2))
                    phraseCell(number: row * 2 + 2, word: word(at: row * 2 + 1))
                }

                if row < rowCount - 1 {
                    Divider()
                }
            }
        }
        .padding(.vertical, 6)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func word(at index: Int) -> String {
        guard words.indices.contains(index) else { return "" }
        return words[index]
    }

    private func phraseCell(number: Int, word: String) -> some View {
        let isRevealed = number <= revealedCount

        return HStack(spacing: 9) {
            PhraseWordNumberBadge(number: number, isRevealed: isRevealed)

            Group {
                if isRevealed {
                    Text(word)
                        .font(.system(size: 14, weight: .medium, design: .monospaced))
                        .foregroundStyle(Theme.textPrimary)
                        .transition(Self.wordPopTransition)
                } else {
                    ShimmerPlaceholder(height: 14, width: 92)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 7)
        .frame(maxWidth: .infinity)
        .animation(EncryptionPhraseReveal.wordRevealSpring, value: isRevealed)
    }

    // Human: Springy pop from slightly underscale + fade so each word lands with a premium overshoot.
    private static var wordPopTransition: AnyTransition {
        .asymmetric(
            insertion: .opacity.combined(with: .scale(scale: 0.86, anchor: .leading)),
            removal: .opacity
        )
    }
}

/// Number badge that briefly pulses accent when its word reveals.
struct PhraseWordNumberBadge: View {
    let number: Int
    let isRevealed: Bool

    @State private var isPulsing = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(isPulsing ? Theme.accent : Theme.accentSoft)
                .frame(width: 20, height: 20)
            Text("\(number)")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .foregroundStyle(isPulsing ? Color.white : Theme.accent)
        }
        .scaleEffect(isPulsing ? 1.12 : 1)
        .onChange(of: isRevealed) { _, revealed in
            guard revealed else {
                isPulsing = false
                return
            }
            // Human: One-shot accent flash when this badge's word unlocks; settles back to soft fill.
            // Agent: Animates isPulsing true→false with spring; no side effects beyond local UI state.
            withAnimation(EncryptionPhraseReveal.wordRevealSpring) {
                isPulsing = true
            }
            Task { @MainActor in
                try? await Task.sleep(
                    nanoseconds: UInt64(EncryptionPhraseReveal.badgePulseDuration * 1_000_000_000)
                )
                withAnimation(.easeOut(duration: 0.18)) {
                    isPulsing = false
                }
            }
        }
    }
}
