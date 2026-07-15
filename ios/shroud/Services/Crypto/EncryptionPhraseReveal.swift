import SwiftUI

/// Staggered shimmer-to-word reveal used when generating or pasting encryption phrases.
enum EncryptionPhraseReveal {
    static let wordRevealDelayNanoseconds: UInt64 = 60_000_000
    static let wordRevealAnimationDuration: TimeInterval = 0.16

    // Human: Reveals words one at a time with a quick stagger shared by Sign Up generation and Log In paste.
    // Agent: Cancels any in-flight task, sleeps 60 ms between steps, WRITES revealedCount via MainActor.
    @MainActor
    static func start(
        wordCount: Int,
        setRevealedCount: @escaping (Int) -> Void,
        existingTask: inout Task<Void, Never>?
    ) {
        existingTask?.cancel()
        existingTask = Task {
            for index in 0 ..< wordCount {
                if Task.isCancelled { return }
                try? await Task.sleep(nanoseconds: wordRevealDelayNanoseconds)
                if Task.isCancelled { return }
                withAnimation(.easeOut(duration: wordRevealAnimationDuration)) {
                    setRevealedCount(index + 1)
                }
            }
        }
    }
}
