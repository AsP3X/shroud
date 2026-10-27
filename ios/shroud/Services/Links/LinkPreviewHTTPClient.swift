import Foundation
import Network
import Security

/// GETs a link-preview URL over a connection to the address that was checked.
///
/// Human: The fetch rules (`LinkPreviewFetcher.allowedTarget` and the address check) only hold
/// if the connection goes where the check looked. URLSession resolves the name again on its own,
/// so a name with a very short TTL could answer a public address to the check and a private one
/// to the connection (DNS rebinding, security audit L17). Here the name is resolved once, every
/// address must be public, and the TCP connection is opened to one of those addresses. TLS still
/// uses the name: it is sent as SNI and the certificate is checked against it, so a hostile name
/// gains nothing by pointing at someone else's server. Redirects are followed here, by hand, and
/// every hop is checked and resolved again the same way.
/// Agent: HTTPS on port 443 only, no proxy, one request per connection. Limits: 8 s without
/// progress, 15 s for the whole call including redirects and the body, 16 redirects (URLSession's
/// limit), 64 KB of response head. Callers cap the body themselves and must `close()` the
/// response. Runs on any executor; cancelling the calling task cancels the connection.
nonisolated enum LinkPreviewHTTPClient {
    static let maxRedirects = 16
    /// Longest wait for a connection, a send, or the next bytes (URLSession's request timeout).
    static let idleTimeout: Double = 8
    /// Budget for the whole call (URLSession's resource timeout).
    static let resourceTimeout: Double = 15
    /// Addresses tried, in resolver order, before giving up on a host.
    static let maxAddressAttempts = 4
    /// `1xx` heads skipped before the real response (e.g. `103 Early Hints`).
    static let maxInterimResponses = 5

    /// GETs `url` (which must pass `allowedTarget`), following redirects, and returns once the
    /// final response's head has arrived.
    ///
    /// Agent: THROWS `notAllowed` when the first URL fails the rules or its addresses are not all
    /// public, `badResponse` when a redirect does, `tooManyRedirects`, `unsupportedEncoding`,
    /// `timedOut`, `CancellationError`, or the connection's `NWError`. Non-2xx responses are
    /// returned; the caller decides.
    static func get(_ url: URL, headers: [(name: String, value: String)]) async throws -> LinkPreviewHTTPResponse {
        let deadline = DispatchTime.now() + resourceTimeout
        var current = url
        var redirects = 0
        while true {
            try Task.checkCancellation()
            // A refused redirect fails like the 3xx URLSession would have handed back unfollowed.
            let refusal: LinkPreviewHTTPError = redirects == 0 ? .notAllowed : .badResponse
            guard let target = LinkPreviewFetcher.allowedTarget(current),
                  let host = LinkPreviewHTTP.hostName(of: target),
                  let request = LinkPreviewHTTP.request(for: target, headers: headers),
                  let addresses = LinkPreviewFetcher.publicAddresses(of: host)
            else { throw refusal }
            let connection = try await connect(to: addresses, serverName: host, deadline: deadline)
            do {
                try await connection.send(request)
                let (head, body) = try await readHead(from: connection)
                if let next = LinkPreviewHTTP.redirectTarget(of: head, from: target) {
                    connection.close()
                    redirects += 1
                    guard redirects <= maxRedirects else { throw LinkPreviewHTTPError.tooManyRedirects }
                    current = next
                    continue
                }
                guard let framing = LinkPreviewHTTP.bodyFraming(of: head) else {
                    throw LinkPreviewHTTPError.unsupportedEncoding
                }
                return LinkPreviewHTTPResponse(
                    url: target,
                    head: head,
                    connection: connection,
                    framing: framing,
                    buffered: body
                )
            } catch {
                connection.close()
                throw error
            }
        }
    }

    /// Opens a TLS connection to the first of `addresses` that answers.
    private static func connect(
        to addresses: [LinkPreviewFetcher.ResolvedAddress],
        serverName: String,
        deadline: DispatchTime
    ) async throws -> LinkPreviewConnection {
        var lastError: Error = LinkPreviewHTTPError.notAllowed
        for address in addresses.prefix(maxAddressAttempts) {
            try Task.checkCancellation()
            guard DispatchTime.now() < deadline else { throw LinkPreviewHTTPError.timedOut }
            guard let connection = LinkPreviewConnection(
                address: address,
                serverName: serverName,
                deadline: deadline,
                idleTimeout: idleTimeout
            ) else { continue }
            do {
                try await connection.open()
                return connection
            } catch {
                connection.close()
                if error is CancellationError { throw error }
                lastError = error
            }
        }
        throw lastError
    }

    /// Reads up to the end of the final (non-1xx) response head. Returns the head and the body
    /// bytes that arrived with it.
    private static func readHead(
        from connection: LinkPreviewConnection
    ) async throws -> (LinkPreviewHTTP.ResponseHead, Data) {
        var bytes: [UInt8] = []
        var interimResponses = 0
        while true {
            if let end = LinkPreviewHTTP.headEnd(in: bytes) {
                guard end.headLength <= LinkPreviewHTTP.maxHeadBytes,
                      let head = LinkPreviewHTTP.parseHead(Array(bytes[..<end.headLength]))
                else { throw LinkPreviewHTTPError.badResponse }
                bytes.removeFirst(end.bodyStart)
                if (100 ..< 200).contains(head.status) {
                    // 101 would switch protocols; nothing here asked for that.
                    interimResponses += 1
                    guard head.status != 101, interimResponses <= maxInterimResponses else {
                        throw LinkPreviewHTTPError.badResponse
                    }
                    continue
                }
                return (head, Data(bytes))
            }
            guard bytes.count <= LinkPreviewHTTP.maxHeadBytes,
                  let chunk = try await connection.receive()
            else { throw LinkPreviewHTTPError.badResponse }
            bytes.append(contentsOf: chunk)
        }
    }
}

