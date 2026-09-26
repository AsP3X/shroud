import CryptoKit
import UserNotifications
import XCTest

@testable import shroud

/// The push payload the server builds (`server/.../push/payload.rs`) and how the notification
/// extension words it. The sealed-name vector is the one the server's own tests open.
@MainActor
final class NotificationPayloadTests: XCTestCase {
    private let key = SymmetricKey(data: Data(repeating: 42, count: 32))
    private let thread = "6f9619ff-8b86-4d01-b42d-00c04fc964ff"
    private let peer = "5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b"
    private let vector = "AAECAwQFBgcICQoLNw86aqYBQlL3UGPJr5ruy3y0NJPwNYGMO+xknak="

    func testOpensTheServersSealedName() {
        XCTAssertEqual(
            NotificationPayload.openName(vector, key: key, kind: .message, thread: thread, peer: peer),
            "alice"
        )
    }

    func testASealedNameIsBoundToItsKindThreadAndPeer() {
        XCTAssertNil(NotificationPayload.openName(vector, key: key, kind: .reaction, thread: thread, peer: peer))
        XCTAssertNil(
            NotificationPayload.openName(vector, key: key, kind: .message, thread: "another-chat", peer: peer)
        )
        XCTAssertNil(
            NotificationPayload.openName(vector, key: key, kind: .message, thread: thread, peer: UUID().uuidString)
        )
        XCTAssertNil(
            NotificationPayload.openName(
                vector,
                key: SymmetricKey(size: .bits256),
                kind: .message,
                thread: thread,
                peer: peer
            )
        )
        XCTAssertNil(
            NotificationPayload.openName("not base64!", key: key, kind: .message, thread: thread, peer: peer)
        )
    }

    func testParsesTheAppPart() throws {
        let conversation = UUID()
        let peer = UUID()
        let message = UUID()
        let contents = try XCTUnwrap(NotificationPayload.parse([
            "aps": ["alert": ["body": "New message"]],
            "shroud": [
                "v": 1,
                "k": "message",
                "c": conversation.uuidString.lowercased(),
                "p": peer.uuidString.lowercased(),
                "m": message.uuidString.lowercased(),
                "e": vector,
            ],
        ]))
        XCTAssertEqual(contents.kind, .message)
        XCTAssertEqual(contents.conversationID, conversation)
        XCTAssertEqual(contents.peerUserID, peer)
        XCTAssertEqual(contents.messageID, message)
        XCTAssertEqual(contents.sealedName, vector)
        XCTAssertNil(NotificationPayload.parse(["aps": [:]]), "not one of ours")
        XCTAssertNil(NotificationPayload.parse(["shroud": ["k": "future_kind"]]), "an unknown kind")
        XCTAssertEqual(NotificationPayload.parse(["shroud": ["k": "video_call"]])?.kind, .videoCall)
    }

    func testDressNamesTheSenderAndNeverShowsText() {
        let content = UNMutableNotificationContent()
        content.body = "New message"
        content.threadIdentifier = thread
        content.userInfo = ["shroud": ["k": "message", "p": peer, "e": vector]]
        NotificationPayload.dress(content, key: key)
        XCTAssertEqual(content.title, "alice")
        XCTAssertEqual(content.body, "New message")

        // No key on this iPhone (before the first unlock): the push stays as sent.
        let locked = UNMutableNotificationContent()
        locked.threadIdentifier = thread
        locked.userInfo = ["shroud": ["k": "reaction", "p": peer, "e": vector]]
        NotificationPayload.dress(locked, key: nil)
        XCTAssertEqual(locked.title, "")
        XCTAssertEqual(locked.body, "Reacted to your message")
    }

    func testBodiesForEveryKind() {
        XCTAssertEqual(NotificationPayload.body(for: .contactRequest), "Wants to add you as a contact")
        XCTAssertEqual(NotificationPayload.body(for: .call), "Incoming call")
        XCTAssertEqual(NotificationPayload.body(for: .videoCall), "Incoming video call")
        XCTAssertEqual(NotificationPayload.body(for: .callEnded), "Call ended")
        XCTAssertEqual(NotificationPayload.body(for: .test), "Notifications are working")
    }
}

/// Mutes, notification settings and the badge.
@MainActor
final class NotificationSettingsTests: XCTestCase {
    private func freshDefaults() -> UserDefaults {
        let name = "notification-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    func testDefaultsAndServerPatch() {
        let preferences = NotificationPreferences(defaults: freshDefaults())
        XCTAssertTrue(preferences.enabled)
        XCTAssertTrue(preferences.showSender)
        XCTAssertTrue(preferences.showPreview)
        XCTAssertFalse(preferences.badgeIncludesMuted)
        XCTAssertEqual(preferences.sound, .standard)
        let patch = preferences.serverPatch
        XCTAssertEqual(patch.sound, "default")
        XCTAssertEqual(patch.enabled, true)
        XCTAssertEqual(patch.badgeIncludesMuted, false)
    }

