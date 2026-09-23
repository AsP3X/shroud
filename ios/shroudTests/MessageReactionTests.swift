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

    private func entry(_ user: UUID, _ emoji: String?, _ seq: Int64, pending: Bool = false) -> MessageReaction {
        MessageReaction(userID: user, emoji: emoji, seq: seq, pending: pending)
    }

    // MARK: - Wire format

    @Test
    func payloadRoundTripsAndBindsTheMessage() throws {
        let message = UUID()
        let data = try JSONEncoder().encode(MessageReactionPayload.make("🔥", for: message))
        let json = try #require(String(data: data, encoding: .utf8))
        #expect(json.contains("\"t\":\"reaction\""))
        #expect(json.contains(message.uuidString.lowercased()))
        #expect(MessageReactionPayload.parse(data, for: message) == "🔥")
        // A genuine record moved onto another message by the server is dropped.
        #expect(MessageReactionPayload.parse(data, for: UUID()) == nil)
    }

    @Test
    func webPayloadParses() {
        let message = UUID()
        let json = #"{"t":"reaction","r":"\#(message.uuidString.lowercased())","e":["❤️","👍"]}"#
        // A newer build may send several; this one shows the first.
        #expect(MessageReactionPayload.parse(Data(json.utf8), for: message) == "❤️")
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
        let plaintext = try JSONEncoder().encode(MessageReactionPayload.make("😮", for: message))
        let sealed = try MessageCrypto.seal(
            plaintext: plaintext,
            toPeerIdentityPublicKey: theirs.publicKey.rawRepresentation,
            ourPrivateKey: ours,
            ourIdentityPublicKey: ours.publicKey.rawRepresentation
        )
        let forPeer = try MessageCrypto.open(
            envelopeData: sealed,
            with: theirs,
            ourIdentityPublicKey: theirs.publicKey.rawRepresentation,
            senderIdentityPublicKey: ours.publicKey.rawRepresentation,
            as: .recipient,
            sentAt: Date()
        )
        let forUs = try MessageCrypto.open(
            envelopeData: sealed,
            with: ours,
            ourIdentityPublicKey: ours.publicKey.rawRepresentation,
            senderIdentityPublicKey: ours.publicKey.rawRepresentation,
            as: .sender,
            sentAt: Date()
        )
        #expect(MessageReactionPayload.parse(forPeer, for: message) == "😮")
        #expect(MessageReactionPayload.parse(forUs, for: message) == "😮")
    }

    // MARK: - Merge

    @Test
    func newerChangeWinsAndOlderIsIgnored() throws {
        let held = [entry(peer, "❤️", 5)]
        let replaced = try #require(ReactionMerge.apply(entry(peer, "🔥", 7), to: held))
        #expect(replaced == [entry(peer, "🔥", 7)])
        #expect(ReactionMerge.apply(entry(peer, "👍", 6), to: replaced) == nil, "a late event is stale")
        #expect(ReactionMerge.apply(entry(peer, "🔥", 7), to: replaced) == nil, "a replay changes nothing")
    }

    @Test
    func removalIsKeptSoAnOlderSetCannotResurrectIt() throws {
        let removed = try #require(ReactionMerge.apply(entry(peer, nil, 9), to: [entry(peer, "❤️", 5)]))
        #expect(ReactionMerge.chips(removed, me: me).isEmpty)
        #expect(ReactionMerge.apply(entry(peer, "❤️", 5), to: removed) == nil)
    }

    @Test
    func pendingChangeOfOursOutlivesServerEvents() {
        let held = [entry(me, "👍", 3, pending: true)]
        // Our other device's older state arriving over the socket must not undo the tap.
        #expect(ReactionMerge.apply(entry(me, "😢", 8), to: held) == nil)
        let confirmed = ReactionMerge.replacing(me, with: entry(me, "👍", 10), in: held)
        #expect(confirmed == [entry(me, "👍", 10)])
    }

    @Test
    func pageReconcileDropsWhatThePageNoLongerLists() {
        let held = [entry(peer, "❤️", 4), entry(me, "👍", 12)]
        // Snapshot 10: the peer's heart is gone by then; our 12 happened after the page was read.
        let result = ReactionMerge.reconcile(held: held, page: [], snapshot: 10)
        #expect(result == [entry(me, "👍", 12)])
    }

    @Test
    func pageReconcileTakesThePageUpToItsSnapshot() {
        let held = [entry(peer, "❤️", 4), entry(me, nil, 6)]
        let page = [entry(peer, "🔥", 9)]
        #expect(ReactionMerge.reconcile(held: held, page: page, snapshot: 9) == page)
        let pending = entry(me, "😮", 6, pending: true)
        #expect(ReactionMerge.reconcile(held: [pending], page: page, snapshot: 9) == [entry(peer, "🔥", 9), pending])
    }

    @Test
    func chipsGroupByEmojiInFirstSeenOrder() {
        let reactions = [entry(peer, "❤️", 2), entry(me, "❤️", 3)]
        let chips = ReactionMerge.chips(reactions, me: me)
        #expect(chips.count == 1)
        #expect(chips[0].userIDs == [peer, me])
        #expect(chips[0].includesMe)

        let split = ReactionMerge.chips([entry(me, "👍", 1, pending: true), entry(peer, "🔥", 2)], me: me)
        #expect(split.map(\.emoji) == ["👍", "🔥"])
        #expect(split.map(\.includesMe) == [true, false])
        #expect(ReactionMerge.emoji(of: me, in: [entry(me, "👍", 1)]) == "👍")
    }

    @Test
    func storedMessagesWithoutReactionsStillDecode() throws {
        let message = MessagingController.ChatMessage(
            id: UUID(), peerUserID: peer, senderUserID: peer, text: "hi",
            createdAt: Date(timeIntervalSince1970: 1_700_000_000), isMine: false, deleted: false,
            reactions: [entry(peer, "❤️", 4), entry(me, nil, 5)]
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
        #expect(result.frames[1].origin == CGPoint(x: CGFloat(54), y: 0))
        // One row: two chips, a gap, the time.
        #expect(result.size == CGSize(width: CGFloat(50 + 4 + 50 + 8 + 44), height: CGFloat(26)))
        #expect(result.frames[2].maxY <= CGFloat(26))
    }

    @Test
    func timeDropsToItsOwnLineWhenTheRowIsFull() {
        let result = ReactionFooterLayout.arrange(chips: [chip, chip], meta: meta, width: 120)
        #expect(result.frames[2].origin == CGPoint(x: 0, y: CGFloat(26 + 4)))
        #expect(result.size.height == CGFloat(26 + 4 + 13))
    }

    @Test
    func chipsWrapAtTheBubbleWidth() {
        let result = ReactionFooterLayout.arrange(chips: [chip, chip, chip], meta: meta, width: 110)
        #expect(result.frames[2].origin == CGPoint(x: 0, y: CGFloat(30)))
        // The time fits after the third chip on the second row.
        #expect(result.frames[3].minY >= CGFloat(30))
        #expect(result.size.height == CGFloat(56))
        #expect(result.size.width <= CGFloat(110))
    }

    @Test
    func unboundedWidthIsOneLine() {
        let result = ReactionFooterLayout.arrange(chips: [chip, chip, chip], meta: meta, width: nil)
        #expect(result.size.height == CGFloat(26))
    }
}
