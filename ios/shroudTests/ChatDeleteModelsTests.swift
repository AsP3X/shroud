import Foundation
import Testing
@testable import shroud

/// Guards the hand-mirrored contract between the Rust DTOs and their Swift counterparts:
/// `DeleteConversationResponse`, `PrivacySettingsDTO`, and `BlockItemDTO`. A rename on either
/// side has to fail here rather than silently decode into defaults at runtime.
struct ChatDeleteModelsTests {
    @Test
    func deleteConversationResponseDecodesSnakeCaseFields() throws {
        let json = """
        {
          "cleared_for_me": true,
          "cleared_for_peer": false,
          "tombstoned": 3,
          "contact_removed": true
        }
        """.data(using: .utf8)!

        let decoded = try JSONDecoder.api.decode(DeleteConversationResponse.self, from: json)
        #expect(decoded.clearedForMe)
        // Peer withheld consent: our messages became tombstones instead of vanishing.
        #expect(decoded.clearedForPeer == false)
        #expect(decoded.tombstoned == 3)
        #expect(decoded.contactRemoved)
    }

    @Test
    func conversationDeleteScopeMatchesServerQueryValues() {
        #expect(ConversationDeleteScope.me.rawValue == "me")
        #expect(ConversationDeleteScope.everyone.rawValue == "everyone")
    }

    @Test
    func privacySettingsRoundTripsThroughTheAPICoders() throws {
        let json = """
        { "allow_peer_chat_delete": true }
        """.data(using: .utf8)!

        let decoded = try JSONDecoder.api.decode(PrivacySettingsDTO.self, from: json)
        #expect(decoded.allowPeerChatDelete)

        let encoded = try JSONEncoder.api.encode(UpdatePrivacySettingsBody(allowPeerChatDelete: false))
        let body = try #require(String(data: encoded, encoding: .utf8))
        #expect(body.contains("allow_peer_chat_delete"))
        #expect(body.contains("false"))
    }

    @Test
    func visibilitySwitchesDecodeAndDefaultToOnForOlderServers() throws {
        let current = """
        { "allow_peer_chat_delete": false, "send_read_receipts": false, "send_typing": true, "share_presence": false }
        """.data(using: .utf8)!
        let decoded = try JSONDecoder.api.decode(PrivacySettingsDTO.self, from: current)
        #expect(!decoded.sendReadReceipts)
        #expect(decoded.sendTyping)
        #expect(!decoded.sharePresence)

        // A server from before the switches enforces none of them, which is "all on".
        let older = try JSONDecoder.api.decode(
            PrivacySettingsDTO.self,
            from: #"{ "allow_peer_chat_delete": true }"#.data(using: .utf8)!
        )
        #expect(older.sendReadReceipts && older.sendTyping && older.sharePresence && older.discoverableByUsername)
    }

    @Test
    func discoverabilityAndNewShareCodeDecode() throws {
        let settings = try JSONDecoder.api.decode(
            PrivacySettingsDTO.self,
            from: #"{ "allow_peer_chat_delete": false, "discoverable_by_username": false }"#.data(using: .utf8)!
        )
        #expect(!settings.discoverableByUsername)
        let rotated = try JSONDecoder.api.decode(ShareCodeDTO.self, from: #"{ "share_code": "ABCD234567" }"#.data(using: .utf8)!)
        #expect(rotated.shareCode == "ABCD234567")
    }

    @Test
    func privacyUpdateSendsOnlyTheChangedSwitch() throws {
        let encoded = try JSONEncoder.api.encode(UpdatePrivacySettingsBody(sharePresence: false))
        let body = try #require(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        #expect(body.count == 1)
        #expect(body["share_presence"] as? Bool == false)
    }

    @Test
    func blockListDecodesUserIdAndTimestamp() throws {
        let json = """
        {
          "blocks": [
            {
              "user_id": "33333333-3333-3333-3333-333333333333",
              "username": "mallory",
              "created_at": "2026-08-07T10:11:12Z"
            }
          ]
        }
        """.data(using: .utf8)!

        let decoded = try JSONDecoder.api.decode(BlocksListResponse.self, from: json)
        let first = try #require(decoded.blocks.first)
        #expect(first.username == "mallory")
        #expect(first.userId.uuidString.lowercased() == "33333333-3333-3333-3333-333333333333")
        // Identifiable id is the user id, so SwiftUI rows stay stable across refreshes.
        #expect(first.id == first.userId)
    }
}
