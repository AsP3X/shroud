import Foundation
import Testing
@testable import shroud

struct APIErrorTests {
    @Test
    func decodesServerErrorEnvelope() throws {
        let json = """
        {"error":{"code":"not_found","message":"resource missing"}}
        """.data(using: .utf8)!

        let error = APIError.from(data: json, statusCode: 404)
        #expect(error == .server(code: "not_found", message: "resource missing", statusCode: 404, reason: nil))
    }

    @Test
    func authenticationFailureIsOnlyHTTP401() {
        #expect(APIError.server(code: "unauthorized", message: "nope", statusCode: 401, reason: nil).isAuthenticationFailure)
        #expect(!APIError.server(code: "forbidden", message: "nope", statusCode: 403, reason: nil).isAuthenticationFailure)
        #expect(!APIError.server(code: "not_found", message: "nope", statusCode: 404, reason: nil).isAuthenticationFailure)
        #expect(!APIError.transport("The Internet connection appears to be offline.").isAuthenticationFailure)
        #expect(!APIError.decoding.isAuthenticationFailure)
    }

    @Test
    func fromPreservesUnauthorizedWithoutEnvelope() {
        let error = APIError.from(data: Data("plain".utf8), statusCode: 401)
        #expect(error.isAuthenticationFailure)
        #expect(error == .server(code: "unauthorized", message: "Unauthorized", statusCode: 401, reason: nil))
    }

    @Test
    func fromNon401WithoutEnvelopeStaysTransport() {
        let error = APIError.from(data: Data(), statusCode: 502)
        #expect(!error.isAuthenticationFailure)
        #expect(error == .transport("Request failed with status 502"))
    }
}
