import CryptoKit
import XCTest
@testable import shroud

final class HistoryKeyVaultTests: XCTestCase {
    /// The vault only exists behind a passcode. A simulator without one (or without enrolled
    /// Face ID) cannot hold it, which `testNoPasscodeRefusesTheVault` covers instead.
    override func setUpWithError() throws {
        try super.setUpWithError()
        HistoryKeyVault.clear()
        if name.contains("testNoPasscodeRefusesTheVault") { return }
        try XCTSkipUnless(HistoryKeyVault.canProtectWrapKey, "device has no passcode")
    }

    override func tearDown() {
        HistoryKeyVault.clear()
        super.tearDown()
    }

    func testNoPasscodeRefusesTheVault() throws {
        try XCTSkipIf(HistoryKeyVault.canProtectWrapKey, "device has a passcode")
        let userID = UUID()
        XCTAssertThrowsError(
            try HistoryKeyVault.store(historyKey: SymmetricKey(size: .bits256), userID: userID)
        ) { error in
            XCTAssertEqual(error as? HistoryKeyVault.VaultError, .passcodeNotSet)
        }
        XCTAssertFalse(HistoryKeyVault.hasBlob(for: userID))
        XCTAssertThrowsError(try HistoryKeyVault.unlock(userID: userID)) { error in
            XCTAssertEqual(error as? HistoryKeyVault.VaultError, .passcodeNotSet)
        }
    }

    func testStoreMarksTheWrapKeyProtected() throws {
        try HistoryKeyVault.store(historyKey: SymmetricKey(size: .bits256), userID: UUID())
        XCTAssertTrue(HistoryKeyVault.isWrapKeyProtected)
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
