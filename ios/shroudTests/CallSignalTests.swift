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

    /// A camera switch lost in a socket gap comes back from the server, possibly after a newer
    /// one arrived over the socket: only newer media states count, per device.
    @Test
    func anOlderMediaStateDoesNotUndoANewerOne() {
        var order = CallMediaOrder()
        let first = order.isNewer(4, from: "DEV-A")
        let newer = order.isNewer(9, from: "dev-a")
        let older = order.isNewer(6, from: "dev-a")
        let same = order.isNewer(9, from: "dev-a")
        let otherDevice = order.isNewer(2, from: "dev-b")
        #expect(first)
        #expect(newer)
        #expect(!older)
        #expect(!same)
        #expect(otherDevice)
    }

    /// The server's copy of the other device's latest media state rides on `GET /calls/:id` and
    /// the heartbeat, and opens like the signal it was.
    @Test
    func aKeptMediaStateDecodesAndOpens() throws {
        let secret = try secret()
        let caller = CallSignalKeys(secret: secret, callID: callID, role: .caller)
        let callee = CallSignalKeys(secret: secret, callID: callID, role: .callee)
        let payload = try CallCrypto.seal(
            CallSignal.media(mic: true, camera: true).plaintext(n: 7),
            key: caller.send,
            callID: callID,
            signalType: "media_state"
        )
        let device = UUID()
        var object: [String: Any] = [
            "id": callID.uuidString.lowercased(),
            "caller_user_id": UUID().uuidString,
            "caller_device_id": device.uuidString,
            "caller_username": "alice",
            "callee_user_id": UUID().uuidString,
            "callee_device_id": UUID().uuidString,
            "callee_username": "bob",
            "modality": "voice",
            "status": "active",
            "protocol": 2,
            "created_at": "2026-09-26T10:00:00Z",
            "answered_at": "2026-09-26T10:00:05Z",
            "peer_media_state": ["from_device_id": device.uuidString.lowercased(), "payload": payload],
        ]
        let call = try JSONDecoder.api.decode(CallDTO.self, from: JSONSerialization.data(withJSONObject: object))
        #expect(call.callModality == .voice)
        let kept = try #require(call.peerMediaState)
        #expect(kept.fromDeviceId == device)
        let opened = try CallCrypto.open(kept.payload, key: callee.receive, callID: callID, signalType: "media_state")
        let parsed = try CallSignal.parse(opened, signalType: "media_state")
        #expect(parsed.n == 7)
        #expect(parsed.signal == .media(mic: true, camera: true))

        // Everywhere else the field is absent.
        object["peer_media_state"] = nil
        let plain = try JSONDecoder.api.decode(CallDTO.self, from: JSONSerialization.data(withJSONObject: object))
        #expect(plain.peerMediaState == nil)
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
