import Foundation
import Testing
@testable import shroud

struct UsernameHashTests {
    @Test func argon2idMatchesThePinnedVector() async throws {
        let params = UsernameKdfParams(
            algorithm: "argon2id",
            version: 19,
            salt: "ABEiM0RVZneImaq7zN3u/w==",
            memoryKiB: 65536,
            iterations: 8,
            parallelism: 1,
            outputBytes: 32
        )
        let digest = try await UsernameHash.argon2idDigest("alice", params: params)
        #expect(digest == "4b4IohXsRZvTapVS+ZJWMkcBP5keMgo8hGWaLN+boI4=")
    }

    @Test func aCheapUsernameHashIsRefused() {
        let params = UsernameKdfParams(
            algorithm: "argon2id",
            version: 19,
            salt: "ABEiM0RVZneImaq7zN3u/w==",
            memoryKiB: 19456,
            iterations: 2,
            parallelism: 1,
            outputBytes: 32
        )
        #expect(throws: APIError.self) {
            try params.validate()
        }
    }
}
