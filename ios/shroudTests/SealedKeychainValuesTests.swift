import CryptoKit
import Foundation
import Testing
@testable import shroud

/// Identity privates and ratchet sessions are sealed under the history key before they reach
/// the Keychain. The Keychain itself is not exercised here (simulator runs lack entitlements);
/// these pin the sealing rules the stores apply to every value.
struct SealedKeychainValuesTests {
    private let historyKey = SymmetricKey(size: .bits256)

    // MARK: - Identity

    @Test
    func identityPrivateRoundTripsOnlyUnderTheHistoryKey() throws {
        let raw = Curve25519.KeyAgreement.PrivateKey().rawRepresentation
        let sealed = try IdentityKeyStore.sealPrivate(raw, historyKey: historyKey)

        #expect(LocalHistoryCrypto.isSealedBlob(sealed))
        #expect(sealed.range(of: raw) == nil)
        #expect(IdentityKeyStore.openPrivate(sealed, historyKey: historyKey) == raw)
        #expect(IdentityKeyStore.openPrivate(sealed, historyKey: SymmetricKey(size: .bits256)) == nil)
    }

    @Test
    func tamperedIdentityValueIsNeverReadAsPlaintext() throws {
        let raw = Curve25519.Signing.PrivateKey().rawRepresentation
        var sealed = try IdentityKeyStore.sealPrivate(raw, historyKey: historyKey)
        sealed[sealed.count - 1] ^= 0x01
        #expect(IdentityKeyStore.openPrivate(sealed, historyKey: historyKey) == nil)
    }

    /// Older builds wrote raw key bytes; the unlock reads them once and re-saves them sealed.
    @Test
    func legacyPlaintextIdentityValueIsReadForMigration() {
        let raw = Curve25519.KeyAgreement.PrivateKey().rawRepresentation
        #expect(IdentityKeyStore.openPrivate(raw, historyKey: historyKey) == raw)
    }

    // MARK: - Ratchet

    private func session() throws -> DoubleRatchet.Session {
        try DoubleRatchet.Session.initiateAsSender(
            ourPrivate: Curve25519.KeyAgreement.PrivateKey(),
            theirIdentityPublic: Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        )
    }

    @Test
    func ratchetSessionRoundTripsOnlyUnderTheHistoryKey() throws {
        let original = try session()
        let sealed = try RatchetSessionStore.seal(original, historyKey: historyKey)

        #expect(LocalHistoryCrypto.isSealedBlob(sealed))
        #expect(sealed.range(of: try JSONEncoder().encode(original)) == nil)
        let opened = try #require(RatchetSessionStore.openStored(sealed, historyKey: historyKey))
        #expect(opened.session == original)
        #expect(!opened.wasPlaintext)
        #expect(RatchetSessionStore.openStored(sealed, historyKey: SymmetricKey(size: .bits256)) == nil)
    }

    @Test
    func legacyPlaintextSessionIsFlaggedForResealing() throws {
        let original = try session()
        let json = try JSONEncoder().encode(original)
        let opened = try #require(RatchetSessionStore.openStored(json, historyKey: historyKey))
        #expect(opened.session == original)
        #expect(opened.wasPlaintext)
    }

    /// The unlock reads only items the marker does not vouch for: older plaintext, and sealed
    /// items written before the marker existed.
    @Test
    func unlockReadsOnlyUnmarkedRatchetItems() {
        let rows: [[String: Any]] = [
            [kSecAttrAccount as String: "sealed", kSecAttrGeneric as String: RatchetSessionStore.sealedMarker],
            [kSecAttrAccount as String: "legacy"],
            [kSecAttrAccount as String: "other", kSecAttrGeneric as String: Data("x".utf8)],
        ]
        #expect(RatchetSessionStore.accountsWithoutSealedMarker(rows) == ["legacy", "other"])
    }

    /// Distinct HKDF info strings: a value sealed for one store does not open as another.
    @Test
    func storesUseSeparateSubkeys() throws {
        let json = try JSONEncoder().encode(try session())
        let asIdentity = try IdentityKeyStore.sealPrivate(json, historyKey: historyKey)
        #expect(RatchetSessionStore.openStored(asIdentity, historyKey: historyKey) == nil)
        let asRatchet = try RatchetSessionStore.seal(try session(), historyKey: historyKey)
        #expect(IdentityKeyStore.openPrivate(asRatchet, historyKey: historyKey) == nil)
        #expect(TranscriptionLanguageMemory.open(asRatchet, historyKey: historyKey) == nil)
    }
}
