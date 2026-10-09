import Argon2
import CryptoKit
import Foundation

/// The account name, checked here and sent as a slow salted digest. Same rules as `auth/username.rs`.
///
/// `digest` stays SHA-256: it is the legacy login value and the local fingerprint of
/// `username + "." + public key` (`MessagingController`).
enum UsernameHash {
    private static let reservedExact: Set<String> = [
        "admin", "administrator", "support", "help", "shroud", "system", "root", "security",
        "null", "undefined", "api", "www", "mail", "email", "mod", "moderator", "staff",
        "official", "everyone", "all", "me", "self", "owner",
    ]
    private static let reservedPrefixes = ["shroud_", "system_", "admin_", "support_"]

    /// Lowercase `[a-z0-9_]`, 3–32, and not a reserved name.
    static func normalize(_ raw: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (3 ... 32).contains(trimmed.count) else {
            throw APIError.server(
                code: "VALIDATION_ERROR",
                message: "Username must be between 3 and 32 characters.",
                statusCode: 400
            )
        }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_")
        guard trimmed.unicodeScalars.allSatisfy({ allowed.contains($0) }) else {
            throw APIError.server(
                code: "VALIDATION_ERROR",
                message: "Username may only contain letters, digits, and underscores.",
                statusCode: 400
            )
        }
        let folded = trimmed.lowercased()
        if reservedExact.contains(folded) || reservedPrefixes.contains(where: { folded.hasPrefix($0) }) {
            throw APIError.server(
                code: "USERNAME_RESERVED",
                message: "That username is reserved.",
                statusCode: 400
            )
        }
        return folded
    }

    /// Standard Base64 of SHA-256 over the UTF-8 normalized name.
    static func digest(_ normalized: String) -> String {
        let hash = SHA256.hash(data: Data(normalized.utf8))
        return Data(hash).base64EncodedString()
    }

    private static let migrationKey = "shroud.username-kdf"
    private static let weak = "This server's username protection is too weak to sign in."
    private static let cacheLock = NSLock()
    private static var digestCache: [String: String] = [:]
    private static var kdfState: KdfState = .unknown

    private enum KdfState {
        case unknown
        case legacy
        case params(UsernameKdfParams)
    }

    /// The slow digest and, for a login that has not moved this account yet, the SHA-256.
    struct AuthFields: Sendable {
        let usernameHash: String
        let legacyUsernameHash: String?
    }

    /// `includeLegacy` is true for login. A 404 from an old server keeps the SHA-256 login.
    static func authFields(for normalized: String, includeLegacy: Bool) async throws -> AuthFields {
        guard let params = try await currentKdf() else {
            return AuthFields(usernameHash: digest(normalized), legacyUsernameHash: nil)
        }
        let slow = try await argon2idDigest(normalized, params: params)
        let legacy = includeLegacy && !isMigrated(slow) ? digest(normalized) : nil
        return AuthFields(usernameHash: slow, legacyUsernameHash: legacy)
    }

    /// After a successful login or register, stop sending the fast digest.
    static func rememberMigrated(_ digest: String) {
        var list = UserDefaults.standard.stringArray(forKey: migrationKey) ?? []
        if !list.contains(digest) {
            list.append(digest)
            UserDefaults.standard.set(list, forKey: migrationKey)
        }
    }

    /// Argon2id of a normalized name. Cached so a device-limit retry does not hash twice.
    static func argon2idDigest(_ normalized: String, params: UsernameKdfParams) async throws -> String {
        let key = "\(normalized)\n\(params.salt)\n\(params.memoryKiB)\n\(params.iterations)"
        cacheLock.lock()
        if let hit = digestCache[key] {
            cacheLock.unlock()
            return hit
        }
        cacheLock.unlock()
        let salt = Data(base64Encoded: params.salt) ?? Data()
        let password = Data(normalized.utf8)
        let iterations = params.iterations
        let memoryKiB = params.memoryKiB
        let parallelism = params.parallelism
        let outputLength = params.outputBytes
        let output = try await Task.detached(priority: .userInitiated) {
            try Argon2.argon2id(
                password: password,
                salt: salt,
                iterations: iterations,
                memoryKiB: memoryKiB,
                parallelism: parallelism,
                outputLength: outputLength
            )
        }.value
        let digest = output.base64EncodedString()
        cacheLock.lock()
        digestCache[key] = digest
        cacheLock.unlock()
        return digest
    }

    private static func isMigrated(_ digest: String) -> Bool {
        UserDefaults.standard.stringArray(forKey: migrationKey)?.contains(digest) ?? false
    }

    private static func currentKdf() async throws -> UsernameKdfParams? {
        cacheLock.lock()
        let cached = kdfState
        cacheLock.unlock()
        switch cached {
        case .legacy:
            return nil
        case .params(let params):
            return params
        case .unknown:
            break
        }
        let (status, data) = try await APIClient.makeConfiguredClient().response("GET", path: "auth/username-kdf")
        if status == 404 {
            cacheLock.lock()
            kdfState = .legacy
            cacheLock.unlock()
            return nil
        }
        guard (200 ..< 300).contains(status) else {
            throw APIError.from(data: data, statusCode: status)
        }
        let params: UsernameKdfParams
        do {
            params = try JSONDecoder.api.decode(UsernameKdfParams.self, from: data)
        } catch {
            throw APIError.server(code: "VALIDATION_ERROR", message: weak, statusCode: 400)
        }
        try params.validate()
        cacheLock.lock()
        kdfState = .params(params)
        cacheLock.unlock()
        return params
    }
}

struct UsernameKdfParams: Decodable, Sendable {
    let algorithm: String
    let version: Int
    let salt: String
    let memoryKiB: UInt32
    let iterations: UInt32
    let parallelism: UInt32
    let outputBytes: Int

    enum CodingKeys: String, CodingKey {
        case algorithm
        case version
        case salt
        case memoryKiB = "memory_kib"
        case iterations
        case parallelism
        case outputBytes = "output_bytes"
    }

    func validate() throws {
        let saltBytes = Data(base64Encoded: salt) ?? Data()
        guard algorithm == "argon2id",
              version == 19,
              parallelism == 1,
              outputBytes == 32,
              memoryKiB >= 65536,
              iterations >= 8,
              (16 ... 64).contains(saltBytes.count)
        else {
            throw APIError.server(
                code: "VALIDATION_ERROR",
                message: "This server's username protection is too weak to sign in.",
                statusCode: 400
            )
        }
    }
}
