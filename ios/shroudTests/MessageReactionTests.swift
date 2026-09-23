import CoreGraphics
import CryptoKit
import Foundation
import Testing
@testable import shroud

/// Reactions: the sealed wire payload (shared with the web client), the last-write-wins merge
/// that keeps late or stale changes from resurrecting a reaction, and the chip footer geometry.
@MainActor
struct MessageReactionTests {
    private let me = UUID()
    private let peer = UUID()

    private func entry(_ user: UUID, _ emojis: [String], _ seq: Int64, pending: Bool = false) -> MessageReaction {
        MessageReaction(userID: user, emojis: emojis, seq: seq, pending: pending)
    }

    // MARK: - Wire format

    @Test
    func payloadRoundTripsAndBindsTheMessage() throws {
        let message = UUID()
        let data = try JSONEncoder().encode(MessageReactionPayload.make(["🔥", "👍"], for: message))
        let json = try #require(String(data: data, encoding: .utf8))
        #expect(json.contains("\"t\":\"reaction\""))
        #expect(json.contains(message.uuidString.lowercased()))
        #expect(MessageReactionPayload.parse(data, for: message) == ["🔥", "👍"])
        // A genuine record moved onto another message by the server is dropped.
        #expect(MessageReactionPayload.parse(data, for: UUID()) == nil)
    }

    @Test
    func webPayloadParses() throws {
        let message = UUID()
        let json = #"{"t":"reaction","r":"\#(message.uuidString.lowercased())","e":["❤️","👍","❤️","ok"]}"#
        // Each emoji once, in order; anything that isn't one emoji is dropped.
        #expect(MessageReactionPayload.parse(Data(json.utf8), for: message) == ["❤️", "👍"])
        let flood = try JSONEncoder().encode(MessageReactionPayload.make(MessageReactionBar.expanded, for: message))
        #expect(MessageReactionPayload.parse(flood, for: message)?.count == MessageReactionPayload.readerCap)
        #expect(MessageReactionPayload.parse(Data(#"{"t":"transcript","r":"x","c":"y"}"#.utf8), for: message) == nil)
    }

    @Test
    func onlySingleEmojiAreAccepted() {
        for emoji in ["❤️", "🔥", "👍🏽", "❤️‍🔥", "👨‍💻", "🇩🇪", "1️⃣", "🫡"] {
            #expect(MessageReactionPayload.isSingleEmoji(emoji), "\(emoji)")
        }
        for text in ["", "a", "1", "ok", "🔥🔥", "❤", " 👍", String(repeating: "👍", count: 9)] {
            #expect(!MessageReactionPayload.isSingleEmoji(text), "\(text)")
        }
    }

    @Test
    func sealedReactionOpensForThePeerAndOurOtherDevices() throws {
        let ours = Curve25519.KeyAgreement.PrivateKey()
        let theirs = Curve25519.KeyAgreement.PrivateKey()
        let message = UUID()
        let plaintext = try JSONEncoder().encode(MessageReactionPayload.make(["😮"], for: message))
        let sealed = try MessageCrypto.seal(
            plaintext: plaintext,
            toPeerIdentityPublicKey: theirs.publicKey.rawRepresentation,
            ourPrivateKey: ours,
            ourIdentityPublicKey: ours.publicKey.rawRepresentation
        )
        let forPeer = try MessageCrypto.openTagged(
            envelopeData: sealed,
            with: theirs,
            ourIdentityPublicKey: theirs.publicKey.rawRepresentation,
            senderIdentityPublicKey: ours.publicKey.rawRepresentation,
            as: .recipient
        )
        let forUs = try MessageCrypto.openTagged(
            envelopeData: sealed,
            with: ours,
            ourIdentityPublicKey: ours.publicKey.rawRepresentation,
            senderIdentityPublicKey: ours.publicKey.rawRepresentation,
            as: .sender
        )
        #expect(MessageReactionPayload.parse(forPeer, for: message) == ["😮"])
        #expect(MessageReactionPayload.parse(forUs, for: message) == ["😮"])
    }

    // MARK: - Merge

    @Test
    func newerChangeWinsAndOlderIsIgnored() throws {
        let held = [entry(peer, ["❤️"], 5)]
        let replaced = try #require(ReactionMerge.apply(entry(peer, ["🔥"], 7), to: held))
        #expect(replaced == [entry(peer, ["🔥"], 7)])
        #expect(ReactionMerge.apply(entry(peer, ["👍"], 6), to: replaced) == nil, "a late event is stale")
        #expect(ReactionMerge.apply(entry(peer, ["🔥"], 7), to: replaced) == nil, "a replay changes nothing")
    }

