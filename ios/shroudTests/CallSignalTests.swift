import CryptoKit
import Foundation
import Testing
@testable import shroud

/// Sealed signal bodies and the end-of-call lines, against docs/calls.md.
struct CallSignalTests {
    private let callID = UUID(uuidString: "0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b")!

    private func secret() throws -> SymmetricKey {
        let alice = try Curve25519.KeyAgreement.PrivateKey(
            rawRepresentation: Data(hexString: "ef80f1878a337c4c39eeb6578cf9af4c38bc681ed61ec43084d00d1e1657723d")
        )
        return try CallCrypto.callSecret(
            ourPrivateKey: alice,
            ourPublicKey: Data(hexString: "8b29cac884916cb7098ba9d90b61ef21d88596315cd1d04799a1962012e0b467"),
            peerPublicKey: Data(hexString: "389c6f5486c58d1c61ee17df7793440e1e5e4b7822cae03fc92c9d2a4920e338")
        )
    }

    @Test
    func anOfferRoundTripsAndAReflectedCopyDoesNotOpen() throws {
        let secret = try secret()
        let caller = CallSignalKeys(secret: secret, callID: callID, role: .caller)
        let callee = CallSignalKeys(secret: secret, callID: callID, role: .callee)
        let offer = CallSignal.offer(sdp: "v=0\r\n", restart: false)
        let payload = try CallCrypto.seal(
            offer.plaintext(n: 1),
            key: caller.send,
            callID: callID,
            signalType: offer.signalType
        )
        let opened = try CallCrypto.open(payload, key: callee.receive, callID: callID, signalType: "sdp_offer")
        let parsed = try CallSignal.parse(opened, signalType: "sdp_offer")
        #expect(parsed.n == 1)
        #expect(parsed.signal == .offer(sdp: "v=0\r\n", restart: false))
        // Reflected back to the sender: their receive key is the other direction.
        #expect(throws: CallCrypto.CryptoError.openFailed) {
            try CallCrypto.open(payload, key: caller.receive, callID: callID, signalType: "sdp_offer")
        }
    }

    @Test
    func relabellingTheSignalTypeFails() throws {
        let keys = CallSignalKeys(secret: try secret(), callID: callID, role: .callee)
        let payload = try CallCrypto.seal(
            CallSignal.restartRequest.plaintext(n: 3),
            key: keys.send,
            callID: callID,
            signalType: "renegotiate"
        )
        #expect(throws: CallCrypto.CryptoError.openFailed) {
            try CallCrypto.open(payload, key: keys.receive, callID: callID, signalType: "sdp_offer")
        }
    }

    @Test
    func candidatesAndMediaKeepTheirFields() throws {
        let ice = CallSignal.candidates([
            IceCandidatePayload(candidate: "candidate:1 1 udp 1 1.2.3.4 9 typ host", sdpMid: "0", sdpMLineIndex: 0),
        ])
        let parsed = try CallSignal.parse(ice.plaintext(n: 2), signalType: "ice_candidate")
        #expect(parsed.signal == ice)
        let media = try CallSignal.parse(
            CallSignal.media(mic: false, camera: true).plaintext(n: 4),
            signalType: "media_state"
        )
        #expect(media.signal == .media(mic: false, camera: true))
    }

    @Test
    func endLinesMatchTheCallTable() {
        #expect(CallEndReason.from(status: "rejected", reason: "rejected", isOutgoing: true)?.announcement == "Declined")
        #expect(CallEndReason.from(status: "rejected", reason: "rejected", isOutgoing: false)?.announcement == nil)
        #expect(CallEndReason.from(status: "missed", reason: "timeout", isOutgoing: true)?.announcement == "No answer")
        #expect(CallEndReason.from(status: "missed", reason: "timeout", isOutgoing: false)?.announcement == "Missed call")
        #expect(CallEndReason.from(status: "cancelled", reason: "cancelled", isOutgoing: true)?.announcement == nil)
        #expect(CallEndReason.from(status: "cancelled", reason: "connection_lost", isOutgoing: true)?.announcement == "Connection lost")
        #expect(CallEndReason.from(status: "cancelled", reason: "cancelled", isOutgoing: false)?.announcement == "Missed call")
        #expect(CallEndReason.from(status: "ended", reason: "hangup", isOutgoing: true)?.announcement == "Call ended")
        #expect(CallEndReason.from(status: "ended", reason: "connection_lost", isOutgoing: false)?.announcement == "Connection lost")
        #expect(CallEndReason.from(status: "ringing", reason: nil, isOutgoing: true) == nil)
    }

    @Test
    func aRepeatedSignalNumberIsDropped() {
        var sequencer = CallSignalSequencer()
        let first = sequencer.accept(1)
        let again = sequencer.accept(1)
        let second = sequencer.accept(2)
        #expect(first)
        #expect(!again)
        #expect(second)
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
}
