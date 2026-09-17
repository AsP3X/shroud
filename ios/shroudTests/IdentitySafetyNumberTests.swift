import CryptoKit
import Foundation
import Testing
@testable import shroud

struct IdentitySafetyNumberTests {
    @Test
    func fingerprintIsSymmetric() {
        let a = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let b = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let left = IdentitySafetyNumber.displayString(localIdentity: a, peerIdentity: b)
        let right = IdentitySafetyNumber.displayString(localIdentity: b, peerIdentity: a)
        #expect(left == right)
        #expect(left.split(separator: " ").count == 12)
        #expect(left.split(separator: " ").allSatisfy { $0.count == 5 })
    }

    @Test
    func differentKeysProduceDifferentNumbers() {
        let local = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let peerA = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let peerB = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let one = IdentitySafetyNumber.displayString(localIdentity: local, peerIdentity: peerA)
        let two = IdentitySafetyNumber.displayString(localIdentity: local, peerIdentity: peerB)
        #expect(one != two)
    }
}
