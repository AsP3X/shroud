import SwiftUI

/// Encryption phrase card with paired rows (1–2, 3–4, …) and staggered shimmer reveal.
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
            ZStack {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Theme.accentSoft)
                    .frame(width: 20, height: 20)
                Text("\(number)")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Theme.accent)
            }

            Group {
                if isRevealed {
                    Text(word)
                        .font(.system(size: 14, weight: .medium, design: .monospaced))
                        .foregroundStyle(Theme.textPrimary)
                        .transition(.opacity.combined(with: .scale(scale: 0.98)))
                } else {
                    ShimmerPlaceholder(height: 14, width: 92)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 7)
        .frame(maxWidth: .infinity)
        .animation(.easeOut(duration: 0.28), value: isRevealed)
    }
}
