import CryptoKit
import Foundation
import Testing
@testable import shroud

struct MessageCryptoTests {
    @Test
    func sealAndOpenAsRecipient() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let plaintext = Data("hello shroud".utf8)

        let sealed = try MessageCrypto.seal(
            plaintext: plaintext,
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
        #expect(opened == plaintext)
    }

    @Test
    func sealAndOpenAsSenderWithoutLocalCache() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let plaintext = Data("multi-device history".utf8)

        let sealed = try MessageCrypto.seal(
            plaintext: plaintext,
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation
        )

        // Alice's other device decrypts the self box.
        let opened = try MessageCrypto.open(
            envelopeData: sealed,
            with: alice,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .sender
        )
        #expect(opened == plaintext)
    }

    @Test
    func wrongRecipientCannotOpen() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let eve = Curve25519.KeyAgreement.PrivateKey()
        let sealed = try MessageCrypto.seal(
            plaintext: Data("secret".utf8),
            toPeerIdentityPublicKey: bob.publicKey.rawRepresentation,
            ourIdentityPublicKey: alice.publicKey.rawRepresentation
        )

        #expect(throws: (any Error).self) {
            _ = try MessageCrypto.open(
                envelopeData: sealed,
                with: eve,
                ourIdentityPublicKey: eve.publicKey.rawRepresentation,
                senderIdentityPublicKey: alice.publicKey.rawRepresentation,
                as: .recipient
            )
        }
    }

    @Test
    func legacyV1EnvelopeStillOpens() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let plaintext = Data("legacy".utf8)

        // Build a v1-shaped envelope manually (peer-only).
        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let shared = try ephemeral.sharedSecretFromKeyAgreement(with: bob.publicKey)
        var info = Data("shroud-msg-v1".utf8)
        info.append(ephemeral.publicKey.rawRepresentation)
        info.append(alice.publicKey.rawRepresentation)
        info.append(bob.publicKey.rawRepresentation)
        let key = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data("shroud-v1".utf8),
            sharedInfo: info,
            outputByteCount: 32
        )
        let sealed = try AES.GCM.seal(plaintext, using: key)
        let combined = try #require(sealed.combined)
        let envelope = MessageCrypto.SealedEnvelope(
            v: 1,
            ek: ephemeral.publicKey.rawRepresentation.base64EncodedString(),
            ct: combined.base64EncodedString(),
            peer: nil,
            selfBox: nil
        )
        let data = try JSONEncoder().encode(envelope)

        let opened = try MessageCrypto.open(
            envelopeData: data,
            with: bob,
            ourIdentityPublicKey: bob.publicKey.rawRepresentation,
            senderIdentityPublicKey: alice.publicKey.rawRepresentation,
            as: .recipient
        )
        #expect(opened == plaintext)
    }
}
