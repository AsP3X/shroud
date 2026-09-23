import CryptoKit
import Foundation
import Testing
@testable import shroud

/// Identity boxes carry a sender tag. A box built from the two public keys alone — what the
/// server can do — must not open as the peer once that peer is known to tag.
final class SenderTagTests: Sendable {
    private let aliceUser: UUID
    private let bobUser: UUID
    private let alice = Curve25519.KeyAgreement.PrivateKey()
    private let bob = Curve25519.KeyAgreement.PrivateKey()
    private let mallory = Curve25519.KeyAgreement.PrivateKey()
    private let t0 = Date(timeIntervalSince1970: 1_788_000_000)

    init() {
        let ids = [UUID(), UUID()].sorted { $0.uuidString.lowercased() < $1.uuidString.lowercased() }
        aliceUser = ids[0]
        bobUser = ids[1]
        SealedTestKey.unlockSealedLocalState()
        // Watermarks are keyed by identity key, and every case has fresh keys.
        SenderTagStore.useInMemoryStorageForTesting()
    }

    deinit {
        RatchetSessionStore.delete(peerUserID: aliceUser)
        RatchetSessionStore.delete(peerUserID: bobUser)
    }

    private var aPub: Data { alice.publicKey.rawRepresentation }
    private var bPub: Data { bob.publicKey.rawRepresentation }

    // MARK: - Helpers

