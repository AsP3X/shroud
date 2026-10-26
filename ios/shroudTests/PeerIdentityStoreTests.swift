import Foundation
import Security
import Testing
@testable import shroud

struct PeerIdentityStoreTests {
    @Test
    func roundTripAndClear() {
        let store = PeerIdentityStore(service: "com.shroud.peer-identity.test." + UUID().uuidString)
        let userID = UUID()
        let key = Data(repeating: 7, count: 32)
        #expect(store.pin(for: userID) == .notPinned)
        store.save(userID: userID, publicKeyBase64: key.base64EncodedString())
        #expect(store.pin(for: userID) == .pinned(key))
        store.clear()
        #expect(store.pin(for: userID) == .notPinned)
    }

    /// A new key replaces the pin in place (update, not delete-then-add).
    @Test
    func saveReplacesThePin() {
        let store = PeerIdentityStore(service: "com.shroud.peer-identity.test." + UUID().uuidString)
        let userID = UUID()
        let first = Data(repeating: 7, count: 32)
        let second = Data(repeating: 8, count: 32)
        store.save(userID: userID, publicKeyBase64: first.base64EncodedString())
        store.save(userID: userID, publicKeyBase64: second.base64EncodedString())
        #expect(store.pin(for: userID) == .pinned(second))
        store.setVerified(userID, true)
        #expect(store.isVerified(userID))
        store.setVerified(userID, false)
        #expect(!store.isVerified(userID))
        store.clear()
    }

    @Test
    func migratesLegacyUserDefaults() {
        let unique = UUID().uuidString
        let suite = "com.shroud.peer-identity.defaults." + unique
        let defaults = UserDefaults(suiteName: suite)!
        let userID = UUID()
        let key = Data(repeating: 7, count: 32)
        defaults.set(key.base64EncodedString(), forKey: "peer_identity_pub." + userID.uuidString.lowercased())
        let store = PeerIdentityStore(
            service: "com.shroud.peer-identity.test." + unique,
            defaults: defaults
        )
        #expect(store.pin(for: userID) == .pinned(key))
        #expect(defaults.string(forKey: "peer_identity_pub." + userID.uuidString.lowercased()) == nil)
        store.clear()
        defaults.removePersistentDomain(forName: suite)
    }

    /// A locked phone's refusal is never "no pin": only `errSecItemNotFound` lets TOFU in
    /// (security audit M8).
    @Test
    func onlyItemNotFoundReadsAsNoPin() {
        let stored = Data("pin".utf8)
        #expect(PeerIdentityStore.keychainRead(status: errSecSuccess, data: stored) == .found("pin"))
        #expect(PeerIdentityStore.keychainRead(status: errSecItemNotFound, data: nil) == .notFound)
        #expect(PeerIdentityStore.keychainRead(status: errSecInteractionNotAllowed, data: nil) == .unavailable)
        #expect(PeerIdentityStore.keychainRead(status: errSecAuthFailed, data: nil) == .unavailable)
        #expect(PeerIdentityStore.keychainRead(status: errSecNotAvailable, data: nil) == .unavailable)
        // An item that is there but unreadable is no proof of "no pin" either.
        #expect(PeerIdentityStore.keychainRead(status: errSecSuccess, data: nil) == .unavailable)
        #expect(PeerIdentityStore.keychainRead(status: errSecSuccess, data: Data([0xC3, 0x28])) == .unavailable)
    }

    @Test
    func onlyAPinnedReadHasAKey() {
        let key = Data(repeating: 7, count: 32)
        #expect(PeerIdentityStore.PinRead.pinned(key).key == key)
        #expect(PeerIdentityStore.PinRead.notPinned.key == nil)
        #expect(PeerIdentityStore.PinRead.unavailable.key == nil)
    }
}