/// A response whose head has been read; the body is read on demand.
///
/// Agent: Used by one task at a time. `nextChunk()` returns body bytes (never more than the
/// framing allows) and nil at the end; it THROWS `badResponse` for a body cut short of its
/// `Content-Length` or last chunk. Call `close()` when done, also after an error.
nonisolated final class LinkPreviewHTTPResponse {
    /// The URL that answered (after redirects).
    let url: URL
    let head: LinkPreviewHTTP.ResponseHead
    private let connection: LinkPreviewConnection
    private let framing: LinkPreviewHTTP.BodyFraming
    /// Body bytes received but not yet handed out.
    private var buffered: Data
    /// For `.length`: bytes still to come.
    private var remaining: Int
    private var decoder = LinkPreviewHTTP.ChunkedDecoder()
    private var finished = false

    init(
        url: URL,
        head: LinkPreviewHTTP.ResponseHead,
        connection: LinkPreviewConnection,
        framing: LinkPreviewHTTP.BodyFraming,
        buffered: Data
    ) {
        self.url = url
        self.head = head
        self.connection = connection
        self.framing = framing
        self.buffered = buffered
        if case let .length(length) = framing {
            remaining = length
        } else {
            remaining = 0
        }
    }

    var status: Int { head.status }

    /// Lowercased `Content-Type`, or "" without one.
    var contentType: String { head.value(named: "content-type")?.lowercased() ?? "" }

    /// The declared body size, when the body is framed by `Content-Length`.
    var contentLength: Int? {
        if case let .length(length) = framing { return length }
        return nil
    }

    func nextChunk() async throws -> Data? {
        guard !finished else { return nil }
        switch framing {
        case .empty:
            finished = true
            return nil
        case .length:
            guard remaining > 0 else {
                finished = true
                return nil
            }
            guard let data = try await nextBytes() else { throw LinkPreviewHTTPError.badResponse }
            let piece = data.count > remaining ? Data(data.prefix(remaining)) : data
            remaining -= piece.count
            return piece
        case .chunked:
            while true {
                if decoder.isFinished {
                    finished = true
                    return nil
                }
                guard let data = try await nextBytes() else { throw LinkPreviewHTTPError.badResponse }
                let decoded = try decoder.decode(data)
                if !decoded.isEmpty { return decoded }
            }
        case .untilClose:
            do {
                guard let data = try await nextBytes() else {
                    finished = true
                    return nil
                }
                return data
            } catch is NWError {
                // The body ends when the connection does; browsers accept a close without TLS
                // close_notify, and so does this. Timeouts and cancellation still throw.
                finished = true
                return nil
            }
        }
    }

    func close() {
        connection.close()
    }

    /// Bytes that arrived with the head first, then whatever the connection delivers next.
    private func nextBytes() async throws -> Data? {
        if !buffered.isEmpty {
            let data = buffered
            buffered = Data()
            return data
        }
        return try await connection.receive()
    }
}