    /// An untagged box as builds before the tag sealed it — and as anyone with the public keys can.
    private func forgedBox(_ text: String, sender: Data, recipient: Data) throws -> MessageCrypto.SealedBox {
        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let recipientKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipient)
        let shared = try ephemeral.sharedSecretFromKeyAgreement(with: recipientKey)
        var info = Data("shroud-msg-v1".utf8)
        info.append(ephemeral.publicKey.rawRepresentation)
        info.append(sender)
        info.append(recipient)
        let key = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data("shroud-v1".utf8),
            sharedInfo: info,
            outputByteCount: 32
        )
        let combined = try #require(try AES.GCM.seal(Data(text.utf8), using: key).combined)
        return MessageCrypto.SealedBox(
            ek: ephemeral.publicKey.rawRepresentation.base64EncodedString(),
            ct: combined.base64EncodedString()
        )
    }

    private func v2(peer: MessageCrypto.SealedBox? = nil, selfBox: MessageCrypto.SealedBox? = nil) throws -> Data {
        try JSONEncoder().encode(MessageCrypto.SealedEnvelope(v: 2, peer: peer, selfBox: selfBox))
    }

    /// v3 with a ratchet body no session can read, so the peer-box fallback runs.
    private func junkV3(peer: MessageCrypto.SealedBox) throws -> Data {
        try JSONEncoder().encode(MessageCrypto.RatchetEnvelope(
            v: 3,
            dh: Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation.base64EncodedString(),
            n: 0,
            pn: 0,
            ct: Data(repeating: 7, count: 40).base64EncodedString(),
            peer: peer,
            selfBox: nil
        ))
    }

    private func aliceV2(_ text: String, signer: Curve25519.KeyAgreement.PrivateKey? = nil) throws -> Data {
        try MessageCrypto.seal(
            plaintext: Data(text.utf8),
            toPeerIdentityPublicKey: bPub,
            ourPrivateKey: signer ?? alice,
            ourIdentityPublicKey: aPub
        )
    }

    private func bobOpens(_ envelope: Data, at sentAt: Date) throws -> String {
        let plain = try MessageCrypto.open(
            envelopeData: envelope,
            peerUserID: aliceUser,
            with: bob,
            ourIdentityPublicKey: bPub,
            senderIdentityPublicKey: aPub,
            as: .recipient,
            sentAt: sentAt
        )
        return String(decoding: plain, as: UTF8.self)
    }

    private func aliceOpensOwn(_ envelope: Data, at sentAt: Date) throws -> String {
        let plain = try MessageCrypto.open(
            envelopeData: envelope,
            peerUserID: bobUser,
            with: alice,
            ourIdentityPublicKey: aPub,
            senderIdentityPublicKey: aPub,
            as: .sender,
            sentAt: sentAt
        )
        return String(decoding: plain, as: UTF8.self)
    }

    // MARK: - Box level

    @Test
    func sealedBoxesCarryATagThatOpens() throws {
        let sealed = try aliceV2("hi")
        let envelope = try JSONDecoder().decode(MessageCrypto.SealedEnvelope.self, from: sealed)
        #expect(envelope.peer?.t != nil)
        #expect(envelope.selfBox?.t != nil)
        #expect(try bobOpens(sealed, at: t0) == "hi")
    }

    @Test
    func tagFromAKeyThatIsNotTheSendersIsRefused() throws {
        // Mallory seals "from Alice" with her own private key: the static ECDH does not match.
        let forged = try aliceV2("forged", signer: mallory)
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) {
            _ = try self.bobOpens(forged, at: self.t0)
        }
    }

    @Test
    func tagMovedOntoOtherCiphertextIsRefused() throws {
        let real = try JSONDecoder().decode(MessageCrypto.SealedEnvelope.self, from: aliceV2("real"))
        let other = try JSONDecoder().decode(MessageCrypto.SealedEnvelope.self, from: aliceV2("other"))
        var moved = try #require(other.peer)
        moved.t = real.peer?.t
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) {
            _ = try self.bobOpens(self.v2(peer: moved), at: self.t0)
        }
    }

    @Test
    func reflectedBoxIsRefused() throws {
        // Bob's own self box, handed back to Bob as if Alice had sent it.
        let bobsOwn = try MessageCrypto.seal(
            plaintext: Data("mine".utf8),
            toPeerIdentityPublicKey: aPub,
            ourPrivateKey: bob,
            ourIdentityPublicKey: bPub
        )
        let selfBox = try #require(
            try JSONDecoder().decode(MessageCrypto.SealedEnvelope.self, from: bobsOwn).selfBox
        )
        #expect(throws: (any Error).self) {
            _ = try self.bobOpens(self.v2(peer: selfBox), at: self.t0)
        }
    }

    /// Sealed by `web/src/crypto/sealedBox.ts` with fixed keys: both clients derive one tag.
    @Test
    func webSealedTagVerifiesHere() throws {
        let alice = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0x11, count: 32))
        let bob = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0x22, count: 32))
        var box = MessageCrypto.SealedBox(
            ek: "S6UOubR4gmyGJzC+XuOl+S0Y3VykkvhJXJ32/m3JWCw=",
            ct: "s67IM+reT2lNl2zRNUPKbSdmtPpU5BX5o/cOH0WTaaHS23Hi",
            t: "iFxRl5LlVWjGZgfs2F1WLl9Olc8ZHOzP67HNLMoeywM="
        )
        func open() throws -> Data {
            try MessageCrypto.open(
                envelopeData: v2(peer: box),
                with: bob,
                ourIdentityPublicKey: bob.publicKey.rawRepresentation,
                senderIdentityPublicKey: alice.publicKey.rawRepresentation,
                as: .recipient,
                sentAt: t0
            )
        }
        #expect(try open() == Data("from web".utf8))
        box.t = "jFxRl5LlVWjGZgfs2F1WLl9Olc8ZHOzP67HNLMoeywM="
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) { _ = try open() }
    }

    // MARK: - Policy

    /// The reported issue: with no tagged message seen yet, a forged v2 still opens. This is
    /// the transition window for peers on builds that cannot tag.
    @Test
    func untaggedBoxOpensBeforeTheSenderIsSeenTagging() throws {
        let forged = try v2(peer: forgedBox("forged", sender: aPub, recipient: bPub))
        #expect(try bobOpens(forged, at: t0) == "forged")
    }

    @Test
    func untaggedBoxesAfterTheWatermarkAreRefused() throws {
        #expect(try bobOpens(aliceV2("real"), at: t0) == "real")

        let forgedV2 = try v2(peer: forgedBox("forged", sender: aPub, recipient: bPub))
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) {
            _ = try self.bobOpens(forgedV2, at: self.t0.addingTimeInterval(1))
        }
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) {
            _ = try self.bobOpens(forgedV2, at: self.t0)
        }

        let box = try forgedBox("forged v1", sender: aPub, recipient: bPub)
        let forgedV1 = try JSONEncoder().encode(
            MessageCrypto.SealedEnvelope(v: 1, ek: box.ek, ct: box.ct, peer: nil, selfBox: nil)
        )
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) {
            _ = try self.bobOpens(forgedV1, at: self.t0.addingTimeInterval(1))
        }

        let forgedV3 = try junkV3(peer: forgedBox("forged v3", sender: aPub, recipient: bPub))
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) {
            _ = try self.bobOpens(forgedV3, at: self.t0.addingTimeInterval(1))
        }
    }

    /// A fresh device reads the history from before the peer upgraded.
    @Test
    func untaggedHistoryBeforeTheWatermarkStillOpens() throws {
        #expect(try bobOpens(aliceV2("real"), at: t0) == "real")
        let old = try v2(peer: forgedBox("old", sender: aPub, recipient: bPub))
        #expect(try bobOpens(old, at: t0.addingTimeInterval(-60)) == "old")

        // An older tagged message moves the watermark back past it.
        #expect(try bobOpens(aliceV2("older"), at: t0.addingTimeInterval(-120)) == "older")
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) {
            _ = try self.bobOpens(old, at: self.t0.addingTimeInterval(-60))
        }
    }

    /// The device that reads by ratchet never opens the peer box; its tag still counts.
    @Test
    func ratchetReadMarksTheSenderAsTagging() throws {
        let real = try MessageCrypto.seal(
            plaintext: Data("by ratchet".utf8),
            peerUserID: bobUser,
            toPeerIdentityPublicKey: bPub,
            ourPrivateKey: alice,
            ourIdentityPublicKey: aPub,
            ourUserID: aliceUser
        )
        #expect(try JSONDecoder().decode(MessageCrypto.RatchetEnvelope.self, from: real).v == 3)
        #expect(try bobOpens(real, at: t0) == "by ratchet")

        let forged = try v2(peer: forgedBox("forged", sender: aPub, recipient: bPub))
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) {
            _ = try self.bobOpens(forged, at: self.t0.addingTimeInterval(1))
        }
    }

    @Test
    func siblingWithoutRatchetStateReadsTheTaggedPeerBox() throws {
        let real = try JSONDecoder().decode(MessageCrypto.SealedEnvelope.self, from: aliceV2("to sibling"))
        let tagged = try #require(real.peer)
        #expect(try bobOpens(junkV3(peer: tagged), at: t0) == "to sibling")

        let malloryBox = try #require(
            try JSONDecoder().decode(MessageCrypto.SealedEnvelope.self, from: aliceV2("m", signer: mallory)).peer
        )
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) {
            _ = try self.bobOpens(self.junkV3(peer: malloryBox), at: self.t0)
        }
    }

    /// "Sent by me" cannot be forged onto our other devices either.
    @Test
    func forgedSelfBoxAfterOwnTaggedMessageIsRefused() throws {
        #expect(try aliceOpensOwn(aliceV2("own"), at: t0) == "own")
        let forged = try v2(selfBox: forgedBox("fake own", sender: aPub, recipient: aPub))
        #expect(throws: MessageCrypto.CryptoError.unauthenticatedSender) {
            _ = try self.aliceOpensOwn(forged, at: self.t0.addingTimeInterval(1))
        }
    }
}
