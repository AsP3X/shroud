import Foundation
import Testing
@testable import shroud

struct SessionStoreTests {
    @Test
    func deviceAnchorSurvivesSessionClear() throws {
        let store = SessionStore(service: "com.shroud.session.test." + UUID().uuidString)
        let userID = UUID()
        let deviceID = UUID()
        let session = SessionStore.Session(
            token: "tok",
            userID: userID,
            username: "alice",
            shareCode: nil,
            deviceID: deviceID
        )
        try store.save(session)
        store.saveDeviceAnchor(username: "Alice", deviceID: deviceID)
        store.clear()
        #expect(store.load() == nil)
        #expect(store.loadDeviceID(matchingUsername: "alice") == deviceID)
        #expect(store.loadDeviceID(matchingUsername: "bob") == nil)
        store.clearDeviceAnchor()
        #expect(store.loadDeviceID(matchingUsername: "alice") == nil)
    }
}