    @Test
    func removalIsKeptSoAnOlderSetCannotResurrectIt() throws {
        let removed = try #require(ReactionMerge.apply(entry(peer, [], 9), to: [entry(peer, ["❤️"], 5)]))
        #expect(ReactionMerge.chips(removed, me: me).isEmpty)
        #expect(ReactionMerge.apply(entry(peer, ["❤️"], 5), to: removed) == nil)
    }

    @Test
    func pendingChangeOfOursOutlivesServerEvents() {
        let held = [entry(me, ["👍"], 3, pending: true)]
        // Our other device's older state arriving over the socket must not undo the tap.
        #expect(ReactionMerge.apply(entry(me, ["😢"], 8), to: held) == nil)
        let confirmed = ReactionMerge.replacing(me, with: entry(me, ["👍"], 10), in: held)
        #expect(confirmed == [entry(me, ["👍"], 10)])
    }

    @Test
    func pageReconcileDropsWhatThePageNoLongerLists() {
        let held = [entry(peer, ["❤️"], 4), entry(me, ["👍"], 12)]
        // Snapshot 10: the peer's heart is gone by then; our 12 happened after the page was read.
        let result = ReactionMerge.reconcile(held: held, page: [], snapshot: 10)
        #expect(result == [entry(me, ["👍"], 12)])
    }

    @Test
    func pageReconcileTakesThePageUpToItsSnapshot() {
        let held = [entry(peer, ["❤️"], 4), entry(me, [], 6)]
        let page = [entry(peer, ["🔥"], 9)]
        #expect(ReactionMerge.reconcile(held: held, page: page, snapshot: 9) == page)
        let pending = entry(me, ["😮"], 6, pending: true)
        #expect(ReactionMerge.reconcile(held: [pending], page: page, snapshot: 9) == [entry(peer, ["🔥"], 9), pending])
    }

    @Test
    func pageEntryNewerThanItsSnapshotBeatsAnOlderHeldOne() {
        // The server reads the snapshot before the page: the page may carry seq 12 > 10.
        let held = [entry(peer, ["❤️"], 11)]
        let page = [entry(peer, ["🔥"], 12)]
        #expect(ReactionMerge.reconcile(held: held, page: page, snapshot: 10) == page)
        // …while a held entry newer than both still wins.
        #expect(ReactionMerge.reconcile(held: [entry(peer, ["😮"], 13)], page: page, snapshot: 10) == [entry(peer, ["😮"], 13)])
    }

