import CryptoKit
import Foundation

/// The history key for sealed stores that sit outside the messaging repository: Double Ratchet
/// sessions in the Keychain and the voice language statistics file.
///
/// Human: These follow the chat lock exactly. While chats are locked the key is not here, so
/// ratchet state and who-you-send-voice-notes-to cannot be read, and nothing is written in the
/// clear instead.
/// Agent: SET by `CryptoController` whenever its `material` changes (unlock → `unlock`,
/// nil → `lock`). READ by `RatchetSessionStore` and `TranscriptionLanguageMemory`. Unlock also
/// runs their plaintext migrations. Never logs the key.
nonisolated enum SealedLocalState {
    private static let keyLock = NSLock()
    private nonisolated(unsafe) static var key: SymmetricKey?

    /// Nil while chats are locked.
    static var historyKey: SymmetricKey? {
        keyLock.withLock { key }
    }

    /// Opens the stores and seals anything an older build left in the clear.
    static func unlock(historyKey: SymmetricKey) {
        setHistoryKey(historyKey)
        RatchetSessionStore.sealPlaintextSessions(historyKey: historyKey)
        TranscriptionLanguageMemory.unlock(historyKey: historyKey)
    }

    static func lock() {
        setHistoryKey(nil)
        TranscriptionLanguageMemory.lock()
    }

    /// Only the key, no migrations — `unlock` and unit tests.
    static func setHistoryKey(_ historyKey: SymmetricKey?) {
        keyLock.withLock { key = historyKey }
    }
}
