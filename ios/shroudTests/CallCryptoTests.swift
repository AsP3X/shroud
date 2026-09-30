import CryptoKit
import Foundation
import Testing
@testable import shroud

/// The call-signal wire format against the vector in docs/calls.md (the web client tests the
/// same bytes).
struct CallCryptoTests {
    private let alicePrivate = "ef80f1878a337c4c39eeb6578cf9af4c38bc681ed61ec43084d00d1e1657723d"
    private let alicePublic = "8b29cac884916cb7098ba9d90b61ef21d88596315cd1d04799a1962012e0b467"
    private let bobPrivate = "58b2859744734402b8fa838480195ffd0c8cfbda7bbe22a710a7d4d8b79b1afd"
    private let bobPublic = "389c6f5486c58d1c61ee17df7793440e1e5e4b7822cae03fc92c9d2a4920e338"
    private let callID = UUID(uuidString: "0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b")!
    private let expectedSecret = "39ea5a3a4128617d799fab4480c1bff13f92bef72ccb0bbc246da1504ba3839f"
    private let expectedCallerKey = "3a3d8f2724fe45e6af1af14b490fd1a4e43aacb8d8e13a9009eb677c79bebb30"
    private let expectedCalleeKey = "46ac38a6ec923643613298cd51555c02f62c163bf191a62f200740d3a0cfb099"
    private let expectedPayload =
        "c1.AAECAwQFBgcICQoLQDFMfgG6gj47QCtynr5cS3IjS6czaNhnseRvgliRTrXZeUi+lMfrLwnqwjds+cTC3HtQ"

