import CryptoKit
import XCTest
@testable import shroud

final class HistoryKeyVaultTests: XCTestCase {
    override func setUp() {
        super.setUp()
        HistoryKeyVault.requiresUserPresence = false
        HistoryKeyVault.clear()
    }

    override func tearDown() {
        HistoryKeyVault.clear()
        HistoryKeyVault.requiresUserPresence = true
        super.tearDown()
    }

    func testStoreAndUnlockRoundTrip() throws {
        let userID = UUID()
        let key = SymmetricKey(size: .bits256)
        try HistoryKeyVault.store(historyKey: key, userID: userID)
        XCTAssertTrue(HistoryKeyVault.hasBlob(for: userID))

        let unlocked = try HistoryKeyVault.unlock(userID: userID)
        let original = key.withUnsafeBytes { Data($0) }
        let roundTrip = unlocked.withUnsafeBytes { Data($0) }
        XCTAssertEqual(original, roundTrip)
    }

    func testWrongUserFails() throws {
        let userID = UUID()
        try HistoryKeyVault.store(historyKey: SymmetricKey(size: .bits256), userID: userID)
        XCTAssertThrowsError(try HistoryKeyVault.unlock(userID: UUID())) { error in
            XCTAssertEqual(error as? HistoryKeyVault.VaultError, .notFound)
        }
    }

    func testClearRemovesBlob() throws {
        let userID = UUID()
        try HistoryKeyVault.store(historyKey: SymmetricKey(size: .bits256), userID: userID)
        HistoryKeyVault.clear()
        XCTAssertFalse(HistoryKeyVault.hasBlob(for: userID))
        XCTAssertThrowsError(try HistoryKeyVault.unlock(userID: userID))
    }
}
