import CryptoKit
import UserNotifications
import XCTest

@testable import shroud

/// The push payload the server builds (`server/.../push/payload.rs`) and how the notification
/// extension opens it. The sealed vector and keyed thread are the ones the server's tests check.
@MainActor
final class NotificationPayloadTests: XCTestCase {
    private let key = SymmetricKey(data: Data(repeating: 42, count: 32))
    private let conversation = UUID(uuidString: "6f9619ff-8b86-4d01-b42d-00c04fc964ff")!
    private let peer = UUID(uuidString: "5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b")!
    private let message = UUID(uuidString: "0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a")!
    /// `{"c","m","n":"alice","p"}` sealed for kind `message` under `key`.
    private let vector = "AAECAwQFBgcICQoLNw83aqYBFVinBTfStNqou0/SpKKtf4lhBa3wMFNUwZ8m4GDt4J+mUCP0sEGhc2LF+GaWQNTrj2GddhUmvcgr7R5PrsSI5FckdDdV7tEv3Uln3FZzMr6L0A9qe7AcXq4YYIRyNsxd/8TO+UXY6DZmfMav6cjaKD+V50Y8Bg8kTczJ3oAypImUmpyPTau9ebND3youNvLvD9YlsvTVQHY="

    private func sealedPush(kind: String = "message") -> [AnyHashable: Any] {
        [
            "aps": ["alert": ["body": "New message"], "thread-id": "3c9ba47195443675c89152bc77587c65"],
            "shroud": ["v": 2, "k": kind, "e": vector],
        ]
    }

    func testOpensTheServersSeal() throws {
        let contents = try XCTUnwrap(NotificationPayload.parse(sealedPush(), key: { self.key }))
        XCTAssertEqual(contents.kind, .message)
        XCTAssertEqual(contents.conversationID, conversation)
        XCTAssertEqual(contents.peerUserID, peer)
        XCTAssertEqual(contents.messageID, message)
        XCTAssertNil(contents.callID)
        XCTAssertEqual(contents.senderName, "alice")
    }

    func testASealIsBoundToItsKindAndKey() throws {
        let asReaction = try XCTUnwrap(NotificationPayload.parse(sealedPush(kind: "reaction"), key: { self.key }))
        XCTAssertEqual(asReaction, NotificationPayload.Contents(kind: .reaction), "a seal never opens as another kind")
        let otherKey = try XCTUnwrap(
            NotificationPayload.parse(sealedPush(), key: { SymmetricKey(size: .bits256) })
        )
        XCTAssertEqual(otherKey, NotificationPayload.Contents(kind: .message))
        let noKey = try XCTUnwrap(NotificationPayload.parse(sealedPush(), key: { nil }))
        XCTAssertEqual(noKey, NotificationPayload.Contents(kind: .message), "without a key only the kind is known")
        XCTAssertNil(NotificationPayload.open("not base64!", key: key, kind: .message))
    }

    func testTheKeyedThreadMatchesTheServer() {
        XCTAssertEqual(NotificationPayload.threadID(for: conversation, key: key), "3c9ba47195443675c89152bc77587c65")
        XCTAssertNotEqual(
            NotificationPayload.threadID(for: conversation, key: SymmetricKey(size: .bits256)),
            "3c9ba47195443675c89152bc77587c65"
        )
    }

    func testParsesAnOpenAppPart() throws {
        let call = UUID()
        let contents = try XCTUnwrap(NotificationPayload.parse([
            "aps": ["alert": ["body": "Incoming call"]],
            "shroud": [
                "v": 1,
                "k": "call",
                "p": peer.uuidString.lowercased(),
                "call": call.uuidString.lowercased(),
            ],
        ], key: { XCTFail("an open push needs no key"); return nil }))
        XCTAssertEqual(contents.kind, .call)
        XCTAssertEqual(contents.peerUserID, peer)
        XCTAssertEqual(contents.callID, call)
        XCTAssertNil(contents.senderName)
        XCTAssertNil(NotificationPayload.parse(["aps": [:]]), "not one of ours")
        XCTAssertNil(NotificationPayload.parse(["shroud": ["k": "future_kind"]]), "an unknown kind")
        XCTAssertEqual(NotificationPayload.parse(["shroud": ["k": "video_call"]])?.kind, .videoCall)
    }

