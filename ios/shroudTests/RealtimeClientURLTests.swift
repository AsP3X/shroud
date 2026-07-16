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
}
