import Foundation
import Testing
@testable import shroud

/// "Always relay calls": relayed when the server has a relay, refused when it has none, and
/// direct paths first when the switch is off.
@MainActor
struct CallRelayPolicyTests {
    private let turn = Self.server(#"{"urls": ["turn:turn.example:3478"], "username": "1:u", "credential": "c"}"#)
    private let turns = Self.server(#"{"urls": ["TURNS:turn.example:5349"], "username": "1:u", "credential": "c"}"#)
    private let stun = Self.server(#"{"urls": ["stun:stun.example:3478"]}"#)

    /// As `GET /calls/ice-servers` sends them.
    private static func server(_ json: String) -> IceServerDTO {
        try! JSONDecoder.api.decode(IceServerDTO.self, from: Data(json.utf8))
    }

    @Test func recognisesTurnAndTurnsButNotStun() {
        #expect(CallMediaEngine.offersRelay([stun, turn]))
        #expect(CallMediaEngine.offersRelay([turns]))
        #expect(!CallMediaEngine.offersRelay([stun]))
        #expect(!CallMediaEngine.offersRelay([]))
    }

    @Test func offMeansDirectPathsFirst() throws {
        #expect(try CallController.relayPolicy(for: [stun, turn], alwaysRelay: false) == false)
        #expect(try CallController.relayPolicy(for: [], alwaysRelay: false) == false)
    }

    @Test func onStaysOnTheRelay() throws {
        #expect(try CallController.relayPolicy(for: [stun, turn], alwaysRelay: true))
    }

    @Test func onWithoutARelayRefusesTheCall() {
        #expect(throws: CallRelayUnavailable.self) {
            try CallController.relayPolicy(for: [stun], alwaysRelay: true)
        }
    }
}