/// One TLS connection to an address that passed the fetch rules, with the TLS server name and
/// certificate check bound to the host name.
///
/// Agent: Every operation (`open`, `send`, `receive`) fails with `timedOut` after `idleTimeout`
/// or at `deadline`, whichever is first, and with `CancellationError` when the calling task is
/// cancelled; either closes the connection. One operation at a time. All mutable state is
/// confined to `queue`, which also delivers every Network.framework callback.
nonisolated final class LinkPreviewConnection: @unchecked Sendable {
    static let receiveChunkBytes = 64 * 1024

    /// Waits for one operation. Touched on the connection's queue only.
    private nonisolated final class Waiter<Value: Sendable>: @unchecked Sendable {
        private var continuation: CheckedContinuation<Value, Error>?
        var timer: DispatchWorkItem?

        init(_ continuation: CheckedContinuation<Value, Error>) {
            self.continuation = continuation
        }

        /// Resumes the waiting task once; later calls do nothing.
        func finish(_ result: Result<Value, Error>) {
            guard let continuation else { return }
            self.continuation = nil
            timer?.cancel()
            timer = nil
            continuation.resume(with: result)
        }
    }

    private let connection: NWConnection
    private let queue: DispatchQueue
    private let deadline: DispatchTime
    private let idleTimeout: Double
    // Confined to `queue`.
    private var failCurrent: ((Error) -> Void)?
    private var onReady: (() -> Void)?
    private var closeReason: Error?
    private var sawEnd = false

    /// Nil when `address` is not a valid IPv4 or IPv6 address.
    init?(
        address: LinkPreviewFetcher.ResolvedAddress,
        serverName: String,
        deadline: DispatchTime,
        idleTimeout: Double
    ) {
        guard let host = LinkPreviewConnection.endpointHost(address) else { return nil }
        let queue = DispatchQueue(label: "com.shroud.link-preview")
        self.queue = queue
        self.deadline = deadline
        self.idleTimeout = idleTimeout
        connection = NWConnection(
            to: .hostPort(host: host, port: .https),
            using: LinkPreviewConnection.parameters(serverName: serverName, verifyQueue: queue, idleTimeout: idleTimeout)
        )
    }

    deinit {
        connection.cancel()
    }

    /// The address as a Network.framework host: an IP literal, so nothing is resolved again.
    static func endpointHost(_ address: LinkPreviewFetcher.ResolvedAddress) -> NWEndpoint.Host? {
        switch address {
        case let .v4(bytes):
            guard bytes.count == 4, let ip = IPv4Address(Data(bytes)) else { return nil }
            return NWEndpoint.Host.ipv4(ip)
        case let .v6(bytes):
            guard bytes.count == 16, let ip = IPv6Address(Data(bytes)) else { return nil }
            return NWEndpoint.Host.ipv6(ip)
        }
    }

    /// TLS 1.2+ over TCP, ALPN `http/1.1`, no proxy.
    ///
    /// Human: The endpoint is an IP address, so TLS is told the host name explicitly: it goes out
    /// as SNI, and the verify block evaluates the server's chain with the system's default SSL
    /// policy for that name (trusted roots, expiry, revocation and CT as the system checks them,
    /// no pinning). Without it the certificate would be checked against the IP address.
    /// No proxy: a proxy would resolve the name itself, past the address check, and would see
    /// every previewed link.
    static func parameters(serverName: String, verifyQueue: DispatchQueue, idleTimeout: Double) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let security = tls.securityProtocolOptions
        sec_protocol_options_set_tls_server_name(security, serverName)
        sec_protocol_options_add_tls_application_protocol(security, "http/1.1")
        sec_protocol_options_set_min_tls_protocol_version(security, .TLSv12)
        sec_protocol_options_set_verify_block(security, { _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard SecTrustSetPolicies(secTrust, SecPolicyCreateSSL(true, serverName as CFString)) == errSecSuccess else {
                complete(false)
                return
            }
            // Runs on `verifyQueue`, as SecTrustEvaluateAsyncWithError requires.
            let status = SecTrustEvaluateAsyncWithError(secTrust, verifyQueue) { _, trusted, _ in
                complete(trusted)
            }
            if status != errSecSuccess { complete(false) }
        }, verifyQueue)

        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = Int(idleTimeout.rounded(.up))
        tcp.noDelay = true

        let parameters = NWParameters(tls: tls, tcp: tcp)
        parameters.preferNoProxies = true
        return parameters
    }

    /// Connects and completes the TLS handshake.
    func open() async throws {
        try await perform { [self] (waiter: Waiter<Void>) in
            self.onReady = { waiter.finish(.success(())) }
            self.connection.stateUpdateHandler = { [weak self] state in
                self?.handle(state)
            }
            self.connection.start(queue: self.queue)
        }
    }

    func send(_ data: Data) async throws {
        try await perform { (waiter: Waiter<Void>) in
            self.connection.send(content: data, completion: .contentProcessed { error in
                if let error {
                    waiter.finish(.failure(error))
                } else {
                    waiter.finish(.success(()))
                }
            })
        }
    }

    /// The next bytes from the server, or nil once it has closed its side cleanly.
    func receive() async throws -> Data? {
        try await perform { (waiter: Waiter<Data?>) in
            if self.sawEnd {
                waiter.finish(.success(nil))
                return
            }
            self.connection.receive(
                minimumIncompleteLength: 1,
                maximumLength: LinkPreviewConnection.receiveChunkBytes
            ) { content, _, isComplete, error in
                if isComplete { self.sawEnd = true }
                if let content, !content.isEmpty {
                    waiter.finish(.success(content))
                } else if let error {
                    waiter.finish(.failure(error))
                } else if isComplete {
                    waiter.finish(.success(nil))
                } else {
                    waiter.finish(.failure(LinkPreviewHTTPError.badResponse))
                }
            }
        }
    }

    /// Cancels the connection and fails the operation in flight. Safe to call more than once.
    func close(because reason: Error = LinkPreviewHTTPError.closed) {
        queue.async {
            self.closeOnQueue(because: reason)
        }
    }

    // MARK: - On `queue`

    private func handle(_ state: NWConnection.State) {
        switch state {
        case .ready:
            let ready = onReady
            onReady = nil
            ready?()
        case let .waiting(error), let .failed(error):
            // `.waiting` would retry when the network changes; a preview does not wait for that.
            closeOnQueue(because: error)
        case .cancelled:
            closeOnQueue(because: LinkPreviewHTTPError.closed)
        default:
            break
        }
    }

    private func closeOnQueue(because reason: Error) {
        guard closeReason == nil else { return }
        closeReason = reason
        onReady = nil
        let fail = failCurrent
        failCurrent = nil
        fail?(reason)
        connection.cancel()
    }

    /// Starts one operation on `queue` and waits for `start` to finish its waiter, failing it on
    /// timeout, on cancellation of the calling task, or when the connection closes.
    private func perform<Value: Sendable>(
        _ start: @escaping @Sendable (Waiter<Value>) -> Void
    ) async throws -> Value {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Value, Error>) in
                let waiter = Waiter<Value>(continuation)
                self.queue.async { [self] in
                    if let reason = self.closeReason {
                        waiter.finish(.failure(reason))
                        return
                    }
                    self.failCurrent = { error in waiter.finish(.failure(error)) }
                    let timer = DispatchWorkItem { [weak self] in
                        waiter.finish(.failure(LinkPreviewHTTPError.timedOut))
                        self?.closeOnQueue(because: LinkPreviewHTTPError.timedOut)
                    }
                    waiter.timer = timer
                    let timeout = min(DispatchTime.now() + self.idleTimeout, self.deadline)
                    self.queue.asyncAfter(deadline: timeout, execute: timer)
                    start(waiter)
                }
            }
        } onCancel: {
            self.close(because: CancellationError())
        }
    }
}
