import Foundation

/// WebSocket client for `/api/v1/ws` — auth then event fan-in.
/// Human: Real-time message/presence delivery; never sends plaintext message content.
/// Agent: Connects with session token; auto-reconnects; publishes events on MainActor.
@MainActor
@Observable
final class RealtimeClient {
    enum ConnectionState: Equatable {
        case disconnected
        case connecting
        case connected
        case failed(String)
    }

    private(set) var state: ConnectionState = .disconnected
    /// True when the socket has completed `auth.ok` (safe for typing etc.).
    var isConnected: Bool {
        if case .connected = state { return true }
        return false
    }

    private var task: URLSessionWebSocketTask?
    private var session: URLSession?
    private var receiveLoop: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var token: String?
    private var onEvent: ((RealtimeEvent) -> Void)?
    private var reconnectAttempt = 0
    private var intentionalDisconnect = false

    func configure(onEvent: @escaping (RealtimeEvent) -> Void) {
        self.onEvent = onEvent
    }

    func connect(token: String) {
        intentionalDisconnect = false
        if self.token == token, case .connected = state { return }
        if self.token == token, case .connecting = state { return }
        disconnect(reconnect: false)
        self.token = token
        openSocket()
    }

    /// Closes the socket. When `reconnect` is false, stops the reconnect loop (logout).
    func disconnect(reconnect: Bool = false) {
        if !reconnect {
            intentionalDisconnect = true
            reconnectTask?.cancel()
            reconnectTask = nil
            reconnectAttempt = 0
        }
        receiveLoop?.cancel()
        receiveLoop = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        session?.invalidateAndCancel()
        session = nil
        if state != .disconnected {
            state = .disconnected
        }
    }

    func sendTyping(peerUserID: UUID, isTyping: Bool) {
        guard case .connected = state, let task else { return }
        let payload: [String: Any] = [
            "type": "typing",
            "peer_user_id": peerUserID.uuidString.lowercased(),
            "is_typing": isTyping,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let string = String(data: data, encoding: .utf8)
        else { return }
        task.send(.string(string)) { _ in }
    }

    // MARK: - Private

    private func openSocket() {
        guard let token else { return }
        state = .connecting

        let base = ServerConfigurationStore().load().resolvedBaseURL
            ?? URL(string: "http://127.0.0.1:8080/api/v1")!
        guard let url = Self.webSocketURL(from: base) else {
            state = .failed("Invalid WebSocket URL")
            scheduleReconnect()
            return
        }

        let session = URLSession(configuration: .default)
        self.session = session
        let task = session.webSocketTask(with: url)
        self.task = task
        task.resume()

        // First message must be auth within 10s — encode JSON safely.
        let authObject: [String: String] = ["type": "auth", "token": token]
        guard let authData = try? JSONSerialization.data(withJSONObject: authObject),
              let authPayload = String(data: authData, encoding: .utf8)
        else {
            state = .failed("Could not encode WebSocket auth payload")
            disconnect(reconnect: true)
            scheduleReconnect()
            return
        }
        task.send(.string(authPayload)) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.state = .failed(error.localizedDescription)
                    self?.disconnect(reconnect: true)
                    self?.scheduleReconnect()
                }
            }
        }

        receiveLoop = Task { [weak self] in
            await self?.runReceiveLoop()
        }
    }

    /// Builds `ws(s)://…/api/v1/ws` from the REST base URL.
    nonisolated static func webSocketURL(from apiBase: URL) -> URL? {
        var components = URLComponents(url: apiBase, resolvingAgainstBaseURL: false)
        guard var comps = components else { return nil }
        switch comps.scheme?.lowercased() {
        case "https":
            comps.scheme = "wss"
        case "http":
            comps.scheme = "ws"
        case "wss", "ws":
            break
        default:
            comps.scheme = "ws"
        }
        // Ensure path ends with /ws (api base is typically …/api/v1).
        var path = comps.path
        while path.hasSuffix("/") { path.removeLast() }
        if !path.hasSuffix("/ws") {
            path += "/ws"
        }
        comps.path = path
        comps.query = nil
        comps.fragment = nil
        return comps.url
    }

    private func runReceiveLoop() async {
        while !Task.isCancelled {
            guard let task else { break }
            do {
                let message = try await task.receive()
                switch message {
                case let .string(text):
                    handleText(text)
                case .data:
                    break
                @unknown default:
                    break
                }
            } catch {
                if !Task.isCancelled {
                    state = .failed(error.localizedDescription)
                }
                break
            }
        }
        // Socket dropped — reconnect unless we logged out.
        if !intentionalDisconnect, token != nil {
            scheduleReconnect()
        }
    }

    private func scheduleReconnect() {
        guard !intentionalDisconnect, token != nil else { return }
        reconnectTask?.cancel()
        let attempt = reconnectAttempt
        reconnectAttempt = min(reconnectAttempt + 1, 8)
        // 1s, 2s, 4s, … capped ~30s
        let delay = min(30.0, pow(2.0, Double(attempt)))
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, !Task.isCancelled, !self.intentionalDisconnect else { return }
            self.openSocket()
        }
    }

    private func handleText(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String
        else { return }

        switch type {
        case "auth.ok":
            state = .connected
            reconnectAttempt = 0
        case "auth.error":
            state = .failed("WebSocket authentication failed")
            // Bad token — do not hammer reconnect with same token.
            intentionalDisconnect = true
            disconnect(reconnect: false)
        case "message.new":
            if let event = RealtimeEvent.parseMessageNew(from: data) {
                onEvent?(event)
            } else {
                // Log-friendly: decoding failed (schema/date) — fall back to poll via handler?
                onEvent?(.raw(type: type, json: json))
            }
        case "message.delivered", "message.read", "message.deleted",
             "typing", "presence.update", "call.ring", "call.accepted",
             "call.ended", "call.signal":
            onEvent?(.raw(type: type, json: json))
        default:
            break
        }
    }
}

enum RealtimeEvent: Sendable {
    case messageNew(MessageDTO)
    case raw(type: String, json: [String: Any])

    static func parseMessageNew(from data: Data) -> RealtimeEvent? {
        struct Envelope: Decodable {
            let type: String
            let message: MessageDTO
        }
        guard let env = try? JSONDecoder.api.decode(Envelope.self, from: data) else { return nil }
        return .messageNew(env.message)
    }
}
