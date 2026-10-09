import Foundation
import Testing
@testable import shroud

struct RealtimeClientURLTests {
    @Test
    func buildsWssFromHttpsApiBase() {
        let base = URL(string: "https://shroud.corespace.de/api/v1")!
        let ws = RealtimeClient.webSocketURL(from: base)
        #expect(ws?.absoluteString == "wss://shroud.corespace.de/api/v1/ws")
    }

    @Test
    func buildsWsFromHttpLocal() {
        let base = URL(string: "http://127.0.0.1:8080/api/v1")!
        let ws = RealtimeClient.webSocketURL(from: base)
        #expect(ws?.absoluteString == "ws://127.0.0.1:8080/api/v1/ws")
    }

    @Test
    func doesNotDoubleAppendWs() {
        let base = URL(string: "https://example.com/api/v1/ws")!
        let ws = RealtimeClient.webSocketURL(from: base)
        #expect(ws?.absoluteString == "wss://example.com/api/v1/ws")
    }

    /// Same table as web `realtime.selftest.ts`: only `RATE_LIMITED` keeps the socket trying,
    /// only `DEVICE_REMOVED` is a removal, no code stops.
    @Test
    func authErrorsStopExceptTooManySockets() {
        #expect(RealtimeClient.authErrorOutcome(code: "UNAUTHORIZED") == .stop)
        #expect(RealtimeClient.authErrorOutcome(code: "RATE_LIMITED") == .retryLater)
        #expect(RealtimeClient.authErrorOutcome(code: nil) == .stop)
        #expect(RealtimeClient.authErrorOutcome(code: "DEVICE_REMOVED") == .removed)
        #expect(RealtimeClient.authErrorOutcome(code: "DEVICE_REMOVED", reason: "account_deleted") == .accountDeleted)
        #expect(RealtimeClient.authErrorOutcome(code: "DEVICE_REMOVED", reason: nil) == .removed)
        #expect(RealtimeClient.authErrorOutcome(code: "UNAUTHORIZED", reason: "account_deleted") == .stop)
        #expect(RealtimeClient.authErrorOutcome(code: "rate_limited") == .stop)
    }

    /// A dropped socket retries after 1, 2, 4, 8, 16, 30, 30 s; a `RATE_LIMITED` one (whose
    /// `auth.ok` reset the attempt) waits 30 s, not 1 s.
    @Test
    func backoffDoublesToThirtySecondsAndRateLimitedStartsThere() {
        #expect((0 ..< 8).map(RealtimeClient.reconnectDelay(attempt:)) == [1, 2, 4, 8, 16, 30, 30, 30])
        let afterAuthOK = 0
        let floored = max(afterAuthOK, RealtimeClient.rateLimitedAttemptFloor)
        #expect(RealtimeClient.reconnectDelay(attempt: floored) == 30)
    }
}
