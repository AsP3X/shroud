import CryptoKit
import Foundation
@testable import shroud

/// One history key for every suite that touches the process-global sealed stores. Suites run
/// in parallel; a random key per suite would leave one unable to open another's ratchet items.
enum SealedTestKey {
    static let historyKey = SymmetricKey(data: Data(repeating: 0x5A, count: 32))

    static func unlockSealedLocalState() {
        SealedLocalState.setHistoryKey(historyKey)
    }
}
