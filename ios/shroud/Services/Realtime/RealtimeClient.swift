import Foundation

/// WebSocket client for `/api/v1/ws` — auth then event fan-in.
/// Human: Real-time message/presence delivery; never sends plaintext message content.
/// Agent: Connects with session token; publishes decoded events on MainActor via handler.
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

    private var task: URLSessionWebSocketTask?
    private var session: URLSession?
    private var receiveLoop: Task<Void, Never>?
    private var token: String?
    private var onEvent: ((RealtimeEvent) -> Void)?

    func configure(onEvent: @escaping (RealtimeEvent) -> Void) {
        self.onEvent = onEvent
    }

    func connect(token: String) {
        if self.token == token, case .connected = state { return }
        disconnect()
        self.token = token
        state = .connecting

        let base = ServerConfigurationStore().load().resolvedBaseURL
            ?? URL(string: "http://127.0.0.1:8080/api/v1")!
        var components = URLComponents(url: base.appending(path: "ws"), resolvingAgainstBaseURL: false)
        // ws / wss from http / https
        if components?.scheme == "https" {
            components?.scheme = "wss"
        } else {
            components?.scheme = "ws"
        }
        guard let url = components?.url else {
            state = .failed("Invalid WebSocket URL")
            return
        }

        let session = URLSession(configuration: .default)
        self.session = session
        let task = session.webSocketTask(with: url)
        self.task = task
        task.resume()

        // First message must be auth within 10s.
        let authPayload = #"{"type":"auth","token":"\#(token)"}"#
        task.send(.string(authPayload)) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.state = .failed(error.localizedDescription)
                    self?.disconnect()
                }
            }
        }

        receiveLoop = Task { [weak self] in
            await self?.runReceiveLoop()
        }
    }

    func disconnect() {
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
    }

    private func handleText(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String
        else { return }

        switch type {
        case "auth.ok":
            state = .connected
        case "auth.error":
            state = .failed("WebSocket authentication failed")
            disconnect()
        case "message.new":
            if let event = RealtimeEvent.parseMessageNew(from: data) {
                onEvent?(event)
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
