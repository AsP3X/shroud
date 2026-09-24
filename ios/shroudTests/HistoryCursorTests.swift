import Foundation
import Testing
@testable import shroud

/// History pages ask for messages older than a cursor. `Date` keeps milliseconds only, so the
/// cursor has to be the server's `created_at` text.
struct HistoryCursorTests {
    @Test func messageCreatedAtWireIsTheServerText() throws {
        let id = UUID()
        let created = "2026-09-24T12:00:00.123456Z"
        let json = """
        {
          "id": "\(id.uuidString)",
          "conversation_id": "\(UUID().uuidString)",
          "sender_user_id": "\(UUID().uuidString)",
          "sender_device_id": "\(UUID().uuidString)",
          "client_message_id": "\(UUID().uuidString)",
          "content_type": "text",
          "ciphertext": null,
          "deleted_for_everyone": false,
          "created_at": "\(created)"
        }
        """.data(using: .utf8)!
        let dto = try JSONDecoder.api.decode(MessageDTO.self, from: json)
        #expect(dto.createdAtWire == created)
        #expect(ISO8601DateFormatter.string(fromAPI: dto.createdAt) == "2026-09-24T12:00:00.123Z")
    }
}