    @Test
    func taggedOpenRefusesUntaggedAndForeignBoxes() throws {
        let ours = Curve25519.KeyAgreement.PrivateKey()
        let theirs = Curve25519.KeyAgreement.PrivateKey()
        let sealed = try MessageCrypto.seal(
            plaintext: Data("x".utf8),
            toPeerIdentityPublicKey: theirs.publicKey.rawRepresentation,
            ourPrivateKey: ours,
            ourIdentityPublicKey: ours.publicKey.rawRepresentation
        )
        // Strip the tag: what the server could build from public keys alone.
        var json = try #require(JSONSerialization.jsonObject(with: sealed) as? [String: Any])
        var peerBox = try #require(json["peer"] as? [String: Any])
        peerBox.removeValue(forKey: "t")
        json["peer"] = peerBox
        let untagged = try JSONSerialization.data(withJSONObject: json)
        #expect(throws: MessageCrypto.CryptoError.self) {
            try MessageCrypto.openTagged(
                envelopeData: untagged,
                with: theirs,
                ourIdentityPublicKey: theirs.publicKey.rawRepresentation,
                senderIdentityPublicKey: ours.publicKey.rawRepresentation,
                as: .recipient
            )
        }
        // A genuine box claimed to be from someone else does not open either.
        let stranger = Curve25519.KeyAgreement.PrivateKey()
        #expect(throws: (any Error).self) {
            try MessageCrypto.openTagged(
                envelopeData: sealed,
                with: theirs,
                ourIdentityPublicKey: theirs.publicKey.rawRepresentation,
                senderIdentityPublicKey: stranger.publicKey.rawRepresentation,
                as: .recipient
            )
        }
    }

    @Test
    func onePersonsReactionsShareOneChip() {
        // Several emoji by one person: one chip, not one per emoji.
        let chips = ReactionMerge.chips([entry(peer, ["❤️", "🔥", "👍"], 2), entry(me, ["😮"], 3)], me: me)
        #expect(chips.map(\.emojis) == [["❤️", "🔥", "👍"], ["😮"]])
        #expect(chips.map(\.includesMe) == [false, true])

        // The same emoji picked by both people: one chip with both faces.
        let shared = ReactionMerge.chips([entry(peer, ["❤️", "🔥"], 2), entry(me, ["🔥", "❤️"], 3)], me: me)
        #expect(shared.count == 1)
        #expect(shared[0].userIDs == [peer, me])
        #expect(shared[0].includesMe)

        // The other side's chip first, ours after, whoever reacted first.
        let order = ReactionMerge.chips([entry(me, ["👍"], 1), entry(peer, ["🔥"], 2)], me: me)
        #expect(order.map(\.emojis) == [["🔥"], ["👍"]])
        #expect(ReactionMerge.emojis(of: me, in: [entry(me, ["👍", "🔥"], 1)]) == ["👍", "🔥"])
    }

    @Test
    func pickingTogglesAndTheLimitDropsTheOldest() {
        #expect(ReactionMerge.toggled("❤️", in: [], limit: 5) == ["❤️"])
        #expect(ReactionMerge.toggled("🔥", in: ["❤️"], limit: 5) == ["❤️", "🔥"])
        #expect(ReactionMerge.toggled("❤️", in: ["❤️", "🔥"], limit: 5) == ["🔥"], "a second pick takes it back")
        let full = ["❤️", "🔥", "👍", "😮", "🙏"]
        #expect(ReactionMerge.toggled("🎉", in: full, limit: 5) == ["🔥", "👍", "😮", "🙏", "🎉"])
        // A limit lowered on the server trims on the next pick, never before.
        #expect(ReactionMerge.toggled("🎉", in: full, limit: 3) == ["😮", "🙏", "🎉"])
        #expect(ReactionMerge.toggled("❤️", in: full, limit: 3) == ["🔥", "👍", "😮", "🙏"])
        #expect(ReactionMerge.toggled("❤️", in: [], limit: 0) == ["❤️"], "never below one")
    }

    @Test
    func storedMessagesWithoutReactionsStillDecode() throws {
        let message = MessagingController.ChatMessage(
            id: UUID(), peerUserID: peer, senderUserID: peer, text: "hi",
            createdAt: Date(timeIntervalSince1970: 1_700_000_000), isMine: false, deleted: false,
            reactions: [entry(peer, ["❤️"], 4), entry(me, [], 5)]
        )
        let stored = LocalMessageStore.StoredMessage.from(message)
        let data = try JSONEncoder().encode(stored)
        let decoded = try JSONDecoder().decode(LocalMessageStore.StoredMessage.self, from: data)
        #expect(decoded.reactions == message.reactions)

        var legacy = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        legacy.removeValue(forKey: "reactions")
        let old = try JSONDecoder().decode(
            LocalMessageStore.StoredMessage.self,
            from: JSONSerialization.data(withJSONObject: legacy)
        )
        #expect(old.reactions == nil)
    }

    // MARK: - Footer geometry

    private let chip = CGSize(width: 50, height: 26)
    private let meta = CGSize(width: 44, height: 13)

    @Test
    func timeSitsAtTheEndOfTheChipRowWhenItFits() {
        let result = ReactionFooterLayout.arrange(chips: [chip, chip], meta: meta, width: 200)
        #expect(result.frames[0].origin == CGPoint(x: 0, y: 0))
        #expect(result.frames[1].origin == CGPoint(x: CGFloat(56), y: 0))
        // One row: two chips, a gap, the time.
        #expect(result.size == CGSize(width: CGFloat(50 + 6 + 50 + 8 + 44), height: CGFloat(26)))
        #expect(result.frames[2].maxY <= CGFloat(26))
    }

    @Test
    func timeDropsToItsOwnLineWhenTheRowIsFull() {
        let result = ReactionFooterLayout.arrange(chips: [chip, chip], meta: meta, width: 120)
        #expect(result.frames[2].origin == CGPoint(x: 0, y: CGFloat(26 + 6)))
        #expect(result.size.height == CGFloat(26 + 6 + 13))
    }

    @Test
    func chipsWrapAtTheBubbleWidth() {
        let result = ReactionFooterLayout.arrange(chips: [chip, chip, chip], meta: meta, width: 110)
        #expect(result.frames[2].origin == CGPoint(x: 0, y: CGFloat(32)))
        // The time fits after the third chip on the second row.
        #expect(result.frames[3].minY >= CGFloat(32))
        #expect(result.size.height == CGFloat(58))
        #expect(result.size.width <= CGFloat(110))
    }

    @Test
    func unboundedWidthIsOneLine() {
        let result = ReactionFooterLayout.arrange(chips: [chip, chip, chip], meta: meta, width: nil)
        #expect(result.size.height == CGFloat(26))
    }
}
