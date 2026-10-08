import Foundation

/// WebSocket client for `/api/v1/ws` — auth then event fan-in.
/// Human: Real-time message/presence delivery; never sends plaintext message content. The
/// server keeps one socket per device (a newer one replaces the older), so the chats and a
/// call share this one: it stays open while either holds it — a call keeps it after the chats
/// lock, and a call answered on a locked phone opens it without the chats.
/// Agent: `hold`/`release` per holder; every listener gets every event; auto-reconnects. The
/// upgrade names the build (`ClientIdentity`); one refused with 426 starts a version check and
/// retries on the normal backoff.
@MainActor
@Observable
final class RealtimeClient {
    static let shared = RealtimeClient()

    /// Who needs the socket open.
    enum Holder: Hashable {
        case messaging
        case call
    }

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
    /// Pings while connected. URLSession answers the server's pings (a socket silent for 75 s is
    /// closed and its device gets pushes), but only a ping of our own notices a dead socket.
    private var keepaliveTask: Task<Void, Never>?
    private static let keepaliveInterval: Duration = .seconds(25)
    private var token: String?
    private var listeners: [Holder: (RealtimeEvent) -> Void] = [:]
    private var holders: Set<Holder> = []
    private var reconnectAttempt = 0
    private var intentionalDisconnect = false
    /// What the server should treat this app as: in front, or away (background, locked).
    /// Sent after `auth.ok` and whenever it changes. A live socket counts as in front until
    /// this says otherwise, which is why a suspended phone used to swallow its pushes.
    private var wantsFocus = true
    /// The value the server has been told on this socket, so a repeat is not sent.
    private var sentFocus: Bool?

    /// `holder`'s handler for every event (replaces an earlier one of the same holder).
    func setListener(_ holder: Holder, _ handler: @escaping (RealtimeEvent) -> Void) {
        listeners[holder] = handler
    }

    /// Opens the socket (if needed) for `holder`.
    func hold(_ holder: Holder, token: String) {
        holders.insert(holder)
        connect(token: token)
    }

    /// `holder` is done with the socket; it closes once nobody holds it.
    func release(_ holder: Holder) {
        holders.remove(holder)
        if holders.isEmpty {
            disconnect(reconnect: false)
        }
    }

    func isHeld(by holder: Holder) -> Bool {
        holders.contains(holder)
    }

    /// The app came to the front or left it. Takes effect now, and again after the next connect.
    func noteFocus(_ focused: Bool) {
        wantsFocus = focused
    }

    /// Tells the server, if the socket is up. Returns whether that frame was sent.
    @discardableResult
    func deliverFocus() async -> Bool {
        guard case .connected = state, let task else { return false }
        if sentFocus == wantsFocus { return true }
        let focused = wantsFocus
        let payload: [String: Any] = ["type": "focus", "focused": focused]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let string = String(data: data, encoding: .utf8)
        else { return false }
        let sent: Bool = await withCheckedContinuation { continuation in
            task.send(.string(string)) { error in
                continuation.resume(returning: error == nil)
            }
        }
        if sent, wantsFocus == focused {
            sentFocus = focused
        }
        return sent
    }

    private func emit(_ event: RealtimeEvent) {
        for handler in listeners.values {
            handler(event)
        }
    }

    private func connect(token: String) {
        intentionalDisconnect = false
        if self.token == token, case .connected = state { return }
        if self.token == token, case .connecting = state { return }
        disconnect(reconnect: false)
        // `disconnect(reconnect: false)` marks the close as wanted; this socket's drops are not.
        intentionalDisconnect = false
        self.token = token
        openSocket()
    }

    /// Closes the socket. When `reconnect` is false, stops the reconnect loop (logout).
    private func disconnect(reconnect: Bool = false) {
        if !reconnect {
            intentionalDisconnect = true
            reconnectTask?.cancel()
            reconnectTask = nil
            reconnectAttempt = 0
        }
        receiveLoop?.cancel()
        receiveLoop = nil
        keepaliveTask?.cancel()
        keepaliveTask = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        session?.invalidateAndCancel()
        session = nil
        sentFocus = nil
        if state != .disconnected {
            state = .disconnected
        }
    }

    func sendTyping(peerUserID: UUID, isTyping: Bool) {
        sendPeerFlag(type: "typing", peerUserID: peerUserID, flag: "is_typing", value: isTyping)
    }

    func sendRecording(peerUserID: UUID, isRecording: Bool) {
        sendPeerFlag(type: "recording", peerUserID: peerUserID, flag: "is_recording", value: isRecording)
    }

    private func sendPeerFlag(type: String, peerUserID: UUID, flag: String, value: Bool) {
        guard case .connected = state, let task else { return }
        let payload: [String: Any] = [
            "type": type,
            "peer_user_id": peerUserID.uuidString.lowercased(),
            flag: value,
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
        guard let request = Self.webSocketRequest(from: base) else {
            state = .failed("Invalid WebSocket URL")
            scheduleReconnect()
            return
        }

        let session = URLSession(configuration: .default)
        self.session = session
        let task = session.webSocketTask(with: request)
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
            guard let self, let error else { return }
            Task { @MainActor in
                Self.noteRefusedUpgrade(task)
                self.state = .failed(error.localizedDescription)
                self.disconnect(reconnect: true)
                self.scheduleReconnect()
            }
        }

        receiveLoop = Task { [weak self] in
            await self?.runReceiveLoop()
        }
    }

    /// The upgrade request for `/api/v1/ws`, naming this build like every API request.
    nonisolated static func webSocketRequest(from apiBase: URL) -> URLRequest? {
        guard let url = webSocketURL(from: apiBase) else { return nil }
        var request = URLRequest(url: url)
        ClientIdentity.apply(to: &request)
        return request
    }

