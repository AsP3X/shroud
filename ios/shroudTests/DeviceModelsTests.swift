import Foundation
import Testing
@testable import shroud

struct DeviceModelsTests {
    @Test
    func devicesListDecodesServerShape() throws {
        // The server omits `name` and `last_seen_at` when null and sends fractional seconds.
        let json = """
        {
          "devices": [
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "name": "iPhone 17 Pro",
              "created_at": "2026-09-20T10:15:00.123456Z",
              "last_seen_at": "2026-09-23T08:00:00Z",
              "is_current": true
            },
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "created_at": "2026-09-21T10:15:00Z",
              "is_current": false
            }
          ]
        }
        """.data(using: .utf8)!

        let decoded = try JSONDecoder.api.decode(DevicesListResponse.self, from: json)
        #expect(decoded.devices.count == 2)
        #expect(decoded.devices[0].isCurrent)
        #expect(decoded.devices[0].lastSeenAt != nil)
        #expect(decoded.devices[1].name == nil)
        #expect(decoded.devices[1].lastSeenAt == nil)
        #expect(!decoded.devices[1].isCurrent)
    }
}
