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

    // MARK: - Shared goldens (Android `IdentitySafetyNumberTest` and web `safetyNumber` agree)

    private static func hex(_ string: String) -> Data {
        var data = Data()
        var index = string.startIndex
        while index < string.endIndex {
            let next = string.index(index, offsetBy: 2)
            data.append(UInt8(string[index ..< next], radix: 16)!)
            index = next
        }
        return data
    }

    private static func number(_ a: Data, _ b: Data) -> String {
        IdentitySafetyNumber.displayString(localIdentity: a, peerIdentity: b)
    }

    /// android-port-specs contacts §4.9 (generated from this file, cross-checked in Python).
    @Test
    func matchesTheSharedGoldens() {
        let a = Data((0x01 ... 0x20).map { UInt8($0) })
        let b = Data((0x21 ... 0x40).map { UInt8($0) })
        let ab = "39936 00420 36095 80875 11472 14538 50394 55836 81423 99087 38599 17095"
        #expect(Self.number(a, b) == ab)
        #expect(Self.number(b, a) == ab)
        #expect(
            Self.number(Data(repeating: 0x00, count: 32), Data(repeating: 0xFF, count: 32))
                == "98683 78184 46324 56350 82390 90107 00987 21869 25032 71750 76963 61577"
        )
        let h1 = Self.hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        let h2 = Self.hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        let h12 = "52249 84593 58473 87782 33310 55290 67694 46749 50759 35105 99615 07906"
        #expect(Self.number(h1, h2) == h12)
        #expect(Self.number(h2, h1) == h12)
        #expect(Self.number(a, a) == "29696 81578 32876 91411 15478 21467 89245 24174 87371 59194 49089 78176")
    }

    /// android-port-specs crypto §16.3: X25519 public keys of 0x11×32 and 0x22×32, and a pair
    /// whose bytes from 0x80 sort after 0x01 only when compared unsigned.
    @Test
    func matchesTheSharedCryptoGoldens() throws {
        let alice = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0x11, count: 32))
            .publicKey.rawRepresentation
        let bob = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0x22, count: 32))
            .publicKey.rawRepresentation
        #expect(alice == Self.hex("7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"))
        #expect(bob == Self.hex("0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20"))
        let aliceBob = "31988 96179 94514 40486 97680 44349 56357 86940 89163 43635 86683 49866"
        #expect(Self.number(alice, bob) == aliceBob)
        #expect(Self.number(bob, alice) == aliceBob)
        let low = Data((0x01 ... 0x20).map { UInt8($0) })
        let high = Data((0x80 ... 0x9F).map { UInt8($0) })
        let lowHigh = "73973 08899 45243 61171 10525 32783 47853 25287 62674 77996 39692 99672"
        #expect(Self.number(low, high) == lowHigh)
        #expect(Self.number(high, low) == lowHigh)
    }

    /// While a key change waits for "Trust new key", the profile shows the new key's number —
    /// the one the contact's phone shows — not the old pin's (P10b).
    @Test @MainActor
    func aPendingKeyChangeShowsTheNewKeysNumber() {
        let pinned = Data(repeating: 0x07, count: 32)
        let fresh = Data(repeating: 0x08, count: 32)
        let change = PeerIdentityChange(previousKey: pinned, currentKey: fresh)
        #expect(MessagingController.safetyNumberKey(pinned: pinned, change: change) == fresh)
        #expect(MessagingController.safetyNumberKey(pinned: pinned, change: nil) == pinned)
        #expect(MessagingController.safetyNumberKey(pinned: nil, change: nil) == nil)
    }
}
