import Foundation
import Testing
@testable import shroud

/// A password call the server refuses for a moment, while every Argon2 slot is busy
/// (`429` with `Retry-After: 1`, security audit M3), is sent once more instead of failing.
@MainActor
struct PasswordCallRetryTests {
    private func answer(_ status: Int, retryAfter: String?) -> HTTPURLResponse {
        let headers = retryAfter.map { ["Retry-After": $0] } ?? [:]
        return HTTPURLResponse(
            url: URL(string: "https://shroud.test/api/v1/auth/login")!,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: headers
        )!
    }

    @Test
    func passwordCallsAreTheOnesThatCheckAPassword() {
        #expect(APIClient.checksPassword(method: "POST", path: "auth/login"))
        #expect(APIClient.checksPassword(method: "POST", path: "/auth/register"))
        #expect(APIClient.checksPassword(method: "DELETE", path: "auth/account"))
        #expect(APIClient.checksPassword(method: "DELETE", path: "devices/3f2504e0-4f89-41d3-9a0c-0305e82c3301"))
        #expect(!APIClient.checksPassword(method: "GET", path: "auth/login"))
        #expect(!APIClient.checksPassword(method: "POST", path: "messages"))
        #expect(!APIClient.checksPassword(method: "PUT", path: "devices/3f2504e0-4f89-41d3-9a0c-0305e82c3301/name"))
    }

    @Test
    func aBusyServerIsAskedOnceMoreAfterItsShortWait() {
        #expect(APIClient.briefRateLimitWait(method: "POST", path: "auth/login", response: answer(429, retryAfter: "1")) == 1)
        #expect(APIClient.briefRateLimitWait(method: "DELETE", path: "auth/account", response: answer(429, retryAfter: " 2 ")) == 2)
    }

    @Test
    func aSpentBudgetOrAnotherAnswerIsTakenAsItIs() {
        // A login budget names its own, longer wait: that is "Too many tries".
        #expect(APIClient.briefRateLimitWait(method: "POST", path: "auth/login", response: answer(429, retryAfter: "60")) == nil)
        #expect(APIClient.briefRateLimitWait(method: "POST", path: "auth/login", response: answer(429, retryAfter: nil)) == nil)
        #expect(APIClient.briefRateLimitWait(method: "POST", path: "auth/login", response: answer(429, retryAfter: "soon")) == nil)
        #expect(APIClient.briefRateLimitWait(method: "POST", path: "auth/login", response: answer(401, retryAfter: "1")) == nil)
        // Not a password call: its own retry rules apply.
        #expect(APIClient.briefRateLimitWait(method: "POST", path: "messages", response: answer(429, retryAfter: "1")) == nil)
    }
}
