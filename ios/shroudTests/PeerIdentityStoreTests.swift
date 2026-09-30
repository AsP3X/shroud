import Foundation
import Testing
@testable import shroud

struct PeerIdentityStoreTests {
    @Test
    func roundTripAndClear() {
        let store = PeerIdentityStore(service: "com.shroud.peer-identity.test." + UUID().uuidString)
        let userID = UUID()
        let key = Data(repeating: 7, count: 32).base64EncodedString()
        store.save(userID: userID, publicKeyBase64: key)
        #expect(store.publicKeyBase64(for: userID) == key)
        store.clear()
        #expect(store.publicKeyBase64(for: userID) == nil)
    }

    @Test
    func migratesLegacyUserDefaults() {
        let unique = UUID().uuidString
        let suite = "com.shroud.peer-identity.defaults." + unique
        let defaults = UserDefaults(suiteName: suite)!
        let userID = UUID()
        let key = Data(repeating: 7, count: 32).base64EncodedString()
        defaults.set(key, forKey: "peer_identity_pub." + userID.uuidString.lowercased())
        let store = PeerIdentityStore(
            service: "com.shroud.peer-identity.test." + unique,
            defaults: defaults
        )
        #expect(store.publicKeyBase64(for: userID) == key)
        #expect(defaults.string(forKey: "peer_identity_pub." + userID.uuidString.lowercased()) == nil)
        store.clear()
        defaults.removePersistentDomain(forName: suite)
    }
}