    func testDressOpensThePushForTheApp() throws {
        let content = UNMutableNotificationContent()
        content.body = "New message"
        content.threadIdentifier = "3c9ba47195443675c89152bc77587c65"
        content.userInfo = sealedPush()
        NotificationPayload.dress(content, key: key)
        XCTAssertEqual(content.title, "alice")
        XCTAssertEqual(content.body, "New message")
        XCTAssertEqual(content.threadIdentifier, conversation.uuidString.lowercased(), "grouped under the chat")
        let app = try XCTUnwrap(content.userInfo["shroud"] as? [String: Any])
        XCTAssertNil(app["e"], "the seal is replaced by what it held")
        XCTAssertNil(app["n"], "the name lives in the title only")
        let reopened = try XCTUnwrap(NotificationPayload.parse(content.userInfo, key: { nil }))
        XCTAssertEqual(reopened.conversationID, conversation)
        XCTAssertEqual(reopened.peerUserID, peer)
        XCTAssertEqual(reopened.messageID, message)

        // No key on this iPhone (before the first unlock): worded, but still sealed for the app.
        let locked = UNMutableNotificationContent()
        locked.threadIdentifier = "3c9ba47195443675c89152bc77587c65"
        locked.userInfo = sealedPush(kind: "reaction")
        NotificationPayload.dress(locked, key: nil)
        XCTAssertEqual(locked.title, "")
        XCTAssertEqual(locked.body, "Reacted to your message")
        XCTAssertEqual(locked.threadIdentifier, "3c9ba47195443675c89152bc77587c65")
        XCTAssertEqual((locked.userInfo["shroud"] as? [String: Any])?["e"] as? String, vector)
    }

    /// A push already in flight from the previous server: v1 ids in the clear, and `e` sealing
    /// only the name under the old associated data. The name is lost. The ids stay, and dressing
    /// the notification does not wipe them. `e` here is not the shared vector: that one would
    /// open under the v2 associated data and hide a parser that treated every `e` as v2.
    func testAnOldNameSealDoesNotDropTheIds() throws {
        let garbage = "bm90IGEgc2VhbA=="
        let info: [AnyHashable: Any] = [
            "shroud": [
                "v": 1,
                "k": "message",
                "c": conversation.uuidString.lowercased(),
                "p": peer.uuidString.lowercased(),
                "m": message.uuidString.lowercased(),
                "e": garbage,
            ] as [String: Any],
        ]
        let contents = try XCTUnwrap(NotificationPayload.parse(info, key: {
            XCTFail("an old push is not a v2 seal")
            return self.key
        }))
        XCTAssertEqual(contents.kind, .message)
        XCTAssertEqual(contents.conversationID, conversation)
        XCTAssertEqual(contents.peerUserID, peer)
        XCTAssertEqual(contents.messageID, message)
        XCTAssertNil(contents.senderName)

        let content = UNMutableNotificationContent()
        content.userInfo = info
        content.threadIdentifier = conversation.uuidString.lowercased()
        NotificationPayload.dress(content, key: nil)
        let dressed = try XCTUnwrap(NotificationPayload.parse(content.userInfo, key: { nil }))
        XCTAssertEqual(dressed.conversationID, conversation)
        XCTAssertEqual(dressed.peerUserID, peer)
        XCTAssertEqual(dressed.messageID, message)
        XCTAssertNil(dressed.senderName)
        XCTAssertNil((content.userInfo["shroud"] as? [String: Any])?["e"] as? String)

        // APNs boxes the version as a number. 2 still opens; 1 still keeps the ids.
        var app = try XCTUnwrap(info["shroud"] as? [String: Any])
        app["v"] = NSNumber(value: 2)
        app["e"] = vector
        var numbered = info
        numbered["shroud"] = app
        let opened = try XCTUnwrap(NotificationPayload.parse(numbered, key: { self.key }))
        XCTAssertEqual(opened.conversationID, conversation)
        XCTAssertEqual(opened.senderName, "alice")

        app["v"] = NSNumber(value: 1)
        app["e"] = garbage
        numbered["shroud"] = app
        let kept = try XCTUnwrap(NotificationPayload.parse(numbered, key: {
            XCTFail("v = 1 is not sealed")
            return nil
        }))
        XCTAssertEqual(kept.conversationID, conversation)
        XCTAssertEqual(kept.peerUserID, peer)
        XCTAssertEqual(kept.messageID, message)
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
