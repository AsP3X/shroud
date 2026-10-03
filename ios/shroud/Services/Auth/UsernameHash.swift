import CryptoKit
import Foundation

/// The account name, checked here and sent only as SHA-256. Same rules as `auth/username.rs`.
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
}
