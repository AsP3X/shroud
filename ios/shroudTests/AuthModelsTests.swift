import Foundation
import Testing
@testable import shroud

struct AuthModelsTests {
    @Test
    func authSessionResponseDecodesSnakeCaseFields() throws {
        let json = """
        {
          "token": "opaque-token-value",
          "user": { "id": "11111111-1111-1111-1111-111111111111", "username": "alice" },
          "device": { "id": "22222222-2222-2222-2222-222222222222", "name": "iPhone" }
        }
        """.data(using: .utf8)!

        let decoded = try JSONDecoder.api.decode(AuthSessionResponse.self, from: json)
        #expect(decoded.token == "opaque-token-value")
        #expect(decoded.user.username == "alice")
        #expect(decoded.device.name == "iPhone")
        #expect(decoded.user.id.uuidString.lowercased() == "11111111-1111-1111-1111-111111111111")
    }

    @Test
    func apiErrorEnvelopeDecodesServerCodes() throws {
        let json = """
        {
          "error": { "code": "USERNAME_TAKEN", "message": "That username is already taken." }
        }
        """.data(using: .utf8)!

        let error = APIError.from(data: json, statusCode: 409)
        #expect(error == .server(
            code: "USERNAME_TAKEN",
            message: "That username is already taken.",
            statusCode: 409
        ))
    }
}
