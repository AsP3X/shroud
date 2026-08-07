import CryptoKit
import Foundation
import Testing
@testable import shroud

final class DoubleRatchetTests: Sendable {
    /// Peer ids for the Keychain-backed session store, fresh for every test case.
    ///
    /// `RatchetSessionStore` is process-global and keyed only by peer user id, so tests
    /// sharing fixed ids share ratchet state. swift-testing runs cases in parallel and the
    /// suite can run more than once per session, which left tests decrypting against another
    /// case's session (built from different identity keys). Unique ids give each case its own
    /// slice of the store; `deinit` clears it again.
    private let aliceUser: UUID
    private let bobUser: UUID

    init() {
        // Initiator election compares uuidString, so keep alice the deterministic initiator.
        let ids = [UUID(), UUID()].sorted { $0.uuidString.lowercased() < $1.uuidString.lowercased() }
        aliceUser = ids[0]
        bobUser = ids[1]
    }

    deinit {
        RatchetSessionStore.delete(peerUserID: aliceUser)
        RatchetSessionStore.delete(peerUserID: bobUser)
    }

    @Test
    func rootSeedIsSymmetric() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let a = try DoubleRatchet.Session.rootSeed(
            ourPrivate: alice,
            theirIdentityPublic: bob.publicKey.rawRepresentation
        )
        let b = try DoubleRatchet.Session.rootSeed(
            ourPrivate: bob,
            theirIdentityPublic: alice.publicKey.rawRepresentation
        )
        #expect(a == b)
    }

    @Test
    func ratchetRoundTripAliceBob() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()

        var aliceSession = try DoubleRatchet.Session.initiateAsSender(
            ourPrivate: alice,
            theirIdentityPublic: bob.publicKey.rawRepresentation
        )
        var bobSession = try DoubleRatchet.Session.prepareAsReceiver(
            ourPrivate: bob,
            theirIdentityPublic: alice.publicKey.rawRepresentation
        )

        let plain1 = Data("hello from alice".utf8)
        let env1 = try DoubleRatchet.encrypt(plaintext: plain1, session: &aliceSession)
        #expect(try DoubleRatchet.decrypt(envelopeData: env1, session: &bobSession) == plain1)

        let plain2 = Data("reply from bob".utf8)
        let env2 = try DoubleRatchet.encrypt(plaintext: plain2, session: &bobSession)
        #expect(try DoubleRatchet.decrypt(envelopeData: env2, session: &aliceSession) == plain2)

        let plain3 = Data("alice again".utf8)
        let env3 = try DoubleRatchet.encrypt(plaintext: plain3, session: &aliceSession)
        #expect(try DoubleRatchet.decrypt(envelopeData: env3, session: &bobSession) == plain3)
    }

    @Test
    func multiMessageSameChain() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        var aliceSession = try DoubleRatchet.Session.initiateAsSender(
            ourPrivate: alice,
            theirIdentityPublic: bob.publicKey.rawRepresentation
        )
        var bobSession = try DoubleRatchet.Session.prepareAsReceiver(
            ourPrivate: bob,
            theirIdentityPublic: alice.publicKey.rawRepresentation
        )

        for i in 0 ..< 5 {
            let plain = Data("msg-\(i)".utf8)
            let env = try DoubleRatchet.encrypt(plaintext: plain, session: &aliceSession)
            #expect(try DoubleRatchet.decrypt(envelopeData: env, session: &bobSession) == plain)
        }
    }

    @Test
    func productionSealDefaultsToV3WhenInitiator() throws {
        // Lower UUID is deterministic initiator → v3.
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()

        let sealed = try MessageCrypto.seal(
            plaintext: Data("hello".utf8),
            peerUserID: bobUser,
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourPrivateKey: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            ourUserID: aliceUser
        )
        struct Peek: Decodable { let v: Int }
        #expect(try JSONDecoder().decode(Peek.self, from: sealed).v == 3)

        let opened = try MessageCrypto.open(
            envelopeData: sealed,
            peerUserID: aliceUser,
            with: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(opened == Data("hello".utf8))
    }

    @Test
    func nonInitiatorFirstMessageUsesV2() throws {
        // Higher UUID must not create a poison initiator session.
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()

        let sealed = try MessageCrypto.seal(
            plaintext: Data("bob first".utf8),
            peerUserID: aliceUser,
            toPeerIdentityPublicKey: alice.publicKey.rawRepresentation,
            ourPrivateKey: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourUserID: bobUser
        )
        struct Peek: Decodable { let v: Int }
        #expect(try JSONDecoder().decode(Peek.self, from: sealed).v == 2)
        #expect(RatchetSessionStore.load(peerUserID: aliceUser) == nil)

        let opened = try MessageCrypto.open(
            envelopeData: sealed,
            peerUserID: bobUser,
            with: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            senderIdentityPublicKey: bob.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(opened == Data("bob first".utf8))
    }

    @Test
    func dualInitiatorBothSendThenDecrypt() throws {
        // Both send before either opens — lower UUID uses DR, higher uses v2.
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()

        let fromBob = try MessageCrypto.seal(
            plaintext: Data("bob first".utf8),
            peerUserID: aliceUser,
            toPeerIdentityPublicKey: alice.publicKey.rawRepresentation,
            ourPrivateKey: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourUserID: bobUser
        )
        let fromAlice = try MessageCrypto.seal(
            plaintext: Data("alice first".utf8),
            peerUserID: bobUser,
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourPrivateKey: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            ourUserID: aliceUser
        )

        struct Peek: Decodable { let v: Int }
        #expect(try JSONDecoder().decode(Peek.self, from: fromAlice).v == 3)
        #expect(try JSONDecoder().decode(Peek.self, from: fromBob).v == 2)

        let bobOpensAlice = try MessageCrypto.open(
            envelopeData: fromAlice,
            peerUserID: aliceUser,
            with: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(bobOpensAlice == Data("alice first".utf8))

        let aliceOpensBob = try MessageCrypto.open(
            envelopeData: fromBob,
            peerUserID: bobUser,
            with: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            senderIdentityPublicKey: bob.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(aliceOpensBob == Data("bob first".utf8))

        // After Bob received, he has a DR session and subsequent sends are v3.
        let fromBob2 = try MessageCrypto.seal(
            plaintext: Data("bob second".utf8),
            peerUserID: aliceUser,
            toPeerIdentityPublicKey: alice.publicKey.rawRepresentation,
            ourPrivateKey: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourUserID: bobUser
        )
        #expect(try JSONDecoder().decode(Peek.self, from: fromBob2).v == 3)

        let aliceOpens2 = try MessageCrypto.open(
            envelopeData: fromBob2,
            peerUserID: bobUser,
            with: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            senderIdentityPublicKey: bob.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(aliceOpens2 == Data("bob second".utf8))
    }

    @Test
    func fullChatSimulationAlternating() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()

        func send(
            from: Curve25519.KeyAgreement.PrivateKey,
            fromPub: Data,
            fromUser: UUID,
            toPub: Data,
            toUser: UUID,
            text: String
        ) throws -> Data {
            try MessageCrypto.seal(
                plaintext: Data(text.utf8),
                peerUserID: toUser,
                toPeerIdentityPublicKey: toPub,
                ourPrivateKey: from,
                ourIdentityPublicKey: fromPub,
                ourUserID: fromUser
            )
        }

        func recv(
            _ env: Data,
            as user: Curve25519.KeyAgreement.PrivateKey,
            userPub: Data,
            senderPub: Data,
            storeAsPeer: UUID
        ) throws -> String {
            let data = try MessageCrypto.open(
                envelopeData: env,
                peerUserID: storeAsPeer,
                with: user,
                ourIdentityPublicKey: userPub,
                senderIdentityPublicKey: senderPub,
                as: .recipient
            )
            return String(data: data, encoding: .utf8) ?? ""
        }

        let e1 = try send(
            from: alice, fromPub: alice.publicKey.rawRepresentation, fromUser: aliceUser,
            toPub: bob.publicKey.rawRepresentation, toUser: bobUser, text: "A1"
        )
        #expect(try recv(
            e1, as: bob, userPub: bob.publicKey.rawRepresentation,
            senderPub: alice.publicKey.rawRepresentation, storeAsPeer: aliceUser
        ) == "A1")

        let e2 = try send(
            from: bob, fromPub: bob.publicKey.rawRepresentation, fromUser: bobUser,
            toPub: alice.publicKey.rawRepresentation, toUser: aliceUser, text: "B1"
        )
        #expect(try recv(
            e2, as: alice, userPub: alice.publicKey.rawRepresentation,
            senderPub: bob.publicKey.rawRepresentation, storeAsPeer: bobUser
        ) == "B1")

        let e3 = try send(
            from: alice, fromPub: alice.publicKey.rawRepresentation, fromUser: aliceUser,
            toPub: bob.publicKey.rawRepresentation, toUser: bobUser, text: "A2"
        )
        #expect(try recv(
            e3, as: bob, userPub: bob.publicKey.rawRepresentation,
            senderPub: alice.publicKey.rawRepresentation, storeAsPeer: aliceUser
        ) == "A2")
    }

    @Test
    func selfBoxOpensOnV3() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()

        let plain = Data("own history".utf8)
        let sealed = try MessageCrypto.seal(
            plaintext: plain,
            peerUserID: bobUser,
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourPrivateKey: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            ourUserID: aliceUser
        )
        let asSender = try MessageCrypto.open(
            envelopeData: sealed,
            peerUserID: bobUser,
            with: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .sender
        )
        #expect(asSender == plain)
    }

    @Test
    func legacyV2StillWorks() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let sealed = try MessageCrypto.seal(
            plaintext: Data("classic".utf8),
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation
        )
        let opened = try MessageCrypto.open(
            envelopeData: sealed,
            with: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(opened == Data("classic".utf8))
    }

    @Test
    func explicitV2OptOut() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let sealed = try MessageCrypto.seal(
            plaintext: Data("no ratchet".utf8),
            peerUserID: UUID(),
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourPrivateKey: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            useRatchet: false
        )
        struct Peek: Decodable { let v: Int }
        #expect(try JSONDecoder().decode(Peek.self, from: sealed).v == 2)
    }

    @Test
    func textThenImageDoesNotBreakRecipient() throws {
        // Regression: second message (e.g. photo) must open after a successful text.
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()

        let textEnv = try MessageCrypto.seal(
            plaintext: Data("hi".utf8),
            peerUserID: bobUser,
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourPrivateKey: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            ourUserID: aliceUser
        )
        #expect(try MessageCrypto.open(
            envelopeData: textEnv,
            peerUserID: aliceUser,
            with: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .recipient
        ) == Data("hi".utf8))

        // Simulate a failed re-open of the same text (must NOT wipe Bob's session).
        #expect(throws: (any Error).self) {
            _ = try MessageCrypto.open(
                envelopeData: textEnv,
                peerUserID: aliceUser,
                with: bob,
                ourIdentityPublicKey: bob.publicKey.rawRepresentation,
                senderIdentityPublicKey: alice.publicKey.rawRepresentation,
                as: .recipient
            )
        }

        let imagePayload = try JSONEncoder().encode(
            MediaMessagePayload(
                t: MediaMessagePayload.kindImage,
                mime: "image/jpeg",
                w: 10,
                h: 10,
                k: Data(repeating: 1, count: 32).base64EncodedString(),
                c: nil,
                d: nil
            )
        )
        let imageEnv = try MessageCrypto.seal(
            plaintext: imagePayload,
            peerUserID: bobUser,
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourPrivateKey: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            ourUserID: aliceUser
        )
        let openedImage = try MessageCrypto.open(
            envelopeData: imageEnv,
            peerUserID: aliceUser,
            with: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(openedImage == imagePayload)
    }
}