    private func key(_ hex: String) throws -> Curve25519.KeyAgreement.PrivateKey {
        try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(hexString: hex))
    }

    private func secrets() throws -> (alice: SymmetricKey, bob: SymmetricKey) {
        let alice = try CallCrypto.callSecret(
            ourPrivateKey: key(alicePrivate),
            ourPublicKey: Data(hexString: alicePublic),
            peerPublicKey: Data(hexString: bobPublic)
        )
        let bob = try CallCrypto.callSecret(
            ourPrivateKey: key(bobPrivate),
            ourPublicKey: Data(hexString: bobPublic),
            peerPublicKey: Data(hexString: alicePublic)
        )
        return (alice, bob)
    }

    @Test
    func publicKeysMatchTheVector() throws {
        #expect(try key(alicePrivate).publicKey.rawRepresentation.hexString == alicePublic)
        #expect(try key(bobPrivate).publicKey.rawRepresentation.hexString == bobPublic)
    }

    @Test
    func bothSidesDeriveTheVectorsSecret() throws {
        let (alice, bob) = try secrets()
        #expect(alice.hexString == expectedSecret)
        #expect(bob.hexString == expectedSecret)
    }

    @Test
    func directionKeysMatchTheVector() throws {
        let secret = try secrets().alice
        #expect(CallCrypto.signalKey(secret: secret, callID: callID, role: .caller).hexString == expectedCallerKey)
        #expect(CallCrypto.signalKey(secret: secret, callID: callID, role: .callee).hexString == expectedCalleeKey)
    }

    @Test
    func sealingWithTheVectorsNonceGivesItsPayload() throws {
        let keys = CallSignalKeys(secret: try secrets().alice, callID: callID, role: .caller)
        let nonce = try AES.GCM.Nonce(data: Data((0..<12).map { UInt8($0) }))
        let payload = try CallCrypto.seal(
            Data(#"{"t":"offer","sdp":"v=0\r\n","n":1}"#.utf8),
            key: keys.send,
            callID: callID,
            signalType: "sdp_offer",
            nonce: nonce
        )
        #expect(payload == expectedPayload)
    }

    @Test
    func forwardSecretMatchesTheVector() throws {
        let identity = try secrets().alice
        let aliceEph = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0x20, count: 32))
        let bobEph = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0x30, count: 32))
        let fromAlice = try CallCrypto.forwardSecret(
            identitySecret: identity,
            ourEphemeralPrivate: aliceEph,
            ourEphemeralPublic: aliceEph.publicKey.rawRepresentation,
            peerEphemeralPublic: bobEph.publicKey.rawRepresentation,
            callID: callID
        )
        let fromBob = try CallCrypto.forwardSecret(
            identitySecret: identity,
            ourEphemeralPrivate: bobEph,
            ourEphemeralPublic: bobEph.publicKey.rawRepresentation,
            peerEphemeralPublic: aliceEph.publicKey.rawRepresentation,
            callID: callID
        )
        #expect(fromAlice.hexString == "7d4c5c4a5c2a2d1bc6979d871db81e8642b840c0f0b816378ac62bcf8e4112d6")
        #expect(fromBob.hexString == fromAlice.hexString)
        #expect(CallCrypto.forwardSignalKey(secret: fromAlice, callID: callID, role: .caller).hexString
            == "4f366306d6ee25ffa435b12c0cfb2bf937b625c7f056171c0a1386d2163b98f9")
        #expect(CallCrypto.forwardSignalKey(secret: fromAlice, callID: callID, role: .callee).hexString
            == "e8b850838219ef3c085075166994a08e4d6a9a5c4edc16c867afe15f2c0f2873")
        let caller = CallSignalKeys(forwardSecret: fromAlice, callID: callID, role: .caller)
        let callee = CallSignalKeys(forwardSecret: fromBob, callID: callID, role: .callee)
        let sealed = try CallCrypto.seal(Data("ice".utf8), key: caller.send, callID: callID, signalType: "ice_candidate")
        #expect(try CallCrypto.open(sealed, key: callee.receive, callID: callID, signalType: "ice_candidate") == Data("ice".utf8))
        let identityKeys = CallSignalKeys(secret: identity, callID: callID, role: .callee)
        #expect(throws: CallCrypto.CryptoError.openFailed) {
            try CallCrypto.open(sealed, key: identityKeys.receive, callID: callID, signalType: "ice_candidate")
        }
    }

    @Test
    func theCalleeOpensWhatTheCallerSealed() throws {
        let (alice, bob) = try secrets()
        let caller = CallSignalKeys(secret: alice, callID: callID, role: .caller)
        let callee = CallSignalKeys(secret: bob, callID: callID, role: .callee)
        let opened = try CallCrypto.open(expectedPayload, key: callee.receive, callID: callID, signalType: "sdp_offer")
        #expect(String(decoding: opened, as: UTF8.self) == #"{"t":"offer","sdp":"v=0\r\n","n":1}"#)

        let back = try CallCrypto.seal(Data("answer".utf8), key: callee.send, callID: callID, signalType: "sdp_answer")
        #expect(try CallCrypto.open(back, key: caller.receive, callID: callID, signalType: "sdp_answer") == Data("answer".utf8))
    }

    @Test
    func reflectedRelabelledOrTamperedSignalsDoNotOpen() throws {
        let (alice, _) = try secrets()
        let caller = CallSignalKeys(secret: alice, callID: callID, role: .caller)
        // Sent back to its sender as if from the other side.
        #expect(throws: CallCrypto.CryptoError.openFailed) {
            try CallCrypto.open(expectedPayload, key: caller.receive, callID: callID, signalType: "sdp_offer")
        }
        let callee = CallSignalKeys(secret: alice, callID: callID, role: .callee)
        // Relabelled by the relay.
        #expect(throws: CallCrypto.CryptoError.openFailed) {
            try CallCrypto.open(expectedPayload, key: callee.receive, callID: callID, signalType: "sdp_answer")
        }
        // Moved to another call.
        #expect(throws: CallCrypto.CryptoError.openFailed) {
            try CallCrypto.open(expectedPayload, key: callee.receive, callID: UUID(), signalType: "sdp_offer")
        }
        var bytes = Data(base64Encoded: String(expectedPayload.dropFirst(3)))!
        bytes[20] ^= 0x01
        #expect(throws: CallCrypto.CryptoError.openFailed) {
            try CallCrypto.open("c1." + bytes.base64EncodedString(), key: callee.receive, callID: callID, signalType: "sdp_offer")
        }
        #expect(throws: CallCrypto.CryptoError.badPayload) {
            try CallCrypto.open("v=0", key: callee.receive, callID: callID, signalType: "sdp_offer")
        }
    }
}

private extension Data {
    init(hexString: String) {
        var bytes = [UInt8]()
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            bytes.append(UInt8(hexString[index..<next], radix: 16)!)
            index = next
        }
        self.init(bytes)
    }

    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}

private extension SymmetricKey {
    var hexString: String { withUnsafeBytes { Data($0) }.hexString }
}