    /// The server refused the upgrade because this build is below its minimum: asks it about
    /// this build. The reconnect backoff keeps running as for any failed connect (1, 2, 4, 8,
    /// 16, then every 30 s), so a refused socket never retries in a tight loop.
    private static func noteRefusedUpgrade(_ task: URLSessionWebSocketTask) {
        guard isRefusedUpgrade(statusCode: (task.response as? HTTPURLResponse)?.statusCode) else { return }
        ClientVersionBridge.noteUpdateRequired()
    }

    /// A failed upgrade's status says this build is too old: HTTP 426. A failed upgrade has no
    /// body to read the `UPDATE_REQUIRED` code from; no response at all (offline) is not one.
    nonisolated static func isRefusedUpgrade(statusCode: Int?) -> Bool {
        statusCode == 426
    }

    /// Builds `ws(s)://…/api/v1/ws` from the REST base URL.
    nonisolated static func webSocketURL(from apiBase: URL) -> URL? {
        let components = URLComponents(url: apiBase, resolvingAgainstBaseURL: false)
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
                    Self.noteRefusedUpgrade(task)
                    state = .failed(error.localizedDescription)
                }
                break
            }
        }
        // Socket dropped — reconnect unless we logged out. A loop cancelled by `disconnect`
        // leaves reconnecting to whoever closed the socket.
        if !Task.isCancelled, !intentionalDisconnect, token != nil {
            scheduleReconnect()
        }
    }

    /// What an `auth.error` means for this socket.
    nonisolated enum AuthErrorOutcome: Equatable, Sendable {
        /// `DEVICE_REMOVED`: stop for good and wipe this iPhone.
        case removed
        /// `RATE_LIMITED` ("Too many WebSocket connections for this account."): the session is
        /// fine, the account just has as many sockets as the server allows. Try again later.
        case retryLater
        /// Anything else (`UNAUTHORIZED`, no code): stop. No sign-out here; the REST 401
        /// streak decides whether the session is over.
        case stop
    }

    /// `auth.error` policy (web `realtime.ts:9-21` and its selftest; android-port-specs
    /// api-realtime §11.8). iOS used to stop on every code, so an account over the socket cap
    /// lost real-time delivery on this iPhone until the next launch.
    nonisolated static func authErrorOutcome(code: String?) -> AuthErrorOutcome {
        switch code ?? "" {
        case APIError.deviceRemovedCode: .removed
        case "RATE_LIMITED": .retryLater
        default: .stop
        }
    }

    /// The backoff attempt a `RATE_LIMITED` socket waits at least: 2^5 s, capped at 30 s.
    nonisolated static let rateLimitedAttemptFloor = 5

    /// Seconds before reconnect attempt `attempt` (0-based): 1, 2, 4, 8, 16, then 30.
    nonisolated static func reconnectDelay(attempt: Int) -> Double {
        min(30.0, pow(2.0, Double(attempt)))
    }

    private func scheduleReconnect() {
        guard !intentionalDisconnect, token != nil else { return }
        reconnectTask?.cancel()
        let attempt = reconnectAttempt
        reconnectAttempt = min(reconnectAttempt + 1, 8)
        let delay = Self.reconnectDelay(attempt: attempt)
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, !Task.isCancelled, !self.intentionalDisconnect else { return }
            self.openSocket()
        }
    }

    private func startKeepalive() {
        keepaliveTask?.cancel()
        keepaliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.keepaliveInterval)
                guard let self, !Task.isCancelled, let task = self.task else { return }
                task.sendPing { [weak self] error in
                    guard error != nil, let client = self else { return }
                    Task { @MainActor in
                        guard client.task === task, !client.intentionalDisconnect else { return }
                        client.disconnect(reconnect: true)
                        client.scheduleReconnect()
                    }
                }
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
            reconnectAttempt = 0
            sentFocus = nil
            startKeepalive()
            Task { await self.deliverFocus() }
            // A reconnect: a call checks what it may have missed meanwhile.
            emit(.raw(type: type, json: json))
        case "auth.error":
            let outcome = Self.authErrorOutcome(code: (json["error"] as? [String: Any])?["code"] as? String)
            switch outcome {
            case .retryLater:
                // Too many sockets for this account: the session is still good, so keep
                // trying (web `realtime.ts:125-127`), but every 30 s — the `auth.ok` before
                // this error reset the backoff (android-port-specs api-realtime §11.7-11.8).
                reconnectAttempt = max(reconnectAttempt, Self.rateLimitedAttemptFloor)
                disconnect(reconnect: true)
                state = .failed("Too many WebSocket connections for this account.")
                scheduleReconnect()
            case .removed, .stop:
                state = .failed("WebSocket authentication failed")
                // Bad token — do not hammer reconnect with same token.
                intentionalDisconnect = true
                disconnect(reconnect: false)
                // The account removed this iPhone while the socket was open: wipe it now.
                if outcome == .removed, let token {
                    SessionAuthBridge.noteDeviceRemoved(token: token)
                }
            }
        case "message.new":
            if let event = RealtimeEvent.parseMessageNew(from: data) {
                emit(event)
            } else {
                // Log-friendly: decoding failed (schema/date) — fall back to poll via handler?
                emit(.raw(type: type, json: json))
            }
        case "message.delivered", "message.read", "message.deleted", "message.reaction",
             "reactions.seen",
             "conversation.deleted", "conversation.read", "conversation.mute",
             "typing", "recording", "presence.update", "call.ring", "call.accepted",
             "call.ended", "call.signal",
             "contact.request", "contact.accepted", "contact.rejected",
             "contact.cancelled", "contact.removed", "contact.updated":
            emit(.raw(type: type, json: json))
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
