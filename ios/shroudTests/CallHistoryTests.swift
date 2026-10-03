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
        calleeName: String? = "anna",
        calleeDeleted: Bool = false
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
        if calleeDeleted { fields.append(#""callee_deleted": true"#) }
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
            status: "cancelled", reason: "cancelled", calleeName: nil, calleeDeleted: true
        )
        let row = try #require(CallController.recentCall(from: dto, me: me, myDevice: myPhone, connectedHere: nil))
        #expect(row.peerDeleted)
        #expect(row.peerUsername == "Deleted account")
    }

    private func recent(with peer: UUID, minutesAgo: Double) -> CallController.RecentCall {
        CallController.RecentCall(
            id: UUID(),
            peerUserID: peer,
            peerUsername: peer == them ? "anna" : "ben",
            modality: .voice,
            isOutgoing: true,
            status: "ended",
            connected: true,
            at: Date(timeIntervalSince1970: 1_790_000_000 - minutesAgo * 60)
        )
    }

    /// Anna, Anna, Ben, Anna: the first two share a section, Ben's call ends it.
    @Test func backToBackCallsWithOnePersonShareASection() {
        let ben = UUID()
        let list = [
            recent(with: them, minutesAgo: 0),
            recent(with: them, minutesAgo: 5),
            recent(with: ben, minutesAgo: 10),
            recent(with: them, minutesAgo: 15),
        ]
        let runs = CallsView.runs(of: list)
        #expect(runs.map(\.calls.count) == [2, 1, 1])
        #expect(runs[0].latest == list[0])
        #expect(runs[0].id == list[1].id)
        #expect(runs[2].calls == [list[3]])
    }

    /// A section spans an hour at most, counted from its newest call.
    @Test func aSectionSpansAnHourAtMost() {
        let list = [
            recent(with: them, minutesAgo: 0),
            recent(with: them, minutesAgo: 40),
            recent(with: them, minutesAgo: 60),
            recent(with: them, minutesAgo: 61),
            recent(with: them, minutesAgo: 100),
        ]
        let runs = CallsView.runs(of: list)
        #expect(runs.map(\.calls.count) == [3, 2])
        #expect(runs[1].latest == list[3])
    }

    /// Red "Missed" is for their calls we never took: rang out, or they gave up. Our own
    /// unanswered calls and the ones we declined are not missed.
    @Test func onlyTheirUntakenCallsCountAsMissed() {
        func call(outgoing: Bool, status: String) -> CallController.RecentCall {
            CallController.RecentCall(
                id: UUID(), peerUserID: them, peerUsername: "anna", modality: .voice,
                isOutgoing: outgoing, status: status, connected: false, at: Date()
            )
        }
        #expect(CallsView.isMissed(call(outgoing: false, status: "missed")))
        #expect(CallsView.isMissed(call(outgoing: false, status: "cancelled")))
        #expect(!CallsView.isMissed(call(outgoing: false, status: "rejected")))
        #expect(!CallsView.isMissed(call(outgoing: true, status: "missed")))
        #expect(!CallsView.isMissed(call(outgoing: true, status: "cancelled")))
    }

    @Test func durationsReadLikeTheCallTimer() {
        #expect(CallsView.durationLabel(0) == "0:00")
        #expect(CallsView.durationLabel(42.9) == "0:42")
        #expect(CallsView.durationLabel(252) == "4:12")
        #expect(CallsView.durationLabel(3723) == "1:02:03")
    }
}
