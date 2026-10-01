import Foundation
import Testing
@testable import shroud

/// `message.read` reaches the reader's other devices too (server `routes/messages.rs`
/// `publish_bulk_read`, `fanout_message_read`). Only the peer's reads tick our messages.
struct MessageReadEventTests {
    private let me = UUID(uuidString: "3F2C8A9E-5B1D-4C7A-9E2F-8D6B4A1C0E7F")!
    private let peer = UUID(uuidString: "9B1DEB4D-3B7D-4BAD-9BDD-2B0D7B3DCB6D")!

    private func event(reader: String?) -> [String: Any] {
        var json: [String: Any] = [
            "type": "message.read",
            "message_id": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
            "up_to_message_id": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
            "conversation_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            "read_at": "2026-10-01T12:00:00.000Z",
        ]
        if let reader { json["user_id"] = reader }
        return json
    }

    @Test
    func thePeersReadTicksOurMessages() {
        #expect(MessagingController.isPeerRead(event(reader: peer.uuidString.lowercased()), me: me))
    }

    /// Our iPad read the chat: our sent messages stay as they were on this iPhone.
    @Test
    func ourOwnReadOnAnotherDeviceIsIgnored() {
        #expect(!MessagingController.isPeerRead(event(reader: me.uuidString.lowercased()), me: me))
        #expect(!MessagingController.isPeerRead(event(reader: me.uuidString), me: me))
    }

    /// Without a reader or a session there is nothing to compare: read it as before.
    @Test
    func anEventWithoutAReaderCountsAsThePeers() {
        #expect(MessagingController.isPeerRead(event(reader: nil), me: me))
        #expect(MessagingController.isPeerRead(event(reader: "not-a-uuid"), me: me))
        #expect(MessagingController.isPeerRead(event(reader: me.uuidString.lowercased()), me: nil))
    }
}
