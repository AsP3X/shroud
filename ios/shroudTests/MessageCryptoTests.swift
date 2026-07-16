import CryptoKit
import Foundation
import Testing
@testable import shroud

struct MessageCryptoTests {
    @Test
    func sealAndOpenRoundTrip() throws {
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
            senderIdentityPublicKey: alice.publicKey.rawRepresentation
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
                senderIdentityPublicKey: alice.publicKey.rawRepresentation
            )
        }
    }
}
