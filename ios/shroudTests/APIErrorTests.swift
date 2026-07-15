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
        #expect(error == .server(code: "not_found", message: "resource missing", statusCode: 404))
    }
}
