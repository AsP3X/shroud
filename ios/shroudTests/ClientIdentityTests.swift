import Foundation
import Testing
@testable import shroud

/// `X-Shroud-Client` on every request to the configured server, and what a `426 UPDATE_REQUIRED`
/// does: a version check, never a sign-out.
///
/// Serialized: the stub transport and the bridges are process-wide.
@MainActor
@Suite(.serialized)
struct ClientIdentityTests {
    private let base = URL(string: "https://chat.example.org/api/v1")!

    private func client() -> APIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubTransport.self]
        return APIClient(baseURL: base, session: URLSession(configuration: configuration))
    }

    // MARK: - The header

    @Test func headerNamesThisBuild() {
        #expect(ClientIdentity.headerField == "X-Shroud-Client")
        #expect(ClientIdentity.headerValue == "ios/\(ClientVersionService.currentVersion)")
        #expect(ClientVersionService.currentVersion != "0", "the test host reads the app's own version")
    }

    @Test func everyRequestPathCarriesTheHeaderToTheAPIHostOnly() async throws {
        StubTransport.reset(status: 200, body: Data("{}".utf8))
        let client = client()
        struct Empty: Codable {}

        _ = try await client.get("auth/me", query: ["a": "b"], as: Empty.self, bearerToken: "t")
        _ = try await client.post("messages", body: Empty(), as: Empty.self, bearerToken: "t")
        try await client.postNoContent(path: "auth/logout", bearerToken: "t")
        _ = try await client.deleteRaw(path: "reactions/1", bearerToken: "t")
        _ = try await client.response("PUT", path: "x", jsonBody: Data("{}".utf8))
        try await client.putRaw(path: "media/1", body: Data([1, 2]), contentType: "application/octet-stream", bearerToken: "t")
        try await client.putRaw(path: "media/2", body: Data([1]), contentType: "application/octet-stream", bearerToken: "t") { _ in }
        _ = try await client.getRaw(path: "media/3", bearerToken: "t")
        _ = try await client.getRaw(path: "media/4", bearerToken: "t") { _ in }

        let upload = FileManager.default.temporaryDirectory.appendingPathComponent("client-identity-\(UUID().uuidString)")
        try Data([9, 9]).write(to: upload)
        defer { try? FileManager.default.removeItem(at: upload) }
        try await client.putFile(path: "files/1", fileURL: upload, contentType: "application/octet-stream", bearerToken: "t")
        let downloaded = try await client.downloadFile(path: "files/2", bearerToken: "t")
        try? FileManager.default.removeItem(at: downloaded)

        let requests = StubTransport.requests
        #expect(requests.count == 11)
        for request in requests {
            #expect(request.url?.host() == "chat.example.org")
            #expect(request.url?.path().hasPrefix("/api/v1/") == true)
            #expect(request.value(forHTTPHeaderField: "X-Shroud-Client") == ClientIdentity.headerValue)
        }
        // The version check itself is never gated, but sends it too.
        _ = try? await client.get("client-version", query: ["platform": "ios", "version": "1"], as: Empty.self)
        #expect(StubTransport.requests.last?.value(forHTTPHeaderField: "X-Shroud-Client") == ClientIdentity.headerValue)
    }

    @Test func webSocketUpgradeCarriesTheHeader() throws {
        let request = try #require(RealtimeClient.webSocketRequest(from: base))
        #expect(request.url?.absoluteString == "wss://chat.example.org/api/v1/ws")
        #expect(request.value(forHTTPHeaderField: "X-Shroud-Client") == ClientIdentity.headerValue)
    }

    // MARK: - 426 UPDATE_REQUIRED

    @Test func updateRequiredRunsTheVersionCheckAndKeepsTheSession() async throws {
        let session = SessionController()
        session.applySessionForTests(Self.sampleSession)
        let calls = Counter()
        let versions = ClientVersionController(currentVersion: "1.0") { _, _ in
            await calls.bump()
            return ClientVersionResponse(status: .updateRequired, latestVersion: "1.1", updateURL: nil)
        }
        let servers = ServerConfigurationController(
            store: ServerConfigurationStore(defaults: UserDefaults(suiteName: "client-identity-tests")!)
        )
        SessionAuthBridge.controller = session
        ClientVersionBridge.controller = versions
        ClientVersionBridge.serverConfig = servers
        defer {
            SessionAuthBridge.controller = nil
            ClientVersionBridge.controller = nil
            ClientVersionBridge.serverConfig = nil
        }

        StubTransport.reset(
            status: 426,
            body: Data(#"{"error":{"code":"UPDATE_REQUIRED","message":"Update Shroud to keep using this server."}}"#.utf8)
        )
        let client = client()
        // Three in a row: three 401s would end the session.
        for _ in 0 ..< 3 {
            let error = await #expect(throws: APIError.self) {
                _ = try await client.get("conversations", as: [String].self, bearerToken: "test-token")
            }
            #expect(error?.isUpdateRequired == true)
        }
        try await waitUntil { await calls.value >= 1 }
        // Let any further hops land before counting.
        for _ in 0 ..< 20 { await Task.yield() }

        #expect(versions.isUpdateRequired)
        #expect(await calls.value == 1, "the first check answered; the rest were folded into it")
        #expect(session.isSignedIn)
        #expect(session.consecutiveAuthenticationFailures == 0)
        #expect(!session.pendingFullLocalWipe)
        #expect(StubTransport.requests.count == 3, "a refused request is not retried")
    }

    @Test func another426IsNotAnUpdateRequired() async throws {
        let calls = Counter()
        let versions = ClientVersionController(currentVersion: "1.0") { _, _ in
            await calls.bump()
            return ClientVersionResponse(status: .current, latestVersion: nil, updateURL: nil)
        }
        let servers = ServerConfigurationController(
            store: ServerConfigurationStore(defaults: UserDefaults(suiteName: "client-identity-tests")!)
        )
        ClientVersionBridge.controller = versions
        ClientVersionBridge.serverConfig = servers
        defer {
            ClientVersionBridge.controller = nil
            ClientVersionBridge.serverConfig = nil
        }

        StubTransport.reset(status: 426, body: Data(#"{"error":{"code":"SOMETHING_ELSE","message":"x"}}"#.utf8))
        await #expect(throws: APIError.self) {
            _ = try await client().get("conversations", as: [String].self, bearerToken: "t")
        }
        for _ in 0 ..< 20 { await Task.yield() }
        #expect(await calls.value == 0)
    }

    /// The version check itself refused (a proxy, a misconfigured server): asking again would
    /// only be refused again, so it doesn't start another check.
    @Test func a426OnTheVersionCheckDoesNotStartAnother() async throws {
        let calls = Counter()
        let versions = ClientVersionController(currentVersion: "1.0") { _, _ in
            await calls.bump()
            return ClientVersionResponse(status: .current, latestVersion: nil, updateURL: nil)
        }
        let servers = ServerConfigurationController(
            store: ServerConfigurationStore(defaults: UserDefaults(suiteName: "client-identity-tests")!)
        )
        ClientVersionBridge.controller = versions
        ClientVersionBridge.serverConfig = servers
        defer {
            ClientVersionBridge.controller = nil
            ClientVersionBridge.serverConfig = nil
        }

        StubTransport.reset(status: 426, body: Data(#"{"error":{"code":"UPDATE_REQUIRED","message":"x"}}"#.utf8))
        let error = await #expect(throws: APIError.self) {
            _ = try await client().get(
                ClientVersionService.path,
                query: ["platform": "ios", "version": "1.0"],
                as: ClientVersionResponse.self
            )
        }
        #expect(error?.isUpdateRequired == true)
        for _ in 0 ..< 20 { await Task.yield() }
        #expect(await calls.value == 0)
    }

    @Test func onlyAnUpdateRequiredOnAnotherRouteStartsACheck() {
        let body = Data(#"{"error":{"code":"UPDATE_REQUIRED","message":"x"}}"#.utf8)
        #expect(APIClient.startsVersionCheck(path: "conversations", status: 426, data: body))
        #expect(APIClient.startsVersionCheck(path: "/media/1", status: 426, data: body))
        #expect(!APIClient.startsVersionCheck(path: "client-version", status: 426, data: body))
        #expect(!APIClient.startsVersionCheck(path: "/client-version/", status: 426, data: body))
        #expect(!APIClient.startsVersionCheck(path: "conversations", status: 401, data: body))
        #expect(!APIClient.startsVersionCheck(path: "conversations", status: 426, data: Data()))
    }

    /// The socket can't read a refused upgrade's body: the status alone counts.
    @Test func onlyA426UpgradeIsARefusal() {
        #expect(RealtimeClient.isRefusedUpgrade(statusCode: 426))
        #expect(!RealtimeClient.isRefusedUpgrade(statusCode: nil))
        #expect(!RealtimeClient.isRefusedUpgrade(statusCode: 401))
        #expect(!RealtimeClient.isRefusedUpgrade(statusCode: 101))
    }

    @Test func updateRequiredErrorIsRecognised() {
        let body = Data(#"{"error":{"code":"UPDATE_REQUIRED","message":"x"}}"#.utf8)
        #expect(APIError.from(data: body, statusCode: 426).isUpdateRequired)
        #expect(!APIError.from(data: body, statusCode: 426).isAuthenticationFailure)
        #expect(!APIError.from(data: body, statusCode: 400).isUpdateRequired)
        #expect(!APIError.from(data: Data(), statusCode: 426).isUpdateRequired)
    }

    // MARK: - Helpers

    private func waitUntil(_ condition: () async -> Bool) async throws {
        for _ in 0 ..< 300 {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("timed out")
    }

    private static let sampleSession = SessionStore.Session(
        token: "test-token",
        userID: UUID(uuidString: "11111111-1111-1111-1111-111111111111")!,
        username: "tester",
        shareCode: nil,
        deviceID: UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
    )
}

private actor Counter {
    private(set) var value = 0
    func bump() { value += 1 }
}

/// Answers every request with one canned status and body, and records what was asked.
private nonisolated final class StubTransport: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private nonisolated(unsafe) static var recorded: [URLRequest] = []
    private nonisolated(unsafe) static var status = 200
    private nonisolated(unsafe) static var body = Data()

    static var requests: [URLRequest] { lock.withLock { recorded } }

    static func reset(status: Int, body: Data) {
        lock.withLock {
            recorded = []
            self.status = status
            self.body = body
        }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let (status, body) = Self.lock.withLock { () -> (Int, Data) in
            Self.recorded.append(request)
            return (Self.status, Self.body)
        }
        guard let url = request.url,
              let response = HTTPURLResponse(
                  url: url,
                  statusCode: status,
                  httpVersion: "HTTP/1.1",
                  headerFields: ["Content-Type": "application/json"]
              )
        else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
