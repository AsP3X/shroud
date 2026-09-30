import Foundation
import Testing
@testable import shroud

/// The Calls tab's rows, from `GET /calls` as the server sends them: whose side, who the other
/// person is, how it ended and how long the two talked.
@MainActor
struct CallHistoryTests {
    private let me = UUID(uuidString: "00000000-0000-0000-0000-00000000000a")!
    private let them = UUID(uuidString: "00000000-0000-0000-0000-00000000000b")!
    private let myPhone = UUID(uuidString: "00000000-0000-0000-0000-0000000000d1")!
    private let myLaptop = UUID(uuidString: "00000000-0000-0000-0000-0000000000d2")!
    private let theirPhone = UUID(uuidString: "00000000-0000-0000-0000-0000000000d3")!

    private func call(
        caller: UUID,
        callerDevice: UUID,
        callee: UUID,
        calleeDevice: UUID?,
        status: String,
        reason: String? = nil,
        answered: String? = nil,
        ended: String? = "2026-09-30T09:45:00.123456Z",
        calleeName: String? = "anna"
    ) -> CallDTO {
        var fields: [String] = [
            #""id": "11111111-1111-1111-1111-111111111111""#,
            #""caller_user_id": "\#(caller.uuidString.lowercased())""#,
            #""caller_device_id": "\#(callerDevice.uuidString.lowercased())""#,
            #""caller_username": "\#(caller == me ? "me" : "anna")""#,
            #""callee_user_id": "\#(callee.uuidString.lowercased())""#,
            #""modality": "video""#,
            #""status": "\#(status)""#,
            #""protocol": 2"#,
            #""created_at": "2026-09-30T09:41:00.5Z""#,
        ]
        fields.append(calleeName.map { #""callee_username": "\#($0)""# } ?? #""callee_username": null"#)
        if let calleeDevice { fields.append(#""callee_device_id": "\#(calleeDevice.uuidString.lowercased())""#) }
        if let reason { fields.append(#""ended_reason": "\#(reason)""#) }
        if let answered { fields.append(#""answered_at": "\#(answered)""#) }
        if let ended { fields.append(#""ended_at": "\#(ended)""#) }
        let json = "{" + fields.joined(separator: ",") + "}"
        return try! JSONDecoder.api.decode(CallDTO.self, from: Data(json.utf8))
    }

    @Test func outgoingCallNamesTheCalleeAndCountsTheTalk() throws {
        let dto = call(
            caller: me, callerDevice: myPhone, callee: them, calleeDevice: theirPhone,
            status: "ended", reason: "hangup", answered: "2026-09-30T09:41:48.123456Z"
        )
        let row = try #require(CallController.recentCall(from: dto, me: me, myDevice: myPhone, connectedHere: nil))
        #expect(row.isOutgoing)
        #expect(row.peerUserID == them)
        #expect(row.peerUsername == "anna")
        #expect(!row.peerDeleted)
        #expect(row.modality == .video)
        #expect(row.status == "ended")
        #expect(row.connected)
        #expect(abs(try #require(row.duration) - 192) < 0.001)
        #expect(row.at == dto.createdAt)
    }

    @Test func incomingCallNamesTheCaller() throws {
        let dto = call(caller: them, callerDevice: theirPhone, callee: me, calleeDevice: nil, status: "missed", reason: "timeout")
        let row = try #require(CallController.recentCall(from: dto, me: me, myDevice: myPhone, connectedHere: nil))
        #expect(!row.isOutgoing)
        #expect(row.peerUserID == them)
        #expect(row.peerUsername == "anna")
        #expect(row.status == "missed")
        #expect(!row.connected)
        #expect(row.duration == nil)
    }

    @Test func callsPlacedFromAnotherOfOurDevicesAreListed() throws {
        let dto = call(
            caller: me, callerDevice: myLaptop, callee: them, calleeDevice: theirPhone,
            status: "ended", reason: "hangup", answered: "2026-09-30T09:42:00Z"
        )
        let row = try #require(CallController.recentCall(from: dto, me: me, myDevice: myPhone, connectedHere: nil))
        #expect(row.isOutgoing)
        #expect(row.connected)
        #expect(row.duration != nil)
    }

    @Test func ringingAndRunningCallsWaitUntilTheyEnd() {
        let ringing = call(caller: them, callerDevice: theirPhone, callee: me, calleeDevice: nil, status: "ringing", ended: nil)
        let active = call(
            caller: me, callerDevice: myPhone, callee: them, calleeDevice: theirPhone,
            status: "active", answered: "2026-09-30T09:42:00Z", ended: nil
        )
        #expect(CallController.recentCall(from: ringing, me: me, myDevice: myPhone, connectedHere: nil) == nil)
        #expect(CallController.recentCall(from: active, me: me, myDevice: myPhone, connectedHere: nil) == nil)
    }

    @Test func aDeclineReadsDeclined() throws {
        let dto = call(caller: me, callerDevice: myPhone, callee: them, calleeDevice: nil, status: "missed", reason: "declined")
        let row = try #require(CallController.recentCall(from: dto, me: me, myDevice: myPhone, connectedHere: nil))
        #expect(row.status == "rejected")
    }

    /// Answered, but the media never connected on this phone: not a completed call.
    @Test func thisPhoneKnowsBetterWhenItRanTheCall() throws {
        let dto = call(
            caller: me, callerDevice: myPhone, callee: them, calleeDevice: theirPhone,
            status: "ended", reason: "hangup", answered: "2026-09-30T09:42:00Z"
        )
        let row = try #require(CallController.recentCall(from: dto, me: me, myDevice: myPhone, connectedHere: false))
        #expect(!row.connected)
        #expect(row.duration == nil)
    }

    /// This phone gave up on the ring, and the laptop then answered: the laptop's call talked.
    @Test func anotherDevicesCallIgnoresThisPhonesFailure() throws {
        let dto = call(
            caller: them, callerDevice: theirPhone, callee: me, calleeDevice: myLaptop,
            status: "ended", reason: "hangup", answered: "2026-09-30T09:42:00Z"
        )
        let row = try #require(CallController.recentCall(from: dto, me: me, myDevice: myPhone, connectedHere: false))
        #expect(row.connected)
        #expect(row.duration != nil)
    }

    @Test func aDeletedAccountHasNoNameAndNoCallBack() throws {
        let dto = call(
            caller: me, callerDevice: myPhone, callee: them, calleeDevice: nil,
            status: "cancelled", reason: "cancelled", calleeName: nil
        )
        let row = try #require(CallController.recentCall(from: dto, me: me, myDevice: myPhone, connectedHere: nil))
        #expect(row.peerDeleted)
        #expect(row.peerUsername == "Deleted account")
    }

    @Test func durationsReadLikeTheCallTimer() {
        #expect(CallsView.durationLabel(0) == "0:00")
        #expect(CallsView.durationLabel(42.9) == "0:42")
        #expect(CallsView.durationLabel(252) == "4:12")
        #expect(CallsView.durationLabel(3723) == "1:02:03")
    }
}
