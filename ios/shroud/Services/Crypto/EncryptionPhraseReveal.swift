import SwiftUI

/// Staggered shimmer-to-word reveal used when generating or pasting encryption phrases.
enum EncryptionPhraseReveal {
    /// Delay between unlocking each pair of words (1–2, 3–4, …).
    static let pairRevealDelayNanoseconds: UInt64 = 45_000_000

    /// Spring used when a word pops in from its shimmer placeholder.
    static let wordRevealSpring = Animation.spring(response: 0.28, dampingFraction: 0.72)

    /// Short accent pulse on the word-number badge when that word reveals.
    static let badgePulseDuration: TimeInterval = 0.32

    // Human: Unlocks words in pairs with a snappy spring so register generation and login paste feel identical.
    // Agent: Cancels in-flight task, sleeps 45 ms between pairs, WRITES revealedCount (2, 4, …, 12) via MainActor.
    @MainActor
    static func start(
        wordCount: Int,
        setRevealedCount: @escaping (Int) -> Void,
        existingTask: inout Task<Void, Never>?
    ) {
        existingTask?.cancel()
        existingTask = Task {
            let pairCount = (wordCount + 1) / 2
            for pairIndex in 0 ..< pairCount {
                if Task.isCancelled { return }
                try? await Task.sleep(nanoseconds: pairRevealDelayNanoseconds)
                if Task.isCancelled { return }
                let revealed = min((pairIndex + 1) * 2, wordCount)
                withAnimation(wordRevealSpring) {
                    setRevealedCount(revealed)
                }
            }
        }
    }
}