    func testPreferencesPersistAndReset() {
        let defaults = freshDefaults()
        let preferences = NotificationPreferences(defaults: defaults)
        preferences.sound = .chime
        preferences.showSender = false
        preferences.inAppBanners = false
        let reloaded = NotificationPreferences(defaults: defaults)
        XCTAssertEqual(reloaded.sound, .chime)
        XCTAssertFalse(reloaded.showSender)
        XCTAssertFalse(reloaded.inAppBanners)
        XCTAssertEqual(reloaded.serverPatch.sound, "chime")
        reloaded.reset()
        XCTAssertEqual(reloaded.sound, .standard)
        XCTAssertTrue(reloaded.showSender)
        XCTAssertTrue(NotificationPreferences(defaults: defaults).inAppBanners)
        XCTAssertTrue(
            defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix("notifications.") }.isEmpty,
            "a reset stores nothing, like a fresh install (the logout wipe checks for leftovers)"
        )
        reloaded.badge = false
        XCTAssertFalse(NotificationPreferences(defaults: defaults).badge, "changes persist again after a reset")
    }

    func testPatchEncodesOnlyWhatIsSet() throws {
        let data = try JSONEncoder().encode(NotificationSettingsPatch(reactions: false))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(object.count, 1)
        XCTAssertEqual(object["reactions"] as? Bool, false)
        let muteForever = try JSONSerialization.jsonObject(with: JSONEncoder().encode(MuteChatBody(seconds: nil)))
        XCTAssertTrue((muteForever as? [String: Any])?["seconds"] is NSNull, "no end is sent as null")
    }

    func testMuteLabels() {
        let now = Date(timeIntervalSince1970: 1_790_000_000)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        XCTAssertNil(MuteDuration.label(for: nil, now: now, calendar: calendar))
        XCTAssertEqual(MuteDuration.label(for: ChatMuteDTO(until: nil), now: now, calendar: calendar), "Muted")
        XCTAssertNil(
            MuteDuration.label(for: ChatMuteDTO(until: now.addingTimeInterval(-60)), now: now, calendar: calendar),
            "a mute that ran out is off"
        )
        XCTAssertTrue(
            MuteDuration.label(for: ChatMuteDTO(until: now.addingTimeInterval(3 * 86400)), now: now, calendar: calendar)?
                .hasPrefix("Muted until ") ?? false
        )
        XCTAssertEqual(MuteDuration.forever.seconds, nil)
        XCTAssertEqual(MuteDuration.eightHours.seconds, 28_800)
    }

    func testConversationDecodesUnreadCountAndMute() throws {
        let json = """
        {"id":"\(UUID().uuidString)","peer":{"id":"\(UUID().uuidString)","username":"alice"},
         "created_at":"2026-09-24T10:00:00Z","last_message_at":"2026-09-24T11:00:00.123456Z",
         "reaction_seq":0,"unseen_reactions":0,"unread_count":3,
         "mute":{"until":"2026-09-24T19:00:00.5Z"}}
        """
        let item = try JSONDecoder.api.decode(ConversationItemDTO.self, from: Data(json.utf8))
        XCTAssertEqual(item.unreadCount, 3)
        XCTAssertNotNil(item.mute?.until)
        let unmuted = try JSONDecoder.api.decode(
            ConversationItemDTO.self,
            from: Data(json.replacingOccurrences(of: #""mute":{"until":"2026-09-24T19:00:00.5Z"}"#, with: #""mute":null"#).utf8)
        )
        XCTAssertNil(unmuted.mute)
        // Older servers send neither.
        let old = try JSONDecoder.api.decode(
            ConversationItemDTO.self,
            from: Data("""
            {"id":"\(UUID().uuidString)","peer":{"id":"\(UUID().uuidString)","username":"bob"},
             "created_at":"2026-09-24T10:00:00Z"}
            """.utf8)
        )
        XCTAssertNil(old.unreadCount)
        XCTAssertNil(old.mute)
    }

    func testBundledSoundsExist() {
        for sound in NotificationSound.allCases where sound != .standard && sound != .none {
            XCTAssertNotNil(sound.fileURL, "\(sound.rawValue).wav is in the app bundle")
        }
        XCTAssertNil(NotificationSound.none.notificationSound)
    }
}
