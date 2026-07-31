import CryptoKit
import Foundation
import Testing
@testable import shroud

struct DoubleRatchetTests {
    @Test
    func ratchetRoundTripAliceBob() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let peerID = UUID()

        // Fresh sessions (avoid Keychain pollution across tests by using in-memory bootstrap).
        let sharedAlice = try DoubleRatchet.identitySharedSecret(
            ourPrivate: alice,
            theirPublic: bob.publicKey.rawRepresentation
        )
        var aliceSession = try DoubleRatchet.Session.bootstrapInitiator(
            sharedSecret: sharedAlice,
            theirRatchetPublic: bob.publicKey.rawRepresentation
        )
        let sharedBob = try DoubleRatchet.identitySharedSecret(
            ourPrivate: bob,
            theirPublic: alice.publicKey.rawRepresentation
        )
        var bobSession = DoubleRatchet.Session.bootstrapResponder(
            sharedSecret: sharedBob,
            ourRatchetPrivate: bob
        )

        let plain1 = Data("hello from alice".utf8)
        let env1 = try DoubleRatchet.encrypt(plaintext: plain1, session: &aliceSession)
        let open1 = try DoubleRatchet.decrypt(envelopeData: env1, session: &bobSession)
        #expect(open1 == plain1)

        let plain2 = Data("reply from bob".utf8)
        let env2 = try DoubleRatchet.encrypt(plaintext: plain2, session: &bobSession)
        let open2 = try DoubleRatchet.decrypt(envelopeData: env2, session: &aliceSession)
        #expect(open2 == plain2)

        let plain3 = Data("alice again".utf8)
        let env3 = try DoubleRatchet.encrypt(plaintext: plain3, session: &aliceSession)
        let open3 = try DoubleRatchet.decrypt(envelopeData: env3, session: &bobSession)
        #expect(open3 == plain3)

        _ = peerID
    }

    @Test
    func messageCryptoV3SealOpenWithSelfBox() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let peerID = UUID()
        // Isolate store key for this test pair.
        RatchetSessionStore.delete(peerUserID: peerID)

        let plain = Data("ratchet media".utf8)
        let sealed = try MessageCrypto.seal(
            plaintext: plain,
            peerUserID: peerID,
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourPrivateKey: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            useRatchet: true
        )

        // Sender multi-device path uses self box.
        let asSender = try MessageCrypto.open(
            envelopeData: sealed,
            peerUserID: peerID,
            with: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .sender
        )
        #expect(asSender == plain)

        // Recipient DR path — bob needs his own session store key (use same peerID for alice's id in bob's view).
        let aliceAsPeer = UUID()
        RatchetSessionStore.delete(peerUserID: aliceAsPeer)
        let asRecipient = try MessageCrypto.open(
            envelopeData: sealed,
            peerUserID: aliceAsPeer,
            with: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(asRecipient == plain)

        RatchetSessionStore.delete(peerUserID: peerID)
        RatchetSessionStore.delete(peerUserID: aliceAsPeer)
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
    func productionSealDefaultsToV2DualSeal() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let peerID = UUID()
        let plain = Data("hello contact".utf8)

        // MessagingController uses this overload without useRatchet — must be v2.
        let sealed = try MessageCrypto.seal(
            plaintext: plain,
            peerUserID: peerID,
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourPrivateKey: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation
        )
        struct Peek: Decodable { let v: Int }
        let version = try JSONDecoder().decode(Peek.self, from: sealed).v
        #expect(version == 2)

        let asRecipient = try MessageCrypto.open(
            envelopeData: sealed,
            peerUserID: peerID,
            with: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(asRecipient == plain)

        let asSender = try MessageCrypto.open(
            envelopeData: sealed,
            peerUserID: peerID,
            with: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .sender
        )
        #expect(asSender == plain)
    }

    @Test
    func dualInitiatorRatchetRecoversWithSessionReset() throws {
        // Both sides sealed as initiator first — old DR path failed open; reset must recover.
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let aliceID = UUID()
        let bobID = UUID()
        RatchetSessionStore.delete(peerUserID: aliceID)
        RatchetSessionStore.delete(peerUserID: bobID)

        // Bob initiates toward Alice (poisons Bob's session store for aliceID if roles invert).
        _ = try MessageCrypto.seal(
            plaintext: Data("bob first".utf8),
            peerUserID: aliceID,
            toPeerIdentityPublicKey: alice.publicKey.rawRepresentation,
            ourPrivateKey: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            useRatchet: true
        )

        // Alice initiates toward Bob.
        let fromAlice = try MessageCrypto.seal(
            plaintext: Data("alice first".utf8),
            peerUserID: bobID,
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourPrivateKey: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            useRatchet: true
        )

        // Bob opens Alice's message — may need session reset after his own initiator seal.
        let opened = try MessageCrypto.open(
            envelopeData: fromAlice,
            peerUserID: aliceID,
            with: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(opened == Data("alice first".utf8))

        RatchetSessionStore.delete(peerUserID: aliceID)
        RatchetSessionStore.delete(peerUserID: bobID)
    }
}
